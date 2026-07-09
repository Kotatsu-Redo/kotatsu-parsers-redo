@file:OptIn(org.koitharu.kotatsu.parsers.InternalParsersApi::class)

package org.koitharu.kotatsu.parsers.site.all

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.jsoup.Jsoup
import org.koitharu.kotatsu.parsers.InternalParsersApi
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import java.util.EnumSet

/**
 * MangaFire (mangafire.to) — 基于新 REST API。
 *
 * 单一源，所有语言翻译混合展示，按语言分 branch。
 *
 * API 端点:
 * - GET /api/titles        — 列表/搜索
 * - GET /api/titles/{hid}  — 详情
 * - GET /api/titles/{hid}/chapters — 章节列表（按 language 参数过滤）
 * - GET /api/chapters/{id} — 页面列表
 * - GET /api/tags          — 标签搜索（作者/画师）
 *
 * 参考: keiyoushi/extensions-source commit f91a65fb3
 */
@MangaSourceParser("MANGAFIRE", "MangaFire")
internal class MangaFireParser(context: MangaLoaderContext) : PagedMangaParser(
    context = context,
    source = MangaParserSource.MANGAFIRE,
    pageSize = 50,
) {

    override val configKeyDomain = ConfigKey.Domain("mangafire.to")

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("User-Agent", config[userAgentKey])
        .add("Referer", "https://$domain/")
        .add("Accept", "application/json")
        .build()

    // ============================== Sort Orders ==============================

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.POPULARITY_WEEK,
        SortOrder.POPULARITY_MONTH,
        SortOrder.RATING,
        SortOrder.NEWEST,
        SortOrder.ALPHABETICAL,
        SortOrder.ALPHABETICAL_DESC,
        SortOrder.RELEVANCE,
    )

    override val defaultSortOrder: SortOrder = SortOrder.RELEVANCE

    // ============================== Filter Capabilities ==============================

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isMultipleTagsSupported = true,
            isTagsExclusionSupported = true,
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
            isYearRangeSupported = true,
            isAuthorSearchSupported = true,
        )

    override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions(
        availableTags = genres.toSet(),
        availableStates = EnumSet.of(
            MangaState.ONGOING,
            MangaState.FINISHED,
            MangaState.PAUSED,
            MangaState.ABANDONED,
            MangaState.UPCOMING,
        ),
    )

    // ============================== List / Search ==============================

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        var authorId: String? = null
        if (!filter.author.isNullOrEmpty()) {
            authorId = resolveAuthorId(filter.author)
            if (authorId == null) {
                return emptyList()
            }
        }

        val url = "https://$domain/api/titles".toHttpUrl().newBuilder().apply {
            if (!filter.query.isNullOrEmpty()) {
                addQueryParameter("keyword", filter.query)
            }

            addQueryParameter("page", page.toString())
            addQueryParameter("limit", pageSize.toString())

            val (sortField, sortDir) = order.toApiSort()
            addQueryParameter("order[$sortField]", sortDir)

            if (filter.tags.isNotEmpty()) {
                filter.tags.forEach { tag ->
                    addQueryParameter("genres_in[]", tag.key)
                }
            }
            if (filter.tagsExclude.isNotEmpty()) {
                filter.tagsExclude.forEach { tag ->
                    addQueryParameter("genres_ex[]", tag.key)
                }
            }
            addQueryParameter("genres_mode", "or")

            if (filter.states.isNotEmpty()) {
                filter.states.forEach { state ->
                    val apiState = state.toApiStatus()
                    if (apiState != null) {
                        addQueryParameter("statuses[]", apiState)
                    }
                }
            }

            if (filter.yearFrom != YEAR_UNKNOWN) {
                addQueryParameter("year_from", filter.yearFrom.toString())
            }
            if (filter.yearTo != YEAR_UNKNOWN) {
                addQueryParameter("year_to", filter.yearTo.toString())
            }

            if (authorId != null) {
                addQueryParameter("authors[]", authorId)
            }
        }.build()

        val json = webClient.httpGet(url).parseJson()
        val items = json.optJSONArray("items") ?: return emptyList()

        return items.mapJSON { item -> item.parseMangaListItem() }
    }

    private fun JSONObject.parseMangaListItem(): Manga {
        val hid = getString("hid")
        val slug = optString("slug", "").takeIf { it.isNotEmpty() }
        val title = getString("title")
        val poster = optJSONObject("poster")
        val coverUrl = poster?.let {
            it.optString("large", "").takeIf { s -> s.isNotEmpty() }
                ?: it.optString("medium", "").takeIf { s -> s.isNotEmpty() }
                ?: it.optString("small", "").takeIf { s -> s.isNotEmpty() }
        }
        val mangaUrl = if (slug != null) "/title/$hid-$slug" else "/title/$hid"

        return Manga(
            id = generateUid(mangaUrl),
            title = title,
            altTitles = emptySet(),
            url = mangaUrl,
            publicUrl = "https://$domain$mangaUrl",
            rating = RATING_UNKNOWN,
            contentRating = null,
            coverUrl = coverUrl,
            largeCoverUrl = null,
            description = null,
            tags = emptySet(),
            state = null,
            authors = emptySet(),
            source = source,
        )
    }

    // ============================== Details ==============================

    override suspend fun getDetails(manga: Manga): Manga {
        val hid = getHid(manga.url)
        val json = webClient.httpGet("https://$domain/api/titles/$hid").parseJson()
        val data = json.getJSONObject("data")

        val title = data.getString("title")
        val poster = data.optJSONObject("poster")
        val coverUrl = poster?.let {
            it.optString("large", "").takeIf { s -> s.isNotEmpty() }
                ?: it.optString("medium", "").takeIf { s -> s.isNotEmpty() }
                ?: it.optString("small", "").takeIf { s -> s.isNotEmpty() }
        }
        val synopsisHtml = data.optString("synopsisHtml", "").takeIf { it.isNotEmpty() }
        val status = data.optString("status", "").takeIf { it.isNotEmpty() }

        val authors = data.optJSONArray("authors")?.mapJSON { it.getString("title") }?.toSet().orEmpty()
        val artists = data.optJSONArray("artists")?.mapJSON { it.getString("title") }?.toSet().orEmpty()

        val genreTags = data.optJSONArray("genres")?.mapJSONNotNull { genre ->
            val genreTitle = genre.getString("title")
            MangaTag(
                title = genreTitle.replaceFirstChar { it.uppercase() },
                key = genreTitle,
                source = source,
            )
        }.orEmpty()

        val themeTags = data.optJSONArray("themes")?.mapJSONNotNull { theme ->
            val themeTitle = theme.getString("title")
            MangaTag(
                title = themeTitle.replaceFirstChar { it.uppercase() },
                key = themeTitle,
                source = source,
            )
        }.orEmpty()

        val allTags = (genreTags + themeTags).toSet()
        val state = status?.let { parseStatus(it) }
        val description = synopsisHtml?.let { Jsoup.parseBodyFragment(it).text() }
        val chapters = loadAllChapters(manga.url)

        return manga.copy(
            title = title,
            coverUrl = coverUrl ?: manga.coverUrl,
            largeCoverUrl = coverUrl,
            description = description ?: manga.description,
            tags = if (allTags.isNotEmpty()) allTags else manga.tags,
            state = state ?: manga.state,
            authors = (authors + artists).ifEmpty { manga.authors },
            chapters = chapters,
            contentRating = manga.contentRating ?: ContentRating.SAFE,
        )
    }

    // ============================== Chapters ==============================

    /**
     * 遍历所有语言，为每个语言加载章节，按语言名分 branch。
     * 没有章节的语言（API 返回空或错误）被跳过。
     */
    private suspend fun loadAllChapters(mangaUrl: String): List<MangaChapter> {
        val allChapters = mutableListOf<MangaChapter>()
        for (lang in allLanguages) {
            val langChapters = loadChapters(mangaUrl, lang)
            if (langChapters.isNotEmpty()) {
                allChapters.addAll(langChapters)
            }
        }
        return allChapters
    }

    private suspend fun loadChapters(mangaUrl: String, langCode: String): List<MangaChapter> {
        val hid = getHid(mangaUrl)
        val chapters = mutableListOf<MangaChapter>()
        var page = 1
        var lastPage = 1
        val branchName = langCodeToName[langCode] ?: langCode

        do {
            val url = "https://$domain/api/titles/$hid/chapters".toHttpUrl().newBuilder()
                .addQueryParameter("language", langCode)
                .addQueryParameter("sort", "number")
                .addQueryParameter("order", "asc")
                .addQueryParameter("page", page.toString())
                .addQueryParameter("limit", "200")
                .build()

            val json = runCatchingCancellable { webClient.httpGet(url).parseJson() }.getOrNull() ?: break
            val items = json.optJSONArray("items") ?: break
            val meta = json.optJSONObject("meta")
            lastPage = meta?.optInt("lastPage", 1) ?: 1

            items.mapJSON { ch ->
                val id = ch.getInt("id")
                val number = ch.getDouble("number").toFloat()
                val name = ch.optString("name", "").takeIf { it.isNotEmpty() }
                val createdAt = ch.getLongOrDefault("createdAt", 0L)

                val chapterUrl = "$mangaUrl/$id-chapter-${number.toDisplayString()}-$langCode"

                chapters.add(
                    MangaChapter(
                        id = generateUid(chapterUrl),
                        title = buildString {
                            append("Ch. ")
                            append(number.toDisplayString())
                            if (name != null) {
                                append(" - ")
                                append(name)
                            }
                        },
                        number = number,
                        volume = 0,
                        url = chapterUrl,
                        scanlator = null,
                        uploadDate = createdAt * 1000L,
                        branch = branchName,
                        source = source,
                    ),
                )
            }

            page++
        } while (page <= lastPage)

        return chapters
    }

    // ============================== Pages ==============================

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val chapterId = chapter.url.substringAfterLast("/").substringBefore("-")
        val json = webClient.httpGet("https://$domain/api/chapters/$chapterId").parseJson()
        val data = json.getJSONObject("data")
        val pages = data.getJSONArray("pages")

        return pages.mapJSONIndexed { index, page ->
            val url = page.getString("url")
            MangaPage(
                id = generateUid(url),
                url = url,
                preview = null,
                source = source,
            )
        }
    }

    // ============================== Utilities ==============================

    private fun getHid(url: String): String {
        val lastPart = url.removeSuffix("/").substringAfterLast("/")
        return when {
            lastPart.contains(".") -> lastPart.substringAfterLast(".")
            lastPart.contains("-") -> lastPart.substringBefore("-")
            else -> lastPart
        }
    }

    private fun SortOrder.toApiSort(): Pair<String, String> = when (this) {
        SortOrder.UPDATED -> "chapter_updated_at" to "desc"
        SortOrder.POPULARITY -> "views_total" to "desc"
        SortOrder.POPULARITY_WEEK -> "views_7d" to "desc"
        SortOrder.POPULARITY_MONTH -> "views_30d" to "desc"
        SortOrder.RATING -> "score" to "desc"
        SortOrder.NEWEST -> "created_at" to "desc"
        SortOrder.ALPHABETICAL -> "title" to "asc"
        SortOrder.ALPHABETICAL_DESC -> "title" to "desc"
        SortOrder.RELEVANCE -> "relevance" to "desc"
        else -> "chapter_updated_at" to "desc"
    }

    private fun MangaState.toApiStatus(): String? = when (this) {
        MangaState.ONGOING -> "releasing"
        MangaState.FINISHED -> "finished"
        MangaState.PAUSED -> "on_hiatus"
        MangaState.ABANDONED -> "discontinued"
        MangaState.UPCOMING -> "not_yet_released"
        else -> null
    }

    private fun parseStatus(status: String): MangaState? = when (status.lowercase()) {
        "releasing" -> MangaState.ONGOING
        "finished" -> MangaState.FINISHED
        "on_hiatus" -> MangaState.PAUSED
        "discontinued" -> MangaState.ABANDONED
        else -> null
    }

    private suspend fun resolveAuthorId(authorQuery: String): String? {
        val url = "https://$domain/api/tags?keyword=${authorQuery.urlEncoded()}"
        val json = runCatchingCancellable { webClient.httpGet(url).parseJson() }.getOrNull() ?: return null
        val data = json.optJSONArray("data") ?: return null
        return data.mapJSONNotNull { tag ->
            val type = tag.optString("type", "")
            if (type == "author" || type == "artist") {
                tag.getIntOrDefault("id", 0).takeIf { it > 0 }?.toString()
            } else null
        }.firstOrNull()
    }

    private fun Float.toDisplayString(): String {
        val str = toString()
        return if (str.endsWith(".0")) str.removeSuffix(".0") else str
    }

    // ============================== Languages ==============================

    private val allLanguages = listOf("en", "es", "es-la", "fr", "ja", "pt", "pt-br")

    private val langCodeToName = mapOf(
        "en" to "English",
        "es" to "Spanish",
        "es-la" to "Spanish (Latin America)",
        "fr" to "French",
        "ja" to "Japanese",
        "pt" to "Portuguese",
        "pt-br" to "Portuguese (Brazil)",
    )

    // ============================== Genres ==============================

    private val genres: List<MangaTag> = listOf(
        "1" to "Action",
        "78" to "Adventure",
        "90" to "Boys Love",
        "89" to "Girls Love",
        "77" to "Comedy",
        "83" to "Drama",
        "92" to "Fantasy",
        "91" to "Hentai",
        "10" to "Horror",
        "93" to "Ecchi",
        "11" to "Mecha",
        "82" to "Mystery",
        "9" to "Psychological",
        "79" to "Romance",
        "3" to "Sci-Fi",
        "81" to "Slice of Life",
        "8" to "Sports",
        "7" to "Supernatural",
        "17" to "Martial Arts",
        "85" to "Historical",
        "80" to "Isekai",
        "84" to "Medical",
        "88" to "Music",
        "86" to "Philosophical",
        "87" to "Tragedy",
        "19" to "Demons",
        "22" to "Magic",
        "21" to "Monsters",
        "18" to "Samurai",
        "20" to "Vampires",
        "14" to "Harem",
        "76" to "Reverse Harem",
        "6" to "School Life",
        "13" to "Shoujo Ai",
        "12" to "Shounen Ai",
        "15" to "Yuri",
        "16" to "Yaoi",
        "5" to "Gender Bender",
        "42" to "Josei",
        "41" to "Seinen",
        "40" to "Shoujo",
        "4" to "Shounen",
        "25" to "Cooking",
        "26" to "Crossdressing",
        "28" to "Delinquents",
        "27" to "Gyaru",
        "29" to "Loli",
        "30" to "Mafia",
        "31" to "Military",
        "32" to "Monster Girls",
        "33" to "Shota",
        "34" to "Survival",
        "35" to "Time Travel",
        "36" to "Video Games",
        "37" to "Villainess",
        "38" to "Zombies",
        "39" to "Animals",
        "43" to "Aliens",
        "44" to "Ghosts",
        "45" to "Ninja",
        "46" to "Office Workers",
        "47" to "Police",
        "48" to "Post-Apocalyptic",
        "49" to "Reincarnation",
        "50" to "Traditional Games",
        "51" to "Virtual Reality",
        "52" to "4-Koma",
        "53" to "Adaptation",
        "54" to "Anthology",
        "55" to "Award Winning",
        "56" to "Doujinshi",
        "57" to "Fan Colored",
        "58" to "Full Color",
        "59" to "Long Strip",
        "60" to "Oneshot",
        "61" to "Web Comic",
        "62" to "Magical Girls",
        "63" to "Superhero",
        "64" to "Wuxia",
        "65" to "Adult",
        "66" to "Sexual Violence",
        "67" to "Smut",
        "68" to "Gore",
        "69" to "Official Colored",
    ).map { (id, name) ->
        MangaTag(
            title = name,
            key = id,
            source = source,
        )
    }
}