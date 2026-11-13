package org.koitharu.kotatsu.parsers.site.es

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Headers
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.util.*

/**
 * MHScans (mhscans.com): server-rendered pages. The site used to be a Madara theme at
 * mhscans.mundoalterno.org and came back under new addresses for titles and chapters, so nothing saved
 * from the old one carries over.
 */
@MangaSourceParser("MHSCANS", "MhScans", "es")
internal class MhScans(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.MHSCANS, pageSize = 24) {

	override val configKeyDomain = ConfigKey.Domain("mhscans.com")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.NEWEST,
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.RATING,
		SortOrder.ALPHABETICAL,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
		)

	private val throttle = Mutex()
	private var lastRequestAt = 0L

	// The site answers a burst of requests with HTTP 429, so page requests are spaced out
	private suspend fun get(url: String, headers: Headers? = null): Response = throttle.withLock {
		val wait = lastRequestAt + REQUEST_INTERVAL_MS - System.currentTimeMillis()
		if (wait > 0) delay(wait)
		try {
			webClient.httpGet(url, headers)
		} finally {
			lastRequestAt = System.currentTimeMillis()
		}
	}

	override suspend fun getFilterOptions(): MangaListFilterOptions {
		val doc = get("https://$domain/library").parseHtml()
		// Genres are only listed as filter links of the library page
		val genres = doc.select("a.dd-opt[href*=genre=]").mapNotNullTo(LinkedHashSet()) { a ->
			val name = a.absUrl("href").toHttpUrl().queryParameter("genre") ?: return@mapNotNullTo null
			MangaTag(key = name, title = a.text().ifEmpty { name }, source = source)
		}
		return MangaListFilterOptions(
			availableTags = genres,
			availableStates = EnumSet.of(
				MangaState.ONGOING,
				MangaState.FINISHED,
				MangaState.PAUSED,
				MangaState.ABANDONED,
			),
			availableContentTypes = EnumSet.of(
				ContentType.MANGA,
				ContentType.MANHWA,
				ContentType.MANHUA,
				ContentType.OTHER,
			),
		)
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = "https://$domain/library".toHttpUrl().newBuilder().apply {
			filter.query?.takeUnless { it.isBlank() }?.let { addQueryParameter("q", it) }
			addQueryParameter(
				"sort",
				when (order) {
					SortOrder.UPDATED -> "updated"
					SortOrder.POPULARITY -> "views"
					SortOrder.RATING -> "rating"
					SortOrder.ALPHABETICAL -> "title"
					else -> "latest"
				},
			)
			filter.types.oneOrThrowIfMany()?.let { type ->
				addQueryParameter(
					"type",
					when (type) {
						ContentType.MANGA -> "Manga"
						ContentType.MANHWA -> "Manhwa"
						ContentType.MANHUA -> "Manhua"
						else -> "Other"
					},
				)
			}
			filter.states.oneOrThrowIfMany()?.let { state ->
				addQueryParameter(
					"status",
					when (state) {
						MangaState.ONGOING -> "Ongoing"
						MangaState.FINISHED -> "Completed"
						MangaState.PAUSED -> "Hiatus"
						else -> "Cancelled"
					},
				)
			}
			filter.tags.oneOrThrowIfMany()?.let { addQueryParameter("genre", it.key) }
			// "page=1" is answered with a redirect to the same list without it
			if (page > 1) addQueryParameter("page", page.toString())
		}.build()
		val doc = get(url.toString()).parseHtml()
		return doc.select("div.lib-grid > a[href^=/comics/]")
			// Novels are text chapters without images, so they can't be read here
			.filterNot { it.selectFirst("div.comic-cover ~ span")?.text()?.trim() == "Novel" }
			.map { a ->
				val href = a.attrAsRelativeUrl("href")
				Manga(
					id = generateUid(href),
					title = a.selectFirstOrThrow("p").text(),
					altTitles = emptySet(),
					url = href,
					publicUrl = a.attrAsAbsoluteUrl("href"),
					rating = a.selectFirst("div.comic-cover ~ span.font-mono")?.text()
						?.filter { it.isDigit() || it == '.' }?.toFloatOrNull()
						?.let { it / 10f } ?: RATING_UNKNOWN,
					contentRating = null,
					coverUrl = a.selectFirst("img")?.src(),
					tags = emptySet(),
					state = null,
					authors = emptySet(),
					source = source,
				)
			}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val mangaUrl = manga.url.toAbsoluteUrl(domain)
		val doc = get(mangaUrl).parseHtml()
		val hero = doc.selectFirstOrThrow(".comic-hero")
		return manga.copy(
			title = hero.selectFirstOrThrow("h1").text(),
			coverUrl = hero.selectFirst(".comic-hero__cover img")?.src() ?: manga.coverUrl,
			description = doc.selectFirst("#comic-description")?.text(),
			tags = doc.select(".comic-chips li").mapTo(LinkedHashSet()) { li ->
				MangaTag(key = li.text(), title = li.text(), source = source)
			},
			state = when (doc.selectFirst(".comic-meta__status")?.text()?.trim()?.lowercase()) {
				"en emisión" -> MangaState.ONGOING
				"completado" -> MangaState.FINISHED
				"en hiato", "en pausa" -> MangaState.PAUSED
				"cancelado", "cancelled" -> MangaState.ABANDONED
				else -> null
			},
			chapters = getChapters(mangaUrl),
		)
	}

	// The chapter list is an HTML fragment endpoint; the X-Pages header holds the number of pages
	private suspend fun getChapters(mangaUrl: String): List<MangaChapter> {
		val headers = Headers.Builder()
			.add("X-Requested-With", "XMLHttpRequest")
			.add("Referer", mangaUrl)
			.build()
		val first = get("$mangaUrl/chapters?page=1", headers)
		val totalPages = first.header("X-Pages")?.toIntOrNull() ?: 1
		val items = parseChapters(first.parseHtml()).toMutableList()
		for (p in 2..totalPages) {
			items += parseChapters(get("$mangaUrl/chapters?page=$p", headers).parseHtml())
		}
		// The site lists the newest chapter first
		return items.asReversed()
	}

	// Paid chapters link to /auth/login instead of /leer/<id> until they are bought, so they are skipped
	private fun parseChapters(doc: Document): List<MangaChapter> =
		doc.select("a[href^=/leer/][data-chapter-num]").map { a ->
			val href = a.attrAsRelativeUrl("href")
			MangaChapter(
				id = generateUid(href),
				title = a.selectFirst("[data-cl-title]")?.text(),
				number = a.attr("data-chapter-num").toFloatOrNull() ?: 0f,
				volume = 0,
				url = href,
				scanlator = null,
				uploadDate = parseRelativeDate(a.selectFirst("[data-cl-title] + div > span")?.text()),
				branch = null,
				source = source,
			)
		}

	private fun parseRelativeDate(text: String?): Long {
		val match = RELATIVE_DATE.find(text?.lowercase() ?: return 0L) ?: return 0L
		val amount = match.groupValues[1].toIntOrNull() ?: return 0L
		val unit = match.groupValues[2]
		val field = when {
			unit.startsWith("min") -> Calendar.MINUTE
			unit.startsWith("h") -> Calendar.HOUR_OF_DAY
			unit.startsWith("d") -> Calendar.DAY_OF_YEAR
			unit.startsWith("sem") -> Calendar.WEEK_OF_YEAR
			unit.startsWith("mes") -> Calendar.MONTH
			unit.startsWith("a") -> Calendar.YEAR
			else -> return 0L
		}
		return Calendar.getInstance().apply { add(field, -amount) }.timeInMillis
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = get(chapter.url.toAbsoluteUrl(domain)).parseHtml()
		return doc.select("img.page-img").map { img ->
			val url = img.attrAsAbsoluteUrlOrNull("data-src") ?: img.attrAsAbsoluteUrl("src")
			MangaPage(id = generateUid(url), url = url, preview = null, source = source)
		}
	}

	private companion object {
		const val REQUEST_INTERVAL_MS = 3000L
		val RELATIVE_DATE = Regex("""hace (\d+) (\p{L}+)""")
	}
}
