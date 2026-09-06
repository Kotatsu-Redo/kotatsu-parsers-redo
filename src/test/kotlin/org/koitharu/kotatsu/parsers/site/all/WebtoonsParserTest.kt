package org.koitharu.kotatsu.parsers.site.all

import kotlinx.coroutines.test.runTest
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.SourceConfigMock
import org.koitharu.kotatsu.parsers.bitmap.Bitmap
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.util.generateUid

internal class WebtoonsParserTest {

    private val locales = mapOf(
        "en" to MangaParserSource.WEBTOONS_EN,
        "id" to MangaParserSource.WEBTOONS_ID,
        "es" to MangaParserSource.WEBTOONS_ES,
        "fr" to MangaParserSource.WEBTOONS_FR,
        "th" to MangaParserSource.WEBTOONS_TH,
        "zh-hant" to MangaParserSource.WEBTOONS_ZH,
        "de" to MangaParserSource.WEBTOONS_DE,
    )

    @Test
    fun genericResolverRoutesEveryLocale() = runTest {
        for ((locale, target) in locales) {
            val context = FixtureContext()
            val url = "https://www.webtoons.com/$locale/fantasy/example/list?title_no=123"

            val manga = context.newLinkResolver(url).getManga()

            assertResolved(context, manga, target, url, "webtoon")
        }
    }

    @Test
    fun activeAndLegacyEntryParsersRouteToUrlLocale() = runTest {
        val cases = listOf(
            MangaParserSource.WEBTOONS_EN to MangaParserSource.WEBTOONS_FR,
            MangaParserSource.LINEWEBTOONS_DE to MangaParserSource.WEBTOONS_ID,
        )
        for ((initial, target) in cases) {
            val locale = locales.entries.single { it.value == target }.key
            val context = FixtureContext()
            val resolver = context.newLinkResolver(
                "https://www.webtoons.com/$locale/canvas/example/list?title_no=123",
            )

            val manga = context.newParserInstance(initial).resolveLink(resolver, resolver.link)

            assertResolved(context, manga, target, resolver.link.toString(), "canvas")
        }
    }

    @Test
    fun supportedHostsAndLinkShapesNormalizeToSeries() = runTest {
        val cases = listOf(
            LinkCase(
                input = "https://webtoons.com/en/fantasy/example/list?title_no=123&sortOrder=ASC#series",
                expected = "https://webtoons.com/en/fantasy/example/list?title_no=123",
                type = "webtoon",
            ),
            LinkCase(
                input = "https://www.webtoons.com/en/canvas/example/viewer?episode_no=45&title_no=123#comments",
                expected = "https://www.webtoons.com/en/canvas/example/list?title_no=123",
                type = "canvas",
            ),
            LinkCase(
                input = "https://m.webtoons.com/en/fantasy/example/episode-one/viewer?title_no=123&episode_no=45",
                expected = "https://m.webtoons.com/en/fantasy/example/list?title_no=123",
                type = "webtoon",
            ),
        )
        for (case in cases) {
            val context = FixtureContext()
            val resolver = context.newLinkResolver(case.input)

            val manga = context.newParserInstance(MangaParserSource.WEBTOONS_EN)
                .resolveLink(resolver, resolver.link)

            assertResolved(context, manga, MangaParserSource.WEBTOONS_EN, case.expected, case.type)
        }
    }

    @Test
    fun malformedLinksReturnNullWithoutRequestsAtParserBoundary() = runTest {
        val invalid = listOf(
            "https://webtoons.com.example.org/en/fantasy/example/list?title_no=123",
            "https://www.webtoons.com/unsupported/fantasy/example/list?title_no=123",
            "https://www.webtoons.com/en/fantasy/example/search?title_no=123",
            "https://www.webtoons.com/en/fantasy/example/extra/list?title_no=123",
            "https://www.webtoons.com/en/fantasy/example/list",
            "https://www.webtoons.com/en/fantasy/example/list?title_no=123&title_no=456",
            "https://www.webtoons.com/en/fantasy/example/list?title_no=garbage",
            "https://www.webtoons.com/en/fantasy/example/list?title_no=0",
            "https://www.webtoons.com/en/fantasy/example/list?title_no=9223372036854775808",
        )
        val context = FixtureContext()
        val parser = context.newParserInstance(MangaParserSource.WEBTOONS_EN)

        for (url in invalid) {
            val resolver = context.newLinkResolver(url)
            assertNull(parser.resolveLink(resolver, resolver.link), url)
        }
        assertTrue(context.requests.isEmpty())
    }

    private fun assertResolved(
        context: FixtureContext,
        manga: Manga?,
        target: MangaParserSource,
        publicUrl: String,
        type: String,
    ) {
        requireNotNull(manga)
        assertEquals(target, manga.source)
        assertEquals(context.newParserInstance(target).generateUid(123L), manga.id)
        assertEquals("123", manga.url)
        assertEquals("Fixture title", manga.title)
        assertEquals(publicUrl, manga.publicUrl)
        assertEquals(publicUrl, context.requests.first().toString())
        assertEquals(2, context.requests.size)
        assertEquals("/api/v1/$type/123/episodes", context.requests.last().encodedPath)
        assertEquals(target, manga.chapters!!.single().source)
        assertEquals(target, manga.tags.single().source)
    }

    private data class LinkCase(
        val input: String,
        val expected: String,
        val type: String,
    )

    /** Synthetic HTTP responses only: no connections to live manga sites. */
    private class FixtureContext : MangaLoaderContext() {
        val requests = mutableListOf<HttpUrl>()

        override val cookieJar = CookieJar.NO_COOKIES

        override val httpClient = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests.add(request.url)
            val isApi = request.url.encodedPath.startsWith("/api/")
            val body = if (isApi) {
                """
                    {
                      "result": {
                        "episodeList": [{
                          "episodeTitle": "Episode 1",
                          "episodeNo": 1,
                          "viewerLink": "https://webtoons.com/en/a/b/viewer?title_no=123&episode_no=1",
                          "exposureDateMillis": 0
                        }]
                      }
                    }
                """.trimIndent()
            } else {
                """<html><head><meta property="og:title" content="Fixture title"></head>
                    <body><h2 class="genre">Fantasy</h2></body></html>"""
            }
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body.toResponseBody((if (isApi) "application/json" else "text/html").toMediaType()))
                .build()
        }.build()

        override fun getConfig(source: MangaSource) = SourceConfigMock()

        override fun getDefaultUserAgent() = "WebtoonsParserTest"

        @Deprecated("Provide a base url")
        override suspend fun evaluateJs(script: String): String? = error("Unexpected JavaScript")

        override suspend fun evaluateJs(baseUrl: String, script: String, timeout: Long): String? =
            error("Unexpected JavaScript")

        override fun redrawImageResponse(response: Response, redraw: (Bitmap) -> Bitmap): Response =
            error("Unexpected image")

        override fun createBitmap(width: Int, height: Int): Bitmap = error("Unexpected image")
    }
}
