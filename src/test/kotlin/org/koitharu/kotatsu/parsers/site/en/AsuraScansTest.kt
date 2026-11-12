package org.koitharu.kotatsu.parsers.site.en

import kotlinx.coroutines.test.runTest
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.SourceConfigMock
import org.koitharu.kotatsu.parsers.bitmap.Bitmap
import org.koitharu.kotatsu.parsers.bitmap.Rect
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.config.MangaSourceConfig
import org.koitharu.kotatsu.parsers.exception.NotFoundException
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import java.util.EnumSet

internal class AsuraScansTest {

	@Test
	fun apiFirstListNormalizesRatingsStripsTokensAndFallsBackForUpcoming() = runTest {
		val apiSeriesListJson = """
			{
				"data": [
					{
						"slug": "omniscient-readers-viewpoint-7f873ca6",
						"public_url": "/comics/omniscient-readers-viewpoint-7f873ca6",
						"title": "Omniscient Reader's Viewpoint",
						"cover": "https://gg.asuracomic.net/storage/media/1/cover.webp",
						"rating": 9.4,
						"status": "ongoing",
						"genres": [{"name": "Action", "slug": "action"}]
					},
					{
						"slug": "unrated-work",
						"title": "Unrated Work",
						"rating": 0,
						"status": "completed"
					}
				]
			}
		""".trimIndent()

		val browseUpcomingHtml = """
			<html><body>
			<div id="series-grid">
				<div class="series-card">
					<a href="/comics/upcoming-work-7f873ca6">
						<img src="https://gg.asuracomic.net/storage/media/2/cover.webp" />
						<h3>Upcoming Work</h3>
						<div class="absolute top-2 right-2"><span>8.5</span></div>
						<div class="p-3"><span>Manhwa</span><span>Coming Soon</span></div>
					</a>
				</div>
			</div>
			</body></html>
		""".trimIndent()

		val requestedUrls = ArrayList<String>()
		val context = TestContext { req ->
			requestedUrls += req.url.toString()
			when ("${req.url.host}${req.url.encodedPath}") {
				"api.asurascans.com/api/series" -> 200 to apiSeriesListJson
				"asurascans.com/browse" -> 200 to browseUpcomingHtml
				else -> 404 to "Not Found"
			}
		}

		val parser = AsuraScansParser(context).also { context.parserInterceptor = it }

		val list = parser.getListPage(1, SortOrder.UPDATED, MangaListFilter.EMPTY)
		assertEquals(
			listOf("https://api.asurascans.com/api/series?offset=0&limit=20&sort=latest&order=desc"),
			requestedUrls,
		)
		assertEquals(2, list.size)
		assertEquals("/comics/omniscient-readers-viewpoint", list[0].url)
		assertEquals("https://asurascans.com/comics/omniscient-readers-viewpoint-7f873ca6", list[0].publicUrl)
		assertEquals(0.94f, list[0].rating, 0.0001f)
		assertEquals(MangaState.ONGOING, list[0].state)
		assertEquals(RATING_UNKNOWN, list[1].rating)
		assertEquals(MangaState.FINISHED, list[1].state)

		requestedUrls.clear()
		val upcomingFilter = MangaListFilter(states = EnumSet.of(MangaState.UPCOMING))
		val upcoming = parser.getListPage(1, SortOrder.NEWEST, upcomingFilter)
		assertEquals(1, upcoming.size)
		assertTrue(requestedUrls.single().startsWith("https://asurascans.com/browse?"))
		assertEquals("/comics/upcoming-work", upcoming[0].url)
		assertEquals(0.85f, upcoming[0].rating, 0.0001f)
		assertEquals(MangaState.UPCOMING, upcoming[0].state)
	}

