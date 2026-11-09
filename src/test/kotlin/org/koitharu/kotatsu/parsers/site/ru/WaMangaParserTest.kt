package org.koitharu.kotatsu.parsers.site.ru

import kotlinx.coroutines.test.runTest
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.SourceConfigMock
import org.koitharu.kotatsu.parsers.bitmap.Bitmap
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.SortOrder

internal class WaMangaParserTest {

	@ParameterizedTest
	@CsvSource(
		"manga, https://wamanga.ru/manga/sample-title",
		"manhwa, https://wamanga.ru/manhwa/sample-title",
		"manhua, https://wamanga.ru/manhua/sample-title",
	)
	fun publicLinksFollowTheTitleType(type: String, expectedUrl: String) = runTest {
		val mangaJson = """
			{"id":"sample-id","slug":"sample-title","title":"Sample title","type":"$type"}
		""".trimIndent()
		val parser = WaMangaParser(TestContext { path ->
			when (path) {
				"/api/v1/manga" -> "[$mangaJson]"
				"/api/v1/manga/sample-id" -> mangaJson
				"/api/v1/manga/sample-id/chapters" -> "[]"
				else -> error("Unexpected request: $path")
			}
		})

		val manga = parser.getListPage(1, SortOrder.ALPHABETICAL, MangaListFilter.EMPTY).single()
		assertEquals(expectedUrl, manga.publicUrl)

		val cachedManga = manga.copy(publicUrl = "https://wamanga.ru/manga/old-slug")
		assertEquals(expectedUrl, parser.getDetails(cachedManga).publicUrl)
	}

	private class TestContext(
		private val responder: (path: String) -> String,
	) : MangaLoaderContext() {
		override val cookieJar: CookieJar = CookieJar.NO_COOKIES
		override val httpClient: OkHttpClient = OkHttpClient.Builder()
			.addInterceptor { chain ->
				val request = chain.request()
				Response.Builder()
					.request(request)
					.protocol(Protocol.HTTP_1_1)
					.code(200)
					.message("OK")
					.body(responder(request.url.encodedPath).toResponseBody("application/json".toMediaType()))
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
