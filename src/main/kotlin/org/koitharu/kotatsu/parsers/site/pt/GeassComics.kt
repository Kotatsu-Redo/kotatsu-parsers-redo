package org.koitharu.kotatsu.parsers.site.pt

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.Broken
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
import org.koitharu.kotatsu.parsers.util.json.mapJSONToSet
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

@Broken
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
		get() = SortOrder.POPULARITY

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
			MangaState.ABANDONED,
		),
		availableContentRating = EnumSet.of(
			ContentRating.SAFE,
			ContentRating.ADULT,
		),
		availableContentTypes = EnumSet.of(
			ContentType.MANGA,
			ContentType.MANHWA,
			ContentType.MANHUA,
			ContentType.COMICS,
		),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = "$apiUrl/api/mangas/search".toHttpUrl().newBuilder().apply {
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
				addQueryParameter("type", it)
			}

			when (filter.contentRating.singleOrNull()) {
				ContentRating.ADULT -> addQueryParameter("nsfw", "true")
				ContentRating.SAFE -> addQueryParameter("nsfw", "false")
				else -> Unit
			}

			val selectedGenres = filter.tags.filter { it.key.startsWith(GENRE_PREFIX) }
			if (selectedGenres.isNotEmpty()) {
				addQueryParameter("genres", selectedGenres.joinToString(",") { it.key.removePrefix(GENRE_PREFIX) })
			}

			val selectedTags = filter.tags.filter { it.key.startsWith(TAG_PREFIX) }
			if (selectedTags.isNotEmpty()) {
				addQueryParameter("tags", selectedTags.joinToString(",") { it.key.removePrefix(TAG_PREFIX) })
			}
		}.build()

		val response = webClient.httpGet(url, getApiHeaders()).parseJson()
		val data = response.optJSONArray("data") ?: JSONArray()
		return data.mapJSONNotNull { json -> parseManga(json) }
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val slug = manga.url.removePrefix("/manga/").substringAfterLast('/')
		val response = webClient.httpGet("$apiUrl/api/mangas/$slug", getApiHeaders()).parseJson()
		val data = response.optJSONObject("data") ?: response
		val parsed = parseManga(data) ?: manga

		return manga.copy(
			title = parsed.title.ifBlank { manga.title },
			altTitles = parsed.altTitles.ifEmpty { manga.altTitles },
			url = parsed.url,
			publicUrl = parsed.publicUrl,
			coverUrl = parsed.coverUrl ?: manga.coverUrl,
			largeCoverUrl = parsed.largeCoverUrl ?: manga.largeCoverUrl,
			description = parsed.description ?: manga.description,
			tags = if (parsed.tags.isNotEmpty()) parsed.tags else manga.tags,
			authors = if (parsed.authors.isNotEmpty()) parsed.authors else manga.authors,
			state = parsed.state ?: manga.state,
			contentRating = parsed.contentRating ?: manga.contentRating,
			chapters = fetchChapters(
				mangaId = data.getString("id"),
				mangaSlug = data.getString("slug"),
			),
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val chapterId = chapter.url.substringAfter("/chapter/").substringBefore('/')
		val response = webClient.httpGet("$apiUrl/api/chapters/$chapterId", getApiHeaders()).parseJson()
		val data = response.optJSONObject("data") ?: response
		val pages = data.optJSONArray("pages") ?: return emptyList()

		return List(pages.length()) { index -> pages.getJSONObject(index) }
			.sortedBy { it.optInt("pageNumber", Int.MAX_VALUE) }
			.map { page ->
				val imageUrl = page.getString("imageUrl").toAbsoluteApiUrl()
				MangaPage(
					id = generateUid(page.getStringOrNull("id") ?: imageUrl),
					url = imageUrl,
					preview = null,
					source = source,
				)
			}
	}

	private suspend fun fetchChapters(mangaId: String, mangaSlug: String): List<MangaChapter> {
		val chapters = ArrayList<MangaChapter>()
		var page = 1

		while (true) {
			val url = "$apiUrl/api/chapters".toHttpUrl().newBuilder()
				.addQueryParameter("mangaId", mangaId)
				.addQueryParameter("page", page.toString())
				.addQueryParameter("limit", CHAPTERS_LIMIT.toString())
				.addQueryParameter("order", "desc")
				.build()

			val response = webClient.httpGet(url, getApiHeaders()).parseJson()
			val data = response.optJSONArray("data") ?: break
			if (data.length() == 0) break

			chapters += data.mapJSONNotNull { json ->
				if (!json.getBooleanOrDefault("isPublished", true)) {
					return@mapJSONNotNull null
				}

				if (json.getBooleanOrDefault("isVipOnly", false)) {
					return@mapJSONNotNull null
				}

				parseChapter(json, mangaSlug)
			}

			val pagination = response.optJSONObject("pagination")
			val hasNext = when {
				pagination == null -> false
				!pagination.isNull("hasNext") -> pagination.getBooleanOrDefault("hasNext", false)
				!pagination.isNull("hasNextPage") -> pagination.getBooleanOrDefault("hasNextPage", false)
				else -> false
			}
			if (!hasNext) {
				break
			}
			page++
		}

		return chapters.reversed()
	}

	private fun parseManga(json: JSONObject): Manga? {
		val slug = json.getStringOrNull("slug") ?: return null
		val relativeUrl = "/manga/$slug"
		val tags = linkedSetOf<MangaTag>().apply {
			addAll(parseTags(json.optJSONArray("genres"), GENRE_PREFIX))
			addAll(parseTags(json.optJSONArray("tags"), TAG_PREFIX))
		}
		val authors = linkedSetOf<String>().apply {
			json.getStringOrNull("author")?.let(::add)
			json.getStringOrNull("artist")?.let(::add)
		}
		return Manga(
			id = generateUid(json.getStringOrNull("id") ?: relativeUrl),
			title = json.getStringOrNull("title").orEmpty(),
			altTitles = parseAltTitles(json.getStringOrNull("alternativeTitles")),
			url = relativeUrl,
			publicUrl = "https://$domain/obra/$slug",
			rating = RATING_UNKNOWN,
			contentRating = if (json.getBooleanOrDefault("isNsfw", false)) {
				ContentRating.ADULT
			} else {
				ContentRating.SAFE
			},
			coverUrl = json.getStringOrNull("coverImage")?.toAbsoluteApiUrl(),
			tags = tags,
			state = parseState(json.getStringOrNull("status")),
			authors = authors,
			largeCoverUrl = json.getStringOrNull("bannerImage")?.toAbsoluteApiUrl(),
			description = json.getStringOrNull("description"),
			source = source,
		)
	}

	private fun parseChapter(json: JSONObject, mangaSlug: String): MangaChapter {
		val id = json.getString("id")
		val chapterNumber = json.getStringOrNull("chapterNumber")?.toFloatOrNull() ?: 0f
		return MangaChapter(
			id = generateUid(id),
			title = json.getStringOrNull("title"),
			number = chapterNumber,
			volume = 0,
			url = "/chapter/$id/$mangaSlug/${chapterNumber.formatChapterSuffix()}",
			scanlator = json.getStringOrNull("uploader"),
			uploadDate = chapterDateFormat.parseSafe(
				json.getStringOrNull("createdAt") ?: json.getStringOrNull("updatedAt"),
			),
			branch = null,
			source = source,
		)
	}

	private fun parseTags(array: JSONArray?, prefix: String): Set<MangaTag> {
		if (array == null) return emptySet()
		return array.mapJSONToSet { json ->
			MangaTag(
				key = prefix + json.getString("id"),
				title = json.getString("name"),
				source = source,
			)
		}
	}

	private fun parseAltTitles(raw: String?): Set<String> {
		return raw?.split(Regex("""[\r\n;]+"""))
			?.mapNotNull { it.trim().nullIfEmpty() }
			?.toSet()
			.orEmpty()
	}

	private fun parseState(status: String?): MangaState? = when (status?.trim()?.lowercase(Locale.ROOT)) {
		"ongoing" -> MangaState.ONGOING
		"completed", "complete" -> MangaState.FINISHED
		"hiatus", "on_hold", "on hold" -> MangaState.PAUSED
		"cancelled", "canceled", "dropped" -> MangaState.ABANDONED
		else -> null
	}

	private fun sortToApi(order: SortOrder, query: String?): List<Pair<String, String>> = when (order) {
		SortOrder.POPULARITY -> listOf("sort" to "views", "order" to "desc")
		SortOrder.UPDATED -> listOf("sort" to "updatedAt", "order" to "desc")
		SortOrder.NEWEST -> listOf("sort" to "createdAt", "order" to "desc")
		SortOrder.ALPHABETICAL -> listOf("sort" to "title", "order" to "asc")
		SortOrder.ALPHABETICAL_DESC -> listOf("sort" to "title", "order" to "desc")
		SortOrder.RELEVANCE -> if (query.isNullOrBlank()) {
			listOf("sort" to "views", "order" to "desc")
		} else {
			emptyList()
		}

		else -> listOf("sort" to "views", "order" to "desc")
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
				MangaTag(
					key = GENRE_PREFIX + json.getString("id"),
					title = json.getString("name"),
					source = source,
				)
			}
			.orEmpty()

		val tags = webClient.httpGet("$apiUrl/api/tags", getApiHeaders()).parseJson()
			.optJSONArray("data")
			?.mapJSONNotNull { json ->
				MangaTag(
					key = TAG_PREFIX + json.getString("id"),
					title = json.getString("name"),
					source = source,
				)
			}
			.orEmpty()

		return linkedSetOf<MangaTag>().apply {
			addAll(genres)
			addAll(tags)
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
		private const val CHAPTERS_LIMIT = 100
		private const val apiUrl = "https://api.skkyscan.fun"
		private const val GENRE_PREFIX = "genre:"
		private const val TAG_PREFIX = "tag:"

		private val chapterDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).apply {
			timeZone = java.util.TimeZone.getTimeZone("UTC")
		}
	}
}
