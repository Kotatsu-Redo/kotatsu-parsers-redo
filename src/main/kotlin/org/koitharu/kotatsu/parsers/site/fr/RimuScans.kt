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
import org.koitharu.kotatsu.parsers.util.json.mapJSONNotNull
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.parseSafe
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
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
	)

	override val filterCapabilities: MangaListFilterCapabilities = MangaListFilterCapabilities(
		isSearchSupported = true,
		isSearchWithFiltersSupported = true,
		isMultipleTagsSupported = true,
		isAuthorSearchSupported = true,
		isTagsExclusionSupported = true,
	)

	private val isoDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.FRENCH).apply {
		timeZone = TimeZone.getTimeZone("UTC")
	}

	private val nextFPushRegex =
		Regex("""self\.__next_f\.push\(\s*\[\s*1\s*,\s*"(.*)"\s*]\s*\)""", RegexOption.DOT_MATCHES_ALL)

	private data class MangaCache(
		val manga: Manga,
		val type: ContentType,
		val latestChapterDate: Long,
	)

	// The whole catalogue is embedded in the /catalogue page Next.js payload.
	private val allMangaCache = suspendLazy {
		val doc = webClient.httpGet("https://$domain/catalogue").parseHtml()
		val list = extractFullMangaListFromDocument(doc)
		list.ifEmpty {
			// Fallback: the homepage also embeds the catalogue.
			extractFullMangaListFromDocument(webClient.httpGet("https://$domain/").parseHtml())
		}
	}

	private val allTagsCache = suspendLazy {
		allMangaCache.get()
			.flatMap { it.manga.tags }
			.toSet()
	}

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = allTagsCache.get(),
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
		var mangaCacheList = allMangaCache.get()

		if (!filter.query.isNullOrEmpty()) {
			val query = filter.query.lowercase(sourceLocale)
			mangaCacheList = mangaCacheList.filter { cachedManga ->
				cachedManga.manga.title.lowercase(sourceLocale).contains(query) ||
					cachedManga.manga.altTitles.any { it.lowercase(sourceLocale).contains(query) }
			}
		}

		if (!filter.author.isNullOrEmpty()) {
			val author = filter.author.lowercase(sourceLocale)
			mangaCacheList = mangaCacheList.filter { cachedManga ->
				cachedManga.manga.authors.any { it.lowercase(sourceLocale).contains(author) }
			}
		}

		if (filter.states.isNotEmpty()) {
			mangaCacheList = mangaCacheList.filter { filter.states.contains(it.manga.state) }
		}

		if (filter.types.isNotEmpty()) {
			mangaCacheList = mangaCacheList.filter { filter.types.contains(it.type) }
		}

		if (filter.tags.isNotEmpty()) {
			mangaCacheList = mangaCacheList.filter { it.manga.tags.containsAll(filter.tags) }
		}

		if (filter.tagsExclude.isNotEmpty()) {
			mangaCacheList = mangaCacheList.filter { cachedManga ->
				!cachedManga.manga.tags.any { tag -> filter.tagsExclude.contains(tag) }
			}
		}

		val sortedCachedMangaList = when (order) {
			SortOrder.UPDATED -> mangaCacheList.sortedByDescending { it.latestChapterDate }
			SortOrder.UPDATED_ASC -> mangaCacheList.sortedBy { it.latestChapterDate }
			SortOrder.ALPHABETICAL -> mangaCacheList.sortedBy { it.manga.title.lowercase() }
			SortOrder.ALPHABETICAL_DESC -> mangaCacheList.sortedByDescending { it.manga.title.lowercase() }
			else -> mangaCacheList
		}

		return sortedCachedMangaList.map { it.manga }
	}

	private fun extractFullMangaListFromDocument(doc: Document): List<MangaCache> {
		val mangasArray = findFirstArray(doc, "mangas", "series") ?: return emptyList()
		val seen = HashSet<String>()
		return mangasArray.mapJSONNotNull { json ->
			val slug = json.getStringOrNull("slug") ?: return@mapJSONNotNull null
			if (!seen.add(slug)) return@mapJSONNotNull null
			parseMangaDetailsFromJson(json)
		}
	}

	private fun parseMangaDetailsFromJson(mangaJson: JSONObject): MangaCache {
		val slug = mangaJson.getString("slug")
		val url = "/manga/$slug"

		val cover = (mangaJson.getStringOrNull("coverImage")
			?: mangaJson.getStringOrNull("cover")
			?: mangaJson.getStringOrNull("thumbnail")
			?: mangaJson.getStringOrNull("image"))
			?.takeIf { it.isNotBlank() && it != "null" }
			?.toAbsoluteUrl(domain)

		val authors = buildSet {
			mangaJson.getStringOrNull("author")?.let { addAll(splitNames(it)) }
			mangaJson.getStringOrNull("artist")?.let { addAll(splitNames(it)) }
		}

		val genres = parseGenres(mangaJson)

		val ratingValue = mangaJson.optDouble("rating").toFloat()
		val rating = if (ratingValue > 0f) ratingValue.div(5f) else RATING_UNKNOWN
		val nsfw = mangaJson.optBoolean("isAdult", false) || mangaJson.optBoolean("isExplicit", false)

		val manga = Manga(
			id = generateUid(url),
			title = mangaJson.getStringOrNull("title") ?: slug,
			altTitles = emptySet(),
			url = url,
			publicUrl = url.toAbsoluteUrl(domain),
			rating = rating,
			contentRating = if (nsfw) ContentRating.ADULT else ContentRating.SAFE,
			coverUrl = cover,
			tags = genres,
			state = parseStatus(mangaJson.getStringOrNull("status")),
			authors = authors,
			description = mangaJson.getStringOrNull("description")
				?.takeIf { it.isNotBlank() && it != "null" },
			source = source,
		)

		val type = when (mangaJson.optString("type").lowercase(sourceLocale)) {
			"manhwa", "webtoon" -> ContentType.MANHWA
			"manhua" -> ContentType.MANHUA
			else -> ContentType.MANGA
		}
		val latestChapterDate = latestChapterDate(mangaJson.optJSONArray("chapters"))

		return MangaCache(manga = manga, type = type, latestChapterDate = latestChapterDate)
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val slug = manga.url.substringAfterLast("/manga/").substringBefore("/")

		// Prefer the dedicated "manga" object; otherwise fall back to matching the slug in the catalogue array.
		val mangaJson = findMangaObject(doc, slug)
		val chapters = mangaJson?.optJSONArray("chapters")?.let { parseChapters(it, slug) }.orEmpty()

		val enriched = mangaJson?.let { json ->
			val cover = (json.getStringOrNull("coverImage") ?: json.getStringOrNull("cover")
				?: json.getStringOrNull("thumbnail") ?: json.getStringOrNull("image"))
				?.takeIf { it.isNotBlank() && it != "null" }?.toAbsoluteUrl(domain)
			manga.copy(
				coverUrl = cover ?: manga.coverUrl,
				description = json.getStringOrNull("description")
					?.takeIf { it.isNotBlank() && it != "null" } ?: manga.description,
				tags = parseGenres(json).ifEmpty { manga.tags },
				state = parseStatus(json.getStringOrNull("status")) ?: manga.state,
				authors = buildSet {
					json.getStringOrNull("author")?.let { addAll(splitNames(it)) }
					json.getStringOrNull("artist")?.let { addAll(splitNames(it)) }
				}.ifEmpty { manga.authors },
			)
		} ?: manga

		return enriched.copy(chapters = chapters)
	}

	private fun parseChapters(chaptersArray: JSONArray, slug: String): List<MangaChapter> {
		return chaptersArray.mapJSONNotNull { chapterJson ->
			if (chapterJson.optString("type").equals("PREMIUM", ignoreCase = true)) {
				return@mapJSONNotNull null
			}
			if (chapterJson.optBoolean("isPremium", false)) return@mapJSONNotNull null

			val number = chapterJson.optDouble("number", -1.0).toFloat()
			if (number < 0f) return@mapJSONNotNull null

			val numberKey = formatChapterNumber(number)
			val chapterUrl = "/read/$slug/$numberKey"
			val title = chapterJson.getStringOrNull("title")?.takeIf { it.isNotBlank() && it != "null" }
			val chapterTitle = if (title != null) {
				"Chapitre $numberKey - $title"
			} else {
				"Chapitre $numberKey"
			}
			val date = chapterJson.getStringOrNull("releaseDate")
				?: chapterJson.getStringOrNull("createdAt")
				?: chapterJson.getStringOrNull("publishedAt")

			MangaChapter(
				id = generateUid(chapterUrl),
				title = chapterTitle,
				number = number,
				volume = 0,
				url = chapterUrl,
				uploadDate = parseDate(date),
				source = source,
				scanlator = null,
				branch = null,
			)
		}.sortedBy { it.number }
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()

		// The reader renders the chapter pages as plain <img> tags pointing at /uploads/mangas/{slug}/chapters/{n}/NNN.jpg
		val imgPages = doc.select("img[src*=/uploads/mangas/][src*=/chapters/]")
			.mapNotNull { it.attr("src").trim().takeIf(String::isNotEmpty) }
			.distinct()
			.map { src ->
				val absUrl = src.toAbsoluteUrl(domain)
				MangaPage(
					id = generateUid(absUrl),
					url = absUrl,
					preview = null,
					source = chapter.source,
				)
			}
		if (imgPages.isNotEmpty()) {
			return imgPages
		}

		// Fallback: read the images array from the Next.js payload for the current chapter.
		val imagesArray = findFirstArray(doc, "images") ?: return emptyList()
		return imagesArray.mapJSONNotNull { imageJson ->
			val imageUrl = (imageJson.getStringOrNull("url") ?: imageJson.getStringOrNull("originalUrl"))
				?.takeIf { it.contains("/uploads/mangas/") } ?: return@mapJSONNotNull null
			val absUrl = imageUrl.toAbsoluteUrl(domain)
			MangaPage(
				id = generateUid(absUrl),
				url = absUrl,
				preview = null,
				source = chapter.source,
			)
		}
	}

	private fun latestChapterDate(chaptersArray: JSONArray?): Long {
		if (chaptersArray == null) return 0L
		var max = 0L
		for (i in 0 until chaptersArray.length()) {
			val chapter = chaptersArray.optJSONObject(i) ?: continue
			val date = chapter.getStringOrNull("releaseDate")
				?: chapter.getStringOrNull("createdAt")
				?: chapter.getStringOrNull("publishedAt")
			val parsed = parseDate(date)
			if (parsed > max) max = parsed
		}
		return max
	}

	private fun parseGenres(mangaJson: JSONObject): Set<MangaTag> {
		val result = LinkedHashSet<MangaTag>()
		val array = mangaJson.optJSONArray("genres") ?: mangaJson.optJSONArray("categories")
		if (array != null) {
			for (i in 0 until array.length()) {
				val name = when (val item = array.opt(i)) {
					is String -> item
					is JSONObject -> item.getStringOrNull("name") ?: item.getStringOrNull("title")
					else -> null
				}?.trim()
				if (!name.isNullOrEmpty() && name != "null") {
					result.add(MangaTag(key = name.lowercase(sourceLocale), title = name, source = source))
				}
			}
		}
		return result
	}

	private fun splitNames(value: String): Set<String> {
		return value.takeIf { it.isNotBlank() && it != "null" }
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
			"cancelled", "abandoned", "annulé", "abandonné" -> MangaState.ABANDONED
			else -> null
		}
	}

	private fun parseDate(dateString: String?): Long {
		if (dateString.isNullOrBlank()) return 0L
		val cleaned = dateString.removePrefix("\"").removeSuffix("\"")
			.removePrefix("\$D").removePrefix("D").trim()
		return isoDateFormat.parseSafe(cleaned)
	}

	// --- Next.js RSC payload helpers ---

	private fun findMangaObject(doc: Document, slug: String): JSONObject? {
		forEachPayloadObject(doc, listOf("\"manga\":{", "\"mangas\":[", "\"series\":[")) { container ->
			container.optJSONObject("manga")?.let { if (it.optString("slug") == slug) return it }
			val arr = container.optJSONArray("mangas") ?: container.optJSONArray("series")
			if (arr != null) {
				for (i in 0 until arr.length()) {
					val obj = arr.optJSONObject(i) ?: continue
					if (obj.optString("slug") == slug) return obj
				}
			}
			if (container.optString("slug") == slug && container.has("chapters")) return container
		}
		return null
	}

	private fun findFirstArray(doc: Document, vararg keys: String): JSONArray? {
		val patterns = keys.map { "\"$it\":[" }
		forEachPayloadObject(doc, patterns) { container ->
			for (key in keys) {
				container.optJSONArray(key)?.let { if (it.length() > 0) return it }
			}
		}
		return null
	}

	private inline fun forEachPayloadObject(
		document: Document,
		patterns: List<String>,
		action: (JSONObject) -> Unit,
	) {
		for (script in document.select("script")) {
			val scriptContent = script.data()
			if (!scriptContent.contains("self.__next_f.push")) continue

			for (matchResult in nextFPushRegex.findAll(scriptContent)) {
				if (matchResult.groupValues.size < 2) continue
				val cleaned = matchResult.groupValues[1].replace("\\\\", "\\").replace("\\\"", "\"")

				for (pattern in patterns) {
					var searchIdx = -1
					while (true) {
						searchIdx = cleaned.indexOf(pattern, startIndex = searchIdx + 1)
						if (searchIdx == -1) break

						var objectStartIndex = -1
						var braceDepth = 0
						for (i in searchIdx downTo 0) {
							when (cleaned[i]) {
								'}' -> braceDepth++
								'{' -> {
									if (braceDepth == 0) {
										objectStartIndex = i
										break
									}
									braceDepth--
								}
							}
						}

						if (objectStartIndex != -1) {
							val potentialJson = extractJsonObjectString(cleaned, objectStartIndex)
							if (potentialJson != null) {
								try {
									action(JSONObject(potentialJson))
								} catch (_: Exception) {
									// keep searching
								}
							}
						}
					}
				}
			}
		}
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
}
