package org.koitharu.kotatsu.parsers.site.en

import org.json.JSONArray
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.attrAsRelativeUrl
import org.koitharu.kotatsu.parsers.util.attrOrNull
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.json.mapJSON
import org.koitharu.kotatsu.parsers.util.json.unescapeJson
import org.koitharu.kotatsu.parsers.util.mapChapters
import org.koitharu.kotatsu.parsers.util.mapNotNullToSet
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.oneOrThrowIfMany
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.parseRaw
import org.koitharu.kotatsu.parsers.util.src
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy
import org.koitharu.kotatsu.parsers.util.textOrNull
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.toTitleCase
import org.koitharu.kotatsu.parsers.util.urlBuilder
import java.util.Calendar
import java.util.EnumSet

@MangaSourceParser("KDTSCANS", "KdtScans", "en")
internal class KdtScans(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.KDTSCANS, 24) {

    // The site moved from WordPress (MangaThemesia) to a Next.js app. Old manga urls (/<slug>/) still
    // redirect to /series/<slug>/ and chapter urls (/<slug>-chapter-<n>/) are unchanged, so the manga
    // keep the old /<slug>/ url to keep their ids in users' libraries.
    override val configKeyDomain = ConfigKey.Domain("silentquill.net", "www.silentquill.net")

