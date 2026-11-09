package org.koitharu.kotatsu.parsers.site.pt

import kotlinx.coroutines.test.runTest
import okhttp3.Response
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaLoaderContextMock
import org.koitharu.kotatsu.parsers.MangaParser
import org.koitharu.kotatsu.parsers.bitmap.Bitmap
import org.koitharu.kotatsu.parsers.config.MangaSourceConfig
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.model.search.MangaSearchQuery

private const val FULL_TITLE = "O Filho Mais Novo do Mestre Espadachim"

internal class NexusMangasWebViewTest {

	@Test
	fun popularListReturnsMangaWhenWebViewPageIsReady() = runTest {
		val parser = NexusMangas(PromiseAwareContext())

		val manga = parser.getList(MangaSearchQuery.EMPTY)

		assertEquals("Obra de teste", manga.single().title)
	}

	@Test
	fun searchResultExtractsTitleAndCoverFromRenderedCard() = runTest {
		val parser = NexusMangas(PromiseAwareContext())
		val result = parser.getList(
			offset = 0,
			order = SortOrder.RELEVANCE,
			filter = MangaListFilter(query = FULL_TITLE),
		).single()

		println("NEXUS_RESULT title=" + result.title + "; url=" + result.publicUrl + "; cover=" + result.coverUrl + "; state=" + result.state)

		assertEquals(FULL_TITLE, result.title)
		assertEquals(MangaState.ONGOING, result.state)
		assertEquals("https://supabase.nexusmangas.com/functions/v1/read-public-media?key=works%2Fcc6347a2-eea3-4113-bd8c-bcef8e072b39%2Fcover%2Fcover_1783708301304.webp", result.coverUrl)
	}

	@Test
	fun onePieceSearchExtractsBothRenderedResults() = runTest {
		val context = PromiseAwareContext()
		val parser = NexusMangas(context)
		val results = parser.getList(
			offset = 0,
			order = SortOrder.RELEVANCE,
			filter = MangaListFilter(query = "One Piece"),
		)

		println("NEXUS_ONE_PIECE_SEARCH results=" + results.joinToString(" | ") {
			"${it.title}; url=${it.publicUrl}; cover=${it.coverUrl}; state=${it.state}"
		})

		assertEquals(listOf("One Piece", "One Piece Gakuen"), results.map { it.title })
		assertTrue(
			context.requestedUrl.startsWith("https://www.nexusmangas.com/search?q=One"),
		)
		assertEquals(MangaState.ONGOING, results.first().state)
		assertEquals(
			"https://supabase.nexusmangas.com/functions/v1/read-public-media?key=works%2Fcd336f4c-741a-493e-a3c5-ffef1fb4019d%2Fcover%2Fcover_1783103603780.webp",
			results.first().coverUrl,
		)
	}

	@Test
	fun onePieceDetailsRequestsManualActionAtWebsiteGate() = runTest {
		val parser = NexusMangas(PromiseAwareContext())
		val manga = parser.getList(
			offset = 0,
			order = SortOrder.RELEVANCE,
			filter = MangaListFilter(query = "One Piece"),
		).first()

		val error = try {
			parser.getDetails(manga)
			throw AssertionError("Expected Nexus access gate to request a user action")
		} catch (e: BrowserActionRequested) {
			e
		}

		println("NEXUS_ONE_PIECE_DETAILS action_required=${error.url}")
		assertEquals("https://www.nexusmangas.com/obra/one-piece", error.url)
	}

	@Test
	fun detailsExtractsChapterTitleAndUploadDateFromSeparateCardFields() = runTest {
		val parser = NexusMangas(PromiseAwareContext(PromiseAwareContext.RENDERED_ONE_PIECE_DETAILS))
		val manga = parser.getList(
			offset = 0,
			order = SortOrder.RELEVANCE,
			filter = MangaListFilter(query = "One Piece"),
		).first()

		val details = parser.getDetails(manga)
		val chapter = details.chapters.orEmpty().first()

		assertEquals(1194f, chapter.number)
		assertEquals("Todas as coisas mudam", chapter.title)
		assertEquals("/capitulo/one-piece/1194", chapter.url)
		assertTrue(chapter.uploadDate > 0L)
	}

