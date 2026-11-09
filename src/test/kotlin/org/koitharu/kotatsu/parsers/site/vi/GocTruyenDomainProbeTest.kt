package org.koitharu.kotatsu.parsers.site.vi

import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.UnknownHostException

internal class GocTruyenDomainProbeTest {

	@Test
	fun requiresSiteSpecificPageMarkersNotJustHttpSuccess() {
		assertTrue(GocTruyenDomainProbe.isExpectedPage(PAGE))
		assertFalse(GocTruyenDomainProbe.isExpectedPage("<title>Domain for sale</title>"))
		assertFalse(GocTruyenDomainProbe.isExpectedPage(PAGE.replace("common.js", "other.js")))
		assertFalse(GocTruyenDomainProbe.isExpectedPage(PAGE.replace("/truyen/", "/other/")))
	}

	@Test
	fun requiresExpectedPublicApiSchemaAndMangaData() {
		assertTrue(GocTruyenDomainProbe.isExpectedApi(API))
		for (body in listOf(
			"<html>Cloudflare challenge</html>",
			"{}",
			"{\"status\":true,\"code\":200,\"result\":{\"data\":[]}}",
			API.replace("\"nameEn\":\"example\"", "\"nameEn\":\"\""),
			API.replace("\"code\":200", "\"code\":403"),
		)) {
			assertFalse(GocTruyenDomainProbe.isExpectedApi(body))
		}
	}

	@Test
	fun doesNotReuseApplicationCredentialInterceptorsOrCookieJar() = runTest {
		var interceptorCalls = 0
		var cookieLoads = 0
		val requests = mutableListOf<Request>()
		val client = OkHttpClient.Builder()
			.cookieJar(object : CookieJar {
				override fun loadForRequest(url: HttpUrl): List<Cookie> {
					cookieLoads++
					return listOf(Cookie.Builder().name("session").value("test").domain(url.host).build())
				}
				override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
			})
			.addInterceptor { chain ->
				interceptorCalls++
				chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer test-session").build())
			}
			.eventListener(object : EventListener() {
				override fun callStart(call: Call) { requests += call.request() }
			})
			.dns { throw UnknownHostException("simulated offline candidate") }
			.build()
		runCatching { GocTruyenDomainProbe(client, "test").isValid("goctruyentranhvui42.com") }
		assertEquals(1, requests.size)
		assertNull(requests.single().header("Authorization"))
		assertNull(requests.single().header("Cookie"))
		assertEquals(0, cookieLoads)
		assertEquals(0, interceptorCalls)
	}

	@Test
	fun extractsLiveMigrationNoticeFieldsRatherThanArbitraryLinks() {
		val page = PAGE + """<script>
			const commonMessages = { messageAll: `notice104||Link web hiện tại là vui30, vui 31, -> https://goctruyentranhvui41.com, mọi người để ý link` };
			let checkDomain = "GOCTRUYENTRANHVUI41.COM";
			</script><a href="https://goctruyentranhvui99.com">Unrelated ad</a>
			<script type="application/ld+json">{"url":"https://goctruyentranhvui80.com"}</script>"""
		assertEquals("goctruyentranhvui41.com", GocTruyenDomainProbe.extractAdvertisedDomain(page))
	}

	@Test
	fun takesHighestDomainOnlyFromExplicitMigrationFields() {
		val page = """<script>
			const commonMessages = { messageAll: 'Old https://goctruyentranhvui30.com, new https://goctruyentranhvui45.com' };
			const checkDomain = 'goctruyentranhvui41.com';
			</script>"""
		assertEquals("goctruyentranhvui45.com", GocTruyenDomainProbe.extractAdvertisedDomain(page))
	}

	@Test
	fun rejectsUnsafeOrForeignMigrationTargets() {
		for (url in listOf(
			"http://goctruyentranhvui50.com",
			"https://goctruyentranhvui50.com.attacker.example",
			"https://attacker.example/goctruyentranhvui50.com",
			"https://user@goctruyentranhvui50.com",
			"https://goctruyentranhvui50.com:8443",
		)) {
			assertNull(GocTruyenDomainProbe.extractAdvertisedDomain("<script>let checkDomain = '$url';</script>"), url)
		}
		assertNull(GocTruyenDomainProbe.extractAdvertisedDomain(PAGE))
	}

	private companion object {
		const val PAGE = """<html><head><title>Đọc truyện [Goc Truyen Tranh]</title>
			<script src="/contents/v2/js/common.js?v=1003"></script></head>
			<body><a href="/truyen/example">Example</a></body></html>"""
		const val API = """{"status":true,"code":200,"result":{"data":[{"id":"1","name":"Example","nameEn":"example"}]}}"""
	}
}
