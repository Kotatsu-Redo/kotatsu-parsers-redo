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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.SourceConfigMock
import org.koitharu.kotatsu.parsers.bitmap.Bitmap
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.SortOrder

internal class KappaBeastTest {

	@Test
	fun strapiQueriesDeduplicateCategoriesAndUpgradeBloggerPageUrls() = runTest {
		val categoriesJson = """
			{
				"data": [
					{"name": "Fantasy"},
					{"name": "fantasy"},
					{"name": "Comedy"}
				]
			}
		""".trimIndent()

		val mangasJson = """
			{
				"data": [
					{
						"documentId": "doc-manga-1",
						"slug": "sample-work",
						"title": "Sample Work",
						"altTitle": "Alt One, Alt Two",
						"description": "Sample description",
						"author": "Writer A",
						"artist": "Artist B",
						"manga_status": "Ongoing",
						"media": [{
							"coverImage": {
								"url": "/uploads/cover_full.jpg",
								"formats": {
									"small": {"url": "/uploads/small_cover.jpg"},
									"large": {"url": "/uploads/large_cover.jpg"}
								}
							}
						}],
						"category": [{"name": "Fantasy"}],
						"chapters": [
							{"documentId": "doc-ch-1", "number": 1, "title": "Opening", "createdAt": "2026-02-01T12:00:00.000Z"}
						]
					}
				]
			}
		""".trimIndent()

		val chapterJson = """
			{
				"data": {
					"htmlContent": "<div class=\"separator\"><a href=\"https://blogger.googleusercontent.com/img/b/R29vZ2xl/AVvXsEg/w800/page1.jpg\"><img src=\"https://blogger.googleusercontent.com/img/b/R29vZ2xl/AVvXsEg/w400/page1.jpg\"/></a></div>"
				}
			}
		""".trimIndent()

		val seenUrls = ArrayList<HttpUrl>()
		val context = TestContext { url ->
			seenUrls.add(url)
			when (url.encodedPath) {
				"/api/categories" -> categoriesJson
				"/api/mangas" -> mangasJson
				"/api/chapters/doc-ch-1" -> chapterJson
				else -> """{"data":[]}"""
			}
		}

		val parser = KappaBeast(context)
		val filterOptions = parser.getFilterOptions()
		assertTrue(filterOptions.availableContentTypes.isEmpty())
		val tags = filterOptions.availableTags
		assertEquals(listOf("Fantasy", "Comedy"), tags.map { it.title })
		assertEquals("true", seenUrls.first().queryParameter("filters[manga][id][\$notNull]"))

		val filter = MangaListFilter(
			tags = setOf(
				MangaTag("Fantasy", "Fantasy", MangaParserSource.KAPPABEAST),
				MangaTag("Comedy", "Comedy", MangaParserSource.KAPPABEAST),
			),
			year = 2025,
		)
		val item = parser.getListPage(1, SortOrder.UPDATED, filter).single()
		val listUrl = seenUrls.last()
		assertEquals("Fantasy", listUrl.queryParameter("filters[category][name][\$in][0]"))
		assertEquals("Comedy", listUrl.queryParameter("filters[category][name][\$in][1]"))
		assertEquals("2025", listUrl.queryParameter("filters[releaseYear][\$eq]"))
		assertEquals("https://strapi.kappabeast.com/uploads/small_cover.jpg", item.coverUrl)
		assertEquals("https://strapi.kappabeast.com/uploads/large_cover.jpg", item.largeCoverUrl)
		assertEquals(MangaState.ONGOING, item.state)
		assertEquals(setOf("Alt One", "Alt Two"), item.altTitles)

		val details = parser.getDetails(item)
		val chapter = requireNotNull(details.chapters).single()
		assertEquals("Chapter 1 - Opening", chapter.title)

		val pages = parser.getPages(chapter)
		assertEquals(
			"https://blogger.googleusercontent.com/img/b/R29vZ2xl/AVvXsEg/s0/page1.jpg",
			pages.single().url,
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
					.body(responder(req.url).toResponseBody("application/json; charset=utf-8".toMediaType()))
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
