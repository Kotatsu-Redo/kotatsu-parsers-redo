package org.koitharu.kotatsu.parsers.site.id

import okhttp3.Headers
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("SHINIGAMI", "Shinigami", "id")
internal class Shinigami(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.SHINIGAMI, 24) {

	override val configKeyDomain = ConfigKey.Domain("s1.shinigami.mx")
	private val apiSuffix = "api.shinigami.io/v1"
	private val cdnSuffix = "cdn.shinigami.gg"

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override fun getRequestHeaders(): Headers = Headers.Builder()
		.add("referer", "https://$domain/")
		.add("sec-fetch-dest", "empty")
		.build()

	override suspend fun resolveLink(resolver: LinkResolver, link: okhttp3.HttpUrl): Manga? {
		val mangaId = link.pathSegments.getOrNull(1)
			?.takeIf { link.pathSegments.firstOrNull() == "series" }
			?: return null
		val json = webClient.httpGet("https://$apiSuffix/manga/detail/$mangaId").parseJson()
		val jo = json.getJSONObject("data")
		val id = jo.getString("manga_id")
		return Manga(
			id = generateUid(id),
			url = "/manga/detail/$id",
			publicUrl = "https://$domain/series/$id",
			title = jo.getString("title"),
			altTitles = jo.getStringOrNull("alternative_title")
				?.split('|')
				?.map(String::trim)
				?.filter(String::isNotEmpty)
				?.toSet().orEmpty(),
			coverUrl = jo.getStringOrNull("cover_image_url"),
			largeCoverUrl = jo.getStringOrNull("cover_portrait_url")?.takeIf(String::isNotEmpty),
			description = jo.getStringOrNull("description"),
			authors = jo.optJSONObject("taxonomy")?.optJSONArray("Author")?.mapJSONToSet { it.getString("name") }.orEmpty(),
			tags = jo.optJSONObject("taxonomy")?.optJSONArray("Genre")?.mapJSONToSet {
				MangaTag(it.getString("slug"), it.getString("name"), source)
			}.orEmpty(),
			state = when (jo.getIntOrDefault("status", 0)) {
				1 -> MangaState.ONGOING
				2 -> MangaState.FINISHED
				3 -> MangaState.PAUSED
				else -> null
			},
			contentRating = null,
			rating = RATING_UNKNOWN,
			chapters = null,
			source = source,
		)
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.POPULARITY,
		SortOrder.POPULARITY_ASC,
		SortOrder.NEWEST,
		SortOrder.NEWEST_ASC,
		SortOrder.RATING,
		SortOrder.RATING_ASC,
	)

	override suspend fun getFilterOptions(): MangaListFilterOptions {
		return MangaListFilterOptions(
			availableTags = fetchTags(),
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
	}

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = true,
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
		)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://")
			append(apiSuffix)
			append("/manga/list")
			append("?offset=")
			append(((page - 1) * pageSize).toString())
			append("&limit=")
			append(pageSize)

			append("&sort=")
			append(
				when (order) {
					SortOrder.POPULARITY, SortOrder.POPULARITY_ASC -> "popularity"
					SortOrder.NEWEST, SortOrder.NEWEST_ASC -> "latest"
					SortOrder.RATING, SortOrder.RATING_ASC -> "rating"
					else -> "latest"
				},
			)
			append("&sort_order=")
			append(
				when (order) {
					SortOrder.POPULARITY_ASC, SortOrder.NEWEST_ASC, SortOrder.RATING_ASC -> "asc"
					else -> "desc"
				},
			)

			filter.states.oneOrThrowIfMany()?.let {
				append("&status=")
				append(
					when (it) {
						MangaState.FINISHED -> "completed"
						MangaState.ONGOING -> "ongoing"
						MangaState.PAUSED -> "hiatus"
						else -> ""
					},
				)
			}

			if (filter.types.isNotEmpty()) {
				filter.types.forEach {
					append("&format=")
					append(
						when (it) {
							ContentType.MANGA -> "manga"
							ContentType.MANHUA -> "manhua"
							ContentType.MANHWA -> "manhwa"
							else -> ""
						},
					)
				}
			}

			if (filter.tags.isNotEmpty()) {
				append("&genre=")
				filter.tags.joinTo(this, separator = ",") { it.key }
			}

			if (filter.tagsExclude.isNotEmpty()) {
				append("&genre_exclude=")
				filter.tagsExclude.joinTo(this, separator = ",") { it.key }
			}

			if (!filter.author.isNullOrEmpty()) {
				append("&author=")
				append(filter.author.urlEncoded())
			}

