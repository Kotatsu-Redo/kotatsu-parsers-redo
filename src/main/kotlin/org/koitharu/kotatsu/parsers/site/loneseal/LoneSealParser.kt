package org.koitharu.kotatsu.parsers.site.loneseal

import androidx.collection.ArrayMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.AuthRequiredException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import java.text.SimpleDateFormat
import java.util.*

internal abstract class LoneSealParser(
	context: MangaLoaderContext,
	source: MangaParserSource,
	domain: String,
	pageSize: Int = 30,
) : PagedMangaParser(context, source, pageSize, pageSize) {

	override val configKeyDomain = ConfigKey.Domain(domain)

	protected open val mangaPathSegment: String = "comic"
	protected open val includeChapterTitle: Boolean = false

	private var genresCache: Map<String, MangaTag>? = null
	private val genresMutex = Mutex()

	override val defaultSortOrder: SortOrder
		get() = SortOrder.POPULARITY

	override val availableSortOrders: Set<SortOrder>
		get() = EnumSet.of(
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
			isSearchWithFiltersSupported = true,
			isAuthorSearchSupported = true,
		)

	override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
		.add("Referer", "https://$domain/")
		.add("Origin", "https://$domain")
		.build()

	protected open fun apiUrlBuilder(): HttpUrl.Builder = urlBuilder().addPathSegment("api")

	override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions(
		availableTags = getGenres().values.toSet(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED, MangaState.PAUSED),
		availableContentTypes = EnumSet.of(ContentType.MANGA, ContentType.MANHWA, ContentType.MANHUA),
	)

	protected suspend fun getGenres(): Map<String, MangaTag> = genresMutex.withLock {
		genresCache?.let { return@withLock it }
		val url = apiUrlBuilder().addPathSegment("genres").build()
		val array = webClient.httpGet(url).parseJsonArray()
		val result = ArrayMap<String, MangaTag>(array.length())
		for (i in 0 until array.length()) {
			val obj = array.optJSONObject(i) ?: continue
			val slug = obj.getStringOrNull("slug") ?: continue
			val name = obj.getStringOrNull("name")?.toTitleCase(sourceLocale) ?: continue
			result[slug] = MangaTag(title = name, key = slug, source = source)
		}
		genresCache = result
		return@withLock result
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val apiSort = when (order) {
			SortOrder.UPDATED, SortOrder.UPDATED_ASC -> "latest"
			SortOrder.POPULARITY, SortOrder.POPULARITY_ASC -> "views"
			SortOrder.RATING, SortOrder.RATING_ASC -> "rate"
			SortOrder.NEWEST, SortOrder.NEWEST_ASC -> "new"
			else -> "latest"
		}
		val apiOrder = when (order) {
			SortOrder.UPDATED_ASC,
			SortOrder.POPULARITY_ASC,
			SortOrder.RATING_ASC,
			SortOrder.NEWEST_ASC,
			-> "asc"

			else -> "desc"
		}
		val url = apiUrlBuilder().apply {
			addPathSegment("search")
			addQueryParameter("type", "COMIC")
			addQueryParameter("limit", pageSize.toString())
			addQueryParameter("page", page.toString())
			addQueryParameter("sort", apiSort)
			addQueryParameter("order", apiOrder)
			filter.query?.takeIf(String::isNotBlank)?.let { addQueryParameter("q", it) }
			filter.tags.firstOrNull()?.let { addQueryParameter("genre", it.key) }
			filter.states.oneOrThrowIfMany()?.let { state ->
				val status = when (state) {
					MangaState.ONGOING -> "ONGOING"
					MangaState.FINISHED -> "COMPLETED"
					MangaState.PAUSED -> "HIATUS"
					else -> null
				}
				if (status != null) {
					addQueryParameter("status", status)
				}
			}
			filter.types.oneOrThrowIfMany()?.let { type ->
				val comicType = when (type) {
					ContentType.MANGA -> "MANGA"
					ContentType.MANHWA -> "MANHWA"
					ContentType.MANHUA -> "MANHUA"
					else -> null
				}
				if (comicType != null) {
					addQueryParameter("comic_type", comicType)
				}
			}
			filter.author?.takeIf(String::isNotBlank)?.let {
				addQueryParameter("author", it)
			}
		}.build()

		val data = webClient.httpGet(url).parseJson().optJSONArray("data") ?: return emptyList()
		val result = data.mapJSONNotNull(::parseManga)
		return when (order) {
			SortOrder.ALPHABETICAL -> result.sortedBy { it.title.lowercase(Locale.ROOT) }
			SortOrder.ALPHABETICAL_DESC -> result.sortedByDescending { it.title.lowercase(Locale.ROOT) }
			else -> result
		}
	}

	protected open fun parseManga(obj: JSONObject): Manga? {
		val slug = obj.getStringOrNull("slug") ?: return null
		val relativeUrl = "/$mangaPathSegment/$slug"
		return Manga(
			id = generateUid(relativeUrl),
			url = relativeUrl,
			title = obj.getStringOrNull("title")?.trim() ?: slug,
			altTitles = parseAlternativeTitles(obj),
			publicUrl = relativeUrl.toAbsoluteUrl(domain),
			rating = parseRating(obj),
			contentRating = parseContentRating(obj),
			coverUrl = obj.getStringOrNull("poster_image_url")?.let(::normalizeImageUrl),
			tags = emptySet(),
			state = parseState(obj.getStringOrNull("comic_status")),
			authors = parseAuthors(obj),
			source = source,
		)
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val slug = manga.url.substringBefore('?').trimEnd('/').substringAfterLast('/')
		val url = apiUrlBuilder()
			.addPathSegments("series/comic")
			.addPathSegment(slug)
			.build()
		val obj = webClient.httpGet(url).parseJson()
		val relativeUrl = "/$mangaPathSegment/$slug"
		val genresMap = getGenres()
		val tags = obj.optJSONArray("genres")?.mapJSONNotNullToSet { genre ->
			val key = genre.getStringOrNull("slug") ?: return@mapJSONNotNullToSet null
			val title = genre.getStringOrNull("name")?.toTitleCase(sourceLocale) ?: return@mapJSONNotNullToSet null
			MangaTag(title = title, key = key, source = source)
		}?.toMutableSet() ?: mutableSetOf()
		obj.getStringOrNull("primary_genre")?.let { primary ->
			genresMap[primary.lowercase(Locale.ROOT)]?.let(tags::add)
				?: genresMap.values.firstOrNull { it.title.equals(primary, ignoreCase = true) }?.let(tags::add)
		}

		val description = obj.getStringOrNull("synopsis")?.let {
			Jsoup.parseBodyFragment(it).body().text().nullIfEmpty()
		}

		return manga.copy(
			url = relativeUrl,
			publicUrl = relativeUrl.toAbsoluteUrl(domain),
			title = obj.getStringOrNull("title")?.trim() ?: manga.title,
			altTitles = parseAlternativeTitles(obj),
			description = description,
			rating = parseRating(obj),
			contentRating = parseContentRating(obj),
			coverUrl = obj.getStringOrNull("poster_image_url")?.let(::normalizeImageUrl) ?: manga.coverUrl,
			state = parseState(obj.getStringOrNull("comic_status")),
			authors = parseAuthors(obj),
			tags = tags,
			chapters = parseChapters(obj.optJSONArray("units"), slug),
		)
	}

	protected open fun parseChapters(array: JSONArray?, seriesSlug: String): List<MangaChapter> {
		if (array.isNullOrEmpty()) return emptyList()
		val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT)
		return array.mapChapters(reversed = true) { _, obj ->
			val chapterSlug = obj.getStringOrNull("slug") ?: return@mapChapters null
			val chapterUrl = "/$mangaPathSegment/$seriesSlug/chapter/$chapterSlug"
			val numberStr = obj.getStringOrNull("number")
			val rawTitle = obj.getStringOrNull("title")
			val title = when {
				numberStr == null -> rawTitle
				includeChapterTitle && rawTitle != null -> "Chapter ${numberStr.removeSuffix(".00")} - $rawTitle"
				else -> "Chapter ${numberStr.removeSuffix(".00")}"
			}
			MangaChapter(
				id = generateUid(chapterUrl),
				url = chapterUrl,
				title = title,
				number = numberStr?.toFloatOrNull() ?: 0f,
				volume = 0,
				branch = null,
				scanlator = null,
				uploadDate = dateFormat.parseSafe(obj.getStringOrNull("created_at")),
				source = source,
			)
		}
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val seriesSlug = chapter.url.substringAfter("/$mangaPathSegment/").substringBefore("/chapter/")
		val chapterSlug = chapter.url.substringAfter("/chapter/")
		val url = apiUrlBuilder()
			.addPathSegments("series/comic")
			.addPathSegment(seriesSlug)
			.addPathSegment("chapter")
			.addPathSegment(chapterSlug)
			.build()
		val chapterObj = webClient.httpGet(url).parseJson().optJSONObject("chapter") ?: return emptyList()
		val pages = chapterObj.optJSONArray("pages")
		if (pages.isNullOrEmpty()) {
			if (chapterObj.getBooleanOrDefault("login_required", false)) {
				throw AuthRequiredException(source)
			}
			return emptyList()
		}
		val rawUrls = pages.mapJSONNotNull { item ->
			item.getStringOrNull("image_url")?.let(::normalizeImageUrl)
		}
		return filterPageUrls(rawUrls).map { imageUrl ->
			MangaPage(
				id = generateUid(imageUrl),
				url = imageUrl,
				preview = null,
				source = source,
			)
		}
	}

	protected open fun filterPageUrls(urls: List<String>): List<String> = urls

	override suspend fun resolveLink(resolver: LinkResolver, link: HttpUrl): Manga? {
		val segments = link.pathSegments
		if (segments.firstOrNull() != mangaPathSegment || segments.getOrNull(2) == "chapter") {
			return null
		}
		val slug = segments.getOrNull(1)?.nullIfEmpty() ?: return null
		val url = "/$mangaPathSegment/$slug"
		return resolver.resolveManga(this, url, generateUid(url))
	}

	protected open fun parseAuthors(obj: JSONObject): Set<String> = setOfNotNull(
		obj.getStringOrNull("author_name")?.trim()?.nullIfEmpty(),
		obj.getStringOrNull("artist_name")?.trim()?.nullIfEmpty(),
	)

	protected open fun parseAlternativeTitles(obj: JSONObject): Set<String> = when (val value = obj.opt("alternative_titles")) {
		is JSONArray -> value.toStringSet().mapNotNullToSet { it.trim().nullIfEmpty() }
		is String -> value.split(',').mapNotNullToSet { it.trim().nullIfEmpty() }
		else -> emptySet()
	}

	protected open fun parseRating(obj: JSONObject): Float {
		val value = obj.getFloatOrDefault("rating_average", -1f)
		return if (value > 0f) value.div(10f).coerceIn(0f, 1f) else RATING_UNKNOWN
	}

	protected open fun parseContentRating(obj: JSONObject): ContentRating =
		if (obj.getIntOrDefault("is_adult", 0) == 1 || obj.getBooleanOrDefault("is_adult", false)) {
			ContentRating.ADULT
		} else {
			ContentRating.SAFE
		}

	protected open fun parseState(value: String?): MangaState? = when (value?.uppercase(Locale.ROOT)) {
		"ONGOING" -> MangaState.ONGOING
		"COMPLETED", "FINISHED" -> MangaState.FINISHED
		"HIATUS", "PAUSED" -> MangaState.PAUSED
		"CANCELLED" -> MangaState.ABANDONED
		else -> null
	}

	protected open fun normalizeImageUrl(url: String): String {
		val parsed = url.toHttpUrlOrNull()
		val normalized = when {
			parsed != null && parsed.scheme == "http" -> {
				parsed.newBuilder()
					.scheme("https")
					.build()
					.toString()
			}

			url.startsWith("/") -> url.toAbsoluteUrl(domain)
			else -> url
		}
		if (!normalized.contains("googleusercontent.com") && !normalized.contains("bp.blogspot.com")) {
			return normalized
		}
		return normalized.replace(BLOGGER_SIZE_QUERY_REGEX, "=s0$1").replace(BLOGGER_SIZE_PATH_REGEX, "/s0/")
	}

	private companion object {
		val BLOGGER_SIZE_QUERY_REGEX = Regex("""=[swh]\d+[^/?]*($|\?)""", RegexOption.IGNORE_CASE)
		val BLOGGER_SIZE_PATH_REGEX = Regex("""/[swh]\d+[^/]*/""", RegexOption.IGNORE_CASE)
	}
}
