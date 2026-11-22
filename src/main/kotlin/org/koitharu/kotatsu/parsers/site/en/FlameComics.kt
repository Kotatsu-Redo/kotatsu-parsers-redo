package org.koitharu.kotatsu.parsers.site.en

import androidx.collection.ArraySet
import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.SinglePageMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy
import java.util.EnumSet
import java.util.concurrent.TimeUnit

@MangaSourceParser("FLAMECOMICS", "FlameComics", "en")
internal class FlameComics(context: MangaLoaderContext) :
	SinglePageMangaParser(context, MangaParserSource.FLAMECOMICS) {

	private val commonPrefix = suspendLazy(initializer = ::fetchBuildId)
	private val removeSpecialCharsRegex = Regex("[^A-Za-z0-9 ]")

	override val configKeyDomain = ConfigKey.Domain("flamecomics.xyz")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.POPULARITY,
		SortOrder.UPDATED,
		SortOrder.NEWEST,
		SortOrder.RATING,
		SortOrder.ALPHABETICAL,
		SortOrder.ALPHABETICAL_DESC,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = true,
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isYearSupported = true,
		)

	override val sourceLocale: java.util.Locale
		get() = java.util.Locale.ENGLISH

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(ConfigKey.UserAgent(context.getDefaultUserAgent()))
	}

	override suspend fun getList(order: SortOrder, filter: MangaListFilter): List<Manga> {
		// browse.json carries everything the filters need (categories, year, ...); latest.json has none of it
		// and is only used to put the series in the order of their latest updates
		val browseList = fetchBrowseRaw()
		val rawList = if (order == SortOrder.UPDATED || order == SortOrder.UPDATED_ASC) {
			val ranks = fetchLatestRanks()
			browseList.sortedBy { raw -> raw.json.seriesIdOrNull()?.let { ranks[it] } ?: Int.MAX_VALUE }
		} else {
			browseList
		}
		val rawById = HashMap<Long, Raw>(rawList.size)
		val allManga = rawList.mapNotNull { raw ->
			val manga = parseManga(raw.json) ?: return@mapNotNull null
			// the site lists some series more than once
			if (rawById.containsKey(manga.id)) return@mapNotNull null
			rawById[manga.id] = raw
			if (!manga.tags.matchesFilter(filter, raw)) return@mapNotNull null
			if (!raw.matchesState(filter)) return@mapNotNull null
			if (!raw.matchesType(filter)) return@mapNotNull null
			if (!raw.matchesYear(filter)) return@mapNotNull null
			manga
		}
		val filtered = if (!filter.query.isNullOrEmpty()) {
			val normalizedQuery = filter.query.normalizeTitle()
			allManga.filter { manga ->
				val titles = mutableListOf(manga.title)
				titles.addAll(manga.altTitles)
				titles.any { normalizedQuery in it.normalizeTitle() }
			}
		} else {
			allManga
		}
		return when (order) {
			SortOrder.POPULARITY -> filtered.sortedBy { rawById[it.id]?.popularityRank ?: Int.MAX_VALUE }
			SortOrder.UPDATED -> filtered
			SortOrder.UPDATED_ASC -> filtered.reversed()
			SortOrder.NEWEST -> filtered.sortedByDescending { rawById[it.id]?.time ?: 0L }
			SortOrder.RATING -> filtered.sortedByDescending { rawById[it.id]?.likes ?: 0 }
			SortOrder.ALPHABETICAL -> filtered.sortedBy { it.title.lowercase(sourceLocale) }
			SortOrder.ALPHABETICAL_DESC -> filtered.sortedByDescending { it.title.lowercase(sourceLocale) }
			else -> filtered.sortedBy { it.title.lowercase(sourceLocale) }
		}
	}

	override suspend fun getDetails(manga: Manga): Manga = getDetailsImpl(manga.url.toLong())

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val (seriesId, token) = chapter.url.split('?')
		val url = urlBuilder()
			.addPathSegment("_next")
			.addPathSegment("data")
			.addPathSegment(commonPrefix.get())
			.addPathSegment("series")
			.addPathSegment(seriesId)
			.addPathSegment("$token.json")
			.addQueryParameter("id", seriesId)
			.addQueryParameter("token", token)
			.build()
		val json = webClient.httpGet(url).parseJson().getJSONObject("pageProps")
			.getJSONObject("chapter")
			.getJSONObject("images")
			.entries<JSONObject>()
		return json.map { (i, jo) ->
			MangaPage(
				id = generateUid("$i|$token"),
				url = imageUrl(seriesId, token + "/" + jo.getString("name"), 1920),
				preview = imageUrl(seriesId, token + "/" + jo.getString("name"), 128),
				source = source,
			)
		}
	}

	override suspend fun resolveLink(resolver: LinkResolver, link: HttpUrl): Manga? {
		val seriesId = link.pathSegments.lastOrNull()?.toLongOrNull() ?: return null
		return getDetailsImpl(seriesId)
	}

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchAvailableTags(),
		availableStates = EnumSet.of(
			MangaState.ONGOING,
			MangaState.FINISHED,
			MangaState.ABANDONED,
			MangaState.PAUSED,
			MangaState.UPCOMING,
		),
		availableContentTypes = EnumSet.of(
			ContentType.MANGA,
			ContentType.MANHWA,
			ContentType.MANHUA,
			ContentType.COMICS,
			ContentType.NOVEL,
		),
	)

	private suspend fun fetchBuildId(): String {
		val doc = webClient.httpGet(urlBuilder().build()).parseHtml()
		val nextData = doc.selectFirst("script#__NEXT_DATA__")?.data()
		if (nextData != null) {
			try {
				val id = JSONObject(nextData).getString("buildId")
				if (id.isNotEmpty()) return id
			} catch (_: Exception) { }
		}
		val raw = doc.html()
		val regex = Regex("/_next/static/([^/]+)/_buildManifest\\.js")
		val prefix = raw.findGroupValue(regex)
		if (prefix != null) return prefix
		throw IllegalStateException("Unable to find buildId")
	}

	private fun imageUrl(seriesId: Any, url: String, width: Int) = urlBuilder()
		.addPathSegment("_next")
		.addPathSegment("image")
		.addQueryParameter(
			"url",
			urlBuilder("cdn")
				.addPathSegment("uploads")
				.addPathSegment("images")
				.addPathSegment("series")
				.addPathSegment(seriesId.toString())
				.addPathSegments(url)
				.build().toString(),
		)
		.addQueryParameter("w", width.toString())
		.addQueryParameter("q", "100")
		.build()
		.toString()

	private data class Raw(
		val json: JSONObject,
		val popularityRank: Int = json.optInt("popularityRank", Int.MAX_VALUE),
		val lastEdit: Long = json.optLong("last_edit", 0L),
		val time: Long = json.optLong("time", 0L),
		val likes: Int = json.optInt("likes", 0),
		val year: Int = json.optInt("year", 0),
		val status: String = json.optString("status", ""),
		val type: String = json.optString("type", ""),
	)

	private suspend fun fetchBrowseRaw(): List<Raw> {
		val url = urlBuilder()
			.addPathSegment("_next")
			.addPathSegment("data")
			.addPathSegment(commonPrefix.get())
			.addPathSegment("browse.json")
			.build()
		val arr = webClient.httpGet(url).parseJson().getJSONObject("pageProps").getJSONArray("series")
		return arr.mapJSON { Raw(it) }
	}

	/** Position of each series in latest.json (first occurrence wins), 0 being the most recently updated */
	private suspend fun fetchLatestRanks(): Map<Long, Int> {
		val url = urlBuilder()
			.addPathSegment("_next")
			.addPathSegment("data")
			.addPathSegment(commonPrefix.get())
			.addPathSegment("latest.json")
			.build()
		val arr = webClient.httpGet(url).parseJson().getJSONObject("pageProps").getJSONArray("allSeries")
		val ranks = HashMap<Long, Int>(arr.length())
		arr.mapJSON { it.seriesIdOrNull() }.forEachIndexed { index, id ->
			if (id != null && id !in ranks) ranks[id] = index
		}
		return ranks
	}

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		val url = urlBuilder()
			.addPathSegment("_next")
			.addPathSegment("data")
			.addPathSegment(commonPrefix.get())
			.addPathSegment("browse.json")
			.build()
		return webClient.httpGet(url).parseJson()
			.getJSONObject("pageProps")
			.getJSONArray("series")
			.mapJSON { it.extractCategories() }
			.flatMapTo(ArraySet<String>()) { it }
			.mapToSet { it.toMangaTag() }
	}

	private fun parseManga(jo: JSONObject): Manga? {
		val seriesId = jo.seriesIdOrNull() ?: return null
		val cover = jo.getStringOrNull("cover")
		val authors = jo.extractStringList("author")
		val coverUrl = cover?.let { imageUrl(seriesId, it, 384) }
		val tags = jo.extractCategories().mapToSet { it.toMangaTag() }
		return Manga(
			id = generateUid(seriesId),
			title = jo.getString("title"),
			altTitles = jo.getStringOrNull("altTitles")?.let { str ->
				try { JSONArray(str).toStringSet() } catch (_: Exception) { emptySet() }
			}.orEmpty(),
			url = seriesId.toString(),
			publicUrl = "https://${domain}/series/$seriesId",
			rating = RATING_UNKNOWN,
			contentRating = null,
			coverUrl = coverUrl,
			tags = tags,
			state = jo.getStringOrNull("status")?.toMangaState(),
			authors = authors,
			largeCoverUrl = cover?.let { imageUrl(seriesId, it, 720) },
			description = jo.getStringOrNull("description"),
			source = source,
		)
	}

	private suspend fun getDetailsImpl(seriesId: Long): Manga {
		val url = urlBuilder()
			.addPathSegment("_next")
			.addPathSegment("data")
			.addPathSegment(commonPrefix.get())
			.addPathSegment("series")
			.addPathSegment("$seriesId.json")
			.addQueryParameter("id", seriesId.toString())
			.build()
		val json = webClient.httpGet(url).parseJson().getJSONObject("pageProps")
		val series = json.getJSONObject("series")
		val chapters = json.getJSONArray("chapters")
		return (parseManga(series) ?: throw IllegalStateException("Invalid series $seriesId")).copy(
			chapters = chapters.mapJSON { jo ->
				val chapterId = jo.getLong("chapter_id")
				val number = jo.getFloatOrDefault("chapter", 0f)
				MangaChapter(
					id = generateUid(longOf(seriesId.toInt(), chapterId.toInt())),
					title = jo.getStringOrNull("name")?.takeIf { it.isNotBlank() } ?: jo.getStringOrNull("title"),
					number = number,
					volume = 0,
					url = seriesId.toString() + "?" + jo.getStringOrNull("token").orEmpty(),
					scanlator = null,
					uploadDate = TimeUnit.SECONDS.toMillis(jo.getLongOrDefault("release_date", 0L)),
					branch = jo.getStringOrNull("language"),
					source = source,
				)
			}.sortedByDescending { it.uploadDate },
		)
	}

	private fun Set<MangaTag>.matchesFilter(filter: MangaListFilter, raw: Raw): Boolean {
		if (filter.tags.isNotEmpty() && !containsAll(filter.tags)) return false
		for (tag in filter.tagsExclude) if (contains(tag)) return false
		return true
	}

	private fun Raw.matchesState(filter: MangaListFilter): Boolean {
		if (filter.states.isEmpty()) return true
		val state = status.toMangaState() ?: return false
		return state in filter.states
	}

	private fun Raw.matchesType(filter: MangaListFilter): Boolean {
		if (filter.types.isEmpty()) return true
		val ct = type.toContentType() ?: return false
		return ct in filter.types
	}

	private fun Raw.matchesYear(filter: MangaListFilter): Boolean {
		if (filter.year == YEAR_UNKNOWN) return true
		return year == filter.year
	}

	private fun String.toMangaState(): MangaState? = when (lowercase().trim()) {
		"ongoing" -> MangaState.ONGOING
		"completed" -> MangaState.FINISHED
		"hiatus" -> MangaState.PAUSED
		"dropped", "cancelled", "canceled" -> MangaState.ABANDONED
		"coming soon", "upcoming" -> MangaState.UPCOMING
		else -> null
	}

	private fun String.toContentType(): ContentType? = when (lowercase().trim()) {
		"manga" -> ContentType.MANGA
		"manhwa" -> ContentType.MANHWA
		"manhua" -> ContentType.MANHUA
		"comic", "comics" -> ContentType.COMICS
		"novel", "web novel" -> ContentType.NOVEL
		"russian" -> ContentType.OTHER
		else -> ContentType.OTHER
	}

	private fun JSONObject.seriesIdOrNull(): Long? =
		getLongOrDefault("series_id", -1L).takeIf { it != -1L }
			?: getLongOrDefault("id", -1L).takeIf { it != -1L }
			?: getStringOrNull("series_id")?.toLongOrNull()
			?: getStringOrNull("id")?.toLongOrNull()
			?: getLongOrDefault("seriesId", -1L).takeIf { it != -1L }
			?: getStringOrNull("seriesId")?.toLongOrNull()

	/**
	 * The list endpoints (browse.json) call them "categories", the series page calls them "tags"
	 */
	private fun JSONObject.extractCategories(): Set<String> {
		for (key in arrayOf("categories", "tags")) {
			val arr = optJSONArray(key) ?: getStringOrNull(key)?.let { str ->
				try { JSONArray(str) } catch (_: Exception) { null }
			} ?: continue
			return arr.toTagNames()
		}
		return emptySet()
	}

	private fun JSONArray.toTagNames(): Set<String> {
		val result = LinkedHashSet<String>(length())
		for (i in 0 until length()) {
			val item = opt(i)
			val name = when (item) {
				is String -> item
				is JSONObject -> item.getStringOrNull("name") ?: item.getStringOrNull("title")
				else -> null
			}
			if (!name.isNullOrBlank()) result.add(name)
		}
		return result
	}

	private fun JSONObject.extractStringList(key: String): Set<String> {
		optJSONArray(key)?.let { return it.asTypedList<String>().filter { it.isNotBlank() }.toSet() }
		getStringOrNull(key)?.let { if (it.isNotBlank()) return setOf(it) }
		getStringOrNull(key)?.let { str ->
			if (str.startsWith("[")) {
				try { return JSONArray(str).asTypedList<String>().toSet() } catch (_: Exception) {}
			}
		}
		return emptySet()
	}

	private fun String.normalizeTitle() = removeSpecialCharsRegex.replace(lowercase(), "")

	private fun String.toMangaTag() = MangaTag(
		title = this.toTitleCase(sourceLocale),
		key = this.lowercase().trim(),
		source = source,
	)
}