    override fun getRequestHeaders() = super.getRequestHeaders().newBuilder()
        .add("Referer", "https://$domain/")
        .build()

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isSearchWithFiltersSupported = true,
    )

    private val filterOptions = suspendLazy(initializer = ::fetchFilterOptions)

    override suspend fun getFilterOptions(): MangaListFilterOptions = filterOptions.get()

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        if (order == SortOrder.POPULARITY && filter.isEmpty()) {
            // The site has no popularity listing, only the "Top 10 this week" block of the home page.
            if (page > 1) {
                return emptyList()
            }
            val decoded = decodeNextChunks(webClient.httpGet("https://$domain/").parseRaw())
            val start = decoded.indexOf(TOP_WEEK_MARKER)
            if (start < 0) {
                return emptyList()
            }
            val end = decoded.indexOf("\"h3\"", start + TOP_WEEK_MARKER.length).takeIf { it > 0 } ?: decoded.length
            return parseMangaList(decoded.substring(start, end))
        }
        // The search page lists series by latest update, with optional query, genre and status.
        val url = urlBuilder().addPathSegments("search/").apply {
            filter.query?.takeIf { it.isNotBlank() }?.let { addQueryParameter("q", it) }
            filter.tags.oneOrThrowIfMany()?.let { addQueryParameter("genre", it.key) }
            when (filter.states.oneOrThrowIfMany()) {
                MangaState.ONGOING -> addQueryParameter("status", "ongoing")
                MangaState.FINISHED -> addQueryParameter("status", "completed")
                else -> Unit
            }
            addQueryParameter("page", page.toString())
        }.build()
        return parseMangaList(decodeNextChunks(webClient.httpGet(url).parseRaw()))
    }

    private fun parseMangaList(decoded: String): List<Manga> {
        val result = ArrayList<Manga>()
        for (match in SERIES_CARD_REGEX.findAll(decoded)) {
            val slug = match.groupValues[1]
            val block = match.groupValues[2]
            val title = (TITLE_REGEX.find(block) ?: ALT_REGEX.find(block))
                ?.groupValues?.get(1)
                ?.unescapeJsonOrSelf()
                ?.trim()
                ?.nullIfEmpty()
                ?: continue
            val url = "/$slug/"
            result += Manga(
                id = generateUid(url),
                url = url,
                publicUrl = "https://$domain/series/$slug/",
                coverUrl = SRC_REGEX.find(block)?.groupValues?.get(1)?.unescapeJsonOrSelf()?.toAbsoluteUrl(domain),
                title = title,
                altTitles = emptySet(),
                rating = RATING_UNKNOWN,
                tags = emptySet(),
                authors = emptySet(),
                state = parseStatus(STATUS_REGEX.find(block)?.groupValues?.get(1)),
                source = source,
                contentRating = if (isNsfwSource) ContentRating.ADULT else null,
            )
        }
        return result.distinctBy { it.url }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.seriesUrl()).parseHtml()

        val authorsText = doc.selectFirst("div.min-w-0 > p.text-sm")?.textOrNull()
        val authors = authorsText?.split("·", ",")?.mapNotNullToSet { segment ->
            segment.trim().replace(AUTHOR_PREFIX_REGEX, "").trim().nullIfEmpty()
        } ?: manga.authors
        val tags = doc.select("a[href*=/search/?genre=], a[href*=/search?genre=]").mapNotNullToSet { a ->
            val key = a.attr("href").substringAfter("genre=").substringBefore("&").trim()
            val title = a.text().trim()
            if (key.isNotEmpty() && title.isNotEmpty()) MangaTag(title, key, source) else null
        }

        // Chapters are grouped in sections (loose chapters, then volumes), newest first.
        val chapterItems = ArrayList<Pair<Element, Int>>()
        val sections = doc.select("section:has(div.columns-1)")
        if (sections.isNotEmpty()) {
            for (section in sections) {
                val volume = section.selectFirst("div.mb-2 > span")?.text()
                    ?.let { VOLUME_REGEX.find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0
                section.select("div.columns-1 a[href]").forEach { chapterItems += it to volume }
            }
        } else {
            doc.select("div.columns-1 a[href]").forEach { chapterItems += it to 0 }
        }
        val chapters = chapterItems.mapChapters(reversed = true) { i, (a, volume) ->
            val href = a.attrAsRelativeUrl("href")
            val title = a.selectFirst("span.text-base")?.textOrNull() ?: a.text()
            MangaChapter(
                id = generateUid(href),
                url = href,
                title = title,
                number = CHAPTER_NUMBER_REGEX.find(title)?.groupValues?.get(1)?.toFloatOrNull() ?: (i + 1f),
                volume = volume,
                uploadDate = parseRelativeDate(a.selectFirst("span.text-right")?.text()),
                source = source,
                scanlator = null,
                branch = null,
            )
        }

        return manga.copy(
            title = doc.selectFirst("h1")?.textOrNull() ?: manga.title,
            coverUrl = doc.selectFirst("meta[property=og:image]")?.attrOrNull("content")
                ?: doc.selectFirst("button img.object-cover, img.object-cover")?.src()
                ?: manga.coverUrl,
            authors = authors,
            description = doc.selectFirst("div.reader-content")?.textOrNull()
                ?: doc.selectFirst("meta[property=og:description]")?.attrOrNull("content"),
            state = parseStatus(doc.selectFirst("dt:containsOwn(Status) + dd")?.text()) ?: manga.state,
            tags = tags,
            chapters = chapters,
        )
    }

    override suspend fun getRelatedManga(seed: Manga): List<Manga> = emptyList()

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val decoded = decodeNextChunks(webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseRaw())
        val urls = PAGES_REGEX.find(decoded)?.groupValues?.get(1)
            ?.let { runCatching { JSONArray(it) }.getOrNull() }
            ?.mapJSON { it.getString("url") }
            ?: PAGE_IMG_REGEX.findAll(decoded).map { it.groupValues[1] }.toList()
        return urls.map { pageUrl ->
            MangaPage(
                id = generateUid(pageUrl),
                url = pageUrl.toAbsoluteUrl(domain),
                preview = null,
                source = source,
            )
        }
    }

    private suspend fun fetchFilterOptions(): MangaListFilterOptions {
        val decoded = decodeNextChunks(webClient.httpGet("https://$domain/search/").parseRaw())
        val tags = GENRE_OPTION_REGEX.findAll(decoded).mapNotNullTo(LinkedHashSet()) { match ->
            val key = match.groupValues[1]
            val title = match.groupValues[2].replace(TAG_COUNT_REGEX, "").trim()
            if (title.isNotEmpty()) MangaTag(title.toTitleCase(sourceLocale), key, source) else null
        }
        return MangaListFilterOptions(
            availableTags = tags,
            availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
        )
    }

    // Old library entries keep the /<slug>/ url; the site serves the series at /series/<slug>/.
    private fun Manga.seriesUrl(): String {
        val slug = url.trim('/').substringAfterLast('/')
        return "https://$domain/series/$slug/"
    }

    private fun decodeNextChunks(html: String): String {
        val builder = StringBuilder()
        for (match in NEXT_F_PUSH_REGEX.findAll(html)) {
            builder.append(match.groupValues[1].unescapeJsonOrSelf())
        }
        return builder.toString()
    }

    private fun String.unescapeJsonOrSelf(): String = runCatching { unescapeJson() }.getOrDefault(this)

    private fun parseStatus(status: String?): MangaState? = when {
        status == null -> null
        status.contains("ongoing", ignoreCase = true) -> MangaState.ONGOING
        status.contains("completed", ignoreCase = true) -> MangaState.FINISHED
        else -> null
    }

    private fun parseRelativeDate(date: String?): Long {
        val match = date?.let { RELATIVE_DATE_REGEX.find(it.trim().lowercase()) } ?: return 0L
        val amount = match.groupValues[1].toIntOrNull() ?: return 0L
        val unit = match.groupValues[2]
        val calendar = Calendar.getInstance()
        when {
            unit.startsWith("y") -> calendar.add(Calendar.YEAR, -amount)
            unit.startsWith("mo") -> calendar.add(Calendar.MONTH, -amount)
            unit.startsWith("w") -> calendar.add(Calendar.WEEK_OF_YEAR, -amount)
            unit.startsWith("d") -> calendar.add(Calendar.DAY_OF_YEAR, -amount)
            unit.startsWith("h") -> calendar.add(Calendar.HOUR_OF_DAY, -amount)
            unit.startsWith("m") -> calendar.add(Calendar.MINUTE, -amount)
            unit.startsWith("s") -> calendar.add(Calendar.SECOND, -amount)
            else -> return 0L
        }
        return calendar.timeInMillis
    }

    private companion object {
        const val TOP_WEEK_MARKER = "Top 10 this week"
        // Every literal `]` and `}` is escaped: Android's ICU regex engine rejects them bare where the
        // JVM's accepts them, so a pattern that passes the JVM tests can crash the app as the class loads
        val NEXT_F_PUSH_REGEX = Regex("""self\.__next_f\.push\(\[\s*1\s*,\s*"(.*?)"\s*\]\)""", RegexOption.DOT_MATCHES_ALL)
        val SERIES_CARD_REGEX = Regex(""""href":"/series/([^"/]+)/?"(.*?)(?="href":"/series/[^"/]+/?"|\z)""", RegexOption.DOT_MATCHES_ALL)
        val TITLE_REGEX = Regex(""""title":"((?:\\.|[^"\\])*)"""")
        val ALT_REGEX = Regex(""""alt":"((?:\\.|[^"\\])+)"""")
        val SRC_REGEX = Regex(""""src":"((?:\\.|[^"\\])*)"""")
        val STATUS_REGEX = Regex(""""children":"(Completed|Ongoing)"""", RegexOption.IGNORE_CASE)
        val PAGES_REGEX = Regex(""""pages":\s*(\[\{.*?\}\])""")
        val PAGE_IMG_REGEX = Regex(""""url":"(/img/p/[^"]+)"""")
        val GENRE_OPTION_REGEX = Regex(""""value":"([a-zA-Z0-9_-]+)","label":"([^"]+)"""")
        val TAG_COUNT_REGEX = Regex("""\s*\(\d+\)$""")
        val CHAPTER_NUMBER_REGEX = Regex("""(?:Ch\.|Chapter)\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
        val VOLUME_REGEX = Regex("""(?:Volume|Vol\.)\s*(\d+)""", RegexOption.IGNORE_CASE)
        val AUTHOR_PREFIX_REGEX = Regex("""^(?:art\s+by|story\s+by)\s+""", RegexOption.IGNORE_CASE)
        val RELATIVE_DATE_REGEX = Regex("""^(\d+)\s*(years?|yrs?|y|months?|mos?|weeks?|w|days?|d|hours?|hrs?|h|minutes?|mins?|m|seconds?|secs?|s)\b""")
    }
}
