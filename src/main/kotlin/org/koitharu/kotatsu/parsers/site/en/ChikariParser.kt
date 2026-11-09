package org.koitharu.kotatsu.parsers.site.en

import okhttp3.HttpUrl
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
import org.koitharu.kotatsu.parsers.model.YEAR_UNKNOWN
import org.koitharu.kotatsu.parsers.util.LinkResolver
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.json.getBooleanOrDefault
import org.koitharu.kotatsu.parsers.util.json.getDoubleOrDefault
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.json.mapJSONNotNull
import org.koitharu.kotatsu.parsers.util.json.mapJSONNotNullToSet
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.oneOrThrowIfMany
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.parseRaw
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import java.math.BigDecimal
import java.time.Instant
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("CHIKARI", "Chikari", "en")
internal class ChikariParser(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.CHIKARI, PAGE_SIZE) {

	override val configKeyDomain = ConfigKey.Domain("chikari.moe")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.RATING,
		SortOrder.NEWEST,
	)

	override val filterCapabilities = MangaListFilterCapabilities(
		isMultipleTagsSupported = true,
		isSearchSupported = true,
		isSearchWithFiltersSupported = true,
		isYearSupported = true,
	)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override fun getRequestHeaders() = super.getRequestHeaders().newBuilder()
		.add("Accept", "application/json")
		.add("Referer", "https://$domain/")
		.build()

	override suspend fun getFilterOptions(): MangaListFilterOptions {
		val genres = JSONArray(webClient.httpGet(apiUrl("genres")).parseRaw())
			.mapJSONNotNullToSet { genre ->
				val slug = genre.getStringOrNull("slug") ?: return@mapJSONNotNullToSet null
				val name = genre.getStringOrNull("name") ?: return@mapJSONNotNullToSet null
				MangaTag(slug, name, source)
			}

		return MangaListFilterOptions(
			availableTags = genres,
			availableStates = EnumSet.of(
				MangaState.ONGOING,
				MangaState.FINISHED,
				MangaState.PAUSED,
				MangaState.ABANDONED,
			),
			availableContentRating = EnumSet.of(ContentRating.SAFE, ContentRating.ADULT),
			availableContentTypes = EnumSet.of(
				ContentType.MANGA,
				ContentType.MANHWA,
				ContentType.MANHUA,
				ContentType.OTHER,
			),
		)
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = apiUrl("series").toHttpUrl().newBuilder().apply {
			addQueryParameter("limit", pageSize.toString())
			addQueryParameter("offset", ((page - 1).coerceAtLeast(0) * pageSize).toString())
			addQueryParameter("sort", order.toApiSort())
			addQueryParameter(
				"adult",
				(filter.contentRating.oneOrThrowIfMany() == ContentRating.ADULT).toString(),
			)

			filter.query?.trim()?.nullIfEmpty()?.let { addQueryParameter("q", it) }
			filter.tags.forEach { addQueryParameter("genre", it.key) }
			filter.states.forEach { state ->
				state.toApiStatus()?.let { addQueryParameter("status", it) }
			}
			filter.types.forEach { type ->
				type.toApiType()?.let { addQueryParameter("type", it) }
			}
			if (filter.year != YEAR_UNKNOWN) {
				addQueryParameter("year", filter.year.toString())
			}
		}.build()

		return webClient.httpGet(url.toString()).parseJson()
			.getJSONArray("items")
			.mapJSONNotNull { it.toManga() }
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val slug = manga.slug()
		val details = webClient.httpGet(apiUrl("series/$slug")).parseJson()
		val parsed = details.toManga() ?: manga
		val chapters = fetchAllChapters(slug, details.getBooleanOrDefault("multi_scan", false))

		return parsed.copy(chapters = chapters)
	}

	private suspend fun fetchAllChapters(slug: String, multipleScans: Boolean): List<MangaChapter> {
		val chapters = ArrayList<JSONObject>()
		var offset = 0
		var total: Int
		do {
			val url = apiUrl("series/$slug/chapters").toHttpUrl().newBuilder()
				.addQueryParameter("limit", CHAPTER_PAGE_SIZE.toString())
				.addQueryParameter("offset", offset.toString())
				.build()
			val response = webClient.httpGet(url.toString()).parseJson()
			val items = response.getJSONArray("items")
			total = response.optInt("total", items.length())
			for (index in 0 until items.length()) {
				chapters.add(items.getJSONObject(index))
			}
			offset += items.length()
		} while (items.length() > 0 && offset < total)

		return chapters
			.flatMap { chapter -> chapter.toChapters(slug, multipleScans) }
			.groupBy { it.branch }
			.values
			.sortedByDescending { it.size }
			.flatMap { branch -> branch.sortedBy { it.number } }
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val json = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseJson()
		val pages = json.getJSONArray("pages")
		return buildList(pages.length()) {
			for (index in 0 until pages.length()) {
				val url = pages.optString(index).nullIfEmpty() ?: continue
				add(
					MangaPage(
						id = generateUid(url),
						url = url,
						preview = null,
						source = source,
					),
				)
			}
		}
	}

	override suspend fun getPageUrl(page: MangaPage): String = page.url

	override suspend fun getRelatedManga(seed: Manga): List<Manga> {
		return JSONArray(webClient.httpGet(apiUrl("series/${seed.slug()}/similar")).parseRaw())
			.mapJSONNotNull { it.toManga() }
	}

	override suspend fun resolveLink(resolver: LinkResolver, link: HttpUrl): Manga? {
		if (link.host != domain || link.pathSegments.firstOrNull() != "series") return null
		val slug = link.pathSegments.getOrNull(1)?.nullIfEmpty() ?: return null
		return Manga(
			id = generateUid(slug),
			title = slug.replace('-', ' ').replaceFirstChar { it.titlecase(sourceLocale) },
			altTitles = emptySet(),
			url = "/series/$slug",
			publicUrl = "https://$domain/series/$slug",
			rating = RATING_UNKNOWN,
			contentRating = null,
			coverUrl = null,
			tags = emptySet(),
			state = null,
			authors = emptySet(),
			source = source,
		)
	}

	private fun JSONObject.toManga(): Manga? {
		val slug = getStringOrNull("slug") ?: return null
		val title = getStringOrNull("title") ?: return null
		val cover = getStringOrNull("cover_url")
		return Manga(
			id = generateUid(slug),
			title = title,
			altTitles = optJSONArray("alt_titles")?.stringSet().orEmpty()
				.filterNotTo(linkedSetOf()) { it.equals(title, ignoreCase = true) },
			url = "/series/$slug",
			publicUrl = "https://$domain/series/$slug",
			rating = getDoubleOrDefault("rating", 0.0)
				.takeIf { it > 0.0 }
				?.div(10.0)
				?.toFloat()
				?: RATING_UNKNOWN,
			contentRating = if (getBooleanOrDefault("is_nsfw", false)) {
				ContentRating.ADULT
			} else {
				ContentRating.SAFE
			},
			coverUrl = cover,
			largeCoverUrl = cover,
			tags = parseTags(),
			state = getStringOrNull("status").toMangaState(),
			authors = optJSONArray("authors")?.mapJSONNotNullToSet {
				it.getStringOrNull("name")
			}.orEmpty(),
			description = getStringOrNull("description"),
			source = source,
		)
	}

	private fun JSONObject.parseTags(): Set<MangaTag> = buildSet {
		optJSONArray("genres")?.mapJSONNotNull { genre ->
			val slug = genre.getStringOrNull("slug") ?: return@mapJSONNotNull null
			val name = genre.getStringOrNull("name") ?: return@mapJSONNotNull null
			MangaTag(slug, name, source)
		}?.let(::addAll)
	}

	private fun JSONObject.toChapters(slug: String, multipleScans: Boolean): List<MangaChapter> {
		val number = getDoubleOrDefault("number", 0.0)
		val numberString = number.toPlainString()
		val title = getStringOrNull("title")
		val volume = getStringOrNull("volume")?.toDoubleOrNull()?.toInt() ?: 0
		val scans = optJSONArray("scans")

		if (scans == null || scans.length() == 0) {
			return listOf(
				createChapter(
					slug = slug,
					number = number,
					numberString = numberString,
					volume = volume,
					title = title,
					uploadDate = getStringOrNull("created_at").parseDate(),
					scanSlug = null,
					scanName = null,
					branch = DEFAULT_BRANCH.takeIf { multipleScans },
				),
			)
		}

		return scans.mapJSONNotNull { scan ->
			val scanSlug = scan.getStringOrNull("slug") ?: return@mapJSONNotNull null
			val scanName = scan.getStringOrNull("name") ?: scanSlug
			createChapter(
				slug = slug,
				number = number,
				numberString = numberString,
				volume = volume,
				title = title,
				uploadDate = scan.getStringOrNull("created_at").parseDate(),
				scanSlug = scanSlug,
				scanName = scanName,
				branch = scanName.takeIf { multipleScans },
			)
		}
	}

	private fun createChapter(
		slug: String,
		number: Double,
		numberString: String,
		volume: Int,
		title: String?,
		uploadDate: Long,
		scanSlug: String?,
		scanName: String?,
		branch: String?,
	): MangaChapter {
		val url = apiUrl("series/$slug/chapters/$numberString").toHttpUrl().newBuilder().apply {
			scanSlug?.let { addQueryParameter("scan", it) }
		}.build().encodedPathWithQuery()
		return MangaChapter(
			id = generateUid(url),
			title = title,
			number = number.toFloat(),
			volume = volume,
			url = url,
			scanlator = scanName,
			uploadDate = uploadDate,
			branch = branch,
			source = source,
		)
	}

	private fun HttpUrl.encodedPathWithQuery(): String = buildString {
		append(encodedPath)
		encodedQuery?.let { append('?').append(it) }
	}

	private fun Manga.slug(): String = url.substringBefore('?').trimEnd('/').substringAfterLast('/')

	private fun apiUrl(path: String): String = "https://$domain/api/${path.trimStart('/')}"

	private fun JSONArray.stringSet(): Set<String> = buildSet {
		for (index in 0 until length()) {
			optString(index).nullIfEmpty()?.let(::add)
		}
	}

	private fun Double.toPlainString(): String = BigDecimal.valueOf(this).stripTrailingZeros().toPlainString()

	private fun String?.parseDate(): Long = this?.let {
		runCatching { Instant.parse(it).toEpochMilli() }.getOrDefault(0L)
	} ?: 0L

	private fun SortOrder.toApiSort(): String = when (this) {
		SortOrder.POPULARITY -> "popular"
		SortOrder.RATING -> "top_rated"
		SortOrder.NEWEST -> "added"
		else -> "updated"
	}

	private fun MangaState.toApiStatus(): String? = when (this) {
		MangaState.ONGOING -> "releasing"
		MangaState.FINISHED -> "completed"
		MangaState.PAUSED -> "hiatus"
		MangaState.ABANDONED -> "cancelled"
		else -> null
	}

	private fun String?.toMangaState(): MangaState? = when (this?.lowercase(Locale.ROOT)) {
		"releasing", "ongoing" -> MangaState.ONGOING
		"completed", "finished" -> MangaState.FINISHED
		"hiatus", "on_hiatus" -> MangaState.PAUSED
		"cancelled", "canceled" -> MangaState.ABANDONED
		else -> null
	}

	private fun ContentType.toApiType(): String? = when (this) {
		ContentType.MANGA -> "manga"
		ContentType.MANHWA -> "manhwa"
		ContentType.MANHUA -> "manhua"
		ContentType.OTHER -> "oel"
		else -> null
	}

	private companion object {
		const val PAGE_SIZE = 24
		const val CHAPTER_PAGE_SIZE = 200
		const val DEFAULT_BRANCH = "Default"
	}
}