	@Test
	fun apiFirstDetailsAvoidsHtmlRouteAndFallsBackToTokenizedAstroHtml() = runTest {
		val seriesJson = """
			{
				"data": {
					"series": {
						"slug": "sample-work",
						"public_url": "/comics/sample-work-7f873ca6",
						"title": "Sample Work",
						"cover": "https://gg.asuracomic.net/storage/media/covers/sample.webp",
						"description": "<p>Plot summary</p>",
						"status": "ongoing",
						"rating": 9.2,
						"author": "Writer A",
						"artist": "Artist B",
						"alternativeTitles": "Alt One • Alt Two",
						"genres": [{"name": "Action", "slug": "action"}, {"name": "Fantasy", "slug": "fantasy"}]
					}
				}
			}
		""".trimIndent()

		val chaptersJson = """
			{
				"data": [
					{"number": 3, "title": "Locked", "is_locked": true, "published_at": "2026-03-03T12:00:00Z"},
					{"number": 2, "title": "Second", "is_locked": false, "published_at": "2026-03-02T12:00:00Z"},
					{"number": 1, "title": "", "is_premium": false, "published_at": "2026-03-01T12:00:00Z"}
				]
			}
		""".trimIndent()

		val astroDetailsHtml = """
			<html><body>
			<astro-island props="{&quot;title&quot;:[0,&quot;Astro Fallback Title&quot;],&quot;description&quot;:[0,&quot;Astro plot&quot;],&quot;status&quot;:[0,&quot;completed&quot;],&quot;rating&quot;:[0,8.8],&quot;author&quot;:[0,&quot;Astro Author&quot;],&quot;genres&quot;:[1,[[0,{&quot;name&quot;:[0,&quot;Action&quot;],&quot;slug&quot;:[0,&quot;action&quot;]}]]]}"></astro-island>
			<astro-island props="{&quot;chapters&quot;:[1,[[0,{&quot;number&quot;:[0,1],&quot;title&quot;:[0,&quot;Opening&quot;],&quot;is_locked&quot;:[0,false],&quot;published_at&quot;:[0,&quot;2026-03-01T00:00:00Z&quot;]}]]]}"></astro-island>
			</body></html>
		""".trimIndent()

		var apiOnline = true
		val requestedUrls = ArrayList<String>()
		val context = TestContext { req ->
			val key = "${req.url.host}${req.url.encodedPath}"
			requestedUrls += key
			when {
				apiOnline && key == "api.asurascans.com/api/series/sample-work" -> 200 to seriesJson
				apiOnline && key == "api.asurascans.com/api/series/sample-work/chapters" -> 200 to chaptersJson
				!apiOnline && key == "asurascans.com/comics/sample-work-7f873ca6" -> 200 to astroDetailsHtml
				else -> 503 to "Unavailable"
			}
		}

		val parser = AsuraScansParser(context)
		val seed = Manga(
			id = 1L,
			title = "Sample Work",
			altTitles = emptySet(),
			url = "/comics/sample-work-7f873ca6",
			publicUrl = "https://asurascans.com/comics/sample-work-7f873ca6",
			rating = RATING_UNKNOWN,
			contentRating = null,
			coverUrl = null,
			tags = emptySet(),
			state = null,
			authors = emptySet(),
			source = MangaParserSource.ASURASCANS,
		)

		val details = parser.getDetails(seed)
		assertEquals(
			listOf(
				"api.asurascans.com/api/series/sample-work",
				"api.asurascans.com/api/series/sample-work/chapters",
			),
			requestedUrls,
		)
		assertEquals("Sample Work", details.title)
		assertEquals("<p>Plot summary</p>", details.description)
		assertEquals(MangaState.ONGOING, details.state)
		assertEquals(0.92f, details.rating, 0.0001f)
		assertEquals(setOf("Writer A", "Artist B"), details.authors)
		assertEquals(setOf("Alt One", "Alt Two"), details.altTitles)
		assertEquals(setOf("Action", "Fantasy"), details.tags.map { it.title }.toSet())
		val chapters = requireNotNull(details.chapters)
		assertEquals(listOf("/comics/sample-work/chapter/1", "/comics/sample-work/chapter/2"), chapters.map { it.url })
		assertEquals(listOf("Chapter 1", "Chapter 2 - Second"), chapters.map { it.title })

		// Now simulate API outage: parser reuses cached live token without hitting tokenless /comics/sample-work
		apiOnline = false
		requestedUrls.clear()
		val fallbackDetails = parser.getDetails(seed.copy(url = "/comics/sample-work", publicUrl = "https://asurascans.com/comics/sample-work"))
		assertTrue("asurascans.com/comics/sample-work-7f873ca6" in requestedUrls)
		assertTrue("asurascans.com/comics/sample-work" !in requestedUrls)
		assertEquals("Astro Fallback Title", fallbackDetails.title)
		assertEquals("Astro plot", fallbackDetails.description)
		assertEquals(MangaState.FINISHED, fallbackDetails.state)
		assertEquals(0.88f, fallbackDetails.rating, 0.0001f)
		assertEquals(listOf("/comics/sample-work/chapter/1"), requireNotNull(fallbackDetails.chapters).map { it.url })
	}

