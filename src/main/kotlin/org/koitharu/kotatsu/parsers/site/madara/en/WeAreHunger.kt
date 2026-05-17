package org.koitharu.kotatsu.parsers.site.madara.en

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser

@MangaSourceParser("WEAREHUNGER", "WeAreHunger", "en")
internal class WeAreHunger(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.WEAREHUNGER, "www.wearehunger.site")