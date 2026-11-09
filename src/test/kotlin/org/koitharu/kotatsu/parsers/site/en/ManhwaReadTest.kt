package org.koitharu.kotatsu.parsers.site.en

import kotlinx.coroutines.test.runTest
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.SourceConfigMock
import org.koitharu.kotatsu.parsers.bitmap.Bitmap
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.Demographic
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.LinkResolver
import java.util.Base64
import java.util.EnumSet

internal class ManhwaReadTest {

	@Test
	fun configHeadersAndFavicons() = runTest {
		val context = MockContext { _, _ -> htmlResponse("<html/>") }
		val parser = ManhwaRead(context)

		assertEquals("manhwaread.com", parser.domain)
		assertEquals(listOf("manhwaread.com", "manhwaread.org"), parser.configKeyDomain.presetValues.toList())

		val keys = ArrayList<ConfigKey<*>>()
		parser.onCreateConfig(keys)
		assertTrue(keys.any { it is ConfigKey.Domain })
		assertTrue(keys.any { it is ConfigKey.UserAgent })

		val headers = parser.getRequestHeaders()
		assertEquals("https://manhwaread.com/", headers["Referer"])
		assertEquals("TestAgent/1.0", headers["User-Agent"])

		val favicons = parser.getFavicons()
		assertEquals(2, favicons.size)
		assertTrue(favicons.all { it.url.startsWith("https://mancover.xyz/") })

		assertTrue(parser.filterCapabilities.isYearSupported)
		assertFalse(parser.filterCapabilities.isYearRangeSupported)
		assertTrue(parser.filterCapabilities.isAuthorSearchSupported)
		assertTrue(parser.filterCapabilities.isMultipleTagsSupported)
		assertTrue(parser.filterCapabilities.isTagsExclusionSupported)
	}

	@Test
	fun catalogueIgnoresSwiperCardsAndParsesLazyCoversRatingsAndStates() = runTest {
		val listHtml = """
			<html><body>
			<div class="swiper-wrapper">
				<div class="manga-item">
					<a class="manga-item__link" href="/manhwa/slider-only/">Slider Item</a>
				</div>
			</div>
			<div class="manga-grid">
				<div class="manga-item">
					<div class="manga-item__img">
						<img src="data:image/svg+xml;base64,PHN2Zy8+" data-lazy-src="https://mancover.xyz/cover/alpha.webp" alt="Alpha Title">
					</div>
					<div class="manga-status" data-status="ongoing">Ongoing</div>
					<div class="manga-item__rating"><span>4.2</span></div>
					<a class="manga-item__link" href="https://manhwaread.com/manhwa/alpha-work/">Alpha Title</a>
					<div class="alt-title">Alt One | Alt Two</div>
					<div class="author-artist"><span>Writer A</span><span>Artist B</span></div>
				</div>
				<div class="manga-item">
					<div class="manga-item__img">
						<img data-src="https://mancover.xyz/cover/beta.webp" alt="Beta Title">
					</div>
					<div class="manga-status" data-status="incomplete">Incomplete</div>
					<div class="manga-item__rating"><span>0.0</span></div>
					<a class="manga-item__link" href="/manhwa/beta-work/">Beta Title</a>
				</div>
			</div>
			</body></html>
		""".trimIndent()

		val context = MockContext { _, _ -> htmlResponse(listHtml) }
		val parser = ManhwaRead(context)

		val page1 = parser.getListPage(1, SortOrder.UPDATED, MangaListFilter.EMPTY)
		assertEquals("https://manhwaread.com/?s=&sortby=release", context.requests.last().url.toString())
		assertEquals(MangaParserSource.MANHWAREAD, context.requests.last().tag(MangaSource::class.java))
		assertEquals(2, page1.size)

		val alpha = page1[0]
		assertEquals("/manhwa/alpha-work/", alpha.url)
		assertEquals("https://manhwaread.com/manhwa/alpha-work/", alpha.publicUrl)
		assertEquals("Alpha Title", alpha.title)
		assertEquals(setOf("Alt One", "Alt Two"), alpha.altTitles)
		assertEquals(setOf("Writer A", "Artist B"), alpha.authors)
		assertEquals("https://mancover.xyz/cover/alpha.webp", alpha.coverUrl)
		assertEquals(MangaState.ONGOING, alpha.state)
		assertEquals(ContentRating.ADULT, alpha.contentRating)
		assertEquals(0.84f, alpha.rating, 0.001f)

		val beta = page1[1]
		assertEquals("/manhwa/beta-work/", beta.url)
		assertEquals("https://mancover.xyz/cover/beta.webp", beta.coverUrl)
		assertNull(beta.state)
		assertEquals(RATING_UNKNOWN, beta.rating)

		parser.getListPage(2, SortOrder.POPULARITY_WEEK, MangaListFilter.EMPTY)
		assertEquals("https://manhwaread.com/page/2/?s=&sortby=weekly_top", context.requests.last().url.toString())
	}

