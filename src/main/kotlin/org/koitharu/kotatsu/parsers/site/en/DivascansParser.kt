package org.koitharu.kotatsu.parsers.site.en

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
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
import org.koitharu.kotatsu.parsers.util.mapChapters
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.util.mapToSet
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.oneOrThrowIfMany
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.toTitleCase
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale
import java.util.TimeZone

/**
 * Parser for [DivaScans](https://divascans.org/).
 *
 * List/search/genres use the public JSON API; details (full chapter list) and
 * pages are scraped from the Next.js HTML responses.
 */
@MangaSourceParser("DIVASCANS", "DivaScans", "en", type = ContentType.MANHWA)
internal class DivascansParser(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.DIVASCANS, 20) {

	override val configKeyDomain = ConfigKey.Domain("divascans.org")

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.NEWEST,
		SortOrder.ALPHABETICAL,
		SortOrder.RATING,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isMultipleTagsSupported = false,
			// API has no exclude-genre param — applied client-side after fetch.
			isTagsExclusionSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchAvailableTags(),
		availableStates = EnumSet.of(
			MangaState.ONGOING,
			MangaState.FINISHED,
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
		val query = filter.query?.trim().orEmpty()
		if (query.isNotEmpty()) {
			return applyLocalFilters(searchSeries(query, page), filter)
		}

		val url = "https://$domain/api/series".toHttpUrl().newBuilder()
			.addQueryParameter("page", page.toString())
			.addQueryParameter("limit", pageSize.toString())
			.apply {
				filter.tags.oneOrThrowIfMany()?.let { tag ->
					addQueryParameter("genre", tag.key)
				}
				filter.states.oneOrThrowIfMany()?.let { state ->
					addQueryParameter(
						"status",
						when (state) {
							MangaState.ONGOING -> "ONGOING"
							MangaState.FINISHED -> "COMPLETED"
							MangaState.ABANDONED -> "DROPPED"
							MangaState.UPCOMING -> "COMING_SOON"
							else -> return@let
						},
					)
				}
				filter.types.oneOrThrowIfMany()?.let { type ->
					addQueryParameter(
						"type",
						when (type) {
							ContentType.MANHWA -> "MANHWA"
							ContentType.MANHUA -> "MANHUA"
							ContentType.MANGA -> "MANGA"
							else -> return@let
						},
					)
				}
				// Best-effort sort params — API may ignore unknown ones.
				when (order) {
					SortOrder.POPULARITY -> {
						addQueryParameter("sortBy", "viewCount")
						addQueryParameter("sortOrder", "desc")
					}
					SortOrder.RATING -> {
						addQueryParameter("sortBy", "rating")
						addQueryParameter("sortOrder", "desc")
					}
					SortOrder.NEWEST -> {
						addQueryParameter("sortBy", "createdAt")
						addQueryParameter("sortOrder", "desc")
					}
					SortOrder.ALPHABETICAL -> {
						addQueryParameter("sortBy", "title")
						addQueryParameter("sortOrder", "asc")
					}
					else -> {
						addQueryParameter("sortBy", "updatedAt")
						addQueryParameter("sortOrder", "desc")
					}
				}
			}
			.build()

		val json = webClient.httpGet(url).parseJson()
		val data = json.optJSONArray("data") ?: return emptyList()
		return applyLocalFilters(parseSeriesArray(data), filter)
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val slug = extractSlug(manga.url)
		val typePath = extractTypePath(manga.url)
		val pageUrl = "https://$domain/series/$typePath/$slug"
		val html = webClient.httpGet(pageUrl).parseHtml().outerHtml()
		val chapters = parseChaptersFromHtml(html, typePath, slug)
		val description = extractDescription(html) ?: manga.description
		val author = extractAuthor(html)
		val isMature = html.contains("\"isMature\":true") || html.contains("\\\"isMature\\\":true")

		return manga.copy(
			description = description,
			authors = author?.let { setOf(it) } ?: manga.authors,
			contentRating = when {
				isMature || manga.contentRating == ContentRating.ADULT -> ContentRating.ADULT
				else -> manga.contentRating
			},
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = chapter.url.toAbsoluteUrl(domain)
		val html = webClient.httpGet(fullUrl).parseHtml().outerHtml()
		val pageUrls = extractPageUrls(html)
		if (pageUrls.isEmpty()) {
			// Locked / premium chapters often render without page images.
			if (
				html.contains("\"isLocked\":true") ||
				html.contains("\\\"isLocked\\\":true")
			) {
				throw ParseException("Chapter is locked on DivaScans", fullUrl)
			}
			throw ParseException("No pages found", fullUrl)
		}
		return pageUrls.map { url ->
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}

	/**
	 * DivaScans hosts chapter images in two formats depending on series age/uploader:
	 * 1. `.../p-{uuid}.webp` — full pages (`s-*.webp` are thumbs, ignored)
	 * 2. `.../chapters/{n}/{###}.webp` — sequential numbered pages
	 */
	private fun extractPageUrls(html: String): List<String> {
		val uuidPages = PAGE_UUID_REGEX.findAll(html)
			.map { it.value }
			.distinct()
			.toList()
		if (uuidPages.isNotEmpty()) {
			return uuidPages
		}
		return PAGE_NUMBERED_REGEX.findAll(html)
			.map { it.value }
			.distinct()
			.sortedBy { url ->
				PAGE_INDEX_REGEX.find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 0
			}
			.toList()
	}

	/** Client-side filters the API does not honour (exclude tags). */
	private fun applyLocalFilters(list: List<Manga>, filter: MangaListFilter): List<Manga> {
		if (filter.tagsExclude.isEmpty()) return list
		val excludeKeys = filter.tagsExclude.mapToSet { it.key }
		return list.filter { manga ->
			manga.tags.none { tag -> tag.key in excludeKeys }
		}
	}

	private suspend fun searchSeries(query: String, page: Int): List<Manga> {
		val url = "https://$domain/api/search".toHttpUrl().newBuilder()
			.addQueryParameter("q", query)
			.addQueryParameter("page", page.toString())
			.build()
		val json = webClient.httpGet(url).parseJson()
		val series = json.optJSONArray("series") ?: return emptyList()
		return parseSeriesArray(series)
	}

	private fun parseSeriesArray(data: JSONArray): List<Manga> {
		val result = ArrayList<Manga>(data.length())
		for (i in 0 until data.length()) {
			val item = data.optJSONObject(i) ?: continue
			parseSeriesItem(item)?.let(result::add)
		}
		return result
	}

	private fun parseSeriesItem(item: JSONObject): Manga? {
		val slug = item.optString("urlSlug").nullIfEmpty()
			?: item.optString("slug").nullIfEmpty()
			?: return null
		val title = item.optString("title").nullIfEmpty() ?: return null
		val type = item.optString("type").ifEmpty { "MANHWA" }
		val typePath = typeToPath(type)
		val mangaUrl = "/series/$typePath/$slug"
		val cover = item.optString("coverImage").nullIfEmpty()?.let { absUrl(it) }
		val rating = item.optDouble("rating", Double.NaN).let { value ->
			if (value.isNaN() || value <= 0.0) RATING_UNKNOWN else (value / 10.0).toFloat().coerceIn(0f, 1f)
		}
		val isMature = item.optBoolean("isMature", false) ||
			item.optJSONArray("nsfwGenreSlugs")?.length()?.let { it > 0 } == true

		val tags = buildSet {
			val genres = item.optJSONArray("genres")
			if (genres != null) {
				for (i in 0 until genres.length()) {
					val g = genres.optJSONObject(i) ?: continue
					val genreObj = g.optJSONObject("genre") ?: g
					val key = genreObj.optString("slug").nullIfEmpty() ?: continue
					val name = genreObj.optString("name").nullIfEmpty() ?: key
					add(MangaTag(key = key, title = name.toTitleCase(sourceLocale), source = source))
				}
			}
		}

		return Manga(
			id = generateUid(mangaUrl),
			url = mangaUrl,
			publicUrl = mangaUrl.toAbsoluteUrl(domain),
			title = title,
			coverUrl = cover,
			largeCoverUrl = cover,
			altTitles = emptySet(),
			authors = emptySet(),
			tags = tags,
			rating = rating,
			state = mapStatus(item.optString("status")),
			contentRating = if (isMature) ContentRating.ADULT else ContentRating.SAFE,
			source = source,
			description = null,
		)
	}

	private fun parseChaptersFromHtml(html: String, typePath: String, slug: String): List<MangaChapter> {
		// HTML embeds RSC flight payloads with heavily escaped JSON chapter objects.
		var text = html
		repeat(3) {
			text = text.replace("\\\"", "\"").replace("\\\\", "\\")
		}
		val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
			timeZone = TimeZone.getTimeZone("UTC")
		}
		val regex = Regex(
			""""id":"(cm[a-z0-9]+)","number":(\d+),"title":"([^"]*)","coverImage":(?:null|"[^"]*"),"isLocked":(true|false),"coinPrice":(\d+),"viewCount":(\d+),"likeCount":(\d+),"publishedAt":"([^"]+)"""",
		)
		val byId = LinkedHashMap<String, MangaChapter>()
		for (match in regex.findAll(text)) {
			val id = match.groupValues[1]
			val number = match.groupValues[2].toFloatOrNull() ?: continue
			val title = match.groupValues[3].nullIfEmpty()
			val isLocked = match.groupValues[4] == "true"
			val publishedAt = match.groupValues[8]
			// Skip locked/premium chapters — no readable pages without payment.
			if (isLocked) continue
			val chapterPath = "/series/$typePath/$slug/chapter/${number.toInt()}"
			byId[id] = MangaChapter(
				id = generateUid(chapterPath),
				title = title,
				number = number,
				volume = 0,
				url = chapterPath,
				scanlator = null,
				uploadDate = runCatching { dateFormat.parse(publishedAt)?.time ?: 0L }.getOrDefault(0L),
				branch = null,
				source = source,
			)
		}
		// API list order is often newest-first; normalize ascending for mapChapters(reversed).
		val sorted = byId.values.sortedBy { it.number }
		return sorted.mapChapters(reversed = true) { _, ch -> ch }
	}

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		val json = webClient.httpGet("https://$domain/api/genres").parseJson()
		val genres = json.optJSONArray("genres") ?: return emptySet()
		return buildSet {
			for (i in 0 until genres.length()) {
				val g = genres.optJSONObject(i) ?: continue
				val key = g.optString("slug").nullIfEmpty() ?: continue
				val name = g.optString("name").nullIfEmpty() ?: key
				add(
					MangaTag(
						key = key,
						title = name.toTitleCase(sourceLocale),
						source = source,
					),
				)
			}
		}
	}

