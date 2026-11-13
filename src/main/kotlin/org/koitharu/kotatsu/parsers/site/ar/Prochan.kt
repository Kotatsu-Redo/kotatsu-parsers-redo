package org.koitharu.kotatsu.parsers.site.ar

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
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

@Broken("Site responds but parser is broken or outdated")
@MangaSourceParser("PROCHAN", "Prochan", "ar")
internal class Prochan(context: MangaLoaderContext) : PagedMangaParser(
	context,
	source = MangaParserSource.PROCHAN,
	pageSize = 18,
) {
	override val configKeyDomain = ConfigKey.Domain("prochan.pro")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.ALPHABETICAL,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
		)

	private val dateFormat by lazy {
		SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", sourceLocale)
	}

	private val baseUrl get() = "https://$domain"

	override suspend fun getFilterOptions() = MangaListFilterOptions()

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://prochan.pro/api/public/series/search?")
			append("status=approved")
			append("&limit=18")
			append("&page=$page")

			val sort = when (order) {
				SortOrder.UPDATED -> "latest_chapter"
				SortOrder.POPULARITY -> "popular"
				SortOrder.ALPHABETICAL -> "az"
				else -> "latest_chapter"
			}
			append("&sort=$sort")

			if (!filter.query.isNullOrEmpty()) {
				append("&search=${filter.query.urlEncoded()}")
			}
		}

		val response = webClient.httpGet(url)
		val body = response.body.string()
		val json = JSONObject(body)
		val data = json.optJSONArray("data") ?: return emptyList()

		return data.mapJSONNotNull { item ->
			val type = item.optString("type")
			if (type == "novel") {
				return@mapJSONNotNull null
			}

			val id = item.optInt("id")
			val slug = item.optString("slug")
			val title = item.optString("title")
			val coverUrl = item.optString("coverImage")
			val metadata = item.optJSONObject("metadata") ?: JSONObject()

			val progress = metadata.optString("progress", "")
			val state = when {
				progress.contains("مستمر", ignoreCase = true) -> MangaState.ONGOING
				progress.contains("مكتمل", ignoreCase = true) -> MangaState.FINISHED
				else -> null
			}

			val url = "/series/$type/$id/$slug"

			Manga(
				id = generateUid(url),
				title = title,
				altTitles = emptySet(),
				url = url,
				publicUrl = url.toAbsoluteUrl(domain),
				rating = RATING_UNKNOWN,
				contentRating = null,
				coverUrl = coverUrl,
				tags = emptySet(),
				state = state,
				authors = emptySet(),
				description = null,
				chapters = null,
				source = source,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
		val doc = webClient.httpGet(manga.publicUrl).parseHtml()
		val chaptersDeferred = async { fetchChapters(manga.url) }

		val description = parseDescriptionFromScript(doc)
		val altTitles = parseAltTitlesFromScript(doc)

		manga.copy(
			description = description,
			altTitles = altTitles,
			chapters = chaptersDeferred.await(),
		)
	}

	private fun parseDescriptionFromScript(doc: Document): String? {
		val scripts = doc.select("script:containsData(__next_f.push)")
		for (script in scripts) {
			val content = script.data()
			val descMatch = Regex("\"description\"\\s*:\\s*\"([^\"]+)\"").find(content)
			if (descMatch != null) {
				return descMatch.groupValues[1]
					.replace("\\n", "\n")
					.replace("\\\"", "\"")
			}
		}
		return null
	}

	private fun parseAltTitlesFromScript(doc: Document): Set<String> {
		val scripts = doc.select("script:containsData(__next_f.push)")
		for (script in scripts) {
			val content = script.data()
			val altTitlesMatch = Regex("\"altTitles\"\\s*:\\s*(\\[[^\\]]+\\])").find(content)
			if (altTitlesMatch != null) {
				return try {
					val altTitlesArray = JSONArray(altTitlesMatch.groupValues[1])
					altTitlesArray.mapJSONToSet { it as String }
				} catch (e: Exception) {
					emptySet()
				}
			}
		}
		return emptySet()
	}

	private suspend fun fetchChapters(mangaUrl: String): List<MangaChapter> {
		val parts = mangaUrl.split("/").filter { it.isNotEmpty() }
		if (parts.size < 3) return emptyList()

		val type = parts[1]
		val id = parts[2]

		val url = "https://prochan.net/api/public/$type/$id/chapters?page=1&limit=30&order=asc"
		val response = webClient.httpGet(url)
		val body = response.body.string()
		val json = JSONObject(body)
		val data = json.optJSONArray("data") ?: return emptyList()

		return data.mapJSONNotNull { item ->
			val chapterId = item.optInt("id")
			val chapterNumber = item.optString("chapter_number")
			val title = item.optString("title")
			val chapterNum = chapterNumber.toFloatOrNull() ?: 0f

			val chapterTitle = title.takeIf { it.isNotBlank() && it != "null" }

			val chapterUrl = "$mangaUrl/$chapterId/$chapterNumber"

			MangaChapter(
				id = generateUid(chapterUrl),
				title = chapterTitle,
				number = chapterNum,
				volume = 0,
				url = chapterUrl,
				scanlator = null,
				uploadDate = 0,
				branch = null,
				source = source,
			)
		}
	}

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
        val scripts = doc.select("script:containsData(__next_f.push)")
        val regex = Regex("""\\?"appImages\\?"\s*:\s*(\[.+?\])""")

        for (script in scripts) {
            val content = script.data()

            val match = regex.find(content)
            if (match != null) {
                return try {
                    // The captured string is likely inside a JS string literal, so it has escaped quotes (e.g., \")
                    // We need to unescape them to make it valid JSON.
                    val rawJson = match.groupValues[1]
                    val cleanJson = rawJson.replace("\\\"", "\"")

                    val appImagesArray = JSONArray(cleanJson)

                    appImagesArray.mapJSONNotNull { imageObj ->
                        if (imageObj is JSONObject) {
                            val desktopUrl = imageObj.optString("desktop")
                            if (desktopUrl.isNotBlank()) {
                                MangaPage(
                                    id = generateUid(desktopUrl),
                                    url = desktopUrl,
                                    preview = null,
                                    source = source,
                                )
                            } else {
                                null
                            }
                        } else {
                            null
                        }
                    }
                } catch (e: Exception) {
                    // If parsing fails for this specific script, continue to the next
                    continue
                }
            }
        }

        return emptyList()
    }
}
