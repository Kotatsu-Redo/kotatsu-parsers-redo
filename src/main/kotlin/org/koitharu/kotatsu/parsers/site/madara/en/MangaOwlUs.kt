package org.koitharu.kotatsu.parsers.site.madara.en

import org.koitharu.kotatsu.parsers.Broken
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser

@Broken("Domain no longer resolves")
@MangaSourceParser("MANGAOWL_US", "MangaOwlYaoi", "en", ContentType.HENTAI)
internal class MangaOwlUs(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.MANGAOWL_US, "mangaowlyaoi.com")
