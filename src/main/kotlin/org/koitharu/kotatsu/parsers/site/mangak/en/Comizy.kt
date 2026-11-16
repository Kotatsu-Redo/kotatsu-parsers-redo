package org.koitharu.kotatsu.parsers.site.mangak.en

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.mangak.MangaKParser

@MangaSourceParser("COMIZYIO", "Comizy.io", "en", ContentType.MANGA)
internal class Comizy(context: MangaLoaderContext) :
	MangaKParser(context, MangaParserSource.COMIZYIO, "comizy.io")
