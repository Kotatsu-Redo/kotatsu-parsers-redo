package org.koitharu.kotatsu.parsers.site.zeistmanga.id

import org.koitharu.kotatsu.parsers.Broken
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.zeistmanga.ZeistMangaParser

@Broken
@MangaSourceParser("NIMEMOB", "Nimemob", "id")
internal class Nimemob(context: MangaLoaderContext) :
	ZeistMangaParser(context, MangaParserSource.NIMEMOB, "www.nimemob.my.id")