	private fun mapStatus(status: String): MangaState? = when (status.uppercase(Locale.ROOT)) {
		"ONGOING" -> MangaState.ONGOING
		"COMPLETED", "FINISHED" -> MangaState.FINISHED
		"DROPPED", "CANCELLED", "CANCELED" -> MangaState.ABANDONED
		"HIATUS", "ON_HIATUS" -> MangaState.PAUSED
		"COMING_SOON", "UPCOMING" -> MangaState.UPCOMING
		else -> null
	}

	private fun typeToPath(type: String): String = when (type.uppercase(Locale.ROOT)) {
		"NOVEL", "NOVELS" -> "novel"
		"MANHUA" -> "comic"
		"MANGA" -> "comic"
		else -> "comic" // MANHWA and default
	}

	private fun extractSlug(url: String): String {
		// /series/comic/some-slug or /series/comic/some-slug/chapter/4
		val parts = url.trim('/').split('/')
		val seriesIdx = parts.indexOf("series")
		return if (seriesIdx >= 0 && parts.size > seriesIdx + 2) {
			parts[seriesIdx + 2]
		} else {
			parts.last { it.isNotEmpty() && it != "chapter" && !it.all(Char::isDigit) }
		}
	}

	private fun extractTypePath(url: String): String {
		val parts = url.trim('/').split('/')
		val seriesIdx = parts.indexOf("series")
		return if (seriesIdx >= 0 && parts.size > seriesIdx + 1) {
			parts[seriesIdx + 1]
		} else {
			"comic"
		}
	}

