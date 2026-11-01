package org.koitharu.kotatsu.parsers.site.madara.ar

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser

// lekmanga.org redirects to lekmanga.online, which turns POST requests into GET.
@MangaSourceParser("LEKMANGAORG", "LekManga.org", "ar")
internal class LekMangaOrg(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.LEKMANGAORG, "lekmanga.online", pageSize = 10) {
	override val listUrl = "comics/"

	// A few genres exist twice on the site under different slugs
	override suspend fun fetchAvailableTags(): Set<MangaTag> =
		super.fetchAvailableTags().distinctBy { it.title }.toSet()
}
