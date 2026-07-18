package org.koitharu.kotatsu.parsers.site.natsu.ja

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.natsu.NatsuParser

@MangaSourceParser("RAWKUMA", "Rawkuma", "ja")
internal class Rawkuma(context: MangaLoaderContext) :
	NatsuParser(context, MangaParserSource.RAWKUMA, pageSize = 24) {

	override val configKeyDomain = ConfigKey.Domain("rawkuma.net")
}
