package org.koitharu.kotatsu.parsers.site.madara.en.theblank

import org.json.JSONObject
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.Broken
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*

import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import java.security.MessageDigest
import java.util.Base64
import okio.Buffer
import java.util.concurrent.ConcurrentHashMap

@Broken
@MangaSourceParser("THEBLANK", "TheBlank", "en", ContentType.HENTAI)
internal class TheBlank(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.THEBLANK, 24) {
    data class ChapterSession(
        val chapterToken: String,
        val clientPubkeyB64: String,
        val sharedSecret: ByteArray,
    )

    private val sessions = ConcurrentHashMap<String, ChapterSession>()

    private fun hmacSha256Hex(key: String, msg: String): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(key.toByteArray(Charsets.US_ASCII), "HmacSHA256"))
        return mac.doFinal(msg.toByteArray(Charsets.US_ASCII)).joinToString("") { "%02x".format(it) }
    }

    private fun hexNonce(byteCount: Int = 16): String {
        val b = ByteArray(byteCount).also { java.security.SecureRandom().nextBytes(it) }
        return b.joinToString("") { "%02x".format(it) }
    }

    private fun sessionKey(serieSlug: String, chapterSlug: String) = "tb-$serieSlug--$chapterSlug"

    override val availableSortOrders: Set<SortOrder> = setOf(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.NEWEST,
        SortOrder.ALPHABETICAL
    )

    override val configKeyDomain = ConfigKey.Domain("theblank.net")

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override fun getRequestHeaders(): okhttp3.Headers {
        return super.getRequestHeaders().newBuilder()
            .add("Referer", "https://$domain/")
            .add("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            .build()
    }

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isMultipleTagsSupported = true,
            isTagsExclusionSupported = true
        )

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        val genres = listOf(
            "BDSM" to "bdsm",
            "Drama" to "drama",
            "Action" to "action",
            "Adventure" to "adventure",
            "Ai" to "ai",
            "Animated" to "animated",
            "Anthology" to "anthology",
            "Boys Love" to "boys-love",
            "Cheating/Infidelity" to "cheatinginfidelity",
            "Cohabitation" to "cohabitation",
            "College" to "college",
            "Comedy" to "comedy",
            "Doujinshi" to "doujinshi",
            "Elf" to "elf",
            "Fantasy" to "fantasy",
            "Folklore" to "folklore",
            "Historical" to "historical",
            "Josei" to "josei",
            "Milf" to "milf",
            "Mature" to "mature",
            "Isekai" to "isekai",
            "Harem" to "harem",
            "Hardcore" to "hardcore",
            "Incest" to "incest",
            "Martial Arts" to "martial-arts",
            "Guideverse" to "guideverse",
            "Horror" to "horror",
            "Love Triangle" to "love-triangle",
            "Mother" to "mother",
            "Mother and daughter" to "mother-and-daughter",
            "Murim" to "murim",
            "Mystery" to "mystery",
            "Omegaverse" to "omegaverse",
            "Office Workers" to "office-workers",
            "Ntr" to "ntr",
            "Noir" to "noir",
            "Psychological" to "psychological",
            "Revenge" to "revenge",
            "Robots" to "robots",
            "Romance" to "romance",
            "Shoujo" to "shoujo",
            "Superpower" to "superpower",
            "Thriller" to "thriller",
            "Smut" to "smut",
            "Seinen" to "seinen",
            "Slice of life" to "slice-of-life",
            "Teacher" to "teacher",
            "Supernatural" to "supernatural",
            "Workplace" to "workplace",
            "Sci-Fi" to "sci-fi",
            "Sisters" to "sisters",
            "Stepmother" to "stepmother",
            "System" to "system",
            "Violence" to "violence",
            "School Life" to "school-life",
            "Shounen" to "shounen",
            "Sports" to "sports",
            "Swapping" to "swapping",
            "Uncensored" to "uncensored"
        )
        val tags = genres.map { (title, key) -> MangaTag(title = title, key = key, source = source) }.toSet()
        
        return MangaListFilterOptions(
            availableTags = tags,
            availableStates = java.util.EnumSet.of(
                MangaState.ONGOING,
                MangaState.FINISHED,
                MangaState.ABANDONED,
                MangaState.PAUSED,
                MangaState.UPCOMING
            )
        )
    }

    private fun Document.getInertiaData(): JSONObject {
        val dataPage = this.selectFirst("div#app")?.attr("data-page") ?: "{}"
        return try {
            org.json.JSONObject(dataPage).optJSONObject("props") ?: org.json.JSONObject()
        } catch (e: Exception) {
            org.json.JSONObject()
        }
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val mangas = mutableListOf<Manga>()
        
        if (!filter.query.isNullOrEmpty()) {
            val searchUrl = "https://$domain/api/v1/search/series?q=${filter.query!!.urlEncoded()}"
            val responseBody = webClient.httpGet(searchUrl).body?.string() ?: "[]"
            val jsonArray = try { org.json.JSONArray(responseBody) } catch (e: Exception) { org.json.JSONArray() }
            
            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.getJSONObject(i)
                val slug = item.optString("slug", "")
                if (slug.isEmpty()) continue
                
                val href = "/serie/$slug"
                val img = item.optString("cover_image", "")
                mangas.add(Manga(
                    id = generateUid(href),
                    title = item.optString("title", item.optString("name", "Unknown")),
                    altTitles = emptySet(),
                    url = href,
                    publicUrl = "https://$domain$href",
                    rating = RATING_UNKNOWN,
                    contentRating = null,
                    coverUrl = if (img.startsWith("http")) img else "https://$domain$img",
                    tags = emptySet(),
                    state = null,
                    authors = emptySet(),
                    source = source
                ))
            }
            return mangas.distinctBy { it.url }
        }

        val url = buildString {
            append("https://")
            append(domain)
            append("/library?page=")
            append(page)
            if (filter.tags.isNotEmpty()) {
                append("&include_genres=")
                val tagsStr = filter.tags.joinToString(",") { it.key }
                append(tagsStr.urlEncoded())
            }
            if (filter.tagsExclude.isNotEmpty()) {
                append("&exclude_genres=")
                val tagsExcludeStr = filter.tagsExclude.joinToString(",") { it.key }
                append(tagsExcludeStr.urlEncoded())
            }
            if (filter.states.isNotEmpty()) {
                append("&status=")
                val statusStr = filter.states.mapNotNull {
                    when (it) {
                        MangaState.ONGOING -> "ongoing"
                        MangaState.FINISHED -> "finished"
                        MangaState.ABANDONED -> "dropped"
                        MangaState.PAUSED -> "onhold"
                        MangaState.UPCOMING -> "upcoming"
                        else -> null
                    }
                }.joinToString(",")
                append(statusStr.urlEncoded())
            }
            val orderParam = when (order) {
                SortOrder.UPDATED -> "recently"
                SortOrder.POPULARITY -> "views"
                SortOrder.NEWEST -> "date"
                SortOrder.ALPHABETICAL -> "alphabetical"
                else -> "recently"
            }
            append("&orderby=")
            append(orderParam)
        }
        val doc = webClient.httpGet(url.toString()).parseHtml()
        val props = doc.getInertiaData()
        
        var dataArray: org.json.JSONArray? = props.optJSONArray("data")
        if (dataArray == null) {
            for (key in props.keys()) {
                val obj = props.optJSONObject(key)
                if (obj != null && obj.has("data") && obj.optJSONArray("data") != null) {
                    dataArray = obj.getJSONArray("data")
                    break
                }
            }
        }
        
        if (dataArray != null) {
            for (i in 0 until dataArray.length()) {
                val item = dataArray.getJSONObject(i)
                val slug = item.optString("slug", "")
                if (slug.isEmpty()) continue
                
                val href = "/serie/$slug"
                val img = item.optString("cover_image", item.optString("image", ""))
                mangas.add(Manga(
                    id = generateUid(href),
                    title = item.optString("name", item.optString("title", "Unknown")),
                    altTitles = emptySet(),
                    url = href,
                    publicUrl = "https://$domain$href",
                    rating = RATING_UNKNOWN,
                    contentRating = null,
                    coverUrl = if (img.startsWith("http")) img else "https://$domain$img",
                    tags = emptySet(),
                    state = null,
                    authors = emptySet(),
                    source = source
                ))
            }
        }
        
        return mangas.distinctBy { it.url }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet("https://$domain${manga.url}").parseHtml()
        val props = doc.getInertiaData()
        
        if (!props.has("serie")) {
            return manga
        }
        val serie = props.getJSONObject("serie")
        
        val genresArray = serie.optJSONArray("genres")
        val tags = mutableSetOf<MangaTag>()
        if (genresArray != null) {
            for (i in 0 until genresArray.length()) {
                val genreObj = genresArray.getJSONObject(i)
                tags.add(MangaTag(
                    title = genreObj.getString("name"),
                    key = genreObj.getString("slug"),
                    source = source
                ))
            }
        }
        
        val chapters = loadChapters(props)
        
        return manga.copy(
            title = serie.optString("name", manga.title),
            altTitles = setOfNotNull(serie.optString("name_alternative").takeIf { it.isNotBlank() }),
            description = serie.optString("description"),
            authors = setOfNotNull(serie.optString("author").takeIf { it.isNotBlank() }),
            state = if (serie.optString("status") == "ongoing") MangaState.ONGOING else MangaState.FINISHED,
            coverUrl = "https://$domain" + serie.optString("cover_image"),
            tags = tags,
            chapters = chapters
        )
    }

    private fun loadChapters(props: JSONObject): List<MangaChapter> {
        val chapters = mutableListOf<MangaChapter>()
        if (!props.has("serie")) return chapters
        
        val serie = props.getJSONObject("serie")
        val chaptersArray = serie.optJSONArray("chapters") ?: return chapters
        
        for (i in 0 until chaptersArray.length()) {
            val chObj = chaptersArray.getJSONObject(i)
            val title = chObj.getString("title")
            val slug = chObj.getString("slug")
            val dateStr = chObj.optString("createdAt", "")
            val url = "/serie/${serie.getString("slug")}/chapter/$slug"
            
            var uploadDate = 0L
            try {
                if (dateStr.isNotEmpty()) {
                    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                    uploadDate = sdf.parse(dateStr)?.time ?: 0L
                }
            } catch (e: Exception) {}
            
            chapters.add(MangaChapter(
                id = generateUid(url),
                title = title,
                number = chObj.optDouble("chapterNumber", 0.0).toFloat(),
                volume = 0,
                url = url,
                scanlator = null,
                uploadDate = uploadDate,
                branch = null,
                source = source
            ))
        }
        
        return chapters
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val chapterUrl = chapter.url.toAbsoluteUrl(domain)
        val html = webClient.httpGet(chapterUrl).parseHtml()
        val dataPage = html.selectFirst("div#app")?.attr("data-page")
            ?: throw Exception("data-page not found (Cloudflare?)")

        val json = JSONObject(dataPage)
        val props = json.getJSONObject("props")

        val serverPubkeyB64 = props.optString("server_pubkey", "").ifEmpty {
            props.optJSONObject("data")?.optString("server_pubkey", "") ?: ""
        }
        val chapterToken = props.optString("chapter_token", "").ifEmpty {
            props.optJSONObject("data")?.optString("chapter_token", "") ?: ""
        }

        if (serverPubkeyB64.isEmpty() || chapterToken.isEmpty()) {
            throw Exception("server_pubkey atau chapter_token tidak ditemukan di JSON")
        }

        val dataObj = props.getJSONObject("data")
        val serieSlug = dataObj.getJSONObject("serie").getString("slug")
        val chapterSlug = dataObj.getString("slug")
        val pageCount = props.optInt("page_count", 0).let {
            if (it > 0) it else dataObj.optInt("page_count", 0)
        }

        if (pageCount == 0) throw Exception("page_count = 0, chapter kosong?")

        // Handshake X25519
        val serverPub = Base64.getDecoder().decode(serverPubkeyB64)
        val priv = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val clientPub = X25519.publicKey(priv)
        val sharedSecret = X25519.scalarMult(priv, serverPub)

        val sessionId = sessionKey(serieSlug, chapterSlug)
        sessions[sessionId] = ChapterSession(
            chapterToken = chapterToken,
            clientPubkeyB64 = Base64.getEncoder().encodeToString(clientPub),
            sharedSecret = sharedSecret
        )

        return (1..pageCount).map { i ->
            val imgUrl = "https://$domain/serie/$serieSlug/chapter/$chapterSlug/page/$i#$sessionId"
            MangaPage(
                id = generateUid("$serieSlug/$chapterSlug/$i"),
                url = imgUrl,
                preview = null,
                source = source
            )
        }

    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.url.encodedPath.contains("/page/")) {
            return chain.proceed(request)
        }

        val sessionId = request.url.fragment
        val session = sessions[sessionId]
        if (session == null) {
            return chain.proceed(request)
        }

        val seg = request.url.pathSegments
        val pageIndex = seg.getOrNull(seg.size - 1)?.toIntOrNull() ?: return chain.proceed(request)

        val ts = (System.currentTimeMillis() / 1000).toString()
        val nonce = hexNonce()
        val sig = hmacSha256Hex(session.chapterToken, "$pageIndex$ts$nonce")

        val newUrl = request.url.newBuilder()
            .addQueryParameter("token", session.chapterToken)
            .addQueryParameter("ts", ts)
            .addQueryParameter("nonce", nonce)
            .addQueryParameter("sig", sig)
            .build()

        val newRequest = request.newBuilder()
            .url(newUrl)
            .header("X-Client-Pubkey", session.clientPubkeyB64)
            .header("Accept", "application/octet-stream")
            .build()

        val response = chain.proceed(newRequest)
        if (!response.isSuccessful) return response

        val pageNameRaw = response.header("X-Page-Name") ?: return response
        val keyHintB64 = response.header("X-Key-Hint") ?: return response
        val keyHint = Base64.getDecoder().decode(keyHintB64)

        val mimeType = when {
            pageNameRaw.endsWith(".webp") -> "image/webp"
            pageNameRaw.endsWith(".png") -> "image/png"
            else -> "image/jpeg"
        }

        val streamKey = run {
            val sha = MessageDigest.getInstance("SHA-256").apply {
                update(session.sharedSecret)
                update(pageNameRaw.toByteArray(Charsets.UTF_8))
            }.digest()
            ByteArray(32) { i -> (sha[i].toInt() xor keyHint[i].toInt()).toByte() }
        }

        val networkSource = response.body?.source() ?: return response
        networkSource.skip(192L) // PREFIX_LENGTH
        val ssHeader = networkSource.readByteArray(24L) // STREAM_HEADER_LENGTH

        val secretStream = SecretStream()
        val state = State().apply {
            secretStream.initPull(this, ssHeader, streamKey)
        }

        val CHUNK_SIZE = 65536 + 17
        val decryptedBuffer = Buffer()
        var isFinished = false

        while (!isFinished) {
            networkSource.request(CHUNK_SIZE.toLong())
            val chunkSize = minOf(CHUNK_SIZE.toLong(), networkSource.buffer.size)
            if (chunkSize == 0L) {
                isFinished = true
                break
            }

            val encryptedData = Buffer().apply { networkSource.read(this, chunkSize) }.readByteArray()
            val pullResult = secretStream.pull(state, encryptedData, encryptedData.size)
            if (pullResult == null) {
                val headHex = ssHeader.joinToString("") { "%02x".format(it) }.take(8)
                val keyHex = streamKey.joinToString("") { "%02x".format(it) }.take(8)
                response.close()
                throw Exception("Decryption pull failed! size=${encryptedData.size}, hd=$headHex, k=$keyHex")
            }

            decryptedBuffer.write(pullResult.message)
            if (pullResult.tag.toInt() == SecretStream.TAG_FINAL) {
                isFinished = true
            }
        }

        return response.newBuilder()
            .body(decryptedBuffer.readByteArray().toResponseBody(mimeType.toMediaTypeOrNull()))
            .build()
    }
}

