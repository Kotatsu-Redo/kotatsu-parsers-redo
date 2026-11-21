package org.koitharu.kotatsu.parsers.site.mangareader.id

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import okhttp3.Cookie
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.internal.closeQuietly
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser
import org.koitharu.kotatsu.parsers.util.*

@MangaSourceParser("KOMIKTAP", "KomikTap", "id", ContentType.HENTAI)
internal class KomikTapParser(context: MangaLoaderContext) :
	MangaReaderParser(context, MangaParserSource.KOMIKTAP, "komiktap.info", pageSize = 25, searchPageSize = 10) {

	override val filterCapabilities: MangaListFilterCapabilities
		get() = super.filterCapabilities.copy(
			isTagsExclusionSupported = false,
		)

	override fun intercept(chain: Interceptor.Chain): Response {
		val response = chain.proceed(chain.request())
		if (response.code != 307 && response.header("Server")?.contains("Sucuri", ignoreCase = true) != true) {
			return response
		}
		if (response.mimeType?.endsWith("/html") == false) {
			return response
		}
		val script = response.copy().parseHtml().select("script").firstNotNullOfOrNull { s ->
			s.data().takeIf { it.contains("sucuri_cloudproxy_js") }
		} ?: return response

		val cookie = runBlocking {
			mutex.withLock {
				val wrappedScript = """
					(function() {
						var __cookie = "";
						var __doc = {
							set cookie(v) { __cookie = v; },
							get cookie() { return __cookie; }
						};
						var __loc = { reload: function() {} };
						var __e = function(code) {
							return (new Function("document", "location", code))(__doc, __loc);
						};
						${script.replace("e=eval", "e=__e")}
						return __cookie;
					})();
				""".trimIndent()
				val cookieStr = context.evaluateJs("https://$domain/", wrappedScript, 10000L)
					?.takeIf { it.isNotBlank() }
				cookieStr?.let { Cookie.parse(response.request.url, it) }
			}
		} ?: return response

		context.cookieJar.insertCookie(domain, cookie)
		response.closeQuietly()
		return chain.proceed(response.request.newBuilder().build())
	}
}
