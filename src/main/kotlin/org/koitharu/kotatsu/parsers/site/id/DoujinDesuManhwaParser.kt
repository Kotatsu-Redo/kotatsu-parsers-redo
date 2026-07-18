package org.koitharu.kotatsu.parsers.site.id

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import java.util.*

@MangaSourceParser("DOUJINDESU_MANHWA", "DoujinDesu Manhwa", "id")
internal class DoujinDesuManhwaParser(context: MangaLoaderContext) :
	BaseDoujinDesuParser(context, MangaParserSource.DOUJINDESU_MANHWA) {

	override val defaultTypes: String = "manhwa"

	override val availableContentTypes: Set<ContentType> = EnumSet.of(ContentType.MANHWA)
}
