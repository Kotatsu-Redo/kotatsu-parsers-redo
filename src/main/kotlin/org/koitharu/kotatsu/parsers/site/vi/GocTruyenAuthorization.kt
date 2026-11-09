package org.koitharu.kotatsu.parsers.site.vi

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.koitharu.kotatsu.parsers.webview.InterceptedRequest

internal object GocTruyenAuthorization {

	fun bearerHeader(request: InterceptedRequest, origin: HttpUrl): String? {
		val url = request.url.toHttpUrlOrNull() ?: return null
		if (url.scheme != "https" || url.host != origin.host || url.port != origin.port ||
			!url.encodedPath.startsWith("/api/") || url.username.isNotEmpty() || url.password.isNotEmpty()
		) {
			return null
		}
		val authorization = request.headers.entries
			.firstOrNull { it.key.equals("Authorization", ignoreCase = true) }?.value ?: return null
		// RFC 6750 bearer syntax; also prevents whitespace/header injection in captured values.
		return authorization.takeIf { BEARER_PATTERN.matches(it) }
	}

	private val BEARER_PATTERN = Regex("Bearer [A-Za-z0-9._~+/-]+=*", RegexOption.IGNORE_CASE)
}
