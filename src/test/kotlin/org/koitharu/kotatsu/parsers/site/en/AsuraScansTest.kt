package org.koitharu.kotatsu.parsers.site.en

import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.koitharu.kotatsu.parsers.InMemoryCookieJar
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.ParsersBundle
import org.koitharu.kotatsu.parsers.bitmap.Bitmap
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.config.MangaSourceConfig
import org.koitharu.kotatsu.parsers.core.ParsersBundleImpl
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.await
import org.koitharu.kotatsu.parsers.util.newLinkResolver
import org.koitharu.kotatsu.test_util.BitmapTestImpl
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

internal class AsuraScansTest {

	@Test
	fun savedLegacyDomainAuthorsSortAndHeaderScoping() = runTest {
		val seen = ArrayList<Request>()
		val context = TestContext(savedDomain = "asuracomic.net") { request ->
			seen += request
			when (request.url.encodedPath) {
				"/api/series" -> jsonResponse(
					request,
					"""
					{"data":[
						{
							"slug":"example-series-00dcbf97",
							"public_url":"/comics/example-series-f212c582",
							"title":"Example Series",
							"cover":"/asura-images/covers/example.webp",
							"rating":9.5,
							"author":"Author One / Author Two, N/A",
							"artist":"Updating",
							"status":"Season End"
						},
						{
							"slug":"second-series",
							"title":"Second Series",
							"author":"N/A",
							"artist":"-"
						}
					]}
					""".trimIndent(),
				)
				else -> binaryResponse(request, ByteArray(0), "image/webp")
			}
		}
		val list = context.parser.getListPage(1, SortOrder.NEWEST, MangaListFilter.EMPTY)
		assertEquals(2, list.size)
		val manga = list[0]
		assertEquals("/comics/example-series", manga.url)
		assertEquals("https://asurascans.com/comics/example-series-f212c582", manga.publicUrl)
		assertEquals("https://asurascans.com/asura-images/covers/example.webp", manga.coverUrl)
		assertEquals(0.95f, manga.rating, 1e-4f)
		assertEquals(setOf("Author One", "Author Two"), manga.authors)
		assertEquals(MangaState.PAUSED, manga.state)
		assertNull(manga.description)
		assertEquals(emptySet<String>(), list[1].authors)

		val apiReq = seen.single()
		assertEquals("api.asurascans.com", apiReq.url.host)
		assertEquals("newest", apiReq.url.queryParameter("sort"))
		assertEquals("desc", apiReq.url.queryParameter("order"))
		assertEquals("https://asurascans.com", apiReq.header("Origin"))
		assertEquals("https://asurascans.com/", apiReq.header("Referer"))
		assertNull(apiReq.header("Cache-Control"))

		seen.clear()
		context.httpClient.newCall(
			Request.Builder()
				.url("http://asuracomic.net/comics/example-series")
				.header("Origin", "https://asuracomic.net")
				.build(),
		).await().close()
		val migratedReq = seen.single()
		assertEquals("https", migratedReq.url.scheme)
		assertEquals("asurascans.com", migratedReq.url.host)
		assertEquals("https://asurascans.com/", migratedReq.header("Referer"))
		assertNull(migratedReq.header("Origin"))

		seen.clear()
		context.httpClient.newCall(
			Request.Builder()
				.url("https://gg.asuracomic.net/storage/media/1/page.webp")
				.header("Origin", "https://asuracomic.net")
				.header("Cache-Control", "no-cache")
				.build(),
		).await().close()
		val cdnReq = seen.single()
		assertEquals("gg.asuracomic.net", cdnReq.url.host)
		assertEquals("https://asurascans.com/", cdnReq.header("Referer"))
		assertNull(cdnReq.header("Origin"))
		assertNull(cdnReq.header("Cache-Control"))

		seen.clear()
		context.httpClient.newCall(
			Request.Builder()
				.url("https://cdn.example.org/external-cover.webp")
				.header("Referer", "https://asurascans.com/")
				.header("Origin", "https://asurascans.com")
				.build(),
		).await().close()
		val externalReq = seen.single()
		assertEquals("cdn.example.org", externalReq.url.host)
		assertNull(externalReq.header("Referer"))
		assertNull(externalReq.header("Origin"))
	}

