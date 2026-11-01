package org.koitharu.kotatsu.parsers.site.en

import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.bitmap.Bitmap
import org.koitharu.kotatsu.parsers.bitmap.Rect
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min

@MangaSourceParser("MANGAGO", "Mangago", "en")
internal class MangagoParser(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.MANGAGO, pageSize = 20), Interceptor {

    override val configKeyDomain = ConfigKey.Domain("www.mangago.me")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.POPULARITY,
        SortOrder.UPDATED,
        SortOrder.NEWEST
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isMultipleTagsSupported = true,
            isTagsExclusionSupported = true,
            isSearchSupported = true,
        )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = getAvailableTags(),
        availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
    )

    private fun getAvailableTags(): Set<MangaTag> = setOf(
        MangaTag("Yaoi", "Yaoi", source),
        MangaTag("Doujinshi", "Doujinshi", source),
        MangaTag("Shounen Ai", "Shounen Ai", source),
        MangaTag("Shoujo", "Shoujo", source),
        MangaTag("Yuri", "Yuri", source),
        MangaTag("Romance", "Romance", source),
        MangaTag("Fantasy", "Fantasy", source),
        MangaTag("Comedy", "Comedy", source),
        MangaTag("Smut", "Smut", source),
        MangaTag("Adult", "Adult", source),
        MangaTag("School Life", "School Life", source),
        MangaTag("Mystery", "Mystery", source),
        MangaTag("One Shot", "One Shot", source),
        MangaTag("Ecchi", "Ecchi", source),
        MangaTag("Shounen", "Shounen", source),
        MangaTag("Martial Arts", "Martial Arts", source),
        MangaTag("Shoujo Ai", "Shoujo Ai", source),
        MangaTag("Supernatural", "Supernatural", source),
        MangaTag("Drama", "Drama", source),
        MangaTag("Action", "Action", source),
        MangaTag("Adventure", "Adventure", source),
        MangaTag("Harem", "Harem", source),
        MangaTag("Historical", "Historical", source),
        MangaTag("Horror", "Horror", source),
        MangaTag("Josei", "Josei", source),
        MangaTag("Mature", "Mature", source),
        MangaTag("Mecha", "Mecha", source),
        MangaTag("Psychological", "Psychological", source),
        MangaTag("Sci-fi", "Sci-fi", source),
        MangaTag("Seinen", "Seinen", source),
        MangaTag("Slice Of Life", "Slice Of Life", source),
        MangaTag("Sports", "Sports", source),
        MangaTag("Gender Bender", "Gender Bender", source),
        MangaTag("Tragedy", "Tragedy", source),
        MangaTag("Bara", "Bara", source),
        MangaTag("Shotacon", "Shotacon", source),
        MangaTag("Webtoons", "Webtoons", source),
    )

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    private val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.ENGLISH)

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        if (!filter.query.isNullOrEmpty()) {
            val results = search(filter.query, page)
            if (page != paginator.firstPage) {
                return results
            }
            // The site matches anywhere in the name, author and summary without ranking, which can bury a title
            // that is exactly the query. Its titles are stored with this label, so asking for that finds them.
            val exact = runCatchingCancellable { search("${filter.query}$YAOI_LABEL", page) }
                .getOrDefault(emptyList())
                .filter { it.title.equals(filter.query, ignoreCase = true) }
            return (exact + results).distinctBy { it.id }
        }

        val url = when (order) {
            SortOrder.NEWEST -> {
                buildString {
                    append("https://")
                    append(domain)
                    append("/list/new/")
                    if (filter.tags.isNotEmpty()) {
                        filter.tags.joinTo(this, ",") { it.key }
                    } else {
                        append("all")
                    }
                    append("/")
                    append(page)
                    append("/")
                }
            }
            else -> {
                buildString {
                    append("https://")
                    append(domain)
                    append("/genre/")
                    if (filter.tags.isNotEmpty()) {
                        filter.tags.joinTo(this, ",") { it.key }
                    } else {
                        append("all")
                    }
                    append("/")
                    append(page)
                    append("/?")
                    val states = filter.states
                    val showFinished = states.isEmpty() || states.contains(MangaState.FINISHED)
                    val showOngoing = states.isEmpty() || states.contains(MangaState.ONGOING)
                    append("f=").append(if (showFinished) "1" else "0")
                    append("&o=").append(if (showOngoing) "1" else "0")
                    append("&sortby=")
                    when (order) {
                        SortOrder.POPULARITY -> append("view")
                        SortOrder.UPDATED -> append("update_date")
                        else -> append("update_date")
                    }
                    append("&e=")
                    if (filter.tagsExclude.isNotEmpty()) {
                        filter.tagsExclude.joinTo(this, ",") { it.key }
                    }
                }
            }
        }

        return parseMangaList(webClient.httpGet(url).parseHtml())
    }

    private fun parseMangaList(doc: Document): List<Manga> {
        return doc.select(".box, .updatesli, .pic_list > li").mapNotNull { element ->
            val linkElement = element.selectFirst(".thm-effect") ?: return@mapNotNull null
            val href = linkElement.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
            val title = linkElement.attr("title").ifEmpty {
                linkElement.selectFirst("h2 a")?.text() ?: return@mapNotNull null
            }
            // Removed uploads are filed under a "DELETE" title, which the site's own search leaves out
            if (title == DELETED_UPLOADS_TITLE && DELETED_UPLOADS_URL_REGEX.containsMatchIn(href)) {
                return@mapNotNull null
            }
            val thumbnailElem = linkElement.selectFirst("img") ?: return@mapNotNull null
            val thumbnailUrl = thumbnailElem.attr("abs:data-src").ifEmpty {
                thumbnailElem.attr("abs:src")
            }

            Manga(
                id = generateUid(href),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                title = title,
                altTitles = emptySet(),
                coverUrl = thumbnailUrl,
                largeCoverUrl = null,
                description = null,
                tags = emptySet(),
                authors = emptySet(),
                state = null,
                rating = RATING_UNKNOWN,
                contentRating = ContentRating.SAFE,
                source = source,
            )
        }.distinctBy { it.url } // desktop pages nest .box inside .pic_list > li, both match
    }

    private suspend fun search(query: String, page: Int): List<Manga> {
        val url = buildString {
            append("https://")
            append(domain)
            append("/r/l_search?name=")
            append(query.urlEncoded())
            append("&page=")
            append(page)
        }
        return parseMangaList(webClient.httpGet(url).parseHtml())
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = loadDetailsPage(manga.url.toAbsoluteUrl(domain))
        val infoBlock = doc.requireElementById("information")

        // The lists show the title without the label the details page adds
        val title = doc.selectFirstOrThrow(".w-title h1").text().removeSuffix(YAOI_LABEL)
        val thumbnail = infoBlock.selectFirstOrThrow("img").absUrl("src")

        var description = infoBlock.selectFirst(".manga_summary")?.let { summary ->
            summary.selectFirst("font")?.remove()
            summary.text()
        }.orEmpty()

        var author: String? = null
        var genres = emptySet<MangaTag>()
        var state: MangaState? = null

        infoBlock.select(".manga_info li, .manga_right tr").forEach { el ->
            when (el.selectFirst("b, label")?.text()?.lowercase()) {
                "alternative:" -> description += "\n\n${el.text()}"
                "status:" -> state = when (el.selectFirstOrThrow("span").text().lowercase()) {
                    "ongoing" -> MangaState.ONGOING
                    "completed" -> MangaState.FINISHED
                    else -> null
                }
                "author(s):", "author:" -> author = el.select("a").joinToString { it.text() }
                "genre(s):" -> genres = el.select("a").mapToSet {
                    MangaTag(
                        key = it.text(),
                        title = it.text(),
                        source = source,
                    )
                }
            }
        }

        val chapters = parseChapterList(doc)

        return manga.copy(
            title = title,
            coverUrl = thumbnail,
            description = description,
            authors = author?.let { setOf(it) } ?: emptySet(),
            tags = genres,
            state = state,
            chapters = chapters,
        )
    }

    private data class ChapterParseData(
        val name: String,
        val url: String,
        val dateUpload: Long,
        val scanlator: String?,
    )

    /**
     * Titles that the site also offers on its partner reader get their chapter links rewritten on a random share of
     * the title pages it serves (about half of them for some titles, in both layouts): every link then points at a
     * partner host, `www.mangago.zone/chapter/84380/2382390/` or `www.youhim.me/chapter/84380/2382390/` (the host
     * picked per link), instead of the site's own reader, `www.mangago.me/read-manga/<slug>/uu/nml_chapter-45/pg-1/`.
     * Nothing in the page ties the two forms together and chapter ids are made from the link, so taking such a page
     * gives every chapter of the title another id for that load, and the app loses the reading position (and the
     * branch it was read in). Ask again until the links are the site's own ones, then make do with the last page.
     */
    private suspend fun loadDetailsPage(url: String): Document {
        var doc = webClient.httpGet(url).parseHtml()
        var attempts = 1
        while (attempts < DETAILS_PAGE_ATTEMPTS && doc.hasPartnerChapterLinks()) {
            doc = webClient.httpGet(url).parseHtml()
            attempts++
        }
        return doc
    }

    private fun Document.chapterRows() = select("table#chapter_table > tbody > tr, table.uk-table > tbody > tr")

    private fun Element.chapterLink(): Element? = selectFirst("a.chico")

    private fun Document.hasPartnerChapterLinks(): Boolean = chapterRows().any { row ->
        row.chapterLink()?.attrAsAbsoluteUrlOrNull("href")?.let(::partnerChapterPath) != null
    }

    /** `/chapter/<title>/<chapter>/` for a link to the partner reader, null for the site's own reader. */
    private fun partnerChapterPath(url: String): String? = PARTNER_CHAPTER_REGEX.find(url)?.groupValues?.get(1)

    /**
     * What a chapter id is made from: the link, without its host when it goes to the partner reader since that
     * host is picked at random on every load. Links to the site's own reader are kept whole, as ids always were.
     */
    private fun chapterKey(url: String): String = partnerChapterPath(url) ?: url

    private fun parseChapterList(doc: Document): List<MangaChapter> {
        val rawChapters = doc.chapterRows()
            .mapNotNull { element ->
                val link = element.chapterLink() ?: return@mapNotNull null
                val url = link.attrAsAbsoluteUrlOrNull("href") ?: return@mapNotNull null
                val name = link.text().trim()

                val dateText = element.select("td:last-child").text().trim()
                val dateUpload = runCatching {
                    dateFormat.parse(dateText)?.time
                }.getOrNull() ?: 0L

                val scanlator = element.selectFirst("td.no a, td.uk-table-shrink a")?.text()?.trim()
                    ?.ifEmpty { null }

                ChapterParseData(name, url, dateUpload, scanlator)
            }
            .reversed()

        return buildChapterList(rawChapters)
    }

    private fun buildChapterList(chapters: List<ChapterParseData>): List<MangaChapter> {
        val canonicalScanlators = LinkedHashMap<String, String>()
        val resolvedChapters = chapters.distinctBy { it.url }.map { chapter ->
            // Only the uploader column names a group: the title suffix is the chapter name ("Chapter 109", "END"...)
            val name = chapter.scanlator
                ?.replace(WHITESPACE_REGEX, " ")
                ?.trim()
                ?.takeIf(String::isNotEmpty)
            val canonicalName = name?.let {
                canonicalScanlators.getOrPut(it.lowercase(Locale.ROOT)) { it }
            }
            chapter.copy(scanlator = canonicalName)
        }
        val rawGroups = resolvedChapters.groupByTo(LinkedHashMap()) { it.scanlator ?: UNKNOWN_SCANLATOR }
        // Uploaders without any numbered chapter (notices, announcements) are not worth a branch
        val groups = LinkedHashMap<String, MutableList<ChapterParseData>>()
        for ((name, group) in rawGroups) {
            val key = if (group.none { extractChapterNumber(it.name) != null }) UNKNOWN_SCANLATOR else name
            groups.getOrPut(key) { ArrayList() }.addAll(group)
        }
        if (groups.size <= 1) {
            return buildScanlatorBranch(selectChapters(resolvedChapters), null, emptyList(), emptyMap())
        }
        // Each chapter keeps its historical id in the branch of its uploader
        val primaryBranch = HashMap<String, String>()
        groups.forEach { (branch, group) -> group.forEach { primaryBranch[it.url] = branch } }

        val selected = groups.mapValuesTo(LinkedHashMap()) { (_, group) -> selectChapters(group) }
        // Official releases are often uploaded by several accounts: they are also gathered in a single branch
        val official = resolvedChapters.filter(::isOfficial)
        val officialBranch = if (official.isNotEmpty()) {
            OFFICIAL_BRANCH to SelectedChapters(selectChapters(official).regular, emptyList())
        } else {
            null
        }

        // Missing chapters of a branch are filled from official releases first, then from the largest uploaders
        val branches = listOfNotNull(officialBranch) + selected.entries
            .sortedByDescending { it.value.regular.size }
            .map { it.key to it.value }
        val donors = branches.map { (branch, chapters) -> branch to chapters.regular }

        return branches
            .map { (branch, chapters) -> buildScanlatorBranch(chapters, branch, donors, primaryBranch) }
            .flatten()
    }

    private class SelectedChapters(
        val regular: Map<Float, ChapterParseData>,
        val special: List<ChapterParseData>,
    )

    private fun selectChapters(chapters: List<ChapterParseData>): SelectedChapters {
        val regularChapters = LinkedHashMap<Float, ChapterParseData>()
        val specialChapters = mutableListOf<ChapterParseData>()

        for (chapter in chapters) {
            val chapterNum = extractChapterNumber(chapter.name)
            if (chapterNum != null) {
                val existing = regularChapters[chapterNum]
                if (
                    existing == null ||
                    chapter.dateUpload > existing.dateUpload ||
                    chapter.dateUpload == existing.dateUpload && chapter.name.length > existing.name.length
                ) {
                    regularChapters[chapterNum] = chapter
                }
            } else {
                specialChapters.add(chapter)
            }
        }
        return SelectedChapters(regularChapters, specialChapters)
    }

    private fun isOfficial(chapter: ChapterParseData): Boolean =
        chapter.scanlator?.contains(OFFICIAL_REGEX) == true ||
            extractTitleSuffix(chapter.name)?.contains(OFFICIAL_REGEX) == true

    private fun buildScanlatorBranch(
        chapters: SelectedChapters,
        branch: String?,
        donors: List<Pair<String, Map<Float, ChapterParseData>>>,
        primaryBranch: Map<String, String>,
    ): List<MangaChapter> {
        // Own uploads first, then only the missing numbers are taken from other branches
        val regularChapters = HashMap<Float, ChapterParseData>(chapters.regular)
        for ((donorBranch, donorChapters) in donors) {
            if (donorBranch == branch) continue
            donorChapters.forEach { (number, chapter) ->
                regularChapters.putIfAbsent(number, chapter)
            }
        }
        val specialChapters = chapters.special

        val result = mutableListOf<MangaChapter>()

        val sortedRegular = regularChapters.entries.sortedBy { it.key }
        for ((chapterNum, chapter) in sortedRegular) {
            result.add(
                MangaChapter(
                    // The same chapter can appear in several branches, ids must stay unique per manga
                    id = chapterId(chapter, branch, primaryBranch),
                    url = chapter.url,
                    title = chapter.name,
                    number = chapterNum,
                    volume = 0,
                    uploadDate = chapter.dateUpload,
                    scanlator = chapter.scanlator,
                    branch = branch,
                    source = source,
                ),
            )
        }

        val baseSpecialNumber = (regularChapters.keys.maxOrNull() ?: 0f) + 10000f
        for ((index, chapter) in specialChapters.withIndex()) {
            result.add(
                MangaChapter(
                    id = generateUid(chapterKey(chapter.url)),
                    url = chapter.url,
                    title = chapter.name,
                    number = baseSpecialNumber + index,
                    volume = 0,
                    uploadDate = chapter.dateUpload,
                    scanlator = chapter.scanlator,
                    branch = branch,
                    source = source,
                ),
            )
        }

        return result
    }

    private fun chapterId(chapter: ChapterParseData, branch: String?, primaryBranch: Map<String, String>): Long {
        val primary = primaryBranch[chapter.url]
        val key = chapterKey(chapter.url)
        return if (primary == null || primary == branch) {
            generateUid(key)
        } else {
            generateUid("$key#$branch")
        }
    }

    private fun extractTitleSuffix(title: String): String? {
        // Extract suffix like "Official" from "Ch.40 : Official" or "Good Translations" from "Ch.41 : Good Translations"
        val regex = Regex("""(?:ch\.?|chapter)\s*\d+(?:\.\d+)?\s*[:\-]\s*(.+)""", RegexOption.IGNORE_CASE)
        return regex.find(title)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun extractChapterNumber(title: String): Float? {
        return try {
            val regex = Regex("""(?:ch\.?|chapter|vol\.?\s*\d+\s+ch\.?)\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
            val match = regex.find(title)
            match?.groupValues?.get(1)?.toFloat()
        } catch (e: Exception) {
            null
        }
    }

    // Cache for deobfuscated JS to avoid re-fetching in getPageUrl
    // Map of chapterJsUrl -> Pair(deobfuscatedJS, timestamp)
    private val jsCache = mutableMapOf<String, Pair<String, Long>>()
    private val maxCacheTime = 1000 * 60 * 5 // 5 minutes

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val fullUrl = chapter.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(fullUrl).parseHtml()

        // Check for mobile mode FIRST (like Mihon does)
        // Mobile mode has a page dropdown - images load 5 at a time per batch
        val pageDropdown = doc.select("div.controls ul#dropdown-menu-page")
        if (pageDropdown.isNotEmpty()) {
            val pagesCount = pageDropdown.select("li").size

            // Detect URL format and build batch URL generator
            // Format 1: .../chapter/xxx/yyy/1/ -> .../chapter/xxx/yyy/6/
            // Format 2: .../pg-1/ -> .../pg-6/
            // Note: Large numbers (like 2115696) are chapter IDs, not page numbers
            val cleanUrl = fullUrl.removeSuffix("/")
            val lastSegment = cleanUrl.substringAfterLast("/")

            val isPgFormat = lastSegment.startsWith("pg-")
            // Only treat as page number if it's a small number (< 1000), large numbers are chapter IDs
            val pageNumber = lastSegment.toIntOrNull()
            val isPageNumber = pageNumber != null && pageNumber < 1000

            val baseUrl: String
            val buildBatchUrl: (Int) -> String

            if (isPgFormat) {
                // Format: .../pg-1/ -> extract base and use pg-N
                baseUrl = cleanUrl.substringBeforeLast("/")
                buildBatchUrl = { batchNum -> "$baseUrl/pg-$batchNum/" }
            } else if (isPageNumber) {
                // URL ends with a small page number, replace it
                baseUrl = cleanUrl.substringBeforeLast("/")
                buildBatchUrl = { batchNum -> "$baseUrl/$batchNum/" }
            } else {
                // No page number in URL (last segment is chapter ID or text), append /N/
                baseUrl = cleanUrl
                buildBatchUrl = { batchNum -> "$baseUrl/$batchNum/" }
            }

            // Mobile mode loads images in batches of 5
            // We need to fetch each batch URL to get all images
            val batchSize = 5
            val allImages = mutableListOf<String>()
            var batchStart = 1

            // Get JS and cols from the first document for descrambling
            val js = getDeobfuscatedJS(doc)
            val cols = getColsFromDoc(doc) ?: ""

            while (allImages.size < pagesCount) {
                val batchUrl = buildBatchUrl(batchStart)
                val batchDoc = if (batchStart == 1) doc else webClient.httpGet(batchUrl).parseHtml()
                val batchImages = decryptImageList(batchDoc, batchUrl)

                if (batchImages.isEmpty()) {
                    break
                }

                allImages.addAll(batchImages)
                batchStart += batchSize

                // Safety check to prevent infinite loops
                if (batchStart > pagesCount + batchSize) {
                    break
                }
            }

            // Return pages with resolved image URLs
            return allImages.take(pagesCount).mapIndexed { index, imageUrl ->
                val url = if (imageUrl.contains("cspiclink") && js != null) {
                    val descramblingKey = getDescramblingKey(js, imageUrl)
                    "$imageUrl#desckey=$descramblingKey&cols=$cols"
                } else {
                    imageUrl
                }

                MangaPage(
                    id = generateUid("$fullUrl-$index"),
                    url = url,
                    preview = null,
                    source = source,
                )
            }
        }

        // Desktop mode - all images are in imgsrcs, decrypt them all at once
        val imgsrcsScript = doc.selectFirst("script:containsData(imgsrcs)")?.html()

        if (imgsrcsScript != null) {
            val images = decryptImageList(doc, fullUrl)
            val cols = getColsFromDoc(doc) ?: ""
            val js = getDeobfuscatedJS(doc) ?: throw Exception("Could not get JS")

            return images.mapIndexed { index, imageUrl ->
                val url = if (imageUrl.contains("cspiclink")) {
                    val descramblingKey = getDescramblingKey(js, imageUrl)
                    "$imageUrl#desckey=$descramblingKey&cols=$cols"
                } else {
                    imageUrl
                }

                if (url.isBlank()) {
                    throw Exception("Final image URL at index $index is blank after processing")
                }

                MangaPage(
                    id = generateUid("$fullUrl-$index"),
                    url = url,
                    preview = null,
                    source = source,
                )
            }
        }

        throw Exception("Could not find pages")
    }

    override suspend fun getPageUrl(page: MangaPage): String {
        // URLs are already resolved in getPages() for both desktop and mobile modes
        if (page.url.isBlank()) {
            throw Exception("Page URL is blank in getPageUrl")
        }
        return page.url
    }

    override suspend fun getRelatedManga(seed: Manga): List<Manga> = emptyList()

    // --- Decryption & Helper Functions ---

    private suspend fun decryptImageList(doc: Document, sourceUrl: String): List<String> {
        val imgsrcsScript = doc.selectFirst("script:containsData(imgsrcs)")?.html()
            ?: throw Exception("Could not find imgsrcs")

        val imgsrcRaw = IMG_SRCS_REGEX.find(imgsrcsScript)?.groupValues?.get(1)
            ?: throw Exception("Could not extract imgsrcs")

        val imgsrcs = context.decodeBase64(imgsrcRaw)

        val deobfChapterJs = getDeobfuscatedJS(doc) ?: throw Exception("Could not deobfuscate JS")

        val key = findHexEncodedVariable(deobfChapterJs, "key").decodeHex()
        val iv = findHexEncodedVariable(deobfChapterJs, "iv").decodeHex()

        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        val keySpec = SecretKeySpec(key, "AES")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(iv))
        val decryptedBytes = cipher.doFinal(imgsrcs)

        var imageList = String(decryptedBytes, Charsets.UTF_8).trimEnd('\u0000')
        imageList = unscrambleImageList(imageList, deobfChapterJs)

        return imageList.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { url ->
                // Hostnames with underscores (e.g., iweb_5.mangapicgallery.com) cause SSL errors on Android
                // Use HTTP instead to bypass the issue
                if (url.startsWith("https://") && url.contains("/_") || url.contains("https://iweb_")) {
                    url.replaceFirst("https://", "http://")
                } else {
                    url
                }
            }
    }

    private suspend fun getDeobfuscatedJS(doc: Document): String? {
        val chapterJsUrl = doc.select("script[src*=chapter.js]").firstOrNull()?.absUrl("src") ?: return null

        val now = System.currentTimeMillis()
        val cached = jsCache[chapterJsUrl]
        if (cached != null && now - cached.second < maxCacheTime) {
            return cached.first
        }

        val obfuscatedChapterJs = webClient.httpGet(chapterJsUrl).parseRaw()
        val deobf = deobfuscateSoJsonV4(obfuscatedChapterJs)

        jsCache[chapterJsUrl] = Pair(deobf, now)
        return deobf
    }

    private fun getColsFromDoc(doc: Document): String? {
        // Get cols from cached JS for the current chapter's script URL
        val chapterJsUrl = doc.select("script[src*=chapter.js]").firstOrNull()?.absUrl("src")
        val cached = if (chapterJsUrl != null) jsCache[chapterJsUrl] else null
        val js = cached?.first ?: return null
        return COLS_REGEX.find(js)?.groupValues?.get(1)
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        // Taken from the request as sent: scrambled pages get redirected to another host (iweb6 to iweb7), and
        // the request a redirect ends on has no fragment any more
        val fragment = chain.request().url.fragment
        val response = chain.proceed(chain.request())

        if (fragment == null || !fragment.contains("desckey=") || !response.isSuccessful) {
            return response
        }

        return context.redrawImageResponse(response) { bitmap ->
            val key = fragment.substringAfter("desckey=").substringBefore("&")
            val cols = fragment.substringAfter("cols=").toIntOrNull() ?: return@redrawImageResponse bitmap
            unscrambleImage(bitmap, key, cols)
        }
    }

    private fun unscrambleImage(bitmap: Bitmap, key: String, cols: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        val result = context.createBitmap(width, height)

        val unitWidth = width / cols
        val unitHeight = height / cols

        val keyArray = key.split("a")

        for (idx in 0 until cols * cols) {
            val keyval = keyArray.getOrNull(idx)?.takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0

            val heightY = keyval / cols
            val dy = heightY * unitHeight
            val dx = (keyval - heightY * cols) * unitWidth

            val widthY = idx / cols
            val sy = widthY * unitHeight
            val sx = (idx - widthY * cols) * unitWidth

            val w = min(unitWidth, width - dx)
            val h = min(unitHeight, height - dy)

            val srcRect = Rect(sx, sy, sx + w, sy + h)
            val dstRect = Rect(dx, dy, dx + w, dy + h)

            result.drawBitmap(bitmap, srcRect, dstRect)
        }

        return result
    }

    private fun deobfuscateSoJsonV4(jsf: String): String {
        if (!jsf.startsWith("['sojson.v4']")) {
            // If it's not sojson.v4, maybe it's already deobfuscated or a different obfuscation?
            // Based on Mihon code, it expects sojson.v4
            // If the site changed, this might crash, but we try to parse as is or throw
            // For now, let's assume if it starts differently, it might not need deobfuscation or format changed.
            // But given the error, we should stick to the logic.
            // If the format changed, this regex logic might need update.
            // However, let's return the string if it looks like JS code?
            // For safety, throw if header mismatch to alert user.
            throw Exception("Obfuscated code header mismatch. Expected sojson.v4")
        }

        val splitRegex = Regex("[a-zA-Z]+")
        val args = jsf.substring(240, jsf.length - 59).split(splitRegex)

        return args.map { it.toInt().toChar() }.joinToString("")
    }

    private fun findHexEncodedVariable(input: String, variable: String): String {
        val regex = Regex("""var $variable\s*=\s*CryptoJS\.enc\.Hex\.parse\("([0-9a-zA-Z]+)"\)""")
        return regex.find(input)?.groupValues?.get(1)
            ?: throw Exception("Could not find variable: $variable")
    }

    private fun String.decodeHex(): ByteArray {
        check(length % 2 == 0) { "Must have an even length" }
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun unscrambleImageList(imageList: String, js: String): String {
        var imgList = imageList

        val keyLocations = KEY_LOCATION_REGEX.findAll(js)
            .map { it.groupValues[1].toInt() }
            .distinct()
            .sorted()
            .toList()

        if (keyLocations.isEmpty()) {
            return imgList
        }

        // Try to extract the unscramble key by parsing characters at key locations as integers.
        // If any character is NOT a digit, it means the image list is already unscrambled.
        val unscrambleKey = try {
            keyLocations.map { loc ->
                if (loc >= imgList.length) {
                    throw NumberFormatException("Position $loc is beyond string length")
                }
                // This will throw NumberFormatException if the character is not a digit
                imgList[loc].toString().toInt()
            }
        } catch (e: NumberFormatException) {
            // Characters at key positions are not digits, meaning the list is already unscrambled
            return imgList
        }

        // Remove characters at key positions, accounting for index shifts
        keyLocations.forEachIndexed { idx, loc ->
            imgList = imgList.removeRange((loc - idx)..(loc - idx))
        }

        imgList = imgList.unscramble(unscrambleKey)

        return imgList
    }

    private fun String.unscramble(keys: List<Int>): String {
        var s = this
        keys.reversed().forEach { key ->
            for (i in s.length - 1 downTo key) {
                if (i % 2 != 0) {
                    val sourceIdx = i - key
                    if (sourceIdx >= 0) {
                        val temp = s[sourceIdx]
                        s = s.substring(0, sourceIdx) + s[i] + s.substring(sourceIdx + 1)
                        s = s.substring(0, i) + temp + s.substring(i + 1)
                    }
                }
            }
        }
        return s
    }

    /**
     * The key `renImg` in chapter.js picks for an image: from a table of hashes looked up in the file name, scrambled
     * for two more hashes, a default one otherwise. Read here instead of running that code in a WebView, which would
     * load the site once per page before the reader can open, and returns its result as a quoted JSON string.
     */
    private fun getDescramblingKey(deobfChapterJs: String, imageUrl: String): String {
        val script = deobfChapterJs
            .substringAfter("var renImg = function(img,width,height,id){", "")
            .substringBefore("key = key.split(", "")

        var key = DEFAULT_IMAGE_KEY_REGEX.find(script)?.groupValues?.get(1)
            ?: throw Exception("Failed to find the image key in chapter.js")

        IMAGE_KEY_TABLE_REGEX.findAll(script).forEach { entry ->
            if (entry.groupValues[1] in imageUrl) {
                key = entry.groupValues[2]
            }
        }

        SCRAMBLED_IMAGE_KEYS_REGEX.find(script)?.let { keys ->
            val (firstHash, secondHash, firstKey, secondKey) = keys.destructured
            when {
                firstHash in imageUrl -> key = unscrambleImageList(firstKey, deobfChapterJs)
                secondHash in imageUrl -> key = unscrambleImageList(secondKey, deobfChapterJs)
            }
        }

        return key
    }

    private companion object {
        private val IMG_SRCS_REGEX = Regex("""var imgsrcs\s*=\s*['"]([a-zA-Z0-9+=/]+)['"]""")
        private val COLS_REGEX = Regex("""var\s*widthnum\s*=\s*heightnum\s*=\s*(\d+);""")
        private val KEY_LOCATION_REGEX = Regex("""str\.charAt\(\s*(\d+)\s*\)""")
        private val DEFAULT_IMAGE_KEY_REGEX = Regex("""var\s+key\s*=\s*"([0-9a]+)"""")
        private val IMAGE_KEY_TABLE_REGEX = Regex("""_imgkeys_\["([0-9a-f]+)"]\s*=\s*"([0-9a]+)"""")
        private val SCRAMBLED_IMAGE_KEYS_REGEX = Regex(
            """indexOf\("([0-9a-f]+)"\)>0\|\|img\.src\.indexOf\("([0-9a-f]+)"\)>0\)\s*\{\s*var\s+\w+\s*=\s*img\.src\.indexOf\("[0-9a-f]+"\)>0\?"([0-9a]+)":"([0-9a]+)"""",
        )
        private val WHITESPACE_REGEX = Regex("""\s+""")
        private const val UNKNOWN_SCANLATOR = "Unknown"
        private const val OFFICIAL_BRANCH = "Official"
        private val OFFICIAL_REGEX = Regex("""\bofficial\b""", RegexOption.IGNORE_CASE)
        private val PARTNER_CHAPTER_REGEX = Regex("""^https?://[^/]+(/chapter/\d+/\d+/?)$""")
        private const val DETAILS_PAGE_ATTEMPTS = 5
        private const val YAOI_LABEL = " (Yaoi)"
        private const val DELETED_UPLOADS_TITLE = "DELETE"
        private val DELETED_UPLOADS_URL_REGEX = Regex("""/read-manga/delete_\d+/?$""")
    }
}
