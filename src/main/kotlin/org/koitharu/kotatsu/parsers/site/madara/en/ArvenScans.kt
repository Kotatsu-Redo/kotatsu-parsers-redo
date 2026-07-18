package org.koitharu.kotatsu.parsers.site.madara.en

import okhttp3.Interceptor
import okhttp3.Response
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser

@MangaSourceParser("ARVENSCANS", "ArvenComics", "en")
internal class ArvenScans(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.ARVENSCANS, "arvencomics.com") {

	override val listUrl = "comic/"
    override val withoutAjax = true

	override fun intercept(chain: Interceptor.Chain): Response {
		synchronized(rateLimitLock) {
			val now = System.currentTimeMillis()
			val waitMs = REQUEST_INTERVAL_MS - (now - lastRequestAt)
			if (waitMs > 0) {
				Thread.sleep(waitMs)
			}
			lastRequestAt = System.currentTimeMillis()
		}
		return super.intercept(chain)
	}

	private companion object {
		private const val REQUEST_INTERVAL_MS = 1_000L
		private var lastRequestAt = 0L
		private val rateLimitLock = Any()
	}
}
