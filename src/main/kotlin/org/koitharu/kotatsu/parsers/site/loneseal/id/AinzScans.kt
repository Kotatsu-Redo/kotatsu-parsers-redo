package org.koitharu.kotatsu.parsers.site.loneseal.id

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.loneseal.LoneSealParser

@MangaSourceParser("AINZSCANS", "AinzScans", "id")
internal class AinzScans(context: MangaLoaderContext) :
	LoneSealParser(context, MangaParserSource.AINZSCANS, "v3.ainzscans01.com") {

	override fun normalizeImageUrl(url: String): String {
		val normalized = super.normalizeImageUrl(url)
		val httpUrl = normalized.toHttpUrlOrNull() ?: return normalized
		if (httpUrl.queryParameterNames.none { it in RESIZE_QUERY_PARAMS }) {
			return normalized
		}
		return httpUrl.newBuilder().apply {
			RESIZE_QUERY_PARAMS.forEach(::removeAllQueryParameters)
		}.build().toString()
	}

	override fun filterPageUrls(urls: List<String>): List<String> = urls.filterIndexed { index, url ->
		val filename = url.toHttpUrlOrNull()?.pathSegments?.lastOrNull() ?: return@filterIndexed true
		val isAd = when (index) {
			urls.lastIndex -> AD_DOMAIN_REGEX.matches(filename)
			urls.lastIndex - 2 -> AD_DONATION_REGEX.matches(filename) || AD_VOTE_POST_REGEX.matches(filename)
			0 -> AD_VOTE_PRE_REGEX.matches(filename)
			1 -> AD_READ_ON_REGEX.matches(filename)
			else -> false
		}
		!isAd
	}

	private companion object {
		val RESIZE_QUERY_PARAMS = arrayOf("w", "width", "resize")
		val AD_DOMAIN_REGEX = Regex("""^999(?:-\d+)?\.jpe?g$""")
		val AD_DONATION_REGEX = Regex("""^997(?:-\d+)?\.jpe?g$""")
		val AD_VOTE_PRE_REGEX = Regex("""^00\.0\.jpg$""")
		val AD_READ_ON_REGEX = Regex("""^00\.1\.jpg$""")
		val AD_VOTE_POST_REGEX = Regex("""^995\.jpg$""")
	}
}
