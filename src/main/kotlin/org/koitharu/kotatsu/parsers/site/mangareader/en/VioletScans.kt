package org.koitharu.kotatsu.parsers.site.mangareader.en

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser

@MangaSourceParser("VIOLETSCANS", "VioletScans", "en")
internal class VioletScans(context: MangaLoaderContext) :
	MangaReaderParser(context, MangaParserSource.VIOLETSCANS, "violetmanga.com", pageSize = 20, searchPageSize = 10) {

	override val listUrl = "/comics"
}
