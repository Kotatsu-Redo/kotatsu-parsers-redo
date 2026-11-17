package org.koitharu.kotatsu.parsers.site.id

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.HttpStatusException
import org.jsoup.Jsoup
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.AbstractMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import java.net.URLDecoder
import java.time.Instant
import java.util.*
import kotlin.math.abs
import kotlin.time.Duration.Companion.seconds

internal abstract class BaseDoujinDesuParser(
    context: MangaLoaderContext,
    source: MangaParserSource,
) : AbstractMangaParser(context, source) {

    protected abstract val defaultTypes: String

    protected abstract val availableContentTypes: Set<ContentType>

    override val configKeyDomain: ConfigKey.Domain
        get() = ConfigKey.Domain("doujin.desu.xxx", "doujindesu.tv", "doujindesu.xxx")

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
        keys.add(ConfigKey.InterceptCloudflare(defaultValue = true))
    }

    @Volatile
    private var genresCache: Set<MangaTag>? = null
    private val genresMutex = Mutex()

    @Volatile
    private var genreCountsCache: Map<String, Int>? = null
    private val genreCountsMutex = Mutex()

    override val defaultSortOrder: SortOrder
        get() = SortOrder.UPDATED

    override val availableSortOrders: Set<SortOrder>
        get() = EnumSet.of(
            SortOrder.UPDATED,
            SortOrder.NEWEST,
            SortOrder.ALPHABETICAL,
            SortOrder.POPULARITY,
            SortOrder.NEWEST_ASC,
        )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isMultipleTagsSupported = true,
            isTagsExclusionSupported = true,
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
            isAuthorSearchSupported = true,
        )

    override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions(
        availableTags = getOrFetchGenres(),
        availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED, MangaState.PAUSED),
        availableContentTypes = availableContentTypes,
    )

    private suspend fun getOrFetchGenres(): Set<MangaTag> {
        genresCache?.let { return it }
        return genresMutex.withLock {
            genresCache ?: fetchAvailableTags().also { genresCache = it }
        }
    }

    private suspend fun getOrFetchGenreCounts(): Map<String, Int> {
        genreCountsCache?.let { return it }
        return genreCountsMutex.withLock {
            genreCountsCache ?: fetchGenreCounts().also { genreCountsCache = it }
        }
    }

    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("X-Requested-With", "XMLHttpRequest")
        .add("Referer", "https://$domain/")
        .add("X-App-Secret", "dfdf72051dbfdc7d76889ebd31324e74")
        .build()

    private suspend fun executeWithCloudflareRetry(url: HttpUrl): Response {
        return try {
            webClient.httpGet(url, extraHeaders = getRequestHeaders())
        } catch (e: HttpStatusException) {
            when (e.statusCode) {
                403 -> {
                    refreshCloudflare()
                    webClient.httpGet(url, extraHeaders = getRequestHeaders())
                }

                in 500..599 -> {
                    delay(1.seconds)
                    webClient.httpGet(url, extraHeaders = getRequestHeaders())
                }

                else -> throw e
            }
        }
    }

    private suspend fun executeWithCloudflareRetry(url: String): Response =
        executeWithCloudflareRetry(url.toHttpUrl())

    private suspend fun fetchDecrypted(url: HttpUrl): String {
        val jsonResponse = executeWithCloudflareRetry(url).parseJson()
        val encHex = jsonResponse.getString("_enc_resp_")
        return decrypt(encHex)
    }

    private suspend fun fetchDecrypted(url: String): String = fetchDecrypted(url.toHttpUrl())

    private suspend fun refreshCloudflare() {
        runCatchingCancellable {
            webClient.httpGet("https://$domain/", extraHeaders = getRequestHeaders()).close()
        }
    }

    override suspend fun resolveLink(resolver: LinkResolver, link: HttpUrl): Manga? {
        val firstSegment = link.pathSegments.firstOrNull() ?: return null
        val slug = when (firstSegment) {
            "manga" -> link.pathSegments.getOrNull(1)?.takeIf { it.isNotEmpty() }
            "reader" -> {
                val chId = link.pathSegments.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: return null
                val chDec = runCatchingCancellable {
                    fetchDecrypted("/api/chapters/$chId".toAbsoluteUrl(domain))
                }.getOrNull() ?: return null
                val chObj = chDec.toJSONObjectOrNull() ?: return null
                chObj.getStringOrNull("manga_slug")
            }

            else -> null
        } ?: return null

        val decrypted = runCatchingCancellable {
            fetchDecrypted("/api/manga/$slug".toAbsoluteUrl(domain))
        }.getOrNull() ?: return null
        val obj = decrypted.toJSONObjectOrNull() ?: return null
        val contentType = parseContentType(obj.optString("type"))
        if (contentType != null && contentType !in availableContentTypes) {
            return null
        }
        val title = obj.getStringOrNull("title") ?: "Unknown manga"
        val mangaUrl = "/manga/$slug"
        return resolver.resolveManga(
            this,
            url = mangaUrl,
            id = generateUid(mangaUrl),
            title = title,
        )
    }

    override suspend fun getList(offset: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val allowedTypeNames = resolveTypeNames(filter.types) ?: return emptyList()
        if (!filter.author.isNullOrBlank()) {
            return getListByAuthor(offset, order, filter, allowedTypeNames)
        }

        val requiredTags = filter.tags.mapToSet { it.key }
        val excludedTags = filter.tagsExclude.mapToSet { it.key }
        val needsClientTagFilter = requiredTags.size > 1 || excludedTags.isNotEmpty()

        if (!needsClientTagFilter) {
            val url = buildMangaListUrl(
                offset = offset,
                limit = PAGE_SIZE,
                order = order,
                filter = filter,
                typeNames = allowedTypeNames,
                genreKey = requiredTags.firstOrNull(),
            )
            val array = JSONArray(fetchDecrypted(url))
            return array.mapJSON(::parseMangaListItem)
        }

        // The backend treats comma-separated `genre=a,b` as OR instead of AND, so narrow by the
        // rarest selected tag on the server and intersect `manga_genres` client-side for true AND.
        val anchorTag = if (requiredTags.isNotEmpty()) {
            val counts = getOrFetchGenreCounts()
            requiredTags.minByOrNull { counts[it] ?: Int.MAX_VALUE }
        } else {
            null
        }

        val results = ArrayList<Manga>(PAGE_SIZE)
        var matchedSkipped = 0
        for (batchIndex in 0 until MAX_SCAN_BATCHES) {
            val url = buildMangaListUrl(
                offset = batchIndex * SCAN_BATCH_SIZE,
                limit = SCAN_BATCH_SIZE,
                order = order,
                filter = filter,
                typeNames = allowedTypeNames,
                genreKey = anchorTag,
            )
            val array = JSONArray(fetchDecrypted(url))
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                if (!matchesTagFilters(obj, requiredTags, excludedTags)) {
                    continue
                }
                if (matchedSkipped < offset) {
                    matchedSkipped++
                    continue
                }
                results.add(parseMangaListItem(obj))
                if (results.size >= PAGE_SIZE) {
                    return results
                }
            }
            if (array.length() < SCAN_BATCH_SIZE) {
                break
            }
        }
        return results
    }

    private suspend fun getListByAuthor(
        offset: Int,
        order: SortOrder,
        filter: MangaListFilter,
        allowedTypeNames: List<String>,
    ): List<Manga> {
        val authorSlug = filter.author.orEmpty()
            .trim()
            .lowercase(Locale.ROOT)
            .replace(AUTHOR_SLUG_REGEX, "-")
            .trim('-')
        if (authorSlug.isEmpty()) {
            return emptyList()
        }

        val requiredTags = filter.tags.mapToSet { it.key }
        val excludedTags = filter.tagsExclude.mapToSet { it.key }
        val requiredState = filter.states.oneOrThrowIfMany()
        val query = filter.query?.trim().orEmpty()
        val sortParam = if (order == SortOrder.POPULARITY) "popular" else "latest"

        val results = ArrayList<Manga>(PAGE_SIZE)
        var matchedSkipped = 0
        for (batchIndex in 0 until MAX_SCAN_BATCHES) {
            val page = batchIndex + 1
            val url = urlBuilder()
                .addPathSegments("api/taxonomy/authors")
                .addPathSegment(authorSlug)
                .addQueryParameter("page", page.toString())
                .addQueryParameter("sort", sortParam)
                .addQueryParameter("limit", SCAN_BATCH_SIZE.toString())
                .build()
            val payload = try {
                fetchDecrypted(url)
            } catch (e: HttpStatusException) {
                if (e.statusCode == 404) break else throw e
            }
            val root = JSONObject(payload)
            val array = root.optJSONArray("mangaList") ?: break
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                if (allowedTypeNames.isNotEmpty() && obj.optString("type") !in allowedTypeNames) {
                    continue
                }
                if (requiredState != null && parseState(obj.optString("status")) != requiredState) {
                    continue
                }
                if (query.isNotEmpty() && !obj.optString("title").contains(query, ignoreCase = true)) {
                    continue
                }
                if (!matchesTagFilters(obj, requiredTags, excludedTags)) {
                    continue
                }
                if (matchedSkipped < offset) {
                    matchedSkipped++
                    continue
                }
                results.add(parseMangaListItem(obj))
                if (results.size >= PAGE_SIZE) {
                    return results
                }
            }
            if (array.length() < SCAN_BATCH_SIZE) {
                break
            }
        }
        return results
    }

    /**
     * Returns explicit type names to filter by, an empty list when all default types of this
     * source should be queried, or `null` when the requested types are unsupported by this source.
     */
    private fun resolveTypeNames(requestedTypes: Set<ContentType>): List<String>? {
        if (requestedTypes.isEmpty()) {
            return if (defaultTypes.isEmpty()) emptyList() else defaultTypes.split(',')
        }
        val matched = requestedTypes.filter { it in availableContentTypes }.mapNotNull {
            when (it) {
                ContentType.MANGA -> "manga"
                ContentType.MANHWA -> "manhwa"
                ContentType.DOUJINSHI -> "doujinshi"
                else -> null
            }
        }
        return matched.takeIf { it.isNotEmpty() }
    }

    private fun buildMangaListUrl(
        offset: Int,
        limit: Int,
        order: SortOrder,
        filter: MangaListFilter,
        typeNames: List<String>,
        genreKey: String?,
    ): HttpUrl = urlBuilder().apply {
        addPathSegment("api")
        addPathSegment("manga")
        addQueryParameter("limit", limit.toString())
        addQueryParameter("offset", offset.toString())

        filter.query?.trim()?.takeIf { it.isNotEmpty() }?.let {
            addQueryParameter("search", it)
        }

        if (!genreKey.isNullOrEmpty()) {
            addQueryParameter("genre", genreKey)
        }

        val sortParam = when (order) {
            SortOrder.POPULARITY -> "rating"
            SortOrder.ALPHABETICAL -> "title_asc"
            SortOrder.NEWEST -> "newest"
            SortOrder.NEWEST_ASC -> "oldest"
            SortOrder.UPDATED -> "latest_chapter"
            else -> "latest_chapter"
        }
        addQueryParameter("sort", sortParam)

        if (typeNames.isNotEmpty()) {
            addQueryParameter("type", typeNames.joinToString(","))
        }

        filter.states.oneOrThrowIfMany()?.let {
            val stateParam = when (it) {
                MangaState.ONGOING -> "publishing"
                MangaState.FINISHED -> "completed"
                MangaState.PAUSED -> "hiatus"
                else -> ""
            }
            if (stateParam.isNotEmpty()) {
                addQueryParameter("status", stateParam)
            }
        }
    }.build()

    private fun parseMangaListItem(obj: JSONObject): Manga {
        val slug = obj.getString("slug")
        val href = "/manga/$slug"
        return Manga(
            id = generateUid(href),
            title = obj.getString("title"),
            altTitles = emptySet(),
            url = href,
            publicUrl = href.toAbsoluteUrl(domain),
            rating = obj.getDoubleOrDefault("rating", 0.0).toFloat() / 10f,
            contentRating = ContentRating.ADULT,
            coverUrl = obj.getStringOrNull("cover_url")?.let { cover ->
                if (cover.startsWith("http")) cover else cover.toAbsoluteUrl(domain)
            },
            tags = parseGenres(obj),
            state = parseState(obj.optString("status")),
            authors = emptySet(),
            largeCoverUrl = null,
            description = null,
            source = source,
        )
    }

    private fun matchesTagFilters(
        obj: JSONObject,
        requiredTags: Set<String>,
        excludedTags: Set<String>,
    ): Boolean {
        if (requiredTags.isEmpty() && excludedTags.isEmpty()) {
            return true
        }
        val itemKeys = parseGenreKeys(obj)
        return requiredTags.all { it in itemKeys } && excludedTags.none { it in itemKeys }
    }

    private fun parseGenreKeys(obj: JSONObject): Set<String> {
        val genresArray = obj.optJSONArray("manga_genres") ?: return emptySet()
        val keys = HashSet<String>(genresArray.length())
        for (i in 0 until genresArray.length()) {
            val slug = genresArray.optJSONObject(i)?.optJSONObject("genres")?.getStringOrNull("slug")
            if (slug != null) {
                keys.add(slug)
            }
        }
        return keys
    }

    private fun parseGenres(obj: JSONObject): Set<MangaTag> {
        val genresArray = obj.optJSONArray("manga_genres") ?: return emptySet()
        val tags = LinkedHashSet<MangaTag>(genresArray.length())
        for (i in 0 until genresArray.length()) {
            val genreObj = genresArray.optJSONObject(i)?.optJSONObject("genres") ?: continue
            val slug = genreObj.getStringOrNull("slug") ?: continue
            val name = genreObj.getStringOrNull("name")
                ?.removeSurrounding("\"")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: continue
            tags.add(MangaTag(key = slug, title = name, source = source))
        }
        return tags
    }

    private fun parseContentType(rawType: String): ContentType? = when (rawType) {
        "manga" -> ContentType.MANGA
        "manhwa" -> ContentType.MANHWA
        "doujinshi" -> ContentType.DOUJINSHI
        else -> null
    }

    private fun parseState(rawStatus: String): MangaState? = when (rawStatus) {
        "completed" -> MangaState.FINISHED
        "ongoing", "publishing" -> MangaState.ONGOING
        "hiatus" -> MangaState.PAUSED
        else -> null
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val slug = manga.url.removePrefix("/manga/").removeSuffix("/")
        val url = "/api/manga/$slug".toAbsoluteUrl(domain)
        val obj = JSONObject(fetchDecrypted(url))

        val state = parseState(obj.optString("status"))
        val authors = parseAuthors(obj)
        val tags = parseGenres(obj)

        val chaptersArray = obj.getJSONArray("chapters")
        val chapters = ArrayList<MangaChapter>(chaptersArray.length())
        for (i in 0 until chaptersArray.length()) {
            val chapObj = chaptersArray.getJSONObject(i)
            val chId = chapObj.getString("id")
            val chNum = chapObj.getDoubleOrDefault("chapter_number", 0.0).toFloat()
            val chTitle = chapObj.getStringOrNull("title") ?: if (chNum == chNum.toLong().toFloat()) {
                "Chapter ${chNum.toLong()}"
            } else {
                "Chapter $chNum"
            }
            val chUrl = "/reader/$chId"
            val createdAt = chapObj.optString("created_at")
            val uploadDate = runCatchingCancellable { Instant.parse(createdAt).toEpochMilli() }.getOrDefault(0L)

            chapters.add(
                MangaChapter(
                    id = generateUid(chUrl),
                    title = chTitle,
                    number = chNum,
                    volume = 0,
                    url = chUrl,
                    scanlator = null,
                    uploadDate = uploadDate,
                    branch = null,
                    source = source,
                ),
            )
        }

        val rawDesc = obj.getStringOrNull("description")?.takeIf { it != "null" }
        val cleanDesc = rawDesc?.let { html ->
            val fullText = Jsoup.parseBodyFragment(html).text()
            val cutoffIndex = DOWNLOAD_CUTOFF_REGEX.find(fullText)?.range?.first
            val textBeforeDownload = if (cutoffIndex != null) fullText.substring(0, cutoffIndex) else fullText
            textBeforeDownload
                .replace(SINOPSIS_PREFIX_REGEX, "")
                .replace(URL_REGEX, "")
                .replace(BBCODE_URL_REGEX, "")
                .trim()
        }

        val coverUrl = obj.getStringOrNull("cover_url")?.let { cover ->
            if (cover.startsWith("http")) cover else cover.toAbsoluteUrl(domain)
        }

        val title = obj.getStringOrNull("title") ?: manga.title
        val altTitles = obj.optString("alt_titles")
            .split('|')
            .mapNotNullToSet { it.trim().takeIf(String::isNotEmpty) }

        return manga.copy(
            title = title,
            altTitles = altTitles.ifEmpty { manga.altTitles },
            authors = authors,
            description = cleanDesc,
            state = state,
            rating = obj.getDoubleOrDefault("rating", 0.0).toFloat() / 10f,
            tags = tags,
            coverUrl = coverUrl ?: manga.coverUrl,
            chapters = chapters.sortedBy { it.number },
        )
    }

    private fun parseAuthors(obj: JSONObject): Set<String> {
        val authors = LinkedHashSet<String>()
        obj.getStringOrNull("author")
            ?.takeIf { !it.equals("null", ignoreCase = true) && it != "-" && !it.equals("N/A", ignoreCase = true) }
            ?.let { authors.add(it) }
        val termList = obj.getStringOrNull("term_list") ?: return authors
        for (entry in termList.split('|')) {
            val parts = entry.split(':')
            if (parts.size >= 2 && parts[1] == "author") {
                val name = parts[0].trim()
                if (name.isNotEmpty() && !name.equals("N/A", ignoreCase = true)) {
                    authors.add(name)
                }
            }
        }
        return authors
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val chId = chapter.url.removePrefix("/reader/").removeSuffix("/")
        val url = "/api/chapters/$chId".toAbsoluteUrl(domain)
        val obj = JSONObject(fetchDecrypted(url))

        val pagesList = mutableListOf<MangaPage>()
        val contentUrls = obj.optJSONArray("content_urls")
        if (contentUrls != null && contentUrls.length() > 0) {
            appendPages(contentUrls, pagesList)
        } else {
            val signedUrlsStr = obj.optString("signed_content_urls")
            if (signedUrlsStr.isNotEmpty()) {
                appendPages(JSONArray(signedUrlsStr), pagesList)
            }
        }
        return pagesList
    }

    private fun appendPages(urls: JSONArray, destination: MutableList<MangaPage>) {
        for (i in 0 until urls.length()) {
            val rawPageUrl = urls.getString(i)
            val pageUrl = transformPageUrl(rawPageUrl)
            destination.add(
                MangaPage(
                    id = generateUid(rawPageUrl.substringBefore('?')),
                    url = pageUrl,
                    preview = null,
                    source = source,
                ),
            )
        }
    }

    /**
     * Rewrites legacy unsigned `/uploads/` paths to `/storage/uploads/`, while preserving
     * pre-signed URLs and numbered storage buckets (e.g. `/ch/storage2/uploads/...`) so
     * HMAC signatures are not invalidated.
     */
    private fun transformPageUrl(page: String): String {
        if (page.contains("X-Amz-Signature=", ignoreCase = true) ||
            STORAGE_PATH_REGEX.containsMatchIn(page)
        ) {
            return page
        }
        return when {
            page.contains("/uploads/") -> page.replace("/uploads/", "/storage/uploads/")
            page.contains("/upload/") -> page.replace("/upload/", "/storage/upload/")
            else -> page
        }
    }

    private suspend fun fetchAvailableTags(): Set<MangaTag> {
        val url = "/api/terms?taxonomy=genre".toAbsoluteUrl(domain)
        val array = JSONArray(fetchDecrypted(url))
        val tags = LinkedHashSet<MangaTag>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val name = obj.getStringOrNull("name") ?: continue
            val slug = obj.getStringOrNull("slug") ?: continue
            tags.add(MangaTag(key = slug, title = name, source = source))
        }
        return tags
    }

    private suspend fun fetchGenreCounts(): Map<String, Int> {
        val url = "/api/genres".toAbsoluteUrl(domain)
        val payload = runCatchingCancellable { fetchDecrypted(url) }.getOrNull() ?: return emptyMap()
        val array = payload.toJSONArrayOrNull() ?: return emptyMap()
        val counts = HashMap<String, Int>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val slug = obj.getStringOrNull("slug") ?: continue
            val count = obj.getIntOrDefault("manga_count", Int.MAX_VALUE)
            counts[slug] = count
        }
        return counts
    }

    private fun generateKey(step: Long): String {
        val input = "doujindesu-scrapers-cannot-read-this-super-secret-salt-2026-v2_$step"
        var n = 0
        for (ch in input) {
            n = (n shl 5) - n + ch.code
        }
        var seed = if (n == 0) 123456789L else abs(n.toLong())
        val keyBuilder = StringBuilder()
        repeat(32) {
            seed = (seed * 1664525L + 1013904223L) and 0xFFFFFFFFL
            val charCode = 33 + (seed % 93).toInt()
            keyBuilder.append(charCode.toChar())
        }
        return keyBuilder.toString()
    }

    private fun decrypt(encHex: String): String {
        val timeStep = 3600000L
        val now = System.currentTimeMillis()
        val currentStep = now / timeStep

        var lastError: Exception? = null
        for (offset in intArrayOf(0, -1, 1)) {
            val step = currentStep + offset
            val key = generateKey(step)
            try {
                val encBytes = ByteArray(encHex.length / 2) { i ->
                    encHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                }
                val decBytes = ByteArray(encBytes.size)
                var c = 42
                for (i in encBytes.indices) {
                    val p = encBytes[i].toInt() and 0xFF
                    val f = key[i % key.length].code
                    val k = p xor f xor (i * 13) xor c
                    decBytes[i] = (k and 0xFF).toByte()
                    c = (c + p) % 256
                }
                val decoded = String(decBytes, Charsets.UTF_8)
                return URLDecoder.decode(decoded, "UTF-8")
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: RuntimeException("Decryption failed")
    }

    private companion object {

        const val PAGE_SIZE = 18
        const val SCAN_BATCH_SIZE = 100
        const val MAX_SCAN_BATCHES = 10

        val STORAGE_PATH_REGEX = Regex("""/storage\d*/uploads?/""")
        val AUTHOR_SLUG_REGEX = Regex("""[^a-z0-9]+""")
        val DOWNLOAD_CUTOFF_REGEX = Regex("""download\s+(batch|volume)""", RegexOption.IGNORE_CASE)
        val SINOPSIS_PREFIX_REGEX = Regex("""^Sinopsis:\s*""", RegexOption.IGNORE_CASE)
        val URL_REGEX = Regex("""https?://\S+""")
        val BBCODE_URL_REGEX = Regex("""\[url=.*?]|\[/url]""")
    }
}

@MangaSourceParser("DOUJINDESU", "DoujinDesu", "id")
internal class DoujinDesuParser(context: MangaLoaderContext) :
    BaseDoujinDesuParser(context, MangaParserSource.DOUJINDESU) {

    override val defaultTypes: String = ""

    override val availableContentTypes: Set<ContentType> = EnumSet.of(
        ContentType.MANGA,
        ContentType.MANHWA,
        ContentType.DOUJINSHI,
    )
}
