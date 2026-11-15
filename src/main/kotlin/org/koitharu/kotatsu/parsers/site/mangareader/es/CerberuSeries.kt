package org.koitharu.kotatsu.parsers.site.mangareader.es

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser
import org.koitharu.kotatsu.parsers.util.*

@MangaSourceParser("LEGIONSCANS", "CerberusSeries", "es")
internal class CerberuSeries(context: MangaLoaderContext) :
	MangaReaderParser(context, MangaParserSource.LEGIONSCANS, "legionscans.com", pageSize = 20, searchPageSize = 10) {

	// Site is Themesia under the /wp WordPress path (no longer HeanCms).
	override val listUrl = "/wp/manga"

	override val filterCapabilities: MangaListFilterCapabilities
		get() = super.filterCapabilities.copy(
			isTagsExclusionSupported = false,
		)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		if (!filter.query.isNullOrEmpty()) {
			val url = buildString {
				append("https://")
				append(domain)
				append("/wp/page/")
				append(page)
				append("/?s=")
				append(filter.query.urlEncoded())
			}
			return parseMangaList(webClient.httpGet(url).parseHtml())
		}
		return super.getListPage(page, order, filter)
	}
}
