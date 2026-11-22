package org.koitharu.kotatsu.parsers.site.id

import okhttp3.Headers
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy
import java.util.Calendar
import java.util.EnumSet
import java.util.LinkedHashSet
import java.util.Locale

@MangaSourceParser("MGKOMIK", "MgKomik", "id")
internal class Mgkomik(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.MGKOMIK, 20) {

	override val configKeyDomain = ConfigKey.Domain("web1.mgkomik.cc", "web2.mgkomik.cc")

	override val availableSortOrders = setOf(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.RATING,
		SortOrder.NEWEST,
		SortOrder.ALPHABETICAL,
		SortOrder.RELEVANCE,
	)

	override val filterCapabilities = MangaListFilterCapabilities(
		isSearchSupported = true,
		isMultipleTagsSupported = true,
		isSearchWithFiltersSupported = true,
	)

	private val availableTags = suspendLazy(soft = true, initializer = ::fetchTags)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = availableTags.get(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED, MangaState.PAUSED),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = if (filter.query.isNullOrBlank() && filter.tags.isEmpty() && filter.states.isEmpty() &&
			filter.types.isEmpty() && filter.contentRating.isEmpty()
		) {
			"https://$domain/komik/?filter=&order_by=${mapOrder(order, search = false)}&page=$page"
		} else {
			buildSearchUrl(page, order, filter)
		}
		val doc = webClient.httpGet(url).parseHtml()
		val cards = doc.select(".manga-grid .manga-card")
		if (cards.isEmpty()) {
			return emptyList()
		}
		return cards.mapNotNull { card ->
			val link = if (card.tagName().equals("a", ignoreCase = true)) {
				card
			} else {
				card.selectFirst("a[href]") ?: return@mapNotNull null
			}
			val href = link.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
			val title = card.selectFirst(".manga-title")?.textOrNull() ?: return@mapNotNull null
			Manga(
				id = generateUid(href),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				title = title,
				altTitles = emptySet(),
				coverUrl = card.selectFirst("img.manga-cover")?.attrAsAbsoluteUrlOrNull("src"),
				rating = RATING_UNKNOWN,
				contentRating = sourceContentRating,
				tags = emptySet(),
				state = parseState(card.selectFirst("span.manga-status-badge")?.textOrNull()),
				authors = emptySet(),
				source = source,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val info = doc.selectFirst("div.manga-info-col")
			?: throw ParseException("Cannot find manga info", doc.location())
		val chapterItems = doc.select("li.chapter-list-item")
		if (chapterItems.isEmpty()) {
			throw ParseException("Cannot find chapter list", doc.location())
		}
		val tags = info.select("div.genre-list a.genre-tag").mapNotNullTo(LinkedHashSet()) { tag ->
			val key = tag.attrAsRelativeUrlOrNull("href")?.substringAfter("filter=", "")?.takeUnless { it.isEmpty() }
				?: return@mapNotNullTo null
			val title = tag.textOrNull()?.trim() ?: return@mapNotNullTo null
			MangaTag(title = title, key = key, source = source)
		}
		val altTitles = doc.selectFirst("#altModalText")?.textOrNull()
			?.split(Regex("""\s*/\s*|,\s*"""))
			?.map { it.trim() }
			?.filter { it.isNotEmpty() }
			?.toSet()
			.orEmpty()
		val chapters = chapterItems.mapNotNull { item ->
			val href = item.selectFirst("a.chapter-link")?.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
			val number = item.attr("data-chapter-number").toFloatOrNull() ?: 0f
			MangaChapter(
				id = generateUid(href),
				url = href,
				title = null,
				number = number,
				volume = 0,
				uploadDate = parseRelativeDate(item.selectFirst("span.chapter-date")?.textOrNull()),
				scanlator = null,
				branch = null,
				source = source,
			)
		}.sortedBy { it.number }
		return manga.copy(
			title = info.selectFirst("h1.manga-title")?.textOrNull()?.trim() ?: manga.title,
			altTitles = altTitles,
			coverUrl = doc.selectFirst("img.manga-cover-large")?.attrAsAbsoluteUrlOrNull("src") ?: manga.coverUrl,
			description = doc.selectFirst("#sinopsisContainer")?.selectFirst("p")?.html(),
			tags = tags,
			state = parseState(info.selectFirst("span.status-badge")?.textOrNull()) ?: manga.state,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
		val content = doc.selectFirst("#readingContent")
			?: throw ParseException("Cannot find reader content", doc.location())
		val pages = content.select("img").mapNotNull { image ->
			val src = image.attrAsAbsoluteUrlOrNull("src") ?: return@mapNotNull null
			MangaPage(
				id = generateUid(src),
				url = src,
				preview = null,
				source = source,
			)
		}
		if (pages.isEmpty()) {
			throw ParseException("Cannot find chapter pages", doc.location())
		}
		return pages
	}

	override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
		.add("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
		.add("Referer", "https://$domain/")
		.build()

	private fun buildSearchUrl(page: Int, order: SortOrder, filter: MangaListFilter): String {
		val params = ArrayList<Pair<String, String>>()
		filter.query?.takeIf { it.isNotBlank() }?.let { params += "q" to it.trim() }
		filter.tags.forEach { params += "genres[]" to it.key }
		filter.types.forEach { type ->
			val value = when (type) {
				ContentType.MANGA -> "manga"
				ContentType.MANHWA -> "manhwa"
				ContentType.MANHUA -> "manhua"
				else -> null
			}
			if (value != null) {
				params += "types[]" to value
			}
		}
		filter.states.forEach { state ->
			val value = when (state) {
				MangaState.ONGOING -> "on-going"
				MangaState.FINISHED -> "end"
				MangaState.PAUSED -> "on-hold"
				else -> null
			}
			if (value != null) {
				params += "status" to value
			}
		}
		params += "order_by" to mapOrder(order, search = true)
		params += "page" to page.toString()
		return "https://$domain/search/?${params.joinToString("&") { (key, value) -> "$key=${value.urlEncoded()}" }}"
	}

	private suspend fun fetchTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/search/").parseHtml()
		val tags = doc.select("input.genre-checkbox").mapNotNullTo(LinkedHashSet()) { input ->
			val key = input.attr("value").takeUnless { it.isEmpty() } ?: return@mapNotNullTo null
			val title = input.nextElementSibling()?.textOrNull()?.trim() ?: return@mapNotNullTo null
			MangaTag(title = title, key = key, source = source)
		}
		if (tags.isEmpty()) {
			throw ParseException("Cannot find genre list", doc.location())
		}
		return tags
	}

	private fun mapOrder(order: SortOrder, search: Boolean): String = when (order) {
		SortOrder.UPDATED, SortOrder.UPDATED_ASC -> "latest"
		SortOrder.POPULARITY, SortOrder.POPULARITY_ASC -> if (search) "views" else "trending"
		SortOrder.RATING, SortOrder.RATING_ASC -> "rating"
		SortOrder.NEWEST, SortOrder.NEWEST_ASC -> "new"
		SortOrder.ALPHABETICAL, SortOrder.ALPHABETICAL_DESC -> "alphabet"
		SortOrder.RELEVANCE -> "relevance"
		else -> if (search) "relevance" else "latest"
	}

	private fun parseState(value: String?): MangaState? = when (value?.trim()?.lowercase(Locale.ROOT)) {
		"ongoing" -> MangaState.ONGOING
		"completed" -> MangaState.FINISHED
		"hiatus" -> MangaState.PAUSED
		else -> null
	}

	private fun parseRelativeDate(value: String?): Long {
		if (value.isNullOrBlank()) {
			return 0L
		}
		val text = value.trim().lowercase(Locale.ROOT)
		val amount = Regex("""(\d+)""").find(text)?.value?.toIntOrNull() ?: 0
		val calendar = Calendar.getInstance()
		return when {
			"second" in text -> calendar.apply { add(Calendar.SECOND, -amount) }.timeInMillis
			"minute" in text -> calendar.apply { add(Calendar.MINUTE, -amount) }.timeInMillis
			"hour" in text -> calendar.apply { add(Calendar.HOUR_OF_DAY, -amount) }.timeInMillis
			"week" in text -> calendar.apply { add(Calendar.WEEK_OF_YEAR, -amount) }.timeInMillis
			"day" in text -> calendar.apply { add(Calendar.DAY_OF_MONTH, -amount) }.timeInMillis
			"month" in text -> calendar.apply { add(Calendar.MONTH, -amount) }.timeInMillis
			"year" in text -> calendar.apply { add(Calendar.YEAR, -amount) }.timeInMillis
			"ago" in text -> calendar.timeInMillis
			else -> 0L
		}
	}
}
