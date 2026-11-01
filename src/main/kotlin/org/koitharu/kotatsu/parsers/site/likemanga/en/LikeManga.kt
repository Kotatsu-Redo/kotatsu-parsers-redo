package org.koitharu.kotatsu.parsers.site.likemanga.en

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.likemanga.LikeMangaParser

@MangaSourceParser("LIKEMANGA", "MGRead (LikeManga)", "en")
internal class LikeManga(context: MangaLoaderContext) :
	LikeMangaParser(context, MangaParserSource.LIKEMANGA, "mgread.io")
