package org.koitharu.kotatsu.parsers.site.ru

import androidx.collection.ArrayMap
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.network.UserAgents
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import org.koitharu.kotatsu.parsers.util.suspendlazy.getOrNull
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy
import java.util.*

@MangaSourceParser("DESUME", "Desu", "ru")
internal class DesuMeParser(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.DESUME, pageSize = 24, searchPageSize = 20) {

    override val configKeyDomain = ConfigKey.Domain("desu.uno")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.NEWEST,
        SortOrder.ALPHABETICAL,
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isMultipleTagsSupported = true,
            isSearchSupported = true,
        )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = tagsCache.get().values.toSet(),
    )

    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("User-Agent", UserAgents.KOTATSU)
        .build()

    private val tagsCache = suspendLazy(initializer = ::fetchTags)

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val query = filter.query?.trim().orEmpty()
        if (query.isNotEmpty()) {
            if (page != searchPaginator.firstPage) {
                return emptyList()
            }
            val url = "https://$domain/manga/search/".toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .build()
            return parseSearch(webClient.httpGet(url).parseHtml())
        }
        val url = "https://$domain/manga/".toHttpUrl().newBuilder().apply {
            if (page != paginator.firstPage) {
                addQueryParameter("page", page.toString())
            }
            if (order != SortOrder.UPDATED) {
                addQueryParameter("order_by", getSortKey(order))
            }
            if (filter.tags.isNotEmpty()) {
                addQueryParameter("genres", filter.tags.joinToString(",") { it.key })
            }
        }.build()
        return parseCatalog(webClient.httpGet(url).parseHtml())
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val mangaId = manga.url.findGroupValue(MANGA_ID_REGEX)
            ?: manga.url.findGroupValue(LEGACY_MANGA_URL_REGEX)
            ?: throw ParseException("Cannot obtain manga id", manga.url)
        val json = webClient.httpGet("/api/manga/$mangaId/".toAbsoluteUrl(domain))
            .parseJson().getJSONObject("manga")
        val chapters = webClient.httpGet("/api/manga/$mangaId/chapters".toAbsoluteUrl(domain))
            .parseJson().getJSONArray("chapters")
        val url = json.getStringOrNull("view_url")?.toHttpUrl()?.encodedPath ?: manga.url
        val cover = json.optJSONObject("cover")?.getStringOrNull("preview")
        return manga.copy(
            url = url,
            publicUrl = url.toAbsoluteUrl(domain),
            coverUrl = cover ?: manga.coverUrl,
            largeCoverUrl = cover?.replace("/covers/preview/", "/covers/original/") ?: manga.largeCoverUrl,
            altTitles = manga.altTitles +
                setOfNotNull(json.getStringOrNull("name")?.takeIf { it != manga.title }) +
                json.optJSONArray("synonyms")?.toStringSet().orEmpty(),
            rating = json.optJSONObject("score")?.getFloatOrDefault("value", 0f)
                ?.takeIf { it > 0f }?.div(10f) ?: manga.rating,
            state = parseState(json.getStringOrNull("status"), json.getStringOrNull("trans_status")),
            contentRating = when (json.getStringOrNull("content_rating")) {
                "18+", "18_plus" -> ContentRating.ADULT
                "16+", "16_plus", "17+", "17_plus" -> ContentRating.SUGGESTIVE
                null, "unrated" -> null
                else -> ContentRating.SAFE
            },
            authors = json.optJSONArray("authors")?.mapJSONNotNullToSet { it.getStringOrNull("name") }.orEmpty(),
            tags = json.optJSONArray("genres")?.mapJSONNotNullToSet { jo ->
                val slug = jo.getStringOrNull("slug") ?: return@mapJSONNotNullToSet null
                MangaTag(
                    key = "${jo.getInt("genre_id")}-$slug",
                    title = (jo.getStringOrNull("name") ?: slug).toTitleCase(),
                    source = manga.source,
                )
            }.orEmpty(),
            description = json.getStringOrNull("description"),
            chapters = chapters.mapChapters(reversed = true) { _, jo ->
                val chapterUrl = jo.getStringOrNull("view_url")?.toHttpUrl()?.encodedPath
                    ?: return@mapChapters null
                MangaChapter(
                    id = generateUid(jo.getLong("id")),
                    source = manga.source,
                    url = chapterUrl,
                    uploadDate = jo.getLongOrDefault("publish_date", 0L) * 1000L,
                    title = jo.getStringOrNull("title"),
                    volume = jo.getStringOrNull("volume")?.toIntOrNull() ?: 0,
                    number = jo.getStringOrNull("number")?.replace(',', '.')?.toFloatOrNull() ?: 0f,
                    scanlator = null,
                    branch = null,
                )
            },
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val fullUrl = chapter.url.toAbsoluteUrl(domain)
        val script = webClient.httpGet(fullUrl).parseHtml().select("script").firstNotNullOfOrNull { element ->
            element.data().takeIf { READER_CONFIG_KEY in it }
        } ?: throw ParseException("Reader configuration not found", fullUrl)
        val config = JSONObject(
            script.findGroupValue(READER_CONFIG_REGEX)
                ?: throw ParseException("Reader configuration not found", fullUrl),
        )
        val apiUrl = config.getString("apiBaseUrl") + "/chapters/" + config.getJSONObject("chapter").getLong("id")
        val pages = webClient.httpGet(apiUrl.toAbsoluteUrl(domain))
            .parseJson().getJSONObject("chapter").getJSONArray("pages")
        return pages.mapJSON { jo ->
            val url = jo.getString("url")
            MangaPage(
                id = generateUid(url),
                preview = null,
                source = chapter.source,
                url = url,
            )
        }
    }

    override suspend fun resolveLink(resolver: LinkResolver, link: HttpUrl): Manga? {
        val doc = webClient.httpGet(link).parseHtml()
        val publicUrl = doc.selectFirst("#animeView > link[itemprop=url]")
            ?.attrAsAbsoluteUrlOrNull("href") ?: return null
        val mangaId = publicUrl.findGroupValue(MANGA_ID_REGEX)?.toLongOrNull() ?: return null
        val title = doc.selectFirst(".titleBar .rus-name")?.textOrNull() ?: return null
        return resolver.resolveManga(
            this,
            id = generateUid(mangaId),
            url = publicUrl.toRelativeUrl(domain),
            title = title,
        )
    }

    private suspend fun parseCatalog(doc: Document): List<Manga> {
        val tagsMap = tagsCache.getOrNull()
        return doc.select(".animeList .memberListItem").mapNotNull { item ->
            val link = item.selectFirst("a.animeTitle[href]") ?: return@mapNotNull null
            val publicUrl = link.attrAsAbsoluteUrl("href")
            val url = publicUrl.toRelativeUrl(domain)
            val mangaId = url.findGroupValue(MANGA_ID_REGEX)?.toLongOrNull() ?: return@mapNotNull null
            val originalTitle = link.text()
            val title = item.selectFirst(".dimmed.oTitle [itemprop=title]")?.text().orEmpty()
                .ifBlank { originalTitle }
            Manga(
                url = url,
                publicUrl = publicUrl,
                source = source,
                title = title,
                altTitles = setOfNotNull(originalTitle.takeIf { it != title }),
                coverUrl = item.selectFirst("a.avatar .img")?.styleValueOrNull("background-image")
                    ?.cssUrl()?.trim('\'', '"')?.toAbsoluteUrl(domain),
                state = null,
                rating = item.info("Рейтинг")?.toFloatOrNull()?.div(10f) ?: RATING_UNKNOWN,
                id = generateUid(mangaId),
                contentRating = null,
                tags = item.info("Жанры")?.split(',')?.mapNotNullToSet { genre ->
                    tagsMap?.get(genre.trim().toTitleCase())
                } ?: emptySet(),
                authors = emptySet(),
            )
        }
    }

    private fun parseSearch(doc: Document): List<Manga> {
        val root = doc.selectFirst("ul.AniMangaSearchList[data-search-content-type=manga]") ?: return emptyList()
        return root.select("li.AniMangaSearchCard").mapNotNull { item ->
            val link = item.selectFirst("a.AniMangaSearchCard__link[href]") ?: return@mapNotNull null
            val publicUrl = link.attrAsAbsoluteUrl("href")
            val url = publicUrl.toRelativeUrl(domain)
            val mangaId = url.findGroupValue(MANGA_ID_REGEX)?.toLongOrNull() ?: return@mapNotNull null
            val originalTitle = item.selectFirst(".AniMangaSearchCard__subtitle")?.textOrNull()
            val title = item.selectFirst(".AniMangaSearchCard__title")?.textOrNull()
                ?: originalTitle ?: return@mapNotNull null
            Manga(
                url = url,
                publicUrl = publicUrl,
                source = source,
                title = title,
                altTitles = setOfNotNull(originalTitle?.takeIf { it != title }),
                coverUrl = item.selectFirst(".AniMangaSearchCard__cover")?.src(),
                state = when {
                    item.selectFirst(".AniMangaSearchCard__tag.released") != null -> MangaState.FINISHED
                    item.selectFirst(".AniMangaSearchCard__tag.ongoing") != null -> MangaState.ONGOING
                    else -> null
                },
                rating = item.selectFirst(".AniMangaSearchCard__facts .is-score")?.text()
                    ?.replace(',', '.')?.toFloatOrNull()?.div(10f) ?: RATING_UNKNOWN,
                id = generateUid(mangaId),
                contentRating = null,
                tags = emptySet(),
                authors = emptySet(),
            )
        }
    }

    private fun parseState(status: String?, transStatus: String?) = when (status) {
        "ongoing" -> MangaState.ONGOING
        "released" -> MangaState.FINISHED
        "anons" -> MangaState.UPCOMING
        "discontinued" -> MangaState.ABANDONED
        "paused" -> MangaState.PAUSED
        else -> when (transStatus) {
            "continued" -> MangaState.ONGOING
            "completed" -> MangaState.FINISHED
            else -> null
        }
    }

    private fun Element.info(key: String): String? = select(".animeInfo dl").firstOrNull {
        it.selectFirst("dt")?.text() == "$key:"
    }?.selectFirst("dd")?.textOrNull()

    private fun getSortKey(sortOrder: SortOrder) =
        when (sortOrder) {
            SortOrder.ALPHABETICAL -> "name"
            SortOrder.POPULARITY -> "popular"
            SortOrder.UPDATED -> "updated"
            SortOrder.NEWEST -> "id"
            else -> "updated"
        }

    private suspend fun fetchTags(): Map<String, MangaTag> {
        val doc = webClient.httpGet("https://$domain/manga/").parseHtml()
        val root = doc.body().requireElementById("animeFilter")
            .selectFirstOrThrow(".catalog-genres")
        val li = root.select("li")
        val result = ArrayMap<String, MangaTag>(li.size)
        for (it in li) {
            val input = it.selectFirst("input") ?: continue
            val genreId = input.attr("data-genre-id").ifEmpty {
                it.parseFailed("data-genre-id is empty")
            }
            val genreSlug = input.attr("data-genre-slug").ifEmpty {
                it.parseFailed("data-genre-slug is empty")
            }
            val tag = MangaTag(
                source = source,
                key = "$genreId-$genreSlug",
                title = input.attr("data-genre-name").toTitleCase().ifEmpty {
                    it.parseFailed("data-genre-name is empty")
                },
            )
            result[tag.title] = tag
        }
        return result
    }

    private companion object {
        val MANGA_ID_REGEX = Regex("""\.(\d+)/?$""")
        val LEGACY_MANGA_URL_REGEX = Regex("""/manga/api/(\d+)/?$""")
        const val READER_CONFIG_KEY = "window.MangaReader"
        // Both braces must be escaped: Android's ICU engine rejects a bare `}` as a
        // syntax error, even though the JVM accepts it.
        val READER_CONFIG_REGEX = Regex(
            """window\.MangaReader\s*=\s*(\{.*?\})\s*;""",
            RegexOption.DOT_MATCHES_ALL,
        )
    }
}
