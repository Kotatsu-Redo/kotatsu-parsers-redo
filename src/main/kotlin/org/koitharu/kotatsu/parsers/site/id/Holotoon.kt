package org.koitharu.kotatsu.parsers.site.id

import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.util.*

@MangaSourceParser("HOTOON", "Holotoon", "id")
internal class Holotoon(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.HOTOON, 24) {

    override val configKeyDomain = ConfigKey.Domain("v1.holotoon.site")
    
    override val userAgentKey = ConfigKey.UserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:127.0) Gecko/20100101 Firefox/127.0")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.RATING,
        SortOrder.ALPHABETICAL,
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isMultipleTagsSupported = false,
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
        )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = fetchAvailableTags(),
        availableStates = EnumSet.of(
            MangaState.ONGOING,
            MangaState.FINISHED,
            MangaState.PAUSED,
        ),
    )

    init {
        paginator.firstPage = 1
        searchPaginator.firstPage = 1
    }

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    private fun getListUrl(order: SortOrder, filter: MangaListFilter, page: Int): String {
        val sortParam = when (order) {
            SortOrder.POPULARITY -> "popular"
            SortOrder.UPDATED -> "latest"
            SortOrder.ALPHABETICAL -> "az"
            SortOrder.RATING -> "rating"
            else -> "latest"
        }
        return buildString {
            append("https://")
            append(domain)
            append("/browse?sort=")
            append(sortParam)
            filter.query?.let {
                append("&q=")
                append(it.urlEncoded())
            }
            filter.tags.forEach { tag ->
                append("&genre=")
                append(tag.key)
            }
            filter.states.oneOrThrowIfMany()?.let { state ->
                append("&status=")
                append(
                    when (state) {
                        MangaState.ONGOING -> "ongoing"
                        MangaState.FINISHED -> "completed"
                        MangaState.PAUSED -> "hiatus"
                        else -> ""
                    },
                )
            }
            append("&page=")
            append(page.toString())
        }
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = getListUrl(order, filter, page)
        val doc = webClient.httpGet(url).parseHtml()
        
        val grids = doc.select("div.grid:has(a[href^='/comic/'])")
        val targetContainer = grids.lastOrNull() ?: doc
        
        val seen = HashSet<String>()
        return targetContainer.select("a.group[href^='/comic/']").mapNotNull { a ->
            val href = a.attrAsRelativeUrl("href")
            if (!seen.add(href)) return@mapNotNull null
            val img = a.selectFirst("img")
            val statusBadge = a.selectFirst("span.absolute.top-2.right-2")
            val statusText = statusBadge?.text()?.trim()?.lowercase() ?: ""
            Manga(
                id = generateUid(href),
                url = href,
                title = a.selectFirst("h3")?.text()?.trim().orEmpty(),
                altTitles = emptySet(),
                coverUrl = img?.attrOrNull("src"),
                publicUrl = href.toAbsoluteUrl(domain),
                tags = emptySet(),
                state = when (statusText) {
                    "ongoing" -> MangaState.ONGOING
                    "completed" -> MangaState.FINISHED
                    "hiatus" -> MangaState.PAUSED
                    else -> null
                },
                rating = RATING_UNKNOWN,
                authors = emptySet(),
                source = source,
                contentRating = null,
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
        val title = doc.selectFirst("h1")?.text()?.trim() ?: manga.title
        val descElement = doc.selectFirst("div.prose, div[class*=synopsis], div[class*=description], p.text-surface-300, #synopsis-wrapper div[data-sr], div#_850f55")
        val desc = descElement?.attrOrNull("data-sr")?.let {
            try {
                String(Base64.getDecoder().decode(it))
            } catch (e: Exception) {
                null
            }
        } ?: descElement?.html()?.trim()?.nullIfEmpty()
        ?: doc.selectFirst("meta[name=description]")?.attrOrNull("content")
        val statusText = doc.select("span.capitalize, span[class*=status]")
            .firstOrNull { el ->
                val t = el.text().trim().lowercase()
                t == "ongoing" || t == "completed" || t == "hiatus" || t == "dropped"
            }?.text()?.trim()?.lowercase()
        val tagElements = doc.select("a[href*='/browse?genre='], a[href*='genre=']")
        val tags = tagElements.mapNotNullToSet { el ->
            val tagTitle = el.text().trim().nullIfEmpty() ?: return@mapNotNullToSet null
            val key = el.attr("href")
                .substringAfter("genre=")
                .substringBefore("&")
                .trim()
                .ifEmpty { tagTitle.lowercase() }
            MangaTag(
                key = key,
                title = tagTitle.toTitleCase(),
                source = source,
            )
        }
        val authorElements = doc.select("a[href*='/profile/'], span[data-user-href]")
        val authors = if (authorElements.isNotEmpty()) {
            authorElements.mapNotNullToSet { it.text().trim().nullIfEmpty() }
        } else {
            emptySet()
        }
        val ratingText = doc.selectFirst("span.text-yellow-400")?.text()?.trim()
        val ratingFloat = ratingText?.toFloatOrNull() ?: manga.rating

        return manga.copy(
            title = title,
            description = desc ?: "",
            tags = tags.ifEmpty { manga.tags },
            authors = authors.ifEmpty { manga.authors },
            rating = ratingFloat,
            state = when (statusText) {
                "ongoing" -> MangaState.ONGOING
                "completed" -> MangaState.FINISHED
                "hiatus" -> MangaState.PAUSED
                "dropped" -> MangaState.ABANDONED
                else -> manga.state
            },
            chapters = doc.select("a[href^='/read/'][data-chapter]").mapChapters(reversed = true) { i, a ->
                val href = a.attrAsRelativeUrl("href")
                val dataChapter = a.attr("data-chapter")
                val chapterNum = dataChapter.split(" ").firstOrNull()?.toFloatOrNull() ?: (i + 1f)
                val chapterLabel = a.selectFirst("span.font-semibold")?.text()?.trim()
                val chapterSubtitle = a.selectFirst("span.truncate")?.text()?.trim()
                    ?.removePrefix("—")?.trim()?.nullIfEmpty()
                val fullTitle = when {
                    chapterLabel != null && chapterSubtitle != null -> "$chapterLabel - $chapterSubtitle"
                    chapterLabel != null -> chapterLabel
                    else -> chapterSubtitle ?: "Chapter ${chapterNum.toInt()}"
                }
                val dateText = a.selectFirst("span.text-right, span[class*=tabular-nums]:last-child")
                    ?.text()?.trim()
                MangaChapter(
                    id = generateUid(href),
                    title = fullTitle,
                    number = chapterNum,
                    volume = 0,
                    url = href,
                    scanlator = null,
                    uploadDate = parseRelativeDate(dateText),
                    branch = null,
                    source = source,
                )
            },
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
        val images = doc.select("div#reader-pages img, main img[src*='/image/']")
        return images.mapNotNull { img ->
            val url = img.attrOrNull("src")?.nullIfEmpty() ?: return@mapNotNull null
            if (url.contains("/chapter-header/") || url.contains("/chapter-footer/") || 
                url.contains("/covers/") || url.contains("/reactions/") || 
                url.contains("/avatars/") || url.contains("/shop/") || 
                url.contains("/site-logo/")) return@mapNotNull null
            MangaPage(
                id = generateUid(url),
                url = url,
                preview = null,
                source = source,
            )
        }
    }

    private fun parseRelativeDate(text: String?): Long {
        if (text.isNullOrBlank()) return 0L
        val lower = text.trim().lowercase()
        if (lower == "baru saja" || lower == "just now") return System.currentTimeMillis()
        val cal = Calendar.getInstance()
        return when {
            lower.contains("detik") || lower.contains("second") ->
                cal.apply { add(Calendar.SECOND, -extractNumber(lower)) }.timeInMillis
            lower.contains("menit") || lower.contains("minute") ->
                cal.apply { add(Calendar.MINUTE, -extractNumber(lower)) }.timeInMillis
            lower.contains("jam") || lower.contains("hour") ->
                cal.apply { add(Calendar.HOUR, -extractNumber(lower)) }.timeInMillis
            lower.contains("hari") || lower.contains("day") ->
                cal.apply { add(Calendar.DAY_OF_MONTH, -extractNumber(lower)) }.timeInMillis
            lower.contains("minggu") || lower.contains("week") ->
                cal.apply { add(Calendar.WEEK_OF_YEAR, -extractNumber(lower)) }.timeInMillis
            lower.contains("bulan") || lower.contains("month") ->
                cal.apply { add(Calendar.MONTH, -extractNumber(lower)) }.timeInMillis
            lower.contains("tahun") || lower.contains("year") ->
                cal.apply { add(Calendar.YEAR, -extractNumber(lower)) }.timeInMillis
            else -> 0L
        }
    }

    private fun extractNumber(text: String): Int {
        return Regex("(\\d+)").find(text)?.value?.toIntOrNull() ?: 1
    }

    private suspend fun fetchAvailableTags(): Set<MangaTag> {
        val doc = webClient.httpGet("https://$domain/browse").parseHtml()
        return doc.select("select[name='genre'] option").mapNotNullToSet { option: Element ->
            val key = option.attr("value")
            if (key.isEmpty()) return@mapNotNullToSet null
            MangaTag(
                key = key,
                title = option.text().toTitleCase(),
                source = source,
            )
        }
    }
}