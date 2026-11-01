package org.koitharu.kotatsu.parsers.site.madara.ar

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser

// lekmanga.org redirects to lekmanga.online, whose WordPress has gone: it serves a bare file listing.
@MangaSourceParser("LEKMANGAORG", "LekManga.org", "ar")
internal class LekMangaOrg(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.LEKMANGAORG, "manga-leko.site", pageSize = 10) {
	override val configKeyDomain = ConfigKey.Domain("manga-leko.site", "lekmanga.online")
	override val listUrl = "manga/"

	// A few genres exist twice on the site under different slugs
	override suspend fun fetchAvailableTags(): Set<MangaTag> =
		super.fetchAvailableTags().distinctBy { it.title }.toSet()
}
