package org.koitharu.kotatsu.parsers.site.mangareader.id

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser
import org.koitharu.kotatsu.parsers.util.*

@MangaSourceParser("MANHWAINDO", "ManhwaIndo", "id")
internal class ManhwaIndoParser(context: MangaLoaderContext) :
    MangaReaderParser(context, MangaParserSource.MANHWAINDO, "www.manhwaindo.my", pageSize = 30, searchPageSize = 20) {
    override val listUrl = "/series"
    override val selectMangaList = "div.bs"
    override val selectMangaListImg = "img"
    override val selectMangaListTitle = ".tt"
    override val selectChapter = "#chapterlist li"
    override val selectPage = "#readerarea img"

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val chapterUrl = chapter.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(chapterUrl).parseHtml()
        return doc.select(selectPage)
            .filter { it.hasAttr("src") || it.hasAttr("data-src") }
            .map { img ->
                val imageUrl = img.src()?.toAbsoluteUrl(domain) ?: ""
                MangaPage(
                    id = generateUid(imageUrl),
                    url = imageUrl,
                    preview = null,
                    source = source
                )
            }
            .filter { it.url.isNotBlank() && !it.url.contains("cover") }
    }
}
