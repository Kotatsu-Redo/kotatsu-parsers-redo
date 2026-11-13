package org.koitharu.kotatsu.parsers.site.madara.ar

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser

// The site moved from lekmanga.net, which now forwards here. The old address is not kept as an alternative:
// a forwarded list request arrives as a GET, which the site answers with HTTP 400.
@MangaSourceParser("MANGALEK", "LekManga", "ar")
internal class LekManga(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.MANGALEK, "mangalik.net", pageSize = 10) {

	// Every page opens with a <noscript> block whose heading reads "Access Denied".
	override val selectTitle = "div.post-title h1"

	// The title page holds no chapters and the site lists them through admin-ajax.php.
	override val postReq = true
}
