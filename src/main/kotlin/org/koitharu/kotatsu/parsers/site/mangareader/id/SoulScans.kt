package org.koitharu.kotatsu.parsers.site.mangareader.id

import okhttp3.Headers
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("SOULSCANS", "SoulScans", "id")
internal class SoulScans(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.SOULSCANS, pageSize = 30, searchPageSize = 30) {

	override val configKeyDomain = ConfigKey.Domain("v1.soulscans.org")
	override val sourceLocale: Locale = Locale.ENGLISH

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

	override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions(
		availableTags = getGenres().values.toSet(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED, MangaState.PAUSED),
		availableContentTypes = EnumSet.of(ContentType.MANGA, ContentType.MANHWA, ContentType.MANHUA),
	)

	private var genres: Map<String, MangaTag>? = null

	private suspend fun getGenres(): Map<String, MangaTag> {
		genres?.let { return it }
		val array = JSONArray(webClient.httpGet("$API_BASE_URL/genres").body?.string().orEmpty())
		val result = buildMap {
			for (i in 0 until array.length()) {
				val obj = array.optJSONObject(i) ?: continue
				val slug = obj.optString("slug")
				val name = obj.optString("name")
				if (slug.isNotEmpty() && name.isNotEmpty()) {
					put(slug, MangaTag(title = name, key = slug, source = source))
				}
			}
		}
		genres = result
		return result
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
		val url = buildString {
			append(API_BASE_URL).append("/search?type=COMIC")
			append("&limit=").append(pageSize)
			append("&page=").append(page)
			append("&sort=").append(apiSort)
			append("&order=").append(apiOrder)
			filter.query?.takeIf(String::isNotBlank)?.let { append("&q=").append(it.urlEncoded()) }
			filter.tags.firstOrNull()?.let { append("&genre=").append(it.key.urlEncoded()) }
			filter.states.oneOrThrowIfMany()?.let {
				append("&status=").append(
					when (it) {
						MangaState.ONGOING -> "ONGOING"
						MangaState.FINISHED -> "COMPLETED"
						MangaState.PAUSED -> "HIATUS"
						else -> ""
					},
				)
			}
			filter.types.oneOrThrowIfMany()?.let {
				append("&comic_type=").append(
					when (it) {
						ContentType.MANGA -> "MANGA"
						ContentType.MANHWA -> "MANHWA"
						ContentType.MANHUA -> "MANHUA"
						else -> ""
					},
				)
			}
			filter.author?.takeIf(String::isNotBlank)?.let {
				append("&author=").append(it.urlEncoded())
			}
		}

		val data = JSONObject(webClient.httpGet(url).body?.string().orEmpty()).optJSONArray("data")
			?: return emptyList()
		val result = buildList {
			for (i in 0 until data.length()) {
				parseManga(data.optJSONObject(i) ?: continue)?.let(::add)
			}
		}
		return when (order) {
			SortOrder.ALPHABETICAL -> result.sortedBy { it.title.lowercase(sourceLocale) }
			SortOrder.ALPHABETICAL_DESC -> result.sortedByDescending { it.title.lowercase(sourceLocale) }
			else -> result
		}
	}

	private fun parseManga(obj: JSONObject): Manga? {
		val slug = obj.optString("slug").takeIf(String::isNotEmpty) ?: return null
		val relativeUrl = "/comic/$slug"
		return Manga(
			id = generateUid(relativeUrl),
			url = relativeUrl,
			title = obj.optString("title").ifEmpty { slug },
			altTitles = parseAlternativeTitles(obj),
			publicUrl = "https://$domain$relativeUrl",
			rating = parseRating(obj),
			contentRating = parseContentRating(obj),
			coverUrl = obj.optString("poster_image_url").nullIfEmpty(),
			tags = emptySet(),
			state = parseState(obj.optString("comic_status")),
			authors = setOfNotNull(obj.optString("author_name").nullIfEmpty()),
			source = source,
		)
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val slug = manga.url.substringBefore('?').trimEnd('/').substringAfterLast('/')
		val obj = JSONObject(
			webClient.httpGet("$API_BASE_URL/series/comic/${slug.urlEncoded()}").body?.string().orEmpty(),
		)
		val relativeUrl = "/comic/$slug"
		val tags = obj.optJSONArray("genres")?.let { array ->
			buildSet {
				for (i in 0 until array.length()) {
					val genre = array.optJSONObject(i) ?: continue
					val key = genre.optString("slug")
					val title = genre.optString("name")
					if (key.isNotEmpty() && title.isNotEmpty()) {
						add(MangaTag(title = title, key = key, source = source))
					}
				}
			}
		}.orEmpty()

		return manga.copy(
			url = relativeUrl,
			publicUrl = "https://$domain$relativeUrl",
			title = obj.optString("title").ifEmpty { manga.title },
			altTitles = parseAlternativeTitles(obj),
			description = obj.optString("synopsis").nullIfEmpty(),
			rating = parseRating(obj),
			contentRating = parseContentRating(obj),
			coverUrl = obj.optString("poster_image_url").nullIfEmpty() ?: manga.coverUrl,
			state = parseState(obj.optString("comic_status")),
			authors = setOfNotNull(obj.optString("author_name").nullIfEmpty()),
			tags = tags,
			chapters = parseChapters(obj.optJSONArray("units"), slug),
		)
	}

