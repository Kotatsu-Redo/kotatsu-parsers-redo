package org.koitharu.kotatsu.parsers.site.en

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.internal.closeQuietly
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.Broken
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.bitmap.Bitmap
import org.koitharu.kotatsu.parsers.bitmap.Rect
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.webview.InterceptionConfig
import java.io.IOException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.concurrent.ConcurrentHashMap

@Broken("Disabled until updates are made faster")
@MangaSourceParser("COMIX", "Comix", "en", ContentType.MANGA)
internal class Comix(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.COMIX, 28) {

    override val configKeyDomain = ConfigKey.Domain("comix.to")

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
        keys.add(ConfigKey.DisableUpdateChecking(defaultValue = true))
        // Lets the app resolve a challenge headlessly instead of putting a browser in
        // front of the user: WebViewExecutor reads this key to pick its intercepting
        // CloudFlare client.
        keys.add(ConfigKey.InterceptCloudflare(defaultValue = true))
    }

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
            isMultipleTagsSupported = true,
            isTagsExclusionSupported = false,
        )

    override val availableSortOrders: Set<SortOrder> = LinkedHashSet(
        listOf(
            SortOrder.RELEVANCE,
            SortOrder.UPDATED,
            SortOrder.POPULARITY,
            SortOrder.NEWEST,
            SortOrder.ALPHABETICAL
        )
    )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = fetchAvailableTags(),
        availableContentRating = EnumSet.allOf(ContentRating::class.java),
    )

    // The site's curated genres, keyed by the numeric id the API expects in
    // `genres_in[]` (verified against /api/v1/tags/search?type=genre). The
    // narrative "tags" (Demons, School Life, ...) live in a separate id space
    // with thousands of entries and no listing endpoint, so they aren't
    // enumerated here — they still work via search because every tag shown on
    // a manga's detail page carries its own numeric id (see [parseTerms]),
    // and any non-numeric tag key is resolved by name through [resolveTagId].
    private suspend fun fetchAvailableTags(): Set<MangaTag> {
        return setOf(
            MangaTag(key = "6", title = "Action", source = source),
            MangaTag(key = "87264", title = "Adult", source = source),
            MangaTag(key = "7", title = "Adventure", source = source),
            MangaTag(key = "8", title = "Boys Love", source = source),
            MangaTag(key = "9", title = "Comedy", source = source),
            MangaTag(key = "10", title = "Crime", source = source),
            MangaTag(key = "11", title = "Drama", source = source),
            MangaTag(key = "87265", title = "Ecchi", source = source),
            MangaTag(key = "12", title = "Fantasy", source = source),
            MangaTag(key = "13", title = "Girls Love", source = source),
            MangaTag(key = "40", title = "Harem", source = source),
            MangaTag(key = "87266", title = "Hentai", source = source),
            MangaTag(key = "14", title = "Historical", source = source),
            MangaTag(key = "15", title = "Horror", source = source),
            MangaTag(key = "16", title = "Isekai", source = source),
            MangaTag(key = "17", title = "Magical Girls", source = source),
            MangaTag(key = "87267", title = "Mature", source = source),
            MangaTag(key = "18", title = "Mecha", source = source),
            MangaTag(key = "19", title = "Medical", source = source),
            MangaTag(key = "20", title = "Mystery", source = source),
            MangaTag(key = "21", title = "Philosophical", source = source),
            MangaTag(key = "22", title = "Psychological", source = source),
            MangaTag(key = "23", title = "Romance", source = source),
            MangaTag(key = "24", title = "Sci-Fi", source = source),
            MangaTag(key = "25", title = "Slice of Life", source = source),
            MangaTag(key = "87268", title = "Smut", source = source),
            MangaTag(key = "26", title = "Sports", source = source),
            MangaTag(key = "27", title = "Superhero", source = source),
            MangaTag(key = "28", title = "Thriller", source = source),
            MangaTag(key = "29", title = "Tragedy", source = source),
            MangaTag(key = "30", title = "Wuxia", source = source),
        )
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        // The `/api/v1/manga` endpoint is request-signed (an unsigned GET 403s
        // with "missing token"), so instead of calling it we do what the website
        // does: load the `/browse` page and read the results the server embeds in
        // its `script#initial-data` JSON. No token needed.
        val query = filter.query
        val browseUrl = buildString {
            append("https://")
            append(domain)
            append("/browse?")
            var firstParam = true
            fun addParam(param: String) {
                if (firstParam) {
                    append(param)
                    firstParam = false
                } else {
                    append("&").append(param)
                }
            }

            if (!query.isNullOrEmpty()) {
                // `keyword` is what the server reads into the page's initial list
                // parameters, so the page searches from its first request; with the
                // page's own `q` it first lists everything, then searches once its
                // search box settles (keiyoushi does the same).
                addParam("keyword=${query.urlEncoded()}")
                addParam("order[relevance]=desc")
            } else {
                when (order) {
                    // Relevance is only meaningful alongside a keyword. With no query
                    // it falls back to the latest-update ordering, same as the
                    // website does.
                    SortOrder.RELEVANCE -> addParam("order[chapter_updated_at]=desc")
                    SortOrder.UPDATED -> addParam("order[chapter_updated_at]=desc")
                    SortOrder.POPULARITY -> addParam("order[views_30d]=desc")
                    SortOrder.NEWEST -> addParam("order[created_at]=desc")
                    SortOrder.ALPHABETICAL -> addParam("order[title]=asc")
                    else -> addParam("order[chapter_updated_at]=desc")
                }
            }

            // Handle genre/tag filtering. A tag key is normally the numeric id
            // the API wants; anything non-numeric (e.g. a tag tapped from a
            // manga's detail page that predates this change) is resolved by name.
            val includedIds = LinkedHashSet<String>()
            for (tag in filter.tags) {
                val id = tag.key.toIntOrNull()?.let { tag.key } ?: resolveTagId(tag.title)
                if (id != null) includedIds.add(id)
            }
            for (id in includedIds) {
                addParam("genres_in[]=$id")
            }

            // Comix exposes four ratings while Kotatsu exposes three. Erotica is
            // grouped with Suggestive, matching the mapping used for MangaDex.
            // Keep Safe as the default so an empty filter never opts into NSFW
            // content; the app can then gate Suggestive and Adult normally.
            val selectedRatings = filter.contentRating.ifEmpty { setOf(ContentRating.SAFE) }
            val comixRatings = buildList {
                if (ContentRating.SAFE in selectedRatings) add("safe")
                if (ContentRating.SUGGESTIVE in selectedRatings) {
                    add("suggestive")
                    add("erotica")
                }
                if (ContentRating.ADULT in selectedRatings) add("pornographic")
            }
            addParam("content_rating=${comixRatings.joinToString(",").urlEncoded()}")
            addParam("page=$page")
        }

        val items = loadBrowseItems(browseUrl)
        return (0 until items.length()).map { i ->
            parseMangaFromJson(items.getJSONObject(i))
        }
    }

    /**
     * Returns the manga items the `/browse` page exposes. The browse listing is
     * not server-rendered — the page fetches it over a signed, encrypted XHR
     * after hydration — so we load the page in a WebView and capture the payload
     * it decrypts and parses (mirroring the upstream Keiyoushi fallback). A plain
     * GET is tried first for the rare route that does inline `script#initial-data`.
     */
    private suspend fun loadBrowseItems(browseUrl: String): JSONArray {
        var sawCloudflare = false
        runCatching { webClient.httpGet(browseUrl).parseHtml() }
            .onFailure { sawCloudflare = it.isCloudFlareProtection() }
            .getOrNull()
            ?.let { extractInitialDataItems(it) }
            ?.let { return it }
        // The WebView shares the client's cookies and agent, so it would land on the
        // same challenge.
        if (sawCloudflare) {
            requestCloudflareVerification(browseUrl)
        }

        val response = try {
            evaluateWebViewApiJson(browseUrl, BROWSE_CAPTURE_SCRIPT)
        } catch (e: Exception) {
            if (sawCloudflare || e.isCloudFlareProtection()) {
                requestCloudflareVerification(browseUrl, e)
            }
            throw e
        }
        return response.optJSONObject("result")?.optJSONArray("items")
            ?: response.optJSONArray("items")
            ?: throw ParseException("Comix browse page returned no results", browseUrl)
    }

    /**
     * Loads a page and returns its rendered HTML as a [Document], retrying so a
     * cold Cloudflare challenge can clear. A plain GET is tried first (it works
     * once the CF cookie is in the shared client); otherwise a WebView drives
     * the navigation, which both passes the challenge and renders the SSR HTML.
     * [isReady] decides whether a candidate document actually carries the data
     * we need (vs. a challenge/empty shell), so we keep retrying until it does.
     */
    private suspend fun loadRenderedDocument(url: String, isReady: (Document) -> Boolean): Document? {
        var sawCloudflare = false
        repeat(WEBVIEW_PAGE_ATTEMPTS) {
            runCatching { webClient.httpGet(url).parseHtml() }
                .onFailure { if (it.isCloudFlareProtection()) sawCloudflare = true }
                .getOrNull()
                ?.takeIf(isReady)
                ?.let { return it }

            val html = context.evaluateJs(url, PAGE_HTML_SCRIPT, WEBVIEW_PAGE_TIMEOUT)
            if (!html.isNullOrBlank()) {
                if (html == CLOUDFLARE_BLOCKED || isCloudflarePage(html)) {
                    requestCloudflareVerification(url)
                }
                Jsoup.parse(html, url).takeIf(isReady)?.let { return it }
            }
        }
        // Every attempt came back as a challenge rather than the page. The caller
        // falls back to what it already knows rather than interrupting the user.
        if (sawCloudflare) {
            return null
        }
        return null
    }

    private fun extractInitialDataItems(document: Document): JSONArray? {
        val raw = document.selectFirst("script#initial-data")?.data()?.nullIfEmpty() ?: return null
        val queries = runCatching { JSONObject(raw).optJSONObject("queries") }.getOrNull() ?: return null
        for (key in queries.keys()) {
            val value = queries.optJSONObject(key) ?: continue
            val items = value.optJSONObject("result")?.optJSONArray("items")
                ?: value.optJSONArray("items")
            if (items != null && items.length() > 0) return items
        }
        return null
    }

    private fun parseMangaFromJson(json: JSONObject): Manga {
        val hashId = json.optString("hid").ifBlank { json.optString("hash_id") }
        val title = json.getString("title")
        val description = json.optString("synopsis", "").nullIfEmpty()
        val poster = json.optJSONObject("poster")
        val coverUrl = poster?.optString("large", "")?.nullIfEmpty()
            ?: poster?.optString("medium", "")?.nullIfEmpty()
            ?: poster?.optString("small", "")?.nullIfEmpty()
        val status = json.optString("status", "")
        val rating = json.optDouble("ratedAvg", Double.NaN)
            .takeUnless { it.isNaN() }
            ?: json.optDouble("rated_avg", 0.0)

        val state = when (status) {
            "finished" -> MangaState.FINISHED
            "releasing" -> MangaState.ONGOING
            "on_hiatus" -> MangaState.PAUSED
            "discontinued" -> MangaState.ABANDONED
            else -> null
        }

        return Manga(
            id = generateUid(hashId),
            url = "/title/$hashId",
            publicUrl = "https://comix.to/title/$hashId",
            coverUrl = coverUrl,
            title = title,
            altTitles = parseAltTitles(json),
            description = description,
            rating = if (rating > 0) (rating / 10.0).toFloat() else RATING_UNKNOWN,
            tags = parseTerms(json),
            authors = parseAuthors(json),
            state = state,
            source = source,
            contentRating = parseContentRating(json),
        )
    }

    private fun parseContentRating(json: JSONObject): ContentRating {
        val rating = json.optString("contentRating")
            .ifBlank { json.optString("content_rating") }
            .lowercase(Locale.ROOT)
        return when (rating) {
            "suggestive", "erotica" -> ContentRating.SUGGESTIVE
            "pornographic" -> ContentRating.ADULT
            else -> ContentRating.SAFE
        }
    }

    override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
        val chaptersDeferred = async { getChapters(manga) }

        // Enrich from the title page's `script#initial-data` (the same SSR JSON
        // the website hydrates from), so no signed API call is needed. If the
        // page is gated/empty, fall back to the listing-derived manga (which
        // already carries synopsis/tags/authors) so details still open.
        val updatedManga = loadRenderedDocument(manga.url.toAbsoluteUrl(domain)) {
            extractInitialDataDetail(it) != null
        }
            ?.let { extractInitialDataDetail(it) }
            ?.let { parseMangaFromJson(it) }
            ?: manga

        return@coroutineScope updatedManga.copy(
            chapters = chaptersDeferred.await(),
        )
    }

    private fun extractInitialDataDetail(document: Document): JSONObject? {
        val raw = document.selectFirst("script#initial-data")?.data()?.nullIfEmpty() ?: return null
        val queries = runCatching { JSONObject(raw).optJSONObject("queries") }.getOrNull() ?: return null
        // The detail query key embeds "detail"; its value is the manga object
        // (occasionally wrapped in `result`).
        for (key in queries.keys()) {
            if (!key.contains("detail")) continue
            val value = queries.optJSONObject(key) ?: continue
            val candidate = value.optJSONObject("result") ?: value
            if (candidate.has("hid") || candidate.has("hash_id") || candidate.has("title")) {
                return candidate
            }
        }
        return null
    }

    override suspend fun getRelatedManga(seed: Manga): List<Manga> = emptyList()

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val chapterId = chapter.url.substringAfterLast("/").substringBefore("-")
        val readerUrl = chapter.url.toAbsoluteUrl(domain)

        // The page list comes over the site's signed, encoded API, so the reader page's
        // own client fetches it for us (see [pageScript]).
        var sawCloudflare = false
        val response = runCatching { webClient.httpGet(readerUrl).parseHtml() }
            .onFailure { sawCloudflare = it.isCloudFlareProtection() }
            .getOrNull()
            ?.let { extractInitialDataPages(it) }
            ?: try {
                if (sawCloudflare) {
                    requestCloudflareVerification(readerUrl)
                }
                evaluateWebViewApiJson(readerUrl, pageScript(chapterId.toJsString()))
            } catch (e: Exception) {
                if (sawCloudflare || e.isCloudFlareProtection()) {
                    requestCloudflareVerification(readerUrl, e)
                }
                throw e
            }
        val pagesRoot = response.optJSONObject("result")?.optJSONObject("pages")
        val baseUrl = pagesRoot?.optString("baseUrl").orEmpty().trimEnd('/')
        val pages = pagesRoot?.optJSONArray("items")
            ?: response.optJSONObject("result")?.optJSONArray("pages")
            ?: JSONArray()
        val altDomains = pagesRoot?.optJSONArray("alt")
            ?.let { alt -> (0 until alt.length()).mapNotNull { alt.optString(it).nullIfEmpty() } }
            .orEmpty()
        val altNode = pagesRoot?.optString("node")?.nullIfEmpty()

        return (0 until pages.length()).map { i ->
            val item = pages.optJSONObject(i)
            val rawUrl = item?.getString("url") ?: pages.get(i).toString()
            val imageUrl = if (rawUrl.startsWith("http", ignoreCase = true) || baseUrl.isBlank()) {
                rawUrl
            } else {
                "$baseUrl/${rawUrl.trimStart('/')}"
            }
            // `s == 1` (or a `v3` flag already on the url) marks a "v3" tile-scrambled
            // image. The server only returns the x-scramble-* headers when the request
            // carries the `v3` query flag, so we add it here; the interceptor then
            // descrambles based on those headers.
            val parsedUrl = imageUrl.toHttpUrlOrNull()
            val isV3 = item?.optInt("s", 0) == 1 || parsedUrl?.queryParameterNames?.contains("v3") == true
            val withV3 = if (!isV3 || parsedUrl == null || parsedUrl.queryParameterNames.contains("v3")) {
                imageUrl
            } else {
                parsedUrl.newBuilder().addQueryParameter("v3", null).build().toString()
            }
            // The fragment never leaves the device: it tells [intercept] this is a page
            // image, and which hosts to retry it on.
            val altHosts = parsedUrl?.host?.let { altHostsFor(it, i, altDomains, altNode) }.orEmpty()
            val fragment = if (altHosts.isEmpty()) PAGE_FRAGMENT else PAGE_FRAGMENT + ":" + altHosts.joinToString(",")
            val finalUrl = "${withV3.substringBefore('#')}#$fragment"
            MangaPage(
                id = generateUid("$chapterId-$i"),
                url = finalUrl,
                preview = null,
                source = source,
            )
        }
    }

    /**
     * Hosts to retry a page image on, in the site reader's order: the same host
     * label on each alternate domain, then the node label on each (after
     * keiyoushi's ChapterPages.altHosts).
     */
    private fun altHostsFor(host: String, index: Int, altDomains: List<String>, node: String?): List<String> {
        if (altDomains.isEmpty()) return emptyList()
        val label = host.substringBefore('.')
        val start = index % altDomains.size
        val domains = altDomains.drop(start) + altDomains.take(start)
        val hosts = domains.map { "$label.$it" } + domains.map { "${node ?: label}.$it" }
        return List(ALT_HOST_ATTEMPTS) { hosts[it % hosts.size] }.filter { it != host }.distinct()
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val fragment = original.url.fragment
        val isPageImage = fragment == PAGE_FRAGMENT || fragment?.startsWith("$PAGE_FRAGMENT:") == true
        // The image hosts answer 403 to any request that names the site in Referer
        // or Origin, and the app adds a Referer to every request.
        val request = if (isPageImage) {
            original.newBuilder().removeHeader("Referer").removeHeader("Origin").build()
        } else {
            original
        }
        val altHosts = fragment?.substringAfter("$PAGE_FRAGMENT:", "")
            ?.split(',')?.filter { it.isNotBlank() }
            .orEmpty()
        val response = if (isPageImage && altHosts.isNotEmpty()) {
            retryOnAltHosts(chain, request, altHosts)
        } else {
            retryScramblePathFallbacks(chain, request, chain.proceed(request))
        }
        if (!response.isSuccessful) {
            return response
        }

        // The CDN protects images with two independent, stackable layers, each
        // signalled by its own response headers (only protected images carry
        // them, so API and HTML responses pass straight through):
        //   * a byte-level XOR stream cipher        — x-enc-seed / x-enc-len / x-enc-algo
        //   * a 5x5 tile shuffle on the decoded image — x-scramble-seed / x-scramble-grid /
        //                                               x-scramble-algo / x-scramble-hash
        val rawScrambleGrid = response.header("x-scramble-grid")
        val rawScrambleAlgo = response.header("x-scramble-algo")
        val rawScrambleHash = response.header("x-scramble-hash")
        val rawEncAlgo = response.header("x-enc-algo")

        val encSeed = response.header("x-enc-seed")?.toLongOrNull()?.toInt()
        val encLen = response.header("x-enc-len")?.toIntOrNull()
        val scrambleSeed = response.header("x-scramble-seed")?.toLongOrNull()?.toInt()
        val scrambleHash = decodeScrambleHash(rawScrambleHash)

        val needsXor = encSeed != null && encSeed != 0 && encLen != null
        val shouldDescrambleGrid = rawScrambleGrid == "5x5" &&
            (rawScrambleAlgo == null || rawScrambleAlgo == "1" || rawScrambleAlgo == "2" || rawScrambleAlgo == "3") &&
            scrambleSeed != null && scrambleSeed != 0

        if (!needsXor && !shouldDescrambleGrid) {
            return response
        }

        val contentType = response.body?.contentType()
        val originalBytes = response.body?.bytes() ?: return response
        val bytes = if (needsXor) {
            decodeEncodedBytes(originalBytes, encSeed!!, encLen!!, rawEncAlgo)
        } else {
            originalBytes
        }

        // Re-wrap the (de-XORed) bytes so the redraw helper can decode them into
        // a bitmap, then undo the tile shuffle on top.
        val decodedResponse = response.newBuilder()
            .body(bytes.toResponseBody(contentType))
            .build()

        if (!shouldDescrambleGrid) {
            return decodedResponse
        }

        return context.redrawImageResponse(decodedResponse) { bitmap ->
            descramble(bitmap, scrambleSeed!! xor scrambleHash, rawScrambleAlgo)
        }
    }

    /**
     * A page image that fails is retried on the alternate hosts the page list names,
     * a moment apart, like the site's reader does (after keiyoushi's altHostFallback).
     */
    private fun retryOnAltHosts(chain: Interceptor.Chain, request: Request, altHosts: List<String>): Response {
        var failed: Response? = null
        var error: IOException? = null
        val urls = listOf(request.url) + altHosts.mapIndexed { i, host ->
            request.url.newBuilder()
                .host(host)
                .setQueryParameter("r", (i + 1).toString())
                .build()
        }
        for (url in urls) {
            if (failed != null || error != null) {
                failed?.closeQuietly()
                failed = null
                Thread.sleep(ALT_HOST_RETRY_DELAY_MS)
            }
            try {
                val response = chain.proceed(request.newBuilder().url(url).build())
                if (response.isSuccessful) return response
                failed = response
            } catch (e: IOException) {
                if (chain.call().isCanceled()) throw e
                error = e
            }
        }
        return failed ?: throw error!!
    }

    /**
     * The CDN keeps the same image under several interchangeable path segments
     * (`/i5/`, `/si/`, `/i/`, `/sii/`, `/ii/`) and the one the page list hands out
     * is not always the one that exists, so a 404 is retried against the others
     * before giving up.
     */
    private fun retryScramblePathFallbacks(
        chain: Interceptor.Chain,
        request: Request,
        response: Response,
    ): Response {
        if (response.code != 404) {
            return response
        }
        val url = request.url.toString()
        val fallbacks = SCRAMBLE_PATH_FALLBACKS
            .map { url.replaceFirst(SCRAMBLE_PATH_FALLBACK_REGEX, it) }
            .filter { it != url }
        if (fallbacks.isEmpty()) {
            return response
        }
        var lastResponse = response
        for (fallbackUrl in fallbacks) {
            lastResponse.closeQuietly()
            lastResponse = chain.proceed(request.newBuilder().url(fallbackUrl).build())
            if (lastResponse.code != 404) {
                break
            }
        }
        return lastResponse
    }

    // A handful of older images ship a constant hash that gets folded into the
    // scramble seed; everything else (and the modern format) uses the seed as-is.
    private fun decodeScrambleHash(hash: String?): Int = when (hash?.trim()) {
        "03632" -> 58414
        "02900" -> 117532
        else -> 0
    }

    // Undo the x-enc XOR stream. Algo "2" is ambiguous about which generator the
    // server used, so we try each candidate and keep the first that decodes to a
    // recognisable image; every other algo is the plain LCG keystream.
    private fun decodeEncodedBytes(bytes: ByteArray, seed: Int, length: Int, algo: String?): ByteArray {
        if (algo != "2") {
            return decodeWithLcg(bytes, seed, length)
        }
        val candidates = listOf(
            decodeWithXorshift(bytes, seed or 1, length, false),
            decodeWithXorshift(bytes, seed, length, false),
            decodeWithXorshift(bytes, seed or 1, length, true),
            decodeWithLcg(bytes, seed, length),
        )
        return candidates.firstOrNull { it.hasImageSignature() } ?: candidates.first()
    }

    private fun decodeWithLcg(bytes: ByteArray, seed: Int, length: Int): ByteArray {
        val result = bytes.copyOf()
        var state = seed
        val limit = minOf(result.size, length)
        for (i in 0 until limit) {
            state = state * ENC_MULTIPLIER + ENC_INCREMENT
            result[i] = (result[i].toInt() xor (state ushr 24)).toByte()
        }
        return result
    }

    private fun decodeWithXorshift(bytes: ByteArray, initialState: Int, length: Int, highByte: Boolean): ByteArray {
        val result = bytes.copyOf()
        var state = initialState
        val limit = minOf(result.size, length)
        for (i in 0 until limit) {
            state = state xor (state shl 13)
            state = state xor (state ushr 17)
            state = state xor (state shl 5)
            val key = if (highByte) state ushr 24 else state and 0xFF
            result[i] = (result[i].toInt() xor key).toByte()
        }
        return result
    }

    private fun ByteArray.hasImageSignature(): Boolean = size >= 12 && (
        (
            this[0] == 'R'.code.toByte() && this[1] == 'I'.code.toByte() && this[2] == 'F'.code.toByte() &&
                this[3] == 'F'.code.toByte() && this[8] == 'W'.code.toByte() && this[9] == 'E'.code.toByte() &&
                this[10] == 'B'.code.toByte() && this[11] == 'P'.code.toByte()
            ) ||
            (this[0] == 0xFF.toByte() && this[1] == 0xD8.toByte()) ||
            (
                this[0] == 0x89.toByte() && this[1] == 'P'.code.toByte() && this[2] == 'N'.code.toByte() &&
                    this[3] == 'G'.code.toByte()
                )
        )

    // Reverses the site's 5x5 tile shuffle. The scramble order is a Fisher-Yates
    // permutation driven by a PRNG seeded with `x-scramble-seed` (xored with the
    // optional hash). Algo "3" uses a xorshift generator; every other algo uses an
    // LCG. `order[srcIdx]` gives the destination position of scrambled tile srcIdx.
    private fun descramble(source: Bitmap, seed: Int, algo: String?): Bitmap {
        val width = source.width
        val height = source.height
        val tileW = width / GRID_COLS
        val tileH = height / GRID_ROWS
        val order = if (algo == "3") {
            buildScrambleOrderXorshift(seed, NUM_TILES)
        } else {
            buildScrambleOrderLcg(seed, NUM_TILES)
        }

        val output = context.createBitmap(width, height)
        // Copy the whole image first so any edge pixels left over from the
        // integer tile division are preserved.
        output.drawBitmap(source, Rect(0, 0, width, height), Rect(0, 0, width, height))

        for (srcIdx in 0 until NUM_TILES) {
            val dstIdx = order[srcIdx]
            val srcCol = srcIdx % GRID_COLS
            val srcRow = srcIdx / GRID_COLS
            val dstCol = dstIdx % GRID_COLS
            val dstRow = dstIdx / GRID_COLS
            val srcRect = Rect(srcCol * tileW, srcRow * tileH, (srcCol + 1) * tileW, (srcRow + 1) * tileH)
            val dstRect = Rect(dstCol * tileW, dstRow * tileH, (dstCol + 1) * tileW, (dstRow + 1) * tileH)
            output.drawBitmap(source, srcRect, dstRect)
        }
        return output
    }

    private fun buildScrambleOrderLcg(seed: Int, n: Int): IntArray {
        val arr = IntArray(n) { it }
        var state = seed
        for (i in n - 1 downTo 1) {
            state = state * LCG_MULTIPLIER + LCG_INCREMENT
            val j = ((state.toLong() and 0xFFFFFFFFL) % (i + 1)).toInt()
            val tmp = arr[i]
            arr[i] = arr[j]
            arr[j] = tmp
        }
        return arr
    }

    private fun buildScrambleOrderXorshift(seed: Int, n: Int): IntArray {
        val arr = IntArray(n) { it }
        var state = seed or 1
        for (i in n - 1 downTo 1) {
            state = state xor (state shl 13)
            state = state xor (state ushr 17)
            state = state xor (state shl 5)
            val j = ((state.toLong() and 0xFFFFFFFFL) % (i + 1)).toInt()
            val tmp = arr[i]
            arr[i] = arr[j]
            arr[j] = tmp
        }
        return arr
    }

    private suspend fun getChapters(manga: Manga): List<MangaChapter> {
        val hashId = manga.url.substringAfter("/title/")
        val payload = loadAllChapters(hashId)
        val rawItems = payload.optJSONArray("items") ?: return emptyList()
        val parsed = (0 until rawItems.length()).mapNotNull { rawItems.optJSONObject(it) }
        if (parsed.isEmpty()) {
            return emptyList()
        }

        // The script sends the shared URL prefix and the group list once and has
        // every chapter reference them by index — see [CHAPTER_SCRIPT].
        val urlPrefix = payload.optString("prefix")
        val groups = payload.optJSONArray("groups")

        // Every scanlation team is kept: each one becomes its own branch, so the
        // reader gets the site's full "All groups" list with a translation
        // picker rather than a single team chosen for it.
        //
        // The site serves chapters newest-first and the capture script merges
        // several pages, so order the list here instead of trusting either.
        val chapters = parsed.sortedBy { it.optDouble("n", 0.0) }

        val chaptersBuilder = ChaptersListBuilder(chapters.size)
        for (chapterData in chapters) {
            val chapterId = chapterData.optLong("i")
            val number = chapterData.optDouble("n", 0.0).toFloat()
            val name = chapterData.optString("t").nullIfEmpty()
            val scanlator = teamNameOf(groups?.optJSONObject(chapterData.optInt("g", -1)))
            val label = number.toChapterUrlPart()
            val title = if (name != null) {
                "Chapter $label: $name"
            } else {
                "Chapter $label"
            }
            // Prefer the canonical path the site itself links to — it carries the
            // full title slug (e.g. `/title/x0ynk-villains.../<id>-chapter-N`).
            // The hashId-only path 404s in the reader.
            val chapterUrl = chapterData.optString("u").nullIfEmpty()
                ?.let { urlPrefix + it }
                ?: "/title/$hashId/$chapterId-chapter-$label"
            chaptersBuilder.add(
                MangaChapter(
                    id = generateUid("$scanlator-$chapterId"),
                    title = title,
                    number = number,
                    volume = chapterData.optIntOrNull("v")?.coerceAtLeast(0) ?: 0,
                    url = chapterUrl,
                    uploadDate = chapterUploadDate(chapterData),
                    source = source,
                    scanlator = scanlator,
                    branch = scanlator,
                ),
            )
        }

        return chaptersBuilder.toList()
    }

    /**
     * The capture script emits an epoch timestamp (`c`) when the API payload
     * carried one, and only the site's relative label (`d`, "3 days ago") when
     * the row was read off the rendered list.
     */
    private fun chapterUploadDate(chapter: JSONObject): Long {
        chapter.optLongOrNull("c")?.let { raw ->
            return if (raw < SECONDS_TIMESTAMP_LIMIT) raw * 1000L else raw
        }
        return parseRelativeDate(chapter.optString("d"))
    }

    /** The branch a chapter belongs to — its scanlation team. */
    private fun teamNameOf(group: JSONObject?): String {
        return group?.optString("name")?.nullIfEmpty()
            ?: if (group?.optInt("o") == 1) "Official" else "Unknown"
    }

    /**
     * The title page ships no chapters in `script#initial-data` — the list comes over
     * a signed, encoded XHR after hydration — so the page's own code has to make the
     * calls for us: the script asks the site's API client for every page in turn
     * (see [chapterScript]).
     */
    private suspend fun loadAllChapters(hashId: String): JSONObject {
        val titleUrl = "https://$domain/title/$hashId"

        // No HTTP fetch of the title page here: the script runs on that very page in
        // the WebView, so this starts straight away and in parallel with the details
        // request rather than queueing behind it.
        //
        // One title at a time, and the next one's page (which asks for its first
        // chapter page as it loads) only after the usual interval, so opening several
        // titles in a row stays within the pace the site tolerates.
        val response = chapterListLock.withLock {
            val wait = chapterListEndedAt + CHAPTER_PAGE_INTERVAL_MS - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            try {
                evaluateWebViewApiJson(
                    pageUrl = titleUrl,
                    script = chapterScript(hashId.toJsString()),
                    timeoutMs = CHAPTER_WEBVIEW_TIMEOUT,
                )
            } catch (e: ParseException) {
                // The WebView cannot always say it was stopped by Cloudflare (see
                // [buildWebViewApiBridgeScript]), so a failure gets one plain request
                // to tell.
                val cloudflare = runCatching { webClient.httpGet(titleUrl).close() }
                    .exceptionOrNull()?.isCloudFlareProtection() == true
                if (cloudflare) {
                    requestCloudflareVerification(titleUrl, e)
                }
                throw e
            } finally {
                chapterListEndedAt = System.currentTimeMillis()
            }
        }
        val items = response.optJSONArray("items")
            ?: throw ParseException("Comix chapter capture returned no items array", titleUrl)
        if (items.length() == 0 && !response.optBoolean("empty")) {
            throw ParseException("Comix chapter list did not load", titleUrl)
        }
        return response
    }

    private fun extractInitialDataPages(document: Document): JSONObject? {
        val raw = document.selectFirst("script#initial-data")?.data()?.nullIfEmpty() ?: return null
        val queries = runCatching { JSONObject(raw).optJSONObject("queries") }.getOrNull() ?: return null
        for (key in queries.keys()) {
            val value = queries.optJSONObject(key) ?: continue
            if (value.optJSONObject("result")?.has("pages") == true) return value
            if (value.has("pages")) return JSONObject().put("result", value)
        }
        return null
    }

    private fun apiUrl(path: String): String = "https://$domain/api/v1/${path.removePrefix("/")}"

    private suspend fun evaluateWebViewApiJson(
        pageUrl: String,
        script: String,
        timeoutMs: Long = WEBVIEW_API_TIMEOUT,
    ): JSONObject {
        val bridgeScript = buildWebViewApiBridgeScript(script)
        repeat(WEBVIEW_NAVIGATION_ATTEMPTS) { attempt ->
            val navigationUrl = if (attempt == 0) pageUrl else pageUrl.withWebViewCacheBuster()
            val requests = runCatching {
                context.interceptWebViewRequests(
                    navigationUrl,
                    InterceptionConfig(
                        timeoutMs = timeoutMs,
                        maxRequests = 1,
                        urlPattern = INTERCEPT_URL_REGEX,
                        pageScript = bridgeScript,
                    ),
                )
            }.getOrElse { e ->
                throw ParseException("Comix WebView API interception failed", pageUrl, e)
            }
            val resultUrl = requests.firstOrNull()?.url
                ?: throw ParseException("Comix WebView API did not return a bridge result", pageUrl)
            val decoded = when {
                resultUrl.contains("/error", ignoreCase = true) -> {
                    val message = resultUrl.queryParameterValue("msg") ?: "unknown WebView error"
                    throw ParseException("Comix WebView API failed: $message", pageUrl)
                }
                else -> resultUrl.base64QueryParameterValue("data64")
                    ?: resultUrl.queryParameterValue("data")
                    ?: throw ParseException("Comix WebView API bridge result missing data", pageUrl)
            }
            if (decoded == WEBVIEW_LOAD_FAILED) {
                if (attempt + 1 < WEBVIEW_NAVIGATION_ATTEMPTS) return@repeat
                requestWebViewRecovery(pageUrl)
            }
            if (decoded == CLOUDFLARE_BLOCKED || isCloudflarePage(decoded)) {
                requestCloudflareVerification(pageUrl)
            }
            if (decoded == SITE_CAPTCHA) {
                requestSiteCaptcha(pageUrl)
            }
            if (decoded.isBlank()) {
                throw ParseException("Comix WebView API returned an empty response", pageUrl)
            }
            val json = runCatching { JSONObject(decoded) }.getOrElse { e ->
                throw ParseException("Comix WebView API returned invalid JSON: ${decoded.take(200)}", pageUrl, e)
            }
            if (json.optBoolean("blocked")) {
                throw ParseException(BLOCKED_MESSAGE, pageUrl)
            }
            json.optString("error").nullIfEmpty()?.let { error ->
                throw ParseException("Comix WebView API failed: $error", pageUrl)
            }
            return json
        }
        throw ParseException("Comix WebView API could not load the page", pageUrl)
    }

    private fun String.withWebViewCacheBuster(): String {
        return toHttpUrlOrNull()?.newBuilder()
            ?.removeAllQueryParameters(WEBVIEW_CACHE_BUSTER_PARAM)
            ?.addQueryParameter(WEBVIEW_CACHE_BUSTER_PARAM, System.currentTimeMillis().toString())
            ?.build()
            ?.toString()
            ?: this
    }

    private fun buildWebViewApiBridgeScript(script: String): String {
        // The script is injected on every navigation and polled while a page is up,
        // so it can start many times over. One run at a time, and a run that has
        // nothing yet (null) leaves without reporting so a later one can try again.
        //
        // The result goes out as a request for a made-up url that the app captures.
        // It is a fetch rather than a navigation: WebView hands navigations to the
        // app without request headers, which the app's capture fails on, so a
        // navigation never came back. A fetch drops the url fragment, hence the
        // query string.
        //
        // Cloudflare's challenge page only lets scripts connect to its own hosts, so
        // there the fetch is refused and a navigation goes out instead. With the app
        // failing on it the capture ends in an error, and the caller tells a
        // challenge apart with a plain request.
        return """
            (function() {
                $SITE_HOOKS_JS
                const state = '__comixBridgeState';
                if (window[state]) return;
                window[state] = true;
                const report = (url) => {
                    const refused = (event) => {
                        if (String(event.blockedURI || '').indexOf('kotatsu.intercept') !== -1) {
                            document.removeEventListener('securitypolicyviolation', refused);
                            window.location.href = url;
                        }
                    };
                    document.addEventListener('securitypolicyviolation', refused);
                    try {
                        fetch(url, { mode: 'no-cors', cache: 'no-store' }).catch(() => {});
                    } catch (e) {
                        window.location.href = url;
                    }
                };
                (async function() {
                    try {
                        const result = await $script;
                        if (result === null || result === undefined || result === "") {
                            window[state] = false;
                            return;
                        }
                        const bytes = new TextEncoder().encode(String(result));
                        let binary = '';
                        const chunkSize = 0x8000;
                        for (let i = 0; i < bytes.length; i += chunkSize) {
                            binary += String.fromCharCode.apply(
                                null,
                                bytes.subarray(i, Math.min(i + chunkSize, bytes.length))
                            );
                        }
                        const data = btoa(binary)
                            .replace(/\+/g, '-')
                            .replace(/\//g, '_')
                            .replace(/=+$/g, '');
                        report("$INTERCEPT_RESULT_URL?data64=" + data);
                    } catch (e) {
                        report("$INTERCEPT_ERROR_URL?msg=" +
                            encodeURIComponent(String((e && e.message) || e)));
                    }
                })();
            })();
        """.trimIndent()
    }

    private fun String.rawQueryParameterValue(name: String): String? {
        val query = substringAfter('#', substringAfter('?', ""))
        if (query.isEmpty()) return null
        return query.split('&')
            .asSequence()
            .map { it.split('=', limit = 2) }
            .firstOrNull { it.size == 2 && it[0] == name }
            ?.get(1)
    }

    private fun String.queryParameterValue(name: String): String? {
        return rawQueryParameterValue(name)
            ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }
    }

    private fun String.base64QueryParameterValue(name: String): String? {
        return rawQueryParameterValue(name)?.let { encoded ->
            runCatching {
                String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8)
            }.getOrNull()
        }
    }

    private fun String.toJsString(): String {
        return "\"" + replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t") + "\""
    }

    /** Detected here, resolved by the app. */
    private fun requestCloudflareVerification(url: String, cause: Throwable? = null): Nothing {
        try {
            context.requestCloudflareVerification(this, url)
        } catch (e: UnsupportedOperationException) {
            throw ParseException(CLOUDFLARE_MESSAGE, url, cause ?: e)
        }
    }

    private fun requestWebViewRecovery(url: String): Nothing {
        try {
            context.requestBrowserAction(this, url)
        } catch (e: UnsupportedOperationException) {
            throw ParseException(WEBVIEW_LOAD_MESSAGE, url, e)
        }
    }

    /**
     * The site's own "verify you're human" captcha, which it puts up when its API
     * answers 403 captcha_required. The reader solves it in the browser, where the
     * page asks for it again.
     */
    private fun requestSiteCaptcha(url: String): Nothing {
        try {
            context.requestBrowserAction(this, url)
        } catch (e: UnsupportedOperationException) {
            throw ParseException(SITE_CAPTCHA_MESSAGE, url, e)
        }
    }

    /**
     * The app raises its own Cloudflare exception from a module this library cannot
     * reference by type, so it is recognised by name instead.
     */
    private fun Throwable.isCloudFlareProtection(): Boolean {
        var error: Throwable? = this
        var depth = 0
        while (error != null && depth < CAUSE_CHAIN_LIMIT) {
            if (error.javaClass.simpleName.contains("CloudFlare", ignoreCase = true)) return true
            if (error.message?.contains("cloudflare", ignoreCase = true) == true) return true
            error = error.cause
            depth++
        }
        return false
    }

    private fun isCloudflarePage(html: String): Boolean {
        if (html.isBlank()) return false
        val lower = html.lowercase(Locale.US)
        return lower.contains("cf-browser-verification") ||
            lower.contains("cf-chl-opt") ||
            lower.contains("window._cf_chl_opt") ||
            lower.contains("__cf_chl") ||
            lower.contains("challenge-platform") ||
            lower.contains("challenges.cloudflare.com") ||
            lower.contains("cf-turnstile") ||
            lower.contains("challenge-error-title") ||
            lower.contains("challenge-error-text")
    }

    private fun parseTerms(json: JSONObject): Set<MangaTag> {
        val tags = LinkedHashSet<MangaTag>()
        for (key in TERM_KEYS) {
            tags += parseTerms(json.optJSONArray(key))
        }
        return tags
    }

    private fun parseTerms(array: JSONArray?): Set<MangaTag> {
        if (array == null) return emptySet()
        return (0 until array.length()).mapNotNullTo(LinkedHashSet()) { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNullTo null
            val title = item.optString("title").nullIfEmpty()
                ?: item.optString("name").nullIfEmpty()
                ?: return@mapNotNullTo null
            // Prefer the numeric id — it's exactly what `genres_in[]` expects,
            // so a tag chip tapped on the details page filters correctly with
            // no name lookup. Fall back to the title for safety.
            val key = item.optIntOrNull("id")?.toString() ?: title
            MangaTag(
                key = key,
                title = title,
                source = source,
            )
        }
    }

    private val chapterListLock = Mutex()

    @Volatile
    private var chapterListEndedAt = 0L

    private val tagIdCache = ConcurrentHashMap<String, String>()

    /**
     * Resolve a genre/tag name to the numeric id the API uses in `genres_in[]`,
     * via the public /tags/search endpoint. Curated genres are looked up first
     * (`type=genre`), then the larger narrative-tag space (`type=tag`). Results
     * are cached; an empty string marks a name that matched nothing.
     */
    private suspend fun resolveTagId(name: String): String? {
        val cacheKey = name.trim().lowercase(Locale.US)
        if (cacheKey.isEmpty()) return null
        tagIdCache[cacheKey]?.let { return it.nullIfEmpty() }
        for (type in arrayOf("genre", "tag")) {
            val url = apiUrl("tags/search?type=$type&q=${name.urlEncoded()}")
            val result = runCatching {
                webClient.httpGet(url).parseJson().optJSONArray("result")
            }.getOrNull()
            val id = result?.optJSONObject(0)?.optIntOrNull("id")?.toString()
            if (id != null) {
                tagIdCache[cacheKey] = id
                return id
            }
        }
        tagIdCache[cacheKey] = ""
        return null
    }

    /** `altTitles` on the current payload, `alt_titles` on the older one. */
    private fun parseAltTitles(json: JSONObject): Set<String> {
        val titles = json.optJSONArray("altTitles") ?: json.optJSONArray("alt_titles") ?: return emptySet()
        return (0 until titles.length()).mapNotNullTo(LinkedHashSet()) { i ->
            titles.optString(i).trim().nullIfEmpty()
        }
    }

    private fun parseAuthors(json: JSONObject): Set<String> {
        val authors = json.optJSONArray("authors") ?: json.optJSONArray("author") ?: return emptySet()
        return (0 until authors.length()).mapNotNullTo(LinkedHashSet()) { i ->
            val item = authors.optJSONObject(i) ?: return@mapNotNullTo null
            item.optString("title").nullIfEmpty() ?: item.optString("name").nullIfEmpty()
        }
    }

    private fun parseRelativeDate(date: String?): Long {
        if (date.isNullOrBlank()) return 0L
        val match = RELATIVE_DATE_REGEX.find(date.trim().lowercase().removeSuffix(" ago")) ?: return 0L
        val amount = match.groupValues[1].toIntOrNull() ?: return 0L
        val calendar = Calendar.getInstance()
        when (match.groupValues[2]) {
            "s", "sec", "secs" -> calendar.add(Calendar.SECOND, -amount)
            "m", "min", "mins" -> calendar.add(Calendar.MINUTE, -amount)
            "h", "hr", "hrs" -> calendar.add(Calendar.HOUR_OF_DAY, -amount)
            "d", "day", "days" -> calendar.add(Calendar.DAY_OF_YEAR, -amount)
            "w", "week", "weeks" -> calendar.add(Calendar.WEEK_OF_YEAR, -amount)
            "mo", "mos", "month", "months" -> calendar.add(Calendar.MONTH, -amount)
            "y", "yr", "yrs", "year", "years" -> calendar.add(Calendar.YEAR, -amount)
        }
        return calendar.timeInMillis
    }

    private fun JSONObject.optIntOrNull(key: String): Int? {
        return if (has(key) && !isNull(key)) optInt(key) else null
    }

    private fun JSONObject.optLongOrNull(key: String): Long? {
        return if (has(key) && !isNull(key)) optLong(key) else null
    }

    private fun Float.toChapterUrlPart(): String {
        return if (this % 1f == 0f) {
            toInt().toString()
        } else {
            toString().trimEnd('0').trimEnd('.')
        }
    }

    private companion object {
        private val TERM_KEYS = arrayOf("genres", "genre", "tags", "theme", "demographics", "demographic", "formats")
        private const val PAGE_FRAGMENT = "comix-page"
        private const val ALT_HOST_ATTEMPTS = 6
        private const val ALT_HOST_RETRY_DELAY_MS = 2000L
        private val SCRAMBLE_PATH_FALLBACKS = listOf("/hi/", "/fcf/", "/i5/", "/si/", "/i/", "/sii/", "/ii/")
        private val SCRAMBLE_PATH_FALLBACK_REGEX = Regex("/(?:i5|s?i+)/")
        private const val GRID_COLS = 5
        private const val GRID_ROWS = 5
        private const val NUM_TILES = GRID_COLS * GRID_ROWS
        private const val LCG_MULTIPLIER = 1664525
        private const val LCG_INCREMENT = 1013904223
        private const val ENC_MULTIPLIER = 1000005
        private const val ENC_INCREMENT = 1234567891
        private val RELATIVE_DATE_REGEX = Regex("""^(\d+)\s*(s|m|h|d|w|mo|mos|y|yr|yrs|min|mins|sec|secs|hr|hrs|day|days|week|weeks|month|months|year|years)$""")
        private const val WEBVIEW_API_TIMEOUT = 90000L

        // Chapter collection is not time-boxed: [chapterScript] pages until the site
        // reports the list complete, one page every [CHAPTER_PAGE_INTERVAL_MS]. This
        // is only the ceiling for a WebView that has stopped responding altogether.
        private const val CHAPTER_WEBVIEW_TIMEOUT = 900000L

        // What the site's own chapter list asks for. Larger pages, or pages asked for
        // faster than this, get every request from the device answered with HTTP 502
        // for a while.
        private const val CHAPTER_PAGE_LIMIT = 20
        private const val CHAPTER_PAGE_INTERVAL_MS = 2500

        // Ceiling on chapter pages, far beyond any real series; only guards against a
        // pager that never reports its end.
        private const val MAX_CHAPTER_API_PAGES = 400

        // How long the scripts look for the site's API client among its modules,
        // which keep loading after the page's load event.
        private const val SITE_API_WAIT_MS = 20000

        // Below this, a timestamp is seconds rather than milliseconds
        // (2286-11-20 in seconds, 1973-03-03 in milliseconds).
        private const val SECONDS_TIMESTAMP_LIMIT = 10_000_000_000L
        private const val CLOUDFLARE_BLOCKED = "CLOUDFLARE_BLOCKED"
        private const val WEBVIEW_LOAD_FAILED = "WEBVIEW_LOAD_FAILED"
        private const val SITE_CAPTCHA = "SITE_CAPTCHA"
        private const val INTERCEPT_RESULT_URL = "https://kotatsu.intercept/result"
        private const val INTERCEPT_ERROR_URL = "https://kotatsu.intercept/error"
        private val INTERCEPT_URL_REGEX = Regex("https://kotatsu\\.intercept/.*", RegexOption.IGNORE_CASE)
        private const val CLOUDFLARE_MESSAGE =
            "Comix could not get past the Cloudflare check. Try again in a moment."
        private const val WEBVIEW_LOAD_MESSAGE =
            "Comix could not load the page in WebView. Open it in the browser and try again."
        private const val SITE_CAPTCHA_MESSAGE =
            "Comix wants you to solve its captcha. Open the site in the browser, solve it and try again."
        private const val BLOCKED_MESSAGE =
            "Comix is refusing requests from this device for now (HTTP 502). Try again later."
        private const val WEBVIEW_CACHE_BUSTER_PARAM = "_kotatsu_retry"
        private const val WEBVIEW_NAVIGATION_ATTEMPTS = 2

        private const val CAUSE_CHAIN_LIMIT = 8
        private const val MAIN_MODULE_SELECTOR_JS = "'script[type=module][src*=\"/dist/main-\"]'"
        private const val WEBVIEW_PAGE_ATTEMPTS = 3
        private const val WEBVIEW_PAGE_TIMEOUT = 20000L

        // Installed once per document, ahead of the scripts.
        //
        // Records what JSON.parse returns in `window.__comixSeen`, newest last, which
        // is how the browse list reaches the page once decoded.
        //
        // Records each title-list response (`/api/v1/manga`) in `window.__comixLists`
        // with the keyword it was asked for, so the browse capture can tell a search
        // result from a list the page loaded before the search applied (after
        // keiyoushi's browse capture).
        //
        // Notes a 403 captcha_required answer: the site asking for its own captcha.
        // The site's client then waits for it to be solved, so a call that hit it
        // never settles in a hidden WebView.
        private const val SITE_HOOKS_JS = """
                if (!window.__comixHooks) {
                    window.__comixHooks = true;
                    const seen = window.__comixSeen = [];
                    const keep = (holder) => {
                        if (holder && typeof holder === 'object' && Array.isArray(holder.items)) {
                            seen.push(holder);
                            if (seen.length > 100) seen.shift();
                        }
                    };
                    const parse = JSON.parse;
                    JSON.parse = function () {
                        const value = parse.apply(this, arguments);
                        try {
                            if (value && typeof value === 'object') {
                                keep(value);
                                const result = value.result;
                                if (result && typeof result === 'object') {
                                    keep(result);
                                    keep(result.pages);
                                }
                            }
                        } catch (e) {}
                        return value;
                    };
                    const lists = window.__comixLists = [];
                    const record = (rawUrl, text) => {
                        try {
                            const url = new URL(String(rawUrl || ''), location.origin);
                            if (url.pathname.replace(/\/+$/, '') !== '/api/v1/manga') return;
                            const value = parse(text);
                            const holder = value && value.result && Array.isArray(value.result.items)
                                ? value.result
                                : (value && Array.isArray(value.items) ? value : null);
                            if (holder) lists.push({ keyword: (url.searchParams.get('keyword') || '').trim(), holder: holder });
                        } catch (e) {}
                    };
                    const open = XMLHttpRequest.prototype.open;
                    XMLHttpRequest.prototype.open = function (method, url) {
                        this.__comixUrl = url;
                        return open.apply(this, arguments);
                    };
                    const send = XMLHttpRequest.prototype.send;
                    XMLHttpRequest.prototype.send = function () {
                        this.addEventListener('load', function () {
                            try {
                                if (this.status === 403 && String(this.responseText).indexOf('captcha_required') !== -1) {
                                    window.__comixCaptcha = true;
                                }
                                if (this.status === 200) record(this.responseURL || this.__comixUrl, this.responseText);
                            } catch (e) {}
                        });
                        return send.apply(this, arguments);
                    };
                    if (typeof window.fetch === 'function') {
                        const fetchNative = window.fetch;
                        window.fetch = function () {
                            return fetchNative.apply(this, arguments).then((response) => {
                                try {
                                    if (response && response.ok && String(response.url).indexOf('/api/v1/manga') !== -1) {
                                        response.clone().text().then((text) => record(response.url, text)).catch(() => {});
                                    }
                                } catch (e) {}
                                return response;
                            });
                        };
                    }
                }
        """

        // Recognises blocking documents from stable DOM markers rather than their
        // localised titles. [evaluateWebViewApiJson] turns a Cloudflare result into
        // a browser prompt; a Chromium network-error result gets one cache-busted
        // retry first because a fresh WebView can fail its initial navigation.
        private const val CLOUDFLARE_DETECT_JS = """
                const isCloudflareChallenge = () => {
                    try {
                        return !!document.querySelector(
                            '#challenge-form, #challenge-running, #challenge-error-title, #challenge-error-text, ' +
                            '#cf-challenge-running, .cf-browser-verification, .cf-turnstile, ' +
                            'form[action*="__cf_chl"], input[name="cf-turnstile-response"], ' +
                            'script[src*="challenge-platform"], script[src*="turnstile"], ' +
                            '[src*="challenges.cloudflare.com"]'
                        );
                    } catch (e) {
                        return false;
                    }
                };
                const isWebViewLoadError = () => {
                    try {
                        const uri = String(document.documentURI || '');
                        return uri.indexOf('chrome-error://') === 0 || !!document.querySelector(
                            '#main-frame-error, #error-information-popup-container, body.neterror'
                        );
                    } catch (e) {
                        return false;
                    }
                };
                const isSiteCaptcha = () => {
                    try {
                        return !!window.__comixCaptcha || !!document.querySelector('iframe[src*="/@waf/"]');
                    } catch (e) {
                        return false;
                    }
                };
                // Cloudflare's own error page, which is what the site's block looks like.
                const isBadGateway = () => {
                    try {
                        return /\b502\b/.test(document.title) && !!document.querySelector('#cf-error-details, .cf-error-details, #cf-wrapper');
                    } catch (e) {
                        return false;
                    }
                };
                const blockedResult = () => JSON.stringify({ error: 'HTTP 502', blocked: true });
        """

        // The site's own API client (after keiyoushi's site_api.js): a route from its
        // route table and its GET helper, which goes through the site's request
        // signing and resolves to the decoded response body. Both are found among
        // the exports of the modules next to the page's main module, so nothing
        // depends on the minified names. `siteApi(isRoute)` resolves to
        // `(id, params) => Promise<body>`, or null when not found in time.
        //
        // Also defines `untilSettled`, which races a call against the site's captcha
        // (see [SITE_HOOKS_JS]) so a call that hit it is not waited on forever.
        private const val SITE_API_JS = """
                const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
                // A bare import() in an injected script is parsed in the wrong context;
                // going through Function keeps it a real dynamic import.
                const importModule = new Function('url', 'return import(url)');
                const isGet = (value) => typeof value === 'function' &&
                    String(value).indexOf('.get(') !== -1 &&
                    /\.data\)?$/.test(String(value));
                const siteApi = async (isRoute) => {
                    const find = async () => {
                        const main = document.querySelector($MAIN_MODULE_SELECTOR_JS);
                        if (!main || !main.src) return null;
                        const directory = main.src.slice(0, main.src.lastIndexOf('/') + 1);
                        const loaded = performance.getEntriesByType('resource')
                            .map((entry) => entry.name)
                            .filter((url) => url.indexOf(directory) === 0 && /\.js(\?|$)/.test(url));
                        const members = [];
                        for (const url of new Set([main.src, ...loaded])) {
                            let exported;
                            try {
                                exported = await importModule(url);
                            } catch (e) {
                                continue;
                            }
                            for (const value of Object.values(exported)) {
                                if (value && typeof value === 'object') members.push(...Object.values(value));
                            }
                            const route = members
                                .filter((group) => group && typeof group === 'object')
                                .flatMap((group) => Object.values(group))
                                .find(isRoute);
                            const get = members.find(isGet);
                            if (route && get) return (id, params) => get(route(id), { params: params });
                        }
                        return null;
                    };
                    const deadline = Date.now() + $SITE_API_WAIT_MS;
                    for (;;) {
                        if (document.readyState === 'complete') {
                            const api = await find();
                            if (api) return api;
                        }
                        if (Date.now() >= deadline) return null;
                        await sleep(250);
                    }
                };
                const CAPTCHA = {};
                const untilSettled = (call) => {
                    let timer = null;
                    const captcha = new Promise((resolve) => {
                        timer = setInterval(() => {
                            if (isSiteCaptcha()) resolve(CAPTCHA);
                        }, 250);
                    });
                    return Promise.race([call, captcha]).finally(() => clearInterval(timer));
                };
                const failure = (e, what) => {
                    const status = e && e.response && e.response.status;
                    if (status === 502) return blockedResult();
                    return JSON.stringify({
                        error: what + ' failed: ' + (status ? 'HTTP ' + status : String((e && e.message) || e))
                    });
                };
        """

        // Collects the whole chapter list.
        //
        // Runs on the title page and asks the site's API client for one page at a
        // time, the way the site's own pager does. The site counts chapter requests
        // made with no click or key press shortly before, and after a few it flags
        // them (`v=1` and an X-Rt header), which gets the device blocked. keiyoushi
        // sends a real swipe before each request; a parser cannot, so each request
        // carries the flag the site sends right after a tap (`v=0`), which also
        // keeps the site from counting it. The site's request signing then rewrites
        // `v` the same way for these requests as for the page's own.
        //
        // The result crosses back in a URL, so it is emitted in a compact form — the
        // shared URL prefix and the scanlation groups are sent once and referenced by
        // index, and absent fields are omitted:
        //   prefix  shared start of every chapter URL
        //   groups  [{ id?, name?, o }] — o = 1 when the group's release is official
        //   items   [{ i: id, n: number, u: url suffix, g: group index,
        //              v: volume?, t: name?, c: epoch seconds?, d: relative date? }]
        private fun chapterScript(mangaId: String) = """
            (async () => {
$CLOUDFLARE_DETECT_JS
                if (isWebViewLoadError()) return '$WEBVIEW_LOAD_FAILED';
                if (isCloudflareChallenge()) return '$CLOUDFLARE_BLOCKED';
                if (isBadGateway()) return blockedResult();
$SITE_API_JS
                const MANGA_ID = $mangaId;
                const CHAPTER_PATH = '/title/' + MANGA_ID + '-';
                const isChapter = (item) => item &&
                    typeof item.id === 'number' &&
                    typeof item.number === 'number' &&
                    typeof item.url === 'string' &&
                    item.url.indexOf(CHAPTER_PATH) !== -1;

                const chapterPage = await siteApi((route) => typeof route === 'function' &&
                    /^\(?[\w$]+\)?=>`\/manga\/\$\{[\w$]+\}\/chapters`$/.test(String(route)));
                if (!chapterPage) {
                    if (isBadGateway()) return blockedResult();
                    return JSON.stringify({ error: "Could not find the site's chapters API" });
                }

                // The page asks for the first page itself as it loads; ours go out after
                // it, one interval apart.
                let lastRequest = Math.max(performance.now(), 1500);
                const pace = async () => {
                    const wait = lastRequest + $CHAPTER_PAGE_INTERVAL_MS - performance.now();
                    if (wait > 0) await sleep(wait);
                    lastRequest = performance.now();
                };

                const collected = [];
                let total = null;
                for (let page = 1; page <= $MAX_CHAPTER_API_PAGES; page++) {
                    await pace();
                    let result;
                    try {
                        result = await untilSettled(chapterPage(MANGA_ID, {
                            page: page,
                            limit: $CHAPTER_PAGE_LIMIT,
                            order: { number: 'desc' },
                            v: '0'
                        }));
                    } catch (e) {
                        if (isSiteCaptcha()) return '$SITE_CAPTCHA';
                        return failure(e, 'Chapter page ' + page);
                    }
                    if (result === CAPTCHA) return '$SITE_CAPTCHA';
                    const items = result && result.items;
                    const meta = result && result.meta;
                    if (!Array.isArray(items) || !items.every(isChapter) || !meta) {
                        return JSON.stringify({
                            error: 'Chapter page ' + page + " came back in a shape the parser doesn't recognise"
                        });
                    }
                    collected.push(...items);
                    if (typeof meta.total === 'number') total = meta.total;
                    const lastPage = meta.lastPage || meta.last_page || page;
                    if (items.length === 0 || meta.hasNext === false || page >= lastPage) break;
                }

                // --- Compact the result for the trip back. ---
                const unique = new Map();
                for (const chapter of collected) unique.set(chapter.id, chapter);
                const list = Array.from(unique.values());
                let prefix = list.length ? String(list[0].url || '') : '';
                for (const chapter of list) {
                    const url = String(chapter.url || '');
                    let i = 0;
                    while (i < prefix.length && i < url.length && prefix[i] === url[i]) i++;
                    prefix = prefix.slice(0, i);
                }

                const groups = [];
                const groupIndex = new Map();
                const items = list.map((chapter) => {
                    const group = chapter.group || null;
                    const official = chapter.isOfficial ? 1 : 0;
                    const groupId = group && group.id != null ? group.id : null;
                    const groupName = group && group.name
                        ? group.name
                        : (official ? 'Official' : null);
                    const key = (groupId != null ? 'i' + groupId : 'n' + (groupName || '')) +
                        '|' + official;
                    let g = groupIndex.get(key);
                    if (g === undefined) {
                        g = groups.length;
                        groupIndex.set(key, g);
                        const entry = { o: official };
                        if (groupId != null) entry.id = groupId;
                        if (groupName) entry.name = groupName;
                        groups.push(entry);
                    }
                    const row = {
                        i: chapter.id,
                        n: chapter.number,
                        u: String(chapter.url || '').slice(prefix.length),
                        g: g
                    };
                    if (chapter.volume != null) row.v = chapter.volume;
                    if (chapter.name) row.t = chapter.name;
                    if (typeof chapter.createdAt === 'number') row.c = chapter.createdAt;
                    else if (chapter.createdAtFormatted) row.d = chapter.createdAtFormatted;
                    return row;
                });

                return JSON.stringify({
                    prefix: prefix,
                    groups: groups,
                    items: items,
                    total: total,
                    empty: items.length === 0
                });
            })()
        """

        // The browse results arrive over a signed, encoded XHR the page decodes
        // itself; [SITE_HOOKS_JS] records the decoded payload, and this waits for a
        // list of titles to show up there (or in `script#initial-data`). Resolves with
        // `{ result: { items: [...] } }` as a compact JSON string for the bridge to
        // hand back. Browse responses include many fields the listing never
        // consumes, especially dozens of translated alternate titles per item;
        // carrying those back and into Manga objects causes avoidable delay.
        private const val BROWSE_CAPTURE_SCRIPT = """
            (async () => {
                const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
$CLOUDFLARE_DETECT_JS
                if (isWebViewLoadError()) return '$WEBVIEW_LOAD_FAILED';
                if (isCloudflareChallenge()) return '$CLOUDFLARE_BLOCKED';
                if (isBadGateway()) return blockedResult();
                const compactNamedItems = (values) => {
                    if (!Array.isArray(values)) return undefined;
                    return values.map((value) => {
                        if (!value || typeof value !== 'object') return null;
                        const item = {};
                        if (value.id != null) item.id = value.id;
                        if (value.title) item.title = value.title;
                        else if (value.name) item.name = value.name;
                        return item;
                    }).filter((value) => value && (value.title || value.name));
                };
                const compactItem = (item) => {
                    const result = {
                        hid: item.hid || item.hash_id || '',
                        title: item.title || ''
                    };
                    if (item.synopsis) result.synopsis = item.synopsis;
                    if (item.status) result.status = item.status;
                    if (item.contentRating) result.contentRating = item.contentRating;
                    if (item.ratedAvg != null) result.ratedAvg = item.ratedAvg;
                    else if (item.rated_avg != null) result.rated_avg = item.rated_avg;
                    if (item.poster && typeof item.poster === 'object') {
                        result.poster = {};
                        if (item.poster.large) result.poster.large = item.poster.large;
                        if (item.poster.medium) result.poster.medium = item.poster.medium;
                        if (item.poster.small) result.poster.small = item.poster.small;
                    }
                    const termKeys = ['genres', 'genre', 'tags', 'theme', 'demographics', 'demographic', 'formats'];
                    for (const key of termKeys) {
                        const values = compactNamedItems(item[key]);
                        if (values && values.length) result[key] = values;
                    }
                    const authors = compactNamedItems(item.authors || item.author);
                    if (authors && authors.length) result.authors = authors;
                    return result;
                };
                const isManga = (item) => item && typeof item === 'object' &&
                    !!(item.hid || item.hash_id) && typeof item.title === 'string';
                // A paginated list of titles. An empty one is a search with no match
                // or a page past the end; the browse page loads no other paged list.
                const isList = (holder) => holder && Array.isArray(holder.items) && (
                    holder.items.length > 0
                        ? holder.items.every(isManga)
                        : !!holder.meta && typeof holder.meta === 'object'
                );
                const found = (holder) => JSON.stringify({ result: { items: holder.items.map(compactItem) } });
                // A search only takes a list asked for with its keyword. A plain listing
                // also falls back to any decoded list, should the response itself not
                // be readable.
                const keyword = (new URL(location.href).searchParams.get('keyword') || '').trim();
                for (let i = 0; i < 300; i++) {
                    const lists = window.__comixLists || [];
                    for (let k = lists.length - 1; k >= 0; k--) {
                        if (lists[k].keyword === keyword && isList(lists[k].holder)) return found(lists[k].holder);
                    }
                    const seen = window.__comixSeen || [];
                    for (let k = seen.length - 1; k >= 0 && !keyword; k--) {
                        if (isList(seen[k])) return found(seen[k]);
                    }
                    try {
                        const node = document.querySelector('script#initial-data');
                        const queries = node && node.textContent && JSON.parse(node.textContent).queries;
                        if (queries) {
                            for (const k in queries) {
                                const value = queries[k];
                                const holder = value && (Array.isArray(value.items) ? value : value.result);
                                if (isList(holder) && holder.items.length > 0) return found(holder);
                            }
                        }
                    } catch (e) {}
                    if (isWebViewLoadError()) return '$WEBVIEW_LOAD_FAILED';
                    if (isCloudflareChallenge()) return '$CLOUDFLARE_BLOCKED';
                    if (isSiteCaptcha()) return '$SITE_CAPTCHA';
                    if (isBadGateway()) return blockedResult();
                    await sleep(100);
                }
                return JSON.stringify({ error: 'no browse data captured' });
            })()
        """

        // The reader's page list, from the site's chapter endpoint through its API
        // client (after keiyoushi's chapter_pages.js). Resolves with
        // `{ result: { pages: { baseUrl, items, alt, node } } }`.
        private fun pageScript(chapterId: String) = """
            (async () => {
$CLOUDFLARE_DETECT_JS
                if (isWebViewLoadError()) return '$WEBVIEW_LOAD_FAILED';
                if (isCloudflareChallenge()) return '$CLOUDFLARE_BLOCKED';
                if (isBadGateway()) return blockedResult();
$SITE_API_JS
                const CHAPTER_ID = $chapterId;
                const isImage = (item) => item && typeof item === 'object' &&
                    typeof item.url === 'string' && !item.hid && !item.title && item.number === undefined;
                const isPages = (value) => value && !value.meta &&
                    Array.isArray(value.items) && value.items.length > 0 && value.items.every(isImage);
                const found = (holder) => JSON.stringify({
                    result: {
                        pages: {
                            baseUrl: holder.baseUrl || '',
                            items: holder.items.map((item) => item.s != null ? { url: item.url, s: item.s } : { url: item.url }),
                            alt: Array.isArray(holder.alt) ? holder.alt : undefined,
                            node: holder.node || undefined
                        }
                    }
                });

                const chapter = await siteApi((route) => typeof route === 'function' &&
                    /^\(?[\w$]+\)?=>`\/chapters\/\$\{[\w$]+\}`$/.test(String(route)));
                if (!chapter) {
                    if (isBadGateway()) return blockedResult();
                    return JSON.stringify({ error: "Could not find the site's chapter API" });
                }
                let result;
                try {
                    result = await untilSettled(chapter(CHAPTER_ID));
                } catch (e) {
                    if (isSiteCaptcha()) return '$SITE_CAPTCHA';
                    return failure(e, 'Chapter pages');
                }
                if (result === CAPTCHA) return '$SITE_CAPTCHA';
                // The site's reader takes the pages either as an object or as a bare array.
                const pages = [result, ...Object.values(result || {})]
                    .map((value) => Array.isArray(value) ? { items: value } : value)
                    .find(isPages);
                if (!pages) {
                    return JSON.stringify({ error: "Chapter pages came back in a shape the parser doesn't recognise" });
                }
                return found(pages);
            })()
        """

        // Drives a WebView navigation past Cloudflare and returns the rendered
        // HTML. Resolves as soon as the SSR `script#initial-data` is present
        // (so we don't wait on the full `load` when the data is already there),
        // otherwise after a short cap — the caller decides if the document is
        // usable and retries the navigation if not.
        private const val PAGE_HTML_SCRIPT = """
            (() => new Promise((resolve) => {
                const finish = () => resolve(
                    document.documentElement ? document.documentElement.outerHTML : ""
                );
                const hasData = () => {
                    const node = document.querySelector('script#initial-data');
                    return !!(node && node.textContent && node.textContent.length > 50);
                };
                let waited = 0;
                const tick = () => {
                    if (hasData()) { finish(); return; }
                    waited += 250;
                    if (waited >= 12000) { finish(); return; }
                    setTimeout(tick, 250);
                };
                tick();
            }))()
        """
    }
}
