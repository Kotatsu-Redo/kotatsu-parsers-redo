package org.koitharu.kotatsu.parsers.site.madara.id

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.Headers
import okhttp3.Headers.Companion.headersOf
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("YURILAB", "YuriLab", "id", ContentType.HENTAI)
internal class YuriLab(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.YURILAB, "yurilab.top", pageSize = 30) {

	override val sourceLocale: Locale = Locale.ENGLISH
	override val withoutAjax = true
	override val datePattern = "d MMMM yyyy"

	// m_orderby=rating lists nothing on this site
	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.NEWEST,
		SortOrder.ALPHABETICAL,
		SortOrder.RELEVANCE,
	)

	override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
		.set("Referer", "https://$domain/")
		.build()

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://")
			append(domain)
			if (page > 0) {
				append("/page/")
				append(page + 1)
			}
			append("/?s=")
			filter.query?.let { append(it.urlEncoded()) }
			append("&post_type=wp-manga")
			filter.tags.forEach {
				append("&genre[]=")
				append(it.key)
			}
			// The site answers HTTP 500 to the theme's usual status[]=, it only takes one status=
			filter.states.oneOrThrowIfMany()?.let {
				append("&status=")
				append(
					when (it) {
						MangaState.ONGOING -> "on-going"
						MangaState.FINISHED -> "end"
						MangaState.ABANDONED -> "canceled"
						MangaState.PAUSED -> "on-hold"
						MangaState.UPCOMING -> "upcoming"
						else -> throw IllegalArgumentException("$it not supported")
					},
				)
			}
			filter.contentRating.oneOrThrowIfMany()?.let {
				append("&adult=")
				append(if (it == ContentRating.ADULT) "1" else "0")
			}
			if (filter.year != 0) {
				append("&release=")
				append(filter.year)
			}
			append("&m_orderby=")
			when (order) {
				SortOrder.POPULARITY -> append("views")
				SortOrder.UPDATED -> append("latest")
				SortOrder.NEWEST -> append("new-manga")
				SortOrder.ALPHABETICAL -> append("alphabet")
				else -> Unit
			}
		}
		return parseMangaList(webClient.httpGet(url).parseHtml())
	}

	override fun parseMangaList(doc: Document): List<Manga> {
		val items = doc.select("div.manga__item")
		if (items.isEmpty()) {
			return super.parseMangaList(doc)
		}
		return items.mapNotNull { div ->
			val a = div.selectFirst(".post-title a") ?: return@mapNotNull null
			val href = a.attrAsRelativeUrl("href")
			Manga(
				id = generateUid(href),
				url = href,
				publicUrl = href.toAbsoluteUrl(div.host ?: domain),
				coverUrl = div.selectFirst("img")?.src()?.replace(thumbnailSizeRegex, ""),
				title = a.textOrNull() ?: return@mapNotNull null,
				altTitles = emptySet(),
				rating = RATING_UNKNOWN,
				tags = div.select(".manga-genres a").mapNotNullToSet { tag ->
					MangaTag(
						key = tag.attr("href").removeSuffix('/').substringAfterLast('/'),
						title = tag.textOrNull()?.toTitleCase(sourceLocale) ?: return@mapNotNullToSet null,
						source = source,
					)
				},
				authors = emptySet(),
				state = null,
				source = source,
				contentRating = if (isNsfwSource) ContentRating.ADULT else null,
			)
		}
	}

	override suspend fun fetchAvailableTags(): Set<MangaTag> {
		val url = urlBuilder().apply {
			addQueryParameter("s", "")
			addQueryParameter("post_type", "wp-manga")
		}.build()
		val docs = webClient.httpGet(url).parseHtml()
		val genreLinks = docs.select(".genres-filter .dropdown-menu a[href*='genre=']")
		return genreLinks.mapNotNullToSet { el ->
			val href = el.attrOrNull("href") ?: return@mapNotNullToSet null
			val key = href.findGroupValue(genreKeyRegex) ?: return@mapNotNullToSet null
			val title = el.textOrNull()?.toTitleCase(sourceLocale) ?: return@mapNotNullToSet null
			MangaTag(
				title = title,
				key = key,
				source = source,
			)
		}
	}

	override val selectGenre = ".genres-content a[href*='genre'], .tags-content a[href*='tag']"

	override suspend fun createMangaTag(a: Element): MangaTag? {
		val href = a.attrOrNull("href") ?: return null
		val tagKey = extractTagKey(href) ?: return null
		val title = a.textOrNull() ?: return null
		return MangaTag(
			title = title,
			key = tagKey,
			source = source,
		)
	}

	private fun extractTagKey(href: String): String? {
		href.findGroupValue(genreKeyRegex)?.let { return it }
		val match = tagPathRegex.find(href) ?: return null
		return match.groupValues[1].ifEmpty { match.groupValues[2] }.nullIfEmpty()
	}

	override val selectChapter = "ul.version-chap li.wp-manga-chapter"

	// The chapter list is paginated (?t=1, 2, ...): a page past the end has no chapters
	override suspend fun loadChapters(mangaUrl: String, document: Document): List<MangaChapter> {
		val ajaxUrl = mangaUrl.toAbsoluteUrl(domain).removeSuffix('/') + "/ajax/chapters/"
		val items = ArrayList<Element>()
		var page = 1
		var previousFirst: String? = null
		pages@ while (true) {
			val batch = coroutineScope {
				(page until page + CHAPTER_PAGES_BATCH).map { t ->
					async { fetchChapterPage(ajaxUrl, t) }
				}.awaitAll()
			}
			for (pageItems in batch) {
				// Stop on an empty page, or if the site repeats a page instead of returning an empty one
				val first = pageItems.firstOrNull()?.outerHtml()
				if (first == null || first == previousFirst) {
					break@pages
				}
				previousFirst = first
				items.addAll(pageItems)
			}
			page += CHAPTER_PAGES_BATCH
		}
		val dateFormat = SimpleDateFormat(datePattern, sourceLocale)
		val usedNumbers = HashSet<Float>()
		return items.mapChapters(reversed = true) { _, li ->
			val a = li.selectFirst("a") ?: return@mapChapters null
			// Coin-locked chapters link to "#" and their reader page has no images
			val href = a.attrAsRelativeUrlOrNull("href")?.takeUnless { it.startsWith('#') }
				?: return@mapChapters null
			val title = a.textOrNull()
			MangaChapter(
				id = generateUid(href),
				url = href,
				title = title,
				number = title?.findGroupValue(chapterNumberRegex)?.toFloatOrNull()
					?.takeIf { usedNumbers.add(it) } ?: 0f,
				volume = 0,
				branch = null,
				uploadDate = parseDate(
					dateFormat,
					li.selectFirst("a.c-new-tag")?.attr("title") ?: li.selectFirst(selectDate)?.text(),
				),
				scanlator = null,
				source = source,
			)
		}
	}

	private suspend fun fetchChapterPage(ajaxUrl: String, page: Int): List<Element> = webClient.httpPost(
		"$ajaxUrl?t=$page".toHttpUrl(),
		emptyMap(),
		headersOf("X-Requested-With", "XMLHttpRequest"),
	).parseHtml().select(selectChapter)

	// Dates are "8 October" without a year, also for chapters from past years, so only
	// relative dates ("2 days ago") and dates with a year can be told
	private fun parseDate(dateFormat: SimpleDateFormat, date: String?): Long {
		if (date.isNullOrBlank() || date.trim().matches(dayMonthRegex)) {
			return 0L
		}
		return parseChapterDate(dateFormat, date.trim())
	}

	private companion object {

		const val CHAPTER_PAGES_BATCH = 5

		val thumbnailSizeRegex = Regex("""-\d+x\d+(?=\.\w+$)""")
		val genreKeyRegex = Regex("""genre=([^&/?]+)""")
		val tagPathRegex = Regex("""series-genre/([^/?]+)|series-tag/([^/?]+)""", RegexOption.IGNORE_CASE)
		val chapterNumberRegex = Regex("""(?i)chapter\s*(\d+(?:\.\d+)?)""")
		val dayMonthRegex = Regex("""\d{1,2}\s+\p{L}+""")
	}
}
