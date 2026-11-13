package org.koitharu.kotatsu.parsers.site.ar

import kotlinx.coroutines.delay
import okhttp3.Headers
import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.HttpStatusException
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.TooManyRequestExceptions
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.network.UserAgents
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * ArabToons (arabtoons.net): a JSON API under `/api/`. The site used to be a Madara theme.
 *
 * Titles are addressed by slug (can be Arabic), but their chapter list is by numeric id, which only
 * the details response gives. A chapter is addressed by `<manga slug>/<chapter slug>`.
 *
 * The new site kept the slugs of the old one, so [Manga.url] and [MangaChapter.url] keep the form the
 * Madara parser gave them (see [mangaUrl]): titles and chapters saved back then are still the same ones.
 */
@MangaSourceParser("ARABTOONS", "ArabToons", "ar", ContentType.HENTAI)
internal class ArabToons(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.ARABTOONS, pageSize = 24) {

	override val configKeyDomain = ConfigKey.Domain("arabtoons.net")

	override val userAgentKey = ConfigKey.UserAgent(UserAgents.CHROME_DESKTOP)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
		.set("Referer", "https://$domain/")
		.build()

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.NEWEST,
		SortOrder.RATING,
		SortOrder.POPULARITY,
		SortOrder.POPULARITY_MONTH,
		SortOrder.POPULARITY_WEEK,
		SortOrder.ALPHABETICAL,
		SortOrder.ALPHABETICAL_DESC,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = true,
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
		)

	override suspend fun getFilterOptions(): MangaListFilterOptions {
		val genres = apiGet("browse/genres").getJSONArray("data").mapJSON {
			MangaTag(title = it.getString("label"), key = it.get("value").toString(), source = source)
		}
		return MangaListFilterOptions(
			availableTags = genres.toSet(),
			availableStates = EnumSet.of(
				MangaState.ONGOING,
				MangaState.FINISHED,
				MangaState.PAUSED,
				MangaState.ABANDONED,
			),
			availableContentTypes = EnumSet.of(ContentType.MANGA, ContentType.MANHWA, ContentType.COMICS),
		)
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = apiUrl("browse").newBuilder().apply {
			addQueryParameter("page", page.toString())
			filter.query?.takeIf { it.isNotBlank() }?.let { addQueryParameter("keyword", it.trim()) }
			addQueryParameter(
				"sort",
				when (order) {
					SortOrder.UPDATED -> "last_chapter_published_at"
					SortOrder.NEWEST -> "created_at"
					SortOrder.RATING -> "average_rating"
					SortOrder.POPULARITY -> "views_count"
					SortOrder.POPULARITY_MONTH -> "views_30d"
					SortOrder.POPULARITY_WEEK -> "views_7d"
					SortOrder.ALPHABETICAL, SortOrder.ALPHABETICAL_DESC -> "title"
					else -> "last_chapter_published_at"
				},
			)
			// The API sorts descending unless told otherwise.
			if (order == SortOrder.ALPHABETICAL) addQueryParameter("direction", "asc")
			if (filter.tags.isNotEmpty()) addQueryParameter("genres", filter.tags.joinToString(",") { it.key })
			if (filter.tagsExclude.isNotEmpty()) {
				addQueryParameter("genres_ex", filter.tagsExclude.joinToString(",") { it.key })
			}
			if (filter.states.isNotEmpty()) {
				addQueryParameter(
					"status",
					filter.states.mapNotNull {
						when (it) {
							MangaState.ONGOING -> 1
							MangaState.FINISHED -> 2
							MangaState.ABANDONED -> 3
							MangaState.PAUSED -> 4
							else -> null
						}
					}.joinToString(","),
				)
			}
			if (filter.types.isNotEmpty()) {
				addQueryParameter(
					"type",
					filter.types.mapNotNull {
						when (it) {
							ContentType.MANGA -> 1
							ContentType.MANHWA -> 2
							ContentType.COMICS -> 3
							else -> null
						}
					}.joinToString(","),
				)
			}
		}.build()
		val items = apiGet(url).getJSONObject("data").getJSONArray("items")
		return items.mapJSON { parseManga(it) }
	}

	private fun parseManga(jo: JSONObject): Manga {
		val slug = jo.getString("slug")
		val url = mangaUrl(slug)
		return Manga(
			id = generateUid(url),
			title = jo.getString("title"),
			altTitles = emptySet(),
			url = url,
			publicUrl = publicUrl(slug),
			rating = jo.optDouble("rating", -1.0).toFloat().takeIf { it > 0f }?.div(10f) ?: RATING_UNKNOWN,
			contentRating = ContentRating.ADULT,
			coverUrl = jo.getStringOrNull("cover")?.let(::coverUrl),
			tags = jo.optJSONArray("genres").toGenreTags(),
			state = parseState(jo.optInt("status", 0)),
			authors = emptySet(),
			source = source,
		)
	}

	// The list gives genres as plain names, the details as objects. Neither is usable as a filter key
	// (that is the numeric id), so only details' tags carry a key.
	private fun JSONArray?.toGenreTags(): Set<MangaTag> {
		if (this == null) return emptySet()
		val result = LinkedHashSet<MangaTag>(length())
		for (i in 0 until length()) {
			when (val item = opt(i)) {
				is JSONObject -> result += MangaTag(item.getString("name"), item.get("id").toString(), source)
				is String -> Unit
			}
		}
		return result
	}

	private fun parseState(status: Int) = when (status) {
		1 -> MangaState.ONGOING
		2 -> MangaState.FINISHED
		3 -> MangaState.ABANDONED
		4 -> MangaState.PAUSED
		else -> null
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val slug = slugOf(manga.url)
		val details = apiGet("manga/$slug").getJSONObject("data").getJSONObject("mangaDetails")
		val id = details.getInt("id")
		val chapterItems = apiGet("manga/$id/chapters").getJSONObject("data").getJSONArray("items")
		// "2026-11-03T21:10:30.000000Z": read to the second, since SimpleDateFormat takes six digits of
		// fraction for a count of milliseconds.
		val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ENGLISH).apply {
			timeZone = TimeZone.getTimeZone("UTC")
		}
		val url = mangaUrl(slug)
		val cover = details.getStringOrNull("cover")?.let(::coverUrl)
		return manga.copy(
			title = details.getString("title"),
			altTitles = setOfNotNull(details.getStringOrNull("alternativeTitle")),
			url = url,
			publicUrl = publicUrl(slug),
			rating = details.optDouble("rating", -1.0).toFloat().takeIf { it > 0f }?.div(10f) ?: RATING_UNKNOWN,
			coverUrl = cover ?: manga.coverUrl,
			largeCoverUrl = cover,
			tags = details.optJSONArray("genres").toGenreTags(),
			state = parseState(details.optInt("status", 0)),
			authors = setOfNotNull(details.getStringOrNull("author"), details.getStringOrNull("artist")),
			description = details.getStringOrNull("description"),
			// The API lists the newest chapter first.
			chapters = chapterItems.mapChapters(reversed = true) { _, jo ->
				val chapterSlug = jo.getString("slug")
				val number = jo.optDouble("number", 0.0).toFloat()
				val chapterUrl = "$url${chapterSlug.wpEncoded()}/"
				MangaChapter(
					id = generateUid(chapterUrl),
					title = jo.getStringOrNull("title"),
					number = number,
					volume = 0,
					url = chapterUrl,
					scanlator = null,
					uploadDate = dateFormat.parseSafe(jo.getStringOrNull("date")?.take(19)),
					branch = null,
					source = source,
				)
			},
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		// chapter.url is "/manga/<manga slug>/<chapter slug>/", percent-encoded; the Madara parser's also
		// ended in "?style=list".
		val path = chapter.url.substringBefore('?').trim('/').split('/').joinToString("/") { it.urlDecode() }
		val data = apiGet(path).getJSONObject("data")
		val mangaDir = data.getString("mangaDir")
		val chapterDir = data.getString("chapterDir")
		return data.getJSONArray("images").mapJSON { image ->
			val imageUrl = HttpUrl.Builder().scheme("https").host(domain)
				.addPathSegments("storage/mangas")
				.addPathSegment(mangaDir)
				.addPathSegment(chapterDir)
				.addPathSegment(image.getString("name"))
				.build().toString()
			MangaPage(
				id = generateUid(imageUrl),
				url = imageUrl,
				preview = null,
				source = source,
			)
		}
	}

	/**
	 * The address the Madara site gave a title, which readers' libraries are kept under: the slug
	 * percent-encoded the way WordPress writes it (lower case) and a closing slash. Chapters follow suit.
	 */
	private fun mangaUrl(slug: String): String = "/manga/${slug.wpEncoded()}/"

	private fun String.wpEncoded(): String = urlEncoded().replace(percentEscape) { it.value.lowercase(Locale.ROOT) }

	private fun slugOf(mangaUrl: String): String =
		mangaUrl.removePrefix("/manga/").trim('/').substringBefore('/').urlDecode()

	private fun coverUrl(cover: String) = "https://$domain/storage/covers/lg/$cover"

	// The slug can be Arabic, so it has to be percent-encoded to give a link that opens in a browser.
	private fun publicUrl(slug: String): String = HttpUrl.Builder().scheme("https").host(domain)
		.addPathSegment("manga")
		.addPathSegment(slug)
		.build().toString()

	private fun apiUrl(path: String): HttpUrl = HttpUrl.Builder().scheme("https").host(domain)
		.addPathSegment("api")
		.apply { path.split('/').forEach(::addPathSegment) }
		.build()

	private suspend fun apiGet(path: String): JSONObject = apiGet(apiUrl(path))

	// Headers a browser's own fetch sends to this API (the reference extension does the same).
	// The site answers 429 to a quick succession of requests (such as applying several filters in a row),
	// so back off and retry instead of failing the screen.
	private suspend fun apiGet(url: HttpUrl): JSONObject {
		val headers = Headers.headersOf("X-Requested-With", "XMLHttpRequest", "Accept", "application/json")
		var attempt = 0
		while (true) {
			try {
				return webClient.httpGet(url, headers).parseJson()
			} catch (e: HttpStatusException) {
				if (e.statusCode != 429 || ++attempt > MAX_RETRIES) throw e
				delay(RETRY_DELAY_MS * attempt)
			} catch (e: TooManyRequestExceptions) {
				if (++attempt > MAX_RETRIES) throw e
				delay(e.getRetryDelay().takeIf { it > 0L } ?: (RETRY_DELAY_MS * attempt))
			}
		}
	}

	private companion object {
		const val MAX_RETRIES = 4
		const val RETRY_DELAY_MS = 3_000L

		val percentEscape = Regex("%[0-9A-F]{2}")
	}
}
