package org.koitharu.kotatsu.parsers.site.ru

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
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
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.parseSafe
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.toRelativeUrl
import org.koitharu.kotatsu.parsers.util.toTitleCase
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.json.mapJSONNotNullToSet
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.Locale


@MangaSourceParser("SENKURO", "Senkuro", "ru")
internal class SenkuroParser(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.SENKURO, pageSize = PAGE_SIZE) {

	override val configKeyDomain = ConfigKey.Domain("senkuro.com")

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
		.add("Content-Type", "application/json")
		.add("Origin", "https://$domain")
		.add("Referer", "https://$domain/")
		.build()

	private val graphQlEndpoint: String
		get() = if (domain == "senkuro.com") {
			"https://api.senkuro.com/graphql"
		} else {
			"https://api.senkuro.me/graphql"
		}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.POPULARITY,
		SortOrder.UPDATED,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = true,
		)

	@Volatile
	private var cachedTags: Set<MangaTag>? = null

	override suspend fun getFilterOptions(): MangaListFilterOptions {
		return MangaListFilterOptions(
			availableTags = fetchLabelTags(),
			availableStates = EnumSet.of(
				MangaState.ONGOING,
				MangaState.FINISHED,
				MangaState.PAUSED,
				MangaState.UPCOMING,
				MangaState.ABANDONED,
			),
			availableContentTypes = EnumSet.of(
				ContentType.MANGA,
				ContentType.MANHWA,
				ContentType.MANHUA,
				ContentType.COMICS,
			),
			availableContentRating = EnumSet.of(
				ContentRating.SAFE,
				ContentRating.SUGGESTIVE,
				ContentRating.ADULT,
			),
		)
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val includeLabels = filter.tags.map { it.key }.toMutableList()
		val excludeLabels = filter.tagsExclude.map { it.key }.toMutableList()
		if (ContentRating.ADULT !in filter.contentRating) {
			excludeLabels += DEFAULT_EXCLUDED_LABELS
		}

		val includeTypes = filter.types.mapNotNull { it.toApiType() }
		val includeStatuses = filter.states.mapNotNull { it.toApiStatus() }
		val includeRatings = filter.contentRating.flatMap { it.toApiRatings() }.distinct()

		val variables = JSONObject().apply {
			val query = filter.query?.trim().orEmpty()
			if (query.isNotEmpty()) {
				put("query", query)
			}
			put("offset", PAGE_SIZE * (page - 1))
			putFilter("label", includeLabels, excludeLabels)
			putFilter("type", includeTypes, emptyList())
			putFilter("status", includeStatuses, emptyList())
			putFilter("rating", includeRatings, emptyList())
			put("format", JSONObject())
			put("translationStatus", JSONObject())
		}

		val data = postGraphQl(SEARCH_QUERY, variables)
		val mangas = data
			.optJSONObject("mangaTachiyomiSearch")
			?.optJSONArray("mangas")
			?: JSONArray()
		return (0 until mangas.length()).mapNotNull { i ->
			parseMangaCard(mangas.optJSONObject(i))
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val (mangaId, mangaSlug) = parseMangaUrl(manga.url)
		val detailsData = postGraphQl(
			query = DETAILS_QUERY,
			variables = JSONObject().put("mangaId", mangaId),
		)
		val info = detailsData.optJSONObject("mangaTachiyomiInfo")
			?: throw ParseException("Cannot parse manga details", manga.publicUrl)

		val parsed = parseMangaInfo(info, manga.url, manga.publicUrl) ?: manga
		val chapters = fetchChapters(mangaId, mangaSlug)
		return manga.copy(
			title = parsed.title.ifBlank { manga.title },
			altTitles = if (parsed.altTitles.isNotEmpty()) parsed.altTitles else manga.altTitles,
			coverUrl = parsed.coverUrl ?: manga.coverUrl,
			largeCoverUrl = parsed.largeCoverUrl ?: manga.largeCoverUrl,
			description = parsed.description ?: manga.description,
			tags = if (parsed.tags.isNotEmpty()) parsed.tags else manga.tags,
			authors = if (parsed.authors.isNotEmpty()) parsed.authors else manga.authors,
			state = parsed.state ?: manga.state,
			contentRating = parsed.contentRating ?: manga.contentRating,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val parts = chapter.url.split(URL_DELIMITER)
		val mangaId = parts.getOrNull(0)?.takeIf { it.isNotBlank() }
			?: throw ParseException("Cannot parse manga id", chapter.url)
		val chapterId = parts.getOrNull(2)?.takeIf { it.isNotBlank() }
			?: throw ParseException("Cannot parse chapter id", chapter.url)

		val data = postGraphQl(
			query = CHAPTER_PAGES_QUERY,
			variables = JSONObject()
				.put("mangaId", mangaId)
				.put("chapterId", chapterId),
		)
		val pages = data
			.optJSONObject("mangaTachiyomiChapterPages")
			?.optJSONArray("pages")
			?: JSONArray()

		return (0 until pages.length()).mapNotNull { index ->
			val url = pages.optJSONObject(index)?.getStringOrNull("url")
				?.takeIf { it.isNotBlank() }
				?: return@mapNotNull null
			MangaPage(
				id = generateUid(url),
				url = url.toRelativeUrl(domain),
				preview = null,
				source = source,
			)
		}
	}

	private suspend fun fetchChapters(mangaId: String, mangaSlug: String): List<MangaChapter> {
		val data = postGraphQl(
			query = CHAPTERS_QUERY,
			variables = JSONObject().put("mangaId", mangaId),
		)
		val payload = data.optJSONObject("mangaTachiyomiChapters") ?: return emptyList()
		val chapters = payload.optJSONArray("chapters") ?: JSONArray()
		val teams = payload.optJSONArray("teams") ?: JSONArray()
		val teamsById = LinkedHashMap<String, String>(teams.length())
		for (i in 0 until teams.length()) {
			val team = teams.optJSONObject(i) ?: continue
			val id = team.getStringOrNull("id") ?: continue
			val name = team.getStringOrNull("name") ?: continue
			teamsById[id] = name
		}

		return (0 until chapters.length()).mapNotNull { i ->
			val ch = chapters.optJSONObject(i) ?: return@mapNotNull null
			val chapterId = ch.getStringOrNull("id") ?: return@mapNotNull null
			val chapterSlug = ch.getStringOrNull("slug") ?: return@mapNotNull null
			val chapterNumberRaw = ch.getStringOrNull("number")?.replace(',', '.')
			val chapterNumber = chapterNumberRaw?.toFloatOrNull() ?: 0f
			val volumeRaw = ch.getStringOrNull("volume")
			val volume = volumeRaw?.toIntOrNull() ?: 0
			val title = buildChapterTitle(volumeRaw, chapterNumberRaw, ch.getStringOrNull("name"))
			val scanlator = ch.optJSONArray("teamIds")
				?.let { ids ->
					(0 until ids.length())
						.mapNotNull { idx -> ids.optString(idx).takeIf(String::isNotBlank) }
						.mapNotNull { id -> teamsById[id] }
						.distinct()
						.joinToString()
						.takeIf { it.isNotBlank() }
				}
			val url = listOf(mangaId, mangaSlug, chapterId, chapterSlug).joinToString(URL_DELIMITER)
			MangaChapter(
				id = generateUid(url),
				title = title,
				number = chapterNumber,
				volume = volume,
				url = url,
				scanlator = scanlator,
				uploadDate = parseDate(ch.getStringOrNull("createdAt")),
				branch = null,
				source = source,
			)
		}.asReversed()
	}

	private suspend fun fetchLabelTags(): Set<MangaTag> {
		cachedTags?.let { return it }
		val data = postGraphQl(FILTERS_QUERY, JSONObject())
		val labels = data
			.optJSONObject("mangaTachiyomiSearchFilters")
			?.optJSONArray("labels")
			?: JSONArray()
		val tags = labels.mapJSONNotNullToSet { item ->
			val slug = item.getStringOrNull("slug")?.takeIf(String::isNotBlank) ?: return@mapJSONNotNullToSet null
			if (slug in DEFAULT_EXCLUDED_LABELS) {
				return@mapJSONNotNullToSet null
			}
			val title = pickLocalizedTitle(item.optJSONArray("titles"))
				?.toTitleCase()
				?: return@mapJSONNotNullToSet null
			MangaTag(
				key = slug,
				title = title,
				source = source,
			)
		}
		cachedTags = tags
		return tags
	}

	private fun parseMangaCard(info: JSONObject?): Manga? {
		if (info == null) return null
		val id = info.getStringOrNull("id") ?: return null
		val slug = info.getStringOrNull("slug") ?: return null
		val url = listOf(id, slug).joinToString(URL_DELIMITER)
		val title = pickLocalizedTitle(info.optJSONArray("titles"))
			?: info.getStringOrNull("slug")
			?: return null
		val cover = info.optJSONObject("cover")
			?.optJSONObject("original")
			?.getStringOrNull("url")
		return Manga(
			id = id.toLongOrNull() ?: generateUid(url),
			url = url,
			publicUrl = "https://$domain/manga/$slug",
			title = title,
			altTitles = emptySet(),
			rating = RATING_UNKNOWN,
			contentRating = parseContentRating(info.getStringOrNull("rating")),
			coverUrl = cover,
			largeCoverUrl = cover,
			tags = emptySet(),
			state = parseState(info.getStringOrNull("status")),
			authors = emptySet(),
			source = source,
		)
	}

	private fun parseMangaInfo(info: JSONObject, fallbackUrl: String, fallbackPublicUrl: String): Manga? {
		val id = info.getStringOrNull("id")
		val slug = info.getStringOrNull("slug")
		val url = if (!id.isNullOrBlank() && !slug.isNullOrBlank()) {
			listOf(id, slug).joinToString(URL_DELIMITER)
		} else {
			fallbackUrl
		}
		val publicUrl = if (!slug.isNullOrBlank()) {
			"https://$domain/manga/$slug"
		} else {
			fallbackPublicUrl
		}
		val title = pickLocalizedTitle(info.optJSONArray("titles")) ?: return null
		val altTitles = parseTitles(info.optJSONArray("alternativeNames"))
		val cover = info.optJSONObject("cover")
			?.optJSONObject("original")
			?.getStringOrNull("url")

		val description = buildString {
			if (altTitles.isNotEmpty()) {
				append("Альтернативные названия:\n")
				append(altTitles.joinToString(" / "))
			}
			val ruDescription = parseLocalizationDescription(info.optJSONArray("localizations"))
			if (!ruDescription.isNullOrBlank()) {
				if (isNotEmpty()) append("\n\n")
				append(ruDescription)
			}
		}.ifBlank { null }

		val authors = LinkedHashSet<String>(8)
		val staff = info.optJSONArray("mainStaff") ?: JSONArray()
		for (i in 0 until staff.length()) {
			val person = staff.optJSONObject(i) ?: continue
			val name = person.optJSONObject("person")?.getStringOrNull("name")?.trim().orEmpty()
			if (name.isNotEmpty()) {
				authors += name
			}
		}

		val tags = LinkedHashSet<MangaTag>(8)
		val labels = info.optJSONArray("labels") ?: JSONArray()
		for (i in 0 until labels.length()) {
			val label = labels.optJSONObject(i) ?: continue
			val slugValue = label.getStringOrNull("slug") ?: continue
			val titleValue = pickLocalizedTitle(label.optJSONArray("titles")) ?: continue
			tags += MangaTag(
				key = slugValue,
				title = titleValue.toTitleCase(),
				source = source,
			)
		}

		return Manga(
			id = id?.toLongOrNull() ?: generateUid(url),
			url = url,
			publicUrl = publicUrl,
			title = title,
			altTitles = altTitles,
			rating = RATING_UNKNOWN,
			contentRating = parseContentRating(info.getStringOrNull("rating")),
			coverUrl = cover,
			largeCoverUrl = cover,
			tags = tags,
			state = parseState(info.getStringOrNull("status")),
			authors = authors,
			description = description,
			source = source,
		)
	}

	private suspend fun postGraphQl(query: String, variables: JSONObject): JSONObject {
		val payload = JSONObject()
			.put("query", query)
			.put("variables", variables)
		val response = webClient.httpPost(graphQlEndpoint.toHttpUrl(), payload, getRequestHeaders()).parseJson()
		val errors = response.optJSONArray("errors")
		if (errors != null && errors.length() != 0) {
			throw ParseException(errors.toString(), graphQlEndpoint)
		}
		return response.optJSONObject("data")
			?: throw ParseException("GraphQL response has no data", graphQlEndpoint)
	}

	private fun parseMangaUrl(url: String): Pair<String, String> {
		val parts = url.split(URL_DELIMITER)
		val id = parts.getOrNull(0)?.takeIf { it.isNotBlank() }
			?: throw ParseException("Cannot parse manga id", url)
		val slug = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
			?: throw ParseException("Cannot parse manga slug", url)
		return id to slug
	}

	private fun parseState(raw: String?): MangaState? = when (raw?.uppercase(Locale.ROOT)) {
		"FINISHED" -> MangaState.FINISHED
		"ONGOING" -> MangaState.ONGOING
		"HIATUS" -> MangaState.PAUSED
		"ANNOUNCE" -> MangaState.UPCOMING
		"CANCELLED" -> MangaState.ABANDONED
		else -> null
	}

	private fun parseContentRating(raw: String?): ContentRating? = when (raw?.uppercase(Locale.ROOT)) {
		"EXPLICIT" -> ContentRating.ADULT
		"QUESTIONABLE", "SENSITIVE" -> ContentRating.SUGGESTIVE
		"GENERAL" -> ContentRating.SAFE
		else -> null
	}

	private fun parseTitles(items: JSONArray?): Set<String> {
		if (items == null) return emptySet()
		val result = LinkedHashSet<String>(items.length())
		for (i in 0 until items.length()) {
			val value = items.optJSONObject(i)?.getStringOrNull("content")?.trim().orEmpty()
			if (value.isNotEmpty()) {
				result += value
			}
		}
		return result
	}

	private fun pickLocalizedTitle(items: JSONArray?): String? {
		if (items == null || items.length() == 0) return null
		var first: String? = null
		var en: String? = null
		for (i in 0 until items.length()) {
			val item = items.optJSONObject(i) ?: continue
			val text = item.getStringOrNull("content")?.trim().orEmpty()
			if (text.isEmpty()) {
				continue
			}
			if (first == null) {
				first = text
			}
			when (item.getStringOrNull("lang")?.uppercase(Locale.ROOT)) {
				"RU" -> return text
				"EN" -> if (en == null) en = text
			}
		}
		return en ?: first
	}

	private fun parseLocalizationDescription(items: JSONArray?): String? {
		if (items == null || items.length() == 0) return null
		var first: String? = null
		for (i in 0 until items.length()) {
			val item = items.optJSONObject(i) ?: continue
			val text = item.getStringOrNull("description")?.trim().orEmpty()
			if (text.isEmpty()) {
				continue
			}
			if (first == null) {
				first = text
			}
			if (item.getStringOrNull("lang")?.uppercase(Locale.ROOT) == "RU") {
				return text
			}
		}
		return first
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

	private fun buildChapterTitle(volume: String?, number: String?, name: String?): String? {
		val v = volume?.trim().orEmpty()
		val n = number?.trim().orEmpty()
		val title = name?.trim().orEmpty()
		val built = buildString {
			if (v.isNotEmpty()) {
				append(v).append(". ")
			}
			if (n.isNotEmpty()) {
				append("Глава ").append(n)
			}
			if (title.isNotEmpty()) {
				if (isNotEmpty()) append(' ')
				append(title)
			}
		}
		return built.ifBlank { null }
	}

	private fun JSONObject.putFilter(key: String, include: List<String>, exclude: List<String>) {
		if (include.isEmpty() && exclude.isEmpty()) return
		put(
			key,
			JSONObject().apply {
				if (include.isNotEmpty()) put("include", JSONArray(include))
				if (exclude.isNotEmpty()) put("exclude", JSONArray(exclude))
			},
		)
	}

	private fun MangaState.toApiStatus(): String? = when (this) {
		MangaState.UPCOMING -> "ANNOUNCE"
		MangaState.ONGOING -> "ONGOING"
		MangaState.FINISHED -> "FINISHED"
		MangaState.PAUSED -> "HIATUS"
		MangaState.ABANDONED -> "CANCELLED"
		else -> null
	}

	private fun ContentType.toApiType(): String? = when (this) {
		ContentType.MANGA -> "MANGA"
		ContentType.MANHWA -> "MANHWA"
		ContentType.MANHUA -> "MANHUA"
		ContentType.COMICS -> "COMICS"
		else -> null
	}

	private fun ContentRating.toApiRatings(): List<String> = when (this) {
		ContentRating.SAFE -> listOf("GENERAL")
		ContentRating.SUGGESTIVE -> listOf("SENSITIVE", "QUESTIONABLE")
		ContentRating.ADULT -> listOf("EXPLICIT")
	}

	private companion object {
		private const val PAGE_SIZE = 20
		private const val URL_DELIMITER = ",,"
		private val DEFAULT_EXCLUDED_LABELS = listOf("hentai", "yaoi", "yuri", "shoujo_ai", "shounen_ai", "lgbt")
		private val DATE_PATTERNS = listOf(
			"yyyy-MM-dd'T'HH:mm:ss.SSSX",
			"yyyy-MM-dd'T'HH:mm:ss.SX",
			"yyyy-MM-dd'T'HH:mm:ss.SSS",
			"yyyy-MM-dd'T'HH:mm:ss.S",
			"yyyy-MM-dd'T'HH:mm:ss",
		)

		private fun gqlQuery(queryAction: () -> String): String = queryAction()
			.trimIndent()
			.replace("%", "$")

		private val SEARCH_QUERY: String = gqlQuery {
			"""
			query searchTachiyomiManga(
				%query: String,
				%type: MangaTachiyomiSearchTypeFilter,
				%status: MangaTachiyomiSearchStatusFilter,
				%translationStatus: MangaTachiyomiSearchTranslationStatusFilter,
				%label: MangaTachiyomiSearchLabelFilter,
				%format: MangaTachiyomiSearchFormatFilter,
				%rating: MangaTachiyomiSearchRatingFilter,
				%offset: Int
			) {
				mangaTachiyomiSearch(
					query: %query,
					type: %type,
					status: %status,
					translationStatus: %translationStatus,
					label: %label,
					format: %format,
					rating: %rating,
					offset: %offset
				) {
					mangas {
						id
						slug
						titles {
							lang
							content
						}
					cover {
						original {
							url
						}
					}
				}
			}
		}
			"""
		}

		private val DETAILS_QUERY: String = gqlQuery {
			"""
			query fetchTachiyomiManga(%mangaId: ID!) {
				mangaTachiyomiInfo(mangaId: %mangaId) {
					id
					slug
					titles {
						lang
						content
					}
					alternativeNames {
						lang
						content
					}
					localizations {
						lang
						description
					}
					type
					rating
					status
					formats
					labels {
						id
						rootId
						slug
						titles {
							lang
							content
						}
					}
					translationStatus
					cover {
						original {
							url
						}
					}
					mainStaff {
						roles
						person {
							name
						}
					}
				}
			}
			"""
		}

		private val CHAPTERS_QUERY: String = gqlQuery {
			"""
			query fetchTachiyomiChapters(%mangaId: ID!) {
				mangaTachiyomiChapters(mangaId: %mangaId) {
					message
					chapters {
						id
						slug
						branchId
						name
						teamIds
						number
						volume
						createdAt
					}
					teams {
						id
						slug
						name
					}
				}
			}
			"""
		}

		private val CHAPTER_PAGES_QUERY: String = gqlQuery {
			"""
			query fetchTachiyomiChapterPages(
				%mangaId: ID!,
				%chapterId: ID!
			) {
				mangaTachiyomiChapterPages(
					mangaId: %mangaId,
					chapterId: %chapterId
				) {
					pages {
						url
					}
				}
			}
			"""
		}

		private val FILTERS_QUERY: String = gqlQuery {
			"""
			query fetchTachiyomiSearchFilters {
				mangaTachiyomiSearchFilters {
					labels {
						id
						rootId
						slug
						titles {
							lang
							content
						}
					}
				}
			}
			"""
		}
	}
}
