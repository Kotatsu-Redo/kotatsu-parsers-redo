package org.koitharu.kotatsu.parsers.site.madara.id

import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Response
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.network.CloudFlareHelper
import org.koitharu.kotatsu.parsers.network.CommonHeaders
import java.util.*

@MangaSourceParser("MGKOMIK", "MgKomik", "id")
internal class Mgkomik(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.MGKOMIK, "id.mgkomik.cc", 20) {

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override val tagPrefix = "genres/"
    override val listUrl = "komik/"
    override val datePattern = "dd MMM yy"
    override val stylePage = ""
    override val sourceLocale: Locale = Locale.ENGLISH

    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add(CommonHeaders.ACCEPT, "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8")
        .add(CommonHeaders.ACCEPT_LANGUAGE, "en-US,en;q=0.9,id;q=0.8")
        .add(CommonHeaders.SEC_FETCH_DEST, "document")
        .add(CommonHeaders.SEC_FETCH_MODE, "navigate")
        .add(CommonHeaders.SEC_FETCH_SITE, "same-origin")
        .add(CommonHeaders.SEC_FETCH_USER, "?1")
        .add(CommonHeaders.UPGRADE_INSECURE_REQUESTS, "1")
        .add("Sec-CH-UA-Model", "\"\"")
        .build()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val path = request.url.encodedPath

        val isAjax = path.contains("admin-ajax.php") ||
            path.contains("wp-json") ||
            path.endsWith("/ajax/chapters") ||
            path.endsWith("/ajax/chapters/")

        val modifiedRequest = if (isAjax) {
            request.newBuilder()
                .header(CommonHeaders.X_REQUESTED_WITH, "XMLHttpRequest")
                .header(CommonHeaders.SEC_FETCH_DEST, "empty")
                .header(CommonHeaders.SEC_FETCH_MODE, "cors")
                .header(CommonHeaders.SEC_FETCH_SITE, "same-origin")
                .header("Origin", "https://$domain")
                .removeHeader(CommonHeaders.SEC_FETCH_USER)
                .removeHeader(CommonHeaders.UPGRADE_INSECURE_REQUESTS)
                .build()
        } else {
            request
        }

        val response = chain.proceed(modifiedRequest)
        val protection = CloudFlareHelper.checkResponseForProtection(response)
        if (protection != CloudFlareHelper.PROTECTION_NOT_DETECTED) {
            response.close()
            context.requestBrowserAction(this, request.url.toString())
        }
        return response
    }
}
