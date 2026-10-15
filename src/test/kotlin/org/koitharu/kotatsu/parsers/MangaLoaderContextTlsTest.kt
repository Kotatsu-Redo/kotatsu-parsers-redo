package org.koitharu.kotatsu.parsers

import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

internal class MangaLoaderContextTlsTest {

	@Test
	fun usesDefaultHostnameVerifier() {
		assertSame(OkHttpClient().hostnameVerifier, MangaLoaderContextMock.httpClient.hostnameVerifier)
	}

	@Test
	fun rejectsEmptyCertificateChain() {
		val trustManager = requireNotNull(MangaLoaderContextMock.httpClient.x509TrustManager)
		assertThrows(IllegalArgumentException::class.java) {
			trustManager.checkServerTrusted(emptyArray(), "RSA")
		}
	}
}
