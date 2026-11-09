package org.koitharu.kotatsu.parsers.site.vi

import kotlinx.coroutines.test.runTest
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.SourceConfigMock
import org.koitharu.kotatsu.parsers.bitmap.Bitmap
import org.koitharu.kotatsu.parsers.bitmap.Rect
import org.koitharu.kotatsu.parsers.model.*

internal class MangaMoriTest {

	@Test
	fun flightJoinsChunksBeforeParsingAndPreservesEscapes() {
		val json = """{"comic":{"slug":"test","title":"A \"quoted\" title","description":"Line 1\nLine 2"},"chapter":{"pages":[]}}"""
		val html = "<script>self.__next_f.push([0])</script>" + flightHtml(json, json.indexOf("title") + 2)
		val flight = MangaMoriData.flight(Jsoup.parse(html))
		assertEquals(json, flight)
		val comic = MangaMoriData.objects(flight, "comic").single()
		assertEquals("A \"quoted\" title", comic.getString("title"))
		assertEquals("Line 1\nLine 2", comic.getString("description"))
		assertTrue(MangaMoriData.props(flight, "comic").single().has("chapter"))
	}

	@Test
	fun decodesLiveReaderMapWithJavascriptIntegerOverflow() {
		val expected = intArrayOf(53, 4, 9, 48, 51, 6, 20, 60, 44, 5, 45, 56, 40, 8, 21, 7,
			55, 2, 49, 58, 37, 0, 42, 17, 36, 19, 61, 23, 14, 63, 12, 15, 47, 18, 38, 29,
			43, 13, 11, 1, 26, 54, 46, 22, 50, 52, 25, 39, 10, 41, 34, 35, 33, 31, 57, 27,
			59, 30, 32, 16, 3, 24, 62, 28)
		assertArrayEquals(expected, MangaMoriData.decodeMap(encodedMap, nonce, 8, 8))
	}

	@Test
	fun rejectsMalformedMaps() {
		assertThrows(IllegalArgumentException::class.java) { MangaMoriData.decodeMap("AA==", nonce, 8, 8) }
		assertThrows(IllegalArgumentException::class.java) { MangaMoriData.decodeMap(encodedMap, "wrong", 8, 8) }
		assertThrows(IllegalArgumentException::class.java) { MangaMoriData.validateMap(intArrayOf(0, 0), 1, 2) }
		assertThrows(IllegalArgumentException::class.java) { MangaMoriData.validateMap(intArrayOf(0, 2), 1, 2) }
		assertThrows(IllegalArgumentException::class.java) { MangaMoriData.decodeMap(encodedMap, nonce, 0, 8) }
	}

	@Test
	fun redrawUsesDestinationToSourceMapAndPreservesRemainders() {
		val source = TestBitmap(5, 5)
		val output = MangaMoriData.redraw(source, 2, 2, intArrayOf(3, 0, 1, 2), ::TestBitmap) as TestBitmap
		assertEquals(5, output.draws.size)
		assertEquals(Rect(0, 0, 5, 5), output.draws[0].first)
		assertEquals(Rect(2, 2, 4, 4), output.draws[1].first)
		assertEquals(Rect(0, 0, 2, 2), output.draws[1].second)
		assertEquals(Rect(0, 2, 2, 4), output.draws[4].first)
		assertEquals(Rect(2, 2, 4, 4), output.draws[4].second)
	}

