package org.koitharu.kotatsu.parsers.site.iken.en

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
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.SortOrder

internal class SanaScansTest {

	@Test
	fun listGenresAndDetailsPreserveSlugUidAndFilterLockedChapters() = runTest {
		val queryResp = """
			{
				"posts": [
					{
						"id": 42,
						"slug": "sample-series",
						"postTitle": "Sample Series",
						"featuredImage": "https://images.example/cover.webp",
						"seriesStatus": "ONGOING",
						"isNovel": false,
						"author": "Author A",
						"genres": [{"id": 7, "name": "fantasy"}]
					},
					{
						"id": 99,
						"slug": "ignored-novel",
						"postTitle": "Ignored Novel",
						"isNovel": true
					},
					{
						"id": 100,
						"slug": "untagged-novel",
						"postTitle": "Untagged Novel",
						"isNovel": false,
						"seriesType": "NOVEL"
					}
				]
			}
		""".trimIndent()

		val genresResp = """[{"id": 7, "name": "fantasy"}, {"id": 9, "name": "action"}]"""

		val postResp = """
			{
				"post": {
					"id": 42,
					"slug": "sample-series",
					"postTitle": "Sample Series",
					"postContent": "Synopsis text",
					"alternativeTitles": "Alt A | Alt B",
					"featuredImage": "https://images.example/cover.webp",
					"author": "Author A",
					"artist": "Artist B",
					"seriesStatus": "ONGOING",
					"genres": [{"id": 7, "name": "fantasy"}]
				}
			}
		""".trimIndent()

		val chaptersResp = """
			{
				"post": {
					"chapters": [
						{"id": 103, "slug": "chapter-3", "number": 3, "isLocked": true, "isAccessible": false, "createdAt": "2026-03-03T00:00:00.000Z"},
						{"id": 102, "slug": "chapter-2", "number": 2, "title": "Part Two", "isLocked": false, "isAccessible": true, "createdAt": "2026-03-02T00:00:00.000Z"},
						{"id": 101, "slug": "chapter-1", "number": 1, "isLocked": false, "isAccessible": true, "createdAt": "2026-03-01T00:00:00.000Z"}
					]
				}
			}
		""".trimIndent()

		val context = TestContext { path ->
			when (path) {
				"/api/query" -> queryResp
				"/api/genres" -> genresResp
				"/api/post" -> postResp
				"/api/chapters" -> chaptersResp
				else -> "{}"
			}
		}

		val parser = SanaScans(context)
		assertTrue(SortOrder.UPDATED in parser.availableSortOrders)
		assertTrue(SortOrder.POPULARITY in parser.availableSortOrders)
		assertTrue(SortOrder.NEWEST in parser.availableSortOrders)
		assertTrue(SortOrder.ALPHABETICAL in parser.availableSortOrders)

		val filterOpts = parser.getFilterOptions()
		assertEquals(setOf(ContentType.MANGA, ContentType.MANHWA), filterOpts.availableContentTypes)
		val tags = filterOpts.availableTags
		assertEquals(setOf("7", "9"), tags.mapTo(HashSet()) { it.key })
		assertEquals(setOf("Fantasy", "Action"), tags.mapTo(HashSet()) { it.title })

		val listItem = parser.getListPage(1, SortOrder.UPDATED, MangaListFilter.EMPTY).single()
		assertEquals("/series/sample-series", listItem.url)
		assertEquals(MangaState.ONGOING, listItem.state)

		val details = parser.getDetails(listItem)
		assertEquals(listItem.id, details.id)
		assertEquals("Synopsis text", details.description)
		assertEquals(setOf("Alt A", "Alt B"), details.altTitles)
		assertEquals(setOf("Author A", "Artist B"), details.authors)

		val chapters = requireNotNull(details.chapters)
		assertEquals(listOf(1f, 2f), chapters.map { it.number })
		assertEquals(listOf("/series/sample-series/chapter-1", "/series/sample-series/chapter-2"), chapters.map { it.url })
	}

	private class TestContext(
		private val responder: (path: String) -> String,
	) : MangaLoaderContext() {
		override val parsers: org.koitharu.kotatsu.parsers.ParsersBundle
			get() = org.koitharu.kotatsu.parsers.core.ParsersBundleImpl

		override val cookieJar: CookieJar = CookieJar.NO_COOKIES
		override val httpClient: OkHttpClient = OkHttpClient.Builder()
			.addInterceptor { chain ->
				val req = chain.request()
				val body = responder(req.url.encodedPath)
				Response.Builder()
					.request(req)
					.protocol(Protocol.HTTP_1_1)
					.code(200)
					.message("OK")
					.body(body.toResponseBody("application/json; charset=utf-8".toMediaType()))
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
