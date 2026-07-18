package org.koitharu.kotatsu.parsers.site.mangareader.id

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.util.*

@MangaSourceParser("KOMIKCAST", "KomikCast", "id")
internal class Komikcast(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.KOMIKCAST, pageSize = 60) {

	override val userAgentKey = ConfigKey.UserAgent(
		"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"
	)

    val searchPageSize = 28
    override val configKeyDomain = ConfigKey.Domain("be.komikcast.cc")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.ALPHABETICAL,
		SortOrder.ALPHABETICAL_DESC,
		SortOrder.NEWEST,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = false,
			isSearchSupported = true,
		)

	override suspend fun getFilterOptions(): MangaListFilterOptions {
		val genreMap = fetchGenreMap()
		return MangaListFilterOptions(
			availableTags = genreMap.values.toSet(),
			availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED, MangaState.PAUSED),
			availableContentTypes = EnumSet.of(
				ContentType.MANGA,
				ContentType.MANHWA,
				ContentType.MANHUA,
			),
		)
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://")
			append(domain)
			append("/series")

			if (!filter.query.isNullOrEmpty()) {
				// Search uses a simple filter URL
				append("?filter=title=like=\"")
				append(filter.query)
				append("\",nativeTitle=like=\"")
				append(filter.query)
				append("\"")
			} else {
				// Normal listing with pagination
				append("?page=")
				append(page + 1)
				append("&take=")
				append(pageSize)
				append("&takeChapter=2")
				append("&includeMeta=true")

				filter.types.oneOrThrowIfMany()?.let { contentType ->
					append("&type=")
					append(when (contentType) {
						ContentType.MANGA -> "manga"
						ContentType.MANHWA -> "manhwa"
						ContentType.MANHUA -> "manhua"
						else -> ""
					})
				}

				filter.tags.takeIf { it.isNotEmpty() }?.let { tags ->
					append("&genreIds=")
					append(tags.joinToString(",") { it.title })
				}

				if (filter.states.isNotEmpty()) {
					filter.states.oneOrThrowIfMany()?.let { state ->
						append("&status=")
						append(when (state) {
							MangaState.ONGOING -> "ongoing"
							MangaState.FINISHED -> "completed"
							MangaState.PAUSED -> "hiatus"
							else -> ""
						})
					}
				}
			}
		}

		return parseSeriesList(webClient.httpGet(url).body?.string() ?: "")
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val slug = manga.url.substringAfter("/series/")
		val url = buildString {
			append("https://")
			append(domain)
			append("/series/")
			append(slug)
			append("?includeMeta=true")
		}

		val json = webClient.httpGet(url).body?.string() ?: throw Exception("Failed to fetch manga details")
		val seriesData = parseSeriesJson(json)

		val chaptersUrl = buildString {
			append("https://")
			append(domain)
			append("/series/")
			append(slug)
			append("/chapters")
		}
		val chaptersJson = webClient.httpGet(chaptersUrl).body?.string() ?: ""
		val chapters = parseChaptersJson(chaptersJson, slug)

		return manga.copy(
			title = seriesData.title,
			description = seriesData.synopsis,
			state = when (seriesData.status.lowercase()) {
				"ongoing" -> MangaState.ONGOING
				"completed" -> MangaState.FINISHED
				"hiatus" -> MangaState.PAUSED
				else -> null
			},
			authors = setOfNotNull(seriesData.author),
			tags = seriesData.genres.mapNotNull { it ->
				MangaTag(
					title = it.name,
					key = it.id.toString(),
					source = source,
				)
			}.toSet(),
			coverUrl = seriesData.coverImage,
			rating = if (seriesData.rating > 0) seriesData.rating / 10f else RATING_UNKNOWN,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val slug = chapter.url.substringAfter("/series/").substringBefore("/chapters")
		val chapterIndex = chapter.url.substringAfterLast("/")

		val url = buildString {
			append("https://")
			append(domain)
			append("/series/")
			append(slug)
			append("/chapters/")
			append(chapterIndex)
		}

		val json = webClient.httpGet(url).body?.string() ?: throw Exception("Failed to fetch chapter pages")
		val responseObj = org.json.JSONObject(json)
		val dataObj = responseObj.getJSONObject("data").getJSONObject("data")
		val imagesArray = dataObj.getJSONArray("images")

		return (0 until imagesArray.length()).mapNotNull { i ->
			val imgUrl = imagesArray.getString(i)
			if (imgUrl.isEmpty()) return@mapNotNull null
			MangaPage(
				id = generateUid(imgUrl),
				url = imgUrl,
				preview = null,
				source = source,
			)
		}
	}

	override fun getRequestHeaders() = super.getRequestHeaders().newBuilder()
		.add("Referer", "https://v1.komikcast.fit/")
        .add("Origin", "https://v1.komikcast.fit")
		.build()

	private suspend fun fetchGenreMap(): Map<String, MangaTag> {
		val url = "https://$domain/genres"
		val jsonStr = webClient.httpGet(url).body?.string() ?: return emptyMap()

		val genreMap = mutableMapOf<String, MangaTag>()
		try {
			val dataArray = org.json.JSONObject(jsonStr).getJSONArray("data")
			for (i in 0 until dataArray.length()) {
				val genreObj = dataArray.getJSONObject(i)
				val name = genreObj.getJSONObject("data").getString("name")
				val id = genreObj.getInt("id")
				genreMap[name] = MangaTag(
					title = name,
					key = id.toString(),
					source = source,
				)
			}
		} catch (e: Exception) {
			e.printStackTrace()
		}
		return genreMap
	}

	private fun parseSeriesList(json: String): List<Manga> {
		val result = mutableListOf<Manga>()
		try {
			val responseObj = org.json.JSONObject(json)
			val dataArray = responseObj.getJSONArray("data")

			for (i in 0 until dataArray.length()) {
				val seriesObj = dataArray.getJSONObject(i)
				val data = seriesObj.getJSONObject("data")

				val slug = data.getString("slug")
				val relativeUrl = "/series/$slug"
				val rating = if (data.has("rating")) {
					data.getDouble("rating").toFloat() / 10f
				} else {
					RATING_UNKNOWN
				}

				val manga = Manga(
					id = generateUid(relativeUrl),
					url = relativeUrl,
					title = data.getString("title"),
					altTitles = emptySet(),
					publicUrl = "https://$domain$relativeUrl",
					rating = rating,
					contentRating = ContentRating.SAFE,
					coverUrl = data.getString("coverImage"),
					tags = emptySet(),
					state = when (data.optString("status").lowercase()) {
						"ongoing" -> MangaState.ONGOING
						"completed" -> MangaState.FINISHED
						else -> null
					},
					authors = setOfNotNull(data.optString("author").takeIf { it.isNotEmpty() }),
					source = source,
				)

				if (data.has("chapters")) {
					val chaptersArray = data.getJSONArray("chapters")
					val chapters = mutableListOf<MangaChapter>()
					for (j in 0 until chaptersArray.length()) {
						val chapterObj = chaptersArray.getJSONObject(j)
						val chapterData = chapterObj.getJSONObject("data")
						val chapterIndex = chapterData.getInt("index")

						val chapter = MangaChapter(
							id = generateUid("$relativeUrl/chapters/$chapterIndex"),
							title = chapterData.optString("title"),
							url = "$relativeUrl/chapters/$chapterIndex",
							number = (chaptersArray.length() - j).toFloat(),
							volume = 0,
							scanlator = null,
							uploadDate = parseChapterDate(chapterObj.optString("createdAt")),
							branch = null,
							source = source,
						)
						chapters.add(chapter)
					}
					result.add(manga.copy(chapters = chapters.reversed()))
				} else {
					result.add(manga)
				}
			}
		} catch (e: Exception) {
			e.printStackTrace()
		}
		return result
	}

	private fun parseSeriesJson(json: String): SeriesData {
		val responseObj = org.json.JSONObject(json)
		val dataObj = if (responseObj.has("data") && responseObj.getJSONObject("data").has("data")) {
			responseObj.getJSONObject("data").getJSONObject("data")
		} else {
			responseObj.getJSONObject("data")
		}

		val genres = mutableListOf<GenreData>()
		if (dataObj.has("genres") && !dataObj.isNull("genres")) {
			val genresArray = dataObj.getJSONArray("genres")
			for (i in 0 until genresArray.length()) {
				val genreObj = genresArray.getJSONObject(i)
				val genreData = genreObj.getJSONObject("data")
				genres.add(GenreData(
					id = genreObj.getInt("id"),
					name = genreData.getString("name"),
				))
			}
		}

		return SeriesData(
			title = dataObj.getString("title"),
			synopsis = dataObj.optString("synopsis"),
			status = dataObj.getString("status"),
			author = dataObj.optString("author").takeIf { it.isNotEmpty() },
			coverImage = dataObj.getString("coverImage"),
			rating = if (dataObj.has("rating")) dataObj.getDouble("rating").toFloat() else 0f,
			genres = genres,
		)
	}

	private fun parseChaptersJson(json: String, slug: String): List<MangaChapter> {
		val result = mutableListOf<MangaChapter>()
		try {
			val responseObj = org.json.JSONObject(json)
			val dataArray = responseObj.getJSONArray("data")

			for (i in 0 until dataArray.length()) {
				val chapterObj = dataArray.getJSONObject(i)
				val chapterData = chapterObj.getJSONObject("data")
				val chapterIndex = chapterData.getDouble("index")
				val chapterSlug = chapterData.optString("slug").takeIf { it.isNotEmpty() && it != "null" }
				val chapterIndexStr = if (chapterIndex % 1.0 == 0.0) chapterIndex.toInt().toString() else chapterIndex.toString()
				val chapterIdentifier = chapterSlug ?: chapterIndexStr
				val chapterUrl = "/series/$slug/chapters/$chapterIdentifier"

				val chapterTitle = chapterData.optString("title").takeIf { it.isNotEmpty() && it != "null" } ?: "Chapter $chapterIndexStr"

				val chapter = MangaChapter(
					id = generateUid(chapterUrl),
					title = chapterTitle,
					url = chapterUrl,
					number = chapterIndex.toFloat(),
					volume = 0,
					scanlator = null,
					uploadDate = parseChapterDate(chapterObj.optString("createdAt")),
					branch = null,
					source = source,
				)
				result.add(chapter)
			}
		} catch (e: Exception) {
			e.printStackTrace()
		}
		return result.reversed()
	}


	private fun parseChapterDate(dateStr: String?): Long {
		if (dateStr.isNullOrEmpty()) return 0
		return try {
			java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ENGLISH).parse(dateStr).time
		} catch (e: Exception) {
			0L
		}
	}

	private data class SeriesData(
		val title: String,
		val synopsis: String,
		val status: String,
		val author: String?,
		val coverImage: String,
		val rating: Float,
		val genres: List<GenreData>,
	)

	private data class GenreData(
		val id: Int,
		val name: String,
	)

}
