package org.koitharu.kotatsu.parsers.site.mangareader.id

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser

@MangaSourceParser("KOMIKINDO_LIVE", "Komikindo.live", "id", ContentType.HENTAI)
internal class KomikindoLive(context: MangaLoaderContext) :
	MangaReaderParser(context, MangaParserSource.KOMIKINDO_LIVE, "komikindo.live", pageSize = 20, searchPageSize = 10) {
	override val datePattern = "MMMM d, yyyy"
}
