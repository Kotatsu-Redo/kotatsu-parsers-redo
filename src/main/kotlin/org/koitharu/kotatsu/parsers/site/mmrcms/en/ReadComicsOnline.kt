package org.koitharu.kotatsu.parsers.site.mmrcms.en

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.site.mmrcms.MmrcmsParser
import org.koitharu.kotatsu.parsers.util.attrAsRelativeUrl
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.mapChapters
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.parseSafe
import org.koitharu.kotatsu.parsers.util.requireSrc
import org.koitharu.kotatsu.parsers.util.src
import org.koitharu.kotatsu.parsers.util.textOrNull
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.toRelativeUrl
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("READCOMICSONLINE", "ReadComicsOnline.ru", "en", ContentType.COMICS)
internal class ReadComicsOnline(context: MangaLoaderContext) :
	MmrcmsParser(context, MangaParserSource.READCOMICSONLINE, "readcomicsonline.ru") {

	private val chapterDateFormat = SimpleDateFormat("d MMM yyyy", Locale.US)

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.POPULARITY,
		SortOrder.UPDATED,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities()

	override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions()

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		val newRequest = request.newBuilder()
			.header("Referer", "https://$domain/")
			.build()
		return chain.proceed(newRequest)
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		if (!filter.query.isNullOrEmpty() || filter.tags.isNotEmpty() || filter.states.isNotEmpty()) {
			return emptyList()
		}
		val sort = when (order) {
			SortOrder.UPDATED -> "latest"
			else -> "views"
		}
		val doc = webClient.httpGet("https://$domain/comic-list?sort=$sort&page=$page").parseHtml()
		return doc.select("div.comic-list-layout .grid > .group").mapNotNull(::parseMangaListItem)
	}

	private fun parseMangaListItem(element: Element): Manga? {
		val anchor = element.selectFirst("a.block.text-sm.font-semibold") ?: return null
		val href = anchor.attrAsRelativeUrl("href")
		return Manga(
			id = generateUid(href),
			title = anchor.text(),
			altTitles = emptySet(),
			url = href,
			publicUrl = href.toAbsoluteUrl(element.baseUriHost()),
			rating = RATING_UNKNOWN,
			contentRating = if (isNsfwSource) ContentRating.ADULT else null,
			coverUrl = guessCover(href, element.selectFirst("img")?.src()),
			tags = emptySet(),
			state = null,
			authors = emptySet(),
			source = source,
		)
	}

	override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
		val fullUrl = manga.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(fullUrl).parseHtml()
		val chaptersDeferred = async { getChapters(doc, manga.title) }
		manga.copy(
			title = doc.selectFirst("h1.text-2xl")?.textOrNull() ?: manga.title,
			coverUrl = guessCover(manga.url, doc.selectFirst("img.w-full.rounded-xl")?.src()) ?: manga.coverUrl,
			description = doc.selectFirst("p.mt-5.text-sm")?.textOrNull(),
			state = parseState(doc.selectFirst("div.flex.flex-wrap.gap-2 span.rounded-full")?.text()),
			tags = doc.select("dl div:contains(Genres:) a").mapTo(LinkedHashSet()) {
				MangaTag(
					key = it.attr("href").removeSuffix("/").substringAfterLast('/'),
					title = it.text(),
					source = source,
				)
			},
			authors = doc.select("div:has(span:contains(Author:)) > a").mapTo(LinkedHashSet()) { it.text() },
			chapters = chaptersDeferred.await(),
		)
	}

	private fun parseState(value: String?): MangaState? {
		return when (value?.lowercase(Locale.US)) {
			"complete", "completed" -> MangaState.FINISHED
			"ongoing", "on going" -> MangaState.ONGOING
			"dropped", "cancelled", "canceled" -> MangaState.ABANDONED
			else -> null
		}
	}

	private fun getChapters(doc: Document, mangaTitle: String): List<MangaChapter> {
		return doc.select(".overflow-hidden.border-ink-600 > a").mapChapters(reversed = true) { i, element ->
			val href = element.attrAsRelativeUrl("href")
			val chapterName = element.selectFirst(".text-brand-400")?.textOrNull() ?: element.text()
			MangaChapter(
				id = generateUid(href),
				title = cleanChapterName(mangaTitle, chapterName),
				number = i + 1f,
				volume = 0,
				url = href,
				uploadDate = chapterDateFormat.parseSafe(element.selectFirst(".text-slate-500")?.text()),
				source = source,
				scanlator = null,
				branch = null,
			)
		}
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
		return doc.select("#reader-all img").mapIndexed { i, img ->
			val url = img.requireSrc().toRelativeUrl(domain)
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}

	private fun cleanChapterName(mangaTitle: String, chapterName: String): String {
		return chapterName
			.removePrefix(mangaTitle)
			.trimStart(' ', '-', ':')
			.nullIfEmpty()
			?: chapterName
	}

	private fun guessCover(mangaUrl: String, imageUrl: String?): String? {
		imageUrl?.takeUnless { it.contains("/cover/cover_missing.", ignoreCase = true) }?.let {
			return it
		}
		val slug = mangaUrl.removeSuffix("/").substringAfterLast('/').nullIfEmpty() ?: return null
		return "https://$domain/uploads/manga/$slug/cover/cover_250x350.jpg"
	}

	private fun Element.baseUriHost(): String {
		return baseUri().substringAfter("://", domain).substringBefore('/').ifEmpty { domain }
	}
}
