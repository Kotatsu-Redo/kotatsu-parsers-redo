package org.koitharu.kotatsu.parsers.site.ru

import kotlinx.coroutines.test.runTest
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.SourceConfigMock
import org.koitharu.kotatsu.parsers.bitmap.Bitmap
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.SortOrder

internal class TwokindsRuTest {

	@Test
	fun archiveMapsDirectPageAndThumbnailUrls() = runTest {
		val archiveHtml = """
			<html><body>
				<section class="Chapter" data-ch-id="0">
					<h2>Пролог</h2>
					<aside class="ChapterLinks">
						<a href="/comic/10"><img src="/comic/10/thumbnail"></a>
						<a href="/comic/11"><img src="/comic/11/thumbnail"></a>
					</aside>
				</section>
			</body></html>
		""".trimIndent()

		val context = TestContext { archiveHtml }
		val parser = TwokindsRu(context)

		val manga = parser.getList(0, SortOrder.NEWEST, MangaListFilter.EMPTY).single()
		val details = parser.getDetails(manga)
		val chapter = requireNotNull(details.chapters).single()
		assertEquals("Пролог", chapter.title)

		val pages = parser.getPages(chapter)
		assertEquals(listOf("/comic/10/page", "/comic/11/page"), pages.map { it.url })
		assertEquals("https://twokinds.ru/comic/10/page", parser.getPageUrl(pages.first()))
	}

	private class TestContext(
		private val responder: (path: String) -> String,
	) : MangaLoaderContext() {
		override val cookieJar: CookieJar = CookieJar.NO_COOKIES
		override val httpClient: OkHttpClient = OkHttpClient.Builder()
			.addInterceptor { chain ->
				val req = chain.request()
				Response.Builder()
					.request(req)
					.protocol(Protocol.HTTP_1_1)
					.code(200)
					.message("OK")
					.body(responder(req.url.encodedPath).toResponseBody("text/html; charset=utf-8".toMediaType()))
					.build()
			}
			.build()

		override fun getConfig(source: MangaSource) = SourceConfigMock()
		override fun getDefaultUserAgent(): String = "TestAgent/1.0"
		@Deprecated("Test")
		override suspend fun evaluateJs(script: String): String? = null
		override suspend fun evaluateJs(baseUrl: String, script: String, timeout: Long): String? = null
		override fun redrawImageResponse(response: Response, redraw: (Bitmap) -> Bitmap): Response = response
		override fun createBitmap(width: Int, height: Int): Bitmap = throw UnsupportedOperationException()
	}
}
