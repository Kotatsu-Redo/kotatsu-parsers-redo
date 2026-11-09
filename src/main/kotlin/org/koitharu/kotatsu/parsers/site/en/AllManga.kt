package org.koitharu.kotatsu.parsers.site.en

import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.exception.ParseException
import kotlinx.coroutines.delay
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.time.Instant
import java.util.*

@MangaSourceParser("ALLMANGA", "Mkissa", "en")
internal class AllManga(context: MangaLoaderContext) : PagedMangaParser(context, MangaParserSource.ALLMANGA, 20) {

    override val configKeyDomain = ConfigKey.Domain("mkissa.to")

    private val apiUrl = "https://api.mkissa.net/api"
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
        return Favicons.single("https://$domain/favicon.svg")
    }

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = fetchAvailableTags()
    )

    private suspend fun fetchAvailableTags(): Set<MangaTag> {
        return GENRES.mapTo(LinkedHashSet()) { MangaTag(it, it, source) }
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
        variables.put("size", 20)
        variables.put("page", page)
        variables.put("translationType", "sub")
        variables.put("countryOrigin", "ALL")

        val jsonResponse = graphQlQuery(SEARCH_QUERY, variables)
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
        val url = "/manga/$id"
        return Manga(
            id = generateUid(url),
            title = node.optString("englishName").takeIf { it.isNotBlank() }
                ?: node.optString("name"),
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
            put("id", mangaId)
            put("showId", "manga@$mangaId")
        }
        val jsonResponse = graphQlQuery(UPDATE_QUERY, variables)
        val responseData = jsonResponse.optJSONObject("data") ?: return manga
        val data = responseData.optJSONObject("manga") ?: return manga

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
        data.optJSONArray("tags")?.let { arr ->
            for (i in 0 until arr.length()) {
                val tag = arr.optString(i)
                if (tag.isNotBlank()) tags.add(MangaTag(tag, tag, source))
            }
        }

        val altTitles = mutableSetOf<String>()
        data.optJSONArray("altNames")?.let { arr ->
            for (i in 0 until arr.length()) altTitles.add(arr.getString(i))
        }

        val chapters = parseChapters(
            mangaData = data,
            chapterInfo = responseData.optJSONArray("episodeInfos") ?: JSONArray(),
            mangaUrl = manga.url,
        )

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

    private fun parseChapters(
        mangaData: JSONObject,
        chapterInfo: JSONArray,
        mangaUrl: String,
    ): List<MangaChapter> {
        val availableChapters = mangaData.optJSONObject("availableChaptersDetail")?.optJSONArray("sub") ?: JSONArray()
        val details = buildMap<String, JSONObject> {
            for (i in 0 until chapterInfo.length()) {
                val item = chapterInfo.optJSONObject(i) ?: continue
                put(item.optString("episodeIdNum"), item)
            }
        }
        val chapters = mutableListOf<MangaChapter>()
        
        for (i in 0 until availableChapters.length()) {
            val chapterNum = availableChapters.getString(i)
            val info = details[chapterNum]
            val note = info?.optString("notes").orEmpty()
            val uploadDate = info?.optJSONObject("uploadDates")?.optString("sub")
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrDefault(0L) }
                ?: 0L
            val chUrl = "$mangaUrl/chapter-$chapterNum-sub"

            chapters.add(
                MangaChapter(
                    id = generateUid(chUrl),
                    title = buildString {
                        append("Chapter ")
                        append(chapterNum)
                        if (note.isNotBlank() && !NUMBER_REGEX.containsMatchIn(note)) {
                            append(": ")
                            append(note)
                        }
                    },
                    url = chUrl,
                    number = chapterNum.toFloatOrNull() ?: 0f,
                    volume = 0,
                    scanlator = null,
                    uploadDate = uploadDate,
                    branch = null,
                    source = source
                )
            )
        }
        return chapters.reversed()
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val chapterUrl = chapter.url.toAbsoluteUrl(domain)
        val mangaId = chapter.url.substringAfter("/manga/").substringBefore('/')
            .takeIf { it.isNotBlank() }
            ?: throw ParseException("Invalid Mkissa chapter URL", chapterUrl)
        val mangaUrl = "/manga/$mangaId".toAbsoluteUrl(domain)
        val chapterPath = chapterUrl.toHttpUrl().encodedPath
        
        val bridgeScript = """
            (function() {
                if (window.__kotatsuMkissaBridgeInstalled) return;
                window.__kotatsuMkissaBridgeInstalled = true;

                let sent = false;
                function sendPayload(value, raw) {
                    if (sent || !value || !value.chapterPages) return;
                    sent = true;
                    const payload = typeof raw === 'string' ? raw : JSON.stringify(value);
                    window.location.href = 'https://kotatsu.intercept/result#data=' + encodeURIComponent(payload);
                }

                try {
                    const originalJson = Response.prototype.json;
                    Response.prototype.json = function() {
                        return originalJson.call(this).then(data => {
                            sendPayload(data);
                            return data;
                        });
                    };

                    const originalParse = JSON.parse;
                    JSON.parse = new Proxy(originalParse, {
                        apply(target, thisArg, args) {
                            const result = Reflect.apply(target, thisArg, args);
                            sendPayload(result, args[0]);
                            return result;
                        }
                    });

                    const guardIframe = element => {
                        if (element && String(element.tagName).toUpperCase() === 'IFRAME') {
                            try {
                                Object.defineProperty(element, 'contentWindow', {
                                    get: () => null,
                                    configurable: false
                                });
                            } catch (_) {}
                        }
                        return element;
                    };
                    for (const key of ['createElement', 'createElementNS']) {
                        const originalCreate = Document.prototype[key];
                        Document.prototype[key] = function(...args) {
                            return guardIframe(originalCreate.call(this, ...args));
                        };
                    }

                    function navigate() {
                        const link = document.createElement('a');
                        link.href = link.dataset.href = ${JSONObject.quote(chapterPath)};
                        document.body.appendChild(link);
                        link.click();
                    }

                    let attempts = 0;
                    const ready = setInterval(() => {
                        attempts++;
                        if (document.body && (document.querySelector('[data-href]') || attempts >= 300)) {
                            clearInterval(ready);
                            navigate();
                        }
                    }, 50);
                } catch (error) {
                    window.location.href = 'https://kotatsu.intercept/error#msg=' +
                        encodeURIComponent(String((error && error.message) || error));
                }
            })();
        """.trimIndent()

        val requests = runCatching {
            context.interceptWebViewRequests(
                mangaUrl,
                org.koitharu.kotatsu.parsers.webview.InterceptionConfig(
                    timeoutMs = 25000,
                    maxRequests = 1,
                    urlPattern = Regex("https://kotatsu\\.intercept/.*", RegexOption.IGNORE_CASE),
                    pageScript = bridgeScript,
                )
            )
        }.getOrElse { e ->
            throw ParseException("Mkissa reader initialization failed", chapterUrl, e)
        }

        val resultUrl = requests.firstOrNull()?.url
            ?: throw ParseException("Mkissa reader did not return chapter pages", chapterUrl)

        val decodedData = when {
            resultUrl.contains("/error", ignoreCase = true) -> {
                val query = resultUrl.substringAfter('#', resultUrl.substringAfter('?', ""))
                val msg = query.split('&').find { it.startsWith("msg=") }?.substringAfter("msg=")
                    ?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: "Unknown error"
                throw ParseException("Mkissa reader extraction failed: $msg", chapterUrl)
            }
            else -> {
                val query = resultUrl.substringAfter('#', resultUrl.substringAfter('?', ""))
                query.split('&').find { it.startsWith("data=") }?.substringAfter("data=")
                    ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                    ?: throw ParseException("Mkissa reader extraction returned no data", chapterUrl)
            }
        }

        val pagesPayload = runCatching { JSONObject(decodedData) }.getOrElse { e ->
            throw ParseException("Invalid Mkissa reader response", chapterUrl, e)
        }
        
        val pageListEdges = pagesPayload.optJSONObject("chapterPages")?.optJSONArray("edges") ?: JSONArray()
        
        var selectedEdge: JSONObject? = null
        for (i in 0 until pageListEdges.length()) {
            val edge = pageListEdges.getJSONObject(i)
            val pictureUrls = edge.optJSONArray("pictureUrls") ?: JSONArray()
            val svUrl = edge.optString("pictureUrlHead").ifBlank { edge.optString("serverUrl") }
            
            val hasFullUrl = (0 until pictureUrls.length()).any { j ->
                pictureUrls.optJSONObject(j)?.optString("url").orEmpty().startsWith("http")
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

        val serverUrl = selectedEdge.optString("pictureUrlHead").ifBlank {
            selectedEdge.optString("serverUrl")
        }
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
            val value = pictureUrls.opt(i)
            val urlStr = when (value) {
                is JSONObject -> value.optString("url")
                is String -> value
                else -> ""
            }
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
    
    private suspend fun graphQlQuery(query: String, variables: JSONObject): JSONObject {
        val payload = JSONObject()
            .put("query", query)
            .put("variables", variables)
        val headers = getRequestHeaders().newBuilder()
            .set("Accept", "application/json")
            .set("Origin", "https://$domain")
            .set("Referer", "https://$domain/")
            .build()

        var lastError: Exception? = null
        var retryDelay = RETRY_DELAY_MS
        for (attempt in 0..MAX_RETRIES) {
            val response = try {
                webClient.httpPost(apiUrl.toHttpUrl(), payload, headers).parseJson()
            } catch (e: Exception) {
                lastError = e
                if (attempt == MAX_RETRIES) break
                delay(retryDelay)
                retryDelay += RETRY_DELAY_MS
                continue
            }

            val errors = response.optJSONArray("errors")
            if (errors == null || errors.length() == 0) return response

            val message = (0 until errors.length()).joinToString("; ") { index ->
                errors.optJSONObject(index)?.optString("message").orEmpty()
            }.ifBlank { errors.toString() }
            val requestedDelay = RETRY_AFTER_REGEX.find(message)
                ?.groupValues?.getOrNull(1)?.toLongOrNull()?.times(1000L)
            val rateLimited = requestedDelay != null || message.contains("too many", ignoreCase = true)
            if (!rateLimited) throw ParseException(message, apiUrl)

            lastError = ParseException(message, apiUrl)
            if (attempt == MAX_RETRIES) break
            delay(requestedDelay ?: retryDelay)
            retryDelay += RETRY_DELAY_MS
        }
        throw lastError ?: ParseException("Unable to query Mkissa", apiUrl)
    }

    companion object {
        private const val MAX_RETRIES = 5
        private const val RETRY_DELAY_MS = 1_000L
        private val RETRY_AFTER_REGEX = Regex("""again in (\d+)\s*second""", RegexOption.IGNORE_CASE)
        private val NUMBER_REGEX = Regex("\\d")

        private val GENRES = listOf(
            "4 Koma", "Action", "Adult", "Adventure", "Cars", "Comedy", "Cooking", "Crossdressing",
            "Dementia", "Demons", "Doujinshi", "Drama", "Ecchi", "Fantasy", "Game", "Gender Bender",
            "Gyaru", "Harem", "Historical", "Horror", "Isekai", "Josei", "Kids", "Loli", "Magic",
            "Manhua", "Manhwa", "Martial Arts", "Mature", "Mecha", "Medical", "Military",
            "Monster Girls", "Music", "Mystery", "One Shot", "Parody", "Police", "Post Apocalyptic",
            "Psychological", "Reincarnation", "Reverse Harem", "Romance", "Samurai", "School", "Sci-Fi",
            "Seinen", "Shota", "Shoujo", "Shoujo Ai", "Shounen", "Shounen Ai", "Slice of Life", "Smut",
            "Space", "Sports", "Super Power", "Supernatural", "Suspense", "Thriller", "Tragedy", "Unknown",
            "Vampire", "Webtoons", "Yaoi", "Youkai", "Yuri", "Zombies",
        )

        private val SEARCH_QUERY = """
            query (
                ${'$'}search: SearchInput
                ${'$'}size: Int
                ${'$'}page: Int
                ${'$'}translationType: VaildTranslationTypeMangaEnumType
                ${'$'}countryOrigin: VaildCountryOriginEnumType
            ) {
                mangas(
                    search: ${'$'}search
                    limit: ${'$'}size
                    page: ${'$'}page
                    translationType: ${'$'}translationType
                    countryOrigin: ${'$'}countryOrigin
                ) {
                    edges { _id name thumbnail englishName }
                }
            }
        """.trimIndent()

        private val UPDATE_QUERY = """
            query (${ '$' }id: String!, ${ '$' }showId: String!) {
                manga(_id: ${ '$' }id) {
                    _id name thumbnail description authors genres tags status altNames englishName
                    malId aniListId relatedMangas availableChaptersDetail
                }
                episodeInfos(showId: ${ '$' }showId, episodeNumStart: 0, episodeNumEnd: 9999) {
                    episodeIdNum notes uploadDates
                }
            }
        """.trimIndent()
    }
}