	@Test
	fun apiFirstPagesSendsReaderTokenEncodesTilesAndFallsBackToHtml() = runTest {
		val apiChapterJson = """
			{
				"data": {
					"chapter": {
						"pages": [
							{"order": 2, "url": "https://gg.asuracomic.net/storage/media/c2/02.webp", "tiles": [1, 0], "tile_cols": 2, "tile_rows": 1},
							{"order": 1, "url": "https://gg.asuracomic.net/storage/media/c2/01.webp"}
						]
					}
				}
			}
		""".trimIndent()

		val astroChapterHtml = """
			<html><body>
			<astro-island component-url="/_astro/Reader.9f81bc20.js" opts="{&quot;name&quot;:&quot;ChapterReader&quot;}"
				props="{&quot;pages&quot;:[1,[[0,{&quot;order&quot;:[0,2],&quot;url&quot;:[0,&quot;https://cdn.asurascans.com/asura-images/chapters/c1/02.webp&quot;],&quot;tiles&quot;:[1,[[0,1],[0,0]]],&quot;tile_cols&quot;:[0,2],&quot;tile_rows&quot;:[0,1]}],[0,{&quot;order&quot;:[0,1],&quot;url&quot;:[0,&quot;https://cdn.asurascans.com/asura-images/chapters/c1/01.webp&quot;]}]]]}">
			</astro-island>
			</body></html>
		""".trimIndent()

		val directImgHtml = """
			<html><body>
			<header><img src="https://cdn.asurascans.com/asura-images/logos/logo.webp" /></header>
			<main>
				<img src="https://cdn.asurascans.com/asura-images/covers/omniscient-cover.webp" />
				<img src="https://cdn.asurascans.com/asura-images/chapters/orv-300/01.webp" />
				<img data-src="https://cdn.asurascans.com/asura-images/chapters/orv-300/02.webp" />
				<img src="https://cdn.asurascans.com/asura-images/avatars/user1.webp" />
			</main>
			</body></html>
		""".trimIndent()

		val observedPageTokens = ArrayList<String?>()
		val requestedKeys = ArrayList<String>()
		val context = TestContext { req ->
			val key = "${req.url.host}${req.url.encodedPath}"
			requestedKeys += key
			if (req.url.host == "api.asurascans.com") {
				observedPageTokens += req.header("X-Page-Token")
			}
			when (key) {
				"api.asurascans.com/api/series/sample-work/chapters/2" -> 200 to apiChapterJson
				"asurascans.com/comics/sample-work/chapter/1" -> 200 to astroChapterHtml
				"asurascans.com/comics/omniscient-readers-viewpoint/chapter/300" -> 200 to directImgHtml
				else -> 404 to "Lost in the Void"
			}
		}

		val parser = AsuraScansParser(context)

		val ch2 = MangaChapter(2L, "Chapter 2", 2f, 0, "/comics/sample-work-ab39de10/chapter/2", null, 0L, null, MangaParserSource.ASURASCANS)
		assertEquals(
			listOf(
				"https://gg.asuracomic.net/storage/media/c2/01.webp",
				"https://gg.asuracomic.net/storage/media/c2/02.webp#tiles=2x1:1,0",
			),
			parser.getPages(ch2).map { it.url },
		)
		assertEquals(listOf("api.asurascans.com/api/series/sample-work/chapters/2"), requestedKeys)
		assertEquals(listOf("asura-reader-2026"), observedPageTokens)

		val ch1 = MangaChapter(1L, "Chapter 1", 1f, 0, "/comics/sample-work/chapter/1", null, 0L, null, MangaParserSource.ASURASCANS)
		assertEquals(
			listOf(
				"https://cdn.asurascans.com/asura-images/chapters/c1/01.webp",
				"https://cdn.asurascans.com/asura-images/chapters/c1/02.webp#tiles=2x1:1,0",
			),
			parser.getPages(ch1).map { it.url },
		)

		val ch300 = MangaChapter(300L, "Chapter 300", 300f, 0, "/comics/omniscient-readers-viewpoint-ab39de10/chapter/300", null, 0L, null, MangaParserSource.ASURASCANS)
		assertEquals(
			listOf(
				"https://cdn.asurascans.com/asura-images/chapters/orv-300/01.webp",
				"https://cdn.asurascans.com/asura-images/chapters/orv-300/02.webp",
			),
			parser.getPages(ch300).map { it.url },
		)

		val missing = MangaChapter(312L, "Chapter 312", 312f, 0, "/comics/sample-work-ab39de10/chapter/312", null, 0L, null, MangaParserSource.ASURASCANS)
		val error = runCatching { parser.getPages(missing) }.exceptionOrNull()
		assertTrue(error is NotFoundException)
	}

