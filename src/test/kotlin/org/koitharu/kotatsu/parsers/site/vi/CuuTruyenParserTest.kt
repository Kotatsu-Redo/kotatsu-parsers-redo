package org.koitharu.kotatsu.parsers.site.vi

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.SourceConfigMock
import org.koitharu.kotatsu.parsers.bitmap.Bitmap
import org.koitharu.kotatsu.parsers.bitmap.Rect
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.LinkResolver
import java.util.Base64

internal class CuuTruyenParserTest {

	@Test
	fun decodesLiveStripMapAndPreservesShortRemainder() {
		val strips = CuuTruyenDrm.decode(Base64.getMimeDecoder().decode(DRM))
		assertEquals(listOf(300 to 150, 900 to 150, 450 to 150, 0 to 150,
			1050 to 102, 150 to 150, 750 to 150, 600 to 150), strips)
		val result = CuuTruyenDrm.redraw(TestBitmap(2048, 1152), strips, ::TestBitmap) as TestBitmap
		assertEquals(8, result.draws.size)
		assertEquals(Rect(0, 0, 2048, 150) to Rect(0, 300, 2048, 450), result.draws[0])
		assertEquals(Rect(0, 600, 2048, 702) to Rect(0, 1050, 2048, 1152), result.draws[4])
	}

	@Test
	fun rejectsUnknownVersionsAndInvalidMaps() {
		assertThrows(IllegalArgumentException::class.java) { CuuTruyenDrm.decode(byteArrayOf(0)) }
		for (map in listOf("", "0-0", "-1-20", "0-10-20")) {
			assertThrows(IllegalArgumentException::class.java) { CuuTruyenDrm.parse(map) }
		}
		for (map in listOf("0-10|0-10", "0-10", "0-30", "10-10|20-10")) {
			assertThrows(IllegalArgumentException::class.java) {
				CuuTruyenDrm.redraw(TestBitmap(10, 20), CuuTruyenDrm.parse(map), ::TestBitmap)
			}
		}
	}

	@Test
	fun catalogueSearchTagsDetailsAndPersistedPages() = runTest {
		val context = TestContext { request ->
			when (request.url.encodedPath) {
				"/api/v2/tags/popular" -> """{"data":{"common_tags":[$TAG],"warning_tags":[],"normal_tags":[]}}"""
				"/api/v2/mangas/42" -> """{"data":{
					"id":42,"name":"Test Manga","cover_url":"https://images.example/cover.jpg",
					"author":{"name":"Author"},"team":{"name":"Team"},"full_description":"<p>Synopsis</p>",
					"titles":[{"name":"Test Manga"},{"name":"Alternative"}],"tags":[$TAG],"is_nsfw":true
				}}"""
				"/api/v2/mangas/42/chapters" -> """{"data":[
					{"id":12,"order":2,"number":"Extra","name":"Special"},
					{"id":11,"order":1,"number":"1.5","name":null,"created_at":"2026-01-02T07:00:00.000+07:00"}
				]}"""
				"/api/v2/chapters/11" -> """{"data":{"pages":[
					{"id":102,"order":1,"image_url":"https://images.example/2.jpg","drm_data":null},
					{"id":101,"order":0,"image_url":"https://images.example/1.jpg","drm_data":"${DRM.replace("\n", "\\n")}"}
				]}}"""
				else -> """{"data":[$CARD]}"""
			}
		}
		val parser = CuuTruyenParser(context)
		assertEquals("cuutruyen.net", parser.domain)
		val manga = parser.getListPage(1, SortOrder.UPDATED, MangaListFilter()).single()
		assertEquals("/api/v2/mangas/recently_updated", context.requests.last().url.encodedPath)
		assertEquals("/api/v2/mangas/42", manga.url)
		assertEquals("https://cuutruyen.net/mangas/42", manga.publicUrl)
		parser.getListPage(2, SortOrder.POPULARITY, MangaListFilter())
		assertEquals("/api/v2/mangas/top", context.requests.last().url.encodedPath)
		assertEquals("2", context.requests.last().url.queryParameter("page"))
		assertEquals("24", context.requests.last().url.queryParameter("per_page"))
		assertEquals("all", context.requests.last().url.queryParameter("duration"))
		val tag = parser.getFilterOptions().availableTags.single()
		val search = parser.getListPage(1, SortOrder.UPDATED, MangaListFilter(query = "a & b", tags = setOf(tag))).single()
		assertEquals(manga.id, search.id)
		assertEquals("/api/v2/mangas/search", context.requests.last().url.encodedPath)
		assertEquals("a & b", context.requests.last().url.queryParameter("q"))
		assertEquals("\"đã hoàn thành\"", context.requests.last().url.queryParameter("tags"))
		assertTrue(context.requests.all { it.header("Cuutruyen-Client") == "OfficialWebApp-20250805" })

		val details = parser.getDetails(manga)
		assertEquals(manga.id, details.id)
		assertEquals(manga.url, details.url)
		assertEquals(setOf("Alternative"), details.altTitles)
		assertEquals(setOf("Author"), details.authors)
		assertEquals(MangaState.FINISHED, details.state)
		assertEquals(ContentRating.ADULT, details.contentRating)
		assertEquals("<p>Synopsis</p>", details.description)
		val chapters = requireNotNull(details.chapters)
		assertEquals(listOf("/api/v2/chapters/11", "/api/v2/chapters/12"), chapters.map { it.url })
		assertEquals(1.5f, chapters.first().number)
		assertNull(chapters.first().title)
		assertEquals("Extra - Special", chapters.last().title)
		assertEquals("Team", chapters.first().scanlator)
		assertEquals(1767312000000L, chapters.first().uploadDate)
		assertEquals(listOf(manga.id), parser.getRelatedManga(manga).map { it.id })
		for (path in listOf("/mangas/42", "/mangas/42/chapters/11", "/api/v2/mangas/42")) {
			val link = "https://cuutruyen.net$path".toHttpUrl()
			assertEquals(manga.id, parser.resolveLink(LinkResolver(context, link), link)?.id)
		}
		val unrelated = "https://other.example/mangas/42".toHttpUrl()
		assertNull(parser.resolveLink(LinkResolver(context, unrelated), unrelated))

		val pages = parser.getPages(chapters.first())
		assertTrue(pages.first().url.startsWith("https://images.example/1.jpg#cuutruyen=300-150|"))
		assertEquals("https://images.example/2.jpg", pages.last().url)
		val freshParser = CuuTruyenParser(context)
		val imageClient = context.httpClient.newBuilder().apply { interceptors().add(0, freshParser) }.build()
		for (page in pages) imageClient.newCall(Request.Builder().url(page.url).build()).execute().close()
		assertEquals(1, context.redraws)
	}

