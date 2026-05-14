package org.koitharu.kotatsu.parsers.site.en

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Headers
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.EnumSet
import java.util.TimeZone

@MangaSourceParser("MANGACLOUD", "MangaCloud", "en", ContentType.MANGA)
internal class MangaCloud(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.MANGACLOUD, 20) {

	override val configKeyDomain = ConfigKey.Domain("mangacloud.org")

	private val apiUrl = "https://api.mangacloud.org"
	private val cdnUrl = "https://pika.mangacloud.org"

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
		keys.add(ConfigKey.InterceptCloudflare(defaultValue = true))
	}

	override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
		.set("Referer", "https://$domain/")
		.build()

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = true,
		)

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.POPULARITY,
		SortOrder.UPDATED,
		SortOrder.NEWEST,
		SortOrder.ALPHABETICAL,
		SortOrder.RELEVANCE,
	)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchAvailableTags(),
		availableStates = EnumSet.of(
			MangaState.ONGOING,
			MangaState.FINISHED,
			MangaState.PAUSED,
			MangaState.ABANDONED,
		),
		availableContentTypes = EnumSet.of(
			ContentType.MANGA,
			ContentType.MANHWA,
			ContentType.MANHUA,
		),
	)

	private var cachedTags: Set<MangaTag>? = null

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		cachedTags?.let { return it }
		return try {
			val response = webClient.httpGet("$apiUrl/tag/list").parseJson()
			val data = response.getJSONArray("data")
			val tags = mutableSetOf<MangaTag>()
			for (i in 0 until data.length()) {
				val tag = data.getJSONObject(i)
				tags.add(
					MangaTag(
						key = tag.getString("id"),
						title = tag.getString("name"),
						source = source,
					)
				)
			}
			cachedTags = tags
			tags
		} catch (_: Exception) {
			emptySet()
		}
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		if (!filter.query.isNullOrEmpty()) {
			return getSearchManga(filter.query)
		}
		return when (order) {
			SortOrder.POPULARITY -> getPopularManga(page)
			SortOrder.UPDATED -> getLatestManga(page)
			else -> getBrowseManga(page, filter, order)
		}
	}

	private suspend fun getSearchManga(query: String): List<Manga> {
		val jsonBody = JSONObject().apply { put("terms", query) }
		val response = webClient.httpPost("$apiUrl/search".toHttpUrl(), jsonBody).parseJson()
		val data = response.getJSONArray("data")
		return (0 until data.length()).map { parseMangaFromSearch(data.getJSONObject(it)) }
	}

	private suspend fun getPopularManga(page: Int): List<Manga> {
		val time = when (page) {
			1 -> "today"
			2 -> "week"
			else -> "month"
		}
		val response = webClient.httpGet("$apiUrl/comic-popular-view/$time").parseJson()
		val data = response.getJSONObject("data")
		val list = data.getJSONArray("list")
		return (0 until list.length()).map { parseMangaFromBrowse(list.getJSONObject(it)) }
	}

	private suspend fun getLatestManga(page: Int): List<Manga> {
		val jsonBody = JSONObject().apply { put("page", page) }
		val response = webClient.httpPost("$apiUrl/comic-updates".toHttpUrl(), jsonBody).parseJson()
		val data = response.getJSONObject("data")
		val list = data.getJSONArray("list")
		return (0 until list.length()).map { parseMangaFromBrowse(list.getJSONObject(it)) }
	}

	private suspend fun getBrowseManga(page: Int, filter: MangaListFilter, order: SortOrder? = null): List<Manga> {
		val includes = JSONArray()
		filter.tags.forEach { includes.put(it.key) }
		val excludes = JSONArray()
		filter.tagsExclude.forEach { excludes.put(it.key) }

		val jsonBody = JSONObject().apply {
			filter.types.firstOrNull()?.let { type ->
				when (type) {
					ContentType.MANGA -> put("type", "Manga")
					ContentType.MANHWA -> put("type", "Manhwa")
					ContentType.MANHUA -> put("type", "Manhua")
					else -> {}
				}
			}
			order?.let {
				when (it) {
					SortOrder.NEWEST -> put("sort", "created_date-DESC")
					SortOrder.ALPHABETICAL -> put("sort", "title-ASC")
					SortOrder.ALPHABETICAL_DESC -> put("sort", "title-DESC")
					SortOrder.UPDATED -> put("sort", "updated_date-DESC")
					SortOrder.RATING -> put("sort", "rating")
					else -> {}
				}
			}
			filter.states.firstOrNull()?.let { state ->
				when (state) {
					MangaState.ONGOING -> put("status", "Ongoing")
					MangaState.FINISHED -> put("status", "Completed")
					MangaState.PAUSED -> put("status", "Hiatus")
					MangaState.ABANDONED -> put("status", "Cancelled")
					else -> {}
				}
			}
			put("includes", includes)
			put("excludes", excludes)
			put("page", page)
		}

		val response = webClient.httpPost("$apiUrl/comic/browse".toHttpUrl(), jsonBody).parseJson()
		val data = response.getJSONArray("data")
		return (0 until data.length()).map { parseMangaFromBrowse(data.getJSONObject(it)) }
	}

	private fun parseMangaFromBrowse(json: JSONObject): Manga {
		val id = json.getString("id")
		val title = json.getString("title")
		val cover = json.optJSONObject("cover")

		val coverUrl = cover?.let {
			"$cdnUrl/$id/${it.getString("id")}.${it.optString("f", "jpg")}"
		}

		val tags = parseTags(json.optJSONArray("tags"))

		return Manga(
			id = generateUid(id),
			url = id,
			publicUrl = "https://mangacloud.org/comic/$id",
			coverUrl = coverUrl,
			title = title,
			altTitles = emptySet(),
			rating = RATING_UNKNOWN,
			contentRating = ContentRating.SAFE,
			tags = tags,
			state = null,
			authors = emptySet(),
			source = source,
		)
	}

	private fun parseMangaFromSearch(json: JSONObject): Manga {
		val id = json.getString("id")
		val title = json.getString("title")
		val cover = json.optJSONObject("cover")

		val coverUrl = cover?.let {
			"$cdnUrl/$id/${it.getString("id")}.${it.optString("f", "jpg")}"
		}

		return Manga(
			id = generateUid(id),
			url = id,
			publicUrl = "https://mangacloud.org/comic/$id",
			coverUrl = coverUrl,
			title = title,
			altTitles = emptySet(),
			rating = RATING_UNKNOWN,
			contentRating = ContentRating.SAFE,
			tags = emptySet(),
			state = null,
			authors = emptySet(),
			source = source,
		)
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val response = webClient.httpGet("$apiUrl/comic/${manga.url}").parseJson()
		val data = response.getJSONObject("data")

		val title = data.getString("title")
		val cover = data.optJSONObject("cover")
		val description = data.optString("description", "").nullIfEmpty()
		val status = data.optString("status", "").nullIfEmpty()
		val authorsStr = data.optString("authors", "").nullIfEmpty()
		val comicId = data.getString("id")

		val coverUrl = cover?.let {
			"$cdnUrl/$comicId/${it.getString("id")}.${it.optString("f", "jpg")}"
		} ?: manga.coverUrl

		val tags = parseTags(data.optJSONArray("tags"))
		val authors = authorsStr?.split("•")?.map { it.trim() }?.filter { it.isNotBlank() }?.toSet().orEmpty()

		val chapters = mutableListOf<MangaChapter>()
		val chaptersArray = data.optJSONArray("chapters")
		if (chaptersArray != null) {
			for (i in 0 until chaptersArray.length()) {
				val ch = chaptersArray.getJSONObject(i)
				val chapterId = ch.getString("id")
				val number = ch.optDouble("number", 0.0).toFloat()
				val name = ch.optString("name", "").nullIfEmpty()
				val dateStr = ch.optString("created_date", "")
				val date = if (dateStr.isNotBlank()) parseDate(dateStr) else 0L

				val chapterTitle = buildString {
					append("Chapter ")
					append(number.toString().substringBefore(".0"))
				}

				chapters.add(
					MangaChapter(
						id = generateUid(chapterId),
						title = chapterTitle,
						number = number,
						volume = 0,
						url = JSONObject().apply {
							put("comicId", comicId)
							put("chapterId", chapterId)
						}.toString(),
						uploadDate = date,
						source = source,
						scanlator = null,
						branch = null,
					)
				)
			}
		}

		return manga.copy(
			title = title,
			coverUrl = coverUrl,
			description = description,
			tags = tags,
			authors = authors,
			state = parseState(status),
			chapters = chapters.reversed(),
		)
	}

	override suspend fun getRelatedManga(seed: Manga): List<Manga> = emptyList()

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val chapterData = JSONObject(chapter.url)
		val chapterId = chapterData.getString("chapterId")
		val comicId = chapterData.getString("comicId")

		val response = webClient.httpGet("$apiUrl/chapter2/$chapterId").parseJson()
		val data = response.getJSONObject("data")
		val images = data.getJSONArray("images")
		val actualComicId = data.optString("comicId", comicId)
		val actualChapterId = data.optString("id", chapterId)

		return (0 until images.length()).map { i ->
			val img = images.getJSONObject(i)
			val format = img.optString("format")
				.nullIfEmpty()
				?: img.optString("f").nullIfEmpty()
				?: "jpg"
			MangaPage(
				id = generateUid("$chapterId-$i"),
				url = "$cdnUrl/$actualComicId/$actualChapterId/${img.getString("id")}.$format",
				preview = null,
				source = source,
			)
		}
	}


	private fun parseDate(dateStr: String): Long = try {
		val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT)
		sdf.timeZone = TimeZone.getTimeZone("UTC")
		sdf.parse(dateStr)?.time ?: 0L
	} catch (_: Exception) { 0L }

	private fun parseState(status: String?): MangaState? = when (status) {
		"Ongoing" -> MangaState.ONGOING
		"Completed" -> MangaState.FINISHED
		"Hiatus" -> MangaState.PAUSED
		"Cancelled" -> MangaState.ABANDONED
		else -> null
	}

	private fun parseTags(tagsArray: JSONArray?): Set<MangaTag> {
		if (tagsArray == null) return emptySet()
		val tags = mutableSetOf<MangaTag>()
		for (i in 0 until tagsArray.length()) {
			val tagObj = tagsArray.getJSONObject(i)
			tags.add(
				MangaTag(
					key = tagObj.getString("id"),
					title = tagObj.getString("name"),
					source = source,
				)
			)
		}
		return tags
	}
}
