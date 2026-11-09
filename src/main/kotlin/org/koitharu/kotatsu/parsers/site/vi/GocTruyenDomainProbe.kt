package org.koitharu.kotatsu.parsers.site.vi

import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import org.jsoup.Jsoup
import org.koitharu.kotatsu.parsers.util.await
import java.util.concurrent.TimeUnit

/**
 * Candidate checks are anonymous and do not follow redirects or run application credential interceptors.
 * Site markers reject parked/unrelated pages; they are not cryptographic proof of domain ownership.
 */
internal class GocTruyenDomainProbe(client: OkHttpClient, private val userAgent: String) {
	private val client = client.newBuilder()
		.apply {
			interceptors().clear()
			networkInterceptors().clear()
		}
		.cookieJar(CookieJar.NO_COOKIES)
		.authenticator(Authenticator.NONE)
		.proxyAuthenticator(Authenticator.NONE)
		.followRedirects(false)
		.followSslRedirects(false)
		.cache(null)
		.connectTimeout(5, TimeUnit.SECONDS)
		.readTimeout(5, TimeUnit.SECONDS)
		.callTimeout(10, TimeUnit.SECONDS)
		.build()

	suspend fun advertisedDomain(domain: String): String? {
		val page = get("https://$domain/trang-chu") ?: return null
		if (!isExpectedPage(page)) return null
		return extractAdvertisedDomain(page)
	}

	suspend fun isValid(domain: String): Boolean {
		val page = get("https://$domain/trang-chu") ?: return false
		if (!isExpectedPage(page)) return false
		val body = get("https://$domain/api/v2/search?p=0&limit=1") ?: return false
		return isExpectedApi(body)
	}

	private suspend fun get(url: String): String? {
		val request = Request.Builder().url(url).header("User-Agent", userAgent)
			.header("X-Requested-With", "XMLHttpRequest").build()
		return client.newCall(request).await().use { response ->
			if (!response.isSuccessful) return@use null
			val source = response.body.source()
			// Parked or hostile candidates must not make discovery consume an unbounded response.
			source.request(MAX_RESPONSE_BYTES + 1)
			if (source.buffer.size > MAX_RESPONSE_BYTES) null else source.buffer.readUtf8()
		}
	}

	companion object {
		private const val MAX_RESPONSE_BYTES = 1024L * 1024L
		private val MESSAGE_ALL = Regex("""\bmessageAll\s*:\s*([`"'])(.*?)\1""", RegexOption.DOT_MATCHES_ALL)
		private val CHECK_DOMAIN = Regex("""\b(?:let|const|var)\s+checkDomain\s*=\s*([`"'])(.*?)\1""")
		private val HTTPS_URL = Regex("""https://[^\s<>"'`,;]+""", RegexOption.IGNORE_CASE)

		fun extractAdvertisedDomain(page: String): String? {
			// Read only the site's migration-message fields, not ads, arbitrary links, or stale JSON-LD URLs.
			val scripts = Jsoup.parse(page).select("script:not([src])").map { it.data() }
			val values = scripts.flatMap { script ->
				MESSAGE_ALL.findAll(script).flatMap { HTTPS_URL.findAll(it.groupValues[2]).map { url -> url.value } }.toList() +
					CHECK_DOMAIN.findAll(script).map { match ->
						val value = match.groupValues[2].trim()
						if (value.contains("://")) value else "https://$value"
					}.toList()
			}
			return values.mapNotNull { value ->
				val url = value.toHttpUrlOrNull() ?: return@mapNotNull null
				url.host.takeIf {
					url.scheme == "https" && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() &&
						GocTruyenDomainResolver.domainNumber(it) != null
				}
			}.maxByOrNull { requireNotNull(GocTruyenDomainResolver.domainNumber(it)) }
		}

		fun isExpectedPage(page: String): Boolean {
			val document = Jsoup.parse(page)
			return document.title().contains("Goc Truyen Tranh", ignoreCase = true) &&
				document.selectFirst("script[src*='/contents/v2/js/common.js']") != null &&
				document.selectFirst("a[href^='/truyen/']") != null
		}

		fun isExpectedApi(body: String): Boolean = try {
			val json = JSONObject(body)
			val item = json.optJSONObject("result")?.optJSONArray("data")?.optJSONObject(0)
			json.optBoolean("status") && json.optInt("code") == 200 && item != null &&
				item.optString("id").isNotBlank() && item.optString("name").isNotBlank() &&
				item.optString("nameEn").isNotBlank()
		} catch (_: JSONException) {
			false
		}
	}
}