	@Test
	fun interceptMigratesLegacyDomainPreservesCdnSetsHeadersAndDescramblesTiles() = runTest {
		val seenRequests = ArrayList<Request>()
		val drawnOps = ArrayList<Pair<Rect, Rect>>()

		val legacyConfig = object : MangaSourceConfig {
			@Suppress("UNCHECKED_CAST")
			override fun <T> get(key: ConfigKey<T>): T {
				if (key is ConfigKey.Domain) return "asuracomic.net" as T
				return key.defaultValue
			}
		}

		val context = object : TestContext(
			config = legacyConfig,
			responder = { req ->
				seenRequests += req
				200 to "{}"
			},
		) {
			override fun redrawImageResponse(response: Response, redraw: (Bitmap) -> Bitmap): Response {
				val srcBitmap = RecordingBitmap(200, 100, drawnOps)
				val out = redraw(srcBitmap)
				assertEquals(200, out.width)
				assertEquals(100, out.height)
				return response
			}

			override fun createBitmap(width: Int, height: Int): Bitmap = RecordingBitmap(width, height, drawnOps)
		}

		val parser = AsuraScansParser(context).also { context.parserInterceptor = it }
		assertEquals("https://asurascans.com/", parser.getRequestHeaders()["Referer"])

		val htmlReq = Request.Builder()
			.url("https://asuracomic.net/browse")
			.header("Origin", "https://asuracomic.net")
			.build()
		context.httpClient.newCall(htmlReq).execute().close()

		val apiReq = Request.Builder()
			.url("https://api.asuracomic.net/api/series")
			.header("Origin", "https://asuracomic.net")
			.build()
		context.httpClient.newCall(apiReq).execute().close()

		val cdnTileReq = Request.Builder()
			.url("https://gg.asuracomic.net/storage/media/1/01.webp#tiles=2x2:3,2,1,0")
			.build()
		context.httpClient.newCall(cdnTileReq).execute().close()

		assertEquals("asurascans.com", seenRequests[0].url.host)
		assertNull(seenRequests[0].header("Origin"))
		assertEquals("https://asurascans.com/", seenRequests[0].header("Referer"))
		assertEquals("navigate", seenRequests[0].header("Sec-Fetch-Mode"))

		assertEquals("api.asurascans.com", seenRequests[1].url.host)
		assertNull(seenRequests[1].header("Origin"))
		assertEquals("cors", seenRequests[1].header("Sec-Fetch-Mode"))

		// gg.asuracomic.net CDN host must NEVER be rewritten
		assertEquals("gg.asuracomic.net", seenRequests[2].url.host)
		assertEquals(
			listOf(
				Rect(0, 0, 100, 50) to Rect(100, 50, 200, 100),
				Rect(100, 0, 200, 50) to Rect(0, 50, 100, 100),
				Rect(0, 50, 100, 100) to Rect(100, 0, 200, 50),
				Rect(100, 50, 200, 100) to Rect(0, 0, 100, 50),
			),
			drawnOps,
		)
	}

	private class RecordingBitmap(
		override val width: Int,
		override val height: Int,
		private val ops: MutableList<Pair<Rect, Rect>>,
	) : Bitmap {
		override fun drawBitmap(sourceBitmap: Bitmap, src: Rect, dst: Rect) {
			ops += src to dst
		}
	}

	private open class TestContext(
		private val config: MangaSourceConfig = SourceConfigMock(),
		private val responder: (req: Request) -> Pair<Int, String>,
	) : MangaLoaderContext() {
		var parserInterceptor: Interceptor? = null

		override val cookieJar: CookieJar = CookieJar.NO_COOKIES
		override val httpClient: OkHttpClient = OkHttpClient.Builder()
			.addInterceptor { chain ->
				val interceptor = parserInterceptor
				if (interceptor != null) {
					interceptor.intercept(chain)
				} else {
					chain.proceed(chain.request())
				}
			}
			.addInterceptor { chain ->
				val req = chain.request()
				val (code, body) = responder(req)
				val mediaType = if (body.trimStart().startsWith("{")) {
					"application/json; charset=utf-8".toMediaType()
				} else {
					"text/html; charset=utf-8".toMediaType()
				}
				Response.Builder()
					.request(req)
					.protocol(Protocol.HTTP_1_1)
					.code(code)
					.message(if (code == 200) "OK" else "Error")
					.body(body.toResponseBody(mediaType))
					.build()
			}
			.build()

		override fun getConfig(source: MangaSource): MangaSourceConfig = config
		override fun getDefaultUserAgent(): String = "TestAgent/1.0"
		@Deprecated("Test")
		override suspend fun evaluateJs(script: String): String? = null
		override suspend fun evaluateJs(baseUrl: String, script: String, timeout: Long): String? = null
		override fun redrawImageResponse(response: Response, redraw: (Bitmap) -> Bitmap): Response = response
		override fun createBitmap(width: Int, height: Int): Bitmap = throw UnsupportedOperationException()
	}
}