	@Test
	fun advancedFiltersResolveIdsArtistsDemographicsStateAndYear() = runTest {
		val context = MockContext { req, _ ->
			when (req.url.encodedPath) {
				"/wp-admin/admin-ajax.php" -> {
					val taxonomy = req.url.queryParameter("taxonomy")
					val search = req.url.queryParameter("search").orEmpty()
					val json = when {
						taxonomy == "manga_genre" && search.equals("Drama", ignoreCase = true) ->
							"""{"results":[{"id":5501,"text":"Drama","count":"900"}]}"""
						taxonomy == "manga_tag" && search.equals("Ntr", ignoreCase = true) ->
							"""{"results":[{"id":6123,"text":"Ntr","count":"420"}]}"""
						taxonomy == "manga_author" && search.equals("Studio X", ignoreCase = true) ->
							"""{"results":[]}"""
						taxonomy == "manga_artist" && search.equals("Studio X", ignoreCase = true) ->
							"""{"results":[{"id":7788,"text":"Studio X","count":"4"}]}"""
						else -> """{"results":[]}"""
					}
					jsonResponse(json)
				}
				else -> htmlResponse("""<div class="manga-grid"></div>""")
			}
		}
		val parser = ManhwaRead(context)

		val genreTag = MangaTag(title = "Drama", key = "drama", source = MangaParserSource.MANHWAREAD)
		val spoilerTag = MangaTag(title = "Ntr", key = "ntr", source = MangaParserSource.MANHWAREAD)

		parser.getListPage(
			page = 2,
			order = SortOrder.RELEVANCE,
			filter = MangaListFilter(
				query = "secret test",
				tags = setOf(genreTag),
				tagsExclude = setOf(spoilerTag),
				states = EnumSet.of(MangaState.PAUSED),
				demographics = EnumSet.of(Demographic.SEINEN),
				year = 2023,
				author = "Studio X",
			),
		)
		val searchUrl = context.requests.last().url
		assertEquals("/page/2/", searchUrl.encodedPath)
		assertEquals("secret test", searchUrl.queryParameter("s"))
		assertEquals("contains", searchUrl.queryParameter("keyword_mode"))
		assertEquals("AND", searchUrl.queryParameter("s_mode"))
		assertEquals("on-hold", searchUrl.queryParameter("status"))
		assertEquals("2023-2023", searchUrl.queryParameter("year_range"))
		assertEquals(listOf("7788"), searchUrl.queryParameterValues("artists[]"))
		assertEquals(listOf("5557", "5501"), searchUrl.queryParameterValues("including[]"))
		assertEquals(listOf("6123"), searchUrl.queryParameterValues("excluding[]"))
		assertNull(searchUrl.queryParameter("sortby"))

		val beforeCount = context.requests.size
		val empty = parser.getListPage(1, SortOrder.UPDATED, MangaListFilter(author = "Nobody"))
		assertTrue(empty.isEmpty())
		assertEquals(beforeCount + 2, context.requests.size)
	}

