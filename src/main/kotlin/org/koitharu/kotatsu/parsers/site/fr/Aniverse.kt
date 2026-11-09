package org.koitharu.kotatsu.parsers.site.fr

import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.NotFoundException
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
import org.koitharu.kotatsu.parsers.util.LinkResolver
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.json.getBooleanOrDefault
import org.koitharu.kotatsu.parsers.util.json.getFloatOrDefault
import org.koitharu.kotatsu.parsers.util.json.getIntOrDefault
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.json.mapJSON
import org.koitharu.kotatsu.parsers.util.json.mapJSONNotNull
import org.koitharu.kotatsu.parsers.util.json.toJSONArrayOrNull
import org.koitharu.kotatsu.parsers.util.json.toJSONObjectOrNull
import org.koitharu.kotatsu.parsers.util.json.unescapeJson
import org.koitharu.kotatsu.parsers.util.nl2br
import org.koitharu.kotatsu.parsers.util.oneOrThrowIfMany
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.parseRaw
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.parsers.util.toTitleCase
import org.koitharu.kotatsu.parsers.util.urlBuilder
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale
import java.util.TimeZone

/**
 * Aniverse (aniverse.fr), formerly RaijinScans: raijin-scans.fr redirects there and most slugs were kept,
 * so manga urls keep RaijinScans' `/manga/{slug}/` format and the ids of library entries don't change.
 *
 * The site (Next.js) has public JSON APIs:
 *  - `/api/manga?page=N[&genre=X]`: catalogue ordered by latest chapter, one genre at most;
 *  - `/api/anime/search?media=manga`: search, sorts and filters (genres, status, origin, format);
 *  - `/api/manga/reader?manga={slug}&chapter={uuid}`: pages of a chapter, plus the whole chapter list.
 * Details and the chapter list come from the RSC payload of `/manga/{slug}`.
 */
@MangaSourceParser("RAIJINSCANS", "Aniverse", "fr")
internal class Aniverse(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.RAIJINSCANS, pageSize = PAGE_SIZE) {

