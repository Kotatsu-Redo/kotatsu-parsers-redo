package org.koitharu.kotatsu.parsers.site.madara.id

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.Broken
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.util.*
import java.util.*

@Broken
@MangaSourceParser("YURILAB", "YuriLab", "id", ContentType.HENTAI)
internal class YuriLab(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.YURILAB, "yurilabs.my.id", pageSize = 30) {

    override val sourceLocale: Locale = Locale.ENGLISH
    override val withoutAjax = true

    override val filterCapabilities: MangaListFilterCapabilities
        get() = super.filterCapabilities.copy(isMultipleTagsSupported = false)

    override fun parseMangaList(doc: Document): List<Manga> {
        return super.parseMangaList(doc).map { manga ->
            manga.copy(coverUrl = manga.coverUrl?.replace(Regex("""-\d+x\d+(?=\.\w+$)"""), ""))
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
        val author = doc.selectFirst(".author-content a, .manga-author a")?.textOrNull()
        return super.getDetails(manga).copy(
            authors = setOfNotNull(author).ifEmpty { manga.authors }
        )
    }


    override suspend fun fetchAvailableTags(): Set<MangaTag> {
        val url = "https://$domain/?s=&post_type=wp-manga"
        val docs = webClient.httpGet(url).parseHtml()
        val genreLinks = docs.select(".genres-filter .dropdown-menu a[href*='genre=']")
        return genreLinks.mapNotNullToSet { el ->
            val href = el.attrOrNull("href") ?: return@mapNotNullToSet null
            val match = Regex("""genre=([^&]+)""").find(href)
            val key = match?.groupValues?.get(1) ?: return@mapNotNullToSet null
            val title = el.textOrNull()?.trim()?.toTitleCase(sourceLocale) ?: return@mapNotNullToSet null
            MangaTag(
                title = title,
                key = key,
                source = source,
            )
        }
    }

    override val selectGenre = ".genres-content a[href*='genre'], .tags-content a[href*='tag']"

    override suspend fun createMangaTag(a: Element): MangaTag? {
        val href = a.attrOrNull("href") ?: return null
        val tagKey = extractTagKey(href) ?: return null
        val title = a.textOrNull()?.trim() ?: return null
        return MangaTag(
            title = title,
            key = tagKey,
            source = source,
        )
    }

    private fun extractTagKey(href: String): String? {
        val genreMatch = Regex("""genre=([^&/?]+)""").find(href)
        if (genreMatch != null) return genreMatch.groupValues[1]
        val pattern = Regex("""series-genre/([^/?]+)|series-tag/([^/?]+)""", RegexOption.IGNORE_CASE)
        return pattern.find(href)?.groupValues?.getOrNull(1)?.takeIf { it.isNotEmpty() }
            ?: pattern.find(href)?.groupValues?.getOrNull(2)?.takeIf { it.isNotEmpty() }
    }

    override val selectChapter = "ul.version-chap li.wp-manga-chapter"

    override suspend fun loadChapters(mangaUrl: String, document: Document): List<MangaChapter> {
        val allChapters = mutableListOf<MangaChapter>()
        var t = 1

        while (true) {
            val ajaxUrl = mangaUrl.toAbsoluteUrl(domain).removeSuffix('/') + "/ajax/chapters/?t=$t"
            val ajaxDocs = webClient.httpPost(
                ajaxUrl.toHttpUrl(),
                emptyMap<String, String>(),
                Headers.Builder().add("X-Requested-With", "XMLHttpRequest").build(),
            ).parseHtml()

            val lis = ajaxDocs.select(selectChapter)
            if (lis.isEmpty()) break

            val pageChapters = lis.mapNotNull { li ->
                val a = li.selectFirst("a") ?: return@mapNotNull null
                val href = a.attrAsRelativeUrl("href")
                if (href.isBlank() || href == "#") return@mapNotNull null
                val name = a.ownText().ifEmpty { null } ?: a.selectFirst("p")?.textOrNull()
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

            allChapters.addAll(pageChapters)
            t++
        }

        return allChapters.reversed().mapIndexed { index, chapter ->
            chapter.copy(number = (index + 1).toFloat())
        }
    }
}