	@Test
	fun legacyUrlsDetailFallbacksEarlyAccessAndZeroTimestamps() = runTest {
		val seenPaths = ArrayList<String>()
		val context = TestContext { request ->
			seenPaths += request.url.encodedPath
			when (request.url.encodedPath) {
				"/api/series/example-series" -> jsonResponse(
					request,
					"""
					{"series":{
						"slug":"example-series-00dcbf97",
						"public_url":"/comics/example-series-f212c582",
						"title":"Example Series",
						"description":"   "
					}}
					""".trimIndent(),
				)
				"/api/series/30-years-since-the-prologue" -> jsonResponse(
					request,
					"""
					{"series":{
						"slug":"30-years-since-the-prologue",
						"public_url":"/comics/30-years-since-the-prologue-f212c582",
						"title":"30 Years Since the Prologue"
					}}
					""".trimIndent(),
				)
				"/api/series/example-series/chapters" -> jsonResponse(
					request,
					"""
					{"data":[
						{"number":5,"is_premium":true,"page_count":24,"early_access_until":"2099-01-01T00:00:00Z"},
						{"number":4,"is_premium":true,"page_count":24},
						{
							"number":3,"is_premium":true,"page_count":0,
							"early_access_until":"2020-01-01T00:00:00Z","published_at":"2025-05-02T12:00:00Z"
						},
						{
							"number":2,"is_premium":false,"page_count":0,
							"published_at":"0001-01-01T00:00:00Z","created_at":"0001-01-01T00:00:00Z"
						},
						{"number":2,"is_premium":false,"page_count":10,"published_at":"0001-01-01T00:00:00Z"},
						{
							"number":1,"title":"Prologue","is_premium":false,
							"page_count":15,"published_at":"2025-05-01T00:00:00Z"
						}
					]}
					""".trimIndent(),
				)
				"/api/series/30-years-since-the-prologue/chapters" -> jsonResponse(request, """{"data":[]}""")
				else -> error("Unexpected path: ${request.url}")
			}
		}

		val savedTag = MangaTag(title = "Action", key = "action", source = MangaParserSource.ASURASCANS)
		val legacyBookmark = Manga(
			id = 99L,
			title = "Example Series",
			altTitles = setOf("Alt Title"),
			url = "/manga/1908287720-example-series-00dcbf97/",
			publicUrl = "https://asuracomic.net/manga/1908287720-example-series-00dcbf97/",
			rating = 0.8f,
			contentRating = null,
			coverUrl = "https://cdn.asurascans.com/saved-cover.webp",
			tags = setOf(savedTag),
			state = MangaState.ONGOING,
			authors = setOf("Saved Author"),
			description = "Saved summary",
			source = MangaParserSource.ASURASCANS,
		)
		val details = context.parser.getDetails(legacyBookmark)
		assertEquals(99L, details.id)
		assertEquals("/manga/1908287720-example-series-00dcbf97/", details.url)
		assertEquals("https://asurascans.com/comics/example-series-f212c582", details.publicUrl)
		assertEquals(setOf("Alt Title"), details.altTitles)
		assertEquals("https://cdn.asurascans.com/saved-cover.webp", details.coverUrl)
		assertEquals(0.8f, details.rating, 1e-4f)
		assertEquals(setOf(savedTag), details.tags)
		assertEquals(MangaState.ONGOING, details.state)
		assertEquals(setOf("Saved Author"), details.authors)
		assertEquals("Saved summary", details.description)
		assertEquals(listOf(1f, 2f, 3f), details.chapters?.map { it.number })
		assertEquals(0L, details.chapters?.get(1)?.uploadDate)
		assertEquals(
			listOf("/api/series/example-series", "/api/series/example-series/chapters"),
			seenPaths,
		)

		for (link in listOf(
			"https://asurascans.com/comics/example-series-f212c582/chapter/3",
			"https://asurascans.com/series/example-series-f212c582",
			"https://asurascans.com/manga/1908287720-example-series",
		)) {
			val resolved = context.parser.resolveLink(context.newLinkResolver(link), link.toHttpUrl())
			assertEquals("/comics/example-series", resolved?.url, link)
			assertEquals("", resolved?.description, link)
		}

		val numericLink = "https://asurascans.com/manga/30-years-since-the-prologue"
		val numericResolved = context.parser.resolveLink(context.newLinkResolver(numericLink), numericLink.toHttpUrl())
		assertEquals("/comics/30-years-since-the-prologue", numericResolved?.url)
	}