	private fun absUrl(path: String): String {
		return if (path.startsWith("http://") || path.startsWith("https://")) {
			path
		} else {
			path.toAbsoluteUrl(domain)
		}
	}

	private fun extractDescription(html: String): String? {
		// Collect candidates from JSON payloads and meta tags, then pick the best
		// synopsis. Important: escaped RSC JSON uses \" delimiters — a naive
		// (?:[^"\\]|\\.)* capture will swallow the closing \" and dump the rest
		// of the series object into the description (coverImage, chapters, …).
		val rawCandidates = ArrayList<String>(12)

		// 1) Unescaped JSON: "description":"…value…"  (value may contain \")
		for (match in JSON_DESC_UNESCAPED.findAll(html)) {
			rawCandidates += match.groupValues[1]
		}
		// 2) Once-escaped RSC/flight: \"description\":\"…value…\"
		//    Value must NOT consume \" (that's the string terminator).
		for (match in JSON_DESC_ESCAPED.findAll(html)) {
			rawCandidates += match.groupValues[1]
		}
		// 3) Meta tags (og: usually has a clean synopsis).
		for (regex in META_DESC_PATTERNS) {
			for (match in regex.findAll(html)) {
				rawCandidates += match.groupValues[1]
			}
		}

		return rawCandidates
			.mapNotNull { decodeDescription(it) }
			.filter { it.length >= 40 && !isSeoDescription(it) && !isJsonPolluted(it) }
			// Prefer longer, non-truncated synopses.
			.maxByOrNull { desc ->
				var score = desc.length
				if (desc.endsWith("…") || desc.endsWith("...")) score -= 80
				score
			}
	}

