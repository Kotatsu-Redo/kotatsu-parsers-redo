package org.koitharu.kotatsu.parsers.site.all

import okhttp3.Interceptor
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.HttpStatusException
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.math.BigInteger
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@MangaSourceParser("KAGANE", "Kagane")
internal class Kagane(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.KAGANE, pageSize = 35) {

    override val configKeyDomain = ConfigKey.Domain("kagane.to")
    private val apiUrl = "https://yuzuki.kagane.to"

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.NEWEST,
        SortOrder.ALPHABETICAL
    )

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(ConfigKey.InterceptCloudflare(defaultValue = true))
    }

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
            isMultipleTagsSupported = true
        )

    private var genresCache: Set<MangaTag>? = null
    private val UUID_REGEX = Regex(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
    )

    private companion object {
        val KAGANE_LANGS = arrayOf(
            "en",
            "ja",
            "ko",
            "zh-Hans",
            "zh-Hant",
            "es",
            "es-419",
            "fr",
            "de",
            "pt",
            "pt-BR",
            "ru",
            "it",
            "id",
            "vi",
            "th",
            "pl",
            "hi",
            "ar",
        )
    }

    // ---- Debug logging (grep logcat for "KAGANE_DBG") ----
    private fun dbg(msg: String) = println("KAGANE_DBG: $msg")

    private fun ByteArray.headHex(n: Int = 16): String =
        take(n).joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }

    private fun signedMessageType(bytes: ByteArray): String {
        // Widevine SignedMessage: field 1 (tag 0x08) = type varint
        if (bytes.size >= 2 && bytes[0].toInt() == 0x08) {
            return when (bytes[1].toInt() and 0xFF) {
                1 -> "LICENSE_REQUEST(1)"
                2 -> "LICENSE(2)"
                3 -> "ERROR_RESPONSE(3)"
                4 -> "SERVICE_CERTIFICATE_REQUEST(4)"
                5 -> "SERVICE_CERTIFICATE(5)"
                else -> "type=${bytes[1].toInt() and 0xFF}"
            }
        }
        return "unknown(${bytes.headHex(4)})"
    }

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        val genres = genresCache ?: fetchGenres().also { genresCache = it }
        return MangaListFilterOptions(
            availableTags = genres,
            availableContentRating = EnumSet.of(
                ContentRating.SAFE,
                ContentRating.SUGGESTIVE,
                ContentRating.ADULT,
            ),
        )
    }

    private suspend fun fetchGenres(): Set<MangaTag> {
        val headers = getRequestHeaders().newBuilder()
            .add("Origin", "https://$domain")
            .add("Referer", "https://$domain/")
            .build()
        return try {
            val raw = webClient.httpGet("$apiUrl/api/v2/genres/list", headers).parseRaw()
            val genres = runCatching { JSONArray(raw) }.getOrElse {
                val wrapper = runCatching { JSONObject(raw) }.getOrNull()
                wrapper?.optJSONArray("content")
                    ?: wrapper?.optJSONArray("genres")
                    ?: JSONArray()
            }
            buildSet {
                for (i in 0 until genres.length()) {
                    val item = genres.optJSONObject(i) ?: continue
                    val id = item.optString("genre_id").ifBlank { item.optString("id") }
                    val title = item.optString("genre_name")
                        .ifBlank { item.optString("genreName") }
                        .ifBlank { item.optString("name") }
                        .ifBlank { item.optString("title") }
                    if (id.isNotBlank() && title.isNotBlank() && UUID_REGEX.matches(id)) {
                        add(MangaTag(title, id, source))
                    }
                }
            }
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun parseContentRating(value: String?): ContentRating? {
        return when (value?.lowercase(Locale.ROOT)) {
            "safe" -> ContentRating.SAFE
            "suggestive" -> ContentRating.SUGGESTIVE
            "adult", "erotica", "pornographic" -> ContentRating.ADULT
            else -> null
        }
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val sortParam = when (order) {
            SortOrder.UPDATED -> "updated_at,desc"
            SortOrder.POPULARITY -> "total_views,desc"
            SortOrder.NEWEST -> "created_at,desc"
            SortOrder.ALPHABETICAL -> "series_name,asc"
            else -> "updated_at,desc"
        }

        val url = "$apiUrl/api/v2/search/series?page=${page - 1}&size=$pageSize&sort=$sortParam"
        val jsonBody = JSONObject()
        if (!filter.query.isNullOrEmpty()) {
            jsonBody.put("title", filter.query)
        }
        jsonBody.put("source_type", JSONArray().apply {
            put("Official")
            put("Unofficial")
            put("Mixed")
        })
        jsonBody.put("content_lang", JSONArray().apply {
            KAGANE_LANGS.forEach(::put)
        })

        val genreIds = filter.tags.map { it.key }.filter { UUID_REGEX.matches(it) }
        if (genreIds.isNotEmpty()) {
            val genresArr = JSONArray()
            genreIds.forEach { genresArr.put(it) }
            val genresObj = JSONObject()
            genresObj.put("values", genresArr)
            genresObj.put("match_all", false)
            jsonBody.put("genres", genresObj)
        }
        if (filter.tagsExclude.isNotEmpty()) {
            val excludedGenreIds = filter.tagsExclude.map { it.key }.filter { UUID_REGEX.matches(it) }
            if (excludedGenreIds.isNotEmpty()) {
                val genresObj = jsonBody.optJSONObject("genres") ?: JSONObject().also {
                    jsonBody.put("genres", it)
                }
                genresObj.put("exclude", JSONArray().apply {
                    excludedGenreIds.forEach(::put)
                })
            }
        }
        jsonBody.put("content_rating", JSONArray().apply {
            val ratings = filter.contentRating.ifEmpty {
                EnumSet.of(ContentRating.SAFE, ContentRating.SUGGESTIVE, ContentRating.ADULT)
            }
            if (ContentRating.SAFE in ratings) put("Safe")
            if (ContentRating.SUGGESTIVE in ratings) put("Suggestive")
            if (ContentRating.ADULT in ratings) {
                put("Erotica")
                put("Pornographic")
            }
        })

        val headers = getRequestHeaders().newBuilder()
            .add("Origin", "https://$domain")
            .add("Referer", "https://$domain/")
            .build()

        val responseBody = try {
            webClient.httpPost(url.toHttpUrl(), jsonBody, headers).parseRaw()
        } catch (e: HttpStatusException) {
            if (e.statusCode == 403) {
                requestCloudflareVerification(url, e)
            } else {
                throw e
            }
        }

        if (responseBody.isCloudflareChallenge()) {
            requestCloudflareVerification(url)
        }

        val response = try {
            JSONObject(responseBody)
        } catch (e: Exception) {
            throw Exception("Invalid JSON search response: $responseBody")
        }

        val content = response.optJSONArray("content")
            ?: response.optJSONObject("result")?.optJSONArray("items")
            ?: return emptyList()

        return (0 until content.length()).mapNotNull { i ->
            val item = content.getJSONObject(i)
            val id = item.optString("id").ifBlank { item.optString("series_id") }
            if (id.isBlank()) return@mapNotNull null
            val name = item.optString("name").ifBlank { item.optString("title") }.ifBlank { return@mapNotNull null }
            val src = item.optString("source").ifBlank { item.optString("source_name") }
            val title = if (src.isNotEmpty()) "$name [$src]" else name
            val coverImageId = item.optString("cover_image_id").ifBlank { item.optString("coverImageId") }
            val coverUrl = if (coverImageId.isNotBlank()) {
                "$apiUrl/api/v2/image/$coverImageId"
            } else {
                "$apiUrl/api/v2/series/$id/thumbnail"
            }

            Manga(
                id = generateUid(id),
                url = id,
                publicUrl = "https://$domain/series/$id",
                coverUrl = coverUrl,
                title = title,
                altTitles = emptySet(),
                rating = RATING_UNKNOWN,
                tags = emptySet(),
                authors = emptySet(),
                state = null,
                source = source,
                contentRating = parseContentRating(item.optString("content_rating"))
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val seriesId = manga.url
        val url = "$apiUrl/api/v2/series/$seriesId"
        val headers = getRequestHeaders().newBuilder()
            .add("Origin", "https://$domain")
            .add("Referer", "https://$domain/")
            .build()
        val resp = webClient.httpGet(url, headers)
        val respBody = resp.body?.string() ?: ""
        if (!resp.isSuccessful) throw Exception("Details error ${resp.code}: $respBody")
        val json = try {
            JSONObject(respBody)
        } catch (e: Exception) {
            throw Exception("Invalid JSON details: $respBody")
        }

        val state = when (
            json.optString("publication_status")
                .ifBlank { json.optString("upload_status") }
                .ifBlank { json.optString("status") }
                .uppercase(Locale.ROOT)
        ) {
            "ONGOING" -> MangaState.ONGOING
            "COMPLETED", "ENDED" -> MangaState.FINISHED
            "HIATUS" -> MangaState.PAUSED
            "ABANDONED", "CANCELLED", "CANCELED", "DROPPED" -> MangaState.ABANDONED
            else -> null
        }

        val genres = json.optJSONArray("genres")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                when (val item = arr.opt(i)) {
                    is String -> {
                        if (UUID_REGEX.matches(item)) {
                            MangaTag(item, item, source)
                        } else {
                            null
                        }
                    }
                    is JSONObject -> {
                        val key = item.optString("genre_id").ifBlank { item.optString("id") }
                        val name = item.optString("genre_name")
                            .ifBlank { item.optString("genreName") }
                            .ifBlank { item.optString("name") }
                            .ifBlank { item.optString("title") }
                        if (key.isNotBlank() && name.isNotBlank()) {
                            MangaTag(name, key, source)
                        } else {
                            null
                        }
                    }
                    else -> null
                }
            }.toSet()
        } ?: emptySet()

        val authors = linkedSetOf<String>()
        json.optJSONArray("authors")?.let { arr ->
            for (i in 0 until arr.length()) {
                when (val item = arr.opt(i)) {
                    is String -> item.takeIf { it.isNotBlank() }?.let(authors::add)
                    is JSONObject -> item.optString("name")
                        .ifBlank { item.optString("title") }
                        .takeIf { it.isNotBlank() }
                        ?.let(authors::add)
                }
            }
        }
        if (authors.isEmpty()) {
            json.optJSONArray("series_staff")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val staff = arr.optJSONObject(i) ?: continue
                    val role = staff.optString("role")
                    if (
                        role.contains("author", ignoreCase = true) ||
                        role.contains("story", ignoreCase = true) ||
                        role.contains("artist", ignoreCase = true) ||
                        role.contains("art", ignoreCase = true)
                    ) {
                        staff.optString("name").takeIf { it.isNotBlank() }?.let(authors::add)
                    }
                }
            }
        }

        val altTitles = json.optJSONArray("series_alternate_titles")?.let { arr ->
            buildSet {
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    item.optString("title").takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        } ?: emptySet()

        val description = buildString {
            json.optString("description")
                .ifBlank { json.optString("summary") }
                .takeIf { it.isNotBlank() }
                ?.let {
                    append(it.trim())
                }
            if (altTitles.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append("Associated Name(s):\n")
                altTitles.forEach {
                    append(it)
                    append('\n')
                }
            }
        }.trim().ifBlank { null }

        val coverUrl = json.optJSONArray("series_covers")
            ?.optJSONObject(0)
            ?.optString("image_id")
            ?.takeIf { it.isNotBlank() }
            ?.let { "$apiUrl/api/v2/image/$it" }
            ?: manga.coverUrl

        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ENGLISH)
        fun parseChapters(content: JSONArray): List<MangaChapter> {
            val chapters = ArrayList<MangaChapter>(content.length())
            for (i in 0 until content.length()) {
                val ch = content.optJSONObject(i) ?: continue
                val chId = ch.optString("book_id")
                    .ifBlank { ch.optString("id") }
                    .ifBlank { ch.optString("bookId") }
                if (chId.isBlank()) continue
                val chapterNo = ch.optString("chapter_no")
                val chapterNumber = chapterNo.toChapterNumberOrNull()
                    ?: ch.optDouble("number", Double.NaN).takeIf { !it.isNaN() }?.toFloat()
                val sortNumber = ch.optDouble("sort_no", Double.NaN).takeIf { !it.isNaN() }?.toFloat()
                    ?: ch.optDouble("number_sort", ch.optDouble("numberSort", Double.NaN)).takeIf { !it.isNaN() }?.toFloat()
                val number = when {
                    sortNumber != null && chapterNumber != null && sortNumber >= chapterNumber -> sortNumber
                    sortNumber != null && chapterNumber == null -> sortNumber
                    chapterNumber != null -> chapterNumber
                    else -> 0f
                }
                val rawTitle = ch.optString("title").ifBlank { ch.optString("name") }.trim()
                val chTitle = rawTitle.ifBlank {
                    chapterNo.takeIf { it.isNotBlank() }?.let { "Ch.$it" }.orEmpty()
                }.ifBlank { "Chapter $number" }
                val volume = ch.optString("volume_no")
                    .ifBlank { ch.optString("volume") }
                    .toIntOrNull() ?: 0
                val dateStr = ch.optString("published_on")
                    .ifBlank { ch.optString("release_date") }
                    .ifBlank { ch.optString("releaseDate") }
                    .ifBlank { ch.optString("created_at") }
                val groups = ch.optJSONArray("groups")
                chapters.add(
                    MangaChapter(
                        id = generateUid("$seriesId:$chId"),
                        title = chTitle,
                        number = number,
                        volume = volume,
                        url = "/series/$seriesId/reader/$chId",
                        uploadDate = dateFormat.parseSafe(dateStr),
                        source = source,
                        scanlator = groups?.let { arr ->
                            buildList {
                                for (j in 0 until arr.length()) {
                                    arr.optJSONObject(j)?.optString("title")?.takeIf { it.isNotBlank() }?.let(::add)
                                }
                            }.joinToString().ifBlank { null }
                        },
                        branch = null,
                    ),
                )
            }
            return chapters.sortedWith(
                compareBy<MangaChapter> { it.number <= 0f }
                    .thenBy { it.number }
                    .thenBy { it.volume }
                    .thenBy { it.title.orEmpty() },
            )
        }

        var chapters = parseChapters(
            json.optJSONArray("series_books")
                ?: json.optJSONArray("seriesBooks")
                ?: json.optJSONArray("books")
                ?: json.optJSONArray("content")
                ?: JSONArray(),
        )
        if (chapters.isEmpty()) {
            val chaptersUrl = "$apiUrl/api/v2/series/$seriesId/books/list"
            val chapterResp = webClient.httpGet(chaptersUrl, headers).parseJson()
            chapters = parseChapters(
                chapterResp.optJSONArray("series_books")
                    ?: chapterResp.optJSONArray("seriesBooks")
                    ?: chapterResp.optJSONArray("content")
                    ?: JSONArray(),
            )
        }

        return manga.copy(
            title = json.optString("title").ifBlank { manga.title },
            altTitles = altTitles,
            coverUrl = coverUrl,
            description = description,
            state = state,
            authors = authors,
            tags = genres,
            chapters = chapters,
            contentRating = parseContentRating(json.optString("content_rating")) ?: manga.contentRating,
        )
    }

    override suspend fun getRelatedManga(seed: Manga): List<Manga> {
        // Disable related/suggested manga feature
        return emptyList()
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val uri = URI(chapter.url)
        val pathParts = uri.path.split("/").filter { it.isNotEmpty() }
        if (pathParts.size < 4) throw Exception("Invalid chapter URL format: ${chapter.url}")

        val chapterId = pathParts.last()
        val challenge = getChallengeResponse(chapterId)
        accessToken = challenge.optString("access_token").ifBlank {
            challenge.optString("accessToken")
        }.ifBlank {
            throw Exception("Invalid challenge response: missing access token")
        }
        cacheUrl = challenge.optString("cache_url").ifBlank {
            challenge.optString("cacheUrl")
        }.ifBlank {
            throw Exception("Invalid challenge response: missing cache url")
        }

        val pages = parseManifestPages(challenge)
        if (pages.isEmpty()) {
            throw Exception("Invalid challenge response: missing pages manifest")
        }

        return pages.sortedBy { it.pageNumber }.map { page ->
            val ext = page.ext?.takeIf { it.isNotBlank() } ?: "jxl"
            val imageUrl = "$cacheUrl/api/v2/books/page".toHttpUrl().newBuilder()
                .addPathSegment(chapterId)
                .addPathSegment("${page.pageUuid}.$ext")
                .addQueryParameter("token", accessToken)
                .addQueryParameter("is_datasaver", "false")
                .build()
                .toString()
            MangaPage(
                id = generateUid(imageUrl),
                url = imageUrl,
                preview = null,
                source = source,
            )
        }
    }

    private fun requestCloudflareVerification(url: String, cause: Throwable? = null): Nothing {
        try {
            context.requestBrowserAction(this, url)
        } catch (e: UnsupportedOperationException) {
            throw ParseException(
                "Cloudflare verification required. Open Kagane in WebView and retry.",
                url,
                cause ?: e,
            )
        }
        throw ParseException("Retry after Cloudflare verification.", url, cause)
    }

    private fun String.isCloudflareChallenge(): Boolean {
        return contains("cf-mitigated", ignoreCase = true) ||
            contains("Just a moment", ignoreCase = true) ||
            contains("challenges.cloudflare.com", ignoreCase = true) ||
            contains("/cdn-cgi/challenge-platform/", ignoreCase = true)
    }

    private data class ManifestPage(
        val pageNumber: Int,
        val pageUuid: String,
        val ext: String?,
    )

    private fun parseManifestPages(challenge: JSONObject): List<ManifestPage> {
        val pagesJson = challenge.optJSONObject("manifest")?.optJSONArray("pages")
            ?: challenge.optJSONArray("pages")
            ?: JSONArray()
        return buildList {
            for (i in 0 until pagesJson.length()) {
                val page = pagesJson.optJSONObject(i) ?: continue
                val pageUuid = page.optString("page_id")
                    .ifBlank { page.optString("pageId") }
                    .ifBlank { page.optString("page_uuid") }
                    .ifBlank { page.optString("pageUuid") }
                if (pageUuid.isBlank()) continue
                add(
                    ManifestPage(
                        pageNumber = page.optInt(
                            "page_no",
                            page.optInt("pageNo", page.optInt("page_number", i + 1)),
                        ),
                        pageUuid = pageUuid,
                        ext = page.optString("ext").ifBlank { null },
                    ),
                )
            }
        }
    }

    private var cacheUrl = "https://akari.$domain"
    private var accessToken: String = ""
    private var cachedCert: String? = null
    private var certificateFetchAttempted = false
    private var integrityToken: String = ""
    private var integrityTokenExp: Long = 0L

    private suspend fun getCertificate(): String? {
        cachedCert?.let { return it }
        if (certificateFetchAttempted) return null
        certificateFetchAttempted = true
        val url = "$apiUrl/api/v2/static/bin.bin"
        val req = Request.Builder().url(url)
            .addHeader("Origin", "https://$domain")
            .addHeader("Referer", "https://$domain/")
            .tag(MangaSource::class.java, source)
            .build()

        val response = runCatching {
            context.httpClient.newCall(req).await()
        }.getOrNull()
        if (response == null) {
            dbg("bin.bin fetch failed (network)")
            return null
        }
        if (!response.isSuccessful) {
            dbg("bin.bin http ${response.code}")
            return null
        }
        val bytes = response.body?.bytes()?.takeIf { it.isNotEmpty() }
        if (bytes == null) {
            dbg("bin.bin empty body")
            return null
        }
        dbg("bin.bin ok bytes=${bytes.size} head=${bytes.headHex()}")

        val b64 = Base64.getEncoder().encodeToString(bytes)
        cachedCert = b64
        return b64
    }

    private suspend fun getIntegrityToken(): String {
        val now = System.currentTimeMillis()
        if (integrityToken.isNotBlank() && now < integrityTokenExp) {
            return integrityToken
        }

        val headers = getRequestHeaders().newBuilder()
            .add("Origin", "https://$domain")
            .add("Referer", "https://$domain/")
            .build()

        val response = webClient.httpPost(
            urlBuilder().addPathSegments("api/integrity").build(),
            JSONObject(),
            headers,
        ).parseJson()

        val token = response.optString("token")
        if (token.isBlank()) {
            throw Exception("Failed to retrieve integrity token")
        }
        integrityToken = token
        integrityTokenExp = response.optLong("exp", 0L) * 1000L
        return integrityToken
    }

    private suspend fun getChallengeResponse(chapterId: String): JSONObject {
        val integrityToken = getIntegrityToken()
        val headers = getRequestHeaders().newBuilder()
            .add("Origin", "https://$domain")
            .add("Referer", "https://$domain/")
            .add("x-integrity-token", integrityToken)
            .build()
        val challengeUrl = "$apiUrl/api/v2/books/$chapterId?is_datasaver=false"
        return webClient.httpPost(challengeUrl.toHttpUrl(), JSONObject(), headers).parseJson()
    }

    private fun getPssh(chapterId: String): String {
        val hash = ":$chapterId".sha256().copyOfRange(0, 16)

        // Widevine System ID
        val systemId = Base64.getDecoder().decode("7e+LqXnWSs6jyCfc1R0h7Q==")
        val zeroes = ByteArray(4)

        // Header: 18 (byte), hash.size (byte) + hash
        val header = byteArrayOf(18, hash.size.toByte()) + hash
        val headerSize = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(header.size).array()

        val innerBox = zeroes + systemId + headerSize + header

        val outerSize = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(innerBox.size + 8).array()
        val psshTag = "pssh".toByteArray(StandardCharsets.UTF_8)

        val fullBox = outerSize + psshTag + innerBox
        return Base64.getEncoder().encodeToString(fullBox)
    }

    private fun String.toChapterNumberOrNull(): Float? = trim()
        .replace(',', '.')
        .toFloatOrNull()

    private fun Any?.toPageFileId(): String = when (this) {
        is String -> toFileNamePart()
        is JSONObject -> optFirstString(
            "pageUuid",
            "page_uuid",
            "pageId",
            "page_id",
            "fileId",
            "file_id",
            "fileName",
            "file_name",
            "filename",
            "name",
            "id",
            "path",
            "url",
        ).toFileNamePart()
        else -> ""
    }

    private fun JSONObject.optFirstString(vararg keys: String): String {
        for (key in keys) {
            val value = optString(key).trim()
            if (value.isNotEmpty()) return value
        }
        return ""
    }

    private fun String.toFileNamePart(): String = substringBefore('?').substringAfterLast('/').trim()

    private operator fun ByteArray.plus(other: ByteArray): ByteArray {
        val result = ByteArray(this.size + other.size)
        System.arraycopy(this, 0, result, 0, this.size)
        System.arraycopy(other, 0, result, this.size, other.size)
        return result
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host
        if (host == domain || host.endsWith(".$domain")) {
            val newRequest = request.newBuilder()
                .header("Origin", "https://$domain")
                .header("Referer", "https://$domain/")
                .build()
            return chain.proceed(newRequest)
        }
        return chain.proceed(request)
    }

    // Decryption helpers

    private data class WordArray(val words: IntArray, val sigBytes: Int)

    private fun wordArrayToBytes(e: WordArray): ByteArray {
        val result = ByteArray(e.sigBytes)
        for (i in 0 until e.sigBytes) {
            val word = e.words[i ushr 2]
            val shift = 24 - (i % 4) * 8
            result[i] = ((word ushr shift) and 0xFF).toByte()
        }
        return result
    }

    private fun aesGcmDecrypt(keyWordArray: WordArray, ivWordArray: WordArray, cipherWordArray: WordArray): ByteArray? {
        return try {
            val keyBytes = wordArrayToBytes(keyWordArray)
            val iv = wordArrayToBytes(ivWordArray)
            val cipherBytes = wordArrayToBytes(cipherWordArray)

            val secretKey: SecretKey = SecretKeySpec(keyBytes, "AES")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(128, iv)

            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
            cipher.doFinal(cipherBytes)
        } catch (_: Exception) {
            null
        }
    }

    private fun toWordArray(bytes: ByteArray): WordArray {
        val words = IntArray((bytes.size + 3) / 4)
        for (i in bytes.indices) {
            val wordIndex = i / 4
            val shift = 24 - (i % 4) * 8
            words[wordIndex] = words[wordIndex] or ((bytes[i].toInt() and 0xFF) shl shift)
        }
        return WordArray(words, bytes.size)
    }

    private fun decryptImage(payload: ByteArray, keyPart1: String, keyPart2: String): ByteArray? {
        return try {
            if (payload.size < 140) return null

            val iv = payload.sliceArray(128 until 140)
            val ciphertext = payload.sliceArray(140 until payload.size)

            val keyHash = "$keyPart1:$keyPart2".sha256()

            val keyWA = toWordArray(keyHash)
            val ivWA = toWordArray(iv)
            val cipherWA = toWordArray(ciphertext)

            aesGcmDecrypt(keyWA, ivWA, cipherWA)
        } catch (_: Exception) {
            null
        }
    }

    private fun processData(input: ByteArray, index: Int, seriesId: String, chapterId: String): ByteArray? {
        fun isValidImage(data: ByteArray): Boolean {
            return when {
                // JPEG
                data.size >= 2 && data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte() -> true
                // GIF
                data.size >= 6 && (
                    data.copyOfRange(0, 6).contentEquals("GIF87a".toByteArray()) ||
                        data.copyOfRange(0, 6).contentEquals("GIF89a".toByteArray())
                    ) -> true
                // PNG
                data.size >= 8 && data.copyOfRange(0, 8).contentEquals(
                    byteArrayOf(
                        0x89.toByte(),
                        'P'.code.toByte(),
                        'N'.code.toByte(),
                        'G'.code.toByte(),
                        0x0D, 0x0A, 0x1A, 0x0A,
                    )
                ) -> true
                // WEBP
                data.size >= 12 && data[0] == 'R'.code.toByte() && data[1] == 'I'.code.toByte() &&
                    data[2] == 'F'.code.toByte() && data[3] == 'F'.code.toByte() &&
                    data[8] == 'W'.code.toByte() && data[9] == 'E'.code.toByte() &&
                    data[10] == 'B'.code.toByte() && data[11] == 'P'.code.toByte() -> true
                // HEIF
                data.size >= 12 && data.copyOfRange(4, 8).contentEquals("ftyp".toByteArray()) -> {
                    val type = data.copyOfRange(8, 11)
                    type.contentEquals("hei".toByteArray()) ||
                        type.contentEquals("hev".toByteArray()) ||
                        type.contentEquals("avi".toByteArray())
                }
                // JXL
                data.size >= 2 && data[0] == 0xFF.toByte() && data[1] == 0x0A.toByte() -> true
                data.size >= 12 && data.copyOfRange(0, 8).contentEquals(
                    byteArrayOf(
                        0,
                        0,
                        0,
                        12,
                        'J'.code.toByte(),
                        'X'.code.toByte(),
                        'L'.code.toByte(),
                        ' '.code.toByte(),
                    ),
                ) -> true
                else -> false
            }
        }

        try {
            var processed: ByteArray = input

            if (!isValidImage(processed)) {
                val seed = generateSeed(seriesId, chapterId, "%04d.jpg".format(index))
                val scrambler = Scrambler(seed, 10)
                val scrambleMapping = scrambler.getScrambleMapping()
                processed = unscramble(processed, scrambleMapping, true)
                if (!isValidImage(processed)) return null
            }

            return processed
        } catch (_: Exception) {
            return null
        }
    }

    private fun generateSeed(t: String, n: String, e: String): BigInteger {
        val sha256 = "$t:$n:$e".sha256()
        var a = BigInteger.ZERO
        for (i in 0 until 8) {
            a = a.shiftLeft(8).or(BigInteger.valueOf((sha256[i].toInt() and 0xFF).toLong()))
        }
        return a
    }

    private fun unscramble(data: ByteArray, mapping: List<Pair<Int, Int>>, n: Boolean): ByteArray {
        val s = mapping.size
        val a = data.size
        val l = a / s
        val o = a % s

        val (r, i) = if (n) {
            if (o > 0) {
                Pair(data.copyOfRange(0, o), data.copyOfRange(o, a))
            } else {
                Pair(ByteArray(0), data)
            }
        } else {
            if (o > 0) {
                Pair(data.copyOfRange(a - o, a), data.copyOfRange(0, a - o))
            } else {
                Pair(ByteArray(0), data)
            }
        }

        val chunks = (0 until s).map {
            val start = it * l
            val end = (it + 1) * l
            i.copyOfRange(start, end)
        }.toMutableList()

        val u = Array(s) { ByteArray(0) }

        if (n) {
            for ((e, m) in mapping) {
                if (e < s && m < s) {
                    u[e] = chunks[m]
                }
            }
        } else {
            for ((e, m) in mapping) {
                if (e < s && m < s) {
                    u[m] = chunks[e]
                }
            }
        }

        val h = u.fold(ByteArray(0)) { acc, chunk -> acc + chunk }

        return if (n) {
            h + r
        } else {
            r + h
        }
    }

    private class Scrambler(private val seed: BigInteger, private val gridSize: Int) {
        private val totalPieces: Int = gridSize * gridSize
        private val randomizer: Randomizer = Randomizer(seed, gridSize)
        private val dependencyGraph: DependencyGraph
        private val scramblePath: List<Int>

        init {
            dependencyGraph = buildDependencyGraph()
            scramblePath = generateScramblePath()
        }

        private data class DependencyGraph(
            val graph: MutableMap<Int, MutableList<Int>>,
            val inDegree: MutableMap<Int, Int>,
        )

        private fun buildDependencyGraph(): DependencyGraph {
            val graph = mutableMapOf<Int, MutableList<Int>>()
            val inDegree = mutableMapOf<Int, Int>()

            for (n in 0 until totalPieces) {
                inDegree[n] = 0
                graph[n] = mutableListOf()
            }

            val rng = Randomizer(seed, gridSize)

            for (r in 0 until totalPieces) {
                val i = (rng.prng() % BigInteger.valueOf(3) + BigInteger.valueOf(2)).toInt()
                repeat(i) {
                    val j = (rng.prng() % BigInteger.valueOf(totalPieces.toLong())).toInt()
                    if (j != r && !wouldCreateCycle(graph, j, r)) {
                        graph[j]!!.add(r)
                        inDegree[r] = inDegree[r]!! + 1
                    }
                }
            }

            for (r in 0 until totalPieces) {
                if (inDegree[r] == 0) {
                    var tries = 0
                    while (tries < 10) {
                        val s = (rng.prng() % BigInteger.valueOf(totalPieces.toLong())).toInt()
                        if (s != r && !wouldCreateCycle(graph, s, r)) {
                            graph[s]!!.add(r)
                            inDegree[r] = inDegree[r]!! + 1
                            break
                        }
                        tries++
                    }
                }
            }

            return DependencyGraph(graph, inDegree)
        }

        private fun wouldCreateCycle(graph: Map<Int, List<Int>>, target: Int, start: Int): Boolean {
            val visited = mutableSetOf<Int>()
            val stack = ArrayDeque<Int>()
            stack.add(start)

            while (stack.isNotEmpty()) {
                val n = stack.removeLast()
                if (n == target) return true
                if (!visited.add(n)) continue
                graph[n]?.let { stack.addAll(it) }
            }
            return false
        }

        private fun generateScramblePath(): List<Int> {
            val graphCopy = dependencyGraph.graph.mapValues { it.value.toMutableList() }.toMutableMap()
            val inDegreeCopy = dependencyGraph.inDegree.toMutableMap()

            val queue = ArrayDeque<Int>()
            for (n in 0 until totalPieces) {
                if (inDegreeCopy[n] == 0) {
                    queue.add(n)
                }
            }

            val order = mutableListOf<Int>()
            while (queue.isNotEmpty()) {
                val i = queue.removeFirst()
                order.add(i)
                val neighbors = graphCopy[i]
                if (neighbors != null) {
                    for (e in neighbors) {
                        inDegreeCopy[e] = inDegreeCopy[e]!! - 1
                        if (inDegreeCopy[e] == 0) {
                            queue.add(e)
                        }
                    }
                }
            }
            return order
        }

        fun getScrambleMapping(): List<Pair<Int, Int>> {
            var e = randomizer.order.toMutableList()

            if (scramblePath.size == totalPieces) {
                val t = Array(totalPieces) { 0 }
                for (i in scramblePath.indices) {
                    t[i] = scramblePath[i]
                }
                val n = Array(totalPieces) { 0 }
                for (r in 0 until totalPieces) {
                    n[r] = e[t[r]]
                }
                e = n.toMutableList()
            }

            val result = mutableListOf<Pair<Int, Int>>()
            for (n in 0 until totalPieces) {
                result.add(n to e[n])
            }
            return result
        }
    }

    private class Randomizer(seedInput: BigInteger, t: Int) {
        val size: Int = t * t
        val seed: BigInteger
        private var state: BigInteger
        private val entropyPool: ByteArray
        val order: MutableList<Int>

        companion object {
            private val MASK64 = BigInteger("FFFFFFFFFFFFFFFF", 16)
            private val MASK32 = BigInteger("FFFFFFFF", 16)
            private val MASK8 = BigInteger("FF", 16)
            private val PRNG_MULT = BigInteger("27BB2EE687B0B0FD", 16)
            private val RND_MULT_32 = BigInteger("45d9f3b", 16)
        }

        init {
            val seedMask = BigInteger("FFFFFFFFFFFFFFFF", 16)
            seed = seedInput.and(seedMask)
            state = hashSeed(seed)
            entropyPool = expandEntropy(seed)
            order = MutableList(size) { it }
            permute()
        }

        private fun hashSeed(e: BigInteger): BigInteger {
            val md = e.toString().sha256()
            return readBigUInt64BE(md, 0).xor(readBigUInt64BE(md, 8))
        }

        private fun readBigUInt64BE(bytes: ByteArray, offset: Int): BigInteger {
            var n = BigInteger.ZERO
            for (i in 0 until 8) {
                n = n.shiftLeft(8).or(BigInteger.valueOf((bytes[offset + i].toInt() and 0xFF).toLong()))
            }
            return n
        }

        private fun expandEntropy(e: BigInteger): ByteArray =
            MessageDigest.getInstance("SHA-512").digest(e.toString().toByteArray(StandardCharsets.UTF_8))

        private fun sbox(e: Int): Int {
            val t = intArrayOf(163, 95, 137, 13, 55, 193, 107, 228, 114, 185, 22, 243, 68, 218, 158, 40)
            return t[e and 15] xor t[e shr 4 and 15]
        }

        fun prng(): BigInteger {
            state = state.xor(state.shiftLeft(11).and(MASK64))
            state = state.xor(state.shiftRight(19))
            state = state.xor(state.shiftLeft(7).and(MASK64))
            state = state.multiply(PRNG_MULT).and(MASK64)
            return state
        }

        private fun roundFunc(e: BigInteger, t: Int): BigInteger {
            var n = e.xor(prng()).xor(BigInteger.valueOf(t.toLong()))

            val rot = n.shiftLeft(5).or(n.shiftRight(3)).and(MASK32)
            n = rot.multiply(RND_MULT_32).and(MASK32)

            val sboxVal = sbox(n.and(MASK8).toInt())
            n = n.xor(BigInteger.valueOf(sboxVal.toLong()))

            n = n.xor(n.shiftRight(13))
            return n
        }

        private fun feistelMix(e: Int, t: Int, rounds: Int): Pair<BigInteger, BigInteger> {
            var r = BigInteger.valueOf(e.toLong())
            var i = BigInteger.valueOf(t.toLong())
            for (round in 0 until rounds) {
                val ent = entropyPool[round % entropyPool.size].toInt() and 0xFF
                r = r.xor(roundFunc(i, ent))
                val secondArg = ent xor (round * 31 and 255)
                i = i.xor(roundFunc(r, secondArg))
            }
            return Pair(r, i)
        }

        private fun permute() {
            val half = size / 2
            val sizeBig = BigInteger.valueOf(size.toLong())

            for (t in 0 until half) {
                val n = t + half
                val (rBig, iBig) = feistelMix(t, n, 4)
                val s = rBig.mod(sizeBig).toInt()
                val a = iBig.mod(sizeBig).toInt()
                val tmp = order[s]
                order[s] = order[a]
                order[a] = tmp
            }

            for (e in size - 1 downTo 1) {
                val ent = entropyPool[e % entropyPool.size].toInt() and 0xFF
                val idxBig = prng().add(BigInteger.valueOf(ent.toLong())).mod(BigInteger.valueOf((e + 1).toLong()))
                val n = idxBig.toInt()
                val tmp = order[e]
                order[e] = order[n]
                order[n] = tmp
            }
        }
    }
}

