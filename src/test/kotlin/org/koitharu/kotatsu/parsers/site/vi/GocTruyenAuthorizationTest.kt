package org.koitharu.kotatsu.parsers.site.vi

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.koitharu.kotatsu.parsers.webview.InterceptedRequest

internal class GocTruyenAuthorizationTest {

	private val origin = "https://example.org".toHttpUrl()

	@Test
	fun acceptsSameOriginApiBearerWithCaseInsensitiveHeader() {
		assertEquals("bearer session-token", header("https://example.org/api/v2/search", "bearer session-token"))
	}

	@Test
	fun rejectsOtherOriginsAndNonApiRequests() {
		for (url in listOf(
			"https://attacker.example/api/v2/search",
			"https://example.org.attacker.example/api/v2/search",
			"http://example.org/api/v2/search",
			"https://example.org:8443/api/v2/search",
			"https://example.org/profile",
			"https://user@example.org/api/v2/search",
			"not a URL",
		)) {
			assertNull(header(url, "Bearer session-token"), url)
		}
	}

	@Test
	fun rejectsMissingMalformedAndInjectedHeaders() {
		for (value in listOf("", "Bearer ", "Basic session-token", "Bearer token\r\nX-Test: injected", "Bearer two tokens")) {
			assertNull(header("https://example.org/api/v2/search", value))
		}
		assertNull(GocTruyenAuthorization.bearerHeader(request("https://example.org/api/v2/search", emptyMap()), origin))
	}

	private fun header(url: String, value: String): String? =
		GocTruyenAuthorization.bearerHeader(request(url, mapOf("aUtHoRiZaTiOn" to value)), origin)

	private fun request(url: String, headers: Map<String, String>) =
		InterceptedRequest(url = url, method = "GET", headers = headers, timestamp = 0L)
}
