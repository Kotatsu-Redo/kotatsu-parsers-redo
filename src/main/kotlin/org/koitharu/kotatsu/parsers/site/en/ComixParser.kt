package org.koitharu.kotatsu.parsers.site.en

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.bitmap.Rect
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.webview.InterceptionConfig
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.*

@MangaSourceParser("COMIX", "Comix", "en", ContentType.MANGA)
internal class Comix(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.COMIX, 28), Interceptor {

    override val configKeyDomain = ConfigKey.Domain("comix.to")

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
        keys.add(ConfigKey.DisableUpdateChecking(defaultValue = true))
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

    private suspend fun fetchAvailableTags(): Set<MangaTag> {
        return setOf(
            // Genres
            MangaTag(key = "6", title = "Action", source = source),
            MangaTag(key = "7", title = "Adventure", source = source),
            MangaTag(key = "8", title = "Boys Love", source = source),
            MangaTag(key = "9", title = "Comedy", source = source),
            MangaTag(key = "10", title = "Crime", source = source),
            MangaTag(key = "11", title = "Drama", source = source),
            MangaTag(key = "12", title = "Fantasy", source = source),
            MangaTag(key = "13", title = "Girls Love", source = source),
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
            MangaTag(key = "26", title = "Sports", source = source),
            MangaTag(key = "27", title = "Superhero", source = source),
            MangaTag(key = "28", title = "Thriller", source = source),
            MangaTag(key = "29", title = "Tragedy", source = source),
            MangaTag(key = "30", title = "Wuxia", source = source),
            // Themes
            MangaTag(key = "31", title = "Aliens", source = source),
            MangaTag(key = "32", title = "Animals", source = source),
            MangaTag(key = "33", title = "Cooking", source = source),
            MangaTag(key = "34", title = "Crossdressing", source = source),
            MangaTag(key = "35", title = "Delinquents", source = source),
            MangaTag(key = "36", title = "Demons", source = source),
            MangaTag(key = "37", title = "Genderswap", source = source),
            MangaTag(key = "38", title = "Ghosts", source = source),
            MangaTag(key = "39", title = "Gyaru", source = source),
            MangaTag(key = "40", title = "Harem", source = source),
            MangaTag(key = "41", title = "Incest", source = source),
            MangaTag(key = "42", title = "Loli", source = source),
            MangaTag(key = "43", title = "Mafia", source = source),
            MangaTag(key = "44", title = "Magic", source = source),
            MangaTag(key = "45", title = "Martial Arts", source = source),
            MangaTag(key = "46", title = "Military", source = source),
            MangaTag(key = "47", title = "Monster Girls", source = source),
            MangaTag(key = "48", title = "Monsters", source = source),
            MangaTag(key = "49", title = "Music", source = source),
            MangaTag(key = "50", title = "Ninja", source = source),
            MangaTag(key = "51", title = "Office Workers", source = source),
            MangaTag(key = "52", title = "Police", source = source),
            MangaTag(key = "53", title = "Post-Apocalyptic", source = source),
            MangaTag(key = "54", title = "Reincarnation", source = source),
            MangaTag(key = "55", title = "Reverse Harem", source = source),
            MangaTag(key = "56", title = "Samurai", source = source),
            MangaTag(key = "57", title = "School Life", source = source),
            MangaTag(key = "58", title = "Shota", source = source),
            MangaTag(key = "59", title = "Supernatural", source = source),
            MangaTag(key = "60", title = "Survival", source = source),
            MangaTag(key = "61", title = "Time Travel", source = source),
            MangaTag(key = "62", title = "Traditional Games", source = source),
            MangaTag(key = "63", title = "Vampires", source = source),
            MangaTag(key = "64", title = "Video Games", source = source),
            MangaTag(key = "65", title = "Villainess", source = source),
            MangaTag(key = "66", title = "Virtual Reality", source = source),
            MangaTag(key = "67", title = "Zombies", source = source),
        )
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = buildString {
            append(apiUrl("manga"))
            append("?")
            var firstParam = true
            fun addParam(param: String) {
                if (firstParam) {
                    append(param)
                    firstParam = false
                } else {
                    append("&").append(param)
                }
            }

            // Search keyword if provided
            if (!filter.query.isNullOrEmpty()) {
                addParam("keyword=${filter.query.urlEncoded()}")
            }

            // Use the provided sort order directly
            when (order) {
                SortOrder.RELEVANCE -> addParam("order[relevance]=desc")
                SortOrder.UPDATED -> addParam("order[chapter_updated_at]=desc")
                SortOrder.POPULARITY -> addParam("order[views_30d]=desc")
                SortOrder.NEWEST -> addParam("order[created_at]=desc")
                SortOrder.ALPHABETICAL -> addParam("order[title]=asc")
                else -> addParam("order[chapter_updated_at]=desc")
            }

            // Handle genre filtering
            if (filter.tags.isNotEmpty()) {
                for (tag in filter.tags) {
                    addParam("genres_in[]=${tag.key}")
                }
            }

            // Default exclude adult content
            addParam("genres_ex[]=87264") // Adult
            addParam("genres_ex[]=87266") // Hentai
            addParam("genres_ex[]=87268") // Smut
            addParam("genres_ex[]=87265") // Ecchi
            addParam("limit=$pageSize")
            addParam("page=$page")
        }

        val response = webClient.httpGet(url).parseJson()
        val result = response.getJSONObject("result")
        val items = result.getJSONArray("items")

        return (0 until items.length()).map { i ->
            val item = items.getJSONObject(i)
            parseMangaFromJson(item)
        }
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
            altTitles = emptySet(),
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
        val hashId = manga.url.substringAfter("/title/")
        val chaptersDeferred = async { getChapters(manga) }

        // Get detailed manga info
        val detailUrl = apiUrl("manga/$hashId")
        val response = webClient.httpGet(detailUrl).parseJson()

        if (response.has("result")) {
            val result = response.getJSONObject("result")
            val updatedManga = parseMangaFromJson(result)

            return@coroutineScope updatedManga.copy(
                chapters = chaptersDeferred.await(),
            )
        }

        return@coroutineScope manga.copy(
            chapters = chaptersDeferred.await(),
        )
    }

