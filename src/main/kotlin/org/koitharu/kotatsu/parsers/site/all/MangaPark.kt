package org.koitharu.kotatsu.parsers.site.all

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser

/**
 * MangaPark revived on mangapark.tv (Madara). Older mangapark.* / mpark.to hosts are dead;
 * asurascans.us redirects here as well.
 */
@MangaSourceParser("MANGAPARK", "MangaPark", type = ContentType.HENTAI)
internal class MangaPark(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.MANGAPARK, "mangapark.tv") {
	override val listUrl = "manhwa/"
	override val tagPrefix = "manhwa-genre/"
}
