package org.koitharu.kotatsu.parsers.site.ar

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("DILARTUBE", "Dilar Tube", "ar", ContentType.MANGA)
internal class DilarTube(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.DILARTUBE, 24) {

    override val configKeyDomain = ConfigKey.Domain("v2.dilar.tube")

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
        )

    override val availableSortOrders: Set<SortOrder> = setOf(SortOrder.RELEVANCE)

    override suspend fun getFilterOptions() = MangaListFilterOptions()

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val hasSearch = !filter.query.isNullOrBlank()

        if (!hasSearch) {
            val url = "https://v2.dilar.tube/api/series/?page=$page"
            val response = webClient.httpGet(url).parseJson()
            val series = response.getJSONArray("series")
            return (0 until series.length()).map { i ->
                parseMangaFromJson(series.getJSONObject(i))
            }
        }

        // Search: use quick_search — response is a JSONArray of categories
        val url = "https://dilar.tube/api/search/quick_search"
        val jsonBody = JSONObject().apply {
            put("query", filter.query)
            put("includes", JSONArray().apply {
                put("Manga")
                put("Team")
                put("Member")
            })
        }

        val response = webClient.httpPost(url.toHttpUrl(), jsonBody).parseJsonArray()
        for (i in 0 until response.length()) {
            val category = response.getJSONObject(i)
            if (category.optString("class") == "Manga") {
                val data = category.getJSONArray("data")
                return (0 until data.length()).map { j ->
                    parseMangaFromJson(data.getJSONObject(j))
                }
            }
        }
        return emptyList()
    }

    private fun parseMangaFromJson(json: JSONObject): Manga {
        val id = json.getInt("id")
        val title = json.getString("title")
        val cover = json.optString("cover", "")
        val summary = json.optString("summary", "")

        // Build cover URL - rollback to original working structure
        val coverUrl = if (cover.isNotEmpty()) {
            if (cover.startsWith("http")) {
                cover
            } else {
                val coverName = cover.substringBeforeLast('.') + ".webp"
                "https://dilar.tube/uploads/manga/cover/$id/large_$coverName"
            }
        } else ""

        val rating = json.optString("rating", "0.00").toFloatOrNull()?.div(5f) ?: RATING_UNKNOWN

        // Get alternative titles from synonyms
        val synonyms = json.optJSONObject("synonyms")
        val altTitles = mutableSetOf<String>()
        synonyms?.let { syn ->
            syn.optString("arabic")?.takeIf { it.isNotEmpty() && it != "null" }?.let { altTitles.add(it) }
            syn.optString("english")?.takeIf { it.isNotEmpty() && it != "null" }?.let { altTitles.add(it) }
            syn.optString("japanese")?.takeIf { it.isNotEmpty() && it != "null" }?.let { altTitles.add(it) }
            syn.optString("alternative")?.takeIf { it.isNotEmpty() && it != "null" }?.let { altTitles.add(it) }
        }

        val status = json.optString("story_status", "")
        val state = when (status.lowercase()) {
            "completed" -> MangaState.FINISHED
            "ongoing" -> MangaState.ONGOING
            "hiatus" -> MangaState.PAUSED
            else -> null
        }

        return Manga(
            id = generateUid(id.toLong()),
            title = title,
            url = "/series/$id",
            publicUrl = "https://v2.dilar.tube/series/$id",
            coverUrl = coverUrl,
            source = source,
            rating = rating,
            altTitles = altTitles,
            contentRating = ContentRating.SAFE,
            tags = emptySet(),
            state = state,
            authors = emptySet(),
            largeCoverUrl = null,
            description = summary.takeIf { it.isNotEmpty() },
            chapters = null,
        )
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val id = manga.url.substringAfterLast("/")
        val url = "https://v2.dilar.tube/api/series/$id"
        val json = webClient.httpGet(url).parseJson()

        val title = json.getString("title")
        val summary = json.optString("summary").nullIfEmpty()
        
        val cover = json.optString("cover").nullIfEmpty()
        val coverUrl = if (cover != null) {
            val coverName = cover.substringBeforeLast('.') + ".webp"
            "https://dilar.tube/uploads/manga/cover/$id/large_$coverName"
        } else manga.coverUrl

        val statusStr = json.optString("story_status")
        val state = when (statusStr?.lowercase()) {
            "ongoing" -> MangaState.ONGOING
            "completed" -> MangaState.FINISHED
            "hiatus" -> MangaState.ONGOING
            else -> null
        }

        val authors = mutableSetOf<String>()
        json.optJSONObject("creator")?.let {
            authors.add(it.getString("nick"))
        }

        val tags = mutableSetOf<MangaTag>()
        val categories = json.optJSONArray("categories")
        if (categories != null) {
            for (i in 0 until categories.length()) {
                val cat = categories.getJSONObject(i)
                tags.add(MangaTag(
                    key = cat.getInt("id").toString(),
                    title = cat.getString("name"),
                    source = source
                ))
            }
        }

        return manga.copy(
            title = title,
            description = summary,
            coverUrl = coverUrl,
            state = state,
            authors = authors,
            tags = tags,
            chapters = getChapters(id),
        )
    }

    private suspend fun getChapters(seriesId: String): List<MangaChapter> {
        val url = "https://v2.dilar.tube/api/series/$seriesId/chapters"
        val response = webClient.httpGet(url).parseJson()
        val chaptersJson = response.getJSONArray("chapters")
        val chapters = mutableListOf<MangaChapter>()

        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)

        for (i in 0 until chaptersJson.length()) {
            val item = chaptersJson.getJSONObject(i)
            val releases = item.getJSONArray("releases")
            if (releases.length() == 0) continue

            val release = releases.getJSONObject(0)
            val releaseId = release.getInt("id")
            
            val chapterNum = item.optString("chapter").toFloatOrNull() ?: 0f
            val volNum = item.optString("volume").toIntOrNull() ?: 0
            val title = item.optString("title").nullIfEmpty() ?: ""
            
            val dateStr = item.optString("created_at")
            val date = try {
                dateFormat.parse(dateStr)?.time ?: 0L
            } catch (e: Exception) {
                0L
            }

            chapters.add(
                MangaChapter(
                    id = generateUid(releaseId.toString()),
                    title = title,
                    number = chapterNum,
                    volume = volNum,
                    url = "/api/chapters/$releaseId",
                    uploadDate = date,
                    source = source,
                    scanlator = null,
                    branch = null
                )
            )
        }
        return chapters.reversed()
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val id = chapter.url.substringAfterLast("/")
        val url = "https://v2.dilar.tube/api/chapters/$id"
        val json = webClient.httpGet(url).parseJson()
        val pagesJson = json.getJSONArray("pages")
        val storageKey = json.optString("storage_key").nullIfEmpty()

        return (0 until pagesJson.length()).map { i ->
            val page = pagesJson.getJSONObject(i)
            val imageUrl = page.getString("url")
            
            val fullUrl = if (imageUrl.startsWith("http")) {
                imageUrl
            } else {
                if (storageKey != null) {
                    "https://dilar.tube/uploads/releases/$storageKey/hq/$imageUrl"
                } else {
                    "https://dilar.tube/uploads/$imageUrl"
                }
            }

            MangaPage(
                id = generateUid("$id-$i"),
                url = fullUrl,
                preview = null,
                source = source
            )
        }
    }
}
