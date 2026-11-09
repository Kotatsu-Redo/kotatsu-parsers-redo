package org.koitharu.kotatsu.parsers.site.madara.en

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
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder

internal class CoComicTest {

	@Test
	fun withoutAjaxIsActiveDuringSuperInitAndExcludesPremiumAndCorruptTags() = runTest {
		val searchHtml = """
			<html><body>
			<input id="action" value="action" name="genre[]" type="checkbox" /><label for="action"> Action </label>
			<input id="action-dup" value="action-2" name="genre[]" type="checkbox" /><label for="action-dup"> action </label>
			<input id="romance" value="romance" name="genre[]" type="checkbox" /><label for="romance"> Romance </label>
			<input id="junk-num" value="18" name="genre[]" type="checkbox" /><label for="junk-num"> 18 </label>
			<input id="junk-site" value="cocomic" name="genre[]" type="checkbox" /><label for="junk-site"> Cocomic </label>
			<input id="junk-typo" value="offical" name="genre[]" type="checkbox" /><label for="junk-typo"> Offical </label>
			<input id="corrupt" value="zombiesenglishtr-fromkoreanpublicationoriginal-publicationongoing2025" name="genre[]" type="checkbox" />
			<label for="corrupt"> ZombiesPublication:Original Publication:Ongoing/2025 </label>
			</body></html>
		""".trimIndent()

		val detailsHtml = """
			<html><body>
			<div class="post-title"><h1>Sample Work</h1></div>
			<div class="genres-content">
				<a href="/manga-genre/ch-53/">Ch.53</a>
				<a href="/manga-genre/extra-1/">extra.1</a>
				<a href="/manga-genre/drama/">Drama</a>
				<a href="/manga-genre/uncategorized/">Uncategorized</a>
			</div>
			<div class="description-summary"><div class="summary__content"><p>Plot summary</p></div></div>
			<div class="listing-chapters_wrap">
				<ul class="main version-chap">
					<li class="wp-manga-chapter premium-block"><a href="/manga/sample/chapter-3/">Chapter 3</a></li>
					<li class="wp-manga-chapter premium"><a href="/manga/sample/chapter-2/">Chapter 2</a></li>
					<li class="wp-manga-chapter"><a href="/manga/sample/chapter-1/">Chapter 1</a><span class="chapter-release-date"><i>January 2, 2026</i></span></li>
				</ul>
			</div>
			</body></html>
		""".trimIndent()

		val context = TestContext { path, query ->
			when {
				query?.contains("post_type=wp-manga") == true -> searchHtml
				path == "/manga/sample/" -> detailsHtml
				else -> "<html/>"
			}
		}

		val parser = CoComic(context)
		assertTrue(SortOrder.UPDATED in parser.availableSortOrders)
		assertTrue(SortOrder.POPULARITY in parser.availableSortOrders)

		val tags = parser.getFilterOptions().availableTags
		assertEquals(listOf("action", "romance"), tags.map { it.key })
		assertEquals(listOf("Action", "Romance"), tags.map { it.title })

		val seed = Manga(
			id = 1L,
			title = "Sample Work",
			altTitles = emptySet(),
			url = "/manga/sample/",
			publicUrl = "https://cocomic.co/manga/sample/",
			rating = RATING_UNKNOWN,
			contentRating = null,
			coverUrl = null,
			tags = emptySet(),
			state = null,
			authors = emptySet(),
			source = MangaParserSource.COCOMIC,
		)
		val details = parser.getDetails(seed)
		assertEquals(listOf("Drama"), details.tags.map { it.title })
		val chapters = requireNotNull(details.chapters)
		assertEquals(1, chapters.size)
		assertEquals("/manga/sample/chapter-1/?style=list", chapters.single().url)
	}

	private class TestContext(
		private val responder: (path: String, query: String?) -> String,
	) : MangaLoaderContext() {
		override val cookieJar: CookieJar = CookieJar.NO_COOKIES
		override val httpClient: OkHttpClient = OkHttpClient.Builder()
			.addInterceptor { chain ->
				val req = chain.request()
				val body = responder(req.url.encodedPath, req.url.encodedQuery)
				Response.Builder()
					.request(req)
					.protocol(Protocol.HTTP_1_1)
					.code(200)
					.message("OK")
					.body(body.toResponseBody("text/html; charset=utf-8".toMediaType()))
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
