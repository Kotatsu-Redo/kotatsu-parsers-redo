package org.koitharu.kotatsu.parsers.site.ru

import androidx.collection.ArrayMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.util.*

@MangaSourceParser("ACOMICS", "AComics", "ru", ContentType.COMICS)
internal class AComics(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.ACOMICS, pageSize = 10) {

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.ALPHABETICAL,
        SortOrder.POPULARITY,
    )

    override val configKeyDomain = ConfigKey.Domain("acomics.ru")

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isMultipleTagsSupported = true,
            isSearchSupported = true,
        )

    init {
        paginator.firstPage = 0
        searchPaginator.firstPage = 0
        context.cookieJar.insertCookies(domain, "ageRestrict=18")
    }

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = getOrCreateTagMap().values.toSet(),
        availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
        availableContentRating = EnumSet.of(ContentRating.SAFE, ContentRating.SUGGESTIVE, ContentRating.ADULT),
    )

    override suspend fun getListPage(
        page: Int,
        order: SortOrder,
        filter: MangaListFilter,
    ): List<Manga> {
        val url = buildString {
            append("https://")
            append(domain)
            when {
                !filter.query.isNullOrEmpty() -> {
                    append("/search?keyword=")
                    append(filter.query.urlEncoded())
                    append("&skip=")
                    append(page * pageSize)
                }

                else -> {
                    append("/comics?skip=")
                    append(page * pageSize)
                    append("&sort=")
                    append(
                        when (order) {
                            SortOrder.UPDATED -> "last_update"
                            SortOrder.ALPHABETICAL -> "serial_name"
                            SortOrder.POPULARITY -> "subscr_count"
                            else -> "last_update"
                        },
                    )

                    val ratings = filter.contentRating.ifEmpty { ContentRating.entries }.flatMap {
                        when (it) {
                            ContentRating.SAFE -> listOf(1, 2, 3, 4) // NR, G, PG, PG-13
                            ContentRating.SUGGESTIVE -> listOf(5) // R
                            ContentRating.ADULT -> listOf(6) // NC-17
                        }
                    }
                    for (rating in ratings) {
                        append("&ratings[]=")
                        append(rating)
                    }

                    for (tag in filter.tags) {
                        append("&categories[]=")
                        append(tag.key)
                    }

                    when (filter.states.singleOrNull()) {
                        MangaState.ONGOING -> append("&updatable=yes")
                        MangaState.FINISHED -> append("&updatable=no")
                        else -> Unit
                    }
                }
            }
        }

        return parseMangaList(webClient.httpGet(url).parseHtml())
    }

    private fun parseMangaList(docs: Document): List<Manga> {
        return docs.select("section.serial-card").map {
            val a = it.selectFirstOrThrow("h2.title a")
            val url = a.attrAsRelativeUrl("href") + "/about"
            Manga(
                id = generateStableUid(url),
                url = url,
                title = a.text(),
                altTitles = emptySet(),
                publicUrl = url.toAbsoluteUrl(domain),
                rating = RATING_UNKNOWN,
                contentRating = when {
                    it.selectFirst(".age-rating-label.rating6") != null -> ContentRating.ADULT
                    it.selectFirst(".age-rating-label.rating5") != null -> ContentRating.SUGGESTIVE
                    it.selectFirst(".age-rating-label") != null -> ContentRating.SAFE
                    else -> null
                },
                coverUrl = it.selectFirst("a.cover img")?.attrAsAbsoluteUrlOrNull("data-real-src"),
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                source = source,
            )
        }
    }

    // Manga and page urls used to be absolute. Saved manga keep their ids, and their old urls still work.
    private fun generateStableUid(path: String) = generateUid("https://acomics.ru$path")

    // "/~name" of any link to a comic
    private fun String.toComicPath() = "/" + toRelativeUrl(domain).removePrefix("/").substringBefore('/')

    private var tagCache: ArrayMap<String, MangaTag>? = null
    private val mutex = Mutex()

    private suspend fun getOrCreateTagMap(): Map<String, MangaTag> = mutex.withLock {
        tagCache?.let { return@withLock it }
        val tagMap = ArrayMap<String, MangaTag>()
        val tagElements = webClient.httpGet("https://$domain/comics").parseHtml().select("fieldset.categories label")
        for (el in tagElements) {
            val name = el.text()
            if (name.isEmpty()) continue
            tagMap[name] = MangaTag(
                title = name,
                key = el.selectFirstOrThrow("input").attr("value"),
                source = source,
            )
        }
        tagCache = tagMap
        return@withLock tagMap
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val comicPath = manga.url.toComicPath()
        val doc = webClient.httpGet("$comicPath/about".toAbsoluteUrl(domain)).parseHtml()
        val tagMap = getOrCreateTagMap()
        val tags = doc.select("p.serial-about-badges a.category").mapNotNullToSet { tagMap[it.text()] }
        val credits = doc.selectFirst("p.serial-about-authors")
        val creditNames = credits?.select("a")?.eachText().orEmpty()
        // A translation lists its translators there, and the original author on a line of its own
        val isTranslation = credits?.selectFirst("b")?.text()?.startsWith("Переводчик") == true
        val originalAuthor = doc.selectFirst("p:has(> b:contains(Автор оригинала))")?.ownText()?.trim()
        return manga.copy(
            title = doc.selectFirst("article h1")?.text() ?: manga.title,
            tags = tags,
            description = doc.selectFirst("section.serial-about-text")?.html(),
            authors = if (isTranslation) setOfNotNull(originalAuthor) else creditNames.toSet(),
            chapters = listOf(
                MangaChapter(
                    id = manga.id,
                    title = manga.title,
                    number = 1f,
                    volume = 0,
                    url = "$comicPath/",
                    scanlator = creditNames.joinToString().takeIf { isTranslation && it.isNotEmpty() },
                    uploadDate = 0,
                    branch = null,
                    source = source,
                ),
            ),
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val comicPath = chapter.url.toComicPath()
        val doc = webClient.httpGet("$comicPath/1".toAbsoluteUrl(domain)).parseHtml()
        val totalPages = doc.selectFirstOrThrow("nav.reader-navigator").attr("data-issue-count").toInt()
        return (1..totalPages).map {
            val url = "$comicPath/$it"
            MangaPage(
                id = generateStableUid(url),
                url = url,
                preview = null,
                source = source,
            )
        }
    }

    override suspend fun getPageUrl(page: MangaPage): String {
        val doc = webClient.httpGet(page.url.toAbsoluteUrl(domain)).parseHtml()
        return doc.selectFirstOrThrow("section.reader-issue img.issue").requireSrc()
    }
}
