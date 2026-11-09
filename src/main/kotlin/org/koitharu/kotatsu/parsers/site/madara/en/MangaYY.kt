package org.koitharu.kotatsu.parsers.site.madara.en

import org.koitharu.kotatsu.parsers.Broken
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser

@Broken
@MangaSourceParser("MANGAYY", "MangaYY", "en")
internal class MangaYY(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.MANGAYY, "mangayy.org") {

	override val datePattern = "dd MMMM, yyyy"
}
