package org.koitharu.kotatsu.parsers.site.en

import kotlinx.coroutines.test.runTest
import okhttp3.CookieJar
import okhttp3.HttpUrl
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
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.SortOrder

internal class MangaHereTest {

	@Test
	fun unpackJsHandlesLargePayloadsAndResolvesBothWebtoonAndChapterFunPages() = runTest {
		val longPadding = "x".repeat(25_000)
		val webtoonHtml = """
			<html><body>
				<script src="/static/chapter_bar.js"></script>
				<script>eval(function(p,a,c,k,e,d){return p}('/*$longPadding*/var 0=[\'//images.example/p1.jpg\',\'//images.example/p2.jpg\'];',10,1,'newImgs'.split('|'),0,{}))</script>
			</body></html>
		""".trimIndent()

		val pagedHtml = """
			<html><body>
				<script>var chapterid = 555; var imagecount = 2;</script>
				<script>eval(function(p,a,c,k,e,d){return p}('0=\'ab\'+\'cd\';',10,1,'guidkey'.split('|'),0,{}))</script>
			</body></html>
		""".trimIndent()

		val chapterFunPacked = """
			eval(function(p,a,c,k,e,d){return p}('function dm5imagefun(){var 0="//images.example/cdn";var 1=["/img1.jpg"];return 0+1[0]}',10,2,'pix|pvalue'.split('|'),0,{}))
		""".trimIndent()

		val dirHtml = """
			<html><body>
				<ul class="manga-list-1-list">
					<li><a href="/manga/sample_work/" title="Sample Work"><img class="manga-list-1-cover" src="https://images.example/cover.jpg"></a></li>
				</ul>
			</body></html>
		""".trimIndent()

		val seenFunKeys = ArrayList<String?>()
		val context = TestContext { url ->
			when (url.encodedPath) {
				"/directory/1.htm" -> dirHtml
				"/manga/sample_work/c001/1.html" -> webtoonHtml
				"/manga/sample_work/c002/1.html" -> pagedHtml
				"/manga/sample_work/c002/chapterfun.ashx" -> {
					val k = url.queryParameter("key")
					seenFunKeys.add(k)
					if (k == "abcd") "" else chapterFunPacked
				}

				else -> "<html/>"
			}
		}

		val parser = MangaHere(context)
		val list = parser.getListPage(1, SortOrder.UPDATED, MangaListFilter.EMPTY)
		assertEquals("/manga/sample_work/", list.single().url)

		val webtoonCh = MangaChapter(
			id = 1L,
			title = "Ch 1",
			number = 1f,
			volume = 0,
			url = "/manga/sample_work/c001/1.html",
			scanlator = null,
			uploadDate = 0L,
			branch = null,
			source = MangaParserSource.MANGAHERE,
		)
		val webtoonPages = parser.getPages(webtoonCh)
		assertEquals(
			listOf("https://images.example/p1.jpg", "https://images.example/p2.jpg"),
			webtoonPages.map { it.url },
		)

		val pagedCh = webtoonCh.copy(id = 2L, url = "/manga/sample_work/c002/1.html")
		val funPages = parser.getPages(pagedCh)
		assertEquals(2, funPages.size)
		assertEquals("https://images.example/cdn/img1.jpg", parser.getPageUrl(funPages.first()))
		assertEquals(listOf("abcd", ""), seenFunKeys)
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
