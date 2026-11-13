package org.koitharu.kotatsu.parsers.site.ar

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.Broken
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.mapJSONNotNull
import org.koitharu.kotatsu.parsers.util.json.mapJSONToSet
import java.text.SimpleDateFormat
import java.util.*

@Broken("Website under maintenance")
@MangaSourceParser("CROWSCANS", "VipManga", "ar")
internal class Vipmanga(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.CROWSCANS, 15) {

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.ALPHABETICAL,
		SortOrder.NEWEST,
	)

	override val configKeyDomain = ConfigKey.Domain("vipmanga.org")

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
		)

	private val dateFormat by lazy {
		SimpleDateFormat("yyyy-MM-dd HH:mm:ss", sourceLocale)
	}

	private val apiDateFormat by lazy {
		SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", sourceLocale)
	}

	private val baseUrl get() = "https://$domain"

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchAvailableGenres(),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("$baseUrl/api/manga?page=$page")
			append("&per_page=15")
			append("&include_chapters=true")
			append("&include_genres=true")

			when (order) {
				SortOrder.UPDATED -> append("&order=updated")
				SortOrder.POPULARITY -> append("&order=popular")
				SortOrder.ALPHABETICAL -> append("&order=alphabetical")
				SortOrder.NEWEST -> append("&order=newest")
				else -> append("&order=updated")
			}

			if (!filter.query.isNullOrEmpty()) {
				append("&search=${filter.query.urlEncoded()}")
			}

			if (filter.tags.isNotEmpty()) {
				val genreSlug = filter.tags.first().title.lowercase(Locale.ROOT)
				append("&genre=$genreSlug")
			}
		}

		val response = webClient.httpGet(url)
		val body = response.body.string()
		val json = JSONObject(body)

		if (!json.optBoolean("success", false)) {
			return emptyList()
		}

		val data = json.optJSONArray("data") ?: return emptyList()

		return data.mapJSONNotNull { item ->
			val mangaId = item.optInt("id")
			val title = item.optString("title")
			val link = item.optString("link")
			val url = mangaId.toString()
			val coverUrl = item.optString("image")
			val description = item.optString("description").ifEmpty { null }
			val statusText = item.optString("status", "")

			val state = when (statusText.lowercase(Locale.ROOT)) {
				"on-going", "ongoing" -> MangaState.ONGOING
				"completed" -> MangaState.FINISHED
				"hiatus" -> MangaState.PAUSED
				else -> null
			}

			val rating = item.optDouble("rating", 0.0).takeIf { it > 0 }?.div(10.0)?.toFloat() ?: RATING_UNKNOWN

			val genresArray = item.optJSONArray("genres")
			val tags = if (genresArray != null) {
				genresArray.mapJSONToSet { genreObj ->
					val genre = genreObj as JSONObject
					MangaTag(
						key = genre.optString("slug"),
						title = genre.optString("name"),
						source = source,
					)
				}
			} else {
				emptySet()
			}

			val chaptersArray = item.optJSONArray("chapters")
			val chapters = if (chaptersArray != null && chaptersArray.length() > 0) {
				parseChaptersFromArray(chaptersArray, mangaId)
			} else {
				null
			}

			Manga(
				id = generateUid(url),
				title = title,
				altTitles = emptySet(),
				url = url,
				publicUrl = link,
				rating = rating,
				contentRating = null,
				coverUrl = coverUrl,
				tags = tags,
				state = state,
				authors = emptySet(),
				description = description,
				chapters = chapters,
				source = source,
			)
		}
	}

	private fun parseChaptersFromArray(array: JSONArray, mangaId: Int): List<MangaChapter> {
		return array.mapJSONNotNull { item ->
			val chapterObj = item as JSONObject
			val chapterId = chapterObj.optInt("chapter_id")
			val chapterTitle = chapterObj.optString("name")
			val chapterNumber = chapterTitle.toFloatOrNull()
			val dateText = chapterObj.optString("date")

			val url = "$mangaId/$chapterId"

			val uploadDate = try {
				dateFormat.parse(dateText)?.time ?: 0L
			} catch (e: Exception) {
				0L
			}

			MangaChapter(
				id = generateUid(url),
				title = chapterTitle,
				number = chapterNumber ?: 0f,
				volume = 0,
				url = url,
				scanlator = null,
				uploadDate = uploadDate,
				branch = null,
				source = source,
			)
		}.sortedBy { it.number }
	}

	override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
		val mangaId = manga.url.toIntOrNull() ?: manga.id.toInt()
		val chaptersDeferred = async { fetchChapters(mangaId) }

		val url = "$baseUrl/api/manga/$mangaId"
		val response = webClient.httpGet(url)
		val body = response.body.string()
		val json = JSONObject(body)

		if (!json.optBoolean("success", false)) {
			return@coroutineScope manga.copy(
				chapters = chaptersDeferred.await(),
			)
		}

		val data = json.optJSONObject("data") ?: return@coroutineScope manga.copy(
			chapters = chaptersDeferred.await(),
		)

		val description = data.optString("description").ifEmpty { manga.description }
		val statusText = data.optString("status", "")
		val state = when (statusText.lowercase(Locale.ROOT)) {
			"on-going", "ongoing" -> MangaState.ONGOING
			"completed" -> MangaState.FINISHED
			"hiatus" -> MangaState.PAUSED
			else -> manga.state
		}

		val rating = data.optDouble("rating", 0.0).takeIf { it > 0 }?.div(10.0)?.toFloat() ?: manga.rating

		val genresArray = data.optJSONArray("genres")
		val tags = if (genresArray != null) {
			genresArray.mapJSONToSet { genreObj ->
				val genre = genreObj as JSONObject
				MangaTag(
					key = genre.optString("slug"),
					title = genre.optString("name"),
					source = source,
				)
			}
		} else {
			manga.tags
		}

		manga.copy(
			coverUrl = data.optString("image").ifEmpty { manga.coverUrl },
			description = description,
			state = state,
			tags = tags,
			rating = rating,
			chapters = chaptersDeferred.await(),
		)
	}

	private suspend fun fetchChapters(mangaId: Int): List<MangaChapter> = coroutineScope {
		val firstPageUrl = "$baseUrl/api/manga/$mangaId/chapters?page=1&per_page=20&order=oldest"
		val firstResponse = webClient.httpGet(firstPageUrl)
		val firstBody = firstResponse.body.string()
		val firstJson = JSONObject(firstBody)

		if (!firstJson.optBoolean("success", false)) {
			return@coroutineScope emptyList()
		}

		val pagination = firstJson.optJSONObject("pagination")
		val totalPages = pagination?.optInt("total_pages", 1) ?: 1

		val allChapters = mutableListOf<MangaChapter>()

		fun parseChapters(data: JSONArray): List<MangaChapter> {
			return data.mapJSONNotNull { item ->
				val chapterObj = item as JSONObject
				val chapterId = chapterObj.optString("id")
				val chapterTitle = chapterObj.optString("title")
				val chapterNumber = chapterObj.optInt("number", 0).toFloat()
				val publishDate = chapterObj.optString("publish_date")

				val url = "$mangaId/$chapterId"

				val uploadDate = try {
					dateFormat.parse(publishDate)?.time ?: 0L
				} catch (e: Exception) {
					0L
				}

				MangaChapter(
					id = generateUid(url),
					title = chapterTitle.ifEmpty { "Chapter $chapterNumber" },
					number = chapterNumber,
					volume = 0,
					url = url,
					scanlator = null,
					uploadDate = uploadDate,
					branch = null,
					source = source,
				)
			}
		}

		val firstData = firstJson.optJSONArray("data")
		if (firstData != null) {
			allChapters.addAll(parseChapters(firstData))
		}

		if (totalPages > 1) {
			val pageTasks = (2..totalPages).map { page ->
				async {
					val url = "$baseUrl/api/manga/$mangaId/chapters?page=$page&per_page=20&order=oldest"
					val response = webClient.httpGet(url)
					val body = response.body.string()
					val json = JSONObject(body)

					if (json.optBoolean("success", false)) {
						json.optJSONArray("data")?.let { data ->
							parseChapters(data)
						} ?: emptyList()
					} else {
						emptyList()
					}
				}
			}

			pageTasks.awaitAll().forEach { chapters ->
				allChapters.addAll(chapters)
			}
		}

		return@coroutineScope allChapters.sortedBy { it.number }
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val parts = chapter.url.split("/").filter { it.isNotEmpty() }
		if (parts.size < 2) return emptyList()

		val mangaId = parts[0]
		val chapterId = parts[1]
		val url = "$baseUrl/manga/$mangaId/read/$chapterId"

		val doc = webClient.httpGet(url).parseHtml()
		val scripts = doc.select("script:containsData(__next_f.push)")

		for (script in scripts) {
			val scriptContent = script.data()

			if (!scriptContent.contains("\\\"images\\\":")) {
				continue
			}

			val imagesMatch = Regex("""\\\"images\\\":\[([\s\S]*?)\],\\\"""").find(scriptContent)

			if (imagesMatch != null) {
				val escapedImagesJson = "[${imagesMatch.groupValues[1]}]"

				val imagesJson = escapedImagesJson
					.replace("\\\\", "\u0000")
					.replace("\\\"", "\"")
					.replace("\u0000", "\\")

				try {
					val imagesArray = JSONArray(imagesJson)
					val pages = mutableListOf<MangaPage>()

					for (i in 0 until imagesArray.length()) {
						val imageObj = imagesArray.getJSONObject(i)
						val imageUrl = imageObj.optString("url")

						if (imageUrl.isNotBlank()) {
							pages.add(
								MangaPage(
									id = generateUid(imageUrl),
									url = imageUrl,
									preview = null,
									source = source,
								)
							)
						}
					}

					if (pages.isNotEmpty()) {
						return pages
					}
				} catch (e: Exception) {
					continue
				}
			}
		}

		return emptyList()
	}

	private suspend fun fetchAvailableGenres(): Set<MangaTag> {
		val url = baseUrl
		val doc = webClient.httpGet(url).parseHtml()
		val genresSet = mutableSetOf<MangaTag>()

		val genreButtons = doc.select("button.px-4.py-2.rounded-full.text-sm.font-medium")
		for (button in genreButtons) {
			val genreName = button.selectFirst("span:first-child")?.text()?.trim()
			val countSpan = button.select("span:nth-child(2)")

			if (!genreName.isNullOrBlank() && countSpan.isNotEmpty()) {
				val slug = genreName.lowercase(Locale.ROOT).replace(" ", "-")
				genresSet.add(
					MangaTag(
						key = slug,
						title = genreName,
						source = source,
					)
				)
			}
		}

		return genresSet
	}
}