	@Test
	fun pagesIncludeOnlyRenderedChapterPagesAndSkipAdImages() = runTest {
		val parser = NexusMangas(
			PromiseAwareContext(
				detailsHtml = PromiseAwareContext.RENDERED_ONE_PIECE_DETAILS,
				pageHtml = PromiseAwareContext.RENDERED_ONE_PIECE_PAGES,
			),
		)
		val manga = parser.getList(
			offset = 0,
			order = SortOrder.RELEVANCE,
			filter = MangaListFilter(query = "One Piece"),
		).first()
		val chapter = parser.getDetails(manga).chapters.orEmpty().first()

		val pages = parser.getPages(chapter)

		assertEquals(
			listOf(
				"https://cdn.nexusmangas.com/one-piece/1194/1.webp",
				"https://cdn.nexusmangas.com/one-piece/1194/2.webp",
			),
			pages.map { it.url },
		)
	}

	@Test
	fun pagesWithoutImagesFailInsteadOfReturningAnEmptyChapter() = runTest {
		val parser = NexusMangas(
			PromiseAwareContext(
				detailsHtml = PromiseAwareContext.RENDERED_ONE_PIECE_DETAILS,
				pageHtml = "<html><body>Capítulo indisponível</body></html>",
			),
		)
		val manga = parser.getList(
			offset = 0,
			order = SortOrder.RELEVANCE,
			filter = MangaListFilter(query = "One Piece"),
		).first()
		val chapter = parser.getDetails(manga).chapters.orEmpty().first()

		val error = try {
			parser.getPages(chapter)
			throw AssertionError("Expected empty chapter pages to be reported as a parse error")
		} catch (e: org.koitharu.kotatsu.parsers.exception.ParseException) {
			e
		}

		assertEquals("https://www.nexusmangas.com/capitulo/one-piece/1194", error.url)
	}

	private class BrowserActionRequested(val url: String) : RuntimeException()

