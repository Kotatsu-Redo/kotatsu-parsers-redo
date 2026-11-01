package org.koitharu.kotatsu.parsers.site.id

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
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.SortOrder

internal class KomikApkTest {

	@Test
	fun resolvesDepthRelativeCardsMultiUploaderBranchesAndFiltersPlaceholderImages() = runTest {
		val pustakaHtml = """
			<html><body>
				<a href="/pustaka/semua/semua/terbaru/1">SEMUA</a>
				<a href="/pustaka/semua/adult/terbaru/1?include_adult=true">ADULT</a>
				<a href="/pustaka/semua/adult/terbaru/1">adult</a>
				<a href="../../../../komik/sample-komik">
					<img src="https://images.example/cover.webp">
					<div class="font-semibold">Sample Komik</div>
				</a>
			</body></html>
		""".trimIndent()

		val detailsGroupA = """
			<html><body>
				<h1>Sample Komik</h1>
				<div class="font-display mt-5 text-center">Komik ini dibuat oleh komikus bernama Writer X ini bercerita tentang petualangan.</div>
				<span>BY:</span><span>TeamA</span>
				<details><ul class="dropdown-content">
					<li><a href="?uploader=TeamA">TeamA</a></li>
					<li><a href="?uploader=TeamB">TeamB</a></li>
				</ul></details>
				<div class="join-vertical">
					<a href="/komik/sample-komik/chapter-1-a">Chapter 1</a>
				</div>
			</body></html>
		""".trimIndent()

		val detailsGroupB = """
			<html><body>
				<h1>Sample Komik</h1>
				<div class="join-vertical">
					<a href="/komik/sample-komik/chapter-1-b">Chapter 1</a>
				</div>
			</body></html>
		""".trimIndent()

		val readerHtml = """
			<html><body>
				<section>
					<img src="/loading.gif">
					<img src="/cover_global.png">
					<img src="https://images.example/komik-chapter/01.webp">
				</section>
			</body></html>
		""".trimIndent()

		val seenQueries = ArrayList<String?>()
		val context = TestContext { url ->
			seenQueries.add(url.encodedQuery)
			when {
				url.encodedPath.startsWith("/pustaka/") -> pustakaHtml
				url.encodedPath == "/komik/sample-komik" && url.queryParameter("uploader") == "TeamB" -> detailsGroupB
				url.encodedPath == "/komik/sample-komik" -> detailsGroupA
				url.encodedPath == "/komik/sample-komik/chapter-1-a" -> readerHtml
				else -> "<html/>"
			}
		}

		val parser = KomikApk(context)
		val tags = parser.getFilterOptions().availableTags
		assertEquals(listOf("adult"), tags.map { it.key })

		val item = parser.getListPage(1, SortOrder.UPDATED, MangaListFilter.EMPTY).single()
		assertEquals("include_adult=true", seenQueries.last())
		assertEquals("/komik/sample-komik", item.url)

		parser.getListPage(1, SortOrder.UPDATED, MangaListFilter(contentRating = setOf(ContentRating.SAFE)))
		assertEquals(null, seenQueries.last())

		val details = parser.getDetails(item)
		assertEquals(setOf("Writer X"), details.authors)
		val chapters = requireNotNull(details.chapters)
		assertEquals(listOf("TeamA", "TeamB"), chapters.map { it.branch })

		val pages = parser.getPages(chapters.first())
		assertEquals(listOf("https://images.example/komik-chapter/01.webp"), pages.map { it.url })
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
