package org.koitharu.kotatsu.parsers.site.madara.pt

import org.koitharu.kotatsu.parsers.Broken
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser

@Broken("Site responds but parser is broken or outdated")
@MangaSourceParser("LUNARSCAN", "LunarrScan.com", "pt")
internal class LunarScan(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.LUNARSCAN, "lunarrscan.com")
