package org.koitharu.kotatsu.parsers.site.en

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("ATSUMOE", "Atsu.moe", "en")
internal class AtsuMoe(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.ATSUMOE, pageSize = 40) {

    override val configKeyDomain = ConfigKey.Domain("atsu.moe")
    private val apiUrl = "https://$domain/api/"
    private val showAdultKey = ConfigKey.ShowSuspiciousContent(false)

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ENGLISH).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(showAdultKey)
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.POPULARITY,
        SortOrder.UPDATED,
        SortOrder.RATING,
        SortOrder.ALPHABETICAL,
        SortOrder.ADDED
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
            isMultipleTagsSupported = true,
            isTagsExclusionSupported = true
        )

    // The site also lists about 6000 fine-grained tags, too many to offer as filters. Only genres
    // are offered; a tag opened from a manga's details page still filters through `tagIds`.
    override suspend fun getFilterOptions(): MangaListFilterOptions {
        val json = webClient.httpGet("${apiUrl}explore/availableFilters").parseJson()
        val genres = json.getJSONArray("genres")
        return MangaListFilterOptions(
            availableTags = (0 until genres.length()).mapTo(LinkedHashSet()) { i ->
                parseGenre(genres.getJSONObject(i))
            },
            availableStates = EnumSet.of(
                MangaState.ONGOING,
                MangaState.FINISHED,
                MangaState.PAUSED,
                MangaState.ABANDONED
            ),
            availableContentTypes = EnumSet.of(
                ContentType.MANGA,
                ContentType.MANHWA,
                ContentType.MANHUA,
                ContentType.OTHER
            )
        )
    }

    // The default lookup picks an icon path that the site answers with its HTML page
    override suspend fun getFavicons(): Favicons {
        return Favicons(listOf(Favicon("https://$domain/favicon.ico", 32, null)), domain)
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        if (filter.isEmpty()) {
            when (order) {
                SortOrder.POPULARITY -> return getHomePage("popular", page, timeframe = "daily")
                SortOrder.UPDATED -> return getHomePage("recentlyUpdated", page)
                else -> Unit
            }
        }
        return getSearchPage(page, order, filter)
    }

    private suspend fun getHomePage(path: String, page: Int, timeframe: String? = null): List<Manga> {
        val url = "${apiUrl}home2/$path".toHttpUrl().newBuilder()
            .addQueryParameter("offset", ((page - 1) * pageSize).toString())
            .addQueryParameter("limit", pageSize.toString())
            .addQueryParameter("types", "Manga,Manwha,Manhua,OEL")
            .addQueryParameter("mediums", "Comic")
        if (timeframe != null) {
            url.addQueryParameter("timeframe", timeframe)
        }
        if (config[showAdultKey]) {
            url.addQueryParameter("adult", "1")
        }

        val json = webClient.httpGet(url.build().toString()).parseJson()
        val items = json.getJSONArray("items")

        return (0 until items.length()).map { i ->
            val item = items.getJSONObject(i)
            parseManga(item)
        }
    }

    private suspend fun getSearchPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val query = filter.query?.takeUnless { it.isBlank() }
        val url = "https://$domain/collections/manga/documents/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query ?: "*")
            .addQueryParameter("filter_by", buildSearchFilter(filter))
            .addQueryParameter("page", page.toString())
            .addQueryParameter("per_page", pageSize.toString())
            .addQueryParameter("include_fields", "id,title,englishTitle,poster,posterMedium,isAdult")
        if (query != null) {
            url.addQueryParameter("query_by", "title,englishTitle,otherNames,authors")
                .addQueryParameter("query_by_weights", "4,3,2,1")
                .addQueryParameter("num_typos", "4,3,2,1")
        } else {
            val sortBy = when (order) {
                SortOrder.RATING -> "mbRating:desc"
                SortOrder.ALPHABETICAL -> "title:asc"
                // The search index has no last-update field, so a filtered "updated" list falls back to it too
                SortOrder.ADDED, SortOrder.UPDATED -> "dateAdded:desc"
                else -> "views:desc"
            }
            url.addQueryParameter("sort_by", sortBy)
        }

        val json = webClient.httpGet(url.build().toString()).parseJson()
        val hits = json.getJSONArray("hits")

        return (0 until hits.length()).map { i ->
            val hit = hits.getJSONObject(i)
            val document = hit.getJSONObject("document")
            parseManga(document)
        }
    }

    private fun buildSearchFilter(filter: MangaListFilter): String {
        val parts = mutableListOf("hidden:!=true", "medium:!=[`Novel`]", "views:>0")
        if (!config[showAdultKey]) {
            parts += "isAdult:=false"
        }
        val (includedGenres, includedTags) = filter.tags.partition { it.key.startsWith(GENRE_KEY_PREFIX) }
        includedGenres.mapTo(parts) { "genreIds:=`${it.key.removePrefix(GENRE_KEY_PREFIX)}`" }
        includedTags.mapTo(parts) { "tagIds:=`${it.key}`" }
        val (excludedGenres, excludedTags) = filter.tagsExclude.partition { it.key.startsWith(GENRE_KEY_PREFIX) }
        if (excludedGenres.isNotEmpty()) {
            parts += "genreIds:!=[${excludedGenres.joinToString(",") { "`${it.key.removePrefix(GENRE_KEY_PREFIX)}`" }}]"
        }
        if (excludedTags.isNotEmpty()) {
            parts += "tagIds:!=[${excludedTags.joinToString(",") { "`${it.key}`" }}]"
        }
        val types = filter.types.flatMap { type ->
            when (type) {
                ContentType.MANGA -> listOf("Manga")
                ContentType.MANHWA -> listOf("Manwha")
                ContentType.MANHUA -> listOf("Manhua")
                ContentType.OTHER -> listOf("OEL", "Other")
                else -> emptyList()
            }
        }
        if (types.isNotEmpty()) {
            parts += "type:=[${types.joinToString(",") { "`$it`" }}]"
        }
        val statuses = filter.states.mapNotNull { state ->
            when (state) {
                MangaState.ONGOING -> "Ongoing"
                MangaState.FINISHED -> "Completed"
                MangaState.PAUSED -> "Hiatus"
                MangaState.ABANDONED -> "Canceled"
                else -> null
            }
        }
        if (statuses.isNotEmpty()) {
            parts += "status:=[${statuses.joinToString(",") { "`$it`" }}]"
        }
        return parts.joinToString(" && ")
    }

    private fun parseGenre(json: JSONObject) = MangaTag(
        key = GENRE_KEY_PREFIX + json.getString("id"),
        title = json.getString("name"),
        source = source
    )

    private fun parseManga(json: JSONObject): Manga {
        val id = json.getString("id")
        val title = json.optString("title").ifEmpty {
            json.optString("englishTitle", "Unknown")
        }

        // List results have "mediumImage"/"image", search results have "posterMedium"/"poster"
        val imagePath = json.getStringOrNull("mediumImage")
            ?: json.getStringOrNull("posterMedium")
            ?: json.getStringOrNull("image")
            ?: json.getStringOrNull("poster")

        return Manga(
            id = generateUid(id),
            title = title,
            altTitles = emptySet(),
            url = "/manga/$id",
            publicUrl = "https://$domain/manga/$id",
            rating = RATING_UNKNOWN,
            contentRating = if (json.optBoolean("isAdult")) ContentRating.ADULT else ContentRating.SAFE,
            coverUrl = imagePath?.let(::toImageUrl),
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
        val description = mangaPage.getStringOrNull("synopsis").orEmpty()

        // Parse poster object
        val posterObj = mangaPage.optJSONObject("poster")
        val posterImage = posterObj?.getStringOrNull("mediumImage") ?: posterObj?.getStringOrNull("image")
        val largePosterImage = posterObj?.getStringOrNull("largeImage")

        // Parse genres and tags
        val genresArray = mangaPage.optJSONArray("genres")
        val genres = if (genresArray != null) {
            (0 until genresArray.length()).map { i -> parseGenre(genresArray.getJSONObject(i)) }.toSet()
        } else emptySet<MangaTag>()
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
            "canceled", "cancelled" -> MangaState.ABANDONED
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
            coverUrl = posterImage?.let(::toImageUrl) ?: manga.coverUrl,
            largeCoverUrl = largePosterImage?.let(::toImageUrl),
            contentRating = if (mangaPage.optBoolean("isAdult")) ContentRating.ADULT else ContentRating.SAFE,
            tags = genres + tags,
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

        val chapters = (0 until chaptersArray.length())
            .map { i ->
                val chapter = chaptersArray.getJSONObject(i)
                val groupName = scanlatorMap[chapter.optString("scanlationMangaId")]
                // Only expose branches when several teams exist, so single-team titles
                // don't get a pointless branch selector.
                parseChapter(chapter, mangaId, branch = groupName.takeIf { multipleGroups }, scanlator = groupName)
            }
            // Oldest first overall; each branch the app splits out stays in number order too
            .sortedWith(compareBy({ it.number }, { it.branch }, { it.uploadDate }))

        // A team can hold several uploads under one number: re-uploads with other page counts, and
        // misnumbered chapters ("Chapterr 148" as number 159). They can't be told apart, so all are
        // kept in place; the newest keeps the number and the others become unnumbered.
        val newestUploads = chapters.associateBy { it.branch to it.number }
        return chapters.map { chapter ->
            if (newestUploads[chapter.branch to chapter.number] === chapter) chapter else chapter.copy(number = 0f)
        }
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
            val fullUrl = toImageUrl(imagePath)

            MangaPage(
                id = generateUid(fullUrl),
                url = fullUrl,
                preview = null,
                source = source
            )
        }
    }

    private fun toImageUrl(path: String): String = toCdnUrl(
        when {
            path.startsWith("http") -> path
            path.startsWith("//") -> "https:$path"
            else -> "https://$domain/static/${path.removePrefix("/").removePrefix("static/")}"
        },
    )

    // Images are served from the `cdn.` subdomain; the main domain no longer serves them
    private fun toCdnUrl(url: String): String {
        val host = url.toHttpUrl().host
        return if (host.startsWith("cdn.")) url else url.replaceFirst(PROTOCOL_REGEX, "https://cdn.")
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.url.host.startsWith("cdn.")) {
            return chain.proceed(request)
        }
        val newRequest = request.newBuilder()
            .header("Accept", "image/avif,image/webp,*/*")
            .header("Referer", "https://$domain/")
            .build()
        return chain.proceed(newRequest)
    }

    override suspend fun getRelatedManga(seed: Manga): List<Manga> {
        return emptyList()
    }

    private companion object {
        const val GENRE_KEY_PREFIX = "genre:"
        val PROTOCOL_REGEX = Regex("^https?:?//")
    }
}
