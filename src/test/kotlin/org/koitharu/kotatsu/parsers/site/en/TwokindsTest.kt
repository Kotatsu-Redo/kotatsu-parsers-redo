package org.koitharu.kotatsu.parsers.site.en

import kotlinx.coroutines.test.runTest
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.SourceConfigMock
import org.koitharu.kotatsu.parsers.bitmap.Bitmap
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.SortOrder

internal class TwokindsTest {

	@Test
	fun archiveBuildsChaptersAndResolvesLazyPageImage() = runTest {
		val archiveHtml = """
			<html>
			<head><meta name="description" content="Sample webcomic summary"></head>
			<body>
				<section class="chapter" data-ch-id="1">
					<h2>Chapter 1: Prologue</h2>
					<aside class="chapter-links">
						<a href="/comic/1/"><img src="https://images.example/thumb1.jpg"></a>
						<a href="/comic/2/"><img src="https://images.example/thumb2.jpg"></a>
					</aside>
				</section>
			</body>
			</html>
		""".trimIndent()

		val comicPageHtml = """
			<html><body>
				<div id="content"><article class="comic"><img src="https://images.example/full1.png"></article></div>
			</body></html>
		""".trimIndent()

		val context = TestContext { path ->
			when (path) {
				"/archive/" -> archiveHtml
				"/comic/1/" -> comicPageHtml
				else -> "<html/>"
			}
		}

		val parser = Twokinds(context)
		assertTrue(parser.getList(0, SortOrder.NEWEST, MangaListFilter(query = "nonexistent")).isEmpty())

		val listItem = parser.getList(0, SortOrder.NEWEST, MangaListFilter(query = "Twokinds")).single()
		assertEquals("https://images.example/thumb2.jpg", listItem.coverUrl)

		val details = parser.getDetails(listItem)
		val chapter = requireNotNull(details.chapters).single()
		assertEquals("Chapter 1: Prologue", chapter.title)

		val pages = parser.getPages(chapter)
		assertEquals(listOf("/comic/1/", "/comic/2/"), pages.map { it.url })
		assertEquals("https://images.example/full1.png", parser.getPageUrl(pages.first()))
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
