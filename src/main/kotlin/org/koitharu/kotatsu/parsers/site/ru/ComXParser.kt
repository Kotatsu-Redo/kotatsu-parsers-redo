package org.koitharu.kotatsu.parsers.site.ru

import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.getFloatOrDefault
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.suspendlazy.getOrNull
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("COMX", "Com-X", "ru", ContentType.COMICS)
internal class ComXParser(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.COMX, 20) {

	override val configKeyDomain = ConfigKey.Domain("com-x.life", "ru.com-x.life")

	private val filterData = suspendLazy(initializer = ::fetchFilterData)
	private val guardLock = Any()

	@Volatile
	private var lastGuardSolveAt = 0L

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	init {
		context.cookieJar.insertCookies(domain, "adt-accepted=1; Path=/")
	}

	override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
		.set("Sec-Fetch-Dest", "document")
		.set("Sec-Fetch-Mode", "navigate")
		.set("Sec-Fetch-Site", "none")
		.set("Sec-Fetch-User", "?1")
		.build()

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		val response = chain.proceed(request)
		if (response.request.url.pathSegments.firstOrNull() != DLE_GUARD_PATH) {
			return response
		}

		response.close()
		val solveUrl = if (request.method == "GET") request.url.toString() else "https://$domain/"
		if (!solveDleGuard(solveUrl)) {
			throw IOException("Failed to bypass Com-X site protection automatically")
		}
		return chain.proceed(request)
	}

	private fun solveDleGuard(url: String): Boolean = synchronized(guardLock) {
		if (
			System.currentTimeMillis() - lastGuardSolveAt < GUARD_TRUST_WINDOW_MS &&
			context.cookieJar.getCookies(domain).any { it.name == DLE_TRUST_COOKIE }
		) {
			return@synchronized true
		}

		val result = runCatching {
			runBlocking {
				context.evaluateJs(url, DLE_GUARD_SCRIPT, GUARD_TIMEOUT_MS)
			}
		}.getOrNull()?.decodeWebViewString() ?: return@synchronized false

		val trustCookie = result.split(';')
			.firstOrNull { it.trim().startsWith("$DLE_TRUST_COOKIE=") }
			?.trim()
			?: return@synchronized false

		context.cookieJar.insertCookies(domain, "$trustCookie; Path=/")
		lastGuardSolveAt = System.currentTimeMillis()
		true
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.UPDATED_ASC,
		SortOrder.POPULARITY,
		SortOrder.POPULARITY_ASC,
		SortOrder.RATING,
		SortOrder.RATING_ASC,
		SortOrder.NEWEST,
		SortOrder.NEWEST_ASC,
		SortOrder.ALPHABETICAL,
		SortOrder.ALPHABETICAL_DESC,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = true,
			isYearRangeSupported = true,
		)

	override suspend fun getFilterOptions(): MangaListFilterOptions {
		val data = filterData.get()
		return MangaListFilterOptions(
			availableTags = data.tags,
			availableStates = data.statusIds.keys,
		)
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val doc = if (!filter.query.isNullOrBlank()) {
			val query = filter.query.splitByWhitespace().joinToString("%20") { it.urlEncoded() }
			val url = buildString {
				append("/search/").append(query)
				if (page > 1) append("/page/").append(page)
				append('/')
			}.toAbsoluteUrl(domain)
			webClient.httpGet(url, getRequestHeaders()).parseHtml()
		} else {
			val (sortBy, direction) = order.toSiteSort()
			val hasFilters = filter.hasNonSearchOptions()
			val url = if (hasFilters) buildFilteredCatalogUrl(page, filter) else buildCatalogUrl(page)
			val form = if (hasFilters) {
				mapOf(
					"dlenewssortby" to sortBy,
					"dledirection" to direction,
					"set_new_sort" to "dle_sort_xfilter",
					"set_direction_sort" to "dle_direction_xfilter",
				)
			} else {
				mapOf(
					"dlenewssortby" to sortBy,
					"dledirection" to direction,
					"set_new_sort" to "dle_sort_cat_1",
					"set_direction_sort" to "dle_direction_cat_1",
				)
			}
			webClient.httpPost(url.toHttpUrl(), form, getRequestHeaders()).parseHtml()
		}

		return doc.select("#dle-content > .readed").mapNotNull { item ->
			val titleAnchor = item.selectFirst(".readed__title > a") ?: return@mapNotNull null
			val href = titleAnchor.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
			val titleParts = titleAnchor.ownText()
				.split(Regex("""\s+/\s+"""))
				.map(String::trim)
				.filter(String::isNotEmpty)
			val title = titleParts.lastOrNull() ?: return@mapNotNull null
			Manga(
				id = generateUid(href),
				url = href,
				publicUrl = titleAnchor.attrAsAbsoluteUrl("href"),
				title = title,
				altTitles = titleParts.dropLast(1).toSet(),
				authors = emptySet(),
				description = null,
				tags = emptySet(),
				rating = RATING_UNKNOWN,
				state = null,
				coverUrl = item.selectFirst("img")?.src(),
				contentRating = sourceContentRating,
				source = source,
			)
		}
	}

	private fun buildCatalogUrl(page: Int): String = buildString {
		append("https://")
		append(domain)
		append("/comix-read/")
		if (page > 1) append("page/").append(page).append('/')
	}

	private suspend fun buildFilteredCatalogUrl(page: Int, filter: MangaListFilter): String {
		val currentYear = Calendar.getInstance().get(Calendar.YEAR)
		val yearFrom = filter.yearFrom.takeIf { it in MIN_YEAR..currentYear } ?: MIN_YEAR
		val yearTo = filter.yearTo.takeIf { it in MIN_YEAR..currentYear } ?: currentYear
		val included = filter.tags.groupBySiteFilter()
		val excluded = filter.tagsExclude.groupBySiteFilter()
		val statusIds = filterData.getOrNull()?.statusIds

		return buildString {
			append("https://").append(domain).append("/ComicList")
			for (key in FILTER_KEYS) {
				included[key]?.takeIf { it.isNotEmpty() }?.let {
					append('/').append(key).append('=').append(it.joinToString(","))
				}
				excluded[key]?.takeIf { it.isNotEmpty() }?.let {
					append("/exc_").append(key).append('=').append(it.joinToString(","))
				}
			}
			filter.states.mapNotNull { statusIds?.get(it) }.takeIf { it.isNotEmpty() }?.let {
				append("/st=").append(it.joinToString(","))
			}
			append("/y[from]=").append(yearFrom)
			append("/y[to]=").append(yearTo)
			if (page > 1) append("/page/").append(page)
			append('/')
		}
	}

	private fun Set<MangaTag>.groupBySiteFilter(): Map<String, List<String>> = mapNotNull { tag ->
		val separator = tag.key.indexOf(':')
		if (separator > 0) {
			tag.key.substring(0, separator) to tag.key.substring(separator + 1)
		} else {
			"g" to tag.key
		}
	}.groupBy({ it.first }, { it.second })

	private fun SortOrder.toSiteSort(): Pair<String, String> = when (this) {
		SortOrder.UPDATED -> "editdate" to "desc"
		SortOrder.UPDATED_ASC -> "editdate" to "asc"
		SortOrder.NEWEST -> "date" to "desc"
		SortOrder.NEWEST_ASC -> "date" to "asc"
		SortOrder.ALPHABETICAL -> "ltitle" to "asc"
		SortOrder.ALPHABETICAL_DESC -> "ltitle" to "desc"
		SortOrder.POPULARITY_ASC, SortOrder.RATING_ASC -> "rating" to "asc"
		else -> "rating" to "desc"
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain), getRequestHeaders()).parseHtml()
		if (doc.selectFirst(".message-info__content:contains(не имеют доступа)") != null) {
			throw ParseException("Авторизируйтесь для просмотра контента", manga.url)
		}

		val data = doc.windowJson("window.__DATA__", manga.url)
		val chapters = parseChapters(data, manga.url)
		val author = doc.pageListItem("Автор")
		val artist = doc.pageListItem("Художник")
		val originalTitle = doc.selectFirst(".page__title-original")?.textOrNull()
		val cover = doc.selectFirst("div.page__poster img")?.src()
		val siteRating = doc.selectFirst(".page__activity-votes")?.ownText()?.trim()?.toFloatOrNull()
			?.div(5f)?.coerceIn(0f, 1f)
		val allTags = filterData.getOrNull()?.tags
		val tags = doc.select("div.page__tags a").mapNotNullToSet { anchor ->
			val name = anchor.text().trim()
			allTags?.firstOrNull { tag ->
				!tag.key.contains(':') && tag.title.equals(name, ignoreCase = true)
			}
		}
		return manga.copy(
			title = doc.selectFirst("header.page__header h1")?.textOrNull() ?: manga.title,
			altTitles = manga.altTitles + setOfNotNull(originalTitle),
			authors = setOfNotNull(author, artist),
			state = parseState(doc.pageListItem("Статус")),
			chapters = chapters,
			description = doc.selectFirst("div.page__text")?.textOrNull(),
			tags = tags.ifEmpty { manga.tags },
			rating = siteRating ?: manga.rating,
			coverUrl = cover ?: manga.coverUrl,
			largeCoverUrl = cover ?: manga.largeCoverUrl,
		)
	}

	private fun parseChapters(data: JSONObject, url: String): List<MangaChapter> {
		val comicId = data.optLong("news_id", -1L).takeIf { it >= 0L }
			?: throw ParseException("Comic id not found", url)
		val array = data.optJSONArray("chapters") ?: return emptyList()
		val dateFormat = SimpleDateFormat("d.M.yyyy", Locale.ROOT)
		var counter = 0f
		var firstChapter = true
		val result = ArrayList<MangaChapter>(array.length())

		for (index in array.length() - 1 downTo 0) {
			val chapter = array.getJSONObject(index)
			val chapterId = chapter.getLong("id")
			val title = chapter.getStringOrNull("title")?.let {
				CHAPTER_TITLE_PREFIX_REGEX.replace(WHITESPACES_REGEX.replace(it, " ").trim(), "").trim()
			}
			val siteNumber = chapter.getFloatOrDefault(
				"number",
				chapter.getFloatOrDefault("posi", 0f),
			)
			val matchNumber = title?.let { CHAPTER_NUMBER_REGEX.find(it)?.groupValues?.get(1)?.toFloatOrNull() }
			val anyNumber = title?.let { CHAPTER_ANY_NUMBER_REGEX.find(it)?.groupValues?.get(1)?.toFloatOrNull() }
			val number = if (firstChapter) {
				firstChapter = false
				siteNumber
			} else if (siteNumber != 0f) {
				when {
					matchNumber != null && matchNumber - counter in 0f..1f -> matchNumber
					isExtraChapter(title) || (title != null && NO_INFO_EXTRA_REGEX.containsMatchIn(title)) -> counter + 0.1f
					anyNumber != null && anyNumber - counter in 0f..1f -> anyNumber
					else -> siteNumber
				}
			} else {
				if (anyNumber != null && anyNumber - counter in 0f..1f) anyNumber else counter + 0.1f
			}

			result += MangaChapter(
				id = generateUid("$comicId/$chapterId"),
				url = "/reader/$comicId/$chapterId",
				number = number,
				title = title,
				uploadDate = dateFormat.parseSafe(chapter.getStringOrNull("date")),
				source = source,
				scanlator = null,
				branch = null,
				volume = chapter.optInt("volume", 0),
			)
			counter = number
		}
		return result
	}

	private fun isExtraChapter(title: String?): Boolean {
		val lower = title?.lowercase(Locale.ROOT) ?: return false
		return EXTRA_CHAPTER_WORDS.any(lower::contains)
	}

	private fun Document.pageListItem(label: String): String? =
		selectFirst(".page__list > li:has(> div:contains($label))")?.let { element ->
			element.selectFirst("a")?.text() ?: element.ownText()
		}?.trim()?.nullIfEmpty()

	private fun parseState(value: String?): MangaState? {
		val status = value?.lowercase(Locale.ROOT) ?: return null
		return when {
			"заморожен" in status || "приостановлен" in status -> MangaState.PAUSED
			"заверш" in status || "лимитка" in status || "ван шот" in status ||
				"графический роман" in status -> MangaState.FINISHED
			"продолжается" in status || " из " in status || "онгоинг" in status -> MangaState.ONGOING
			else -> null
		}
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val comicId = chapter.url.substringAfter("/reader/").substringBefore('/')
		context.cookieJar.insertCookies(domain, "adult=$comicId; Path=/")
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain), getRequestHeaders()).parseHtml()
		if (doc.html().contains("Выпуск был удален по требованию правообладателя")) {
			throw ParseException("Выпуск удалён по требованию правообладателя", chapter.url)
		}

		val data = doc.windowJson("window.__DATA__", chapter.url)
		val images = data.optJSONArray("images")
			?: throw ParseException("Image data not found", chapter.url)
		val preferredHostKey = if (domain.startsWith("ru.")) "host_ru" else "host"
		val fallbackHostKey = if (preferredHostKey == "host") "host_ru" else "host"
		val imageHost = data.optString(preferredHostKey)
			.ifBlank { data.optString(fallbackHostKey) }
			.ifBlank { throw ParseException("Image host not found", chapter.url) }
		val imageBase = imageHost.toImageBaseUrl()

		return List(images.length()) { index ->
			val path = images.getString(index)
			val imageUrl = if (path.startsWith("http://") || path.startsWith("https://")) {
				path
			} else {
				imageBase + path.trimStart('/')
			}
			MangaPage(
				id = generateUid("${chapter.id}-$index"),
				url = imageUrl,
				preview = null,
				source = source,
			)
		}
	}

	private fun String.toImageBaseUrl(): String {
		val hostUrl = if (startsWith("http://") || startsWith("https://")) this else "https://$this"
		val normalized = hostUrl.trimEnd('/')
		return if (normalized.endsWith("/comix")) "$normalized/" else "$normalized/comix/"
	}

	private suspend fun fetchFilterData(): SiteFilterData {
		val url = "https://$domain/comix-read/"
		val doc = webClient.httpGet(url, getRequestHeaders()).parseHtml()
		val root = doc.windowJson("window.__XFILTER__", url)
		val filterItems = root.optJSONObject("filter_items")
			?: throw ParseException("Filter data not found", url)
		val tags = LinkedHashSet<MangaTag>()
		filterItems.filterValues("g").forEach { (id, title) ->
			tags += MangaTag(key = id, title = title.toTitleCase(sourceLocale), source = source)
		}
		filterItems.filterValues("p.cat").forEach { (id, title) ->
			tags += MangaTag(key = "p.cat:$id", title = "Раздел: $title", source = source)
		}
		filterItems.filterValues("t").forEach { (id, title) ->
			tags += MangaTag(key = "t:$id", title = "Тип выпуска: $title", source = source)
		}
		val statusIds = buildMap {
			filterItems.filterValues("st").forEach { (id, title) ->
				parseState(title)?.let { put(it, id) }
			}
		}
		return SiteFilterData(tags, statusIds)
	}

	private fun JSONObject.filterValues(key: String): List<Pair<String, String>> {
		val values = optJSONObject(key)?.optJSONArray("values") ?: return emptyList()
		return List(values.length()) { index ->
			val item = values.getJSONObject(index)
			item.get("id").toString() to item.getString("value")
		}
	}

	private fun Document.windowJson(variable: String, url: String): JSONObject {
		val script = selectFirst("script:containsData($variable)")?.data()
			?: throw ParseException("$variable data not found", url)
		val assignment = script.indexOf(variable)
		val start = script.indexOf('{', assignment)
		if (assignment < 0 || start < 0) throw ParseException("$variable data not found", url)
		var depth = 0
		var inString = false
		var escaped = false
		for (index in start until script.length) {
			val char = script[index]
			when {
				escaped -> escaped = false
				char == '\\' && inString -> escaped = true
				char == '"' -> inString = !inString
				!inString && char == '{' -> depth++
				!inString && char == '}' -> {
					depth--
					if (depth == 0) return JSONObject(script.substring(start, index + 1))
				}
			}
		}
		throw ParseException("Invalid $variable data", url)
	}

	private fun String.decodeWebViewString(): String {
		if (length < 2 || first() != '"' || last() != '"') return this
		return runCatching { JSONObject("""{"value":$this}""").getString("value") }
			.getOrDefault(removeSurrounding("\""))
	}

	private data class SiteFilterData(
		val tags: Set<MangaTag>,
		val statusIds: Map<MangaState, String>,
	)

	companion object {
		private const val MIN_YEAR = 1980
		private const val DLE_GUARD_PATH = "_c"
		private const val DLE_TRUST_COOKIE = "__guard_trust"
		private const val GUARD_TIMEOUT_MS = 30_000L
		private const val GUARD_TRUST_WINDOW_MS = 5_000L
		private val FILTER_KEYS = arrayOf("p.cat", "g", "t")
		private val CHAPTER_NUMBER_REGEX = Regex(
			"""(?:\d+\s*-|.*?Глава)\s*([\d.]+)""",
			RegexOption.IGNORE_CASE,
		)
		private val CHAPTER_ANY_NUMBER_REGEX = Regex("""([\d.]+)""")
		private val CHAPTER_TITLE_PREFIX_REGEX = Regex("""^1\s*-\s*""")
		private val NO_INFO_EXTRA_REGEX = Regex("""#\s?1(?!\d|\.\d)""")
		private val WHITESPACES_REGEX = Regex("""\s{2,}""")
		private val EXTRA_CHAPTER_WORDS = arrayOf(
			"экстра",
			"ежегодник",
			"вернулся",
			"extra",
			"special",
			"annual",
			"bonus",
		)
		private val DLE_GUARD_SCRIPT = """
			(() => {
				const cookies = document.cookie || "";
				return cookies
					.split(';')
					.some(cookie => cookie.trim().startsWith('__guard_trust='))
					? cookies
					: null;
			})();
		""".trimIndent()
	}
}
