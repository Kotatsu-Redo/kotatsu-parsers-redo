package org.koitharu.kotatsu.parsers.site.fmreader.es

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.ContentRating
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
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.parseSafe
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale
import java.util.TimeZone

@MangaSourceParser("OLIMPOSCANS", "OlimpoScans", "es")
internal class OlimpoScans(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.OLIMPOSCANS, 24, 100) {

	override val configKeyDomain = ConfigKey.Domain("olympusbiblioteca.com")

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
		.set("Referer", "https://$domain/")
		.set("Origin", "https://$domain")
		.build()

	override val defaultSortOrder: SortOrder
		get() = SortOrder.UPDATED

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.POPULARITY,
		SortOrder.UPDATED,
		SortOrder.ALPHABETICAL,
		SortOrder.RELEVANCE,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isMultipleTagsSupported = true,
		)

	override suspend fun getFilterOptions(): MangaListFilterOptions {
		val filters = fetchFilters()
		return MangaListFilterOptions(
			availableTags = filters.first,
			availableStates = filters.second.keys,
		)
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		if (!filter.query.isNullOrBlank()) {
			return search(page, filter.query.orEmpty())
		}
		if (filter.isEmpty()) {
			when (order) {
				SortOrder.POPULARITY -> return getPopularPage(page)
				SortOrder.UPDATED -> return getLatestPage(page)
				else -> {}
			}
		}
		return browse(page, order, filter)
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val slug = manga.url.substringAfterLast('/')
		val root = webClient.httpGet(detailsUrl(slug), getRequestHeaders()).parseJson()
		val data = root.optJSONObject("data") ?: return manga
		return manga.copy(
			title = data.optString("name").ifBlank { manga.title },
			url = buildMangaUrl(slug),
			publicUrl = publicMangaUrl(slug),
			coverUrl = data.optString("cover").nullIfEmpty() ?: manga.coverUrl,
			description = data.optString("summary").nullIfEmpty(),
			tags = parseGenres(data.optJSONArray("genres")),
			state = parseState(data.optJSONObject("status")?.optString("name")),
			authors = parseAuthors(data.opt("team")),
			rating = parseRating(data),
			contentRating = ContentRating.SAFE,
			chapters = fetchChapters(slug),
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val slug = chapter.url.substringAfter("/chapter/").substringBeforeLast("/")
		val chapterId = chapter.url.substringAfterLast("/")
		val root = webClient.httpGet(chapterPagesUrl(slug, chapterId), getRequestHeaders()).parseJson()
		val pages = root.optJSONObject("chapter")?.optJSONArray("pages") ?: return emptyList()
		return List(pages.length()) { index ->
			val imageUrl = pages.optString(index)
			MangaPage(
				id = generateUid("$chapterId-$index"),
				url = imageUrl,
				preview = null,
				source = source,
			)
		}
	}

	private suspend fun search(page: Int, query: String): List<Manga> {
		val items = fetchSeriesList()
			.filter { it.optString("name").contains(query, ignoreCase = true) }
		return items
			.drop((page - 1) * pageSize)
			.take(pageSize)
			.mapNotNull(::parseSeries)
	}

	private suspend fun browse(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = apiUrl("api/series").newBuilder()
			.addQueryParameter("type", "comic")
			.addQueryParameter("page", page.toString())

		if (order == SortOrder.ALPHABETICAL) {
			url.addQueryParameter("direction", "asc")
		}

		if (filter.tags.isNotEmpty()) {
			url.addQueryParameter("genres", filter.tags.joinToString(",") { it.key })
		}

		filter.states.firstOrNull()?.let { state ->
			fetchFilters().second[state]?.let { statusId ->
				url.addQueryParameter("status", statusId)
			}
		}

		val root = webClient.httpGet(url.build(), getRequestHeaders()).parseJson()
		val items = root.optJSONObject("data")
			?.optJSONObject("series")
			?.optJSONArray("data")
			?: JSONArray()
		return parseSeriesList(items)
	}

	private suspend fun getPopularPage(page: Int): List<Manga> {
		val url = siteUrl("api/rankings").newBuilder()
			.addQueryParameter("page", page.toString())
			.addQueryParameter("period", "total_ranking")
			.build()
		val root = webClient.httpGet(url, getRequestHeaders()).parseJson()
		val items = root.optJSONArray("data") ?: JSONArray()
		return parseSeriesList(items)
	}

	private suspend fun getLatestPage(page: Int): List<Manga> {
		val url = siteUrl("api/new-chapters").newBuilder()
			.addQueryParameter("page", page.toString())
			.build()
		val root = webClient.httpGet(url, getRequestHeaders()).parseJson()
		val items = root.optJSONArray("data") ?: JSONArray()
		return parseSeriesList(items).distinctBy { it.url }
	}

	private suspend fun fetchChapters(slug: String): List<MangaChapter> {
		val result = ArrayList<MangaChapter>()
		var page = 1
		var lastPage = 1
		do {
			val url = apiUrl("api/series/$slug/chapters").newBuilder()
				.addQueryParameter("page", page.toString())
				.addQueryParameter("direction", "desc")
				.addQueryParameter("type", "comic")
				.build()
			val root = webClient.httpGet(url, getRequestHeaders()).parseJson()
			val items = root.optJSONArray("data") ?: JSONArray()
			result += List(items.length()) { index ->
				parseChapter(slug, items.getJSONObject(index))
			}
			lastPage = root.optJSONObject("meta")?.optInt("last_page").takeIf { it != null && it > 0 } ?: page
			page += 1
		} while (page <= lastPage)
		return result.reversed()
	}

	private suspend fun fetchFilters(): Pair<Set<MangaTag>, Map<MangaState, String>> {
		val root = webClient.httpGet(apiUrl("api/genres-statuses"), getRequestHeaders()).parseJson()
		val tags = parseGenres(root.optJSONArray("genres"))
		val statusMap = LinkedHashMap<MangaState, String>(4)
		val statuses = root.optJSONArray("statuses") ?: JSONArray()
		for (i in 0 until statuses.length()) {
			val item = statuses.optJSONObject(i) ?: continue
			val id = item.opt("id")?.toString().orEmpty()
			if (id.isBlank()) continue
			val state = parseState(item.optString("name")) ?: continue
			statusMap[state] = id
		}
		return tags to statusMap
	}

	private fun parseSeriesList(items: JSONArray): List<Manga> {
		val result = ArrayList<Manga>(items.length())
		for (i in 0 until items.length()) {
			val item = items.optJSONObject(i) ?: continue
			if (item.optString("type").ifBlank { "comic" } != "comic") continue
			parseSeries(item)?.let(result::add)
		}
		return result
	}

	private fun parseSeries(item: JSONObject): Manga? {
		val slug = item.optString("slug").nullIfEmpty() ?: return null
		return Manga(
			id = generateUid(buildMangaUrl(slug)),
			url = buildMangaUrl(slug),
			publicUrl = publicMangaUrl(slug),
			title = item.optString("name").ifBlank { slug },
			coverUrl = item.optString("cover").nullIfEmpty(),
			altTitles = emptySet(),
			rating = RATING_UNKNOWN,
			tags = emptySet(),
			description = null,
			state = parseState(
				item.optJSONObject("status")?.optString("name")
					?: item.optString("status"),
			),
			authors = emptySet(),
			contentRating = ContentRating.SAFE,
			source = source,
		)
	}

	private fun parseChapter(slug: String, item: JSONObject): MangaChapter {
		val chapterId = item.opt("id")?.toString().orEmpty()
		val numberText = item.optString("name").nullIfEmpty()
		val titleText = item.optString("title").nullIfEmpty()
		val title = when {
			!titleText.isNullOrBlank() && !numberText.isNullOrBlank() -> "$numberText - $titleText"
			!titleText.isNullOrBlank() -> titleText
			numberText?.toFloatOrNull() == null -> numberText
			else -> null
		}
		return MangaChapter(
			id = generateUid("$slug/$chapterId"),
			title = title,
			number = numberText?.toFloatOrNull() ?: 0f,
			volume = 0,
			url = "/chapter/$slug/$chapterId",
			scanlator = parseScanlator(item.opt("team")),
			uploadDate = parseDate(item.optString("published_at")),
			branch = null,
			source = source,
		)
	}

	private fun parseGenres(items: JSONArray?): Set<MangaTag> {
		if (items == null) return emptySet()
		val result = LinkedHashSet<MangaTag>(items.length())
		for (i in 0 until items.length()) {
			val item = items.optJSONObject(i) ?: continue
			val id = item.opt("id")?.toString().orEmpty()
			if (id.isBlank()) continue
			val title = item.optString("name").trim().nullIfEmpty() ?: continue
			result += MangaTag(
				key = id,
				title = title,
				source = source,
			)
		}
		return result
	}

	private fun parseAuthors(team: Any?): Set<String> {
		val name = parseScanlator(team)
		return if (name == null) emptySet() else setOf(name)
	}

	private fun parseScanlator(team: Any?): String? = when (team) {
		is JSONObject -> team.optString("name").nullIfEmpty()
		is String -> team.nullIfEmpty()
		else -> null
	}

	private fun parseState(value: String?): MangaState? {
		return when (value?.trim()?.lowercase(sourceLocale)) {
			"activo", "activa", "emision", "en emision", "publicandose", "publicándose" -> MangaState.ONGOING
			"completado", "completa", "finalizado", "finalizada" -> MangaState.FINISHED
			"hiato", "pausado", "pausada" -> MangaState.PAUSED
			"cancelado", "cancelada", "abandonado", "abandonada" -> MangaState.ABANDONED
			"proximamente", "próximamente", "proximo", "próximo" -> MangaState.UPCOMING
			else -> null
		}
	}

	private fun parseRating(item: JSONObject): Float {
		val value = item.optDouble("rating", Double.NaN).takeUnless { it.isNaN() || it <= 0.0 }
			?: return RATING_UNKNOWN
		return if (value > 5.0) (value / 2.0).toFloat() else value.toFloat()
	}

	private fun parseDate(value: String?): Long {
		if (value.isNullOrBlank()) return 0L
		return synchronized(dateFormats) {
			dateFormats.firstNotNullOfOrNull { format ->
				format.parseSafe(value).takeIf { it != 0L }
			} ?: 0L
		}
	}

	private fun buildMangaUrl(slug: String): String = "/series/$slug"

	private fun publicMangaUrl(slug: String): String = "https://$domain/series/comic-$slug"

	private fun detailsUrl(slug: String) = siteUrl("api/series/$slug").newBuilder()
		.addQueryParameter("type", "comic")
		.build()

	private fun chapterPagesUrl(slug: String, chapterId: String) = siteUrl("api/capitulo/comic-$slug/$chapterId")

	private fun apiUrl(path: String) = "https://dashboard.$domain/${path.removePrefix("/")}".toHttpUrl()

	private fun siteUrl(path: String) = "https://$domain/${path.removePrefix("/")}".toHttpUrl()

	private suspend fun fetchSeriesList(): List<JSONObject> {
		val now = System.currentTimeMillis()
		seriesListCache?.takeIf { now - seriesListCacheTime < SERIES_LIST_CACHE_DURATION }?.let {
			return it
		}
		val root = webClient.httpGet(siteUrl("api/series/list"), getRequestHeaders()).parseJson()
		val items = root.optJSONArray("data") ?: JSONArray()
		val result = ArrayList<JSONObject>(items.length())
		for (i in 0 until items.length()) {
			val item = items.optJSONObject(i) ?: continue
			if (item.optString("type").ifBlank { "comic" } == "comic") {
				result += item
			}
		}
		seriesListCache = result
		seriesListCacheTime = now
		return result
	}

	private companion object {
		private const val SERIES_LIST_CACHE_DURATION = 60 * 60 * 1000L

		@Volatile
		private var seriesListCache: List<JSONObject>? = null

		@Volatile
		private var seriesListCacheTime = 0L

		private val dateFormats = listOf(
			SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'", Locale.US),
			SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US),
		).onEach {
			it.timeZone = TimeZone.getTimeZone("UTC")
		}
	}
}
