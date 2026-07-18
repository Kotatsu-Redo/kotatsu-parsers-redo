package org.koitharu.kotatsu.parsers.site.en

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("ATSUMOE", "Atsu.moe", "en")
internal class AtsuMoe(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.ATSUMOE, pageSize = 24) {

    override val configKeyDomain = ConfigKey.Domain("atsu.moe")
    private val apiUrl = "https://$domain/api/"

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ENGLISH).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.POPULARITY,
        SortOrder.UPDATED
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true
        )

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        return MangaListFilterOptions()
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val endpoint = when (order) {
            SortOrder.POPULARITY -> "infinite/trending"
            SortOrder.UPDATED -> "infinite/recentlyUpdated"
            else -> "infinite/trending"
        }

        val url = "${apiUrl}$endpoint".toHttpUrl().newBuilder()
            .addQueryParameter("page", (page - 1).toString())
            .addQueryParameter("types", "Manga,Manwha,Manhua,OEL")
            .build()

        if (!filter.query.isNullOrEmpty()) {
            return getSearchPage(page, filter.query)
        }

        val json = webClient.httpGet(url.toString()).parseJson()
        val items = json.getJSONArray("items")

        return (0 until items.length()).map { i ->
            val item = items.getJSONObject(i)
            parseManga(item)
        }
    }

    private suspend fun getSearchPage(page: Int, query: String): List<Manga> {
        val url = "https://$domain/collections/manga/documents/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("query_by", "title,englishTitle,otherNames")
            .addQueryParameter("limit", pageSize.toString())
            .addQueryParameter("page", page.toString())
            .addQueryParameter("query_by_weights", "3,2,1")
            .addQueryParameter("include_fields", "id,title,englishTitle,poster")
            .addQueryParameter("num_typos", "4,3,2")
            .build()

        val json = webClient.httpGet(url.toString()).parseJson()
        val hits = json.getJSONArray("hits")

        return (0 until hits.length()).map { i ->
            val hit = hits.getJSONObject(i)
            val document = hit.getJSONObject("document")
            parseManga(document)
        }
    }

    private fun parseManga(json: JSONObject): Manga {
        val id = json.getString("id")
        val title = json.optString("title").ifEmpty {
            json.optString("englishTitle", "Unknown")
        }

        // List results have "image", search results have "poster"
        val image = json.optString("image")
        val poster = json.optString("poster")
        val imagePath = image.ifEmpty { poster }

        val coverUrl = if (imagePath.isNotEmpty()) {
            when {
                imagePath.startsWith("http") -> imagePath
                imagePath.startsWith("/static/") -> "https://$domain$imagePath"
                imagePath.startsWith("/") -> "https://$domain$imagePath"
                else -> "https://$domain/static/$imagePath"
            }
        } else null

        return Manga(
            id = generateUid(id),
            title = title,
            altTitles = emptySet(),
            url = "/manga/$id",
            publicUrl = "https://$domain/manga/$id",
            rating = RATING_UNKNOWN,
            contentRating = ContentRating.SAFE,
            coverUrl = coverUrl,
            tags = emptySet(),
            state = null,
            authors = emptySet(),
            source = source
        )
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val mangaId = manga.url.substringAfterLast("/")
        val json = webClient.httpGet("${apiUrl}manga/page?id=$mangaId").parseJson()
        val mangaPage = json.getJSONObject("mangaPage")

        val title = mangaPage.optString("title").ifEmpty {
            mangaPage.optString("englishTitle", manga.title)
        }
        val description = mangaPage.optString("synopsis")

        // Parse poster object
        val posterObj = mangaPage.optJSONObject("poster")
        val posterImage = posterObj?.optString("image")
        val coverUrl = if (!posterImage.isNullOrEmpty()) {
            "https://$domain/static/$posterImage"
        } else manga.coverUrl

        // Parse tags
        val tagsArray = mangaPage.optJSONArray("tags")
        val tags = if (tagsArray != null) {
            (0 until tagsArray.length()).map { i ->
                val tag = tagsArray.getJSONObject(i)
                MangaTag(
                    key = tag.getString("id"),
                    title = tag.getString("name"),
                    source = source
                )
            }.toSet()
        } else emptySet<MangaTag>()

        // Parse authors
        val authorsArray = mangaPage.optJSONArray("authors")
        val authors = if (authorsArray != null) {
            (0 until authorsArray.length()).mapNotNull { i ->
                val author = authorsArray.getJSONObject(i)
                author.optString("name").takeIf { it.isNotEmpty() }
            }.toSet()
        } else emptySet<String>()

        // Parse status
        val status = mangaPage.optString("status")
        val state = when (status.lowercase()) {
            "ongoing" -> MangaState.ONGOING
            "completed" -> MangaState.FINISHED
            "hiatus" -> MangaState.PAUSED
            "cancelled" -> MangaState.ABANDONED
            else -> null
        }

        // Map of scanlationMangaId -> scanlation group (team) name
        val scanlatorMap = LinkedHashMap<String, String>()
        mangaPage.optJSONArray("scanlators")?.let { arr ->
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                val id = s.optString("id")
                val name = s.optString("name")
                if (id.isNotEmpty() && name.isNotEmpty()) {
                    scanlatorMap[id] = name
                }
            }
        }

        // Atsu.moe serves the same title from multiple scanlation groups (teams). Split the
        // chapters into one branch per team so each team keeps its full chapter list, instead
        // of merging them into a single list with duplicate numbers.
        val chapters = fetchAllChapters(mangaId, scanlatorMap)

        return manga.copy(
            title = title,
            description = description,
            coverUrl = coverUrl,
            tags = tags,
            authors = authors,
            state = state,
            chapters = chapters
        )
    }

    // The `allChapters` endpoint returns every chapter (no pagination) with its scanlation
    // group (`scanlationMangaId`), unlike `manga/chapters` which caps/omits group info.
    private suspend fun fetchAllChapters(mangaId: String, scanlatorMap: Map<String, String>): List<MangaChapter> {
        val response = webClient.httpGet("${apiUrl}manga/allChapters?mangaId=$mangaId").parseJson()
        val chaptersArray = response.optJSONArray("chapters") ?: return emptyList()
        val multipleGroups = scanlatorMap.size > 1

        return (0 until chaptersArray.length())
            .map { i ->
                val chapter = chaptersArray.getJSONObject(i)
                val groupName = scanlatorMap[chapter.optString("scanlationMangaId")]
                // Only expose branches when several teams exist, so single-team titles
                // don't get a pointless branch selector.
                parseChapter(chapter, mangaId, branch = groupName.takeIf { multipleGroups }, scanlator = groupName)
            }
            .sortedWith(compareBy({ it.branch }, { it.number }))
    }

    private fun parseChapter(json: JSONObject, mangaId: String, branch: String?, scanlator: String?): MangaChapter {
        val chapterId = json.getString("id")
        val title = json.optString("title").takeIf { it.isNotEmpty() }
        val number = json.optDouble("number", 0.0).toFloat()

        return MangaChapter(
            id = generateUid("$mangaId/$chapterId"),
            title = title,
            number = number,
            volume = 0,
            url = "$mangaId/$chapterId",
            uploadDate = parseChapterDate(json.opt("createdAt")),
            source = source,
            scanlator = scanlator,
            branch = branch
        )
    }

    // `createdAt` is returned as epoch millis (number); tolerate an ISO string as a fallback
    private fun parseChapterDate(value: Any?): Long = when (value) {
        is Number -> value.toLong()
        is String -> value.takeIf { it.isNotEmpty() }
            ?.let { runCatching { dateFormat.parse(it)?.time }.getOrNull() }
            ?: 0L
        else -> 0L
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val (mangaId, chapterId) = chapter.url.split("/")
        val url = "${apiUrl}read/chapter".toHttpUrl().newBuilder()
            .addQueryParameter("mangaId", mangaId)
            .addQueryParameter("chapterId", chapterId)
            .build()

        val json = webClient.httpGet(url.toString()).parseJson()
        val readChapter = json.getJSONObject("readChapter")
        val pages = readChapter.getJSONArray("pages")

        return (0 until pages.length()).map { i ->
            val page = pages.getJSONObject(i)
            val imagePath = page.getString("image")
            val fullUrl = "https://$domain$imagePath"

            MangaPage(
                id = generateUid(fullUrl),
                url = fullUrl,
                preview = null,
                source = source
            )
        }
    }

    override suspend fun getRelatedManga(seed: Manga): List<Manga> {
        return emptyList()
    }
}
