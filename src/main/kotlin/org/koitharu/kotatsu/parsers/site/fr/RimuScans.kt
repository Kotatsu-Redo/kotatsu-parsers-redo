package org.koitharu.kotatsu.parsers.site.fr

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
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
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.json.mapJSONNotNull
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.parseSafe
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.urlBuilder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale
import java.util.TimeZone

@MangaSourceParser("RIMUSCANS", "RimuScans", "fr")
internal class RimuScans(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.RIMUSCANS, pageSize = 24) {

	override val configKeyDomain = ConfigKey.Domain("rimuscan.fr")

	// The site's own default sort, "updated", is left out: it orders by a timestamp the site rewrites
	// all the time (unrelated to chapter releases), so its pages overlap and skip titles.
	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.POPULARITY,
		SortOrder.NEWEST,
		SortOrder.ALPHABETICAL,
		SortOrder.RATING,
	)

	override val filterCapabilities: MangaListFilterCapabilities = MangaListFilterCapabilities(
		isSearchSupported = true,
		isSearchWithFiltersSupported = true,
		isMultipleTagsSupported = true,
		isAuthorSearchSupported = true,
	)

	private companion object {
		// Widths accepted by the site's Next.js image optimizer (defaults include 640 and 1080).
		private const val COVER_WIDTH = 640
		private const val PAGE_WIDTH = 1080

		private val SUGGESTIVE_GENRES = setOf("Ecchi", "Mature", "Torride")
	}

	private val isoDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.FRENCH).apply {
		timeZone = TimeZone.getTimeZone("UTC")
	}

	private val nextFPushRegex =
		Regex("""self\.__next_f\.push\(\s*\[\s*1\s*,\s*"(.*)"\s*]\s*\)""", RegexOption.DOT_MATCHES_ALL)

	override suspend fun getFilterOptions(): MangaListFilterOptions {
		// The catalogue API returns the genre counts of the whole catalogue in `facets`.
		val genres = webClient.httpGet(
			urlBuilder().addPathSegments("api/series").addQueryParameter("limit", "1").build(),
		).parseJson().optJSONObject("facets")?.optJSONObject("genres")
		val tags = genres?.keys()?.asSequence().orEmpty()
			.filter { it.isNotBlank() }
			.map { MangaTag(key = it, title = it.trim(), source = source) }
			.sortedBy { it.title.lowercase(sourceLocale) }
			.toCollection(LinkedHashSet())
		return MangaListFilterOptions(
			availableTags = tags,
			availableStates = EnumSet.of(
				MangaState.ONGOING,
				MangaState.FINISHED,
				MangaState.PAUSED,
			),
			availableContentTypes = EnumSet.of(
				ContentType.MANGA,
				ContentType.MANHWA,
			),
		)
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = urlBuilder().addPathSegments("api/series").apply {
			// The site search matches titles, alternative titles and authors.
			(filter.query ?: filter.author)?.trim()?.nullIfEmpty()?.let {
				addQueryParameter("search", it)
			}
			if (filter.tags.isNotEmpty()) {
				// Genre names are matched exactly (case-sensitive); several genres are ANDed.
				addQueryParameter("genres", filter.tags.joinToString(",") { it.key })
			}
			filter.types.mapNotNull {
				when (it) {
					ContentType.MANHWA -> "webtoon"
					ContentType.MANGA -> "manga"
					else -> null
				}
			}.takeIf { it.isNotEmpty() }?.let { addQueryParameter("types", it.joinToString(",")) }
			filter.states.mapNotNull {
				when (it) {
					MangaState.ONGOING -> "ongoing"
					MangaState.FINISHED -> "completed"
					MangaState.PAUSED -> "hiatus"
					else -> null
				}
			}.takeIf { it.isNotEmpty() }?.let { addQueryParameter("status", it.joinToString(",")) }
			val sort = when (order) {
				SortOrder.NEWEST -> "new"
				SortOrder.ALPHABETICAL -> "az"
				SortOrder.RATING -> "rating"
				else -> "popular"
			}
			addQueryParameter("sort", sort)
			addQueryParameter("page", page.toString())
		}.build()
		val json = webClient.httpGet(url).parseJson()
		return json.optJSONArray("series")?.mapJSONNotNull { parseSeries(it) }.orEmpty()
	}

	private fun parseSeries(json: JSONObject): Manga? {
		val slug = json.getStringOrNull("slug")?.trim()?.nullIfEmpty() ?: return null
		val url = "/manga/$slug"
		val tags = parseGenreNames(json.optJSONArray("genres"))
		val rating = json.optDouble("rating", Double.NaN)
		val hasRating = json.optInt("rating_count", 0) > 0 && !rating.isNaN()
		return Manga(
			id = generateUid(url),
			title = json.getStringOrNull("title")?.trim()?.nullIfEmpty() ?: slug,
			altTitles = emptySet(),
			url = url,
			publicUrl = url.toAbsoluteUrl(domain),
			rating = if (hasRating) (rating / 5.0).toFloat().coerceIn(0f, 1f) else RATING_UNKNOWN,
			contentRating = if (tags.any { it.key in SUGGESTIVE_GENRES }) {
				ContentRating.SUGGESTIVE
			} else {
				ContentRating.SAFE
			},
			coverUrl = json.getStringOrNull("cover_url")?.trim()?.nullIfEmpty()
				?.let { optimizedImageUrl(it, COVER_WIDTH) },
			tags = tags,
			state = parseStatus(json.getStringOrNull("status")),
			authors = emptySet(),
			source = source,
		)
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val slug = manga.url.substringAfterLast("/manga/").substringBefore("/").substringBefore("?")
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val ld = parseLdObjects(doc)
		// The ComicSeries JSON-LD carries the synopsis, genres and authors.
		val series = ld.firstOrNull { it.optString("@type").equals("ComicSeries", ignoreCase = true) }

		// The type and status badges sit right before the title.
		val badges = doc.selectFirst("h1")?.previousElementSibling()?.select("span")
			?.map { it.text().trim() }.orEmpty()

		val altTitles = when (val alt = series?.opt("alternateName")) {
			is JSONArray -> (0 until alt.length()).mapNotNull { alt.optString(it).trim().nullIfEmpty() }
			is String -> listOfNotNull(alt.trim().nullIfEmpty())
			else -> emptyList()
		}

		return manga.copy(
			title = series?.getStringOrNull("name")?.trim()?.nullIfEmpty() ?: manga.title,
			altTitles = manga.altTitles + altTitles,
			description = series?.getStringOrNull("description")?.trim()?.nullIfEmpty() ?: manga.description,
			tags = parseGenreNames(series?.optJSONArray("genre")).ifEmpty { manga.tags },
			authors = parseLdAuthors(series).ifEmpty { manga.authors },
			state = badges.firstNotNullOfOrNull { parseStatus(it) } ?: manga.state,
			coverUrl = manga.coverUrl ?: series?.getStringOrNull("image")?.let { optimizedImageUrl(it, COVER_WIDTH) },
			chapters = parseChapters(decodePayload(doc), slug, ld),
		)
	}

	private fun parseLdObjects(doc: Document): List<JSONObject> {
		val result = ArrayList<JSONObject>()
		for (script in doc.select("script[type=application/ld+json]")) {
			val raw = script.data().trim()
			if (!raw.startsWith("{")) continue
			try {
				result.add(JSONObject(raw))
			} catch (_: Exception) {
				// ignore malformed JSON-LD blocks
			}
		}
		return result
	}

	private fun parseLdAuthors(ld: JSONObject?): Set<String> {
		if (ld == null) return emptySet()
		val out = LinkedHashSet<String>()
		for (key in arrayOf("author", "illustrator")) {
			val name = ld.optJSONObject(key)?.getStringOrNull("name")?.trim()
			if (!name.isNullOrEmpty() && name != "null" && !name.equals("N/A", ignoreCase = true)) {
				out.add(name)
			}
		}
		return out
	}

	/**
	 * Builds the full chapter list, oldest first.
	 *
	 * The largest `chapters` array of the Next.js flight payload is the complete list (premium
	 * chapters included), but React deduplicates its newest entries into back-references such as
	 * `$2b:props:children:3:props:chapters:0`, pointing into the "latest chapters" component;
	 * those are resolved here. The JSON-LD chapter lists (an `ItemList` of every free chapter and
	 * the ComicSeries `hasPart`, capped to the 10 newest) then fill in anything still missing.
	 * Premium and unpublished chapters cannot be read and are skipped.
	 */
	private fun parseChapters(blob: String, slug: String, ld: List<JSONObject>): List<MangaChapter> {
		val chapters = LinkedHashMap<String, MangaChapter>()
		val locked = HashSet<String>()
		val flight = FlightPayload(blob)

		val array = flight.chapterArrays().maxByOrNull { it.length() }
		for (i in 0 until (array?.length() ?: 0)) {
			val json = when (val item = array?.opt(i)) {
				is JSONObject -> item
				is String -> flight.resolve(item) as? JSONObject
				else -> null
			} ?: continue
			val number = json.optDouble("number", -1.0).toFloat()
			if (number < 0f) continue
			val numberKey = formatChapterNumber(number)
			val status = json.getStringOrNull("status")
			if (json.optString("type").equals("PREMIUM", ignoreCase = true) ||
				(status != null && !status.equals("PUBLISHED", ignoreCase = true))
			) {
				locked.add(numberKey)
				continue
			}
			if (numberKey in chapters) continue
			chapters[numberKey] = buildChapter(
				slug = slug,
				numberKey = numberKey,
				number = number,
				rawTitle = json.getStringOrNull("title"),
				date = json.getStringOrNull("releaseDate"),
			)
		}

		for (obj in ld) {
			val items = when {
				obj.optString("@type").equals("ComicSeries", ignoreCase = true) -> obj.optJSONArray("hasPart")
				obj.optString("@type").equals("ItemList", ignoreCase = true) -> obj.optJSONArray("itemListElement")
				else -> null
			} ?: continue
			for (i in 0 until items.length()) {
				val item = items.optJSONObject(i) ?: continue
				val url = item.getStringOrNull("url")?.trim() ?: continue
				if (!url.contains("/read/$slug/")) continue
				val numberKey = url.substringAfterLast('/').trim()
				val number = numberKey.toFloatOrNull() ?: continue
				if (number < 0f || numberKey in locked || numberKey in chapters) continue
				chapters[numberKey] = buildChapter(
					slug = slug,
					numberKey = numberKey,
					number = number,
					rawTitle = item.getStringOrNull("name")?.substringAfterLast(" - "),
					date = item.getStringOrNull("datePublished"),
				)
			}
		}
		return chapters.values.sortedBy { it.number }
	}

	private fun buildChapter(
		slug: String,
		numberKey: String,
		number: Float,
		rawTitle: String?,
		date: String?,
	): MangaChapter {
		val chapterUrl = "/read/$slug/$numberKey"
		val name = rawTitle?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
		return MangaChapter(
			id = generateUid(chapterUrl),
			title = when {
				name == null -> "Chapitre $numberKey"
				name.startsWith("Chapitre", ignoreCase = true) -> name
				else -> "Chapitre $numberKey - $name"
			},
			number = number,
			volume = 0,
			url = chapterUrl,
			uploadDate = parseDate(date),
			source = source,
			scanlator = null,
			branch = null,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()

		// The reader renders each page as <img src="/uploads/mangas/{slug}/chapters/{n}/NNN.webp?v=...">.
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
		// Drop any cache-busting query (?v=...) or fragment before encoding: the Next.js
		// optimizer returns HTTP 400 when they are folded into its `url` parameter.
		val clean = path.substringBefore('?').substringBefore('#').trim()
		val relative = when {
			clean.startsWith("http", ignoreCase = true) -> "/" + clean.substringAfter("://").substringAfter('/')
			clean.startsWith("/") -> clean
			else -> "/$clean"
		}
		val encoded = URLEncoder.encode(relative, "UTF-8")
		return "https://$domain/_next/image?url=$encoded&w=$width&q=75"
	}

	// --- helpers ---

	private fun parseGenreNames(arr: JSONArray?): Set<MangaTag> {
		if (arr == null) return emptySet()
		val out = LinkedHashSet<MangaTag>()
		for (i in 0 until arr.length()) {
			val name = arr.optString(i)
			if (name.isNotBlank() && name != "null") {
				// The key is the exact genre name: the catalogue API filters on it case-sensitively.
				out.add(MangaTag(key = name, title = name.trim(), source = source))
			}
		}
		return out
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

	/** Decoded React Flight (Next.js RSC) payload: newline-separated `<hex id>:<json>` rows. */
	private class FlightPayload(private val blob: String) {

		private val rows = HashMap<String, Any?>()

		/** Every `"chapters":[...]` array in the payload. */
		fun chapterArrays(): List<JSONArray> {
			val result = ArrayList<JSONArray>()
			val marker = "\"chapters\":["
			var idx = 0
			while (true) {
				val at = blob.indexOf(marker, idx)
				if (at == -1) break
				val arrStart = at + marker.length - 1 // position of '['
				val arrStr = extractJsonString(blob, arrStart, '[', ']')
				if (arrStr == null) {
					idx = at + marker.length
					continue
				}
				try {
					result.add(JSONArray(arrStr))
				} catch (_: Exception) {
					// ignore malformed
				}
				idx = arrStart + arrStr.length
			}
			return result
		}

		/**
		 * Resolves a back-reference like `$2b:props:children:3:props:chapters:0`: row `2b`, then
		 * the path, where `type`/`key`/`props` of an element tuple `["$", type, key, props]` are
		 * its slots 1, 2 and 3. Returns null for anything that is not a row reference.
		 */
		fun resolve(ref: String, depth: Int = 0): Any? {
			if (depth > 8 || ref.length < 2 || ref[0] != '$' || Character.digit(ref[1], 16) == -1) {
				return null
			}
			val path = ref.substring(1).split(':')
			var current: Any? = row(path.first())
			for (segment in path.drop(1)) {
				val node = if (current is String) resolve(current, depth + 1) else current
				current = when (node) {
					is JSONArray -> {
						val slot = if (node.optString(0) == "$") {
							when (segment) {
								"type" -> 1
								"key" -> 2
								"props" -> 3
								else -> segment.toIntOrNull()
							}
						} else {
							segment.toIntOrNull()
						}
						slot?.let { node.opt(it) }
					}

					is JSONObject -> node.opt(segment)
					else -> null
				} ?: return null
			}
			val value = current
			return if (value is String) resolve(value, depth + 1) ?: value else value
		}

		private fun row(id: String): Any? {
			if (id !in rows) {
				rows[id] = parseRow(id)
			}
			return rows[id]
		}

		private fun parseRow(id: String): Any? {
			val prefix = "$id:"
			val start = if (blob.startsWith(prefix)) {
				0
			} else {
				blob.indexOf("\n$prefix").takeIf { it != -1 }?.plus(1) ?: return null
			}
			val valueStart = start + prefix.length
			return try {
				when (blob.getOrNull(valueStart)) {
					'[' -> extractJsonString(blob, valueStart, '[', ']')?.let(::JSONArray)
					'{' -> extractJsonString(blob, valueStart, '{', '}')?.let(::JSONObject)
					else -> null
				}
			} catch (_: Exception) {
				null
			}
		}

		private fun extractJsonString(data: String, startIndex: Int, open: Char, close: Char): String? {
			if (startIndex < 0 || startIndex >= data.length || data[startIndex] != open) return null
			var balance = 1
			var inString = false
			var i = startIndex + 1
			while (i < data.length) {
				when (data[i]) {
					'\\' -> if (inString) i++
					'"' -> inString = !inString
					open -> if (!inString) balance++
					close -> if (!inString) {
						balance--
						if (balance == 0) return data.substring(startIndex, i + 1)
					}
				}
				i++
			}
			return null
		}
	}
}