			if (!filter.query.isNullOrEmpty()) {
				append("&q=")
				append(filter.query.urlEncoded())
			}
		}

		val json = webClient.httpGet(url).parseJson()
		val data = json.getJSONArray("data")

		return data.mapJSON { jo ->
			val id = jo.getString("manga_id")
			Manga(
				id = generateUid(id),
				url = "/manga/detail/$id",
				publicUrl = "https://$domain/series/$id",
				title = jo.getString("title"),
				altTitles = jo.getStringOrNull("alternative_title")
					?.split('|')
					?.map(String::trim)
					?.filter(String::isNotEmpty)
					?.toSet().orEmpty(),
				coverUrl = jo.getStringOrNull("cover_image_url"),
				largeCoverUrl = jo.getStringOrNull("cover_portrait_url")?.takeIf(String::isNotEmpty),
				authors = jo.optJSONObject("taxonomy")
					?.optJSONArray("Author")
					?.mapJSONToSet { x ->
						x.getString("name")
					}.orEmpty(),
				tags = jo.optJSONObject("taxonomy")
					?.optJSONArray("Genre")
					?.mapJSONToSet { x ->
						MangaTag(
							key = x.getString("slug"),
							title = x.getString("name").toTitleCase(sourceLocale),
							source = source,
						)
					}.orEmpty(),
				state = when (jo.getIntOrDefault("status", 0)) {
					1 -> MangaState.ONGOING
					2 -> MangaState.FINISHED
					3 -> MangaState.PAUSED
					else -> null
				},
				description = jo.getStringOrNull("description"),
				contentRating = null,
				source = source,
				rating = RATING_UNKNOWN,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val json = webClient.httpGet("https://$apiSuffix${manga.url}").parseJson()
		val jo = json.getJSONObject("data")
		val id = jo.getString("manga_id")

		return manga.copy(
			title = jo.getString("title"),
			altTitles = jo.getStringOrNull("alternative_title")
				?.split('|')
				?.map(String::trim)
				?.filter(String::isNotEmpty)
				?.toSet().orEmpty(),
			coverUrl = jo.getStringOrNull("cover_image_url"),
			tags = jo.optJSONObject("taxonomy")
				?.optJSONArray("Genre")
				?.mapJSONToSet { x ->
					MangaTag(
						key = x.getString("slug"),
						title = x.getString("name"),
						source = source,
					)
				} ?: manga.tags,
			authors = jo.optJSONObject("taxonomy")
				?.optJSONArray("Author")
				?.mapJSONToSet { x ->
					x.getString("name")
				} ?: manga.authors,
			state = when (jo.getIntOrDefault("status", 0)) {
				1 -> MangaState.ONGOING
				2 -> MangaState.FINISHED
				3 -> MangaState.PAUSED
				else -> null
			},
			description = jo.getStringOrNull("description"),
			largeCoverUrl = jo.getStringOrNull("cover_portrait_url")?.takeIf(String::isNotEmpty),
			chapters = getChapters(id),
		)
	}

	private suspend fun getChapters(mangaId: String): List<MangaChapter> {
		val chapters = ArrayList<MangaChapter>()
		var cursor: String? = null
		var hasNext: Boolean
		do {
			val url = buildString {
				append("https://$apiSuffix/chapter/$mangaId/list?limit=100&sort=chapter_number&sort_order=asc")
				cursor?.let { append("&cursor=").append(it) }
			}
			val json = webClient.httpGet(url).parseJson()
			val data = json.getJSONArray("data")
			chapters.addAll(data.mapJSON { jo ->
				val chapterId = jo.getString("chapter_id")
				val title = jo.getStringOrNull("chapter_title")?.takeIf(String::isNotEmpty)

				MangaChapter(
					id = generateUid(chapterId),
					title = title,
					number = jo.getFloatOrDefault("chapter_number", 0f),
					url = "chapter/detail/$chapterId",
					scanlator = null,
					uploadDate = jo.getString("release_date").parseDate(),
					branch = null,
					source = source,
					volume = 0,
				)
			})
			val meta = json.optJSONObject("meta")
			cursor = meta?.getStringOrNull("next_cursor")
			hasNext = meta?.optBoolean("has_next") == true && !cursor.isNullOrEmpty() && data.length() > 0
		} while (hasNext)
		return chapters
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val json = webClient.httpGet("https://$apiSuffix/${chapter.url}").parseJson()
		val data = json.getJSONObject("data")
		val chapterData = data.getJSONObject("chapter")
		val basePath = chapterData.getString("path")
		val baseUrl = (data.getStringOrNull("base_url_low") ?: data.getStringOrNull("base_url"))
			?.trimEnd('/') ?: "https://$cdnSuffix"
		val datas = chapterData.getJSONArray("data")

		return List(datas.length()) { i ->
			val imgExt = datas.getString(i)
			val imageUrl = "$baseUrl$basePath$imgExt"

			MangaPage(
				id = generateUid(imageUrl),
				url = imageUrl,
				preview = null,
				source = source,
			)
		}
	}

	private suspend fun fetchTags(): Set<MangaTag> {
		val json = webClient.httpGet("https://$apiSuffix/taxonomy/list?type=genre&limit=100").parseJson()

		return json.getJSONArray("data").mapJSONToSet { x ->
			MangaTag(
				key = x.getString("slug"),
				title = x.getString("name").toTitleCase(sourceLocale),
				source = source,
			)
		}
	}

	private fun String.parseDate(): Long {
		return try {
			SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
				.apply {
					timeZone = TimeZone.getTimeZone("UTC")
				}
				.parse(this)?.time ?: 0L
		} catch (e: Exception) {
			0L
		}
	}
}