	@Test
	fun filterOptionsUnifiesTagKeysDeduplicatesAndCachesIds() = runTest {
		val context = MockContext { req, _ ->
			when (req.url.encodedPath) {
				"/wp-admin/admin-ajax.php" -> {
					val taxonomy = req.url.queryParameter("taxonomy")
					val offset = req.url.queryParameter("offset")?.toIntOrNull() ?: 0
					val json = when {
						taxonomy == "manga_genre" -> """{
							"total": 2,
							"results": [
								{"id": 5501, "text": "Drama", "count": "1.2K"},
								{"id": 5502, "text": "Girl&#039;s Love", "count": "85"}
							]
						}"""
						taxonomy == "manga_tag" && offset == 0 -> """{
							"total": 45,
							"results": [
								{"id": 9001, "text": "Drama", "count": "12"},
								{"id": 6001, "text": "Age Gap", "count": "186"},
								{"id": 6002, "text": "Rare Niche", "count": "15"}
							]
						}"""
						taxonomy == "manga_tag" && offset == 40 -> """{
							"results": [
								{"id": 6003, "text": "Friends &amp; Lovers", "count": "120"}
							]
						}"""
						else -> """{"results":[]}"""
					}
					jsonResponse(json)
				}
				"/manhwa/sample-work/" -> htmlResponse(
					"""
					<html><body>
					<div id="mangaSummary">
						<div class="manga-titles"><h1>Sample Work</h1></div>
						<a href="https://manhwaread.com/genre/drama/">Drama</a>
						<a href="https://manhwaread.com/tag/age-gap/"><span>#</span><span>Age Gap</span><span>186</span></a>
					</div>
					<div id="chaptersList"></div>
					</body></html>
					""".trimIndent(),
				)
				else -> htmlResponse("""<div class="manga-grid"></div>""")
			}
		}
		val parser = ManhwaRead(context)

		val detailUrl = "https://manhwaread.com/manhwa/sample-work/".toHttpUrl()
		val detailed = requireNotNull(parser.resolveLink(LinkResolver(context, detailUrl), detailUrl))
		assertEquals(
			mapOf("Drama" to "drama", "Age Gap" to "age gap"),
			detailed.tags.associate { it.title to it.key },
		)

		val ageGapFromDetail = detailed.tags.first { it.title == "Age Gap" }
		val beforeSingleTag = context.requests.size
		parser.getListPage(1, SortOrder.UPDATED, MangaListFilter(tags = setOf(ageGapFromDetail)))
		assertEquals(beforeSingleTag + 1, context.requests.size)
		assertEquals("https://manhwaread.com/tag/age-gap/?sortby=release", context.requests.last().url.toString())

		val options = parser.getFilterOptions()
		assertTrue(options.availableLocales.isEmpty())

		val byTitle = options.availableTags.associateBy { it.title }
		assertEquals("drama", byTitle["Drama"]?.key)
		assertEquals("girl's love", byTitle["Girl's Love"]?.key)
		assertEquals("age gap", byTitle["Age Gap"]?.key)
		assertEquals("friends & lovers", byTitle["Friends & Lovers"]?.key)
		assertNull(byTitle["Rare Niche"])
		assertEquals(ageGapFromDetail, byTitle["Age Gap"])

		val afterOptionsCount = context.requests.size
		parser.getListPage(
			1,
			SortOrder.POPULARITY,
			MangaListFilter(
				tags = setOf(byTitle.getValue("Drama")),
				tagsExclude = setOf(ageGapFromDetail),
			),
		)
		assertEquals(afterOptionsCount + 1, context.requests.size)
		val filterUrl = context.requests.last().url
		assertEquals(listOf("5501"), filterUrl.queryParameterValues("including[]"))
		assertEquals(listOf("6001"), filterUrl.queryParameterValues("excluding[]"))
	}

