package org.koitharu.kotatsu.parsers.site.zeistmanga.id

import org.koitharu.kotatsu.parsers.Broken
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.zeistmanga.ZeistMangaParser


@Broken
@MangaSourceParser("MAGERIN", "Magerin", "id")
internal class Magerin(context: MangaLoaderContext) :
	ZeistMangaParser(context, MangaParserSource.MAGERIN, "www.magerin.com")
