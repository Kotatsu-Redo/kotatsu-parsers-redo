package org.koitharu.kotatsu.parsers.site.en

import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.exception.ParseException
import java.util.*

@MangaSourceParser("ALLMANGA", "AllManga", "en")
internal class AllManga(context: MangaLoaderContext) : PagedMangaParser(context, MangaParserSource.ALLMANGA, 20) {

    override val configKeyDomain = ConfigKey.Domain("allmanga.to")

    private val apiUrl = "https://api.allanime.day/api"
    private val imageCdn = "https://wp.youtube-anime.com/aln.youtube-anime.com"
    private val defaultImageDomain = "https://ytimgf.youtube-anime.com/"

    override fun getRequestHeaders(): okhttp3.Headers = super.getRequestHeaders().newBuilder()
        .add("Referer", "https://$domain/")
        .add("Origin", "https://$domain")
        .build()

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.ALPHABETICAL
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
            isMultipleTagsSupported = true,
            isTagsExclusionSupported = true
        )

    override suspend fun getFavicons(): Favicons {
        return Favicons.single("https://$domain/pics/avatar-w.png")
    }

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = fetchAvailableTags()
    )

    private suspend fun fetchAvailableTags(): Set<MangaTag> {
        val tags = mutableSetOf<MangaTag>()
        var page = 1
        var hasNext = true

        while (hasNext && page <= 5) {
            val variables = JSONObject().apply {
                put("search", JSONObject().apply {
                    put("format", "manga")
                    put("allowUnknown", false)
                    put("allowAdult", true)
                })
                put("page", page)
                put("limit", 100)
            }
            
            val response = graphQlQuery(TAGS_HASH, variables)
            val edges = response.optJSONObject("data")?.optJSONObject("queryTags")?.optJSONArray("edges") ?: break
            
            if (edges.length() == 0) break
            
            for (i in 0 until edges.length()) {
                val name = edges.getJSONObject(i).optString("name")
                if (name.isNotBlank()) {
                    tags.add(MangaTag(name, name, source))
                }
            }
            
            if (edges.length() < 100) hasNext = false
            page++
        }
        
        return tags
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val variables = JSONObject()

        variables.put("search", JSONObject().apply {
            if (!filter.query.isNullOrEmpty()) {
                put("query", filter.query)
            }
            put("isManga", true)
            put("allowAdult", true)
            put("allowUnknown", false)

            if (filter.tags.isNotEmpty() || filter.tagsExclude.isNotEmpty()) {
                val included = filter.tags.map { it.key }
                val excluded = filter.tagsExclude.map { it.key }
                if (included.isNotEmpty()) put("genres", JSONArray(included))
                if (excluded.isNotEmpty()) put("excludeGenres", JSONArray(excluded))
            }

            val sortByStr = when (order) {
                SortOrder.UPDATED -> "Latest_Update"
                SortOrder.ALPHABETICAL -> "Name_ASC"
                SortOrder.POPULARITY -> "Popular"
                else -> "Latest_Update"
            }
            put("sortBy", sortByStr)
        })
        variables.put("limit", 20)
        variables.put("page", page)
        variables.put("translationType", "sub")
        variables.put("countryOrigin", "ALL")

        val jsonResponse = graphQlQuery(SEARCH_HASH, variables)
        val data = jsonResponse.optJSONObject("data")?.optJSONObject("mangas") ?: return emptyList()
        val edges = data.optJSONArray("edges") ?: return emptyList()

        val mangaList = mutableListOf<Manga>()
        for (i in 0 until edges.length()) {
            val node = edges.getJSONObject(i)
            mangaList.add(parseMangaNode(node))
        }

        return mangaList
    }

    private fun parseMangaNode(node: JSONObject): Manga {
        val id = node.getString("_id")
        val slugTime = node.optString("slugTime", "")
        val url = "/manga/$id"
        return Manga(
            id = generateUid(url),
            title = node.optString("name").takeIf { it.isNotBlank() } ?: node.optString("englishName"),
            altTitles = emptySet<String>(),
            url = url,
            publicUrl = url.toAbsoluteUrl(domain),
            rating = RATING_UNKNOWN,
            contentRating = ContentRating.SAFE,
            coverUrl = node.optString("thumbnail").let { if (it.startsWith("http")) it else "$imageCdn/$it" },
            tags = emptySet<MangaTag>(),
            state = null,
            authors = emptySet<String>(),
            source = source
        )
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val mangaId = manga.url.split("/").getOrNull(2) ?: return manga

        val variables = JSONObject().apply {
            put("_id", mangaId)
            put("search", JSONObject().apply {
                put("allowAdult", true)
                put("allowUnknown", false)
            })
        }
        val jsonResponse = graphQlQuery(DETAILS_HASH, variables)
        val data = jsonResponse.optJSONObject("data")?.optJSONObject("manga") ?: return manga

        val description = data.optString("description").replace(Regex("<[^>]*>"), "").trim()
        val status = data.optString("status")

        val authors = mutableSetOf<String>()
        data.optJSONArray("authors")?.let { arr ->
            for (i in 0 until arr.length()) authors.add(arr.getString(i))
        }

        val tags = mutableSetOf<MangaTag>()
        data.optJSONArray("genres")?.let { arr ->
            for (i in 0 until arr.length()) {
                val genre = arr.getString(i)
                tags.add(MangaTag(genre, genre, source))
            }
        }

        val altTitles = mutableSetOf<String>()
        data.optJSONArray("altNames")?.let { arr ->
            for (i in 0 until arr.length()) altTitles.add(arr.getString(i))
        }

        val chapters = parseChapters(data, mangaId, data.optString("name"), manga.url)

        return manga.copy(
            description = description,
            state = when (status.lowercase()) {
                "releasing", "publishing" -> MangaState.ONGOING
                "finished", "completed" -> MangaState.FINISHED
                else -> null
            },
            authors = authors,
            tags = tags,
            altTitles = altTitles,
            chapters = chapters
        )
    }

    private fun parseChapters(mangaData: JSONObject, mangaId: String, mangaName: String, mangaUrl: String): List<MangaChapter> {
        val availableChapters = mangaData.optJSONObject("availableChaptersDetail")?.optJSONArray("sub") ?: JSONArray()
        val chapters = mutableListOf<MangaChapter>()
        
        for (i in 0 until availableChapters.length()) {
            val chapterNum = availableChapters.getString(i)
            
            val titleToSlug = mangaName.replace(Regex("[^a-zA-Z0-9]+"), "-").lowercase()
            val slug = "chapter-$chapterNum-$titleToSlug"
            val chUrl = "$mangaUrl/$slug"

            chapters.add(
                MangaChapter(
                    id = generateUid(chUrl),
                    title = "Chapter $chapterNum",
                    url = chUrl,
                    number = chapterNum.toFloatOrNull() ?: 0f,
                    volume = 0,
                    scanlator = null,
                    uploadDate = 0,
                    branch = null,
                    source = source
                )
            )
        }
        return chapters.reversed()
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val fullUrl = chapter.url.toAbsoluteUrl(domain)
        
        val bridgeScript = """
            (async function() {
                try {
                    let captured = null;
                    const originalParse = JSON.parse;
                    JSON.parse = new Proxy(originalParse, {
                        apply(target, thisArg, args) {
                            const result = Reflect.apply(target, thisArg, args);
                            if (result && result.chapterPages) {
                                captured = args[0];
                            }
                            return result;
                        }
                    });
                    
                    for(let i = 0; i < 300; i++) {
                        if (captured) break;
                        await new Promise(r => setTimeout(r, 100));
                    }
                    
                    if (!captured) {
                        window.location.href = "https://kotatsu.intercept/error#msg=" + encodeURIComponent("chapterPages JSON not intercepted");
                        return;
                    }
                    window.location.href = "https://kotatsu.intercept/result#data=" + encodeURIComponent(captured);
                } catch(e) {
                    window.location.href = "https://kotatsu.intercept/error#msg=" + encodeURIComponent(String((e && e.message) || e));
                }
            })();
        """.trimIndent()

        val requests = runCatching {
            context.interceptWebViewRequests(
                fullUrl,
                org.koitharu.kotatsu.parsers.webview.InterceptionConfig(
                    timeoutMs = 45000,
                    maxRequests = 1,
                    urlPattern = Regex("https://kotatsu\\.intercept/.*", RegexOption.IGNORE_CASE),
                    pageScript = bridgeScript,
                )
            )
        }.getOrElse { e ->
            throw ParseException("WebView interception failed", fullUrl, e)
        }

        val resultUrl = requests.firstOrNull()?.url
            ?: throw ParseException("WebView interception did not return a result", fullUrl)

        val decodedData = when {
            resultUrl.contains("/error", ignoreCase = true) -> {
                val query = resultUrl.substringAfter('#', resultUrl.substringAfter('?', ""))
                val msg = query.split('&').find { it.startsWith("msg=") }?.substringAfter("msg=")
                    ?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: "Unknown error"
                throw ParseException("WebView extraction failed: $msg", fullUrl)
            }
            else -> {
                val query = resultUrl.substringAfter('#', resultUrl.substringAfter('?', ""))
                query.split('&').find { it.startsWith("data=") }?.substringAfter("data=")
                    ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                    ?: throw ParseException("WebView extraction missing data", fullUrl)
            }
        }

        val pagesPayload = runCatching { JSONObject(decodedData) }.getOrElse { e ->
            throw ParseException("Invalid JSON from WebView", fullUrl, e)
        }
        
        val pageListEdges = pagesPayload.optJSONObject("chapterPages")?.optJSONArray("edges") ?: JSONArray()
        
        var selectedEdge: JSONObject? = null
        for (i in 0 until pageListEdges.length()) {
            val edge = pageListEdges.getJSONObject(i)
            val pictureUrls = edge.optJSONArray("pictureUrls") ?: JSONArray()
            val svUrl = edge.optString("serverUrl")
            
            val hasFullUrl = (0 until pictureUrls.length()).any { j ->
                pictureUrls.getJSONObject(j).optString("url").startsWith("http")
            }
            
            if (hasFullUrl || svUrl.isNotEmpty()) {
                selectedEdge = edge
                break
            }
        }
        if (selectedEdge == null && pageListEdges.length() > 0) {
            selectedEdge = pageListEdges.getJSONObject(0)
        }
        
        if (selectedEdge == null) return emptyList()

        val serverUrl = selectedEdge.optString("serverUrl")
        val imageDomainUrl = if (serverUrl.startsWith("http")) {
            "${serverUrl.removeSuffix("/")}/"
        } else if (serverUrl.isNotEmpty()) {
            "https://${serverUrl.removeSuffix("/")}/"
        } else {
            defaultImageDomain
        }

        val pictureUrls = selectedEdge.optJSONArray("pictureUrls") ?: JSONArray()
        val pages = mutableListOf<MangaPage>()
        for (i in 0 until pictureUrls.length()) {
            val urlStr = pictureUrls.getJSONObject(i).optString("url")
            if (urlStr.isBlank()) continue
            
            val imageUrl = if (urlStr.startsWith("http")) {
                urlStr
            } else {
                imageDomainUrl + urlStr.removePrefix("/")
            }

            pages.add(
                MangaPage(
                    id = generateUid(imageUrl),
                    url = imageUrl,
                    preview = null,
                    source = source
                )
            )
        }
        return pages
    }
    
    private suspend fun graphQlQuery(hash: String, variables: JSONObject): JSONObject {
        val extensions = JSONObject().apply {
            put("persistedQuery", JSONObject().apply {
                put("version", 1)
                put("sha256Hash", hash)
            })
        }
        
        val url = "$apiUrl?variables=${variables.toString().urlEncoded()}&extensions=${extensions.toString().urlEncoded()}"
        
        val response = webClient.httpGet(url, getRequestHeaders())
        
        return runCatching { response.parseJson() }.getOrElse { e ->
            try {
                context.requestBrowserAction(this, "https://$domain/")
            } catch (ex: UnsupportedOperationException) {
                throw ParseException(
                    "Cloudflare verification required. Please open AllManga in WebView.",
                    "https://$domain/",
                    e
                )
            }
            throw ParseException("Retrying after Cloudflare...", url)
        }
    }

    companion object {
        private const val SEARCH_HASH = "2d48e19fb67ddcac42fbb885204b6abb0a84f406f15ef83f36de4a66f49f651a"
        private const val DETAILS_HASH = "d77781dcf964b97aea0be621dbde430e89e200b58526823ee6010dd11c3ca96a"
        private const val TAGS_HASH = "fbd24de3aec73d35332185b621beec15396aaf8e8ae00183ddac6c19fbf8adcf"
    }
}
