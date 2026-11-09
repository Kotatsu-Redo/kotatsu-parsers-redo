package org.koitharu.kotatsu.parsers.site.pt

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
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
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.parseSafe
import org.koitharu.kotatsu.parsers.util.json.getBooleanOrDefault
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.json.mapJSONNotNull
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("GEASSCOMICS", "Geass Comics", "pt")
internal class GeassComics(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.GEASSCOMICS, PAGE_SIZE) {

	@Volatile
	private var cachedFilters: Set<MangaTag>? = null
	private val filtersMutex = Mutex()

	override val configKeyDomain = ConfigKey.Domain("geasscomics.xyz")

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
		.add("Referer", "https://$domain/")
		.add("Origin", "https://$domain")
		.build()

	private fun getApiHeaders(): Headers = getRequestHeaders().newBuilder()
		.set("Accept", "application/json, text/plain, */*")
		.build()

	override val defaultSortOrder: SortOrder
		get() = SortOrder.UPDATED

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.RELEVANCE,
		SortOrder.POPULARITY,
		SortOrder.UPDATED,
		SortOrder.NEWEST,
		SortOrder.ALPHABETICAL,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isMultipleTagsSupported = true,
		)

	override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions(
		availableTags = getOrCreateFilters(),
		availableStates = EnumSet.of(
			MangaState.ONGOING,
			MangaState.FINISHED,
			MangaState.PAUSED,
		),
		availableContentTypes = EnumSet.of(
			ContentType.MANGA,
			ContentType.MANHWA,
			ContentType.MANHUA,
		),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = "$apiUrl/api/works".toHttpUrl().newBuilder().apply {
			addQueryParameter("page", page.toString())
			addQueryParameter("limit", PAGE_SIZE.toString())

			filter.query?.trim()?.takeIf { it.isNotEmpty() }?.let {
				addQueryParameter("q", it)
			}

			sortToApi(order, filter.query).forEach { (key, value) ->
				addQueryParameter(key, value)
			}

			stateToApi(filter.states.firstOrNull())?.nullIfEmpty()?.let {
				addQueryParameter("status", it)
			}

			typeToApi(filter.types.firstOrNull())?.nullIfEmpty()?.let {
				addQueryParameter("types", it)
			}

			val selectedGenres = filter.tags.filter { it.key.startsWith(GENRE_PREFIX) }
			if (selectedGenres.isNotEmpty()) {
				addQueryParameter("genre", selectedGenres.first().key.removePrefix(GENRE_PREFIX))
			}
		}.build()

		val response = webClient.httpGet(url, getApiHeaders()).parseJson()
		val data = response.optJSONObject("data")?.optJSONArray("items") ?: JSONArray()
		return data.mapJSONNotNull { json -> parseManga(json) }
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val slug = manga.url.removePrefix("/manga/").substringAfterLast('/')
		val response = webClient.httpGet("$apiUrl/api/works/$slug", getApiHeaders()).parseJson()
		val data = response.optJSONObject("data") ?: response
		val parsed = parseManga(data) ?: manga
		val chapters = data.optJSONArray("chapters")
			?.mapJSONNotNull { json -> parseChapter(json, slug) }
			?.reversed()
			.orEmpty()

		return parsed.copy(
			id = manga.id,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val path = chapter.url.removePrefix("/read/").split('/')
		val slug = path.getOrNull(0).orEmpty()
		val chapterNumber = path.getOrNull(1).orEmpty()
		if (slug.isEmpty() || chapterNumber.isEmpty()) return emptyList()
		val response = webClient.httpGet(
			"$apiUrl/api/works/$slug/chapters/$chapterNumber",
			getApiHeaders(),
		).parseJson()
		val data = response.optJSONObject("data") ?: response
		val pages = data.optJSONArray("pages") ?: return emptyList()

		return List(pages.length()) { index ->
			val imageUrl = pages.getString(index).toAbsoluteApiUrl()
			MangaPage(
				id = generateUid("$imageUrl#$index"),
				url = imageUrl,
				preview = null,
				source = source,
			)
		}
	}

	private fun parseManga(json: JSONObject): Manga? {
		val slug = json.getStringOrNull("slug") ?: return null
		val relativeUrl = "/manga/$slug"
		val tags = parseTags(json.optJSONArray("tags"))
		val authors = linkedSetOf<String>().apply {
			json.getStringOrNull("author")?.nullIfEmpty()?.let(::add)
		}
		val rating = json.optDouble("rating", 0.0)
			.takeIf { it > 0.0 }
			?.div(5.0)
			?.toFloat()
			?: RATING_UNKNOWN
		return Manga(
			id = generateUid(json.getStringOrNull("id") ?: relativeUrl),
			title = json.getStringOrNull("title").orEmpty(),
			altTitles = emptySet(),
			url = relativeUrl,
			publicUrl = "https://$domain/obra/$slug",
			rating = rating,
			contentRating = if (json.getBooleanOrDefault("isNsfw", false)) {
				ContentRating.ADULT
			} else {
				ContentRating.SAFE
			},
			coverUrl = json.getStringOrNull("cover")?.toAbsoluteApiUrl(),
			tags = tags,
			state = parseState(json.getStringOrNull("status")),
			authors = authors,
			largeCoverUrl = json.getStringOrNull("coverLarge")?.toAbsoluteApiUrl(),
			description = json.getStringOrNull("synopsis"),
			source = source,
		)
	}

	private fun parseChapter(json: JSONObject, mangaSlug: String): MangaChapter {
		val id = json.getString("id")
		val chapterNumber = json.optDouble("number", 0.0).toFloat()
		return MangaChapter(
			id = generateUid(id),
			title = json.getStringOrNull("title"),
			number = chapterNumber,
			volume = 0,
			url = "/read/$mangaSlug/" + chapterNumber.formatChapterSuffix(),
			scanlator = null,
			uploadDate = parseChapterDate(json.getStringOrNull("releasedAt")),
			branch = null,
			source = source,
		)
	}

	private fun parseTags(array: JSONArray?): Set<MangaTag> {
		if (array == null) return emptySet()
		return List(array.length()) { index -> array.getString(index) }
			.mapNotNull { title ->
				val cleanTitle = cleanTagTitle(title).nullIfEmpty() ?: return@mapNotNull null
				MangaTag(
					key = TAG_PREFIX + cleanTitle.toApiSlug(),
					title = cleanTitle,
					source = source,
				)
			}
			.toSet()
	}

	private fun parseState(status: String?): MangaState? = when (status?.trim()?.lowercase(Locale.ROOT)) {
		"ongoing" -> MangaState.ONGOING
		"completed", "complete" -> MangaState.FINISHED
		"hiatus", "on_hold", "on hold" -> MangaState.PAUSED
		"cancelled", "canceled", "dropped" -> MangaState.ABANDONED
		else -> null
	}

	private fun sortToApi(order: SortOrder, query: String?): List<Pair<String, String>> = when (order) {
		SortOrder.POPULARITY -> listOf("sortBy" to "rating", "sortDir" to "desc")
		SortOrder.UPDATED, SortOrder.NEWEST -> listOf("sortBy" to "recent", "sortDir" to "desc")
		SortOrder.ALPHABETICAL -> listOf("sortBy" to "title", "sortDir" to "asc")
		SortOrder.ALPHABETICAL_DESC -> listOf("sortBy" to "title", "sortDir" to "desc")
		SortOrder.RELEVANCE -> if (query.isNullOrBlank()) {
			listOf("sortBy" to "recent", "sortDir" to "desc")
		} else {
			emptyList()
		}

		else -> listOf("sortBy" to "recent", "sortDir" to "desc")
	}

	private fun stateToApi(state: MangaState?): String? = when (state) {
		MangaState.ONGOING -> "ongoing"
		MangaState.FINISHED -> "completed"
		MangaState.PAUSED -> "hiatus"
		MangaState.ABANDONED -> "cancelled"
		else -> null
	}

	private fun typeToApi(type: ContentType?): String? = when (type) {
		ContentType.MANGA -> "manga"
		ContentType.MANHWA -> "manhwa"
		ContentType.MANHUA -> "manhua"
		ContentType.COMICS -> "comic"
		else -> null
	}

	private suspend fun getOrCreateFilters(): Set<MangaTag> = filtersMutex.withLock {
		cachedFilters ?: fetchFilters().also { cachedFilters = it }
	}

	private suspend fun fetchFilters(): Set<MangaTag> {
		val genres = webClient.httpGet("$apiUrl/api/genres", getApiHeaders()).parseJson()
			.optJSONArray("data")
			?.mapJSONNotNull { json ->
				val title = cleanTagTitle(json.getString("label")).nullIfEmpty() ?: return@mapJSONNotNull null
				MangaTag(
					key = GENRE_PREFIX + json.getString("slug"),
					title = title,
					source = source,
				)
			}
			.orEmpty()

		return genres
			.distinctBy { it.title }
			.sortedBy { it.title }
			.toCollection(LinkedHashSet())
	}

	private fun cleanTagTitle(raw: String): String = raw
		.replace(Regex("""\s+"""), " ")
		.replace(Regex("""\s*\(\d+\)$"""), "")
		.trim()
		.replaceFirstChar { char -> char.titlecase(Locale.ROOT) }

	private fun String.toApiSlug(): String = Normalizer.normalize(this, Normalizer.Form.NFD)
		.replace(Regex("""\p{Mn}+"""), "")
		.lowercase(Locale.ROOT)
		.replace(Regex("""[^a-z0-9]+"""), "-")
		.trim('-')

	private fun parseChapterDate(raw: String?): Long {
		if (raw.isNullOrBlank()) return 0L
		return synchronized(chapterDateFormats) {
			chapterDateFormats.firstNotNullOfOrNull { format ->
				format.parseSafe(raw).takeIf { it != 0L }
			} ?: 0L
		}
	}

	private fun String.toAbsoluteApiUrl(): String = when {
		startsWith("http://") || startsWith("https://") -> this
		else -> "$apiUrl${if (startsWith('/')) this else "/$this"}"
	}.urlEncodedPathFix()

	private fun String.urlEncodedPathFix(): String = replace(" ", "%20")

	private fun Float.formatChapterSuffix(): String {
		val asInt = toInt()
		return if (this == asInt.toFloat()) asInt.toString() else toString()
	}

	private companion object {
		private const val PAGE_SIZE = 24
		private const val apiUrl = "https://api.geasscomics.xyz"
		private const val GENRE_PREFIX = "genre:"
		private const val TAG_PREFIX = "tag:"

		private val chapterDateFormats = listOf(
			SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT),
			SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT),
			SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT),
		).onEach {
			it.timeZone = java.util.TimeZone.getTimeZone("UTC")
		}
	}
}
