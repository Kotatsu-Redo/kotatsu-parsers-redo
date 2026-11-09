package org.koitharu.kotatsu.parsers.site.vi

import kotlinx.coroutines.test.runTest
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.HttpStatusException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.SourceConfigMock
import org.koitharu.kotatsu.parsers.bitmap.Bitmap
import org.koitharu.kotatsu.parsers.exception.AuthRequiredException
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.webview.InterceptedRequest
import org.koitharu.kotatsu.parsers.webview.InterceptionConfig
import java.net.UnknownHostException

internal class GocTruyenSessionTest {

	@Test
	fun publicApiDoesNotRequireBrowserOrSendAuthorization() = runTest {
		val context = SessionContext { 200 }
		assertEquals(emptyList<Any>(), context.parser().getListPage(1, SortOrder.UPDATED, MangaListFilter()))
		assertEquals(0, context.captures)
		assertNull(context.requests.single().header("Authorization"))
	}

	@Test
	fun unauthorizedApiRetriesWithUsersCapturedSession() = runTest {
		for (status in listOf(401, 403)) {
			val context = SessionContext { if (it.header("Authorization") == null) status else 200 }
			context.parser().getListPage(1, SortOrder.UPDATED, MangaListFilter())
			assertEquals(1, context.captures)
			assertEquals(2, context.requests.size)
			assertNull(context.requests.first().header("Authorization"))
			assertEquals("Bearer test-session", context.requests.last().header("Authorization"))
		}
	}

	@Test
	fun missingSessionFailsWithoutRetryingWithSharedCredentials() {
		val context = SessionContext { 401 }.apply { captured = emptyList() }
		assertThrows(AuthRequiredException::class.java) {
			runTest { context.parser().getListPage(1, SortOrder.UPDATED, MangaListFilter()) }
		}
		assertEquals(1, context.requests.size)
	}

	@Test
	fun unavailableBrowserReportsAuthorizationRequired() {
		val context = SessionContext { 401 }.apply { browserAvailable = false }
		assertThrows(AuthRequiredException::class.java) {
			runTest { context.parser().getListPage(1, SortOrder.UPDATED, MangaListFilter()) }
		}
	}

	@Test
	fun rejectedSessionDoesNotLoop() {
		val context = SessionContext { 403 }
		assertThrows(AuthRequiredException::class.java) {
			runTest { context.parser().getListPage(1, SortOrder.UPDATED, MangaListFilter()) }
		}
		assertEquals(2, context.requests.size)
		assertEquals(1, context.captures)
	}

	@Test
	fun serverErrorsDoNotTriggerSessionCapture() {
		val context = SessionContext { 500 }
		assertThrows(HttpStatusException::class.java) {
			runTest { context.parser().getListPage(1, SortOrder.UPDATED, MangaListFilter()) }
		}
		assertEquals(0, context.captures)
	}

	@Test
	fun sessionIsNotCachedAcrossCalls() {
		val context = SessionContext { if (it.header("Authorization") == null) 401 else 200 }
		val parser = context.parser()
		runTest { parser.getListPage(1, SortOrder.UPDATED, MangaListFilter()) }
		context.captured = emptyList()
		assertThrows(AuthRequiredException::class.java) {
			runTest { parser.getListPage(2, SortOrder.UPDATED, MangaListFilter()) }
		}
		assertNull(context.requests.last().header("Authorization"))
		assertEquals(2, context.captures)
	}

	@Test
	fun http200ExpiredSessionRetriesWithCapturedAuthorization() = runTest {
		val context = SessionContext { 200 }.apply {
			responseBody = { request ->
				if (request.header("Authorization") == null) {
					"""{"status":false,"errorCode":400,"messages":["Phiên làm việc đã hết hạn, vui lòng tải lại."]}"""
				} else """{"result":{"data":[]}}"""
			}
		}
		context.parser().getListPage(1, SortOrder.UPDATED, MangaListFilter())
		assertEquals(2, context.requests.size)
		assertEquals(1, context.captures)
	}

	@Test
	fun chapterFallbackParsesFormattedComicObjectAndUsesCurrentEndpoint() = runTest {
		val context = SessionContext { 200 }.apply {
			responseBody = { request ->
				when {
					request.method == "GET" -> "<script>const comic = {\n id: `0001285725`,\n currentNumber: `24`\n};</script>"
					request.header("Authorization") == null ->
						"""{"status":false,"errorCode":400,"messages":["Phiên làm việc đã hết hạn, vui lòng tải lại."]}"""
					else -> """{"status":true,"result":{"state":true,"data":["/image/page.jpg"]}}"""
				}
			}
		}
		val chapter = MangaChapter(
			id = 1L, title = null, number = 24f, volume = 0,
			url = "/truyen/example/chuong-24", scanlator = null, uploadDate = 0L,
			branch = null, source = MangaParserSource.GOCTRUYENTRANHVUI,
		)
		val pages = context.parser().getPages(chapter)
		assertEquals("https://goctruyentranhvui41.com/image/page.jpg", pages.single().url)
		assertEquals("/api/chapter/loadAll", context.requests.last().url.encodedPath)
		assertEquals("POST", context.requests.last().method)
		val body = okio.Buffer().also { context.requests.last().body!!.writeTo(it) }.readUtf8()
		org.junit.jupiter.api.Assertions.assertTrue(body.contains("comicId=0001285725"))
		assertEquals(1, context.captures)
	}

	private class SessionContext(status: (Request) -> Int) : MangaLoaderContext() {
		val requests = mutableListOf<Request>()
		var captures = 0
		var browserAvailable = true
		var responseBody: (Request) -> String = { "{\"result\":{\"data\":[]}}" }
		var captured = listOf(
			InterceptedRequest(
				url = "https://goctruyentranhvui41.com/api/v2/search",
				method = "GET",
				headers = mapOf("Authorization" to "Bearer test-session"),
				timestamp = 0L,
			),
		)
		override val cookieJar = CookieJar.NO_COOKIES
		override val httpClient = OkHttpClient.Builder()
			.dns { throw UnknownHostException("Live network is disabled in session tests") }
			.addInterceptor { chain ->
			val request = chain.request()
			requests += request
			Response.Builder()
				.request(request)
				.protocol(Protocol.HTTP_1_1)
				.code(status(request))
				.message("test response")
				.body(responseBody(request).toResponseBody("application/json".toMediaType()))
				.build()
		}.build()

		fun parser() = GocTruyenTranhVui(this)

		override suspend fun interceptWebViewRequests(url: String, config: InterceptionConfig): List<InterceptedRequest> {
			captures++
			if (!browserAvailable) throw UnsupportedOperationException("No browser")
			return captured
		}

		override fun getConfig(source: MangaSource) = SourceConfigMock()
		override fun getDefaultUserAgent() = "test"
		@Deprecated("Provide a base url")
		override suspend fun evaluateJs(script: String): String? = error("Unused")
		override suspend fun evaluateJs(baseUrl: String, script: String, timeout: Long): String? = error("Unused")
		override fun redrawImageResponse(response: Response, redraw: (Bitmap) -> Bitmap): Response = error("Unused")
		override fun createBitmap(width: Int, height: Int): Bitmap = error("Unused")
	}
}
