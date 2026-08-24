package org.koitharu.kotatsu.parsers.site.en

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.concurrent.ConcurrentHashMap

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
                // The website routes keyword search through `q` + `sort`.
                addParam("q=${query.urlEncoded()}")
                addParam("sort=relevance:desc")
            } else {
                when (order) {
                    // Relevance is only meaningful alongside a keyword, and it is
                    // not an `order[...]` field at all — the site passes it as
                    // `sort=relevance:desc`. With no query it falls back to the
                    // latest-update ordering, same as the website does.
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

            // Default exclude adult content, unless the user explicitly asked
            // for one of those genres via the filter.
            for (excludeId in ADULT_EXCLUDE_IDS) {
                if (excludeId !in includedIds) {
                    addParam("genres_ex[]=$excludeId")
                }
            }
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
            contentRating = if (json.optString("contentRating") in NSFW_RATINGS) ContentRating.ADULT else ContentRating.SAFE,
        )
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

        // Capture the reader page's own (signed, decrypted) page payload rather
        // than re-implementing the request signing, which hangs (see [loadAllChapters]).
        var sawCloudflare = false
        val response = runCatching { webClient.httpGet(readerUrl).parseHtml() }
            .onFailure { sawCloudflare = it.isCloudFlareProtection() }
            .getOrNull()
            ?.let { extractInitialDataPages(it) }
            ?: try {
                evaluateWebViewApiJson(readerUrl, PAGE_CAPTURE_SCRIPT)
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
            //
            // Everything else may still be protected by the older byte-level XOR, which
            // the server applies to every fourth page. Those responses only carry
            // x-enc-seed when the request has an `Origin` header — the exact opposite of
            // the v3 images, which withhold x-scramble-seed when `Origin` is present —
            // so the two kinds are tagged apart here and [intercept] sets the header for
            // the legacy ones only.
            //
            // Both fragments are dropped before the request goes out; they also keep a
            // protected page from colliding with an unprotected namesake in the cache.
            val parsedUrl = imageUrl.toHttpUrlOrNull()
            val isV3 = item?.optInt("s", 0) == 1 || parsedUrl?.queryParameterNames?.contains("v3") == true
            val isLegacyScramble = !isV3 && (i + 1) % 4 == 0
            val finalUrl = when {
                isV3 -> {
                    val withV3 = if (parsedUrl == null || parsedUrl.queryParameterNames.contains("v3")) {
                        imageUrl
                    } else {
                        parsedUrl.newBuilder().addQueryParameter("v3", null).build().toString()
                    }
                    "$withV3#$SCRAMBLED_FRAGMENT"
                }

                isLegacyScramble -> "$imageUrl#$LEGACY_SCRAMBLED_FRAGMENT"
                else -> imageUrl
            }
            MangaPage(
                id = generateUid("$chapterId-$i"),
                url = finalUrl,
                preview = null,
                source = source,
            )
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        // Legacy byte-XOR images only come back with their x-enc-* headers when the
        // request carries an `Origin`; v3 tile-scrambled ones do the reverse and
        // withhold x-scramble-seed when it is present, so it is added for the pages
        // [getPages] tagged as legacy and for nothing else.
        val request = chain.request().let { original ->
            if (original.url.fragment == LEGACY_SCRAMBLED_FRAGMENT && original.header("Origin") == null) {
                original.newBuilder().header("Origin", "https://$domain").build()
            } else {
                original
            }
        }
        val response = retryScramblePathFallbacks(chain, request, chain.proceed(request))
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
     * a signed XHR after hydration — so the page's own code has to make the call for
     * us. The script does not need the rendered list though: it pulls the signing
     * bundle the page's main module references and talks to the chapter API directly.
     */
    private suspend fun loadAllChapters(hashId: String): JSONObject {
        val titleUrl = "https://$domain/title/$hashId"

        // No HTTP fetch of the title page here: the script runs on that very page in
        // the WebView and reads the module tag itself, so this starts straight away
        // and in parallel with the details request rather than queueing behind it.
        val response = evaluateWebViewApiJson(
            pageUrl = titleUrl,
            script = chapterScript(hashId.toJsString(), cachedEnvUrl?.toJsString() ?: "null"),
            timeoutMs = CHAPTER_WEBVIEW_TIMEOUT,
        )
        // The bundle url only changes when the site is redeployed, so reusing it saves
        // downloading the whole main bundle on every later chapter load.
        response.optString("env").nullIfEmpty()?.let { cachedEnvUrl = it }
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
            if (decoded.isBlank()) {
                throw ParseException("Comix WebView API returned an empty response", pageUrl)
            }
            val json = runCatching { JSONObject(decoded) }.getOrElse { e ->
                throw ParseException("Comix WebView API returned invalid JSON: ${decoded.take(200)}", pageUrl, e)
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
        // nothing yet (null) leaves without navigating so a later one can try again.
        return """
            (function() {
                const state = '__comixBridgeState';
                if (window[state]) return;
                window[state] = true;
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
                        window.location.href = "$INTERCEPT_RESULT_URL#data64=" + data;
                    } catch (e) {
                        window.location.href = "$INTERCEPT_ERROR_URL#msg=" +
                            encodeURIComponent(String((e && e.message) || e));
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

    /** Url of the site's env bundle, reused across chapter loads. */
    @Volatile
    private var cachedEnvUrl: String? = null

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
        private val NSFW_RATINGS = setOf("erotica", "pornographic")
        private val TERM_KEYS = arrayOf("genres", "genre", "tags", "theme", "demographics", "demographic", "formats")
        private val ADULT_EXCLUDE_IDS = listOf("87264", "87266", "87268", "87265") // Adult, Hentai, Smut, Ecchi
        private const val SCRAMBLED_FRAGMENT = "scrambled"
        private const val LEGACY_SCRAMBLED_FRAGMENT = "enc-scrambled"
        private val SCRAMBLE_PATH_FALLBACKS = listOf("/i5/", "/si/", "/i/", "/sii/", "/ii/")
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

        // Chapter collection is not time-boxed: [CHAPTER_SCRIPT] pages until the
        // site reports the list complete. This is only the ceiling for a WebView
        // that has stopped responding altogether, so it is deliberately far
        // higher than any real chapter list should need.
        private const val CHAPTER_WEBVIEW_TIMEOUT = 600000L

        // How long the script waits for one page to render before deciding the
        // list has stalled and returning what it already has.
        private const val CHAPTER_STALL_MS = 45000

        // Ceiling for the API fast path: 100 chapters a call, so this is far beyond
        // any real series and only guards against a pager that never reports its end.
        private const val MAX_CHAPTER_API_PAGES = 200

        // How long the fast path waits for the page's main module to appear before
        // giving up on it and falling back to walking the rendered pager.
        private const val BUNDLE_WAIT_MS = 20000

        // Below this, a timestamp is seconds rather than milliseconds
        // (2286-11-20 in seconds, 1973-03-03 in milliseconds).
        private const val SECONDS_TIMESTAMP_LIMIT = 10_000_000_000L
        private const val CLOUDFLARE_BLOCKED = "CLOUDFLARE_BLOCKED"
        private const val WEBVIEW_LOAD_FAILED = "WEBVIEW_LOAD_FAILED"
        private const val INTERCEPT_RESULT_URL = "https://kotatsu.intercept/result"
        private const val INTERCEPT_ERROR_URL = "https://kotatsu.intercept/error"
        private val INTERCEPT_URL_REGEX = Regex("https://kotatsu\\.intercept/.*", RegexOption.IGNORE_CASE)
        private const val CLOUDFLARE_MESSAGE =
            "Comix could not get past the Cloudflare check. Try again in a moment."
        private const val WEBVIEW_LOAD_MESSAGE =
            "Comix could not load the page in WebView. Open it in the browser and try again."
        private const val WEBVIEW_CACHE_BUSTER_PARAM = "_kotatsu_retry"
        private const val WEBVIEW_NAVIGATION_ATTEMPTS = 2

        private const val CAUSE_CHAIN_LIMIT = 8
        private const val MAIN_MODULE_SELECTOR = "script[type=module][src*=/dist/main-]"
        private const val MAIN_MODULE_SELECTOR_JS = "'script[type=module][src*=\"/dist/main-\"]'"

        // 100ms apart; only ever waits for the served HTML to be parsed.
        private const val BUNDLE_WAIT_TICKS = 200
        private const val WEBVIEW_PAGE_ATTEMPTS = 3
        private const val WEBVIEW_PAGE_TIMEOUT = 20000L

        // Concurrent chapter-list requests once the page count is known.
        private const val CHAPTER_API_CONCURRENCY = 6

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
        """

        // Collects the whole chapter list.
        //
        // Runs against the title page with the site's own module stripped out, so
        // nothing renders and nothing is clicked. It pulls the signing bundle that
        // module referenced, takes the api object out of it and asks the chapter
        // endpoint directly, a hundred at a time.
        //
        // The result crosses back as a URL fragment, so it is emitted in a compact
        // form — the shared URL prefix and the scanlation groups are sent once and
        // referenced by index, and absent fields are omitted:
        //   prefix  shared start of every chapter URL
        //   groups  [{ id?, name?, o }] — o = 1 when the group's release is official
        //   items   [{ i: id, n: number, u: url suffix, g: group index,
        //              v: volume?, t: name?, c: epoch seconds?, d: relative date? }]
        private fun chapterScript(mangaId: String, knownEnvUrl: String) = """
            (async () => {
$CLOUDFLARE_DETECT_JS
                if (isWebViewLoadError()) return '$WEBVIEW_LOAD_FAILED';
                if (isCloudflareChallenge()) return '$CLOUDFLARE_BLOCKED';

                const MANGA_ID = $mangaId;
                // Reused from an earlier load when we have it: resolving it means
                // downloading the whole main bundle to read one filename out of it.
                const KNOWN_ENV_URL = $knownEnvUrl;
                // Kotatsu refreshes the whole list, so there is no known chapter to
                // stop at; the field is kept so the loop matches the site's own.
                const LATEST_CHAPTER_ID = null;

                const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

                try {
                    let envUrl = KNOWN_ENV_URL;
                    if (!envUrl) {
                        // The module tag is in the served HTML, so this only ever waits
                        // for the document to be parsed.
                        let mainScript = null;
                        for (let i = 0; i < $BUNDLE_WAIT_TICKS; i++) {
                            if (isWebViewLoadError()) return '$WEBVIEW_LOAD_FAILED';
                            if (isCloudflareChallenge()) return '$CLOUDFLARE_BLOCKED';
                            mainScript = document.querySelector($MAIN_MODULE_SELECTOR_JS);
                            if (mainScript && mainScript.src) break;
                            await sleep(100);
                        }
                        if (!mainScript || !mainScript.src) return null;

                        const mainResponse = await fetch(mainScript.src);
                        if (!mainResponse.ok) throw new Error('Could not load main bundle');
                        const mainJavaScript = await mainResponse.text();
                        const environmentFile = mainJavaScript.match(
                            /from\s*["']\.\/(env-[^"']+\.js)["']/
                        );
                        if (!environmentFile) throw new Error('Could not find environment bundle');
                        envUrl = new URL(environmentFile[1], mainScript.src).href;
                    }

                    // A bare import() in an injected script is parsed in the wrong
                    // context; going through Function keeps it a real dynamic import.
                    const importBundle = new Function('url', 'return import(url)');
                    const environment = await importBundle(envUrl);
                    const mangaApi = Object.values(environment).find((value) =>
                        value &&
                        typeof value === 'object' &&
                        typeof value.chapters === 'function'
                    );
                    if (!mangaApi) throw new Error('Could not find manga API');

                    const askFor = (page) => mangaApi.chapters(MANGA_ID, {
                        page: page,
                        limit: 100,
                        order: { number: 'desc' }
                    });
                    const rowsOf = (response) => response && (response.items ||
                        (response.result && response.result.items));

                    const collected = [];
                    const first = await askFor(1);
                    const firstItems = rowsOf(first);
                    if (!Array.isArray(firstItems)) throw new Error('Unexpected chapter response');
                    collected.push(...firstItems);

                    // The first response says how many pages there are, so the rest do
                    // not have to be discovered one at a time: they go out together and
                    // the list arrives in two round trips instead of one per hundred.
                    const meta = first.meta || first.pagination || {};
                    const lastPage = Math.min(
                        meta.lastPage || meta.last_page || 1,
                        $MAX_CHAPTER_API_PAGES
                    );
                    const reachedKnown = firstItems.some((item) => item.id === LATEST_CHAPTER_ID);

                    if (!reachedKnown && firstItems.length > 0 && lastPage > 1) {
                        let nextPage = 2;
                        const worker = async () => {
                            for (;;) {
                                const page = nextPage++;
                                if (page > lastPage) return;
                                const items = rowsOf(await askFor(page));
                                if (Array.isArray(items)) collected.push(...items);
                            }
                        };
                        await Promise.all(
                            Array.from(
                                { length: Math.min($CHAPTER_API_CONCURRENCY, lastPage - 1) },
                                worker
                            )
                        );
                    } else if (!reachedKnown && meta.hasNext) {
                        // No page count to fan out over, so walk it the slow way.
                        let page = 2;
                        while (page <= $MAX_CHAPTER_API_PAGES) {
                            const response = await askFor(page);
                            const items = rowsOf(response);
                            if (!Array.isArray(items) || items.length === 0) break;
                            collected.push(...items);
                            if (items.some((item) => item.id === LATEST_CHAPTER_ID)) break;
                            const pageMeta = response.meta || response.pagination || {};
                            if (!pageMeta.hasNext) break;
                            page++;
                        }
                    }

                    // --- Compact the result for the fragment-URL trip back. ---
                    let prefix = collected.length ? String(collected[0].url || '') : '';
                    for (const chapter of collected) {
                        const url = String(chapter.url || '');
                        let i = 0;
                        while (i < prefix.length && i < url.length && prefix[i] === url[i]) i++;
                        prefix = prefix.slice(0, i);
                    }

                    const groups = [];
                    const groupIndex = new Map();
                    const items = collected.map((chapter) => {
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
                            n: typeof chapter.number === 'number'
                                ? chapter.number
                                : Number(chapter.number) || 0,
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
                        empty: items.length === 0,
                        env: envUrl
                    });
                } catch (error) {
                    return JSON.stringify({
                        error: String((error && error.message) || error)
                    });
                }
            })()
        """

        // Browse results arrive via a signed, encrypted XHR the page decrypts in
        // JS, so we hook `JSON.parse` (catches the decrypted object), `fetch` and
        // `XMLHttpRequest` (catch plain responses), plus poll `script#initial-data`
        // as a backstop. Resolves with the first `{ result: { items: [...] } }`
        // payload as a compact JSON string for the bridge to hand back. Browse
        // responses include many fields the listing never consumes, especially
        // dozens of translated alternate titles per item; carrying those through
        // a WebView navigation and then into Manga objects causes avoidable delay.
        private const val BROWSE_CAPTURE_SCRIPT = """
            (async () => {
                const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
$CLOUDFLARE_DETECT_JS
                if (isWebViewLoadError()) return '$WEBVIEW_LOAD_FAILED';
                if (isCloudflareChallenge()) return '$CLOUDFLARE_BLOCKED';
                const original = JSON.parse;
                let captured = null;
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
                const take = (obj) => {
                    if (captured) return true;
                    try {
                        const items = obj && obj.result && obj.result.items;
                        if (Array.isArray(items) && items.length > 0) {
                            captured = JSON.stringify({ result: { items: items.map(compactItem) } });
                            return true;
                        }
                    } catch (e) {}
                    return false;
                };
                JSON.parse = function () {
                    const parsed = original.apply(this, arguments);
                    take(parsed);
                    return parsed;
                };
                if (typeof window.fetch === 'function') {
                    const originalFetch = window.fetch;
                    window.fetch = function () {
                        return originalFetch.apply(this, arguments).then((response) => {
                            try {
                                response.clone().text().then((text) => {
                                    try { take(original(text)); } catch (e) {}
                                }).catch(() => {});
                            } catch (e) {}
                            return response;
                        });
                    };
                }
                const originalSend = XMLHttpRequest.prototype.send;
                XMLHttpRequest.prototype.send = function () {
                    this.addEventListener('load', function () {
                        try { take(original(this.responseText)); } catch (e) {}
                    });
                    return originalSend.apply(this, arguments);
                };
                for (let i = 0; i < 300; i++) {
                    if (captured) return captured;
                    if (isWebViewLoadError()) return '$WEBVIEW_LOAD_FAILED';
                    if (isCloudflareChallenge()) return '$CLOUDFLARE_BLOCKED';
                    try {
                        const node = document.querySelector('script#initial-data');
                        if (node && node.textContent) {
                            const queries = original(node.textContent).queries;
                            if (queries) {
                                for (const k in queries) {
                                    if (take(queries[k]) || take({ result: queries[k] })) break;
                                }
                            }
                        }
                    } catch (e) {}
                    await sleep(100);
                }
                return JSON.stringify({ error: 'no browse data captured' });
            })()
        """

        // Same capture technique, for the reader page's page list, recognised by
        // a `result.pages` object. Resolves with `{ result: { pages: ... } }`.
        private const val PAGE_CAPTURE_SCRIPT = """
            (async () => {
                const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
$CLOUDFLARE_DETECT_JS
                if (isWebViewLoadError()) return '$WEBVIEW_LOAD_FAILED';
                if (isCloudflareChallenge()) return '$CLOUDFLARE_BLOCKED';
                const original = JSON.parse;
                let captured = null;
                const take = (obj) => {
                    if (captured) return true;
                    try {
                        const result = obj && obj.result ? obj.result : obj;
                        if (result && result.pages) {
                            captured = JSON.stringify({ result: result });
                            return true;
                        }
                    } catch (e) {}
                    return false;
                };
                JSON.parse = function () {
                    const parsed = original.apply(this, arguments);
                    take(parsed);
                    return parsed;
                };
                if (typeof window.fetch === 'function') {
                    const originalFetch = window.fetch;
                    window.fetch = function () {
                        return originalFetch.apply(this, arguments).then((response) => {
                            try {
                                response.clone().text().then((text) => {
                                    try { take(original(text)); } catch (e) {}
                                }).catch(() => {});
                            } catch (e) {}
                            return response;
                        });
                    };
                }
                const originalSend = XMLHttpRequest.prototype.send;
                XMLHttpRequest.prototype.send = function () {
                    this.addEventListener('load', function () {
                        try { take(original(this.responseText)); } catch (e) {}
                    });
                    return originalSend.apply(this, arguments);
                };
                for (let i = 0; i < 300; i++) {
                    if (captured) return captured;
                    if (isWebViewLoadError()) return '$WEBVIEW_LOAD_FAILED';
                    if (isCloudflareChallenge()) return '$CLOUDFLARE_BLOCKED';
                    try {
                        const node = document.querySelector('script#initial-data');
                        if (node && node.textContent) {
                            const queries = original(node.textContent).queries;
                            if (queries) {
                                for (const k in queries) { if (take(queries[k])) break; }
                            }
                        }
                    } catch (e) {}
                    await sleep(100);
                }
                return JSON.stringify({ error: 'no page data captured' });
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