	override val configKeyDomain = ConfigKey.Domain("aniverse.fr")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.RATING,
		SortOrder.NEWEST,
		SortOrder.ALPHABETICAL,
		SortOrder.RELEVANCE,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isMultipleTagsSupported = true,
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
		)

	private val genres: Set<MangaTag> = GENRES.mapTo(LinkedHashSet()) { (key, title) ->
		MangaTag(title = title, key = key, source = source)
	}

	override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions(
		availableTags = genres,
		availableStates = EnumSet.of(
			MangaState.ONGOING,
			MangaState.FINISHED,
			MangaState.PAUSED,
			MangaState.ABANDONED,
		),
		availableContentTypes = EnumSet.of(
			ContentType.MANGA,
			ContentType.MANHWA,
			ContentType.MANHUA,
			ContentType.ONE_SHOT,
		),
	)

	// ---------------------------------------------------------------- list

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val query = filter.query?.trim().orEmpty()
		val isCatalogue = order == SortOrder.UPDATED && query.isEmpty() && filter.tags.size <= 1 &&
			filter.states.isEmpty() && filter.types.isEmpty()
		if (isCatalogue) {
			val url = urlBuilder()
				.addPathSegments("api/manga")
				.addQueryParameter("page", page.toString())
			filter.tags.firstOrNull()?.let { url.addQueryParameter("genre", it.key) }
			return webClient.httpGet(url.build()).parseJson()
				.getJSONArray("items")
				.mapJSON { it.toManga() }
		}
		val url = urlBuilder()
			.addPathSegments("api/anime/search")
			.addQueryParameter("media", "manga")
			.addQueryParameter("page", page.toString())
			.addQueryParameter("pageSize", PAGE_SIZE.toString())
		if (query.isNotEmpty()) {
			url.addQueryParameter("q", query)
		}
		// Without a sort the site orders by relevance with a query and by popularity otherwise.
		// That is also the fallback for UPDATED, which only the catalogue (no search, one genre) supports.
		when (order) {
			SortOrder.POPULARITY -> "popularity"
			SortOrder.RATING -> "rating"
			SortOrder.NEWEST -> "newest"
			SortOrder.ALPHABETICAL -> "title"
			else -> null
		}?.let { url.addQueryParameter("sort", it) }
		filter.tags.forEach { url.addQueryParameter("genres", it.key) }
		filter.states.forEach { state ->
			when (state) {
				MangaState.ONGOING -> "Ongoing"
				MangaState.FINISHED -> "Finished"
				MangaState.PAUSED -> "On hiatus"
				MangaState.ABANDONED -> "Cancelled"
				else -> null
			}?.let { url.addQueryParameter("status", it) }
		}
		if (ContentType.ONE_SHOT in filter.types) {
			url.addQueryParameter("type", "One-shot")
		}
		// The origin ("kind") accepts a single value.
		val kind = filter.types.filterTo(EnumSet.noneOf(ContentType::class.java)) { it != ContentType.ONE_SHOT }
			.oneOrThrowIfMany()
		when (kind) {
			ContentType.MANGA -> "manga"
			ContentType.MANHWA -> "manhwa"
			ContentType.MANHUA -> "manhua"
			else -> null
		}?.let { url.addQueryParameter("kind", it) }
		return webClient.httpGet(url.build()).parseJson()
			.getJSONArray("data")
			.mapJSON { it.toManga() }
	}

	/** An item of the catalogue or of the search results. */
	private fun JSONObject.toManga(): Manga {
		val slug = getString("slug")
		val url = "/manga/$slug/"
		val title = getString("title")
		val rating = getFloatOrDefault("rating", 0f)
		return Manga(
			id = generateUid(url),
			title = title,
			altTitles = setOfNotNull(
				getStringOrNull("titleEnglish"),
				getStringOrNull("titleRomaji"),
				getStringOrNull("titleNative"),
			).filterNotTo(LinkedHashSet()) { it.equals(title, ignoreCase = true) },
			url = url,
			publicUrl = "https://$domain/manga/$slug",
			rating = if (rating > 0f) (rating / 10f).coerceAtMost(1f) else RATING_UNKNOWN,
			contentRating = null,
			coverUrl = getStringOrNull("image"),
			tags = emptySet(),
			state = parseState(getStringOrNull("status")),
			authors = emptySet(),
			source = source,
		)
	}

	// -------------------------------------------------------------- details

	override suspend fun getDetails(manga: Manga): Manga {
		val (slug, html) = loadMangaPage(manga)
		val pageUrl = "https://$domain/manga/$slug"
		val flight = buildFlightText(html)
		val obj = findMangaObject(flight, slug) ?: throw ParseException("Manga data not found", pageUrl)
		val chapters = findChaptersArray(flight)?.takeIf { it.length() > 0 }
			?: fetchChaptersFromReader(slug, obj.getString("databaseId"))
		val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
			timeZone = TimeZone.getTimeZone("UTC")
		}
		val now = System.currentTimeMillis()
		val title = obj.titleAsString() ?: manga.title
		val score = obj.getFloatOrDefault("averageScore", 0f)
		return manga.copy(
			title = title,
			publicUrl = pageUrl,
			altTitles = obj.altTitles(title).ifEmpty { manga.altTitles },
			description = obj.descriptionOrNull(),
			coverUrl = obj.optJSONObject("coverImage")?.getStringOrNull("large") ?: manga.coverUrl,
			rating = if (score > 0f) (score / 100f).coerceAtMost(1f) else manga.rating,
			contentRating = if (obj.getBooleanOrDefault("isAdult", false)) ContentRating.ADULT else manga.contentRating,
			tags = obj.genreTags(),
			state = parseState(obj.getStringOrNull("status")) ?: manga.state,
			authors = obj.peopleOrEmpty(),
			// Premium chapters can't be read until premiumUntil (the reader answers "locked").
			chapters = chapters?.mapJSONNotNull { jo ->
				if (parseDate(dateFormat, jo.getStringOrNull("premiumUntil")) > now) {
					null
				} else {
					jo.toChapter(slug, dateFormat)
				}
			}.orEmpty(),
		)
	}

	override suspend fun resolveLink(resolver: LinkResolver, link: HttpUrl): Manga? {
		val segments = link.pathSegments.filter { it.isNotEmpty() }
		if (segments.size < 2 || segments[0] !in LINK_PREFIXES) {
			return null
		}
		val slug = segments[1]
		val url = "/manga/$slug/"
		return getDetails(
			Manga(
				id = generateUid(url),
				title = slug,
				altTitles = emptySet(),
				url = url,
				publicUrl = "https://$domain/manga/$slug",
				rating = RATING_UNKNOWN,
				contentRating = null,
				coverUrl = null,
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				source = source,
			),
		)
	}

	// ---------------------------------------------------------------- pages

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		// "/read/{slug}/{uuid}"
		val segments = chapter.url.split('/').filter { it.isNotEmpty() }
		if (segments.size < 3) {
			throw ParseException("Invalid chapter url", chapter.url)
		}
		return fetchReader(segments[1], segments[2])
			.getJSONArray("pages")
			.mapJSON { jo ->
				val src = jo.getString("src")
				MangaPage(
					id = generateUid(jo.getStringOrNull("id") ?: src),
					url = src,
					preview = null,
					source = source,
				)
			}
	}

	// ------------------------------------------------------------- internals

	private suspend fun fetchReader(slug: String, chapterId: String): JSONObject {
		val url = urlBuilder()
			.addPathSegments("api/manga/reader")
			.addQueryParameter("manga", slug)
			.addQueryParameter("chapter", chapterId)
			.build()
		return webClient.httpGet(url).parseJson()
	}

	/**
	 * The manga page is cached by the site for a few minutes, so right after an import its payload can have
	 * no chapters yet: ask the reader, which always returns the full list, starting from the first chapter.
	 */
	private suspend fun fetchChaptersFromReader(slug: String, mangaId: String): JSONArray? = runCatchingCancellable {
		val cardUrl = urlBuilder()
			.addPathSegments("api/manga/card-info")
			.addQueryParameter("id", mangaId)
			.build()
		val readHref = webClient.httpGet(cardUrl).parseJson().getStringOrNull("readHref")
			?: return@runCatchingCancellable null
		fetchReader(slug, readHref.trimEnd('/').substringAfterLast('/')).optJSONArray("chapters")
	}.getOrNull()

	private fun slugOf(url: String): String =
		url.substringAfter("/manga/").substringBefore('?').trim('/')

	/** Returns the slug and the html of the manga page. */
	private suspend fun loadMangaPage(manga: Manga): Pair<String, String> {
		val slug = slugOf(manga.url)
		return try {
			slug to webClient.httpGet("https://$domain/manga/$slug").parseRaw()
		} catch (e: NotFoundException) {
			val renamed = findRenamedSlug(manga) ?: throw e
			renamed to webClient.httpGet("https://$domain/manga/$renamed").parseRaw()
		}
	}

	/**
	 * Most RaijinScans slugs were kept by Aniverse, but some changed (a numeric suffix dropped, a new title).
	 * Library entries keep their url (and id), and are looked up by title: only an unambiguous match counts.
	 */
	private suspend fun findRenamedSlug(manga: Manga): String? {
		val titles = (manga.altTitles + manga.title).mapNotNullTo(HashSet()) { it.normalizedTitle().ifEmpty { null } }
		val matches = getListPage(1, SortOrder.RELEVANCE, MangaListFilter(query = manga.title)).filter {
			it.title.normalizedTitle() in titles
		}
		return matches.singleOrNull()?.let { slugOf(it.url) }?.takeUnless { it == slugOf(manga.url) }
	}

	private fun String.normalizedTitle(): String = lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

	/**
	 * Extracts and joins the RSC payloads (`self.__next_f.push([1, "..."])`), unescaping the JavaScript
	 * strings. Scanned by hand: big payloads overflow the java.util.regex stack.
	 */
	private fun buildFlightText(html: String): String {
		val sb = StringBuilder(html.length)
		var searchFrom = 0
		while (true) {
			val marker = html.indexOf(NEXT_F_PREFIX, searchFrom)
			if (marker == -1) {
				break
			}
			var i = marker + NEXT_F_PREFIX.length
			if (i >= html.length || html[i] != '"') {
				searchFrom = i
				continue
			}
			i++ // opening quote
			val start = i
			var escaped = false
			var end = -1
			while (i < html.length) {
				val c = html[i]
				if (escaped) {
					escaped = false
				} else if (c == '\\') {
					escaped = true
				} else if (c == '"') {
					end = i
					break
				}
				i++
			}
			if (end == -1) {
				break
			}
			val payload = html.substring(start, end)
			sb.append(
				try {
					payload.unescapeJson()
				} catch (_: IllegalArgumentException) {
					payload
				},
			)
			searchFrom = end + 1
		}
		return sb.toString()
	}

	private fun findMangaObject(flight: String, slug: String): JSONObject? {
		val needle = "\"slug\":\"$slug\""
		var index = flight.indexOf(needle)
		while (index != -1) {
			val start = flight.lastIndexOf('{', index)
			if (start != -1) {
				val end = findJsonEnd(flight, start, '{', '}')
				if (end != -1) {
					val json = flight.substring(start, end + 1).toJSONObjectOrNull()
					if (json != null && json.has("databaseId") && json.has("genres")) {
						return json
					}
				}
			}
			index = flight.indexOf(needle, index + 1)
		}
		return null
	}

	/**
	 * The page props hold the chapter list as a reference to another RSC row (`"chapters":"$@3a"`,
	 * then a `3a:[...]` row), or inline.
	 */
	private fun findChaptersArray(flight: String): JSONArray? {
		for (match in CHAPTERS_REGEX.findAll(flight)) {
			val ref = match.groupValues[1]
			val start = if (ref.isEmpty()) {
				match.range.last
			} else {
				val row = "\n$ref:"
				val rowIndex = flight.indexOf(row)
				if (rowIndex == -1) {
					continue
				}
				rowIndex + row.length
			}
			if (flight.getOrNull(start) != '[') {
				continue
			}
			val end = findJsonEnd(flight, start, '[', ']')
			if (end == -1) {
				continue
			}
			val array = flight.substring(start, end + 1).toJSONArrayOrNull() ?: continue
			val first = array.optJSONObject(0)
			if (array.length() == 0 || (first != null && first.has("id") && first.has("number"))) {
				return array
			}
		}
		return null
	}

	/** Finds the end of the JSON object or array opened at [start], skipping strings. */
	private fun findJsonEnd(text: String, start: Int, open: Char, close: Char): Int {
		var depth = 0
		var inString = false
		var escaped = false
		for (i in start until text.length) {
			val c = text[i]
			if (inString) {
				when {
					escaped -> escaped = false
					c == '\\' -> escaped = true
					c == '"' -> inString = false
				}
			} else {
				when (c) {
					'"' -> inString = true
					open -> depth++
					close -> {
						depth--
						if (depth == 0) {
							return i
						}
					}
				}
			}
		}
		return -1
	}

	private fun JSONObject.toChapter(slug: String, dateFormat: DateFormat): MangaChapter {
		val url = "/read/$slug/${getString("id")}"
		val number = getFloatOrDefault("number", 0f)
		val isVolume = getStringOrNull("kind") == "tome"
		return MangaChapter(
			id = generateUid(url),
			title = getStringOrNull("title")?.replaceFirst(CHAPTER_PREFIX_REGEX, "")?.trim()?.ifEmpty { null }
				?: if (isVolume) "Tome ${formatNumber(number)}" else null,
			number = number,
			volume = if (isVolume) number.toInt() else getIntOrDefault("volume", 0),
			url = url,
			scanlator = null,
			uploadDate = parseDate(dateFormat, getStringOrNull("updatedAt")),
			branch = null,
			source = source,
		)
	}

	private fun JSONObject.titleAsString(): String? {
		val titleObj = optJSONObject("title") ?: return getStringOrNull("title")
		return titleObj.getStringOrNull("userPreferred")
			?: titleObj.getStringOrNull("english")
			?: titleObj.getStringOrNull("romaji")
			?: titleObj.getStringOrNull("native")
	}

	private fun JSONObject.altTitles(title: String): Set<String> {
		val titleObj = optJSONObject("title") ?: return emptySet()
		return setOfNotNull(
			titleObj.getStringOrNull("english"),
			titleObj.getStringOrNull("romaji"),
			titleObj.getStringOrNull("native"),
		).filterNotTo(LinkedHashSet()) { it.equals(title, ignoreCase = true) }
	}

	/** Titles without an AniList entry have no description of their own, only the one of their source. */
	private fun JSONObject.descriptionOrNull(): String? {
		getStringOrNull("description")?.let { return it }
		val sources = optJSONObject("savedMetadata")
			?.optJSONObject("otherInfo")
			?.optJSONObject("sources")
			?: return null
		for (key in sources.keys()) {
			sources.optJSONObject(key)?.getStringOrNull("description")?.let { return it.trim().nl2br() }
		}
		return null
	}

	private fun JSONObject.genreTags(): Set<MangaTag> {
		val array = optJSONArray("genres") ?: return emptySet()
		val result = LinkedHashSet<MangaTag>(array.length())
		for (i in 0 until array.length()) {
			val name = array.optString(i).trim()
			if (name.isEmpty()) {
				continue
			}
			// AniList genres are the filter keys; titles from other sources may use the French label.
			result += genres.find { it.key.equals(name, ignoreCase = true) || it.title.equals(name, ignoreCase = true) }
				?: MangaTag(title = name.toTitleCase(sourceLocale), key = name, source = source)
		}
		return result
	}

	private fun JSONObject.peopleOrEmpty(): Set<String> {
		val result = LinkedHashSet<String>()
		for (field in arrayOf("authors", "artists")) {
			val array = optJSONArray(field) ?: continue
			for (i in 0 until array.length()) {
				array.optString(i).trim().takeIf { it.isNotEmpty() }?.let { result += it }
			}
		}
		return result
	}

	private fun parseState(raw: String?): MangaState? =
		when (raw?.lowercase(Locale.ROOT)?.replace(" ", "")?.replace("_", "")) {
			"ongoing", "releasing" -> MangaState.ONGOING
			"finished" -> MangaState.FINISHED
			"onhiatus", "hiatus" -> MangaState.PAUSED
			"cancelled", "canceled" -> MangaState.ABANDONED
			"upcoming", "notyetreleased" -> MangaState.UPCOMING
			else -> null
		}

	private fun parseDate(dateFormat: DateFormat, raw: String?): Long {
		if (raw.isNullOrEmpty()) {
			return 0L
		}
		return try {
			dateFormat.parse(raw)?.time ?: 0L
		} catch (_: Exception) {
			0L
		}
	}

	private fun formatNumber(value: Float): String =
		if (value % 1f == 0f) value.toInt().toString() else value.toString()
}

