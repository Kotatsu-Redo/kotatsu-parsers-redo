package org.koitharu.kotatsu.parsers.site.en

import kotlinx.coroutines.test.runTest
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.SourceConfigMock
import org.koitharu.kotatsu.parsers.bitmap.Bitmap
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource

internal class AtsuMoeTest {

	@Test
	fun liftsLimitWithForceNetworkAndSetsChapterReferer() = runTest {
		val context = MockContext { req ->
			when (req.url.encodedPath) {
				"/api/android/download" -> headResponse(req)
				"/api/read/chapter" -> jsonResponse(
					req,
					"""
					{
						"readChapter": {
							"pages": [
								{"image": "/static/pages/ch1/abc123.avif", "number": 0},
								{"image": "/static/pages/ch1/def456.avif", "number": 1}
							]
						}
					}
					""".trimIndent(),
				)
				else -> error("Unexpected path: ${req.url}")
			}
		}
		val parser = AtsuMoe(context)
		context.parser = parser

		val pages = parser.getPages(sampleChapter("manga1/ch1"))
		assertEquals(
			listOf(
				"https://cdn.atsu.moe/static/pages/ch1/abc123.avif",
				"https://cdn.atsu.moe/static/pages/ch1/def456.avif",
			),
			pages.map { it.url },
		)
		assertEquals(2, context.requests.size)
		val headReq = context.requests[0]
		assertEquals("HEAD", headReq.method)
		assertEquals("/api/android/download", headReq.url.encodedPath)
		assertTrue(headReq.cacheControl.noCache)

		val getReq = context.requests[1]
		assertEquals("GET", getReq.method)
		assertEquals("/api/read/chapter", getReq.url.encodedPath)
		assertTrue(getReq.cacheControl.noCache)
		assertEquals("https://atsu.moe/read/manga1/ch1", getReq.header("Referer"))
	}

	@Test
	fun retriesPromoImmediatelyWithoutDelayAndNeverThrows() = runTest {
		var chapterCalls = 0
		val context = MockContext { req ->
			when (req.url.encodedPath) {
				"/api/android/download" -> headResponse(req)
				"/api/read/chapter" -> {
					chapterCalls++
					if (chapterCalls == 1) {
						jsonResponse(
							req,
							"""{"readChapter":{"pages":[{"image":"/static/promo/app.webp","number":0}]}}""",
						)
					} else {
						jsonResponse(
							req,
							"""
							{
								"readChapter": {
									"pages": [
										{"image": "/static/pages/ch2/aaa.webp", "number": 0},
										{"image": "/static/pages/ch2/bbb.webp", "number": 1}
									]
								}
							}
							""".trimIndent(),
						)
					}
				}
				else -> error("Unexpected path: ${req.url}")
			}
		}
		val parser = AtsuMoe(context)
		context.parser = parser

		val pages = parser.getPages(sampleChapter("manga1/ch2"))
		assertEquals(2, chapterCalls)
		assertEquals(
			listOf(
				"https://cdn.atsu.moe/static/pages/ch2/aaa.webp",
				"https://cdn.atsu.moe/static/pages/ch2/bbb.webp",
			),
			pages.map { it.url },
		)
	}

	@Test
	fun persistentPromoDoesNotThrow() = runTest {
		val context = MockContext { req ->
			when (req.url.encodedPath) {
				"/api/android/download" -> headResponse(req)
				"/api/read/chapter" -> jsonResponse(
					req,
					"""{"readChapter":{"pages":[{"image":"/static/promo/app.webp","number":0}]}}""",
				)
				else -> error("Unexpected path: ${req.url}")
			}
		}
		val parser = AtsuMoe(context)
		context.parser = parser

		val pages = parser.getPages(sampleChapter("manga1/ch3"))
		assertEquals(listOf("https://cdn.atsu.moe/static/promo/app.webp"), pages.map { it.url })
	}