    override suspend fun getRelatedManga(seed: Manga): List<Manga> = emptyList()

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val chapterId = chapter.url.substringAfterLast("/").substringBefore("-")
        val response = webViewApiJson("/api/v1/chapters/$chapterId")
        val pagesRoot = response.optJSONObject("result")?.optJSONObject("pages")
        val baseUrl = pagesRoot?.optString("baseUrl").orEmpty().trimEnd('/')
        val pages = pagesRoot?.optJSONArray("items")
            ?: response.optJSONObject("result")?.optJSONArray("pages")
            ?: JSONArray()

        return (0 until pages.length()).map { i ->
            val rawUrl = when (val item = pages.get(i)) {
                is JSONObject -> item.getString("url")
                else -> item.toString()
            }
            val imageUrl = if (rawUrl.startsWith("http", ignoreCase = true) || baseUrl.isBlank()) {
                rawUrl
            } else {
                "$baseUrl/${rawUrl.trimStart('/')}"
            }
            MangaPage(
                id = generateUid("$chapterId-$i"),
                url = imageUrl,
                preview = null,
                source = source,
            )
        }
    }

    private suspend fun getChapters(manga: Manga): List<MangaChapter> {
        val hashId = manga.url.substringAfter("/title/")
        val allChapters = webViewChapterList(hashId)
        val allChapterObjects = (0 until allChapters.length()).map { allChapters.getJSONObject(it) }
        val chaptersBuilder = ChaptersListBuilder(allChapterObjects.size)
        for (chapterData in allChapterObjects) {
            val chapterId = chapterData.getLong("id")
            val number = chapterData.getDouble("number").toFloat()
            val name = chapterData.optString("name", "").nullIfEmpty()
            val scanlationGroup = chapterData.optJSONObject("group") ?: chapterData.optJSONObject("scanlation_group")
            val scanlator = scanlationGroup?.optString("name", null)
                ?: if (chapterData.optBoolean("isOfficial")) "Official" else "Unknown"
            val title = if (name != null) {
                "Chapter $number: $name"
            } else {
                "Chapter $number"
            }
            chaptersBuilder.add(
                MangaChapter(
                    id = generateUid("$scanlator-$chapterId"),
                    title = title,
                    number = number,
                    volume = 0,
                    url = "/title/$hashId/$chapterId-chapter-${number.toChapterUrlPart()}",
                    uploadDate = parseRelativeDate(chapterData.optString("createdAtFormatted")),
                    source = source,
                    scanlator = scanlator,
                    branch = scanlator,
                ),
            )
        }

        return chaptersBuilder.toList().reversed()
    }

    private fun apiUrl(path: String): String = "https://$domain/api/v1/${path.removePrefix("/")}"

    private suspend fun webViewApiJson(apiPath: String): JSONObject {
        return evaluateWebViewJson(
            label = apiPath,
            script = buildWebViewApiScript("return JSON.stringify(await fetchProtected(${apiPath.toJsString()}));"),
        )
    }

    private suspend fun webViewChapterList(hashId: String): JSONArray {
        val pathPrefix = "/api/v1/manga/$hashId/chapters?page="
        val json = evaluateWebViewJson(
            label = "chapters:$hashId",
            script = buildWebViewApiScript(
                """
                    const all = [];
                    const compact = (item) => ({
                        id: item.id,
                        number: item.number,
                        name: item.name || "",
                        createdAtFormatted: item.createdAtFormatted || "",
                        isOfficial: !!item.isOfficial,
                        group: item.group || item.scanlation_group || null
                    });
                    const mostActiveGroupId = (items) => {
                        const counts = new Map();
                        for (const item of items) {
                            const group = item.group || item.scanlation_group;
                            const id = group && group.id;
                            if (id === undefined || id === null) continue;
                            counts.set(String(id), (counts.get(String(id)) || 0) + 1);
                        }
                        let best = "";
                        let bestCount = 0;
                        for (const [id, count] of counts) {
                            if (count > bestCount) {
                                best = id;
                                bestCount = count;
                            }
                        }
                        return best;
                    };
                    const pagePath = (page, groupId) =>
                        ${pathPrefix.toJsString()} + page +
                            "&limit=$CHAPTER_API_LIMIT&order%5Bnumber%5D=desc" +
                            (groupId ? "&group_id=" + encodeURIComponent(groupId) : "");

                    const appendItems = (items) => {
                        for (const item of items) all.push(compact(item));
                    };
                    const pageInfo = (result, fallbackPage) => {
                        const pagination = (result && (result.pagination || result.meta)) || {};
                        return {
                            current: Number(pagination.page || pagination.current_page || fallbackPage),
                            last: Number(pagination.lastPage || pagination.last_page || 1)
                        };
                    };

                    const firstRoot = await fetchProtected(pagePath(1, ""));
                    const firstResult = firstRoot && firstRoot.result ? firstRoot.result : firstRoot;
                    if (!firstResult || !Array.isArray(firstResult.items)) {
                        const keys = firstResult && typeof firstResult === "object" ? Object.keys(firstResult).join(",") : typeof firstResult;
                        throw new Error("chapter payload has no items; keys=" + keys);
                    }
                    const firstItems = firstResult.items;
                    if (!firstItems.length) {
                        return JSON.stringify({ items: [], debug: { pages: 1, count: 0, groupId: "", firstPageCount: 0 } });
                    }

                    const groupId = mostActiveGroupId(firstItems);
                    const firstPagination = pageInfo(firstResult, 1);
                    let page = 1;
                    if (!groupId) {
                        appendItems(firstItems);
                        page = firstPagination.current >= firstPagination.last ? $MAX_CHAPTER_API_PAGES + 1 : 2;
                    }
                    while (page <= $MAX_CHAPTER_API_PAGES) {
                        const root = await fetchProtected(pagePath(page, groupId));
                        const result = root && root.result ? root.result : root;
                        if (!result || !Array.isArray(result.items)) {
                            const keys = result && typeof result === "object" ? Object.keys(result).join(",") : typeof result;
                            throw new Error("chapter payload has no items; keys=" + keys);
                        }
                        const items = result.items;
                        appendItems(items);
                        const pagination = pageInfo(result, page);
                        if (!items.length || pagination.current >= pagination.last) break;
                        page++;
                    }
                    return JSON.stringify({ items: all, debug: { pages: page, count: all.length, groupId, firstPageCount: firstItems.length } });
                """.trimIndent(),
            ),
        )
        val items = json.optJSONArray("items") ?: JSONArray()
        return items
    }

    private suspend fun evaluateWebViewJson(label: String, script: String): JSONObject {
        val bridgeScript = buildBridgeScript(script)
        val startedAt = System.currentTimeMillis()
        val bridgeUrl = "https://$domain/?kotatsu_comix_bridge=$startedAt"
        val requests = runCatching {
            context.interceptWebViewRequests(
                bridgeUrl,
                InterceptionConfig(
                    timeoutMs = WEBVIEW_API_TIMEOUT,
                    maxRequests = 1,
                    urlPattern = INTERCEPT_URL_REGEX,
                    pageScript = bridgeScript,
                ),
            )
        }.getOrElse { e ->
            throw ParseException("Comix WebView API interception failed", bridgeUrl, e)
        }
        val resultUrl = requests.firstOrNull()?.url
            ?: throw ParseException("Comix WebView API did not return a bridge result", bridgeUrl)
        val decoded = when {
            resultUrl.contains("/error", ignoreCase = true) -> {
                val message = resultUrl.queryParameterValue("msg") ?: "unknown WebView error"
                throw ParseException("Comix WebView API failed: $message", bridgeUrl)
            }
            else -> resultUrl.queryParameterValue("data")
                ?: throw ParseException("Comix WebView API bridge result missing data", bridgeUrl)
        }
        if (decoded == CLOUDFLARE_BLOCKED || isCloudflarePage(decoded)) {
            requestCloudflareVerification(bridgeUrl)
        }
        if (decoded.isBlank()) {
            throw ParseException("Comix WebView API returned an empty response", bridgeUrl)
        }
        val json = runCatching { JSONObject(decoded) }.getOrElse { e ->
            throw ParseException("Comix WebView API returned invalid JSON: ${decoded.take(200)}", bridgeUrl, e)
        }
        json.optString("error").nullIfEmpty()?.let { error ->
            throw ParseException("Comix WebView API failed: $error", bridgeUrl)
        }
        return json
    }

    private fun buildBridgeScript(script: String): String {
        return """
            (async function() {
                try {
                    const result = await $script;
                    window.location.href = "$INTERCEPT_RESULT_URL#data=" + encodeURIComponent(String(result || ""));
                } catch (e) {
                    window.location.href = "$INTERCEPT_ERROR_URL#msg=" + encodeURIComponent(String((e && e.message) || e));
                }
            })();
        """.trimIndent()
    }

    private fun requestCloudflareVerification(url: String, cause: Throwable? = null): Nothing {
        try {
            context.requestBrowserAction(this, url)
        } catch (e: UnsupportedOperationException) {
            throw ParseException(CLOUDFLARE_MESSAGE, url, cause ?: e)
        }
    }

    private fun buildWebViewApiScript(body: String): String {
        return """
            (async () => {
                const probePath = "/manga/g2rk/chapters";
                const tokenRegex = /^[A-Za-z0-9_-]{20,200}$/;
                const sleep = (ms) => new Promise(resolve => setTimeout(resolve, ms));
                const challengeDetected = () => {
                    const root = document.documentElement;
                    const html = (root && root.outerHTML) || "";
                    const text = ((document.body && document.body.innerText) || (root && root.innerText) || "");
                    const lower = (document.title + "\n" + text + "\n" + html).toLowerCase();
                    return document.querySelector('script[src*="challenge-platform"]') !== null ||
                        document.querySelector('script[src*="turnstile"]') !== null ||
                        document.querySelector('iframe[src*="challenges.cloudflare.com"]') !== null ||
                        document.querySelector('.cf-turnstile') !== null ||
                        document.querySelector('form[action*="__cf_chl"]') !== null ||
                        document.querySelector('.cf-browser-verification') !== null ||
                        ((lower.includes('just a moment') || lower.includes('checking your browser')) && lower.includes('cloudflare')) ||
                        lower.includes('challenge-platform') ||
                        lower.includes('challenges.cloudflare.com') ||
                        lower.includes('cf-turnstile') ||
                        lower.includes('turnstile') ||
                        lower.includes('cf-chl-opt');
                };
                const findGlue = () => {
                    let signer = null;
                    let installer = null;
                    let responseHandler = null;
                    const keys = Object.keys(window);
                    for (let i = 0; i < keys.length; i++) {
                        const topName = keys[i];
                        if (!/^vm[A-Za-z]_\w+${'$'}/.test(topName)) continue;
                        const ns = window[topName];
                        if (!ns || typeof ns !== "object") continue;
                        const fnames = Object.keys(ns);
                        for (let j = 0; j < fnames.length; j++) {
                            const fn = ns[fnames[j]];
                            if (typeof fn !== "function") continue;
                            if (!signer) {
                                try {
                                    const out = fn(probePath);
                                    if (typeof out === "string" && out !== probePath && tokenRegex.test(out)) {
                                        signer = fn;
                                    }
                                } catch (e) {}
                            }
                            if (!installer) {
                                try {
                                    let got = false;
                                    let resFn = null;
                                    const fakeAxios = {
                                        interceptors: {
                                            request: { use: function() {} },
                                            response: { use: function(fn) { got = true; resFn = fn; } }
                                        },
                                        defaults: { headers: { common: {} }, transformRequest: [], transformResponse: [] }
                                    };
                                    fn(fakeAxios);
                                    if (got) {
                                        installer = fn;
                                        responseHandler = resFn;
                                    }
                                } catch (e) {}
                            }
                            if (signer && installer) return { signer, installer, responseHandler };
                        }
                    }
                    return null;
                };

                try {
                    let glue = null;
                    for (let attempt = 0; attempt < 80; attempt++) {
                        if (challengeDetected()) {
                            return "$CLOUDFLARE_BLOCKED";
                        }
                        glue = findGlue();
                        if (glue) break;
                        await sleep(250);
                    }
                    if (!glue) throw new Error("signer/decryptor not detected");

                    const captured = { res: glue.responseHandler || null };
                    if (!captured.res) {
                        const fakeAxios = {
                            interceptors: {
                                request: { use: function() {} },
                                response: { use: function(fn) { captured.res = fn; } }
                            },
                            defaults: { headers: { common: {} }, transformRequest: [], transformResponse: [] }
                        };
                        glue.installer(fakeAxios);
                    }

                    const signCandidates = (apiPath) => {
                        const withoutApi = apiPath.replace(/^\/api\/v1/, "");
                        const withoutQuery = withoutApi.split("?")[0];
                        const decoded = (() => {
                            try { return decodeURIComponent(withoutApi); } catch (e) { return withoutApi; }
                        })();
                        return [...new Set([withoutApi, decoded, withoutQuery])];
                    };

                    const fetchProtected = async (apiPath) => {
                        const sep = apiPath.indexOf("?") === -1 ? "?" : "&";
                        let resp = null;
                        let text = "";
                        let signedUrl = "";
                        let lastError = "";
                        const candidates = signCandidates(apiPath);
                        for (const signablePath of candidates) {
                            const sig = glue.signer(signablePath);
                            if (!sig) {
                                lastError = "signer returned empty token";
                                continue;
                            }
                            signedUrl = apiPath + sep + "_=" + encodeURIComponent(sig);
                            resp = await fetch(signedUrl, {
                                credentials: "include",
                                headers: { "Accept": "application/json", "X-Requested-With": "XMLHttpRequest" }
                            });
                            text = await resp.text();
                            if (resp.status >= 200 && resp.status < 300) break;
                            lastError = "HTTP " + resp.status + " signed=" + signablePath + ": " + text.slice(0, 200);
                            if (resp.status !== 422) break;
                        }
                        if (!resp) throw new Error(lastError || "request was not sent");
                        if (resp.status < 200 || resp.status >= 300) {
                            throw new Error(lastError || ("HTTP " + resp.status + ": " + text.slice(0, 200)));
                        }
                        const raw = JSON.parse(text);
                        if (raw && typeof raw === "object" && "e" in raw && captured.res) {
                            const fakeResp = {
                                data: raw,
                                status: resp.status,
                                statusText: resp.statusText,
                                headers: Object.fromEntries([...resp.headers.entries()]),
                                config: { url: signedUrl, method: "get", baseURL: "/api/v1" },
                                request: {}
                            };
                            const decoded = await captured.res(fakeResp);
                            return { result: decoded && decoded.data };
                        }
                        if (raw && typeof raw === "object" && "e" in raw) {
                            throw new Error("encrypted response received but decryptor was not captured");
                        }
                        if (raw && typeof raw === "object" && "result" in raw) return raw;
                        return { result: raw };
                    };

                    $body
                } catch (e) {
                    return JSON.stringify({ error: String((e && e.message) || e) });
                }
            })();
        """.trimIndent()
    }

    private fun String.queryParameterValue(name: String): String? {
        val query = substringAfter('#', substringAfter('?', ""))
        if (query.isEmpty()) return null
        return query.split('&')
            .asSequence()
            .map { it.split('=', limit = 2) }
            .firstOrNull { it.size == 2 && it[0] == name }
            ?.get(1)
            ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }
    }

    private fun String.toJsString(): String {
        return "\"" + replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t") + "\""
    }

    private fun String.decodeWebViewString(): String {
        if (length < 2 || first() != '"' || last() != '"') {
            return this
        }
        return substring(1, lastIndex)
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .replace("\\/", "/")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
            .replace(UNICODE_ESCAPE_REGEX) { match ->
                match.groupValues[1].toInt(16).toChar().toString()
            }
    }

    private fun isCloudflarePage(html: String): Boolean {
        if (html.isBlank()) return false
        val lower = html.lowercase(Locale.US)
        return lower.contains("<title>just a moment") ||
            ((lower.contains("just a moment") || lower.contains("checking your browser")) && lower.contains("cloudflare")) ||
            lower.contains("cf-browser-verification") ||
            lower.contains("cf-chl-opt") ||
            lower.contains("challenge-platform") ||
            lower.contains("challenges.cloudflare.com") ||
            lower.contains("cf-turnstile") ||
            lower.contains("turnstile")
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
            MangaTag(
                key = title,
                title = title,
                source = source,
            )
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

    private fun Float.toChapterUrlPart(): String {
        return if (this % 1f == 0f) {
            toInt().toString()
        } else {
            toString().trimEnd('0').trimEnd('.')
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var response = chain.proceed(request)

        if (response.code == 404) {
            val url = request.url.toString()
            val fallbacks = listOf("/si/", "/i/", "/sii/", "/ii/")
                .map { url.replaceFirst(SCRAMBLE_PATH_FALLBACK_REGEX, it) }
                .filter { it != url }

            if (fallbacks.isNotEmpty()) {
                var lastResponse = response
                for (fallbackUrl in fallbacks) {
                    lastResponse.close()
                    lastResponse = chain.proceed(request.newBuilder().url(fallbackUrl).build())
                    if (lastResponse.code != 404) break
                }
                response = lastResponse
            }
        }

        if (!response.isSuccessful) return response

        val seed = response.header("x-scramble-seed")?.toLongOrNull()?.toInt()
            ?: return response

        if (seed == 0) return response

        return context.redrawImageResponse(response) { bitmap ->
            val width = bitmap.width
            val height = bitmap.height
            val tileW = width / GRID_COLS
            val tileH = height / GRID_ROWS

            val perm = buildOrder(seed, NUM_TILES)
            val result = context.createBitmap(width, height)

            result.drawBitmap(bitmap, Rect(0, 0, width, height), Rect(0, 0, width, height))

            for (srcIdx in 0 until NUM_TILES) {
                val dstIdx = perm[srcIdx]
                val srcCol = srcIdx % GRID_COLS
                val srcRow = srcIdx / GRID_COLS
                val dstCol = dstIdx % GRID_COLS
                val dstRow = dstIdx / GRID_COLS

                val srcRect = Rect(
                    srcCol * tileW,
                    srcRow * tileH,
                    (srcCol + 1) * tileW,
                    (srcRow + 1) * tileH,
                )
                val dstRect = Rect(
                    dstCol * tileW,
                    dstRow * tileH,
                    (dstCol + 1) * tileW,
                    (dstRow + 1) * tileH,
                )

                result.drawBitmap(bitmap, srcRect, dstRect)
            }

            result
        }
    }

    private fun buildOrder(seed: Int, n: Int): IntArray {
        val arr = IntArray(n) { it }
        var state = seed
        for (i in n - 1 downTo 1) {
            state = state * LCG_MULTIPLIER + LCG_INCREMENT
            val j = (state.toLong() and 0xFFFFFFFFL) % (i + 1)
            val tmp = arr[i]
            arr[i] = arr[j.toInt()]
            arr[j.toInt()] = tmp
        }
        return arr
    }

    private companion object {
        private val NSFW_RATINGS = setOf("erotica", "pornographic")
        private val TERM_KEYS = arrayOf("genres", "genre", "tags", "theme", "demographics", "demographic", "formats")
        private val RELATIVE_DATE_REGEX = Regex("""^(\d+)\s*(s|m|h|d|w|mo|mos|y|yr|yrs|min|mins|sec|secs|hr|hrs|day|days|week|weeks|month|months|year|years)$""")
        private val UNICODE_ESCAPE_REGEX = Regex("""\\u([0-9A-Fa-f]{4})""")
        private const val WEBVIEW_API_TIMEOUT = 90000L
        private const val CHAPTER_API_LIMIT = 100
        private const val MAX_CHAPTER_API_PAGES = 30
        private const val CLOUDFLARE_BLOCKED = "CLOUDFLARE_BLOCKED"
        private const val INTERCEPT_RESULT_URL = "https://kotatsu.intercept/result"
        private const val INTERCEPT_ERROR_URL = "https://kotatsu.intercept/error"
        private val INTERCEPT_URL_REGEX = Regex("https://kotatsu\\.intercept/.*", RegexOption.IGNORE_CASE)
        private const val CLOUDFLARE_MESSAGE = "Cloudflare verification is required. Open Comix in the in-app browser, complete the check, then try again."

        private const val GRID_COLS = 5
        private const val GRID_ROWS = 5
        private const val NUM_TILES = GRID_COLS * GRID_ROWS
        private const val LCG_MULTIPLIER = 1664525
        private const val LCG_INCREMENT = 1013904223
        private val SCRAMBLE_PATH_FALLBACK_REGEX = Regex("/s?i+/")
    }
}
