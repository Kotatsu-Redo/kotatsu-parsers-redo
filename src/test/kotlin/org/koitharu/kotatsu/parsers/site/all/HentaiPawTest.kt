package org.koitharu.kotatsu.parsers.site.all

import kotlinx.coroutines.test.runTest
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.SourceConfigMock
import org.koitharu.kotatsu.parsers.bitmap.Bitmap
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.SortOrder
import java.util.Locale

internal class HentaiPawTest {

	@Test
	fun skipsDailyRankingGridRoutesLocalesAndExtractsSortedViewerImages() = runTest {
		val newestHtml = """
			<html><body>
				<div class="grid">
					<a href="/articles/100"><img src="https://cdn.imagedeliveries.com/100/thumbnails/cover.webp"><div title="Ranking Item">Ranking Item</div></a>
				</div>
				<div class="grid">
					<a href="/articles/200"><img src="https://cdn.imagedeliveries.com/200/thumbnails/cover.webp"><div title="Newest Item">Newest Item</div></a>
				</div>
			</body></html>
		""".trimIndent()

		val detailsHtml = """
			<html><body>
				<h1>Newest Item</h1>
				<div id="article-tag-information">
					<a href="/artists/5">Artist A</a>
					<a href="/groups/8">Circle B</a>
					<a href="/tags/12">fantasy</a>
					<a href="/languages/2">English</a>
				</div>
			</body></html>
		""".trimIndent()

		val viewerRaw = """
			<script>self.__next_f.push([1,"[\"https:\/\/cdn.imagedeliveries.com\/200\/deadbeef\/2.webp\",\"https:\/\/cdn.imagedeliveries.com\/200\/deadbeef\/1.jpg\"]"])</script>
		""".trimIndent()

		val seenPaths = ArrayList<String>()
		val context = TestContext { url ->
			seenPaths.add(url.encodedPath)
			when (url.encodedPath) {
				"/" -> newestHtml
				"/languages/1" -> newestHtml
				"/articles/200" -> detailsHtml
				"/viewer" -> viewerRaw
				else -> "<html/>"
			}
		}

		val parser = HentaiPaw(context)
		assertFalse(parser.filterCapabilities.isOriginalLocaleSupported)
		val newest = parser.getListPage(1, SortOrder.NEWEST, MangaListFilter.EMPTY)
		assertEquals(listOf("/articles/200"), newest.map { it.url })

		parser.getListPage(1, SortOrder.POPULARITY, MangaListFilter(locale = Locale.JAPANESE))
		assertEquals("/languages/1", seenPaths.last())

		val details = parser.getDetails(newest.single())
		assertEquals(setOf("Artist A", "Circle B"), details.authors)
		val chapter = requireNotNull(details.chapters).single()
		assertEquals("English", chapter.title)

		val pages = parser.getPages(chapter)
		assertEquals(
			listOf(
				"https://cdn.imagedeliveries.com/200/deadbeef/1.jpg",
				"https://cdn.imagedeliveries.com/200/deadbeef/2.webp",
			),
			pages.map { it.url },
		)
	}

	private class TestContext(
		private val responder: (url: HttpUrl) -> String,
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
					.body(responder(req.url).toResponseBody("text/html; charset=utf-8".toMediaType()))
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
