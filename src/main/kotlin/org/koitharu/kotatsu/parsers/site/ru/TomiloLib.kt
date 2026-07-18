package org.koitharu.kotatsu.parsers.site.ru

import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.ParseException
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
import org.koitharu.kotatsu.parsers.model.YEAR_UNKNOWN
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.parseSafe
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.LinkedHashSet
import java.util.Locale

@MangaSourceParser("TOMILOLIB", "TomiloLib", "ru")
internal class TomiloLib(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.TOMILOLIB, pageSize = PAGE_SIZE) {

	override val configKeyDomain = ConfigKey.Domain("tomilo-lib.ru")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.POPULARITY,
		SortOrder.POPULARITY_TODAY,
		SortOrder.POPULARITY_WEEK,
		SortOrder.POPULARITY_MONTH,
		SortOrder.UPDATED,
		SortOrder.NEWEST,
		SortOrder.RATING,
		SortOrder.ALPHABETICAL,
		SortOrder.ALPHABETICAL_DESC,
	)

	override val defaultSortOrder: SortOrder = SortOrder.POPULARITY_WEEK

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isYearSupported = true,
		)

	private val filterOptions = suspendLazy(initializer = ::fetchFilterOptions)

	override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
		.add("Accept", "application/json")
		.add("Origin", "https://$domain")
		.add("Referer", "https://$domain/")
		.build()

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		val builder = request.newBuilder()
			.header("Referer", "https://$domain/")
		if (request.url.isImageUrl()) {
			builder.header("Accept", IMAGE_ACCEPT)
		}
		return chain.proceed(builder.build())
	}

	override suspend fun getFilterOptions(): MangaListFilterOptions = filterOptions.get()

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		if (filter.contentRating.isNotEmpty() && filter.contentRating.all { it == ContentRating.ADULT }) {
			return emptyList()
		}
		val url = apiUrl("titles").newBuilder()
			.addQueryParameter("page", page.toString())
			.addQueryParameter("limit", PAGE_SIZE.toString())
			.addQueryParameter("includeAdult", "false")
			.addQueryParameter("sortBy", order.toApiSortBy())
			.addQueryParameter("sortOrder", order.toApiSortOrder())
			.apply {
				filter.query?.trim()?.takeIf { it.isNotEmpty() }?.let { addQueryParameter("search", it) }
				filter.states.mapNotNullTo(LinkedHashSet()) { it.toApiStatus() }.forEach {
					addQueryParameter("status", it)
				}
				filter.types.mapNotNullTo(LinkedHashSet()) { it.toApiType() }.forEach {
					addQueryParameter("type", it)
				}
				if (filter.year != YEAR_UNKNOWN) {
					addQueryParameter("releaseYear", filter.year.toString())
				}
			}
			.build()

		val titles = webClient.httpGet(url, getRequestHeaders())
			.parseJson()
			.optJSONObject("data")
			?.optJSONArray("titles")
			?: JSONArray()

		val allowedRatings = filter.contentRating.takeIf { it.isNotEmpty() }
		val result = ArrayList<Manga>(titles.length())
		for (i in 0 until titles.length()) {
			parseManga(titles.optJSONObject(i), withDetails = false)?.let(result::add)
		}
		return result.filter { manga ->
			allowedRatings == null || manga.contentRating in allowedRatings
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val slug = manga.url.toAbsoluteUrl(domain).toHttpUrl().pathSegments.lastOrNull()
			?: throw ParseException("Cannot parse title slug", manga.url)
		val url = apiUrl("titles/slug/$slug").newBuilder()
			.addQueryParameter("populateChapters", "true")
			.build()
		val info = webClient.httpGet(url, getRequestHeaders())
			.parseJson()
			.optJSONObject("data")
			?: throw ParseException("Cannot parse title details", manga.url)
		if (info.isAdult()) {
			throw ParseException("Adult titles are not supported", manga.publicUrl)
		}

		val parsed = parseManga(info, withDetails = true) ?: manga
		val chapters = parseChapters(info, slug).takeIf { it.isNotEmpty() }
			?: fetchChapters(info.getStringOrNull("_id") ?: return parsed, slug)
		return parsed.copy(
			id = manga.id,
			url = manga.url,
			publicUrl = manga.publicUrl,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val url = chapter.url.toAbsoluteUrl(domain).toHttpUrl()
		val chapterId = url.pathSegments.lastOrNull()?.takeIf { it.isNotEmpty() }
			?: throw ParseException("Cannot parse chapter id", chapter.url)
		val pages = webClient.httpGet(apiUrl("chapters/$chapterId"), getRequestHeaders())
			.parseJson()
			.optJSONObject("data")
			?.optJSONArray("pages")
			?: throw ParseException("Cannot parse chapter pages", chapter.url)
		return (0 until pages.length()).mapNotNull { i ->
			val path = pages.optString(i).takeIf { it.isNotBlank() } ?: return@mapNotNull null
			MangaPage(
				id = generateUid("${chapter.url}#$i"),
				url = path.toCdnUrl(),
				preview = null,
				source = source,
			)
		}
	}

	private suspend fun fetchFilterOptions(): MangaListFilterOptions = MangaListFilterOptions(
		availableStates = EnumSet.of(
			MangaState.ONGOING,
			MangaState.FINISHED,
			MangaState.PAUSED,
			MangaState.ABANDONED,
		),
		availableContentRating = EnumSet.of(
			ContentRating.SAFE,
			ContentRating.SUGGESTIVE,
		),
		availableContentTypes = EnumSet.of(
			ContentType.MANGA,
			ContentType.MANHWA,
			ContentType.MANHUA,
			ContentType.COMICS,
		),
	)

	private suspend fun fetchChapters(titleId: String, slug: String): List<MangaChapter> {
		return parseChapters(fetchChapterJson(titleId), titleId, slug)
	}

	private suspend fun fetchChapterJson(titleId: String): JSONArray {
		return webClient.httpGet(
			apiUrl("chapters/title/$titleId").newBuilder()
				.addQueryParameter("page", "1")
				.addQueryParameter("limit", "10000")
				.addQueryParameter("sortOrder", "asc")
				.build(),
			getRequestHeaders(),
		).parseJson()
			.optJSONObject("data")
			?.optJSONArray("chapters")
			?: JSONArray()
	}

	private suspend fun parseManga(info: JSONObject?, withDetails: Boolean): Manga? {
		if (info == null || info.isAdult()) {
			return null
		}
		info.getStringOrNull("_id") ?: return null
		val slug = info.getStringOrNull("slug") ?: return null
		val title = info.getStringOrNull("name") ?: slug
		val url = "/titles/$slug"
		val cover = info.getStringOrNull("coverImage")?.toCdnUrl()
		val tags = LinkedHashSet<MangaTag>()
		info.optJSONArray("genres")?.addStringsTo(tags)
		info.optJSONArray("tags")?.addStringsTo(tags)
		val authors = LinkedHashSet<String>()
		info.getStringOrNull("author")?.splitTo(authors)
		info.getStringOrNull("artist")?.splitTo(authors)

		return Manga(
			id = generateUid(url),
			title = title,
			altTitles = parseStringSet(info.optJSONArray("altNames")),
			url = url,
			publicUrl = "https://$domain$url",
			rating = info.optDouble("averageRating", 0.0).takeIf { it > 0.0 }?.div(10.0)?.toFloat()
				?: RATING_UNKNOWN,
			contentRating = parseContentRating(info),
			coverUrl = cover,
			largeCoverUrl = cover,
			tags = tags,
			state = parseState(info.getStringOrNull("status")),
			authors = authors,
			description = if (withDetails) info.getStringOrNull("description") else null,
			source = source,
		)
	}

	private fun parseChapters(info: JSONObject, slug: String): List<MangaChapter> {
		val titleId = info.getStringOrNull("_id") ?: return emptyList()
		val chapters = info.optJSONArray("chapters") ?: return emptyList()
		if (chapters.length() == 0 || chapters.opt(0) !is JSONObject) {
			return emptyList()
		}
		return parseChapters(chapters, titleId, slug)
	}

	private fun parseChapters(chapters: JSONArray, titleId: String, slug: String): List<MangaChapter> {
		return (0 until chapters.length()).mapNotNull { i ->
			val ch = chapters.optJSONObject(i) ?: return@mapNotNull null
			if (!ch.optBoolean("isPublished", true)) {
				return@mapNotNull null
			}
			val chapterId = ch.getStringOrNull("_id") ?: return@mapNotNull null
			val number = ch.optDouble("chapterNumber", 0.0).toFloat()
			val url = "/titles/$slug/chapter/$chapterId?titleId=$titleId"
			MangaChapter(
				id = generateUid(url),
				title = buildChapterTitle(number, ch.getStringOrNull("name")),
				number = number,
				volume = 0,
				url = url,
				scanlator = null,
				uploadDate = parseDate(ch.getStringOrNull("releaseDate") ?: ch.getStringOrNull("createdAt")),
				branch = null,
				source = source,
			)
		}
	}

	private fun JSONArray.addStringsTo(destination: MutableSet<MangaTag>) {
		for (i in 0 until length()) {
			val value = optString(i).trim()
			if (value.isNotEmpty() && !value.isAdultGenre()) {
				destination += MangaTag(
					key = value,
					title = value,
					source = source,
				)
			}
		}
	}

	private fun parseStringSet(items: JSONArray?): Set<String> {
		if (items == null) {
			return emptySet()
		}
		val result = LinkedHashSet<String>(items.length())
		for (i in 0 until items.length()) {
			val value = items.optString(i).trim()
			if (value.isNotEmpty()) {
				result += value
			}
		}
		return result
	}

	private fun JSONObject.isAdult(): Boolean {
		return optBoolean("isAdult", false) || optInt("ageLimit", 0) >= 18
	}

	private fun parseContentRating(info: JSONObject): ContentRating {
		val ageLimit = info.optInt("ageLimit", 0)
		return when {
			info.optBoolean("isAdult", false) || ageLimit >= 18 -> ContentRating.ADULT
			ageLimit >= 16 -> ContentRating.SUGGESTIVE
			else -> ContentRating.SAFE
		}
	}

	private fun parseState(raw: String?): MangaState? = when (raw?.lowercase(Locale.ROOT)) {
		"ongoing" -> MangaState.ONGOING
		"completed" -> MangaState.FINISHED
		"pause" -> MangaState.PAUSED
		"cancelled" -> MangaState.ABANDONED
		else -> null
	}

	private fun buildChapterTitle(number: Float, name: String?): String? {
		val title = name?.trim().orEmpty()
		if (title.isEmpty()) {
			return if (number > 0f) "Глава ${number.toCleanString()}" else null
		}
		return title
	}

	private fun parseDate(raw: String?): Long {
		val value = raw?.trim().orEmpty()
		if (value.isEmpty()) {
			return 0L
		}
		for (pattern in DATE_PATTERNS) {
			val parsed = SimpleDateFormat(pattern, Locale.ROOT).parseSafe(value)
			if (parsed > 0L) {
				return parsed
			}
		}
		return 0L
	}

	private fun String.splitTo(destination: MutableSet<String>) {
		split(',', ';', '/')
			.map { it.trim() }
			.filterTo(destination) { it.isNotEmpty() }
	}

	private fun String.isAdultGenre(): Boolean {
		return lowercase(Locale.ROOT) in ADULT_GENRES
	}

	/**
	 * Maps a relative storage path from the API (e.g. `/uploads/titles/{id}/cover.jpg` or
	 * `/chapters/{id}/001.webp`) to its absolute CDN URL. The API already encodes the correct
	 * file extension and page names, so no probing is required.
	 */
	private fun String.toCdnUrl(): String {
		if (startsWith("http://", ignoreCase = true) || startsWith("https://", ignoreCase = true)) {
			return this
		}
		val path = removePrefix("/uploads").let { if (it.startsWith("/")) it else "/$it" }
		return "$CDN_URL$path"
	}

	private fun HttpUrl.isImageUrl(): Boolean {
		return host == "s3.regru.cloud" && encodedPath.startsWith("/tomilolib/")
	}

	private fun apiUrl(path: String) = "https://$domain/api/$path".toHttpUrl()

	private fun SortOrder.toApiSortBy(): String = when (this) {
		SortOrder.POPULARITY_TODAY -> "dayViews"
		SortOrder.POPULARITY_MONTH -> "monthViews"
		SortOrder.POPULARITY, SortOrder.POPULARITY_WEEK -> "weekViews"
		SortOrder.UPDATED -> "updatedAt"
		SortOrder.NEWEST -> "createdAt"
		SortOrder.RATING -> "averageRating"
		SortOrder.ALPHABETICAL, SortOrder.ALPHABETICAL_DESC -> "name"
		else -> "weekViews"
	}
	private fun SortOrder.toApiSortOrder(): String = when (this) {
		SortOrder.ALPHABETICAL -> "asc"
		else -> "desc"
	}

	private fun MangaState.toApiStatus(): String? = when (this) {
		MangaState.ONGOING -> "ongoing"
		MangaState.FINISHED -> "completed"
		MangaState.PAUSED -> "pause"
		MangaState.ABANDONED -> "cancelled"
		else -> null
	}

	private fun ContentType.toApiType(): String? = when (this) {
		ContentType.MANGA -> "manga"
		ContentType.MANHWA -> "manhwa"
		ContentType.MANHUA -> "manhua"
		ContentType.COMICS -> "comic"
		else -> null
	}

	private fun Float.toCleanString(): String {
		return if (this % 1f == 0f) {
			toInt().toString()
		} else {
			toString()
		}
	}

	private companion object {
		private const val PAGE_SIZE = 24
		private const val CDN_URL = "https://s3.regru.cloud/tomilolib"
		private const val IMAGE_ACCEPT = "image/avif,image/webp,image/png,image/svg+xml,image/*;q=0.8,*/*;q=0.5"
		private val DATE_PATTERNS = listOf(
			"yyyy-MM-dd'T'HH:mm:ss.SSSX",
			"yyyy-MM-dd'T'HH:mm:ssX",
		)
		private val ADULT_GENRES = setOf(
			"изнасилование",
			"секс",
			"хентай",
			"эротика",
			"этти",
		)
	}
}
