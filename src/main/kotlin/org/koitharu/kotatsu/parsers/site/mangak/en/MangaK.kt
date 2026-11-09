package org.koitharu.kotatsu.parsers.site.mangak.en

import okhttp3.Interceptor
import okhttp3.Response
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.mangak.MangaKParser

@MangaSourceParser("MANGAKIO", "MangaKIO", "en", ContentType.MANGA)
internal class MangaK(context: MangaLoaderContext) :
	MangaKParser(context, MangaParserSource.MANGAKIO, "mangak.io") {

	// Chapter images are served from rx.qvzr?.org, which rx.rzyn.net mirrors when it fails.
	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		val response = chain.proceed(request)
		if (response.isSuccessful || !request.url.host.matches(IMAGE_HOST_REGEX)) {
			return response
		}
		response.close()
		val newUrl = request.url.newBuilder().host(FALLBACK_IMAGE_HOST).build()
		return chain.proceed(request.newBuilder().url(newUrl).build())
	}

	private companion object {

		const val FALLBACK_IMAGE_HOST = "rx.rzyn.net"

		val IMAGE_HOST_REGEX = Regex("rx\\.qvzr[a-z]\\.org")
	}
}
