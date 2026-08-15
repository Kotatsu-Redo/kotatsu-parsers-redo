package org.koitharu.kotatsu.parsers.site.fr

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.SinglePageMangaParser
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
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.parseSafe
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale
import java.util.TimeZone

@MangaSourceParser("RIMUSCANS", "RimuScans", "fr")
internal class RimuScans(context: MangaLoaderContext) :
	SinglePageMangaParser(context, MangaParserSource.RIMUSCANS) {

	override val configKeyDomain = ConfigKey.Domain("rimuscan.fr")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.ALPHABETICAL,
		SortOrder.POPULARITY,
	)

	override val filterCapabilities: MangaListFilterCapabilities = MangaListFilterCapabilities(
		isSearchSupported = true,
		isSearchWithFiltersSupported = true,
		isMultipleTagsSupported = true,
		isAuthorSearchSupported = true,
		isTagsExclusionSupported = true,
	)

	private companion object {
		// Widths accepted by the site's Next.js image optimizer (defaults include 640 and 1080).
		private const val COVER_WIDTH = 640
		private const val PAGE_WIDTH = 1080
	}

	private val isoDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.FRENCH).apply {
		timeZone = TimeZone.getTimeZone("UTC")
	}

	private val nextFPushRegex =
		Regex("""self\.__next_f\.push\(\s*\[\s*1\s*,\s*"(.*)"\s*]\s*\)""", RegexOption.DOT_MATCHES_ALL)

	private data class MangaCache(
		val manga: Manga,
		val type: ContentType,
		val views: Int,
		val latestChapterDate: Long,
	)

	// The whole catalogue (429+ titles) is embedded in the homepage Next.js payload.
	// Fetched fresh on every list load so a pull-to-refresh surfaces newly added titles.
	private suspend fun loadCatalogue(): List<MangaCache> {
		val doc = webClient.httpGet("https://$domain/").parseHtml()
		return extractMangaObjects(decodePayload(doc)).map { parseMangaFromJson(it) }
	}

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = loadCatalogue().flatMapTo(LinkedHashSet()) { it.manga.tags },
		availableStates = EnumSet.of(
			MangaState.ONGOING,
			MangaState.FINISHED,
			MangaState.PAUSED,
		),
		availableContentTypes = EnumSet.of(
			ContentType.MANGA,
			ContentType.MANHWA,
			ContentType.MANHUA,
		),
	)

	override suspend fun getList(order: SortOrder, filter: MangaListFilter): List<Manga> {
		var list = loadCatalogue()

		if (!filter.query.isNullOrEmpty()) {
			val query = filter.query.lowercase(sourceLocale)
			list = list.filter {
				it.manga.title.lowercase(sourceLocale).contains(query) ||
					it.manga.altTitles.any { alt -> alt.lowercase(sourceLocale).contains(query) }
			}
		}
		if (!filter.author.isNullOrEmpty()) {
			val author = filter.author.lowercase(sourceLocale)
			list = list.filter { c -> c.manga.authors.any { it.lowercase(sourceLocale).contains(author) } }
		}
		if (filter.states.isNotEmpty()) {
			list = list.filter { filter.states.contains(it.manga.state) }
		}
		if (filter.types.isNotEmpty()) {
			list = list.filter { filter.types.contains(it.type) }
		}
		if (filter.tags.isNotEmpty()) {
			list = list.filter { it.manga.tags.containsAll(filter.tags) }
		}
		if (filter.tagsExclude.isNotEmpty()) {
			list = list.filter { c -> c.manga.tags.none { filter.tagsExclude.contains(it) } }
		}

		val sorted = when (order) {
			SortOrder.UPDATED -> list.sortedByDescending { it.latestChapterDate }
			SortOrder.UPDATED_ASC -> list.sortedBy { it.latestChapterDate }
			SortOrder.ALPHABETICAL -> list.sortedBy { it.manga.title.lowercase() }
			SortOrder.ALPHABETICAL_DESC -> list.sortedByDescending { it.manga.title.lowercase() }
			SortOrder.POPULARITY -> list.sortedByDescending { it.views }
			SortOrder.POPULARITY_ASC -> list.sortedBy { it.views }
			else -> list
		}
		return sorted.map { it.manga }
	}

	private fun parseMangaFromJson(json: JSONObject): MangaCache {
		val slug = json.getString("slug")
		val url = "/manga/$slug"

		val cover = json.getStringOrNull("cover")
			?.takeIf { it.isNotBlank() && it != "null" }
			?.let { optimizedImageUrl(it, COVER_WIDTH) }

		val authors = buildSet {
			addAll(splitNames(json.getStringOrNull("author")))
			addAll(splitNames(json.getStringOrNull("artist")))
		}

		val altTitles = json.optJSONArray("alternativeTitles")?.let { arr ->
			(0 until arr.length()).mapNotNull { arr.optString(it).trim().takeIf(String::isNotEmpty) }.toSet()
		}.orEmpty()

		val nsfw = json.optBoolean("isAdult", false) || json.optBoolean("isExplicit", false)

		val manga = Manga(
			id = generateUid(url),
			title = json.getStringOrNull("title") ?: slug,
			altTitles = altTitles,
			url = url,
			publicUrl = url.toAbsoluteUrl(domain),
			rating = RATING_UNKNOWN,
			contentRating = if (nsfw) ContentRating.ADULT else ContentRating.SAFE,
			coverUrl = cover,
			tags = parseGenres(json),
			state = parseStatus(json.getStringOrNull("status")),
			authors = authors,
			description = json.getStringOrNull("description")?.takeIf { it.isNotBlank() && it != "null" },
			source = source,
		)

		val type = when (json.optString("type").lowercase(sourceLocale)) {
			"manhwa", "webtoon" -> ContentType.MANHWA
			"manhua" -> ContentType.MANHUA
			else -> ContentType.MANGA
		}
		return MangaCache(
			manga = manga,
			type = type,
			views = json.optInt("views", 0),
			latestChapterDate = latestChapterDate(json.optJSONArray("chapters")),
		)
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val slug = manga.url.substringAfterLast("/manga/").substringBefore("/")
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val blob = decodePayload(doc)
		val objects = extractMangaObjects(blob)

		// Pick the object matching this slug, else the one carrying the most chapters.
		val detail = objects.firstOrNull { it.optString("slug") == slug }
			?: objects.maxByOrNull { it.optJSONArray("chapters")?.length() ?: 0 }

		// The full chapter list may be nested in the manga object or held in a standalone
		// RSC node; use whichever is longest so a truncated preview can't win.
		val nested = detail?.optJSONArray("chapters")
		val standalone = findLargestChaptersArray(blob)
		val chaptersArray = listOfNotNull(nested, standalone).maxByOrNull { it.length() }
		val chapters = chaptersArray?.let { parseChapters(it, slug) }.orEmpty()

		val enriched = detail?.let { json ->
			val cover = json.getStringOrNull("cover")?.takeIf { it.isNotBlank() && it != "null" }
				?.let { optimizedImageUrl(it, COVER_WIDTH) }
			manga.copy(
				coverUrl = cover ?: manga.coverUrl,
				description = json.getStringOrNull("description")?.takeIf { it.isNotBlank() && it != "null" }
					?: manga.description,
				tags = parseGenres(json).ifEmpty { manga.tags },
				state = parseStatus(json.getStringOrNull("status")) ?: manga.state,
				authors = buildSet {
					addAll(splitNames(json.getStringOrNull("author")))
					addAll(splitNames(json.getStringOrNull("artist")))
				}.ifEmpty { manga.authors },
			)
		} ?: manga

		return enriched.copy(chapters = chapters)
	}

	private fun parseChapters(chaptersArray: JSONArray, slug: String): List<MangaChapter> {
		val result = ArrayList<MangaChapter>(chaptersArray.length())
		for (i in 0 until chaptersArray.length()) {
			val chapterJson = chaptersArray.optJSONObject(i) ?: continue
			// Skip locked/premium chapters that cannot be read.
			if (chapterJson.optString("type").equals("PREMIUM", ignoreCase = true)) continue
			val status = chapterJson.getStringOrNull("status")
			if (status != null && !status.equals("PUBLISHED", ignoreCase = true)) continue

			val number = chapterJson.optDouble("number", -1.0).toFloat()
			if (number < 0f) continue

			val numberKey = formatChapterNumber(number)
			val chapterUrl = "/read/$slug/$numberKey"
			val rawTitle = chapterJson.getStringOrNull("title")?.takeIf { it.isNotBlank() && it != "null" }
			val title = when {
				rawTitle == null -> "Chapitre $numberKey"
				rawTitle.startsWith("Chapitre", ignoreCase = true) -> rawTitle
				else -> "Chapitre $numberKey - $rawTitle"
			}

			result.add(
				MangaChapter(
					id = generateUid(chapterUrl),
					title = title,
					number = number,
					volume = 0,
					url = chapterUrl,
					uploadDate = parseDate(chapterJson.getStringOrNull("releaseDate")),
					source = source,
					scanlator = null,
					branch = null,
				),
			)
		}
		return result.sortedBy { it.number }
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()

		// The reader renders each page as <img src="/uploads/mangas/{slug}/chapters/{n}/NNN.jpg?v=...">.
		// The credits page lives under /uploads/credits/ and is excluded by the selector.
		// Pages are served through the Next.js image optimizer so they arrive as WebP: this
		// dodges buggy hardware JPEG decoders (e.g. MediaTek libjpeg-alpha) and cuts bandwidth.
		return doc.select("img[src*=/uploads/mangas/][src*=/chapters/]")
			.mapNotNull { it.attr("src").trim().takeIf(String::isNotEmpty) }
			.distinct()
			.map { src ->
				val optimized = optimizedImageUrl(src, PAGE_WIDTH)
				MangaPage(
					id = generateUid(optimized),
					url = optimized,
					preview = null,
					source = chapter.source,
				)
			}
	}

	/**
	 * Routes an on-site image path through the Next.js image optimizer (`/_next/image`),
	 * which serves WebP/AVIF instead of the original JPEG.
	 */
	private fun optimizedImageUrl(path: String, width: Int): String {
		val relative = when {
			path.startsWith("http", ignoreCase = true) -> "/" + path.substringAfter("://").substringAfter('/')
			path.startsWith("/") -> path
			else -> "/$path"
		}
		val encoded = URLEncoder.encode(relative, "UTF-8")
		return "https://$domain/_next/image?url=$encoded&w=$width&q=75"
	}

	// --- helpers ---

	private fun latestChapterDate(chaptersArray: JSONArray?): Long {
		if (chaptersArray == null) return 0L
		var max = 0L
		for (i in 0 until chaptersArray.length()) {
			val c = chaptersArray.optJSONObject(i) ?: continue
			val d = parseDate(c.getStringOrNull("releaseDate"))
			if (d > max) max = d
		}
		return max
	}

	private fun parseGenres(json: JSONObject): Set<MangaTag> {
		val arr = json.optJSONArray("genres") ?: json.optJSONArray("categories") ?: return emptySet()
		val out = LinkedHashSet<MangaTag>()
		for (i in 0 until arr.length()) {
			val name = when (val item = arr.opt(i)) {
				is String -> item
				is JSONObject -> item.getStringOrNull("name") ?: item.getStringOrNull("title")
				else -> null
			}?.trim()
			if (!name.isNullOrEmpty() && name != "null") {
				out.add(MangaTag(key = name.lowercase(sourceLocale), title = name, source = source))
			}
		}
		return out
	}

	private fun splitNames(value: String?): Set<String> {
		return value?.takeIf { it.isNotBlank() && it != "null" }
			?.split(',', '&')
			?.map(String::trim)
			?.filter { it.isNotEmpty() && it != "null" }
			?.toSet()
			.orEmpty()
	}

	private fun formatChapterNumber(number: Float): String {
		return if (number % 1 == 0f) number.toInt().toString() else number.toString()
	}

	private fun parseStatus(status: String?): MangaState? {
		return when (status?.trim()?.lowercase(sourceLocale)) {
			"ongoing", "en cours" -> MangaState.ONGOING
			"completed", "finished", "terminé" -> MangaState.FINISHED
			"hiatus", "paused", "en pause" -> MangaState.PAUSED
			"cancelled", "abandoned", "dropped", "annulé", "abandonné" -> MangaState.ABANDONED
			else -> null
		}
	}

	private fun parseDate(dateString: String?): Long {
		if (dateString.isNullOrBlank()) return 0L
		return isoDateFormat.parseSafe(dateString.trim())
	}

	/**
	 * Decodes the Next.js RSC payload and returns every embedded manga object
	 * (each is a self-contained `{"id":..,"slug":..,..,"chapters":[..]}` JSON object).
	 */
	private fun extractMangaObjects(blob: String): List<JSONObject> {
		val result = ArrayList<JSONObject>()
		val seen = HashSet<String>()
		val marker = "{\"id\":\""
		var searchIdx = 0
		while (true) {
			val start = blob.indexOf(marker, searchIdx)
			if (start == -1) break
			val objStr = extractJsonObjectString(blob, start)
			if (objStr == null) {
				searchIdx = start + marker.length
				continue
			}
			// Only manga objects carry a slug; chapter objects (also `{"id":..}`) do not.
			if (objStr.contains("\"slug\"")) {
				try {
					val obj = JSONObject(objStr)
					val slug = obj.optString("slug")
					if (slug.isNotEmpty() && seen.add(slug)) {
						result.add(obj)
					}
					// Skip past the whole manga object (with its nested chapters).
					searchIdx = start + objStr.length
					continue
				} catch (_: Exception) {
					// fall through
				}
			}
			searchIdx = start + marker.length
		}
		return result
	}

	/** Finds the longest `"chapters":[...]` array anywhere in the decoded payload. */
	private fun findLargestChaptersArray(blob: String): JSONArray? {
		val marker = "\"chapters\":["
		var best: JSONArray? = null
		var searchIdx = 0
		while (true) {
			val at = blob.indexOf(marker, searchIdx)
			if (at == -1) break
			val arrStart = at + marker.length - 1 // position of '['
			val arrStr = extractJsonArrayString(blob, arrStart)
			if (arrStr != null) {
				try {
					val arr = JSONArray(arrStr)
					if (best == null || arr.length() > best.length()) best = arr
				} catch (_: Exception) {
					// ignore malformed
				}
				searchIdx = arrStart + arrStr.length
			} else {
				searchIdx = at + marker.length
			}
		}
		return best
	}

	private fun decodePayload(doc: Document): String {
		val sb = StringBuilder()
		for (script in doc.select("script")) {
			val data = script.data()
			if (!data.contains("__next_f.push")) continue
			for (match in nextFPushRegex.findAll(data)) {
				sb.append(match.groupValues[1])
			}
		}
		return unescape(sb.toString())
	}

	/** Single-level unescape of the JS string payload (equivalent to Python's unicode_escape). */
	private fun unescape(s: String): String {
		if (s.indexOf('\\') == -1) return s
		val sb = StringBuilder(s.length)
		var i = 0
		while (i < s.length) {
			val c = s[i]
			if (c == '\\' && i + 1 < s.length) {
				when (s[i + 1]) {
					'\\' -> { sb.append('\\'); i += 2 }
					'"' -> { sb.append('"'); i += 2 }
					'/' -> { sb.append('/'); i += 2 }
					'n' -> { sb.append('\n'); i += 2 }
					'r' -> { sb.append('\r'); i += 2 }
					't' -> { sb.append('\t'); i += 2 }
					'b' -> { sb.append('\b'); i += 2 }
					'f' -> { sb.append('\u000C'); i += 2 }
					'u' -> {
						val hex = if (i + 6 <= s.length) s.substring(i + 2, i + 6) else null
						val code = hex?.toIntOrNull(16)
						if (code != null) {
							sb.append(code.toChar()); i += 6
						} else {
							sb.append(c); i++
						}
					}
					else -> { sb.append(c); i++ }
				}
			} else {
				sb.append(c); i++
			}
		}
		return sb.toString()
	}

	private fun extractJsonObjectString(data: String, startIndex: Int): String? {
		if (startIndex < 0 || startIndex >= data.length || data[startIndex] != '{') return null
		var braceBalance = 1
		var inString = false
		var i = startIndex + 1
		while (i < data.length) {
			when (data[i]) {
				'\\' -> if (inString) i++
				'"' -> inString = !inString
				'{' -> if (!inString) braceBalance++
				'}' -> if (!inString) {
					braceBalance--
					if (braceBalance == 0) return data.substring(startIndex, i + 1)
				}
			}
			i++
		}
		return null
	}

	private fun extractJsonArrayString(data: String, startIndex: Int): String? {
		if (startIndex < 0 || startIndex >= data.length || data[startIndex] != '[') return null
		var balance = 1
		var inString = false
		var i = startIndex + 1
		while (i < data.length) {
			when (data[i]) {
				'\\' -> if (inString) i++
				'"' -> inString = !inString
				'[' -> if (!inString) balance++
				']' -> if (!inString) {
					balance--
					if (balance == 0) return data.substring(startIndex, i + 1)
				}
			}
			i++
		}
		return null
	}
}
