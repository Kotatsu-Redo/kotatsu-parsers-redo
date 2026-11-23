package org.koitharu.kotatsu.parsers.site.madara.en

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser

@MangaSourceParser("DRAGONTEA", "DragonTea", "en")
internal class DragonTea(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.DRAGONTEA, "dragontea.ink") {

	override val datePattern = "MM/dd/yyyy"

	// DragonTea's Madara archive uses /novel/ rather than the default /manga/ path.
	override val listUrl = "novel/"

	// Match Keiyoushi Madara's filterNonMangaItems=true: omit novel/text-only entries.
	override val mangaChaptersOnly = true

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		// Lets Kotatsu use its header-filtering Cloudflare WebView resolver for this source.
		keys.add(ConfigKey.InterceptCloudflare(defaultValue = true))
	}
}