private const val PAGE_SIZE = 28

private const val NEXT_F_PREFIX = "self.__next_f.push([1,"

private val LINK_PREFIXES = setOf("manga", "read")

private val CHAPTERS_REGEX = Regex(""""chapters":(?:"\$@?([0-9a-zA-Z]+)"|\[)""")

private val CHAPTER_PREFIX_REGEX = Regex(
	"""^\s*(?:chapitre|chapter)\s*\d+(?:[.,]\d+)?\s*(?:[-–—:.]\s*)?""",
	RegexOption.IGNORE_CASE,
)

/** Genre keys of the site's filters (AniList genres) to their French labels on the site. */
private val GENRES = listOf(
	"Action" to "Action",
	"Adventure" to "Aventure",
	"Comedy" to "Comédie",
	"Drama" to "Drame",
	"Ecchi" to "Ecchi",
	"Fantasy" to "Fantasy",
	"Horror" to "Horreur",
	"Mahou Shoujo" to "Mahou shoujo",
	"Mecha" to "Mecha",
	"Music" to "Musique",
	"Mystery" to "Mystère",
	"Psychological" to "Psychologique",
	"Romance" to "Romance",
	"Sci-Fi" to "Science-fiction",
	"Slice of Life" to "Tranche de vie",
	"Sports" to "Sport",
	"Supernatural" to "Surnaturel",
	"Thriller" to "Thriller",
)