private fun String.sha256(): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(this.toByteArray(StandardCharsets.UTF_8))

private class Scrambler(private val seed: BigInteger, private val gridSize: Int) {
    private val totalPieces: Int = gridSize * gridSize
    private val randomizer: Randomizer = Randomizer(seed, gridSize)
    private val dependencyGraph: DependencyGraph
    private val scramblePath: List<Int>

    init {
        dependencyGraph = buildDependencyGraph()
        scramblePath = generateScramblePath()
    }

    private data class DependencyGraph(
        val graph: MutableMap<Int, MutableList<Int>>,
        val inDegree: MutableMap<Int, Int>,
    )

    private fun buildDependencyGraph(): DependencyGraph {
        val graph = mutableMapOf<Int, MutableList<Int>>()
        val inDegree = mutableMapOf<Int, Int>()

        for (n in 0 until totalPieces) {
            inDegree[n] = 0
            graph[n] = mutableListOf()
        }

        val rng = Randomizer(seed, gridSize)

        for (r in 0 until totalPieces) {
            val i = (rng.prng() % BigInteger.valueOf(3) + BigInteger.valueOf(2)).toInt()
            repeat(i) {
                val j = (rng.prng() % BigInteger.valueOf(totalPieces.toLong())).toInt()
                if (j != r && !wouldCreateCycle(graph, j, r)) {
                    graph[j]!!.add(r)
                    inDegree[r] = inDegree[r]!! + 1
                }
            }
        }

        for (r in 0 until totalPieces) {
            if (inDegree[r] == 0) {
                var tries = 0
                while (tries < 10) {
                    val s = (rng.prng() % BigInteger.valueOf(totalPieces.toLong())).toInt()
                    if (s != r && !wouldCreateCycle(graph, s, r)) {
                        graph[s]!!.add(r)
                        inDegree[r] = inDegree[r]!! + 1
                        break
                    }
                    tries++
                }
            }
        }

        return DependencyGraph(graph, inDegree)
    }