	@Test
	fun pagesLockStatesEmptyUnlockedAndTileUnscrambling() = runTest {
		val scrambled = BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB).apply {
			fillTile(0, 0, 2, 2, 0x0000FF) // tile 0 holds destination 2 (bottom-left)
			fillTile(2, 0, 2, 2, 0xFF0000) // tile 1 holds destination 0 (top-left)
			fillTile(0, 2, 2, 2, 0xFFFFFF) // tile 2 holds destination 3 (bottom-right)
			fillTile(2, 2, 2, 2, 0x00FF00) // tile 3 holds destination 1 (top-right)
		}
		val pngBytes = BitmapTestImpl(scrambled).compress("png")

		val context = TestContext { request ->
			when (request.url.encodedPath) {
				"/api/series/example-series/chapters/10.5" -> jsonResponse(
					request,
					"""
					{"data":{"chapter":{"pages":[
						{"url":"https://cdn.asurascans.com/ch/01.png","tile_cols":2,"tile_rows":2,"tiles":[2,0,3,1]}
					]}}}
					""".trimIndent(),
				)
				"/api/series/30-years-since-the-prologue/chapters/2" -> jsonResponse(
					request,
					"""
					{"data":{"chapter":{
						"number":2,"is_premium":true,"early_access_until":"2020-01-01T00:00:00Z","pages":[]
					}}}
					""".trimIndent(),
				)
				"/api/series/example-series/chapters/98" -> jsonResponse(
					request,
					"""{"data":{"is_locked":true,"chapter":{"number":98}}}""",
				)
				"/api/series/example-series/chapters/99" -> jsonResponse(
					request,
					"""
					{"data":{"chapter":{
						"number":99,"is_premium":true,"page_count":0,
						"early_access_until":"2099-01-01T00:00:00Z"
					}}}
					""".trimIndent(),
				)
				"/ch/01.png" -> binaryResponse(request, pngBytes, "image/png")
				else -> error("Unexpected path: ${request.url}")
			}
		}

		val pages = context.parser.getPages(newChapter("/manga/1908287720-example-series-00dcbf97/chapter-10-5/"))
		assertEquals(listOf("https://cdn.asurascans.com/ch/01.png#tiles=2x2:2,0,3,1"), pages.map { it.url })
		val bareLegacyPages = context.parser.getPages(newChapter("/manga/1908287720-example-series/chapter-10-5"))
		assertEquals(pages.map { it.url }, bareLegacyPages.map { it.url })
		val seriesPages = context.parser.getPages(newChapter("/series/example-series-00dcbf97/chapter/10.5"))
		assertEquals(pages.map { it.url }, seriesPages.map { it.url })

		val unscrambled = context.httpClient.newCall(Request.Builder().url(pages.single().url).build())
			.await()
			.use { ImageIO.read(it.body.byteStream()) }
		assertEquals(0xFF0000, unscrambled.getRGB(0, 0) and 0xFFFFFF)
		assertEquals(0x00FF00, unscrambled.getRGB(2, 0) and 0xFFFFFF)
		assertEquals(0x0000FF, unscrambled.getRGB(0, 2) and 0xFFFFFF)
		assertEquals(0xFFFFFF, unscrambled.getRGB(2, 2) and 0xFFFFFF)