	private fun parseChapters(array: JSONArray?, seriesSlug: String): List<MangaChapter> {
		if (array == null) return emptyList()
		return buildList {
			for (i in 0 until array.length()) {
				val obj = array.optJSONObject(i) ?: continue
				val chapterSlug = obj.optString("slug").takeIf(String::isNotEmpty) ?: continue
				val chapterUrl = "/comic/$seriesSlug/chapter/$chapterSlug"
				add(
					MangaChapter(
						id = generateUid(chapterUrl),
						url = chapterUrl,
						title = obj.optString("title").nullIfEmpty(),
						number = obj.optString("number").toFloatOrNull() ?: 0f,
						volume = 0,
						branch = null,
						scanlator = null,
						uploadDate = parseChapterDate(obj.optString("created_at")),
						source = source,
					),
				)
			}
		}.reversed()
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val seriesSlug = chapter.url.substringAfter("/comic/").substringBefore("/chapter/")
		val chapterSlug = chapter.url.substringAfter("/chapter/")
		val url = "$API_BASE_URL/series/comic/${seriesSlug.urlEncoded()}/chapter/${chapterSlug.urlEncoded()}"
		val pages = JSONObject(webClient.httpGet(url).body?.string().orEmpty())
			.optJSONObject("chapter")?.optJSONArray("pages") ?: return emptyList()
		return buildList {
			for (i in 0 until pages.length()) {
				val imageUrl = pages.optJSONObject(i)?.optString("image_url")?.nullIfEmpty() ?: continue
				add(
					MangaPage(
						id = generateUid(imageUrl),
						url = imageUrl,
						preview = null,
						source = source,
					),
				)
			}
		}
	}

	private fun parseAlternativeTitles(obj: JSONObject): Set<String> =
		when (val value = obj.opt("alternative_titles")) {
			is JSONArray -> buildSet {
				for (i in 0 until value.length()) {
					value.optString(i).trim().takeIf(String::isNotEmpty)?.let(::add)
				}
			}
			is String -> value.split(',').mapNotNullTo(mutableSetOf()) {
				it.trim().takeIf(String::isNotEmpty)
			}
			else -> emptySet()
		}

	private fun parseRating(obj: JSONObject): Float {
		val value = obj.optString("rating_average").toFloatOrNull() ?: return RATING_UNKNOWN
		return if (value > 0f) value.div(10f).coerceIn(0f, 1f) else RATING_UNKNOWN
	}

	private fun parseContentRating(obj: JSONObject): ContentRating =
		if (obj.optInt("is_adult") == 1 || obj.optBoolean("is_adult")) {
			ContentRating.ADULT
		} else {
			ContentRating.SAFE
		}

	private fun parseState(value: String): MangaState? = when (value.uppercase(Locale.ENGLISH)) {
		"ONGOING" -> MangaState.ONGOING
		"COMPLETED", "FINISHED" -> MangaState.FINISHED
		"HIATUS", "PAUSED" -> MangaState.PAUSED
		"CANCELLED" -> MangaState.ABANDONED
		else -> null
	}

	private fun parseChapterDate(value: String): Long = try {
		SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ENGLISH).parse(value)?.time ?: 0L
	} catch (_: Exception) {
		0L
	}

	private companion object {
		const val API_BASE_URL = "https://img.soulscans.org/api"
	}
}