    private fun wouldCreateCycle(graph: Map<Int, List<Int>>, target: Int, start: Int): Boolean {
        val visited = mutableSetOf<Int>()
        val stack = ArrayDeque<Int>()
        stack.add(start)

        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            if (n == target) return true
            if (!visited.add(n)) continue
            graph[n]?.let { stack.addAll(it) }
        }
        return false
    }

    private fun generateScramblePath(): List<Int> {
        val graphCopy = dependencyGraph.graph.mapValues { it.value.toMutableList() }.toMutableMap()
        val inDegreeCopy = dependencyGraph.inDegree.toMutableMap()

        val queue = ArrayDeque<Int>()
        for (n in 0 until totalPieces) {
            if (inDegreeCopy[n] == 0) {
                queue.add(n)
            }
        }

        val order = mutableListOf<Int>()
        while (queue.isNotEmpty()) {
            val i = queue.removeFirst()
            order.add(i)
            val neighbors = graphCopy[i]
            if (neighbors != null) {
                for (e in neighbors) {
                    inDegreeCopy[e] = inDegreeCopy[e]!! - 1
                    if (inDegreeCopy[e] == 0) {
                        queue.add(e)
                    }
                }
            }
        }
        return order
    }

    fun getScrambleMapping(): List<Pair<Int, Int>> {
        var e = randomizer.order.toMutableList()

        if (scramblePath.size == totalPieces) {
            val t = Array(totalPieces) { 0 }
            for (i in scramblePath.indices) {
                t[i] = scramblePath[i]
            }
            val n = Array(totalPieces) { 0 }
            for (r in 0 until totalPieces) {
                n[r] = e[t[r]]
            }
            e = n.toMutableList()
        }

        val result = mutableListOf<Pair<Int, Int>>()
        for (n in 0 until totalPieces) {
            result.add(n to e[n])
        }
        return result
    }
}

