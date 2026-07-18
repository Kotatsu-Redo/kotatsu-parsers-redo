package org.koitharu.kotatsu.parsers.site.iken

import org.jsoup.nodes.Document
import org.json.JSONObject
import org.json.JSONArray
import okhttp3.Headers
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import java.text.SimpleDateFormat
import java.util.*

internal abstract class IkenParser(
	context: MangaLoaderContext,
	source: MangaParserSource,
	domain: String,
	pageSize: Int = 18,
	protected val useAPI: Boolean = false
) : PagedMangaParser(context, source, pageSize) {

	override val configKeyDomain = ConfigKey.Domain(domain)

	protected val defaultDomain: String
		get() = if (useAPI) "api.$domain" else domain

	protected open val apiHeaders: Headers by lazy {
		getRequestHeaders().newBuilder()
			.set("Accept", "application/json, text/plain, */*")
			.set("Origin", "https://$domain")
			.set("Referer", "https://$domain/")
			.build()
	}

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.POPULARITY)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isMultipleTagsSupported = true,
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
			ContentType.MANGA,
			ContentType.MANHUA,
			ContentType.MANHWA,
			ContentType.OTHER,
		),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://")
			append(defaultDomain)
			append("/api/query?page=")
			append(page)
			append("&perPage=18&searchTerm=")

			filter.query?.let {
				append(filter.query.urlEncoded())
			}

			if (filter.tags.isNotEmpty()) {
				append("&genreIds=")
				filter.tags.joinTo(this, ",") { it.key }
			}

			append("&seriesType=")
			filter.types.oneOrThrowIfMany()?.let {
				append(
					when (it) {
						ContentType.MANGA -> "MANGA"
						ContentType.MANHWA -> "MANHWA"
						ContentType.MANHUA -> "MANHUA"
						ContentType.OTHER -> "RUSSIAN"
						else -> ""
					},
				)
			}

			append("&seriesStatus=")
			filter.states.oneOrThrowIfMany()?.let {
				append(
					when (it) {
						MangaState.ONGOING -> "ONGOING"
						MangaState.FINISHED -> "COMPLETED"
						MangaState.UPCOMING -> "COMING_SOON"
						MangaState.ABANDONED -> "DROPPED"
						else -> ""
					},
				)
			}
		}
		return parseMangaList(webClient.httpGet(url).parseJson())
	}

	protected open fun parseMangaList(json: JSONObject): List<Manga> {
		return json.getJSONArray("posts").mapJSON {
			val url = "/series/${it.getString("slug")}"
			val isNsfwSource = it.getBooleanOrDefault("hot", false)
			val author = it.getStringOrNull("author")?.nullIfEmpty()
			val description = it.getStringOrNull("postContent")
				?: it.getStringOrNull("description")
				?: ""
			Manga(
				id = it.getLong("id"),
				url = url,
				publicUrl = url.toAbsoluteUrl(domain),
				coverUrl = it.getString("featuredImage"),
				title = it.getString("postTitle"),
				altTitles = setOfNotNull(it.getStringOrNull("alternativeTitles")),
				description = description,
				rating = RATING_UNKNOWN,
				tags = emptySet(),
				authors = setOfNotNull(author),
				state = when (it.getString("seriesStatus")) {
					"ONGOING" -> MangaState.ONGOING
					"COMPLETED" -> MangaState.FINISHED
					"DROPPED", "CANCELLED" -> MangaState.ABANDONED
					"COMING_SOON" -> MangaState.UPCOMING
					else -> null
				},
				source = source,
				contentRating = if (isNsfwSource) ContentRating.ADULT else null,
			)
		}
	}


	protected open val datePattern = "yyyy-MM-dd"

	override suspend fun getDetails(manga: Manga): Manga {
		val seriesId = manga.id
		val url = "https://$defaultDomain/api/chapters?postId=$seriesId&skip=0&take=900&order=desc&userid="
		val json = webClient.httpGet(url).parseJson().getJSONObject("post")
		val slug = json.getStringOrNull("slug")
		val data = json.getJSONArray("chapters").asTypedList<JSONObject>()
		val dateFormat = SimpleDateFormat(datePattern, Locale.ENGLISH)
		return manga.copy(
			chapters = data.mapChapters(reversed = true) { i, it ->
				val slugName = if (slug.isNullOrEmpty()) {
					it.getJSONObject("mangaPost").getString("slug")
				} else {
					slug
				}
				val chapterUrl = "/series/$slugName/${it.getString("slug")}"
				MangaChapter(
					id = it.getLong("id"),
					title = null,
					number = it.getFloatOrDefault("number", 0f),
					volume = 0,
					url = chapterUrl,
					scanlator = null,
					uploadDate = dateFormat.parseSafe(it.getString("createdAt").substringBefore("T")),
					branch = null,
					source = source,
				)
			},
		)
	}

	protected open val selectPages = "main section img"

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		if (useAPI) {
			val apiPages = runCatching { readChapterImages(chapter.id) }.getOrElse { error ->
				if (error.message?.contains("unlock", ignoreCase = true) == true) {
					throw error
				}
				emptyList()
			}
			if (apiPages.isNotEmpty()) {
				return apiPages
			}
		}

		val fullUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(fullUrl).parseHtml()

		if (doc.selectFirst("svg.lucide-lock") != null) {
            throw Exception("Need to unlock chapter!")
        }

		val imagesJson = doc.getNextJson("images")
		val images = parseImagesJson(imagesJson)

		return images.map { p ->
			MangaPage(
				id = generateUid(p),
				url = p,
				preview = null,
				source = source,
			)
		}
	}

	protected open suspend fun readChapterImages(chapterId: Long): List<MangaPage> {
		if (chapterId <= 0L) return emptyList()
		val json = webClient.httpGet(
			"https://$defaultDomain/api/chapter?chapterId=$chapterId",
			apiHeaders,
		).parseJson()
		val chapterJson = json.optJSONObject("chapter") ?: return emptyList()
		if (chapterJson.optBoolean("isLocked", false) || chapterJson.opt("isAccessible") == false) {
			throw Exception("Need to unlock chapter!")
		}
		val images = chapterJson.optJSONArray("images") ?: return emptyList()
		val pages = (0 until images.length()).mapNotNull { index ->
			val item = images.optJSONObject(index) ?: return@mapNotNull null
			val url = item.getStringOrNull("url")
				?: item.getStringOrNull("src")
				?: item.getStringOrNull("image")
				?: return@mapNotNull null
			PageImage(
				order = item.opt("order")?.toString()?.toIntOrNull() ?: Int.MAX_VALUE,
				url = url.replace("/public//", "/public/"),
			)
		}.sortedBy { it.order }

		return pages.map { image ->
			MangaPage(
				id = generateUid(image.url),
				url = image.url,
				preview = null,
				source = source,
			)
		}
	}

	protected open suspend fun fetchAvailableTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/series").parseHtml()
		return doc.selectLastOrThrow("select").select("option[value]").mapNotNullToSet {
			val key = it.attrOrNull("value") ?: return@mapNotNullToSet null
			MangaTag(
				key = key,
                title = it.text().ifBlank { key }.toTitleCase(sourceLocale),
                source = source,
			)
		}
	}

	protected fun Document.getNextJson(key: String): String {
		val scripts = select("script")
		val scriptData = scripts.find { script ->
            script.data().contains(key)
		}?.data() ?: throw Exception("Unable to retrieve NEXT data")

		val keyIndex = scriptData.indexOf(key)
		if (keyIndex == -1) throw Exception("Key $key not found in script data")

		val start = scriptData.indexOf('[', keyIndex)
		if (start == -1) {
			val objStart = scriptData.indexOf('{', keyIndex)
			if (objStart == -1) throw Exception("No JSON data found after key")

			var depth = 1
			var i = objStart + 1
			while (i < scriptData.length && depth > 0) {
				when (scriptData[i]) {
					'{' -> depth++
					'}' -> depth--
				}
				i++
			}
			return scriptData.substring(objStart, i)
		}

		var depth = 1
		var i = start + 1
		while (i < scriptData.length && depth > 0) {
			when (scriptData[i]) {
				'[' -> depth++
				']' -> depth--
			}
			i++
		}

		val jsonStr = scriptData.substring(start, i)
		return jsonStr.replace("\\/", "/").replace("\\\"", "\"")
	}

	private fun parseImagesJson(json: String): List<String> {
		val jsonArray = JSONArray(json)
		return List(jsonArray.length()) { index ->
			val item = jsonArray.getJSONObject(index)
			item.getString("url")
		}
	}

	private data class PageImage(
		val order: Int,
		val url: String,
	)
}
