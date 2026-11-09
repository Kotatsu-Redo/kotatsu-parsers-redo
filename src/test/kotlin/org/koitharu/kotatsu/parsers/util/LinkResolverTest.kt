package org.koitharu.kotatsu.parsers.util

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.koitharu.kotatsu.parsers.MangaLoaderContextMock
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import kotlin.time.Duration.Companion.minutes

internal class LinkResolverTest {

	private val context = MangaLoaderContextMock

	@Test
	fun supportedSource() = runTest(timeout = 2.minutes) {
		val resolver = context.newLinkResolver("REDACTED" /* do not publish links to manga on GitHub */)
		Assertions.assertEquals(MangaParserSource.MANGADEX, resolver.getSource())
		val manga = resolver.getManga()
		Assertions.assertEquals(resolver.link.toString(), manga?.publicUrl)
	}

	@Test
	fun sharedDomainPrefersWorkingSourceWithMatchingLocale() = runTest {
		val cases = mapOf(
			"https://www.webtoons.com/en/fantasy/example/list?title_no=123" to MangaParserSource.WEBTOONS_EN,
			"https://www.webtoons.com/fr/fantasy/example/list?title_no=123" to MangaParserSource.WEBTOONS_FR,
			"https://www.webtoons.com/de/canvas/example/list?title_no=123" to MangaParserSource.WEBTOONS_DE,
			"https://m.webtoons.com/zh-hant/fantasy/example/ep-1/viewer?title_no=123" to MangaParserSource.WEBTOONS_ZH,
			"https://webtoons.com/id/fantasy/example/list?title_no=123" to MangaParserSource.WEBTOONS_ID,
		)
		for ((url, expected) in cases) {
			Assertions.assertEquals(expected, context.newLinkResolver(url).getSource(), url)
		}
	}

	@Test
	fun exactHostWinsOverSharedTopDomain() = runTest {
		val cases = mapOf(
			"https://www.niadd.com/manga/example.html" to MangaParserSource.NINEMANGA_EN,
			"https://es.niadd.com/manga/example.html" to MangaParserSource.NINEMANGA_ES,
			"https://br.niadd.com/manga/example.html" to MangaParserSource.NINEMANGA_BR,
			"https://fr.niadd.com/manga/example.html" to MangaParserSource.NINEMANGA_FR,
		)
		for ((url, expected) in cases) {
			Assertions.assertEquals(expected, context.newLinkResolver(url).getSource(), url)
		}
	}

	@Test
	fun unsupportedSource2()= runTest(timeout = 2.minutes) {
		val resolver = context.newLinkResolver("REDACTED" /* do not publish links to manga on GitHub */)
		Assertions.assertEquals(MangaParserSource.XBATCAT, resolver.getSource())
		val manga = resolver.getManga()
		Assertions.assertEquals(resolver.link.toString(), manga?.publicUrl)
	}
}
