package org.koitharu.kotatsu.parsers.site.mangareader.en

import okhttp3.Headers
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser
import org.koitharu.kotatsu.parsers.util.attrAsAbsoluteUrlOrNull
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import java.util.*

@MangaSourceParser("ROKARICOMICS", "Rokari Comics", "en")
internal class RokariComics(context: MangaLoaderContext) :
	MangaReaderParser(
		context = context,
		source = MangaParserSource.ROKARICOMICS,
		domain = "rokaricomics.com",
		pageSize = 20,
		searchPageSize = 10,
	) {

	override val sourceLocale: Locale = Locale.ENGLISH
	override val selectChapter = "#chapterlist li:has(div.chbox):has(div.eph-num):has(a[href]):not(:has(.text-gold))"

	override val filterCapabilities: MangaListFilterCapabilities
		get() = super.filterCapabilities.copy(
			isTagsExclusionSupported = false,
		)

	override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
		.add("Referer", "https://$domain/")
		.build()

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val chapterUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(chapterUrl).parseHtml()
		val known = HashSet<String>()
		val pages = ArrayList<MangaPage>()

		doc.select("#readerarea img[src], #readerarea img[data-src], #readerarea img[data-lazy-src]").forEach { img ->
			val url = img.attrAsAbsoluteUrlOrNull("data-lazy-src")
				?: img.attrAsAbsoluteUrlOrNull("data-src")
				?: img.attrAsAbsoluteUrlOrNull("src")
				?: return@forEach
			if (known.add(url)) {
				pages += MangaPage(
					id = generateUid(url),
					url = url,
					preview = null,
					source = source,
				)
			}
		}

		return pages.ifEmpty {
			super.getPages(chapter)
		}
	}

}
