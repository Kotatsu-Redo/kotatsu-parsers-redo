package org.koitharu.kotatsu.parsers.site.loneseal.id

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.loneseal.LoneSealParser

@MangaSourceParser("SOULSCANS", "SoulScans", "id")
internal class SoulScans(context: MangaLoaderContext) :
	LoneSealParser(context, MangaParserSource.SOULSCANS, "v1.soulscans.asia") {

	override fun normalizeImageUrl(url: String): String {
		val parsed = url.toHttpUrlOrNull()
		if (parsed != null && (parsed.host == "127.0.0.1" || parsed.host == "localhost")) {
			return parsed.newBuilder()
				.scheme("https")
				.host(domain)
				.port(443)
				.build()
				.toString()
		}
		return super.normalizeImageUrl(url)
	}
}
