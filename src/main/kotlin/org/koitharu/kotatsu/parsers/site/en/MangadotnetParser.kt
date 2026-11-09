package org.koitharu.kotatsu.parsers.site.en

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("MANGADOTNET", "Mangadotnet", "en", ContentType.MANGA)
internal class Mangadotnet(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.MANGADOTNET, pageSize = 56) {

	override val configKeyDomain = ConfigKey.Domain("mangadot.net")

	private val baseUrl: String
		get() = "https://$domain"

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = true,
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isYearRangeSupported = true,
			isAuthorSearchSupported = true,
		)

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.RATING,
		SortOrder.ALPHABETICAL,
		SortOrder.ALPHABETICAL_DESC,
		SortOrder.RELEVANCE,
	)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = tagsCache.get(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED, MangaState.PAUSED),
		availableContentRating = EnumSet.allOf(ContentRating::class.java),
		availableContentTypes = EnumSet.of(
			ContentType.MANGA,
			ContentType.MANHWA,
			ContentType.MANHUA,
			ContentType.ONE_SHOT,
		),
		availableDemographics = EnumSet.of(
			Demographic.SHOUNEN,
			Demographic.SHOUJO,
			Demographic.SEINEN,
			Demographic.JOSEI,
		),
	)

	private val tagsCache = suspendLazy(initializer = ::fetchTags)

	private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).apply {
		timeZone = TimeZone.getTimeZone("UTC")
	}

	// region RSC decoder

	private fun decodeRsc(flat: JSONArray): Any? {
		val cache = arrayOfNulls<Any>(flat.length())
		val nil = Object()

		fun resolve(i: Int): Any? {
			if (i < 0 || i >= flat.length()) return null
			val cached = cache[i]
			if (cached != null) return if (cached === nil) null else cached

			val result: Any? = when (val el = flat.opt(i)) {
				JSONObject.NULL -> null
				is String, is Number, is Boolean -> el
				is JSONArray -> {
					(0 until el.length()).mapTo(mutableListOf()) { j ->
						resolve(el.optInt(j, -1))
					}
				}

				is JSONObject -> {
					val map = mutableMapOf<String, Any?>()
					for (key in el.keys()) {
						val actualKey = if (key.startsWith("_")) {
							flat.optString(key.substring(1).toInt(), key)
						} else {
							key
						}
						map[actualKey] = resolve(el.optInt(key, -1))
					}
					map
				}

				else -> null
			}

			cache[i] = result ?: nil
			return result
		}

		return resolve(0)
	}

	/**
	 * Fetch an RSC-encoded URL and extract the given route.
	 * Route values are wrapped in `{"data": ...}` (Data<T>).
	 * Returns the inner `data` value of the route.
	 */
	@Suppress("UNCHECKED_CAST")
	private suspend fun fetchRscData(url: String, route: String): Map<String, Any?>? {
		val flat = webClient.httpGet(url).parseJsonArray()
		val decoded = decodeRsc(flat) ?: return null
		val routeValue = (decoded as? Map<String, Any?>)?.get(route) as? Map<String, Any?> ?: return null
		return routeValue.asMap("data")
	}

	// endregion

	// region Listing

	/**
	 * Both browsing and searching go through the plain JSON `/api/search` endpoint, which is the only
	 * one that accepts filters. It returns `{"results"|"manga_list": [...], "pagination": {...}}`.
	 */
	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val query = filter.query?.trim().orEmpty()
		val url = "$baseUrl/api/search".toHttpUrl().newBuilder().apply {
			addQueryParameter("page", page.toString())
			addQueryParameter("limit", pageSize.toString())
			if (query.isNotEmpty()) {
				addQueryParameter("search", query)
			}
			getSortKey(order, query)?.let { addQueryParameter("sortBy", it) }
			addQueryParameter("sortOrder", if (order == SortOrder.ALPHABETICAL) "asc" else "desc")

			val genres = ArrayList<String>()
			val tags = ArrayList<String>()
			filter.tags.forEach { it.appendTo(genres, tags, isExcluded = false) }
			filter.tagsExclude.forEach { it.appendTo(genres, tags, isExcluded = true) }
			filter.demographics.mapNotNullTo(genres) { it.toApiValue() }
			if (genres.isNotEmpty()) {
				addQueryParameter("genres", genres.joinToString(","))
			}
			if (tags.isNotEmpty()) {
				addQueryParameter("tags", tags.joinToString(","))
			}

			filter.states.oneOrThrowIfMany()?.toApiValue()?.let {
				addQueryParameter("status", it)
			}

			if (filter.contentRating.isNotEmpty()) {
				val ratings = filter.contentRating.flatMapTo(LinkedHashSet()) { it.toApiValues() }
				addQueryParameter("content_rating", ratings.joinToString(","))
			}

			if (filter.types.isNotEmpty()) {
				val origins = filter.types.mapNotNullTo(LinkedHashSet()) { it.toApiValue() }
				if (origins.isNotEmpty()) {
					addQueryParameter("origin", origins.joinToString(","))
				}
			}

			val yearFrom = filter.year.takeUnless { it == YEAR_UNKNOWN } ?: filter.yearFrom
			val yearTo = filter.year.takeUnless { it == YEAR_UNKNOWN } ?: filter.yearTo
			if (yearFrom != YEAR_UNKNOWN) {
				addQueryParameter("year_min", yearFrom.toString())
			}
			if (yearTo != YEAR_UNKNOWN) {
				addQueryParameter("year_max", yearTo.toString())
			}

			filter.author?.trim()?.takeIf { it.isNotEmpty() }?.let {
				addQueryParameter("author", it)
			}
		}.build()

		val json = webClient.httpGet(url).parseJson()
		val list = json.optJSONArray("results") ?: json.optJSONArray("manga_list") ?: return emptyList()
		return list.mapJSON { parseMangaFromList(it) }
	}

	private fun getSortKey(order: SortOrder, query: String) = when (order) {
		SortOrder.POPULARITY -> "views"
		SortOrder.RATING -> "rating"
		SortOrder.ALPHABETICAL, SortOrder.ALPHABETICAL_DESC -> "alphabetical"
		// an empty sortBy means "relevance", which the backend only understands together with a query
		SortOrder.RELEVANCE -> if (query.isEmpty()) "latest" else null
		else -> "latest"
	}

	private fun parseMangaFromList(jo: JSONObject): Manga {
		val id = jo.getLongOrDefault("manga_id", 0L).takeIf { it != 0L }
			?: jo.getLong("id")
		val coverUrl = jo.getStringOrNull("photo")?.toCoverUrl()
		return Manga(
			id = generateUid(id),
			url = id.toString(),
			publicUrl = "$baseUrl/manga/$id",
			coverUrl = coverUrl,
			title = jo.getStringOrNull("title").orEmpty(),
			altTitles = emptySet(),
			rating = RATING_UNKNOWN,
			contentRating = null,
			tags = emptySet(),
			state = null,
			authors = emptySet(),
			source = source,
		)
	}

	// endregion

	// region Filters

	private suspend fun fetchTags(): Set<MangaTag> {
		// genres live in the search facets
		val genres = runCatchingCancellable {
			webClient.httpGet("$baseUrl/api/search?facets=1").parseJson()
				.optJSONObject("facets")
				?.optJSONArray("genres")
				?.mapJSONNotNull { it.getStringOrNull("key")?.trim()?.nullIfEmpty() }
				?.filterNot { it in demographicNames }
				.orEmpty()
		}

		// tags are served by a dedicated endpoint, grouped in categories
		val tags = runCatchingCancellable {
			webClient.httpGet("$baseUrl/api/manga/tags").parseJson()
				.optJSONArray("categories")
				?.mapJSON { it }
				?.flatMap { category ->
					category.optJSONArray("tags")
						?.mapJSONNotNull { it.getStringOrNull("name")?.trim()?.nullIfEmpty() }
						.orEmpty()
				}
				.orEmpty()
		}

		// tolerate one of the two endpoints failing, but do not silently cache an empty tag list
		if (genres.isFailure && tags.isFailure) {
			throw genres.exceptionOrNull() ?: tags.exceptionOrNull()!!
		}

		val result = LinkedHashSet<MangaTag>()
		val titles = HashSet<String>()
		genres.getOrDefault(emptyList()).sortedBy { it.lowercase(Locale.ROOT) }.forEach { name ->
			if (titles.add(name)) {
				result += MangaTag(key = GENRE_PREFIX + name, title = name, source = source)
			}
		}
		tags.getOrDefault(emptyList()).sortedBy { it.lowercase(Locale.ROOT) }.forEach { name ->
			if (titles.add(name)) {
				result += MangaTag(key = TAG_PREFIX + name, title = name, source = source)
			}
		}
		return result
	}

	private fun MangaTag.appendTo(genres: MutableList<String>, tags: MutableList<String>, isExcluded: Boolean) {
		val prefix = if (isExcluded) "-" else ""
		when {
			key.startsWith(TAG_PREFIX) -> tags.add(prefix + key.removePrefix(TAG_PREFIX))
			else -> genres.add(prefix + key.removePrefix(GENRE_PREFIX))
		}
	}

	private fun Demographic.toApiValue() = when (this) {
		Demographic.SHOUNEN -> "Shounen"
		Demographic.SHOUJO -> "Shoujo"
		Demographic.SEINEN -> "Seinen"
		Demographic.JOSEI -> "Josei"
		else -> null
	}

	private fun MangaState.toApiValue() = when (this) {
		MangaState.ONGOING -> "Ongoing"
		MangaState.FINISHED -> "Completed"
		MangaState.PAUSED -> "Hiatus"
		else -> null
	}

	private fun ContentType.toApiValue() = when (this) {
		ContentType.MANGA -> "JP"
		ContentType.MANHWA -> "KR"
		ContentType.MANHUA -> "CN"
		ContentType.ONE_SHOT -> "ONESHOT"
		else -> null
	}

	private fun ContentRating.toApiValues() = when (this) {
		ContentRating.SAFE -> listOf("safe")
		ContentRating.SUGGESTIVE -> listOf("suggestive")
		ContentRating.ADULT -> listOf("erotica", "pornographic")
	}

	// endregion

	// region Details

	/**
	 * MangaDetailPage route structure:
	 *   route_value = { "data": MangaData }
	 *   MangaData = { "mangaData": { "manga": Manga } }
	 *
	 * After fetchRscData: we get MangaData.
	 */
	@Suppress("UNCHECKED_CAST")
	override suspend fun getDetails(manga: Manga): Manga {
		val url = "$baseUrl/manga/${manga.url}.data?_routes=pages/MangaDetailPage"
		val mangaData = fetchRscData(url, "pages/MangaDetailPage") ?: return manga

		val mangaInfo = mangaData
			.asMap("mangaData")?.asMap("manga")
			?: return manga

		val title = mangaInfo["title"] as? String ?: manga.title
		val description = mangaInfo["description"] as? String
		val coverUrl = (mangaInfo["photo"] as? String)?.toCoverUrl() ?: manga.coverUrl
		val hiatus = mangaInfo["hiatus"] as? String
		val status = mangaInfo["status"] as? String
		val genres = (mangaInfo["genres"] as? List<*>)?.filterIsInstance<String>()
			?.mapNotNull { it.trim().nullIfEmpty() }.orEmpty()
		val altTitles = (mangaInfo["alt_titles"] as? List<*>)?.filterIsInstance<String>()
			?.mapNotNull { it.trim().nullIfEmpty() }?.toSet().orEmpty()
		val ratingValue = (mangaInfo["avg_rating"] as? Number)?.toFloat()
		val anilistId = (mangaInfo["anilist_id"] as? Number)?.toLong()
		val sourceUrl = mangaInfo["source_url"] as? String

		val state = when {
			"One Shot" in genres -> MangaState.FINISHED
			hiatus.equals("Yes", ignoreCase = true) -> MangaState.PAUSED
			else -> when (status?.lowercase()) {
				"ongoing" -> MangaState.ONGOING
				"completed" -> MangaState.FINISHED
				else -> null
			}
		}

		// keys must match the ones produced by fetchTags, otherwise tapping a tag yields no results
		val tags = LinkedHashSet<MangaTag>()
		val titles = HashSet<String>()
		genres.forEach { name ->
			if (titles.add(name)) {
				tags += MangaTag(key = GENRE_PREFIX + name, title = name, source = source)
			}
		}
		(mangaInfo["tags"] as? List<*>)?.filterIsInstance<Map<String, Any?>>()?.forEach { category ->
			(category["tags"] as? List<*>)?.filterIsInstance<Map<String, Any?>>()?.forEach { tag ->
				val name = (tag["name"] as? String)?.trim()?.nullIfEmpty() ?: return@forEach
				if (titles.add(name)) {
					tags += MangaTag(key = TAG_PREFIX + name, title = name, source = source)
				}
			}
		}

		val richDescription = buildString {
			ratingValue?.let { rating ->
				val stars = (rating / 2).toInt().coerceIn(0, 5)
				append("${"★".repeat(stars)}${"☆".repeat(5 - stars)} $rating\n\n")
			}
			description?.let {
				append(
					it.replace("\r\n", "\n")
						.replace(Regex("\n{3,}"), "\n\n")
						.trim(),
					"\n\n",
				)
			}
			val links = buildList {
				anilistId?.let { add("[AniList](https://anilist.co/manga/$it)") }
				sourceUrl?.let { add("[Source]($it)") }
			}
			if (links.isNotEmpty()) {
				append("\nLinks:\n")
				links.forEach { append("- ", it, "\n") }
			}
			if (altTitles.isNotEmpty()) {
				append("\nAlternative Names:\n")
				altTitles.forEach { append("- ", it, "\n") }
			}
		}.trim()

		val chapters = fetchChapters(manga.url)

		return manga.copy(
			title = title,
			coverUrl = coverUrl,
			description = richDescription,
			altTitles = altTitles,
			tags = tags,
			state = state,
			rating = ratingValue?.takeIf { it > 0f }?.div(10f) ?: manga.rating,
			contentRating = when ((mangaInfo["content_rating"] as? String)?.lowercase()) {
				"safe" -> ContentRating.SAFE
				"suggestive" -> ContentRating.SUGGESTIVE
				"erotica", "pornographic" -> ContentRating.ADULT
				else -> manga.contentRating
			},
			authors = parseNames(mangaInfo["authors"]),
			chapters = chapters,
		)
	}

	/**
	 * `authors` and `artists` are strings holding a JSON-encoded list of names.
	 */
	private fun parseNames(value: Any?): Set<String> {
		val raw = value as? String ?: return emptySet()
		raw.toJSONArrayOrNull()?.let { array ->
			return (0 until array.length()).mapNotNullTo(LinkedHashSet()) { i ->
				array.optString(i).trim().nullIfEmpty()
			}
		}
		return setOfNotNull(raw.trim().nullIfEmpty())
	}

	// endregion

	// region Chapters

	@Suppress("UNCHECKED_CAST")
	private suspend fun fetchChapters(mangaId: String): List<MangaChapter> {
		val response = webClient.httpGet("$baseUrl/api/manga/$mangaId/chapters/list?lang=en").parseJsonArray()

		val allChapters = (0 until response.length()).map { i ->
			response.getJSONObject(i)
		}

		val chaptersByTeam = mutableMapOf<String, MutableList<JSONObject>>()
		for (chapter in allChapters) {
			val group = chapter.optString("group_name", "").nullIfEmpty()
			val scanlator = chapter.optString("scanlator_name", "").nullIfEmpty()
			val teamName = group ?: scanlator ?: "Unknown"
			chaptersByTeam.getOrPut(teamName) { mutableListOf() }.add(chapter)
		}

		val allChapterNumbers = allChapters.map { it.optDouble("chapter_number", 0.0).toFloat() }.toSet().sorted()

		val chaptersBuilder = ChaptersListBuilder(allChapters.size * chaptersByTeam.size)

		for ((teamName, teamChapters) in chaptersByTeam) {
			val teamChapterMap = teamChapters.associateBy { it.optDouble("chapter_number", 0.0).toFloat() }

			for (chapterNumber in allChapterNumbers) {
				val chapterData = teamChapterMap[chapterNumber]
					?: allChapters.find { it.optDouble("chapter_number", 0.0).toFloat() == chapterNumber }
					?: continue

				val chapterId = chapterData.getString("id")
				val chapterSource = chapterData.optString("source", "scraper")
				val number = chapterData.optDouble("chapter_number", 0.0).toFloat()
				val name = chapterData.optString("chapter_title", "").nullIfEmpty()
				val group = chapterData.optString("group_name", "").nullIfEmpty()
				val scanlator = chapterData.optString("scanlator_name", "").nullIfEmpty()
				val actualTeamName = group ?: scanlator ?: "Unknown"
				val date = chapterData.optString("date_added", "").nullIfEmpty()

				val title = buildString {
					val numStr = number.toString().substringBefore(".0")
					if (name != null && !name.contains(numStr)) {
						append("Chapter $numStr: ")
					} else if (name == null) {
						append("Chapter $numStr")
					}
					name?.let { append(it.trim()) }
				}

				val chapter = MangaChapter(
					id = generateUid("$teamName-$chapterId"),
					title = title,
					number = number,
					volume = 0,
					url = JSONObject().apply {
						put("id", chapterId)
						put("source", chapterSource)
					}.toString(),
					uploadDate = date?.let { dateFormat.parseSafe(it) } ?: 0L,
					source = source,
					scanlator = actualTeamName,
					branch = teamName,
				)

				chaptersBuilder.add(chapter)
			}
		}

		return chaptersBuilder.toList()
	}

	// endregion

	// region Pages

	override suspend fun getRelatedManga(seed: Manga): List<Manga> = emptyList()

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val chapterData = JSONObject(chapter.url)
		val chapterId = chapterData.getString("id")
		val chapterSource = chapterData.optString("source", "scraper")

		val segment = if (chapterSource == "user") "uploads" else "chapters"
		val response = webClient.httpGet("$baseUrl/api/$segment/$chapterId/images").parseJson()

		// Response can be wrapped in {"data": {"manga": {"id": ...}, "images": [...]}} or flat {"images": [...]}
		val data = response.optJSONObject("data")
		val imagesArray = if (data != null && data.has("images")) {
			data.getJSONArray("images")
		} else {
			response.getJSONArray("images")
		}

		return (0 until imagesArray.length()).mapNotNull { i ->
			val img = imagesArray.getJSONObject(i)
			val imgUrl = img.optString("url", "").nullIfEmpty() ?: return@mapNotNull null
			val fullUrl = when {
				imgUrl.startsWith("/") -> "$baseUrl$imgUrl"
				imgUrl.startsWith("http") -> imgUrl
				else -> return@mapNotNull null
			}
			MangaPage(
				id = generateUid(fullUrl),
				url = fullUrl,
				preview = null,
				source = source,
			)
		}
	}

	// endregion

	// region Helpers

	private fun String.toCoverUrl(): String? = when {
		startsWith("/") -> "$baseUrl$this"
		startsWith("http") -> this
		else -> null
	}

	@Suppress("UNCHECKED_CAST")
	private fun Map<String, Any?>.asMap(key: String): Map<String, Any?>? = this[key] as? Map<String, Any?>

	private companion object {

		const val GENRE_PREFIX = "genre:"
		const val TAG_PREFIX = "tag:"

		val demographicNames = setOf("Josei", "Seinen", "Shoujo", "Shounen")
	}

	// endregion
}
