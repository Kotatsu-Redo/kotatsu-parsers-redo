package org.koitharu.kotatsu.parsers.site.pt

import kotlinx.coroutines.test.runTest
import okhttp3.Response
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaLoaderContextMock
import org.koitharu.kotatsu.parsers.bitmap.Bitmap
import org.koitharu.kotatsu.parsers.config.MangaSourceConfig
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.site.madara.pt.LeitorDeManga
import org.koitharu.kotatsu.parsers.site.madara.pt.MangasBrasukas
import org.koitharu.kotatsu.parsers.exception.ParseException

internal class MangaCatalogParserSearchTest {

	@Test
	fun leitorSearchUsesCatalogQueryAndFindsDandadan() = runTest {
		val context = StaticHtmlContext(LIST_RESULT)
		val parser = LeitorDeManga(context)

		val result = parser.getList(
			offset = 0,
			order = SortOrder.POPULARITY,
			filter = MangaListFilter(query = "Dandadan"),
		).single()

		assertTrue(context.requestedUrl.startsWith("https://leitordemangas.com/catalogo?q=Dandadan"))
		assertEquals("Dandadan", result.title)
		assertEquals("/manga/dandadan", result.url)
		assertEquals("https://cdn.example/dandadan.webp", result.coverUrl)
		assertEquals(1f, result.rating)
	}

	@Test
	fun mangasBrasukasSearchUsesBuscaRouteAndFindsDandadan() = runTest {
		val context = StaticHtmlContext(LIST_RESULT)
		val parser = MangasBrasukas(context)

		val result = parser.getList(
			offset = 0,
			order = SortOrder.POPULARITY,
			filter = MangaListFilter(query = "Dandadan"),
		).single()

		assertTrue(context.requestedUrl.startsWith("https://mangasbrasuka.org/buscar?q=Dandadan"))
		assertEquals("Dandadan", result.title)
		assertEquals(MangaParserSource.MANGASBRASUKAS, result.source)
	}

	@Test
	fun mangasBrasukasBrowseUsesCatalogInsteadOfEmptySearch() = runTest {
		val context = StaticHtmlContext(LIST_RESULT)
		val parser = MangasBrasukas(context)

		parser.getList(
			offset = 0,
			order = SortOrder.POPULARITY,
			filter = MangaListFilter.EMPTY,
		)

		assertEquals("https://mangasbrasuka.org/catalogo", context.requestedUrl)
	}

	@Test
	fun detailsParseAuthorsDescriptionAndCompleteChapterList() = runTest {
		val parser = LeitorDeManga(StaticHtmlContext(LIST_RESULT, DETAILS_RESULT))
		val manga = parser.getList(
			offset = 0,
			order = SortOrder.POPULARITY,
			filter = MangaListFilter(query = "Dandadan"),
		).single()

		val details = parser.getDetails(manga)

		assertEquals("Yukinobu Tatsu", details.authors.single())
		assertEquals(setOf("DAN DA DAN"), details.altTitles)
		assertEquals("Momo e Okarun descobrem o sobrenatural.", details.description)
		assertEquals(listOf(1f, 2f), details.chapters.orEmpty().map { it.number })
		assertEquals("Primeiro encontro", details.chapters?.first()?.title)
		assertTrue(details.chapters?.first()?.uploadDate ?: 0L > 0L)
	}

	@Test
	fun detailsFailWhenLazyChapterListIsIncomplete() = runTest {
		val parser = LeitorDeManga(StaticHtmlContext(LIST_RESULT, INCOMPLETE_DETAILS_RESULT))
		val manga = parser.getList(
			offset = 0,
			order = SortOrder.POPULARITY,
			filter = MangaListFilter(query = "Dandadan"),
		).single()

		val error = try {
			parser.getDetails(manga)
			throw AssertionError("Expected incomplete lazy chapters to be reported")
		} catch (e: ParseException) {
			e
		}

		assertTrue(error.message.orEmpty().contains("loaded 1 of 2 chapters"))
	}

	private class StaticHtmlContext(
		private val listHtml: String,
		private val detailsHtml: String? = null,
	) : MangaLoaderContext() {

		private val delegate = MangaLoaderContextMock
		var requestedUrl = ""
			private set

		override val httpClient get() = delegate.httpClient
		override val cookieJar get() = delegate.cookieJar

		@Deprecated("Provide a base url")
		override suspend fun evaluateJs(script: String): String? = evaluateJs("", script, 10_000L)

		override suspend fun evaluateJs(baseUrl: String, script: String, timeout: Long): String {
			requestedUrl = baseUrl
			val html = if (script.contains("__kazujiMangaCatalogDetailsState")) {
				detailsHtml ?: listHtml
			} else {
				listHtml
			}
			return JSONObject.quote(html)
		}

		override fun getConfig(source: MangaSource): MangaSourceConfig = delegate.getConfig(source)
		override fun getDefaultUserAgent(): String = delegate.getDefaultUserAgent()

		override fun redrawImageResponse(response: Response, redraw: (Bitmap) -> Bitmap): Response =
			delegate.redrawImageResponse(response, redraw)

		override fun createBitmap(width: Int, height: Int): Bitmap = delegate.createBitmap(width, height)
	}

	private companion object {
		const val LIST_RESULT = """
			<html data-kotatsu-list-page="1">
				<body>
					<a href="/manga/dandadan" data-kotatsu-manga-url="/manga/dandadan"
						data-kotatsu-manga-title="Dandadan" data-kotatsu-cover="https://cdn.example/dandadan.webp">
						<h3>Dandadan</h3>
						<span>MANGA Shounen 5.0 Capítulo 256</span>
					</a>
				</body>
			</html>
		"""

		const val DETAILS_RESULT = """
			<html data-kotatsu-chapter-total="2" data-kotatsu-chapter-count="2">
				<body>
					<main>
						<h1>Dandadan</h1>
						<p>DAN DA DAN Por Yukinobu Tatsu MANGA Em Lançamento 5.0 (1)</p>
						<h2>Sinopse</h2>
						<p data-kotatsu-description="">Momo e Okarun descobrem o sobrenatural.</p>
						<a href="/manga/dandadan/1" data-kotatsu-chapter-url="/manga/dandadan/1"
							data-kotatsu-chapter-number="1" data-kotatsu-chapter-text="Capítulo 1 — Primeiro encontro 27 de jul. de 2026"
							data-kotatsu-upload-date="2026-07-27T00:00:00Z">Capítulo 1</a>
						<a href="/manga/dandadan/2" data-kotatsu-chapter-url="/manga/dandadan/2"
							data-kotatsu-chapter-number="2" data-kotatsu-chapter-text="Capítulo 2">Capítulo 2</a>
					</main>
				</body>
			</html>
		"""

		const val INCOMPLETE_DETAILS_RESULT = """
			<html data-kotatsu-chapter-total="2" data-kotatsu-chapter-count="1">
				<body>
					<main>
						<h1>Dandadan</h1>
						<h2>Capítulos (2)</h2>
						<a href="/manga/dandadan/1" data-kotatsu-chapter-url="/manga/dandadan/1"
							data-kotatsu-chapter-number="1" data-kotatsu-chapter-text="Capítulo 1">Capítulo 1</a>
					</main>
				</body>
			</html>
		"""
	}
}
