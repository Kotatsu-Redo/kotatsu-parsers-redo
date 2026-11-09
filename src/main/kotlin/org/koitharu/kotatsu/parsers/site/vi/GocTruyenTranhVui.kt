package org.koitharu.kotatsu.parsers.site.vi

import androidx.collection.arraySetOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response
import org.jsoup.HttpStatusException
import org.koitharu.kotatsu.parsers.exception.AuthRequiredException
import org.koitharu.kotatsu.parsers.webview.InterceptionConfig
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.json.JSONObject
import java.io.IOException
import java.util.*

@MangaSourceParser("GOCTRUYENTRANHVUI", "Góc Truyện Tranh Vui", "vi")
internal class GocTruyenTranhVui(context: MangaLoaderContext):
    PagedMangaParser(context, MangaParserSource.GOCTRUYENTRANHVUI, 50) {

    override val configKeyDomain = ConfigKey.Domain("goctruyentranhvui41.com")
    private val domainResolver by lazy {
        val probe = GocTruyenDomainProbe(context.httpClient, context.getDefaultUserAgent())
        GocTruyenDomainResolver({ domain }, probe::isValid, advertisedDomain = probe::advertisedDomain)
    }

    private val requestMutex = Mutex()
    private var lastRequestTime = 0L

    private fun apiHeaders(siteDomain: String): Headers = Headers.Builder()
        .add("Referer", "https://$siteDomain/")
        .add("X-Requested-With", "XMLHttpRequest")
        .build()

    private suspend fun getApi(url: String, pageUrl: String, siteDomain: String): JSONObject =
        withSessionRetry(pageUrl, siteDomain) { headers -> webClient.httpGet(url, extraHeaders = headers) }

    private suspend fun withSessionRetry(
        pageUrl: String,
        siteDomain: String,
        request: suspend (Headers) -> Response,
    ): JSONObject {
        try {
            return readApiJson(request(apiHeaders(siteDomain)))
        } catch (_: AuthRequiredException) {
            // The web client closes unsuccessful responses before throwing.
        } catch (e: HttpStatusException) {
            if (e.statusCode != 403) throw e
        }
        return try {
            readApiJson(request(authenticatedHeaders(pageUrl, siteDomain)))
        } catch (_: AuthRequiredException) {
            requestLogin(pageUrl)
        } catch (e: HttpStatusException) {
            if (e.statusCode != 403) throw e
            requestLogin(pageUrl)
        }
    }

    private fun readApiJson(response: Response): JSONObject {
        val json = response.parseJson()
        if (GocTruyenPageData.requiresAuthorization(json)) throw AuthRequiredException(source)
        return json
    }

    private fun requestLogin(pageUrl: String): Nothing {
        try {
            context.requestBrowserAction(this, pageUrl)
        } catch (e: UnsupportedOperationException) {
            throw AuthRequiredException(source, e)
        }
    }

    private suspend fun authenticatedHeaders(pageUrl: String, siteDomain: String): Headers {
        // Capture the user's actual session; never ship a shared account token or cache it across logouts.
        val requests = try {
            context.interceptWebViewRequests(
                pageUrl,
                InterceptionConfig(
                    timeoutMs = 15000L,
                    urlPattern = Regex("^${Regex.escape("https://$siteDomain/api/")}"),
                ),
            )
        } catch (e: UnsupportedOperationException) {
            throw AuthRequiredException(source, e)
        }
        val authorization = requests.firstNotNullOfOrNull {
            GocTruyenAuthorization.bearerHeader(it, "https://$siteDomain".toHttpUrl())
        } ?: throw AuthRequiredException(source)
        return apiHeaders(siteDomain).newBuilder().add("Authorization", authorization).build()
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.NEWEST,
        SortOrder.RATING
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isMultipleTagsSupported = true,
    )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = availableTags(),
        availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED)
    )

    override suspend fun getListPage(
        page: Int,
        order: SortOrder,
        filter: MangaListFilter,
    ): List<Manga> = domainResolver.withDomain { siteDomain ->
        enforceRateLimit()
        val url = buildString {
            append("https://$siteDomain/api/v2")
            append("/search?p=${page - 1}")
            if (!filter.query.isNullOrBlank()) {
                append("&searchValue=${filter.query.urlEncoded()}")
            }

            val sortValue = when (order) {
                SortOrder.POPULARITY -> "viewCount"
                SortOrder.NEWEST -> "createdAt"
                SortOrder.RATING -> "evaluationScore"
                else -> "recentDate" // UPDATED
            }
            append("&orders%5B%5D=$sortValue")

            filter.tags.forEach { append("&categories%5B%5D=${it.key}") }

            filter.states.forEach {
                val statusKey = when (it) {
                    MangaState.ONGOING -> "PRG"
                    MangaState.FINISHED -> "END"
                    else -> null
                }
                if (statusKey != null) append("&status%5B%5D=$statusKey")
            }
        }

        val json = getApi(url, "https://$siteDomain/", siteDomain)
        val result = json.optJSONObject("result") ?: return@withDomain emptyList()
        val data = result.optJSONArray("data") ?: return@withDomain emptyList()

        List(data.length()) { i ->
            val item = data.getJSONObject(i)
            val comicId = item.getString("id")
            val slug = item.getString("nameEn")
            val mangaUrl = "/truyen/$slug"
            val tags = item.optJSONArray("category")?.let { arr ->
                (0 until arr.length()).mapNotNullTo(mutableSetOf()) { index ->
                    val tagName = arr.getString(index)
                    availableTags().find { it.title.equals(tagName, ignoreCase = true) }?.let { genrePair ->
                        MangaTag(key = genrePair.key, title = genrePair.title, source = source)
                    }
                }
            } ?: emptySet()

            Manga(
                id = generateUid(comicId),
                title = item.getString("name"),
                altTitles = item.optString("otherName", "").split(",").mapNotNull { it.trim().takeIf(String::isNotBlank) }.toSet(),
                url = "$comicId:$slug", // Store both id and slug, separated by ':'
                publicUrl = "https://$siteDomain$mangaUrl",
                rating = item.optDouble("evaluationScore", 0.0).toFloat(),
                contentRating = null,
                coverUrl = GocTruyenDomainResolver.rebaseUrl(item.getString("photo"), siteDomain),
                tags = tags,
                state = when (item.optString("statusCode")) {
                    "PRG" -> MangaState.ONGOING
                    "END" -> MangaState.FINISHED
                    else -> null
                },
                authors = setOf(item.optString("author", "Updating")),
                source = source
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga = domainResolver.withDomain { siteDomain ->
        val comicId = manga.url.substringBefore(':')
        val slug = manga.url.substringAfter(':')
        val publicUrl = "https://$siteDomain/truyen/$slug"

        val chapters = try {
            enforceRateLimit()
            // This public endpoint returns the full list; /chapter requires an account session.
            val chapterApiUrl = "https://$siteDomain/api/comic/$comicId/chapterNumber"
            val chapterJson = getApi(chapterApiUrl, publicUrl, siteDomain)
            val chaptersData = chapterJson.getJSONObject("result").getJSONArray("chapterNumbers")

            List(chaptersData.length()) { i ->
                val number = chaptersData.getString(i)
                val chapterUrl = "/truyen/$slug/chuong-$number"
                MangaChapter(
                    id = generateUid(chapterUrl),
                    title = "Chapter $number",
                    number = number.toFloatOrNull() ?: -1f,
                    volume = 0,
                    url = chapterUrl,
                    scanlator = null,
                    uploadDate = 0L,
                    branch = null,
                    source = source
                )
            }
        } catch (e: AuthRequiredException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            if (GocTruyenDomainResolver.isConnectionFailure(e)) throw e
            emptyList()
        } catch (_: Exception) {
            emptyList()
        }.reversed()

        enforceRateLimit()
        val doc = webClient.httpGet(publicUrl).parseHtml()

        val detailTags = doc.select(".group-content > .v-chip-link").mapNotNullTo(mutableSetOf()) { el ->
            availableTags().find { it.title.equals(el.text(), ignoreCase = true) }?.let {
                MangaTag(key = it.key, title = it.title, source = source)
            }
        }

        manga.copy(
            publicUrl = publicUrl,
            title = doc.selectFirst(".v-card-title")?.text().orEmpty(),
            tags = manga.tags + detailTags,
            coverUrl = doc.selectFirst("img.image")?.absUrl("src"),
            state = when (doc.selectFirst(".mb-1:contains(Trạng thái:) span")?.text()) {
                "Đang thực hiện" -> MangaState.ONGOING
                "Hoàn thành" -> MangaState.FINISHED
                else -> manga.state
            },
            authors = setOfNotNull(doc.selectFirst(".mb-1:contains(Tác giả:) span")?.text()),
            description = doc.selectFirst(".v-card-text")?.text(),
            chapters = chapters
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> = domainResolver.withDomain { siteDomain ->
        enforceRateLimit()
        val chapterUrl = GocTruyenDomainResolver.rebaseUrl(chapter.url, siteDomain)
        val responseBody = webClient.httpGet(chapterUrl).body.string()
        val chapterJsonRaw = responseBody.substringAfter("chapterJson: `", "").substringBefore("`", "")

        val imageUrls: List<String>
        if (chapterJsonRaw.isNotBlank()) {
            val json = JSONObject(chapterJsonRaw)
            val data = json.getJSONObject("body").getJSONObject("result").getJSONArray("data")
            imageUrls = List(data.length()) { i -> data.getString(i) }
        } else {
            // Fallback: Call the authenticated API
            val comicId = GocTruyenPageData.comicId(responseBody)
                ?: throw IllegalStateException("Cannot find comic metadata in chapter HTML")
            val chapterNumber = chapter.url.substringAfterLast("chuong-")
            val nameEn = chapter.url.substringAfter("/truyen/").substringBefore("/chuong-")

            val formBody = mapOf(
                "comicId" to comicId,
                "chapterNumber" to chapterNumber,
                "nameEn" to nameEn
            )
            val authApiUrl = "https://$siteDomain/api/chapter/loadAll".toHttpUrl()
            val authResponse = withSessionRetry(chapterUrl, siteDomain) { headers ->
                webClient.httpPost(url = authApiUrl, form = formBody, extraHeaders = headers)
            }
            val data = authResponse.getJSONObject("result").getJSONArray("data")
            imageUrls = List(data.length()) { i -> data.getString(i) }
        }

        imageUrls.map { url ->
            val finalUrl = GocTruyenDomainResolver.rebaseUrl(url, siteDomain)
            MangaPage(id = generateUid(finalUrl.toRelativeUrl(siteDomain)), url = finalUrl, preview = null, source = source)
        }
    }

    override suspend fun getPageUrl(page: MangaPage): String = domainResolver.withDomain { siteDomain ->
        GocTruyenDomainResolver.rebaseUrl(page.url, siteDomain)
    }

    override suspend fun getFavicons(): Favicons = domainResolver.withDomain { siteDomain ->
        FaviconParser(webClient, siteDomain).parseFavicons()
    }

    private suspend fun enforceRateLimit() {
        requestMutex.withLock {
            val currentTime = System.currentTimeMillis()
            val timeSinceLastRequest = currentTime - lastRequestTime
            if (timeSinceLastRequest < REQUEST_DELAY_MS) { // Vẫn truy cập được REQUEST_DELAY_MS
                delay(REQUEST_DELAY_MS - timeSinceLastRequest)
            }
            lastRequestTime = System.currentTimeMillis()
        }
    }

    private fun availableTags() = arraySetOf(
        MangaTag("Anime", "ANI", source),
        MangaTag("Drama", "DRA", source),
        MangaTag("Josei", "JOS", source),
        MangaTag("Manhwa", "MAW", source),
        MangaTag("One Shot", "OSH", source),
        MangaTag("Shounen", "SHO", source),
        MangaTag("Webtoons", "WEB", source),
        MangaTag("Shoujo", "SHJ", source),
        MangaTag("Harem", "HAR", source),
        MangaTag("Ecchi", "ECC", source),
        MangaTag("Mature", "MAT", source),
        MangaTag("Slice of life", "SOL", source),
        MangaTag("Isekai", "ISE", source),
        MangaTag("Manga", "MAG", source),
        MangaTag("Manhua", "MAU", source),
        MangaTag("Hành Động", "ACT", source),
        MangaTag("Phiêu Lưu", "ADV", source),
        MangaTag("Hài Hước", "COM", source),
        MangaTag("Võ Thuật", "MAA", source),
        MangaTag("Huyền Bí", "MYS", source),
        MangaTag("Lãng Mạn", "ROM", source),
        MangaTag("Thể Thao", "SPO", source),
        MangaTag("Học Đường", "SCL", source),
        MangaTag("Lịch Sử", "HIS", source),
        MangaTag("Kinh Dị", "HOR", source),
        MangaTag("Siêu Nhiên", "SUN", source),
        MangaTag("Bi Kịch", "TRA", source),
        MangaTag("Trùng Sinh", "RED", source),
        MangaTag("Game", "GAM", source),
        MangaTag("Viễn Tưởng", "FTS", source),
        MangaTag("Khoa Học", "SCF", source),
        MangaTag("Truyện Màu", "COI", source),
        MangaTag("Người Lớn", "ADU", source),
        MangaTag("BoyLove", "BBL", source),
        MangaTag("Hầm Ngục", "DUN", source),
        MangaTag("Săn Bắn", "HUNT", source),
        MangaTag("Ngôn Từ Nhạy Cảm", "NTNC", source),
        MangaTag("Doujinshi", "DOU", source),
        MangaTag("Bạo Lực", "BLM", source),
        MangaTag("Ngôn Tình", "NTT", source),
        MangaTag("Nữ Cường", "NCT", source),
        MangaTag("Gender Bender", "GDB", source),
        MangaTag("Murim", "MRR", source),
        MangaTag("Leo Tháp", "LTT", source),
        MangaTag("Nấu Ăn", "COO", source)
    )

    companion object {
        private const val REQUEST_DELAY_MS = 350L
    }
}