	private fun decodeDescription(raw: String): String? {
		var text = raw
		// If a bad match overran into the next JSON fields, cut at the boundary.
		val overrunMarkers = listOf(
			"\",\"",
			"\\\",\\\"",
			"\",\"coverImage\"",
			"\",\"bannerImage\"",
			"\",\"status\"",
			"\",\"type\"",
			"\",\"chapters\"",
			"\",\"genres\"",
		)
		for (marker in overrunMarkers) {
			val idx = text.indexOf(marker)
			if (idx >= 40) {
				text = text.substring(0, idx)
				break
			}
		}

		// JSON unicode escapes (\u0027, \u003c, …)
		text = UNICODE_ESCAPE_REGEX.replace(text) { match ->
			match.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: match.value
		}
		text = text
			.replace("\\n", "\n")
			.replace("\\r", "")
			.replace("\\t", " ")
			.replace("\\\"", "\"")
			.replace("\\/", "/")
			.replace("\\\\", "\\")
		// HTML entities (named + numeric / hex)
		text = HTML_ENTITY_REGEX.replace(text) { match ->
			val named = match.groupValues[1]
			val dec = match.groupValues[2]
			val hex = match.groupValues[3]
			when {
				named.isNotEmpty() -> when (named.lowercase()) {
					"amp" -> "&"
					"lt" -> "<"
					"gt" -> ">"
					"quot" -> "\""
					"apos" -> "'"
					"nbsp" -> " "
					else -> match.value
				}
				dec.isNotEmpty() -> dec.toIntOrNull()?.toChar()?.toString() ?: match.value
				hex.isNotEmpty() -> hex.toIntOrNull(16)?.toChar()?.toString() ?: match.value
				else -> match.value
			}
		}
		// Common UTF-8-as-Latin-1 mojibake seen in DivaScans payloads.
		// Built from code points so this source file stays ASCII-safe.
		text = fixMojibake(text)
		text = text
			.replace(Regex("<[^>]+>"), " ")
			.replace(Regex("[\\t\\x0B\\f\\r]+"), " ")
			.replace(Regex(" *\\n *"), "\n")
			.replace(Regex("\\n{3,}"), "\n\n")
			.replace(Regex(" {2,}"), " ")
			.trim()
		// Drop trailing incomplete JSON fragments if any remain.
		text = text.replace(Regex(""",\\s*"[a-zA-Z].*$"""), "").trim()
		return text.nullIfEmpty()
	}

	/** True for DivaScans SEO/meta filler, not the real series synopsis. */
	private fun isSeoDescription(text: String): Boolean {
		val lower = text.lowercase(Locale.ROOT)
		return lower.contains("online for free") ||
			lower.contains("manhwa/comic reading on") ||
			lower.contains("explore chapters") ||
			lower.contains("divascans") ||
			lower.startsWith("read chapter ") ||
			lower.startsWith("read your favorite") ||
			Regex("""\bby null\b""").containsMatchIn(lower) ||
			// Very short generic taglines
			(text.length < 80 && lower.startsWith("read "))
	}

	/** True when extraction leaked neighboring JSON fields into the synopsis. */
	private fun isJsonPolluted(text: String): Boolean {
		return text.contains("\"coverImage\"") ||
			text.contains("\"bannerImage\"") ||
			text.contains("\"chapterCount\"") ||
			text.contains("\"viewCount\"") ||
			text.contains("\"genres\"") ||
			text.contains("\"chapters\"") ||
			text.contains("\"similarSeries\"") ||
			text.contains("\"status\":\"") ||
			text.contains("\"type\":\"") ||
			text.contains("\"slug\":") ||
			text.contains("{\"$") ||
			text.contains("\"id\":\"cm")
	}

