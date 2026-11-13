package org.koitharu.kotatsu.parsers.site.id

import kotlinx.coroutines.test.runTest
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.ParsersBundle
import org.koitharu.kotatsu.parsers.SourceConfigMock
import org.koitharu.kotatsu.parsers.bitmap.Bitmap
import org.koitharu.kotatsu.parsers.core.ParsersBundleImpl
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.MangaState

internal class VoratoonTest {

	// 1024 characters or more, with letters of several bytes, line breaks, and a line that looks like a row
	private val longSynopsis = buildString {
		append("Kisah seorang kaisar — « iblis » yang terlahir kembali.\n")
		append("9:{\"series\":[],\"chapters\":[]}\n")
		while (length < 1100) append("Ia berjalan jauh, sangat jauh… ")
	}

	private val seriesData = """{"series":[{"id":7,"data":{"title":"$$100 Hero","nativeTitle":null,"slug":"hero",""" +
		""""coverImage":"https://cvr.example/cover.webp","synopsis":"$2","author":" Someone ","rating":9,""" +
		""""status":"ongoing","genres":[{"id":19,"data":{"name":"Action"}}]}}],"chapters":[""" +
		"""{"id":56,"createdAt":"2026-08-17T10:00:00.000+00:00","data":{"index":2,"title":"The Return"}},""" +
		"""{"id":55,"createdAt":"2026-08-16T17:21:23.416+00:00","data":{"index":1.5,"title":null}}]}"""

	private val seriesPage = page(
		"1:I[57121,[],\"LoadingBoundaryProvider\"]\n" +
			"2:T${longSynopsis.toByteArray().size.toString(16)},$longSynopsis" +
			"3:[\"$\",\"\$L1\",null,$seriesData]\n",
	)

	private val chapterPage = page(
		"""4:{"series":[],"chapter":{"id":55,"data":{"title":null,"images":["https://cdn.example/1.jpg",""" +
			""""https://cdn.example/2.jpg"]}},"chapters":[]}""" + "\n",
	)

	private val notFoundPage = page("5:[\"$\",\"main\",null,{\"children\":\"Tidak ditemukan\"}]\n")

	@Test
	fun readsTheDataOfAPageAroundALongText() = runTest {
		val parser = Voratoon(TestContext { if (it.encodedPath == "/series/hero") seriesPage else notFoundPage })
		val manga = parser.getDetails(newManga("/series/hero"))
		assertEquals("$100 Hero", manga.title)
		assertEquals(longSynopsis, manga.description)
		assertEquals(MangaState.ONGOING, manga.state)
		assertEquals(setOf("Someone"), manga.authors)
		assertEquals(listOf("19" to "Action"), manga.tags.map { it.key to it.title })
		assertEquals(0.9f, manga.rating)
		val chapters = requireNotNull(manga.chapters)
		assertEquals(listOf("Chapter 1.5", "Chapter 2: The Return"), chapters.map { it.title })
		assertEquals(listOf("/series/hero/chapter/1.5", "/series/hero/chapter/2"), chapters.map { it.url })
	}

	@Test
	fun readsAChapterSavedByItsId() = runTest {
		val seen = ArrayList<String>()
		val parser = Voratoon(
			TestContext { url ->
				seen += url.encodedPath
				when (url.encodedPath) {
					"/series/hero" -> seriesPage
					"/series/hero/chapter/1.5" -> chapterPage
					else -> notFoundPage
				}
			},
		)
		val chapter = MangaChapter(
			id = 1L,
			title = null,
			number = 0f,
			volume = 0,
			url = "/series/hero/chapters/55",
			scanlator = null,
			uploadDate = 0L,
			branch = null,
			source = MangaParserSource.VORATOON,
		)
		val pages = parser.getPages(chapter)
		assertEquals(listOf("https://cdn.example/1.jpg", "https://cdn.example/2.jpg"), pages.map { it.url })
		assertEquals(listOf("/series/hero/chapter/55", "/series/hero", "/series/hero/chapter/1.5"), seen)
	}

	private fun newManga(url: String) = Manga(
		id = 1L,
		title = "",
		altTitles = emptySet(),
		url = url,
		publicUrl = url,
		rating = 0f,
		contentRating = null,
		coverUrl = null,
		tags = emptySet(),
		state = null,
		authors = emptySet(),
		source = MangaParserSource.VORATOON,
	)

	// The payload reaches a page cut in pieces, each handed over by a script of its own
	private fun page(payload: String): String {
		val half = payload.length / 2
		val scripts = listOf(payload.substring(0, half), payload.substring(half)).joinToString("") {
			"<script>self.__next_f.push([1,${JSONObject.quote(it)}])</script>"
		}
		return "<!DOCTYPE html><html><head><script>(self.__next_f=self.__next_f||[]).push([0])</script></head>" +
			"<body><main>…</main>$scripts</body></html>"
	}

	private class TestContext(
		private val responder: (url: HttpUrl) -> String,
	) : MangaLoaderContext() {

		override val parsers: ParsersBundle
			get() = ParsersBundleImpl

		override val cookieJar: CookieJar = CookieJar.NO_COOKIES

		override val httpClient: OkHttpClient = OkHttpClient.Builder()
			.addInterceptor { chain ->
				val request = chain.request()
				Response.Builder()
					.request(request)
					.protocol(Protocol.HTTP_1_1)
					.code(200)
					.message("OK")
					.body(responder(request.url).toResponseBody("text/html; charset=utf-8".toMediaType()))
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