	@Test
	fun savedMoeEntryRequiresUniqueExactTitleAndRetainsIdentity() = runTest {
		var cards = CARD
		val context = TestContext { request ->
			when (request.url.encodedPath) {
				"/api/v2/mangas/42" -> """{"data":{"id":42,"name":"Test Manga","tags":[],"titles":[]}}"""
				"/api/v2/mangas/42/chapters" -> """{"data":[]}"""
				else -> """{"data":[$cards]}"""
			}
		}
		val parser = CuuTruyenParser(context)
		val saved = parser.getListPage(1, SortOrder.UPDATED, MangaListFilter()).single()
			.copy(id = 123L, url = "/truyen/test-manga")
		val details = parser.getDetails(saved)
		assertEquals(saved.id, details.id)
		assertEquals(saved.url, details.url)
		assertEquals("https://cuutruyen.net/mangas/42", details.publicUrl)
		cards = CARD.replace("Test Manga", "Different Manga")
		assertThrows(ParseException::class.java) { runBlocking { parser.getDetails(saved) } }
		cards = "$CARD,$CARD"
		assertThrows(ParseException::class.java) { runBlocking { parser.getDetails(saved) } }
	}

	@Test
	fun rewritesRetiredStorageHostsIncludingPersistedImages() = runTest {
		val context = TestContext { """{"data":[{"id":42,"name":"Test","cover_url":"https://storage-ct.lrclib.net/cover.jpg"}]}""" }
		val parser = CuuTruyenParser(context)
		assertEquals("https://storage-bravo.cuutruyen.net/cover.jpg",
			parser.getListPage(1, SortOrder.UPDATED, MangaListFilter()).single().coverUrl)
		val client = context.httpClient.newBuilder().apply { interceptors().add(0, parser) }.build()
		client.newCall(Request.Builder().url("https://storage-ct-riften.site/page.jpg").build()).execute().close()
		assertEquals("storage-charlie.cuutruyen.net", context.requests.last().url.host)
	}

	@Test
	fun emptyReaderFailsInsteadOfReturningNoPages() = runTest {
		val parser = CuuTruyenParser(TestContext { """{"data":{"pages":[]}}""" })
		val chapter = MangaChapter(1L, "Chapter", 1f, 0, "/api/v2/chapters/1", null, 0L, null, MangaParserSource.CUUTRUYEN)
		assertThrows(ParseException::class.java) { runBlocking { parser.getPages(chapter) } }
	}

	private class TestBitmap(override val width: Int, override val height: Int) : Bitmap {
		val draws = mutableListOf<Pair<Rect, Rect>>()
		override fun drawBitmap(sourceBitmap: Bitmap, src: Rect, dst: Rect) { draws += src to dst }
	}

	private class TestContext(val body: (Request) -> String) : MangaLoaderContext() {
		val requests = mutableListOf<Request>()
		var redraws = 0
		override val cookieJar = CookieJar.NO_COOKIES
		override val httpClient: OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
			val request = chain.request()
			requests += request
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
			redraw(TestBitmap(2048, 1152))
			return response
		}
		override fun createBitmap(width: Int, height: Int): Bitmap = TestBitmap(width, height)
	}

	private companion object {
		const val CARD = """{"id":42,"name":"Test Manga","cover_url":"https://images.example/cover.jpg"}"""
		const val TAG = """{"name":"đã hoàn thành","slug":"da-hoan-thanh"}"""
		const val DRM = "EEcATQYJAhsEBgVEAAcJHgIEBE0BDAIbBAYFRAkaCAYDTQUBAAkfBwUBSQkM\nBxQCBgFIBgAJHwcAA0kOCQcUAgYB\n"
	}
}
