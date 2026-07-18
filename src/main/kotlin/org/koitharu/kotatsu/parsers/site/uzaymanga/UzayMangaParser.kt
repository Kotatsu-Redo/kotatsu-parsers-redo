package org.koitharu.kotatsu.parsers.site.uzaymanga

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
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
import org.koitharu.kotatsu.parsers.util.attrAsAbsoluteUrlOrNull
import org.koitharu.kotatsu.parsers.util.attrAsRelativeUrl
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.mapChapters
import org.koitharu.kotatsu.parsers.util.mapToSet
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.oneOrThrowIfMany
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.parseRaw
import org.koitharu.kotatsu.parsers.util.parseSafe
import org.koitharu.kotatsu.parsers.util.textOrNull
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.toRelativeUrl
import org.koitharu.kotatsu.parsers.util.urlEncoded
import org.koitharu.kotatsu.parsers.util.json.mapJSONToSet
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

internal abstract class UzayMangaParser(
	context: MangaLoaderContext,
	source: MangaParserSource,
	domain: String,
	private val cdnUrl: String? = null,
) : PagedMangaParser(context, source, 24) {

	override val configKeyDomain = ConfigKey.Domain(domain)

	private val tagMutex = Mutex()
	private var cachedTags: Set<MangaTag>? = null

	private val chapterDateFormat = SimpleDateFormat("MMM d ,yyyy", Locale("tr"))

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override val defaultSortOrder: SortOrder
		get() = SortOrder.POPULARITY

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.ALPHABETICAL,
		SortOrder.ALPHABETICAL_DESC,
		SortOrder.NEWEST,
		SortOrder.POPULARITY,
		SortOrder.UPDATED,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isMultipleTagsSupported = true,
			isSearchWithFiltersSupported = true,
		)

	override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions(
		availableTags = getOrCreateTags(),
		availableStates = EnumSet.of(
			MangaState.ONGOING,
			MangaState.FINISHED,
			MangaState.ABANDONED,
			MangaState.PAUSED,
		),
		availableContentTypes = EnumSet.of(
			ContentType.MANGA,
			ContentType.MANHWA,
			ContentType.MANHUA,
			ContentType.COMICS,
		),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		if (page > 1 && !filter.query.isNullOrBlank() && filter.tags.isEmpty() && filter.states.isEmpty() && filter.types.isEmpty()) {
			return emptyList()
		}

		val query = filter.query?.trim().orEmpty()
		return when {
			query.startsWith(URL_SEARCH_PREFIX, ignoreCase = true) -> {
				if (page != 1) emptyList() else searchBySlug(query.substringAfter(URL_SEARCH_PREFIX))
			}

			query.isNotEmpty() && filter.tags.isEmpty() && filter.states.isEmpty() && filter.types.isEmpty() -> {
				searchByApi(query)
			}

			order == SortOrder.UPDATED && query.isEmpty() && filter.tags.isEmpty() && filter.states.isEmpty() && filter.types.isEmpty() -> {
				getLatestPage(page)
			}

			else -> getDirectoryPage(page, order, filter)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = loadSiteDocument(manga.url.toAbsoluteUrl(domain))
		val content = doc.getElementById("content") ?: doc

		return manga.copy(
			title = content.selectFirst("h1")?.textOrNull() ?: manga.title,
			coverUrl = content.selectFirst("img")?.attrAsAbsoluteUrlOrNull("src") ?: manga.coverUrl,
			description = content.selectFirst("div.grid h2 + p")?.textOrNull(),
			tags = content.select("a[href*='search?categories=']").mapToSet(::parseTag),
			state = parseStatus(content.selectFirst("span:contains(Durum) + span")?.textOrNull()),
			chapters = doc.select("div.list-episode a").mapChapters(reversed = true) { index, element ->
				parseChapter(index, element)
			},
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = loadSiteDocument(chapter.url.toAbsoluteUrl(domain))
		val script = doc.select("script")
			.asSequence()
			.map(Element::html)
			.firstOrNull { PAGE_REGEX.containsMatchIn(it) }
			?: return emptyList()

		val prefix = cdnUrl?.trimEnd('/')
		return PAGE_REGEX.findAll(script).mapNotNull { match ->
			val path = match.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
			val imageUrl = when {
				path.startsWith("http://") || path.startsWith("https://") -> path
				prefix != null -> "$prefix/${path.removePrefix("/")}"
				else -> "https://$domain/${path.removePrefix("/")}"
			}
			MangaPage(
				id = generateUid(imageUrl),
				url = imageUrl,
				preview = null,
				source = source,
			)
		}.toList()
	}

	private suspend fun getDirectoryPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://")
			append(domain)
			append("/search?page=")
			append(page)
			append("&search=")
			append(filter.query?.urlEncoded().orEmpty())

			if (filter.tags.isNotEmpty()) {
				append("&categories=")
				filter.tags.joinTo(this, ",") { it.key }
			}

			filter.states.oneOrThrowIfMany()?.let {
				append("&publicStatus=")
				append(
					when (it) {
						MangaState.ONGOING -> "1"
						MangaState.FINISHED -> "2"
						MangaState.ABANDONED -> "3"
						MangaState.PAUSED -> "4"
						else -> ""
					},
				)
			}

			filter.types.oneOrThrowIfMany()?.let {
				append("&country=")
				append(
					when (it) {
						ContentType.MANHUA -> "1"
						ContentType.MANHWA -> "2"
						ContentType.MANGA -> "3"
						ContentType.COMICS -> "4"
						else -> ""
					},
				)
			}

			append("&order=")
			append(
				when (order) {
					SortOrder.ALPHABETICAL -> "1"
					SortOrder.ALPHABETICAL_DESC -> "2"
					SortOrder.NEWEST -> "3"
					SortOrder.POPULARITY -> "4"
					SortOrder.UPDATED -> "5"
					else -> "1"
				},
			)
		}

		val doc = loadSiteDocument(url)
		return extractDirectoryCards(doc)
			.mapNotNull(::parseSearchCard)
			.distinctBy { it.url }
	}

	private suspend fun getLatestPage(page: Int): List<Manga> {
		val doc = loadSiteDocument("https://$domain/?page=$page")
		val header = doc.selectFirst("div.header:has(h2:contains(En Son Yüklenen))")
		val grid = header?.nextElementSibling()
			?: doc.selectFirst("div.grid.grid-cols-1")
			?: doc.selectFirst("div.grid")

		val latest = grid?.select("> div")?.mapNotNull(::parseLatestCard).orEmpty()
		return if (latest.isNotEmpty()) latest else getDirectoryPage(
			page = page,
			order = SortOrder.UPDATED,
			filter = MangaListFilter(),
		)
	}

	private suspend fun searchBySlug(slug: String): List<Manga> {
		val normalizedSlug = slug.trim().removePrefix("/").nullIfEmpty() ?: return emptyList()
		val doc = loadSiteDocument("https://$domain/manga/$normalizedSlug")
		if (!isMangaPage(doc)) {
			return emptyList()
		}
		return listOf(parseMangaStub(doc))
	}

	private suspend fun searchByApi(query: String): List<Manga> {
		val apiUrl = "https://$domain/api/series/search/navbar?search=${query.urlEncoded()}"
		val raw = fetchApiRaw(apiUrl).trim()
		if (raw.isEmpty() || raw == "[]") {
			return emptyList()
		}

		val json = JSONArray(raw)
		val imageBase = (cdnUrl ?: "https://$domain").trimEnd('/')
		return List(json.length()) { index -> json.getJSONObject(index) }.mapNotNull { item ->
			val id = item.optString("id").nullIfEmpty() ?: return@mapNotNull null
			val title = item.optString("name").nullIfEmpty() ?: return@mapNotNull null
			val image = item.optString("image")
			val relativeUrl = "/manga/$id/${slugify(title)}"
			Manga(
				id = generateUid(relativeUrl),
				title = title,
				altTitles = emptySet(),
				url = relativeUrl,
				publicUrl = relativeUrl.toAbsoluteUrl(domain),
				rating = RATING_UNKNOWN,
				contentRating = null,
				coverUrl = when {
					image.startsWith("http://") || image.startsWith("https://") -> image
					image.isBlank() -> null
					else -> "$imageBase/${image.removePrefix("/")}"
				},
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				source = source,
			)
		}
	}

	private fun parseSearchCard(card: Element): Manga? {
		val link = when {
			card.normalName() == "a" && card.hasAttr("href") -> card
			else -> card.selectFirst("a[href*='/manga/']") ?: card.selectFirst("a")
		} ?: return null
		val href = link.attrAsRelativeUrl("href")
		val title = card.selectFirst("h2")?.textOrNull()
			?: link.selectFirst("h2")?.textOrNull()
			?: link.attr("title").nullIfEmpty()
			?: return null
		return Manga(
			id = generateUid(href),
			title = title,
			altTitles = emptySet(),
			url = href,
			publicUrl = href.toAbsoluteUrl(domain),
			rating = RATING_UNKNOWN,
			contentRating = null,
			coverUrl = card.selectFirst("img")?.attrAsAbsoluteUrlOrNull("src")
				?: link.selectFirst("img")?.attrAsAbsoluteUrlOrNull("src"),
			tags = emptySet(),
			state = null,
			authors = emptySet(),
			source = source,
		)
	}

	private fun extractDirectoryCards(doc: Document): List<Element> {
		val section = doc.selectFirst("section[aria-label='series area']") ?: return emptyList()
		val cards = section.select(".card").ifEmpty {
			section.select("a[href*='/manga/']").filter { element ->
				element.selectFirst("h2, img") != null
			}
		}
		return cards.filterNot { element ->
			val href = when {
				element.normalName() == "a" -> element.attr("href")
				else -> element.selectFirst("a")?.attr("href").orEmpty()
			}
			href.contains("/chapter/") || href.contains("/bolum/")
		}
	}

	private fun parseLatestCard(card: Element): Manga? {
		val mangaLink = card.selectFirst("h2")?.parent()
			?: card.selectFirst("a[href*='/manga/']")
			?: return null
		val href = mangaLink.attrAsRelativeUrl("href")
		val title = card.selectFirst("h2")?.textOrNull() ?: return null
		return Manga(
			id = generateUid(href),
			title = title,
			altTitles = emptySet(),
			url = href,
			publicUrl = href.toAbsoluteUrl(domain),
			rating = RATING_UNKNOWN,
			contentRating = null,
			coverUrl = card.selectFirst(".card-image img")?.attrAsAbsoluteUrlOrNull("src")
				?: card.selectFirst("img")?.attrAsAbsoluteUrlOrNull("src"),
			tags = emptySet(),
			state = null,
			authors = emptySet(),
			source = source,
		)
	}

	private fun parseMangaStub(doc: Document): Manga {
		val relativeUrl = doc.location().toRelativeUrl(domain)
		val content = doc.getElementById("content") ?: doc
		return Manga(
			id = generateUid(relativeUrl),
			title = content.selectFirst("h1")?.textOrNull().orEmpty(),
			altTitles = emptySet(),
			url = relativeUrl,
			publicUrl = doc.location(),
			rating = RATING_UNKNOWN,
			contentRating = null,
			coverUrl = content.selectFirst("img")?.attrAsAbsoluteUrlOrNull("src"),
			tags = emptySet(),
			state = parseStatus(content.selectFirst("span:contains(Durum) + span")?.textOrNull()),
			authors = emptySet(),
			source = source,
		)
	}

	private fun parseChapter(index: Int, element: Element): MangaChapter {
		val href = element.attrAsRelativeUrl("href")
		val title = element.selectFirst("h3")?.textOrNull()
			?: element.textOrNull()
			?: "Chapter ${index + 1}"
		return MangaChapter(
			id = generateUid(href),
			title = title,
			number = CHAPTER_NUMBER_REGEX.find(title)?.groupValues?.getOrNull(1)?.toFloatOrNull() ?: (index + 1).toFloat(),
			volume = 0,
			url = href,
			scanlator = null,
			uploadDate = chapterDateFormat.parseSafe(element.selectFirst("span")?.text()),
			branch = null,
			source = source,
		)
	}

	private fun parseStatus(statusText: String?): MangaState? {
		val value = statusText?.trim()?.lowercase(Locale("tr")) ?: return null
		return when {
			value.contains("devam ediyor") -> MangaState.ONGOING
			value.contains("tamamlandi") || value.contains("tamamlandı") -> MangaState.FINISHED
			value.contains("birakildi") || value.contains("bırakıldı") -> MangaState.ABANDONED
			value.contains("ara veridi") || value.contains("ara verildi") -> MangaState.PAUSED
			else -> null
		}
	}

	private fun parseTag(element: Element): MangaTag {
		val href = element.attr("href")
		val key = href.substringAfter("?categories=").substringBefore('&').ifEmpty { href }
		return MangaTag(
			key = key,
			title = element.text(),
			source = source,
		)
	}

	private suspend fun getOrCreateTags(): Set<MangaTag> = tagMutex.withLock {
		cachedTags ?: fetchTags().also { cachedTags = it }
	}

	private suspend fun fetchTags(): Set<MangaTag> {
		val doc = loadSiteDocument("https://$domain/search")
		val script = doc.select("script")
			.firstOrNull { it.html().contains("\"category\":[") }
			?.html()
			?: return emptySet()

		val jsonStr = script.substringAfter("\"category\":[")
			.substringBefore("],\"searchParams\":{}")
			.takeIf { it.isNotBlank() }
			?: return emptySet()

		return JSONArray("[$jsonStr]".replace("\\", "")).mapJSONToSet { json ->
			MangaTag(
				key = json.optString("id"),
				title = json.optString("name"),
				source = source,
			)
		}
	}

	private fun isMangaPage(doc: Document): Boolean {
		return doc.selectFirst("div.list-episode, div.grid h2 + p, #content h1") != null
	}

	private fun slugify(title: String): String {
		return title.lowercase(Locale("tr"))
			.replace("ı", "i")
			.replace("ğ", "g")
			.replace("ü", "u")
			.replace("ş", "s")
			.replace("ö", "o")
			.replace("ç", "c")
			.replace(Regex("[^a-z0-9\\s]"), "")
			.trim()
			.replace(Regex("\\s+"), "-")
	}

	private fun siteHeaders() = getRequestHeaders().newBuilder()
		.set("Referer", "https://$domain/")
		.build()

	private suspend fun loadSiteDocument(url: String): Document =
		webClient.httpGet(url, extraHeaders = siteHeaders()).parseHtml()

	private suspend fun fetchApiRaw(url: String): String =
		webClient.httpGet(url = url, extraHeaders = siteHeaders()).parseRaw()

	private companion object {
		private const val URL_SEARCH_PREFIX = "slug:"
		private val PAGE_REGEX = Regex("""\\"path\\":\\"([^"]+)\\""")
		private val CHAPTER_NUMBER_REGEX = Regex("""(\d+(?:\.\d+)?)""")
	}
}