		val unlockedEmpty = context.parser.getPages(newChapter("/manga/30-years-since-the-prologue/chapter-2"))
		assertTrue(unlockedEmpty.isEmpty())

		try {
			context.parser.getPages(newChapter("/comics/example-series/chapter/98"))
			fail("Expected ParseException for paywalled chapter")
		} catch (e: ParseException) {
			assertTrue(e.message.orEmpty().contains("paid account"))
		}

		try {
			context.parser.getPages(newChapter("/comics/example-series/chapter/99"))
			fail("Expected ParseException for locked early-access chapter")
		} catch (e: ParseException) {
			assertTrue(e.message.orEmpty().contains("early access"))
		}
	}

	private fun newChapter(url: String) = MangaChapter(
		id = 1L,
		title = null,
		number = 1f,
		volume = 0,
		url = url,
		scanlator = null,
		uploadDate = 0L,
		branch = null,
		source = MangaParserSource.ASURASCANS,
	)

	private fun BufferedImage.fillTile(x0: Int, y0: Int, w: Int, h: Int, rgb: Int) {
		for (y in y0 until y0 + h) {
			for (x in x0 until x0 + w) {
				setRGB(x, y, rgb)
			}
		}
	}

	private class TestContext(
		private val savedDomain: String? = null,
		private val responder: (Request) -> Response,
	) : MangaLoaderContext() {

		override val parsers: ParsersBundle
			get() = ParsersBundleImpl

		override val cookieJar = InMemoryCookieJar()

		lateinit var parser: AsuraScansParser
			private set

		override val httpClient: OkHttpClient = OkHttpClient.Builder()
			.cookieJar(cookieJar)
			.addInterceptor { chain -> parser.intercept(chain) }
			.addInterceptor { chain -> responder(chain.request()) }
			.build()

		init {
			parser = AsuraScansParser(this)
		}

		override fun getConfig(source: MangaSource): MangaSourceConfig = object : MangaSourceConfig {
			@Suppress("UNCHECKED_CAST")
			override fun <T> get(key: ConfigKey<T>): T {
				if (key is ConfigKey.Domain && savedDomain != null) {
					return savedDomain as T
				}
				return key.defaultValue
			}
		}

		override fun getDefaultUserAgent(): String = "TestAgent/1.0"

		@Deprecated("Test")
		override suspend fun evaluateJs(script: String): String? = null

		override suspend fun evaluateJs(baseUrl: String, script: String, timeout: Long): String? = null

		override fun redrawImageResponse(response: Response, redraw: (Bitmap) -> Bitmap): Response {
			val srcImage = response.body.byteStream().use(ImageIO::read)
			val resImage = redraw(BitmapTestImpl(srcImage)) as BitmapTestImpl
			return response.newBuilder()
				.body(resImage.compress("png").toResponseBody("image/png".toMediaType()))
				.build()
		}

		override fun createBitmap(width: Int, height: Int): Bitmap {
			return BitmapTestImpl(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB))
		}
	}

	private companion object {
		private fun jsonResponse(request: Request, body: String): Response = Response.Builder()
			.request(request)
			.protocol(Protocol.HTTP_1_1)
			.code(200)
			.message("OK")
			.body(body.toResponseBody("application/json; charset=utf-8".toMediaType()))
			.build()

		private fun binaryResponse(
			request: Request,
			bytes: ByteArray,
			contentType: String,
		): Response = Response.Builder()
			.request(request)
			.protocol(Protocol.HTTP_1_1)
			.code(200)
			.message("OK")
			.body(bytes.toResponseBody(contentType.toMediaType()))
			.build()
	}
}
