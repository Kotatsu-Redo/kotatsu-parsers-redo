package org.koitharu.kotatsu.parsers.site.id

import okhttp3.Headers
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.util.*
import java.util.concurrent.ConcurrentHashMap

@MangaSourceParser("SOFTKOMIK", "Softkomik", "id")
internal class Softkomik(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.SOFTKOMIK, pageSize = 24) {

    override val configKeyDomain = ConfigKey.Domain("softkomik.co")

    private val apiUrl = "https://v2.softdevices.my.id"
    private val coverUrl = "https://cover.softdevices.my.id/softkomik-cover"

    private val cdnUrls = listOf(
        "https://psy1.komik.im",
        "https://image.komik.im/softkomik",
        "https://cdn1.softkomik.online/softkomik",
        "https://cd1.softkomik.online/softkomik",
        "https://f1.softkomik.com/file/softkomik-image",
        "https://img.softdevices.my.id/softkomik-image",
        "https://image.softkomik.com/softkomik",
    )

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.NEWEST,
        SortOrder.POPULARITY,
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isSearchWithFiltersSupported = false,
        isMultipleTagsSupported = false,
    )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = fetchAvailableTags(),
    )

    private val tagsCache = ConcurrentHashMap<String, Set<MangaTag>>()

    private suspend fun fetchAvailableTags(): Set<MangaTag> {
        tagsCache["all"]?.let { return it }
        return runCatching {
            val headers = Headers.Builder()
                .add("Referer", "https://$domain/")
                .build()
            val doc = webClient.httpGet("https://$domain/komik/library", headers).parseHtml()
            val pageProps = extractPageProps(doc) ?: return@runCatching emptySet<MangaTag>()
            val arr = pageProps.optJSONArray("genre") ?: return@runCatching emptySet<MangaTag>()
            val tags = LinkedHashSet<MangaTag>(arr.length())
            for (i in 0 until arr.length()) {
                val jo = arr.optJSONObject(i) ?: continue
                val value = jo.optString("value", "").ifEmpty { jo.optString("label", "") }
                val label = jo.optString("label", "").ifEmpty { value }
                if (value.isNotEmpty() && label.isNotEmpty()) {
                    tags.add(MangaTag(title = label, key = value, source = source))
                }
            }
            tags
        }.getOrDefault(emptySet()).also { tagsCache["all"] = it }
    }

    // ── Session cache ─────────────────────────────────────────────────────────

    private val sessionCache = ConcurrentHashMap<String, SessionDto>()

    private suspend fun getSession(endpoint: String): SessionDto {
        sessionCache[endpoint]?.takeIf { it.ex > System.currentTimeMillis() + 30_000L }?.let { return it }

        val headers = Headers.Builder()
            .add("Accept", "application/json")
            .add("Referer", "https://$domain/")
            .add("Origin", "https://$domain")
            .build()
        val res = webClient.httpGet("https://$domain$endpoint", headers).parseJson()
        // The sign is returned as `<hex>|<suffix>` — the server only uses the hex prefix.
        val rawSign = res.optString("sign", "")
        val session = SessionDto(
            ex = res.optLong("ex", System.currentTimeMillis() + 60_000L),
            token = res.optString("token", ""),
            sign = rawSign.substringBefore('|'),
        )
        sessionCache[endpoint] = session
        return session
    }

    private suspend fun getListSession(): SessionDto = getSession("/api/session/amsnuy")
    private suspend fun getChapterImageSession(): SessionDto = getSession("/api/session/chapter/iuisxs")

    // ── LIST PAGE ─────────────────────────────────────────────────────────────

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        if (!filter.query.isNullOrEmpty()) {
            return searchByQuery(filter.query, page)
        }

        val sortBy = if (order == SortOrder.POPULARITY) "popular" else "newKomik"
        val genre = filter.tags.firstOrNull()?.key
        val url = buildString {
            append("https://").append(domain).append("/komik/library?sortBy=").append(sortBy)
            append("&page=").append(page)
            if (!genre.isNullOrEmpty()) append("&genre=").append(genre.urlEncoded())
        }

        val headers = Headers.Builder()
            .add("Referer", "https://$domain/")
            .build()

        val doc = webClient.httpGet(url, headers).parseHtml()
        val pageProps = extractPageProps(doc) ?: return emptyList()
        val libData = pageProps.optJSONObject("libData") ?: pageProps
        val dataArray = libData.optJSONArray("data") ?: return emptyList()
        return parseListData(dataArray)
    }

    private suspend fun searchByQuery(query: String, page: Int): List<Manga> {
        val session = getListSession()
        val url = "$apiUrl/komik?name=${query.urlEncoded()}&search=true&limit=24&page=$page"
        val headers = Headers.Builder()
            .add("X-Token", session.token)
            .add("X-Sign", session.sign)
            .add("Accept", "application/json")
            .add("Referer", "https://$domain/")
            .add("Origin", "https://$domain")
            .build()
        val json = webClient.httpGet(url, headers).parseJson()
        val dataArray = json.optJSONArray("data") ?: return emptyList()
        return parseListData(dataArray)
    }

    private fun parseListData(dataArray: JSONArray): List<Manga> {
        val result = ArrayList<Manga>(dataArray.length())
        for (i in 0 until dataArray.length()) {
            val jo = dataArray.optJSONObject(i) ?: continue
            val slug = jo.optString("title_slug", "").ifEmpty { jo.optString("id", "") }
            if (slug.isEmpty()) continue
            val gambar = jo.optString("gambar", "").removePrefix("/")
            result.add(
                Manga(
                    id = generateUid(slug),
                    title = jo.optString("title", "Untitled"),
                    altTitles = emptySet(),
                    url = "/$slug",
                    publicUrl = "https://$domain/$slug",
                    rating = RATING_UNKNOWN,
                    contentRating = ContentRating.SAFE,
                    coverUrl = if (gambar.isNotEmpty()) "$coverUrl/$gambar" else "",
                    tags = emptySet(),
                    state = parseStatus(jo.optString("status")),
                    authors = emptySet(),
                    source = source,
                ),
            )
        }
        return result
    }

    // ── DETAILS ───────────────────────────────────────────────────────────────

    override suspend fun getDetails(manga: Manga): Manga {
        val headers = Headers.Builder()
            .add("Referer", "https://$domain/")
            .build()
        val doc = webClient.httpGet(manga.publicUrl, headers).parseHtml()

        val pageProps = extractPageProps(doc)
        val detail = pageProps?.optJSONObject("data")

        val title = detail?.optString("title")?.takeIf { it.isNotBlank() } ?: manga.title
        val gambar = detail?.optString("gambar", "")?.removePrefix("/") ?: ""
        val coverImg = if (gambar.isNotEmpty()) "$coverUrl/$gambar" else manga.coverUrl
        val description = detail?.optString("sinopsis")?.takeIf { it.isNotBlank() }
        val author = detail?.optString("author")?.takeIf { it.isNotBlank() }
        val state = parseStatus(detail?.optString("status"))

        val tags = LinkedHashSet<MangaTag>()
        detail?.optJSONArray("Genre")?.let { arr ->
            for (i in 0 until arr.length()) {
                val name = arr.optString(i, "").trim()
                if (name.isNotEmpty()) tags.add(MangaTag(name, name.lowercase(), source))
            }
        }

        val slug = manga.url.trim('/').substringBefore('/')
        val chapters = fetchChapterList(slug, manga.url)

        return manga.copy(
            title = title,
            coverUrl = coverImg,
            description = description,
            tags = tags,
            authors = setOfNotNull(author),
            state = state,
            chapters = chapters,
        )
    }

    private suspend fun fetchChapterList(slug: String, mangaUrl: String): List<MangaChapter> {
        val session = getListSession()
        val url = "$apiUrl/komik/$slug/chapter?limit=9999999"
        val headers = Headers.Builder()
            .add("X-Token", session.token)
            .add("X-Sign", session.sign)
            .add("Accept", "application/json")
            .add("Referer", "https://$domain/")
            .add("Origin", "https://$domain")
            .build()

        val json = webClient.httpGet(url, headers).parseJson()
        val chapterArray = json.optJSONArray("chapter") ?: return emptyList()

        val chapters = ArrayList<MangaChapter>(chapterArray.length())
        for (i in 0 until chapterArray.length()) {
            val ch = chapterArray.optJSONObject(i) ?: continue
            val chStr = ch.optString("chapter", "")
            if (chStr.isEmpty()) continue
            val number = chStr.substringBefore(".").toFloatOrNull() ?: continue
            val chapterUrl = "/${slug}/chapter/$chStr"
            chapters.add(
                MangaChapter(
                    id = generateUid(chapterUrl),
                    title = "Chapter ${formatChapterDisplay(chStr)}",
                    url = chapterUrl,
                    number = number,
                    volume = 0,
                    scanlator = null,
                    uploadDate = 0L,
                    branch = null,
                    source = source,
                ),
            )
        }
        chapters.sortBy { it.number }
        return chapters
    }

    // ── PAGES ─────────────────────────────────────────────────────────────────

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val headers = Headers.Builder()
            .add("Referer", "https://$domain/")
            .build()
        val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain), headers).parseHtml()

        val pageProps = extractPageProps(doc)
        val data = pageProps?.optJSONObject("data")
        val komik = data?.optJSONObject("komik")
        val chapterData = data?.optJSONObject("data")

        val slug = chapter.url.trim('/').substringBefore("/chapter/")
        val chNum = chapter.url.substringAfterLast("/chapter/").trim('/')

        var imageSrc = chapterData?.optJSONArray("imageSrc") ?: JSONArray()
        val isInter2 = chapterData?.optBoolean("storageInter2", false) ?: false

        if (imageSrc.length() == 0) {
            val id = chapterData?.optString("_id")
                ?: komik?.optString("_id")
                ?: return emptyList()
            imageSrc = fetchChapterImages(slug, chNum, id)
        }

        if (imageSrc.length() == 0) return emptyList()

        val host = if (isInter2) cdnUrls[1] else cdnUrls[0]

        return (0 until imageSrc.length()).mapNotNull { i ->
            val path = imageSrc.optString(i, "").removePrefix("/")
            if (path.isEmpty()) return@mapNotNull null
            MangaPage(
                id = generateUid(path),
                url = "$host/$path",
                preview = null,
                source = source,
            )
        }
    }

    private suspend fun fetchChapterImages(slug: String, chapter: String, id: String): JSONArray {
        return try {
            val session = getChapterImageSession()
            val url = "$apiUrl/komik/$slug/chapter/$chapter/imgs/$id"
            val headers = Headers.Builder()
                .add("X-Token", session.token)
                .add("X-Sign", session.sign)
                .add("Accept", "application/json")
                .add("Referer", "https://$domain/$slug/chapter/$chapter")
                .add("Origin", "https://$domain")
                .build()
            val json = webClient.httpGet(url, headers).parseJson()
            json.optJSONArray("imageSrc") ?: JSONArray()
        } catch (_: Exception) {
            JSONArray()
        }
    }

    // ── HELPERS ───────────────────────────────────────────────────────────────

    private fun parseStatus(text: String?): MangaState? = when {
        text == null -> null
        text.contains("ongoing", ignoreCase = true) -> MangaState.ONGOING
        text.contains("tamat", ignoreCase = true) ||
            text.contains("completed", ignoreCase = true) -> MangaState.FINISHED
        else -> null
    }

    private fun formatChapterDisplay(chStr: String): String {
        val parts = chStr.split(".")
        val numPart = parts[0].toFloatOrNull()
            ?: return chStr
        val formatted = if (numPart == numPart.toLong().toFloat()) {
            numPart.toLong().toString()
        } else {
            numPart.toString().trimEnd('0').trimEnd('.')
        }
        val suffix = parts.drop(1).joinToString(".")
        return if (suffix.isNotEmpty()) "$formatted.$suffix" else formatted
    }

    private fun extractPageProps(doc: org.jsoup.nodes.Document): JSONObject? {
        val raw = doc.selectFirst("script#__NEXT_DATA__")?.data() ?: return null
        return runCatching {
            JSONObject(raw).optJSONObject("props")?.optJSONObject("pageProps")
        }.getOrNull()
    }
}

private data class SessionDto(
    val ex: Long,
    val token: String,
    val sign: String,
)
