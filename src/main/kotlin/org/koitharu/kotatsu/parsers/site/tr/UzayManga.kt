package org.koitharu.kotatsu.parsers.site.tr

import org.koitharu.kotatsu.parsers.Broken
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.uzaymanga.UzayMangaParser

@Broken
@MangaSourceParser("UZAYMANGA", "Uzay Manga", "tr")
internal class UzayManga(context: MangaLoaderContext) :
	UzayMangaParser(
		context = context,
		source = MangaParserSource.UZAYMANGA,
		domain = "uzaymanga.com",
		cdnUrl = "https://uzaymangacdn3.efsaneler.can.re",
	)
