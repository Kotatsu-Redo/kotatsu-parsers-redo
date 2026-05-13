package org.koitharu.kotatsu.parsers.site.mangareader.id

import org.jsoup.nodes.Document
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.util.*

@MangaSourceParser("YURILAB", "YuriLab", "id", ContentType.HENTAI)
internal class YuriLab(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.YURILAB, pageSize = 30, searchPageSize = 10) {

    override val configKeyDomain = ConfigKey.Domain("yurilabs.my.id")

    override val sourceLocale: Locale = Locale.ENGLISH

    override val availableSortOrders: Set<SortOrder>
        get() = EnumSet.of(
            SortOrder.RELEVANCE,
            SortOrder.UPDATED,
            SortOrder.NEWEST,
            SortOrder.POPULARITY,
            SortOrder.ALPHABETICAL,
        )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isMultipleTagsSupported = true,
        )

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        return MangaListFilterOptions(
            availableTags = getOrCreateTagMap().values.toSet(),
            availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED, MangaState.PAUSED, MangaState.ABANDONED),
            availableContentTypes = EnumSet.of(ContentType.MANGA, ContentType.MANHWA, ContentType.MANHUA),
        )
    }

    private var tagCache: Map<String, MangaTag>? = null

    private suspend fun getOrCreateTagMap(): Map<String, MangaTag> {
        tagCache?.let { return it }
        val url = "https://$domain/?s=&post_type=wp-manga"
        val docs = webClient.httpGet(url).parseHtml()
        val genreLinks = docs.select(".genres-filter .dropdown-menu a[href*='genre=']")
        val map = mutableMapOf<String, MangaTag>()
        for (el in genreLinks) {
            val href = el.attrOrNull("href") ?: continue
            val match = Regex("""genre=([^&]+)""").find(href)
            val key = match?.groupValues?.get(1) ?: continue
            val title = el.textOrNull()?.trim()?.toTitleCase(sourceLocale) ?: continue
            map[key] = MangaTag(
                title = title,
                key = key,
                source = source,
            )
        }
        tagCache = map
        return map
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = buildString {
            append("https://")
            append(domain)

            if (!filter.query.isNullOrEmpty()) {
                append("/?s=")
                append(filter.query.urlEncoded())
                append("&post_type=wp-manga")
                if (page > 1) {
                    append("&paged=")
                    append(page)
                }
            } else {
                if (page > 1) {
                    append("/page/")
                    append(page)
                    append("/?s&post_type=wp-manga")
                } else {
                    append("/?s&post_type=wp-manga")
                }
                if (filter.tags.isNotEmpty()) {
                    append("&genre=")
                    append(filter.tags.first().key)
                }
                filter.states.oneOrThrowIfMany()?.let { state ->
                    append("&status=")
                    append(
                        when (state) {
                            MangaState.ONGOING -> "on-going"
                            MangaState.FINISHED -> "end"
                            MangaState.ABANDONED -> "canceled"
                            MangaState.PAUSED -> "on-hold"
                            else -> return@let
                        },
                    )
                }
                append("&m_orderby=")
                append(
                    when (order) {
                        SortOrder.ALPHABETICAL -> "alphabet"
                        SortOrder.NEWEST -> "new-manga"
                        SortOrder.POPULARITY -> "trending"
                        SortOrder.UPDATED -> "latest"
                        SortOrder.RELEVANCE -> ""
                        else -> "latest"
                    },
                )
            }
        }

        val docs = webClient.httpGet(url).parseHtml()
        return docs.select(".manga__item").mapNotNull { item ->
            val a = item.selectFirst("a") ?: return@mapNotNull null
            val titleEl = item.selectFirst("h2 a") ?: item.selectFirst("a")
            val imgEl = item.selectFirst(".manga__thumb img")
            Manga(
                id = generateUid(a.attrAsRelativeUrl("href")),
                url = a.attrAsRelativeUrl("href"),
                title = titleEl?.textOrNull().nullIfEmpty() ?: a.text(),
                altTitles = emptySet(),
                publicUrl = a.attrAsAbsoluteUrl("href"),
                rating = RATING_UNKNOWN,
                contentRating = ContentRating.ADULT,
                coverUrl = imgEl?.srcWithFallback(),
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                source = source,
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val docs = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()

        val description = docs.selectFirst(".description-summary p")?.textOrNull()
            ?: docs.selectFirst(".summary__content p")?.textOrNull()
            ?: docs.selectFirst(".manga-summary p")?.textOrNull()
            ?: docs.selectFirst(".manga__content .manga-excerpt p")?.textOrNull()

        val tags = docs.select(".genres-content a[href*='genre'], .tags-content a[href*='tag']").mapNotNull { el ->
            val href = el.attrOrNull("href") ?: return@mapNotNull null
            val tagKey = extractTagKey(href) ?: return@mapNotNull null
            val title = el.textOrNull()?.trim() ?: return@mapNotNull null
            MangaTag(
                title = title,
                key = tagKey,
                source = source,
            )
        }.toSet()

        val stateText = docs.selectFirst(".manga-status, .post-status")?.textOrNull()
            ?: docs.select(".summary-content").firstOrNull {
                val t = it.text().lowercase()
                t.contains("ongoing") || t.contains("completed") || t.contains("hiatus") ||
                    t.contains("canceled") || t.contains("hold")
            }?.textOrNull()

        val state = stateText?.lowercase()?.let {
            when {
                it.contains("ongoing") -> MangaState.ONGOING
                it.contains("completed") || it.contains("end") -> MangaState.FINISHED
                it.contains("canceled") -> MangaState.ABANDONED
                it.contains("hiatus") || it.contains("hold") -> MangaState.PAUSED
                else -> null
            }
        }

        val author = docs.selectFirst(".author-content a, .manga-author a")?.textOrNull()

        val title = docs.selectFirst("h1.post-title")?.textOrNull()
            ?: docs.selectFirst(".post-title")?.textOrNull()
            ?: docs.selectFirst("h1")?.textOrNull()
            ?: docs.selectFirst("title")?.text()?.substringBefore(" - ")?.trim()
            ?: manga.title

        val chapters = loadChapters(manga.url.toAbsoluteUrl(domain), docs)

        return manga.copy(
            title = title,
            description = description,
            tags = tags,
            state = state,
            authors = setOfNotNull(author),
            chapters = chapters,
        )
    }

    private val selectChapter = "ul.version-chap li.wp-manga-chapter"

    private suspend fun loadChapters(mangaUrl: String, document: Document): List<MangaChapter> {
        val allChapters = mutableListOf<MangaChapter>()
        var t = 1

        while (true) {
            val ajaxUrl = mangaUrl.removeSuffix('/') + "/ajax/chapters/?t=$t"
            val ajaxDocs = webClient.httpPost(
                ajaxUrl.toHttpUrl(),
                emptyMap<String, String>(),
                Headers.Builder().add("X-Requested-With", "XMLHttpRequest").build(),
            ).parseHtml()

            val pageChapters = ajaxDocs.select(selectChapter).mapNotNull { li ->
                val a = li.selectFirst("a") ?: return@mapNotNull null
                val href = a.attrAsRelativeUrl("href")
                if (href.isBlank() || href == "#") return@mapNotNull null
                val name = a.ownText().nullIfEmpty() ?: a.selectFirst("p")?.textOrNull()
                    ?: "Chapter ${allChapters.size + 1}"
                MangaChapter(
                    id = generateUid(href),
                    url = href,
                    title = name.trim(),
                    number = 0f,
                    volume = 0,
                    branch = null,
                    uploadDate = 0L,
                    scanlator = null,
                    source = source,
                )
            }

            if (pageChapters.isEmpty()) break
            allChapters.addAll(pageChapters)
            t++
        }

        // Assign sequential chapter numbers (reversed order = oldest first)
        return allChapters.reversed().mapIndexed { index, chapter ->
            chapter.copy(number = (index + 1).toFloat())
        }
    }

    private val selectPage = "div#readerarea img, .reading-content img, .page-break img"

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val chapterUrl = chapter.url.toAbsoluteUrl(domain)
        val docs = webClient.httpGet(chapterUrl).parseHtml()

        // Check for encrypted image content (wp-manga-protector)
        val chapterProtector = docs.getElementById("chapter-protector-data")
        if (chapterProtector != null) {
            val chapterProtectorHtml = chapterProtector.attr("src").takeIf { it.isNotBlank() }
                ?: chapterProtector.html()
            val password = chapterProtectorHtml.substringAfter("wpmangaprotectornonce='").substringBefore("';")
            val chapterDataJson = chapterProtectorHtml.substringAfter("chapter_data='").substringBefore("';").replace("\\/", "/")
            return decryptPages(chapterDataJson, password)
        }

        // Fallback: direct image parsing
        return docs.select(selectPage).mapNotNull { el ->
            val src = el.srcWithFallback()
            if (src.isNullOrBlank()) return@mapNotNull null
            MangaPage(
                id = generateUid(src),
                url = src,
                preview = null,
                source = source,
            )
        }.filter { it.url.isNotBlank() && !it.url.contains("cover") }
    }

    private fun decryptPages(chapterDataJson: String, password: String): List<MangaPage> {
        val parsed = org.json.JSONObject(chapterDataJson)
        val ctBase64 = parsed.getString("ct")
        val saltHex = parsed.getString("s")
        val ivHex = parsed.getString("iv")

        val ctBytes = context.decodeBase64(ctBase64)
        val saltBytes = hexToBytes(saltHex)
        val ivBytes = hexToBytes(ivHex)

        // CryptoJS EvpKDF: keySize=8 (words), iterations=1
        // First block: MD5(password || salt)
        // Subsequent blocks: MD5(prev_block || password || salt)
        val derived = evpKdfCryptoJS(password.toByteArray(Charsets.UTF_8), saltBytes, keySizeWords = 8)
        val keyBytes = derived.copyOfRange(0, 32)

        // AES-256-CBC decryption
        val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            javax.crypto.Cipher.DECRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(keyBytes, "AES"),
            javax.crypto.spec.IvParameterSpec(ivBytes)
        )
        val decrypted = cipher.doFinal(ctBytes)
        val jsonString = String(decrypted, Charsets.UTF_8)

        val cleaned = jsonString
            .replace(Regex("""\\\/"""), "/")
            .replace(Regex("""\\"""), "")

        val inner = if (cleaned.startsWith("\"") && cleaned.endsWith("\"")) {
            cleaned.substring(1, cleaned.length - 1).replace("\\\"", "\"")
        } else {
            cleaned
        }
        val jsonArray = org.json.JSONArray(inner)
        return parseImageUrls(jsonArray)
    }

    private fun parseImageUrls(jsonArray: org.json.JSONArray): List<MangaPage> {
        return (0 until jsonArray.length()).mapNotNull { jsonArray.optString(it).nullIfEmpty() }.map { imageUrl ->
            MangaPage(
                id = generateUid(imageUrl),
                url = imageUrl,
                preview = null,
                source = source,
            )
        }
    }

    private fun evpKdfCryptoJS(password: ByteArray, salt: ByteArray, keySizeWords: Int = 4): ByteArray {
        val targetBytes = keySizeWords * 4
        val result = mutableListOf<Byte>()
        var prevBlock = ByteArray(0)

        while (result.size < targetBytes) {
            // MD5(prev_block || password || salt)
            val mdInput = prevBlock + password + salt
            val md = java.security.MessageDigest.getInstance("MD5")
            val block = md.digest(mdInput)

            result.addAll(block.toList())
            prevBlock = block
        }

        return result.take(targetBytes).toByteArray()
    }

    private fun hexToBytes(hex: String): ByteArray {
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun String.urlEncoded(): String = java.net.URLEncoder.encode(this, "UTF-8")

    private fun String?.nullIfEmpty(): String? = if (this.isNullOrEmpty()) null else this

    private fun Element.srcWithFallback(): String? {
        return src() ?: attrOrNull("data-src") ?: attrOrNull("src")
    }

    private fun extractTagKey(href: String): String? {
        val genreMatch = Regex("""genre=([^&/?]+)""").find(href)
        if (genreMatch != null) return genreMatch.groupValues[1]
        val pattern = Regex("""series-genre/([^/?]+)|series-tag/([^/?]+)""", RegexOption.IGNORE_CASE)
        return pattern.find(href)?.groupValues?.getOrNull(1)?.takeIf { it.isNotEmpty() }
            ?: pattern.find(href)?.groupValues?.getOrNull(2)?.takeIf { it.isNotEmpty() }
    }

    companion object {
        private const val RATING_UNKNOWN = 0f
    }
}