private class Randomizer(seedInput: BigInteger, t: Int) {
    val size: Int = t * t
    val seed: BigInteger
    private var state: BigInteger
    private val entropyPool: ByteArray
    val order: MutableList<Int>

    companion object {
        private val MASK64 = BigInteger("FFFFFFFFFFFFFFFF", 16)
        private val MASK32 = BigInteger("FFFFFFFF", 16)
        private val MASK8 = BigInteger("FF", 16)
        private val PRNG_MULT = BigInteger("27BB2EE687B0B0FD", 16)
        private val RND_MULT_32 = BigInteger("45d9f3b", 16)
    }

    init {
        val seedMask = BigInteger("FFFFFFFFFFFFFFFF", 16)
        seed = seedInput.and(seedMask)
        state = hashSeed(seed)
        entropyPool = expandEntropy(seed)
        order = MutableList(size) { it }
        permute()
    }

    private fun hashSeed(e: BigInteger): BigInteger {
        val md = e.toString().sha256()
        return readBigUInt64BE(md, 0).xor(readBigUInt64BE(md, 8))
    }

    private fun readBigUInt64BE(bytes: ByteArray, offset: Int): BigInteger {
        var n = BigInteger.ZERO
        for (i in 0 until 8) {
            n = n.shiftLeft(8).or(BigInteger.valueOf((bytes[offset + i].toInt() and 0xFF).toLong()))
        }
        return n
    }