	private fun fixMojibake(text: String): String {
		var s = text
		// UTF-8 multi-byte sequences mis-decoded as Latin-1/Windows-1252.
		val pairs = listOf(
			"\u00C3\u00A9" to "\u00E9", // é
			"\u00C3\u00A8" to "\u00E8", // è
			"\u00C3\u00A0" to "\u00E0", // à
			"\u00C3\u00A1" to "\u00E1", // á
			"\u00C3\u00AD" to "\u00ED", // í
			"\u00C3\u00B3" to "\u00F3", // ó
			"\u00C3\u00BA" to "\u00FA", // ú
			"\u00C3\u00B1" to "\u00F1", // ñ
			"\u00C3\u00A7" to "\u00E7", // ç
			"\u00E2\u0080\u0099" to "'", // ’
			"\u00E2\u0080\u0098" to "'", // ‘
			"\u00E2\u0080\u009C" to "\"", // “
			"\u00E2\u0080\u009D" to "\"", // ”
			"\u00E2\u0080\u0093" to "\u2013", // –
			"\u00E2\u0080\u0094" to "\u2014", // —
			"\u00E2\u0080\u00A6" to "\u2026", // …
		)
		for ((bad, good) in pairs) {
			s = s.replace(bad, good)
		}
		// Lone Â often left after fixing nbsp-style sequences.
		s = s.replace("\u00C2", "")
		return s
	}

	private fun extractAuthor(html: String): String? {
		val m = Regex(
			""""author"\s*:\s*\{\s*"@type"\s*:\s*"Person"\s*,\s*"name"\s*:\s*"([^"]+)"""",
		).find(html)
		return m?.groupValues?.get(1)?.nullIfEmpty()
	}

	private companion object {
		/** Format A: full-page images (`s-*.webp` thumbs intentionally excluded). */
		val PAGE_UUID_REGEX = Regex(
			"""https://media\.divascans\.org/series/[^"'\\\s>]+/p-[a-f0-9-]+\.(?:webp|jpe?g|png)""",
			RegexOption.IGNORE_CASE,
		)

		/** Format B: sequential pages under `/chapters/{n}/{###}.ext`. */
		val PAGE_NUMBERED_REGEX = Regex(
			"""https://media\.divascans\.org/series/[^"'\\\s>]+/chapters/\d+/\d+\.(?:webp|jpe?g|png)""",
			RegexOption.IGNORE_CASE,
		)

		val PAGE_INDEX_REGEX = Regex("""/(\d+)\.(?:webp|jpe?g|png)$""", RegexOption.IGNORE_CASE)

		val UNICODE_ESCAPE_REGEX = Regex("""\\u([0-9a-fA-F]{4})""")
		// Groups: 1=named, 2=decimal, 3=hex
		val HTML_ENTITY_REGEX = Regex("""&(?:([a-zA-Z]+)|#(\d+)|#x([0-9a-fA-F]+));""")

		/** Unescaped JSON string value; \. may include \" inside the value. */
		val JSON_DESC_UNESCAPED = Regex(
			""""description"\s*:\s*"((?:[^"\\]|\\.){20,})"""",
		)

		/**
		 * Once-escaped RSC/flight JSON: \"description\":\"value\".
		 * Content must NOT treat \" as an in-string escape (it is the terminator).
		 * Only allow \\ followed by a non-quote char (\\n, \\\\, \\/, …).
		 */
		val JSON_DESC_ESCAPED = Regex(
			"""\\"description\\"\s*:\\"((?:[^"\\]|\\[^"]){20,})\\"""",
		)

		val META_DESC_PATTERNS = listOf(
			Regex(
				"""<meta[^>]+property=["']og:description["'][^>]+content=["']([^"']{20,})["']""",
				RegexOption.IGNORE_CASE,
			),
			Regex(
				"""<meta[^>]+content=["']([^"']{20,})["'][^>]+property=["']og:description["']""",
				RegexOption.IGNORE_CASE,
			),
			Regex(
				"""<meta[^>]+name=["']twitter:description["'][^>]+content=["']([^"']{20,})["']""",
				RegexOption.IGNORE_CASE,
			),
			Regex(
				"""<meta[^>]+name=["']description["'][^>]+content=["']([^"']{20,})["']""",
				RegexOption.IGNORE_CASE,
			),
		)
	}
}
