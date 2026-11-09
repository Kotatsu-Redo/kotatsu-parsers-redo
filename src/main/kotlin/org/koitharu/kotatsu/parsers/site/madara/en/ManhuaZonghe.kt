package org.koitharu.kotatsu.parsers.site.madara.en

import org.koitharu.kotatsu.parsers.Broken
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser

@Broken("Site down: every page answers 503 or 404")
@MangaSourceParser("MANHUAZONGHE", "ManhuaZonghe", "en")
internal class ManhuaZonghe(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.MANHUAZONGHE, "www.manhuazonghe.com") {
	override val tagPrefix = "genre/"
	override val listUrl = "manhua/"
}