	private class PromiseAwareContext(
		private val detailsHtml: String = RENDERED_ACCESS_GATE,
		private val pageHtml: String = RENDERED_ONE_PIECE_PAGES,
	) : MangaLoaderContext() {

		private val delegate = MangaLoaderContextMock
		var requestedUrl = ""
			private set

		override val httpClient get() = delegate.httpClient
		override val cookieJar get() = delegate.cookieJar

		@Deprecated("Provide a base url")
		override suspend fun evaluateJs(script: String): String? = evaluateJs("", script, 10_000L)

		override suspend fun evaluateJs(baseUrl: String, script: String, timeout: Long): String? {
			requestedUrl = baseUrl
			// Android WebView serializes an unawaited Promise result as an empty object.
			if (script.contains("new Promise")) return "{}"
			val html = when {
				script.contains("__nexusMangasSearchState") && script.contains("One Piece") -> RENDERED_ONE_PIECE_SEARCH
				script.contains("__nexusMangasSearchState") -> RENDERED_SEARCH
				script.contains("__nexusMangasDetailsState") -> detailsHtml
				script.contains("__nexusMangasPagesState") -> pageHtml
				else -> RENDERED_RANKING
			}
			return JSONObject.quote(html)
		}

		override fun getConfig(source: MangaSource): MangaSourceConfig = delegate.getConfig(source)
		override fun getDefaultUserAgent(): String = delegate.getDefaultUserAgent()

		override fun redrawImageResponse(response: Response, redraw: (Bitmap) -> Bitmap): Response =
			delegate.redrawImageResponse(response, redraw)

		override fun createBitmap(width: Int, height: Int): Bitmap = delegate.createBitmap(width, height)

		override fun requestBrowserAction(parser: MangaParser, url: String): Nothing =
			throw BrowserActionRequested(url)

		companion object {
			const val RENDERED_RANKING = """
				<html><body>
					<h2>OBRAS MAIS VISTAS</h2>
					<a href="/obra/obra-de-teste"><h3>Obra de teste</h3></a>
				</body></html>
			"""
			const val RENDERED_ONE_PIECE_SEARCH = """
				<html><body>
					<div>2 resultados</div>
					<a class="block group" href="/obra/one-piece">
						<div>
							<img src="https://supabase.nexusmangas.com/functions/v1/read-public-media?key=works%2Fcd336f4c-741a-493e-a3c5-ffef1fb4019d%2Fcover%2Fcover_1783103603780.webp" alt="One Piece" />
							<span>MANGA</span><span>ATIVO</span>
							<h3 title="One Piece">ONE PIECE</h3>
							<p>ELEVEN SCANLATOR</p>
						</div>
					</a>
					<a class="block group" href="/obra/one-piece-gakuen">
						<div>
							<img src="https://supabase.nexusmangas.com/functions/v1/read-public-media?key=works%2F003c2ca0-deed-46ce-a9d5-ef4f4251f829%2Fcover%2Fcover_1784815479731.webp" alt="One Piece Gakuen" />
							<span>MANGA</span><span>ATIVO</span>
							<h3 title="One Piece Gakuen">ONE PIECE GAKUEN</h3>
							<p>GRAND CITY SCANS</p>
						</div>
					</a>
				</body></html>
			"""
			const val RENDERED_ACCESS_GATE = """
				<html><body>
					<main><h1>One Piece</h1></main>
					<dialog open>
						<h2>Desbloqueie mais conteúdo</h2>
						<p>Realize uma ação para continuar acessando o conteúdo deste site</p>
						<button>Assista um rápido anúncio — Acesso a todo o site por 24 horas</button>
					</dialog>
				</body></html>
			"""
			const val RENDERED_ONE_PIECE_DETAILS = """
				<html><body>
					<main>
						<h1>One Piece</h1>
						<h3>Sinopse Detalhada</h3>
						<p>Uma aventura na Grand Line.</p>
						<div>Avaliação Nexus 4.8</div>
						<div>74 Lançamentos</div>
						<h2>Capítulos</h2>
						<div class="overflow-y-auto">
							<div class="group cursor-pointer">
								<div class="flex flex-col gap-2">
									<span>Cap. 1194<span class="truncate">- Todas as coisas mudam</span></span>
									<span>Eleven Scanlator</span>
									<span>25/09/2026</span>
									<span>13 págs</span>
								</div>
							</div>
						</div>
					</main>
				</body></html>
			"""
			const val RENDERED_ONE_PIECE_PAGES = """
				<html><body>
					<img alt="Anúncio patrocinado" src="https://ads.example/ad.webp" />
					<img alt="Página 1" data-kotatsu-page-url="https://cdn.nexusmangas.com/one-piece/1194/1.webp" />
					<img alt="Página 2" data-kotatsu-page-url="https://cdn.nexusmangas.com/one-piece/1194/2.webp" />
					<img alt="Página 3" data-kotatsu-page-url="data:image/gif;base64,AAAA" />
				</body></html>
			"""
			const val RENDERED_SEARCH = """
				<html><body>
					<div>1 resultados</div>
					<a class="block group" href="/obra/o-filho-mais-novo-do-mestre-espadachim">
						<div>
							<img src="https://supabase.nexusmangas.com/functions/v1/read-public-media?key=works%2Fcc6347a2-eea3-4113-bd8c-bcef8e072b39%2Fcover%2Fcover_1783708301304.webp" alt="O Filho Mais Novo do Mestre Espadachim" />
							<span>MANHWA</span><span>ATIVO</span>
							<h3 title="O Filho Mais Novo do Mestre Espadachim">O Filho Mais Novo do Mest...</h3>
							<p>NX Scan</p>
						</div>
					</a>
				</body></html>
			"""
		}
	}
}
