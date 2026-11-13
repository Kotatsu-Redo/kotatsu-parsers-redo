package org.koitharu.kotatsu.parsers.site.en

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.LinkResolver
import org.koitharu.kotatsu.parsers.util.formatSimple
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.json.asTypedList
import org.koitharu.kotatsu.parsers.util.json.getFloatOrDefault
import org.koitharu.kotatsu.parsers.util.json.getIntOrDefault
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.json.mapJSON
import org.koitharu.kotatsu.parsers.util.json.mapJSONNotNull
import org.koitharu.kotatsu.parsers.util.json.mapJSONNotNullToSet
import org.koitharu.kotatsu.parsers.util.mapNotNullToSet
import org.koitharu.kotatsu.parsers.util.nl2br
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.oneOrThrowIfMany
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.parseSafe
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.toTitleCase
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale
import java.util.TimeZone

/**
 * The site at arvencomics.com is gone. This is the one that carries the name now: a site of its own making, with other
 * titles, which gives everything through an API.
 */
@MangaSourceParser("ARVENSCANS", "ArvenScans", "en")
internal class ArvenScans(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.ARVENSCANS, pageSize = 24) {

	override val configKeyDomain = ConfigKey.Domain("arvenscans.net")

	private val apiDomain: String
		get() = "api.$domain"

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.NEWEST,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
		)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override suspend fun getFilterOptions(): MangaListFilterOptions {
		val genres = webClient.httpGet(api("genres").build()).parseJson().getJSONArray("data")
		return MangaListFilterOptions(
			// The site keeps genres that no title has yet, and leaves them out of its own list too
			availableTags = genres.mapJSONNotNullToSet { jo ->
				if (jo.getIntOrDefault("work_count", 0) > 0) parseGenre(jo) else null
			},
		)
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val query = filter.query?.trim().orEmpty()
		val genre = filter.tags.oneOrThrowIfMany()
		val url = when {
			// Looks in titles and authors
			query.isNotEmpty() -> worksUrl(page, order).addQueryParameter("search", query)

			// A genre has a list of its own, which takes no sort order and comes newest first
			genre != null -> api("genres", genre.key, "works").addPage(page)

			// The most read are one list of twenty at most
			order == SortOrder.POPULARITY -> {
				if (page > 1) return emptyList()
				api("works", "most-read").addQueryParameter("limit", "20")
			}

			else -> worksUrl(page, order)
		}
		return webClient.httpGet(url.build()).parseJson().getJSONArray("data").mapJSON(::parseWork)
	}

	// Newest first unless asked for the latest updated
	private fun worksUrl(page: Int, order: SortOrder): HttpUrl.Builder = api("works").addPage(page).apply {
		if (order == SortOrder.UPDATED) addQueryParameter("sort", "updated")
	}

	override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
		val workId = workIdOf(manga.url)
		val work = async { webClient.httpGet(api("works", workId).build()).parseJson() }
		// A list gives three genres of a title at most, this gives them all
		val genres = async { webClient.httpGet(api("works", workId, "genres").build()).parseJson().optJSONArray("data") }
		val chapters = async { loadChapters(workId) }
		// A title saved from the old site keeps the id and the address it was saved with
		parseWork(work.await(), genres.await()).copy(id = manga.id, url = manga.url, chapters = chapters.await())
	}

	// Oldest first, a hundred at a time
	private suspend fun loadChapters(workId: String): List<MangaChapter> = coroutineScope {
		val first = loadChaptersPage(workId, 1)
		val totalPages = first.optJSONObject("meta")?.getIntOrDefault("total_pages", 1) ?: 1
		val rest = (2..totalPages).map { page -> async { loadChaptersPage(workId, page) } }.awaitAll()
		val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT)
		dateFormat.timeZone = TimeZone.getTimeZone("UTC")
		(listOf(first) + rest).flatMap { json ->
			json.getJSONArray("data").mapJSONNotNull { parseChapter(it, dateFormat) }
		}
	}

	private suspend fun loadChaptersPage(workId: String, page: Int): JSONObject {
		val url = api("works", workId, "chapters")
			.addQueryParameter("page", page.toString())
			.addQueryParameter("limit", "100")
		return webClient.httpGet(url.build()).parseJson()
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val images = webClient.httpGet(api("chapters", chapter.url).build()).parseJson()
			.optJSONArray("images")?.asTypedList<String>().orEmpty()
		return images.mapIndexed { index, path ->
			MangaPage(
				id = generateUid("${chapter.url}/$index"),
				url = path.toAbsoluteUrl(apiDomain),
				preview = null,
				source = source,
			)
		}
	}

	override suspend fun resolveLink(resolver: LinkResolver, link: HttpUrl): Manga? {
		val name = link.pathSegments.getOrNull(1)?.nullIfEmpty() ?: return null
		val workId = when (link.pathSegments.first()) {
			"series" -> resolveWorkId(name)
			"reader" -> webClient.httpGet(api("chapters", name).build()).parseJson().getString("work_id")
			else -> return null
		}
		return resolver.resolveManga(this, url = workId)
	}

	// A title is saved by its id. One saved from the old site ("/comic/slug/") is known by its slug only, and is still
	// found when the site has a title of that slug.
	private suspend fun workIdOf(url: String): String =
		if ('/' in url) resolveWorkId(url.trim('/').substringAfterLast('/')) else url

	private suspend fun resolveWorkId(slug: String): String =
		webClient.httpGet(api("resolve", "work", slug).build()).parseJson().getString("work_id")

	private fun parseWork(jo: JSONObject): Manga = parseWork(jo, jo.optJSONArray("genres"))

	private fun parseWork(jo: JSONObject, genres: JSONArray?): Manga {
		val id = jo.getString("id")
		val cover = jo.getStringOrNull("cover_url")?.nullIfEmpty()?.toAbsoluteUrl(apiDomain)
		return Manga(
			id = generateUid(id),
			title = jo.getString("title").trim(),
			altTitles = jo.optJSONArray("alt_titles")?.asTypedList<String>()
				?.mapNotNullToSet { it.trim().nullIfEmpty() }.orEmpty(),
			url = id,
			publicUrl = "https://$domain/series/${jo.getStringOrNull("slug") ?: id}",
			rating = RATING_UNKNOWN,
			contentRating = null,
			// The site makes its covers smaller on request, in a few set widths
			coverUrl = if (cover != null && COVERS_PATH in cover) "$cover?w=$COVER_WIDTH" else cover,
			largeCoverUrl = cover,
			tags = genres?.mapJSONNotNullToSet(::parseGenre).orEmpty(),
			state = when (jo.getStringOrNull("status")) {
				"ongoing" -> MangaState.ONGOING
				"completed" -> MangaState.FINISHED
				"hiatus" -> MangaState.PAUSED
				else -> null
			},
			// Several in one text, and "updating" where the site doesn't know yet
			authors = jo.getStringOrNull("author")?.split(',', '/')
				?.mapNotNullToSet { name -> name.trim().takeUnless { it.isEmpty() || it.equals("updating", true) } }
				.orEmpty(),
			// Plain text, where "<" and ">" are written as they are
			description = jo.getStringOrNull("description")?.trim()
				?.replace("&", "&amp;")?.replace("<", "&lt;")?.replace(">", "&gt;")?.nl2br(),
			source = source,
		)
	}

	private fun parseGenre(jo: JSONObject): MangaTag? {
		return MangaTag(
			key = jo.getStringOrNull("slug")?.nullIfEmpty() ?: return null,
			title = jo.getStringOrNull("name_en")?.nullIfEmpty()?.toTitleCase(sourceLocale) ?: return null,
			source = source,
		)
	}

	private fun parseChapter(jo: JSONObject, dateFormat: SimpleDateFormat): MangaChapter? {
		// A chapter that costs points on the site can't be read here
		if (jo.getIntOrDefault("coins", 0) > 0) return null
		val id = jo.getString("id")
		val number = jo.getFloatOrDefault("chapter_number", 0f)
		val title = jo.getStringOrNull("title")?.trim()?.nullIfEmpty()
		return MangaChapter(
			id = generateUid(id),
			title = if (title == null) "Chapter ${number.formatSimple()}" else "Chapter ${number.formatSimple()}: $title",
			number = number,
			volume = 0,
			url = id,
			scanlator = null,
			// Down to the microsecond, which the format can't read
			uploadDate = dateFormat.parseSafe(jo.getStringOrNull("created_at")?.substringBefore('.')),
			branch = null,
			source = source,
		)
	}

	private fun api(vararg path: String): HttpUrl.Builder {
		val builder = "https://$apiDomain".toHttpUrl().newBuilder()
		path.forEach(builder::addPathSegment)
		return builder
	}

	private fun HttpUrl.Builder.addPage(page: Int): HttpUrl.Builder = addQueryParameter("page", page.toString())
		.addQueryParameter("limit", pageSize.toString())

	private companion object {
		const val COVERS_PATH = "/uploads/covers/"
		const val COVER_WIDTH = 320
	}
}
