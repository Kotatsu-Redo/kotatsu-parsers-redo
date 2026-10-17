package org.koitharu.kotatsu.parsers.site.madara.ar

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.core.HttpMangaParser
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

@MangaSourceParser("ARABTOONS", "ArabToons", "ar", ContentType.HENTAI)
internal class ArabToons(context: MangaLoaderContext) : HttpMangaParser(context) {

    override val domain: String = "arabtoons.net"
    override val baseUrl: String = "https://arabtoons.net"

    override fun latestMangaRequest(page: Int): Request {
        // طلب قائمة الأعمال أو الأحدث باستخدام مسار الـ API الجديد
        return GET("$baseUrl/api/manga?page=$page", headers)
    }

    override fun parseLatestManga(response: String): MangasPage {
        val jsonArray = JSONArray(response)
        val mangaList = mutableListOf<Manga>()

        for (i in 0 until jsonArray.length()) {
            val item = jsonArray.getJSONObject(i)
            val slug = item.getString("slug")
            mangaList.add(
                Manga(
                    id = item.getLong("id"),
                    title = item.getString("title"),
                    url = "/manga/$slug",
                    coverUrl = item.optString("cover"),
                    source = source
                )
            )
        }
        return MangasPage(mangaList, false)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: Set<MangaFilter>): Request {
        return GET("$baseUrl/api/search?q=$query&page=$page", headers)
    }

    override fun parseSearchManga(response: String): MangasPage = parseLatestManga(response)

    // بقية الدوال الخاصة بالفصول وصفحات القراءة هتبني على نفس الـ Endpoints للـ API
}