	@Test
	fun detailsChaptersRelatedAndLinkResolution() = runTest {
		val detailHtml = """
			<html><body>
			<div id="mangaSummary">
				<div class="flex justify-center">
					<img data-lazy-src="https://mancover.xyz/cover/sample-work.webp" alt="Sample Work">
				</div>
				<div class="manga-titles">
					<h1>Sample Work</h1>
					<h2>Alt Alpha | Alt Beta</h2>
				</div>
				<div class="manga-status" data-status="hiatus">Hiatus</div>
				<div class="rating__current">4.5</div>
				<a href="https://manhwaread.com/author/writer-kim/"><span>#</span><span>Writer Kim</span></a>
				<a href="https://manhwaread.com/artist/art-park/"><span>#</span><span>Art Park</span></a>
				<a href="https://manhwaread.com/genre/romance/">Romance</a>
				<a href="https://manhwaread.com/tag/office-worker/"><span>#</span><span>Office Worker</span><span>340</span></a>
				<div class="text-primary">Updated:</div>
				<div>22/05/2026 23:50</div>
				<div id="mangaDesc">
					<div class="manga-desc__content"><p>Detailed plot summary.</p></div>
				</div>
			</div>
			<div id="chaptersList">
				<a class="chapter-item" href="/manhwa/sample-work/oneshot/">
					<span class="chapter-item__name">Oneshot</span>
					<span class="chapter-item__date">10/11/2019</span>
				</a>
				<a class="chapter-item" href="/manhwa/sample-work/chapter-075/">
					<span class="chapter-item__name">Chapter 075</span>
					<span class="chapter-item__date">15/05/2026</span>
				</a>
				<a class="chapter-item" href="/manhwa/sample-work/chapter-075-1/">
					<span class="chapter-item__name">Chapter 075</span>
					<span class="chapter-item__date">3d</span>
				</a>
				<a class="chapter-item" href="/manhwa/sample-work/chapter-156x5/">
					<span class="chapter-item__name">Chapter 156.5</span>
					<span class="chapter-item__date">New</span>
				</a>
			</div>
			<div class="swiper-wrapper">
				<div class="manga-item">
					<img src="https://mancover.xyz/cover/sample-work.webp">
					<a class="manga-item__link" href="/manhwa/sample-work/">Sample Work</a>
				</div>
				<div class="manga-item">
					<img src="https://mancover.xyz/cover/related-one.webp">
					<a class="manga-item__link" href="/manhwa/related-one/">Related One</a>
				</div>
			</div>
			</body></html>
		""".trimIndent()

		val context = MockContext { _, _ -> htmlResponse(detailHtml) }
		val parser = ManhwaRead(context)

		val linkCases = listOf(
			"https://manhwaread.com/manhwa/sample-work/",
			"https://manhwaread.com/manhwa/sample-work",
			"https://manhwaread.com/manhwa/sample-work/chapter-075/",
			"https://manhwaread.org/manhwa/sample-work/chapter-156x5/",
		)
		val resolvedFirst = requireNotNull(
			parser.resolveLink(LinkResolver(context, linkCases.first().toHttpUrl()), linkCases.first().toHttpUrl()),
		)
		for (rawLink in linkCases.drop(1)) {
			val url = rawLink.toHttpUrl()
			val resolved = requireNotNull(parser.resolveLink(LinkResolver(context, url), url))
			assertEquals(resolvedFirst.id, resolved.id)
			assertEquals("/manhwa/sample-work/", resolved.url)
			assertEquals("https://manhwaread.com/manhwa/sample-work/", resolved.publicUrl)
		}

		for (invalid in listOf(
			"https://manhwaread.com/genre/romance/",
			"https://manhwaread.com/manhwa/page/2/",
			"https://example.org/manhwa/sample-work/",
		)) {
			val url = invalid.toHttpUrl()
			assertNull(parser.resolveLink(LinkResolver(context, url), url))
		}

		assertEquals("Sample Work", resolvedFirst.title)
		assertEquals(setOf("Alt Alpha", "Alt Beta"), resolvedFirst.altTitles)
		assertEquals("https://mancover.xyz/cover/sample-work.webp", resolvedFirst.coverUrl)
		assertEquals(0.9f, resolvedFirst.rating, 0.001f)
		assertEquals(MangaState.PAUSED, resolvedFirst.state)
		assertEquals(setOf("Writer Kim", "Art Park"), resolvedFirst.authors)
		assertEquals("<p>Detailed plot summary.</p>", resolvedFirst.description)
		assertEquals(
			mapOf("Romance" to "romance", "Office Worker" to "office worker"),
			resolvedFirst.tags.associate { it.title to it.key },
		)

		val chapters = requireNotNull(resolvedFirst.chapters)
		assertEquals(4, chapters.size)
		assertEquals(0f, chapters[0].number)
		assertEquals(1573344000000L, chapters[0].uploadDate)
		assertEquals(75f, chapters[1].number)
		assertEquals(75.01f, chapters[2].number)
		assertEquals(0L, chapters[2].uploadDate)
		assertEquals(156.5f, chapters[3].number)
		assertEquals(1779493800000L, chapters[3].uploadDate)

		val related = parser.getRelatedManga(resolvedFirst)
		assertEquals(listOf("/manhwa/related-one/"), related.map { it.url })
	}

