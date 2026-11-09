package org.koitharu.kotatsu.parsers.site.en

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.ContentType
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
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.json.asTypedList
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.json.mapJSON
import org.koitharu.kotatsu.parsers.util.json.mapJSONNotNull
import org.koitharu.kotatsu.parsers.util.json.mapJSONNotNullToSet
import org.koitharu.kotatsu.parsers.util.mapChapters
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.oneOrThrowIfMany
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.parseRaw
import org.koitharu.kotatsu.parsers.util.parseSafe
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.toTitleCase
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale
import java.util.TimeZone

/**
 * Elf Toon moved from elftoon.com (MangaThemesia) to elftoon.net, a Next.js site (keiyoushi's VineTheme).
 * Lists come from its JSON API; details, chapters and pages from the React Server Components payload
 * of the series and chapter pages. Ids are still generated from the old MangaThemesia paths
 * (`/manga/<slug>/`, `/<slug>-chapter-<n>/`, which the site redirects) so library and history survive the move.
 */
@MangaSourceParser("ELFTOON", "Elftoon", "en")
internal class Elftoon(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.ELFTOON, 24) {

	override val configKeyDomain = ConfigKey.Domain("elftoon.net")

	private val rscHeaders = Headers.headersOf("rsc", "1")

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.RATING,
		SortOrder.NEWEST,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isMultipleTagsSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchTags(),
		availableStates = EnumSet.of(
			MangaState.ONGOING,
			MangaState.FINISHED,
			MangaState.PAUSED,
			MangaState.ABANDONED,
			MangaState.UPCOMING,
		),
		availableContentTypes = EnumSet.of(
			ContentType.MANHWA,
			ContentType.MANHUA,
			ContentType.MANGA,
		),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = "https://$domain/api/series".toHttpUrl().newBuilder()
			.addQueryParameter("contentMode", "comics")
			.addQueryParameter("page", page.toString())
			.addQueryParameter("limit", pageSize.toString())
			.addQueryParameter(
				"sort",
				when (order) {
					SortOrder.POPULARITY -> "views"
					SortOrder.RATING -> "rating"
					SortOrder.NEWEST -> "newest"
					else -> "updated"
				},
			)
		filter.query?.trim()?.nullIfEmpty()?.let {
			url.addQueryParameter("q", it)
		}
		if (filter.tags.isNotEmpty()) {
			// Several genres are combined with AND by the site
			url.addQueryParameter("genre", filter.tags.joinToString(",") { it.key })
		}
		filter.states.oneOrThrowIfMany()?.let { state ->
			url.addQueryParameter(
				"status",
				when (state) {
					MangaState.ONGOING -> "Ongoing"
					MangaState.FINISHED -> "Completed"
					MangaState.PAUSED -> "Hiatus"
					MangaState.ABANDONED -> "Dropped"
					MangaState.UPCOMING -> "Upcoming"
					else -> return@let
				},
			)
		}
		filter.types.oneOrThrowIfMany()?.let { type ->
			url.addQueryParameter(
				"type",
				when (type) {
					ContentType.MANHWA -> "MANHWA"
					ContentType.MANHUA -> "MANHUA"
					ContentType.MANGA -> "MANGA"
					else -> return@let
				},
			)
		}
		val data = webClient.httpGet(url.build()).parseJson().getJSONArray("data")
		return data.mapJSON { parseManga(it) }
	}

	override suspend fun getDetails(manga: Manga): Manga {
		// Library entries saved before the move still hold "/manga/<slug>/"
		val slug = manga.url.removeSuffix("/").substringAfterLast('/')
		val seriesUrl = "https://$domain/series/comic/$slug"
		val first = fetchSeriesPage(seriesUrl, 1)
		val series = first.getJSONObject("series")
		val chapters = first.getJSONArray("chapters").asTypedList<JSONObject>().toMutableList()
		for (page in 2..first.optInt("totalPages", 1)) {
			chapters += fetchSeriesPage(seriesUrl, page).getJSONArray("chapters").asTypedList<JSONObject>()
		}
		val tags = series.optJSONArray("genres")?.let { parseTags(it) }
		return manga.copy(
			title = series.getStringOrNull("title") ?: manga.title,
			altTitles = buildSet {
				series.getStringOrNull("altTitle")?.let(::add)
				series.getStringOrNull("originalTitle")?.let(::add)
				series.optJSONArray("aliases")?.let { aliases ->
					for (i in 0 until aliases.length()) {
						aliases.optString(i).trim().nullIfEmpty()?.let(::add)
					}
				}
				remove(series.optString("title"))
			},
			publicUrl = seriesUrl,
			coverUrl = series.getStringOrNull("coverImage")?.toAbsoluteUrl(domain) ?: manga.coverUrl,
			description = series.getStringOrNull("description")?.takeUnless { it.startsWith('$') },
			tags = if (tags.isNullOrEmpty()) manga.tags else tags,
			state = parseState(series.optString("status")) ?: manga.state,
			rating = parseRating(series),
			contentRating = if (series.optBoolean("isMature")) ContentRating.ADULT else ContentRating.SAFE,
			chapters = parseChapters(slug, chapters),
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val url = chapter.url.toAbsoluteUrl(domain)
		val data = fetchRscObject(url, "\"pages\"") { it.optJSONObject("chapter")?.has("pages") == true }
			?: throw ParseException("Chapter data not found", url)
		val pages = data.getJSONObject("chapter").getJSONArray("pages").mapJSONNotNull { page ->
			if (page.optBoolean("isRedacted")) {
				return@mapJSONNotNull null
			}
			val imageUrl = page.getStringOrNull("imageUrl")?.toAbsoluteUrl(domain) ?: return@mapJSONNotNull null
			MangaPage(
				id = generateUid(imageUrl),
				url = imageUrl,
				preview = null,
				source = source,
			)
		}
		if (pages.isEmpty() && data.optBoolean("isLocked")) {
			throw ParseException("This chapter is locked and requires coins to read", url)
		}
		return pages
	}

	private suspend fun fetchSeriesPage(seriesUrl: String, page: Int): JSONObject {
		val url = "$seriesUrl?sort=desc&page=$page"
		return fetchRscObject(url, "\"totalPages\"") { it.has("series") && it.has("chapters") }
			?: throw ParseException("Series data not found", url)
	}

	/**
	 * Requests the React Server Components payload of a page (one `<id>:<json>` row per line)
	 * and returns the first object matching [predicate] in the rows containing [marker].
	 */
	private suspend fun fetchRscObject(
		url: String,
		marker: String,
		predicate: (JSONObject) -> Boolean,
	): JSONObject? {
		val body = webClient.httpGet(url, rscHeaders).parseRaw()
		for (line in body.lineSequence()) {
			if (marker !in line) {
				continue
			}
			val payload = line.substringAfter(':', "")
			if (!payload.startsWith('{') && !payload.startsWith('[')) {
				continue
			}
			val root = runCatching { JSONTokener(payload).nextValue() }.getOrNull() ?: continue
			findObject(root, predicate)?.let { return it }
		}
		return null
	}

	private fun findObject(value: Any?, predicate: (JSONObject) -> Boolean): JSONObject? {
		when (value) {
			is JSONObject -> {
				if (predicate(value)) {
					return value
				}
				for (key in value.keys()) {
					findObject(value.opt(key), predicate)?.let { return it }
				}
			}

			is JSONArray -> for (i in 0 until value.length()) {
				findObject(value.opt(i), predicate)?.let { return it }
			}
		}
		return null
	}

	private fun parseManga(json: JSONObject): Manga {
		val slug = json.getStringOrNull("urlSlug") ?: json.getString("slug")
		val url = "/series/comic/$slug"
		return Manga(
			id = generateUid("/manga/$slug/"),
			title = json.getString("title"),
			altTitles = emptySet(),
			url = url,
			publicUrl = url.toAbsoluteUrl(domain),
			rating = parseRating(json),
			contentRating = if (json.optBoolean("isMature")) ContentRating.ADULT else ContentRating.SAFE,
			coverUrl = json.getStringOrNull("coverImage")?.toAbsoluteUrl(domain),
			tags = json.optJSONArray("genres")?.let { parseTags(it) }.orEmpty(),
			state = parseState(json.optString("status")),
			authors = emptySet(),
			source = source,
		)
	}

	private fun parseChapters(slug: String, items: List<JSONObject>): List<MangaChapter> {
		val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
			timeZone = TimeZone.getTimeZone("UTC")
		}
		// Locked chapters need coins; their pages are redacted
		return items.filterNot { it.optBoolean("isLocked") }
			.sortedBy { it.optDouble("number", 0.0) }
			.mapChapters { _, json ->
				val number = json.optDouble("number", 0.0)
				val numberText = if (number % 1.0 == 0.0) number.toLong().toString() else number.toString()
				MangaChapter(
					id = generateUid("/$slug-chapter-${numberText.replace('.', '-')}/"),
					title = json.getStringOrNull("title")?.takeUnless {
						it == numberText || it.equals("Chapter $numberText", ignoreCase = true)
					},
					number = number.toFloat(),
					volume = 0,
					url = "/series/comic/$slug/chapter/$numberText",
					scanlator = null,
					uploadDate = dateFormat.parseSafe(json.getStringOrNull("publishedAt")?.removePrefix("\$D")),
					branch = null,
					source = source,
				)
			}
	}

	private suspend fun fetchTags(): Set<MangaTag> {
		val genres = webClient.httpGet("https://$domain/api/genres").parseJson().getJSONArray("genres")
		return parseTags(genres)
	}

	private fun parseTags(genres: JSONArray): Set<MangaTag> = genres.mapJSONNotNullToSet { item ->
		// List items only carry {"genre": {"slug": ...}}
		val genre = item.optJSONObject("genre") ?: item
		val key = genre.getStringOrNull("slug") ?: return@mapJSONNotNullToSet null
		MangaTag(
			key = key,
			title = genre.getStringOrNull("name") ?: key.replace('-', ' ').toTitleCase(sourceLocale),
			source = source,
		)
	}

	private fun parseState(status: String): MangaState? = when (status.uppercase(Locale.ROOT)) {
		"ONGOING" -> MangaState.ONGOING
		"COMPLETED" -> MangaState.FINISHED
		"HIATUS" -> MangaState.PAUSED
		"DROPPED", "DISCONTINUED", "CANCELLED" -> MangaState.ABANDONED
		"UPCOMING" -> MangaState.UPCOMING
		else -> null
	}

	private fun parseRating(json: JSONObject): Float {
		val rating = json.optDouble("rating", 0.0)
		return if (rating > 0.0) (rating / 10.0).toFloat().coerceAtMost(1f) else RATING_UNKNOWN
	}
}
