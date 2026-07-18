package org.koitharu.kotatsu.parsers.site.madara.es

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser

@MangaSourceParser("EMPERORSCAN", "EmperorScan", "es")
internal class EmperorScan(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.EMPERORSCAN, "imperiomanhua.com") {

	override val withoutAjax = true

	override val datePattern = "MMMM dd, yyyy"

	override val selectDesc = "div.summary_content div.post-content_item:has(h5:contains(Sinopsis)) div"
}
