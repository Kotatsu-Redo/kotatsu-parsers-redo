package org.koitharu.kotatsu.parsers.site.madara.en

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

// The site left Madara for its own theme on manhwaz.cc; manga and chapter urls keep the /webtoon/<slug> scheme
@MangaSourceParser("MANHWAZ", "ManhwaZ", "en")
internal class Manhwaz(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.MANHWAZ, pageSize = 40, searchPageSize = 30) {

	override val configKeyDomain = ConfigKey.Domain("manhwaz.cc")

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	// The homepage "Popular" grid is only the first latest updates, so the site has no real sort
	override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.UPDATED)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchTags(),
		availableStates = EnumSet.of(MangaState.FINISHED),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val tag = filter.tags.oneOrThrowIfMany()
		val isFinished = MangaState.FINISHED in filter.states
		val url = urlBuilder()
		when {
			!filter.query.isNullOrEmpty() -> url.addPathSegment("search").addQueryParameter("s", filter.query)
			tag != null -> url.addPathSegment("genre").addPathSegment(tag.key)
			isFinished -> url.addPathSegment("completed")
		}
		url.addQueryParameter("page", page.toString())
		val doc = webClient.httpGet(url.build()).parseHtml()
		// The homepage lists the latest updates; genre, completed and search pages share another card
		val isHome = filter.query.isNullOrEmpty() && tag == null && !isFinished
		return doc.select(if (isHome) "article.latest-card" else "article.popular-card").map { card ->
			val a = card.selectFirstOrThrow("h3 a")
			val href = a.attrAsRelativeUrl("href")
			Manga(
				id = generateUid(href),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				coverUrl = card.selectFirst("img")?.src(),
				title = a.text(),
				altTitles = emptySet(),
				rating = RATING_UNKNOWN,
				tags = emptySet(),
				authors = emptySet(),
				state = null,
				source = source,
				contentRating = null,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val facts = doc.select("div.series-facts dl > div").associate { row ->
			row.selectFirst("dt")?.text()?.trim()?.lowercase().orEmpty() to row.selectFirst("dd")
		}
		val title = doc.selectFirst("section.profile-manga h1")?.textOrNull() ?: manga.title
		val status = facts["status"]?.text()?.lowercase().orEmpty()
		return manga.copy(
			title = title,
			altTitles = facts["alternative"]?.text()?.split(", ")?.mapNotNullToSet { alt ->
				alt.trim().takeUnless { it.isEmpty() || it == title || it.equals("updating", ignoreCase = true) }
			}.orEmpty(),
			authors = facts["author(s)"]?.text()?.split(", ")?.mapNotNullToSet { author ->
				author.trim().takeUnless { it.isEmpty() || it.equals("updating", ignoreCase = true) }
			}.orEmpty(),
			// Titles imported from some sources have no summary
			description = doc.selectFirst("p.series-summary")?.html().orEmpty(),
			coverUrl = doc.selectFirst("div.series-cover-frame img")?.src() ?: manga.coverUrl,
			tags = facts["genre(s)"]?.select("a")?.mapToSet { a ->
				MangaTag(
					key = a.attr("href").removeSuffix("/").substringAfterLast('/'),
					title = a.text(),
					source = source,
				)
			}.orEmpty(),
			state = when {
				"ongoing" in status -> MangaState.ONGOING
				"completed" in status -> MangaState.FINISHED
				"hiatus" in status -> MangaState.PAUSED
				"cancel" in status || "dropped" in status -> MangaState.ABANDONED
				else -> null
			},
			// "Average 4.0 / 5 out of 491"
			rating = facts["rating"]?.text()?.let { ratingRegex.find(it) }?.groupValues?.get(1)
				?.toFloatOrNull()?.takeIf { it > 0f }?.div(5f) ?: RATING_UNKNOWN,
			chapters = getChapters(doc),
		)
	}

	// Chapters are paginated with ?chapterPage=N. The site lists the "chapter-N" chapters newest first,
	// then the unnumbered extras (side stories, notices) oldest first.
	private suspend fun getChapters(doc: Document): List<MangaChapter> {
		val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
			timeZone = TimeZone.getTimeZone("UTC")
		}
		val rows = ArrayList<Element>()
		val visited = HashSet<String>()
		var page = doc
		while (true) {
			rows += page.select("a.release-row")
			val next = page.selectFirst("nav.pagination a[aria-label=\"Next page\"]")
				?.attrAsAbsoluteUrlOrNull("href")
			if (next == null || !visited.add(next)) break
			page = webClient.httpGet(next).parseHtml()
		}
		val numbered = ArrayList<MangaChapter>(rows.size)
		val extras = ArrayList<MangaChapter>()
		for (row in rows.distinctBy { it.attr("href") }) {
			val href = row.attrAsRelativeUrl("href")
			val match = chapterNumberRegex.find(href)
			val chapter = MangaChapter(
				id = generateUid(href),
				title = row.selectFirst("strong")?.textOrNull(),
				number = match?.let { "${it.groupValues[1]}.${it.groupValues[2].ifEmpty { "0" }}".toFloatOrNull() } ?: 0f,
				volume = 0,
				url = href,
				scanlator = null,
				uploadDate = dateFormat.parseSafe(row.selectFirst("time")?.attr("datetime")),
				branch = null,
				source = source,
			)
			if (match != null) numbered += chapter else extras += chapter
		}
		return numbered.asReversed() + extras
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
		return doc.select("div.reader-pages figure.reader-page-image img").map { img ->
			val url = img.requireSrc()
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}

	private suspend fun fetchTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/genre").parseHtml()
		return doc.select(".genre-directory a").mapToSet { a ->
			MangaTag(
				key = a.attr("href").removeSuffix("/").substringAfterLast('/'),
				title = a.text(),
				source = source,
			)
		}
	}

	private companion object {

		val chapterNumberRegex = Regex("""/chapter-(\d+)(?:[.-](\d+))?/?$""")
		val ratingRegex = Regex("""([\d.]+)\s*/\s*5""")
	}
}