	@Test
	fun unshufflesNumericFilenamesWhenShuffled() = runTest {
		val context = MockContext { req ->
			when (req.url.encodedPath) {
				"/api/android/download" -> headResponse(req)
				"/api/read/chapter" -> jsonResponse(
					req,
					"""
					{
						"readChapter": {
							"pages": [
								{"image": "/static/pages/uoa_2K/2.webp", "number": 0},
								{"image": "/static/pages/uoa_2K/0.webp", "number": 1},
								{"image": "/static/pages/uoa_2K/3.webp", "number": 2},
								{"image": "/static/pages/uoa_2K/1.webp", "number": 3}
							]
						}
					}
					""".trimIndent(),
				)
				else -> error("Unexpected path: ${req.url}")
			}
		}
		val parser = AtsuMoe(context)
		context.parser = parser

		val pages = parser.getPages(sampleChapter("OaKBx/uoa_2K"))
		assertEquals(
			listOf(
				"https://cdn.atsu.moe/static/pages/uoa_2K/0.webp",
				"https://cdn.atsu.moe/static/pages/uoa_2K/1.webp",
				"https://cdn.atsu.moe/static/pages/uoa_2K/2.webp",
				"https://cdn.atsu.moe/static/pages/uoa_2K/3.webp",
			),
			pages.map { it.url },
		)
	}

	@Test
	fun consecutiveChaptersForceNetworkOnEveryLimitReset() = runTest {
		val context = MockContext { req ->
			when (req.url.encodedPath) {
				"/api/android/download" -> headResponse(req)
				"/api/read/chapter" -> jsonResponse(
					req,
					"""{"readChapter":{"pages":[{"image":"/static/pages/ch/aaa.webp","number":0}]}}""",
				)
				else -> error("Unexpected path: ${req.url}")
			}
		}
		val parser = AtsuMoe(context)
		context.parser = parser

		parser.getPages(sampleChapter("manga1/ch1"))
		parser.getPages(sampleChapter("manga1/ch2"))
		val headRequests = context.requests.filter { it.url.encodedPath == "/api/android/download" }
		assertEquals(2, headRequests.size)
		assertTrue(headRequests.all { it.cacheControl.noCache })
	}

	private fun sampleChapter(url: String) = MangaChapter(
		id = 1L,
		title = "Chapter 1",
		number = 1f,
		volume = 0,
		url = url,
		scanlator = null,
		uploadDate = 0L,
		branch = null,
		source = MangaParserSource.ATSUMOE,
	)

	private class MockContext(
		private val handler: (Request) -> Response,
	) : MangaLoaderContext() {
		override val parsers: org.koitharu.kotatsu.parsers.ParsersBundle
			get() = org.koitharu.kotatsu.parsers.core.ParsersBundleImpl

		lateinit var parser: AtsuMoe
		val requests = ArrayList<Request>()

		override val httpClient: OkHttpClient = OkHttpClient.Builder()
			.addInterceptor { chain -> parser.intercept(chain) }
			.addInterceptor { chain ->
				val req = chain.request()
				requests += req
				handler(req)
			}
			.build()

		override val cookieJar: CookieJar = CookieJar.NO_COOKIES

		@Deprecated("Provide a base url")
		override suspend fun evaluateJs(script: String): String? = throw UnsupportedOperationException()

		override suspend fun evaluateJs(baseUrl: String, script: String, timeout: Long): String? =
			throw UnsupportedOperationException()

		override fun getDefaultUserAgent(): String = "TestAgent/1.0"

		override fun getConfig(source: MangaSource) = SourceConfigMock()

		override fun createBitmap(width: Int, height: Int): Bitmap = throw UnsupportedOperationException()

		override fun redrawImageResponse(response: Response, redraw: (Bitmap) -> Bitmap): Response = response
	}

	private companion object {
		private fun headResponse(request: Request): Response = Response.Builder()
			.request(request)
			.protocol(Protocol.HTTP_1_1)
			.code(200)
			.message("OK")
			.header("Cache-Control", "public, max-age=60")
			.body("".toResponseBody(null))
			.build()

		private fun jsonResponse(request: Request, body: String): Response = Response.Builder()
			.request(request)
			.protocol(Protocol.HTTP_1_1)
			.code(200)
			.message("OK")
			.body(body.toResponseBody("application/json; charset=utf-8".toMediaType()))
			.build()
	}
}