    private fun expandEntropy(e: BigInteger): ByteArray =
        MessageDigest.getInstance("SHA-512").digest(e.toString().toByteArray(StandardCharsets.UTF_8))

    private fun sbox(e: Int): Int {
        val t = intArrayOf(163, 95, 137, 13, 55, 193, 107, 228, 114, 185, 22, 243, 68, 218, 158, 40)
        return t[e and 15] xor t[e shr 4 and 15]
    }

    fun prng(): BigInteger {
        state = state.xor(state.shiftLeft(11).and(MASK64))
        state = state.xor(state.shiftRight(19))
        state = state.xor(state.shiftLeft(7).and(MASK64))
        state = state.multiply(PRNG_MULT).and(MASK64)
        return state
    }

    private fun roundFunc(e: BigInteger, t: Int): BigInteger {
        var n = e.xor(prng()).xor(BigInteger.valueOf(t.toLong()))

        val rot = n.shiftLeft(5).or(n.shiftRight(3)).and(MASK32)
        n = rot.multiply(RND_MULT_32).and(MASK32)

        val sboxVal = sbox(n.and(MASK8).toInt())
        n = n.xor(BigInteger.valueOf(sboxVal.toLong()))

        n = n.xor(n.shiftRight(13))
        return n
    }

    private fun feistelMix(e: Int, t: Int, rounds: Int): Pair<BigInteger, BigInteger> {
        var r = BigInteger.valueOf(e.toLong())
        var i = BigInteger.valueOf(t.toLong())
        for (round in 0 until rounds) {
            val ent = entropyPool[round % entropyPool.size].toInt() and 0xFF
            r = r.xor(roundFunc(i, ent))
            val secondArg = ent xor (round * 31 and 255)
            i = i.xor(roundFunc(r, secondArg))
        }
        return Pair(r, i)
    }

    private fun permute() {
        val half = size / 2
        val sizeBig = BigInteger.valueOf(size.toLong())

        for (t in 0 until half) {
            val n = t + half
            val (rBig, iBig) = feistelMix(t, n, 4)
            val s = rBig.mod(sizeBig).toInt()
            val a = iBig.mod(sizeBig).toInt()
            val tmp = order[s]
            order[s] = order[a]
            order[a] = tmp
        }

        for (e in size - 1 downTo 1) {
            val ent = entropyPool[e % entropyPool.size].toInt() and 0xFF
            val idxBig = prng().add(BigInteger.valueOf(ent.toLong())).mod(BigInteger.valueOf((e + 1).toLong()))
            val n = idxBig.toInt()
            val tmp = order[e]
            order[e] = order[n]
            order[n] = tmp
        }
    }
}
