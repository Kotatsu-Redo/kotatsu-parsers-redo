package org.koitharu.kotatsu.parsers.site.madara.en

import org.koitharu.kotatsu.parsers.Broken
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser

@Broken("Down for now")
@MangaSourceParser("HARIMANGA", "HariManga", "en")
internal class HariManga(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.HARIMANGA, "harimanga.me", pageSize = 10)