	@Test
	fun pagesDecodesUnpaddedBase64AndSkipsMp4Credits() = runTest {
		val rawPayload = """[{"src":"p_001.webp","w":720,"h":4000},{"src":"/promo_credit.MP4","w":720,"h":720},{"src":"p_002.webp","w":720,"h":3800}]"""
		val unpaddedB64 = Base64.getEncoder().withoutPadding().encodeToString(rawPayload.toByteArray(Charsets.UTF_8))
		val chapterHtml = """
			<html><body>
			<script>
			var chapterData = {"id": 99, "base": "https:\/\/manread.xyz\/100\/200\/", "data": "$unpaddedB64"};
			</script>
			</body></html>
		""".trimIndent()

		val context = MockContext { req, _ ->
			if (req.url.encodedPath.contains("broken")) {
				htmlResponse("<html><body>No reader script</body></html>")
			} else {
				htmlResponse(chapterHtml)
			}
		}
		val parser = ManhwaRead(context)
		val chapter = MangaChapter(
			id = 1L,
			title = "Chapter 1",
			number = 1f,
			volume = 0,
			url = "/manhwa/sample-work/chapter-001/",
			scanlator = null,
			uploadDate = 0L,
			branch = null,
			source = MangaParserSource.MANHWAREAD,
		)

		val pages = parser.getPages(chapter)
		assertEquals(
			listOf("https://manread.xyz/100/200/p_001.webp", "https://manread.xyz/100/200/p_002.webp"),
			pages.map { it.url },
		)
		assertEquals(2, pages.map { it.id }.distinct().size)

		val brokenChapter = chapter.copy(url = "/manhwa/sample-work/broken/")
		assertThrows(ParseException::class.java) {
			kotlinx.coroutines.runBlocking { parser.getPages(brokenChapter) }
		}
	}

	private class MockContext(
		private val handler: (Request, Int) -> Response,
	) : MangaLoaderContext() {

		val requests = ArrayList<Request>()

		override val httpClient: OkHttpClient = OkHttpClient.Builder()
			.addInterceptor { chain ->
				val req = chain.request()
				requests += req
				handler(req, requests.size)
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
		private fun htmlResponse(body: String): Response = Response.Builder()
			.request(Request.Builder().url("https://manhwaread.com/").build())
			.protocol(Protocol.HTTP_1_1)
			.code(200)
			.message("OK")
			.body(body.toResponseBody("text/html; charset=utf-8".toMediaType()))
			.build()

		private fun jsonResponse(body: String): Response = Response.Builder()
			.request(Request.Builder().url("https://manhwaread.com/").build())
			.protocol(Protocol.HTTP_1_1)
			.code(200)
			.message("OK")
			.body(body.toResponseBody("application/json; charset=utf-8".toMediaType()))
			.build()
	}
}
