package org.koitharu.kotatsu.parsers.site.madara.id

import kotlinx.coroutines.test.runTest
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.SourceConfigMock
import org.koitharu.kotatsu.parsers.bitmap.Bitmap
import org.koitharu.kotatsu.parsers.core.ParsersBundleImpl
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable

internal class MgkomikTest {

	@Test
	fun `given cloudflare challenge in response body - when loading list - then rendered WebView DOM is used`() = runTest {
		val context = TestContext(
			httpBody = CHALLENGE_HTML,
			jsResult = JSONObject.quote(LISTING_HTML),
		)

		val list = Mgkomik(context).getListPage(0, SortOrder.UPDATED, MangaListFilter())

		assertEquals(1, list.size)
		assertEquals("Sample Manga", list.single().title)
	}

	@Test
	fun `given HTTP 403 - when loading list - then WebView fallback is used`() = runTest {
		val context = TestContext(
			httpBody = CHALLENGE_HTML,
			httpCode = 403,
			jsResult = JSONObject.quote(LISTING_HTML),
		)

		val list = Mgkomik(context).getListPage(0, SortOrder.UPDATED, MangaListFilter())

		assertEquals("Sample Manga", list.single().title)
	}

	@Test
	fun `given no browser support - when challenge cannot be solved - then clear ParseException is thrown`() = runTest {
		val context = TestContext(httpBody = CHALLENGE_HTML, httpCode = 403)
		val parser = Mgkomik(context)

		val error = runCatchingCancellable {
			parser.getListPage(0, SortOrder.UPDATED, MangaListFilter())
		}.exceptionOrNull()

		require(error is ParseException)
		assertTrue(error.message.orEmpty().contains("Cloudflare verification"))
	}

	@Test
	fun `given JSON escaped WebView output - when decoding - then all escape sequences survive`() = runTest {
		val parser = Mgkomik(TestContext("<html/>"))
		val original = "line\\nnext \"quoted\"\ttab \\ backslash A / slash"

		assertEquals(original, parser.decodeWebViewString(JSONObject.quote(original)))
	}

	@Test
	fun `given challenge markers - when detecting - then only challenge pages match`() = runTest {
		val parser = Mgkomik(TestContext("<html/>"))

		assertTrue(parser.isCloudflareChallengePage(CHALLENGE_HTML))
		assertTrue(
			parser.isCloudflareChallengePage(
				"<html><head><title>Attention Required! | Cloudflare</title></head></html>",
			),
		)
		assertFalse(parser.isCloudflareChallengePage(LISTING_HTML))
		assertFalse(parser.isCloudflareChallengePage(""))
	}

	private class TestContext(
		private val httpBody: String,
		private val httpCode: Int = 200,
		private val jsResult: String? = null,
	) : MangaLoaderContext() {

		override val parsers = ParsersBundleImpl
		override val cookieJar: CookieJar = CookieJar.NO_COOKIES
		override val httpClient: OkHttpClient = OkHttpClient.Builder()
			.addInterceptor { chain ->
				Response.Builder()
					.request(chain.request())
					.protocol(Protocol.HTTP_1_1)
					.code(httpCode)
					.message(if (httpCode == 403) "Forbidden" else "OK")
					.body(httpBody.toResponseBody("text/html; charset=utf-8".toMediaType()))
					.build()
			}
			.build()

		override fun getConfig(source: MangaSource) = SourceConfigMock()
		override fun getDefaultUserAgent(): String = "TestAgent/1.0"

		@Deprecated("Provide a base url")
		override suspend fun evaluateJs(script: String): String? = jsResult
		override suspend fun evaluateJs(baseUrl: String, script: String, timeout: Long): String? = jsResult

		override fun redrawImageResponse(response: Response, redraw: (Bitmap) -> Bitmap): Response = response
		override fun createBitmap(width: Int, height: Int): Bitmap = throw UnsupportedOperationException()
	}

	private companion object {

		const val CHALLENGE_HTML = """<html><head><title>Just a moment...</title></head><body>
			<div id="challenge-error-text">Enable JavaScript and cookies to continue</div>
			<script>window._cf_chl_opt = {cType: 'interactive'};</script>
			<script src="/cdn-cgi/challenge-platform/h/b/orchestrate/chl_page/v1"></script>
			</body></html>"""

		const val LISTING_HTML = """<html><body>
			<div class="row c-tabs-item__content">
				<div class="tab-summary">
					<a href="/komik/sample-manga/"><h3>Sample Manga</h3></a>
				</div>
			</div>
			</body></html>"""
	}
}