	@Test
	fun catalogueSearchDetailsAndPagesUseStableIdsAndBranches() = runTest {
		val comic = """{"slug":"test","title":"Test","status":"ongoing","thumbnail":"https://images.example/cover.png","author":"Author"}"""
		val home = flightHtml("""{"spotlightComic":$comic}{"comic":$comic}{"comic":{"slug":"recent-only","title":"Recent"}}{"currentPage":1,"totalPages":1}""")
		val details = """<script type="application/ld+json">{"@type":"Book","name":"Test","description":"Synopsis","author":{"name":"Author"}}</script>""" +
			flightHtml("""{"comicSlug":"test","isFavorited":false}{"comicSlug":"test","chapters":[{"chapterNumber":2,"title":null,"createdAt":"${'$'}D2026-01-02T00:00:00.000Z"},{"chapterNumber":1.5,"title":"Extra"}],"rawChapters":[{"chapterNumber":2,"title":"Raw title"}]}""")
		val reader = flightHtml("""{"comic":$comic,"chapter":{"pages":[{"id":"second","imageUrl":"https://images.example/2.webp","pageOrder":2,"isScrambled":false},{"id":"first","imageUrl":"https://images.example/1.webp","pageOrder":1,"isScrambled":true,"scrambleRows":8,"scrambleCols":8}]},"encryptedMap":"$encodedMap","mapNonce":"$nonce"}""")
		val context = TestContext { request ->
			when (request.url.encodedPath) {
				"/" -> home
				"/api/search" -> """{"data":[$comic]}"""
				"/truyen/test" -> details
				else -> reader
			}
		}
		val parser = MangaMori(context)
		val manga = parser.getListPage(1, SortOrder.UPDATED, MangaListFilter()).single()
		val search = parser.getListPage(1, SortOrder.UPDATED, MangaListFilter(query = "test & title")).single()
		assertEquals(manga.id, search.id)
		assertEquals("test & title", context.requests.last().url.queryParameter("q"))
		assertTrue(parser.getListPage(2, SortOrder.UPDATED, MangaListFilter()).isEmpty())
		assertTrue(parser.getListPage(2, SortOrder.UPDATED, MangaListFilter(query = "test")).isEmpty())
		val detailed = parser.getDetails(manga)
		assertEquals(manga.id, detailed.id)
		assertEquals(manga.url, detailed.url)
		assertEquals("Synopsis", detailed.description)
		val chapters = requireNotNull(detailed.chapters)
		assertEquals(listOf(1.5f, 2f, 2f), chapters.map { it.number })
		assertEquals(3, chapters.map { it.id }.toSet().size)
		assertEquals("/truyen/test/2?mode=raw", chapters.last().url)
		assertEquals("Raw", chapters.last().branch)
		assertEquals(1767312000000L, chapters[1].uploadDate)
		val pages = parser.getPages(chapters.first())
		assertTrue(pages.first().url.startsWith("https://images.example/1.webp#mangamori=8,8;"))
		assertEquals("https://images.example/2.webp", pages.last().url)

		// A fresh parser can reconstruct a persisted page without an in-memory key cache.
		val imageParser = MangaMori(context)
		val imageClient = context.httpClient.newBuilder().apply { interceptors().add(0, imageParser) }.build()
		imageClient
			.newCall(Request.Builder().url(pages.first().url).build()).execute().use { assertTrue(it.isSuccessful) }
		assertEquals(1, context.redraws)
		imageClient.newCall(Request.Builder().url(pages.last().url).build()).execute().close()
		assertEquals(1, context.redraws)
	}

	private class TestBitmap(override val width: Int, override val height: Int) : Bitmap {
		val draws = mutableListOf<Pair<Rect, Rect>>()
		override fun drawBitmap(sourceBitmap: Bitmap, src: Rect, dst: Rect) { draws += src to dst }
	}

	private class TestContext(val body: (Request) -> String) : MangaLoaderContext() {
		val requests = mutableListOf<Request>()
		var redraws = 0
		override val cookieJar = CookieJar.NO_COOKIES
		override val httpClient: OkHttpClient
			get() = OkHttpClient.Builder().addInterceptor { chain ->
				val request = chain.request()
				requests += request
				// This is the transport; earlier application interceptors still receive the response.
				Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
					.body(body(request).toResponseBody()).build()
			}.build()
		override fun getConfig(source: MangaSource) = SourceConfigMock()
		override fun getDefaultUserAgent() = "Mozilla/5.0"
		@Deprecated("Provide a base url")
		override suspend fun evaluateJs(script: String): String? = error("Unused")
		override suspend fun evaluateJs(baseUrl: String, script: String, timeout: Long): String? = error("Unused")
		override fun redrawImageResponse(response: Response, redraw: (Bitmap) -> Bitmap): Response {
			redraws++
			redraw(TestBitmap(1024, 1464))
			return response
		}
		override fun createBitmap(width: Int, height: Int): Bitmap = TestBitmap(width, height)
	}

	private fun flightHtml(data: String, split: Int = data.length / 2): String =
		listOf(data.substring(0, split), data.substring(split)).joinToString("") {
			"<script>self.__next_f.push(${JSONArray().put(1).put(it)})</script>"
		}

	private companion object {
		const val encodedMap = "RTArhleXmVLJZYZJTJUoX6teSlydJLssqg2Ww1GoTJ783nY3pPhciUQkRAL/I3xBENYnYHXZx7Rb0o7TSKFkSwsBt3NjxgcLcbItm9+9bsLOu0NLgRvm8ct9nC4428as/pGl1KK28BhqLhf3htdVVf0ueBpZ3nCxEcOZPLucyDvuWhc8+BI="
		const val nonce = "6b7438aa1a7449fc"
	}
}
