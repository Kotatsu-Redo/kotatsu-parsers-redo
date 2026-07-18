package org.koitharu.kotatsu.parsers.site.id

import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.util.*

@MangaSourceParser("HWAGO", "Hwago", "id")
internal class Hwago(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.HWAGO, pageSize = 24) {

    override val configKeyDomain = ConfigKey.Domain("02.hwago.xyz")

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.RATING,
        SortOrder.ALPHABETICAL,
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isMultipleTagsSupported = true,
            isTagsExclusionSupported = false,
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
            isYearSupported = false,
        )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = fetchAvailableTags(),
        availableStates = EnumSet.of(
            MangaState.ONGOING,
            MangaState.FINISHED,
            MangaState.PAUSED,
        ),
    )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = buildString {
            append("https://")
            append(domain)
            append("/browse?page=")
            append(page)

            filter.query?.let {
                append("&q=")
                append(it.urlEncoded())
            }

            append("&sort=")
            append(
                when (order) {
                    SortOrder.UPDATED -> "latest"
                    SortOrder.POPULARITY -> "popular"
                    SortOrder.RATING -> "rating"
                    SortOrder.ALPHABETICAL -> "az"
                    else -> "latest"
                },
            )

            filter.states.firstOrNull()?.let {
                append("&status=")
                append(
                    when (it) {
                        MangaState.ONGOING -> "ongoing"
                        MangaState.FINISHED -> "completed"
                        MangaState.PAUSED -> "hiatus"
                        else -> return@let
                    },
                )
            }

            if (filter.tags.isNotEmpty()) {
                append("&genre=")
                append(filter.tags.joinToString(",") { it.key })
            }
        }
        return parseMangaList(webClient.httpGet(url).parseHtml())
    }

    private fun parseMangaList(doc: Document): List<Manga> {
        return doc.select(".htg-card").map { div ->
            val coverLink = div.selectFirstOrThrow("a[href^='/comic/']")
            val href = coverLink.attrAsRelativeUrl("href")
            val img = coverLink.selectFirst("img")
            val title = div.selectFirst("h3")?.text().orEmpty()
            val statusText = div.selectFirst("span.capitalize")?.text()?.lowercase().orEmpty()
            Manga(
                id = generateUid(href),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                coverUrl = img?.src(),
                title = title,
                altTitles = emptySet(),
                rating = RATING_UNKNOWN,
                tags = emptySet(),
                authors = emptySet(),
                state = parseState(statusText),
                source = source,
                contentRating = if (isNsfwSource) ContentRating.ADULT else null,
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val fullUrl = manga.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(fullUrl).parseHtml()

        val container = doc.selectFirst("[data-synopsis-id-cls]")

        val synopsisId = container?.attr("data-synopsis-id-cls")
        val desc = synopsisId?.let { id ->
            val el = doc.getElementById(id) ?: return@let null
            val sr = el.attr("data-sr")
            if (sr.isNotEmpty()) {
                try {
                    val decoded = Base64.getDecoder().decode(sr)
                    String(decoded, Charsets.UTF_8)
                } catch (_: Exception) {
                    el.html()
                }
            } else {
                el.html()
            }
        }

        val altTitlesText = doc.selectFirst("p.text-surface-400.mb-4")?.text()
        val statusText = doc.selectFirst("span.text-green-400")?.text()?.lowercase().orEmpty()
        val tags = doc.select("a[href*='/browse?genre=']").mapNotNullToSet { a ->
            val key = a.attr("href").substringAfter("genre=").substringBefore("&").trim()
            if (key.isEmpty()) return@mapNotNullToSet null
            MangaTag(
                key = key,
                title = a.text().toTitleCase(),
                source = source,
            )
        }

        val author = doc.select("div.bg-surface-800\\/50")
            .firstOrNull { it.selectFirst("span.text-surface-500")?.text() == "Author" }
            ?.selectFirst("span.text-surface-100")?.text()

        val chapters = doc.select("[data-chapter]").mapChapters(reversed = true) { i, a ->
            val href = a.attrAsRelativeUrl("href")
            val chapterNum = a.attr("data-chapter").trim().toFloatOrNull() ?: (i + 1f)
            val spans = a.select("span")
            val name = spans.firstOrNull()?.text() ?: "Chapter ${chapterNum.toInt()}"
            val dateText = spans.lastOrNull()?.text()
            MangaChapter(
                id = generateUid(href),
                title = name,
                number = chapterNum,
                volume = 0,
                url = href,
                uploadDate = parseRelativeDate(dateText),
                source = source,
                scanlator = null,
                branch = null,
            )
        }

        return manga.copy(
            title = doc.selectFirst("h1")?.textOrNull() ?: manga.title,
            coverUrl = doc.selectFirst("img[src*='cover']")?.src() ?: manga.coverUrl,
            description = desc,
            altTitles = setOfNotNull(altTitlesText),
            state = parseState(statusText),
            tags = tags,
            authors = setOfNotNull(author),
            chapters = chapters,
            contentRating = if (isNsfwSource) ContentRating.ADULT else null,
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val fullUrl = chapter.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(fullUrl).parseHtml()
        return doc.selectOrThrow("#reader-pages img").map { img ->
            val url = img.requireSrc()
            MangaPage(
                id = generateUid(url),
                url = url,
                preview = null,
                source = source,
            )
        }
    }

    private suspend fun fetchAvailableTags(): Set<MangaTag> {
        val doc = webClient.httpGet("https://$domain/browse").parseHtml()
        return doc.select(".bf-genre-cb").mapNotNullToSet { cb ->
            val value = cb.attr("value").trim()
            if (value.isEmpty()) return@mapNotNullToSet null
            val label = cb.closest("label")?.attr("data-bf-genre-name")?.trim()
                ?: cb.parent()?.text()?.trim()
                ?: value
            MangaTag(
                key = value,
                title = label.toTitleCase(),
                source = source,
            )
        }
    }

    private fun parseState(text: String): MangaState? = when (text.lowercase().trim()) {
        "ongoing" -> MangaState.ONGOING
        "completed" -> MangaState.FINISHED
        "hiatus" -> MangaState.PAUSED
        else -> null
    }

    private fun parseRelativeDate(date: String?): Long {
        if (date.isNullOrBlank()) return 0L
        val d = date.lowercase()
        val number = Regex("""\d+""").find(d)?.value?.toIntOrNull() ?: return 0L
        val cal = Calendar.getInstance()
        return when {
            "detik" in d || "second" in d || "seg" in d ->
                cal.apply { add(Calendar.SECOND, -number) }.timeInMillis
            "menit" in d || "minute" in d || "min" in d || "mins" in d ->
                cal.apply { add(Calendar.MINUTE, -number) }.timeInMillis
            "jam" in d || "hour" in d || "heure" in d || " h" in d ->
                cal.apply { add(Calendar.HOUR, -number) }.timeInMillis
            "hari" in d || "day" in d || "jour" in d || " d " in d ->
                cal.apply { add(Calendar.DAY_OF_MONTH, -number) }.timeInMillis
            "minggu" in d || "week" in d || "semaine" in d ->
                cal.apply { add(Calendar.WEEK_OF_YEAR, -number) }.timeInMillis
            "bulan" in d || "month" in d || "mois" in d ->
                cal.apply { add(Calendar.MONTH, -number) }.timeInMillis
            "tahun" in d || "year" in d || "an" in d ->
                cal.apply { add(Calendar.YEAR, -number) }.timeInMillis
            else -> 0L
        }
    }
}
