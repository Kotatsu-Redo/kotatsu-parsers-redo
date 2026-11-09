package org.koitharu.kotatsu.parsers.site.all

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Entities
import org.jsoup.parser.Parser
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.GraphQLException
import org.koitharu.kotatsu.parsers.exception.NotFoundException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.network.UserAgents
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import java.io.IOException
import java.util.*

/**
 * xComic (xcomic.me): a multi-language aggregator with a GraphQL API at `/query/`.
 *
 * A title groups several "sources" (comics): uploads of the work in one language each, by different
 * groups or sites. Chapters are shown in one branch per language. Within a language, the source with
 * the most chapters is used and the others only fill in the chapter numbers it lacks.
 */
@MangaSourceParser("XCOMIC", "XComic")
internal class XComic(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.XCOMIC, pageSize = 36) {

	override val configKeyDomain = ConfigKey.Domain("xcomic.me", "xcomic.net", "comik.to", "yona.to")

	override val userAgentKey = ConfigKey.UserAgent(UserAgents.CHROME_DESKTOP)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
		.set("Referer", "https://$domain/")
		.build()

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.RATING,
		SortOrder.NEWEST,
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
			isYearRangeSupported = true,
			isOriginalLocaleSupported = true,
		)

	override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions(
		availableTags = TAGS.mapToSet { (key, title) -> MangaTag(title = title, key = key, source = source) },
		availableStates = EnumSet.of(
			MangaState.ONGOING,
			MangaState.FINISHED,
			MangaState.PAUSED,
			MangaState.ABANDONED,
			MangaState.UPCOMING,
		),
		availableContentTypes = EnumSet.of(
			ContentType.MANGA,
			ContentType.MANHWA,
			ContentType.MANHUA,
			ContentType.COMICS,
			ContentType.IMAGE_SET,
			ContentType.OTHER,
		),
		availableDemographics = EnumSet.of(
			Demographic.SHOUNEN,
			Demographic.SHOUJO,
			Demographic.SEINEN,
			Demographic.JOSEI,
			Demographic.KODOMO,
		),
		availableLocales = LANGUAGES.mapToSet { it.toLocale() },
	)

	// ============================== List ==============================

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val select = JSONObject().apply {
			put("page", page)
			put("size", pageSize)
			put("init", (page - 1) * pageSize)
			put("where", "browse")
			put("word", filter.query?.trim().orEmpty())
			put(
				"sortby",
				when (order) {
					SortOrder.UPDATED -> "field_update"
					SortOrder.POPULARITY -> "field_follow"
					SortOrder.NEWEST -> "field_create"
					SortOrder.ALPHABETICAL -> "field_name_asc"
					SortOrder.ALPHABETICAL_DESC -> "field_name_desc"
					else -> "field_score"
				},
			)
			put("incGenres", JSONArray(filter.tags.map { it.key }))
			put("excGenres", JSONArray(filter.tagsExclude.map { it.key }))
			put("incGenresMode", "and")
			put("excGenresMode", "or")
			put("incTLangs", JSONArray(listOfNotNull(filter.locale?.toLangCode())))
			put("incOLangs", JSONArray(listOfNotNull(filter.originalLocale?.toLangCode())))
			put(
				"origStatus",
				JSONArray(
					filter.states.mapNotNull {
						when (it) {
							MangaState.ONGOING -> "releasing"
							MangaState.FINISHED -> "completed"
							MangaState.PAUSED -> "hiatus"
							MangaState.ABANDONED -> "cancelled"
							MangaState.UPCOMING -> "upcoming"
							else -> null
						}
					},
				),
			)
			put(
				"incTypes",
				JSONArray(
					filter.types.flatMap {
						when (it) {
							ContentType.MANGA -> listOf("manga")
							ContentType.MANHWA -> listOf("manhwa")
							ContentType.MANHUA -> listOf("manhua")
							ContentType.COMICS -> listOf("western", "cartoon")
							ContentType.IMAGE_SET -> listOf("imageset")
							ContentType.OTHER -> listOf("oel", "other")
							else -> emptyList()
						}
					},
				),
			)
			put(
				"incDemographics",
				JSONArray(
					filter.demographics.mapNotNull {
						when (it) {
							Demographic.SHOUNEN -> "shounen"
							Demographic.SHOUJO -> "shoujo"
							Demographic.SEINEN -> "seinen"
							Demographic.JOSEI -> "josei"
							Demographic.KODOMO -> "kodomo"
							else -> null
						}
					},
				),
			)
			if (filter.year != YEAR_UNKNOWN) {
				put("releaseYearMin", filter.year)
				put("releaseYearMax", filter.year)
			} else {
				if (filter.yearFrom != YEAR_UNKNOWN) put("releaseYearMin", filter.yearFrom)
				if (filter.yearTo != YEAR_UNKNOWN) put("releaseYearMax", filter.yearTo)
			}
		}
		val data = graphQL(BROWSE_QUERY, JSONObject().put("select", select))
		val items = data.optJSONArray("get_title_browse_items") ?: return emptyList()
		return items.mapJSONNotNull { item ->
			val id = item.getStringOrNull("id") ?: return@mapJSONNotNull null
			parseManga(id, item.optJSONObject("data") ?: return@mapJSONNotNull null)
		}.distinctBy { it.id }
	}

	private fun parseManga(id: String, jo: JSONObject): Manga {
		val url = "/title/$id"
		val cover = (jo.getStringOrNull("cover_local_url") ?: jo.getStringOrNull("cover_url"))?.toAbsoluteUrl(domain)
		val title = jo.getStringOrNull("title")?.unescape()?.trim()?.nullIfEmpty() ?: id
		return Manga(
			id = uid(id, url),
			title = title,
			altTitles = buildSet {
				jo.getStringOrNull("native_title")?.let(::add)
				jo.getStringOrNull("romanized_title")?.let(::add)
				jo.optJSONArray("alt_titles")?.let { arr ->
					for (i in 0 until arr.length()) {
						arr.optString(i).takeUnless { it.isBlank() || it == "null" }?.let(::add)
					}
				}
			}.mapNotNullTo(LinkedHashSet()) { it.unescape().trim().takeUnless { t -> t.isEmpty() || t == title } },
			url = url,
			publicUrl = "https://$domain$url",
			rating = jo.optDouble("vote_val", 0.0).toFloat().takeIf { it > 0f }?.div(10f) ?: RATING_UNKNOWN,
			contentRating = when (jo.getStringOrNull("content_rating_id")) {
				"safe" -> ContentRating.SAFE
				"suggestive" -> ContentRating.SUGGESTIVE
				"erotica", "pornographic" -> ContentRating.ADULT
				else -> null
			},
			coverUrl = cover,
			tags = (jo.optJSONArray("genre_ids").toStringList() + jo.optJSONArray("format_ids").toStringList())
				.mapToSet { key -> MangaTag(title = TAGS_MAP[key] ?: key.slugToTitle(), key = key, source = source) },
			state = when (jo.getStringOrNull("status")?.lowercase()) {
				"releasing", "ongoing" -> MangaState.ONGOING
				"completed" -> MangaState.FINISHED
				"hiatus" -> MangaState.PAUSED
				"cancelled" -> MangaState.ABANDONED
				"upcoming", "pending" -> MangaState.UPCOMING
				else -> null
			},
			authors = (jo.optJSONArray("authors").toStringList() + jo.optJSONArray("artists").toStringList())
				.mapNotNullTo(LinkedHashSet()) { it.unescape().trim().nullIfEmpty() },
			source = source,
		)
	}

	// ============================== Details ==============================

	override suspend fun getDetails(manga: Manga): Manga {
		val titleId = manga.url.removeSuffix("/").substringAfterLast("/").substringBefore('-')
		var node = fetchTitleNode(titleId) ?: throw NotFoundException("Title not found", manga.publicUrl)
		if (node.optBoolean("is_merged")) {
			node.getStringOrNull("merged_to")?.takeIf { it.isNotBlank() && it != titleId }?.let { mergedId ->
				node = fetchTitleNode(mergedId) ?: node
			}
		}
		val details = parseManga(titleId, node)
		val chapters = fetchChapters(node.optJSONArray("comic_ids").toStringList())
		return manga.copy(
			title = details.title,
			altTitles = details.altTitles,
			rating = details.rating,
			contentRating = details.contentRating ?: manga.contentRating,
			coverUrl = details.coverUrl ?: manga.coverUrl,
			largeCoverUrl = details.coverUrl,
			tags = details.tags,
			state = details.state,
			authors = details.authors,
			description = node.getStringOrNull("description")?.unescape()?.trim()
				?.let { Entities.escape(it).replace("\n", "<br>") }
				.orEmpty(),
			chapters = chapters,
		)
	}

	private suspend fun fetchTitleNode(id: String): JSONObject? =
		graphQL(TITLE_QUERY, JSONObject().put("id", id))
			.optJSONObject("get_title_titleNode")
			?.optJSONObject("data")

	// ============================== Chapters ==============================

	private suspend fun fetchChapters(comicIds: List<String>): List<MangaChapter> = coroutineScope {
		val semaphore = Semaphore(MAX_CONCURRENT_REQUESTS)
		val editions = comicIds.distinct().map { id ->
			async { semaphore.withPermit { fetchEdition(id) } }
		}.awaitAll().filterNotNull()

		// Per language: the source with the most chapters first, then the others that hold numbers it lacks.
		val selected = editions.groupBy { languageName(it.language) }.mapValues { (_, list) ->
			val covered = HashSet<Int>()
			list.sortedWith(compareByDescending<Edition> { it.chapterCount }.thenByDescending { it.lastPublic })
				.filterIndexed { index, edition ->
					val numbers = edition.numbers()
					(index == 0 || numbers.any { it !in covered }).also { keep ->
						if (keep) covered.addAll(numbers)
					}
				}
		}
		val chapterLists = selected.values.flatten().map { edition ->
			async { edition to semaphore.withPermit { fetchChapterList(edition.id) } }
		}.awaitAll().toMap()

		selected.map { (branch, list) ->
			mergeChapters(branch, list.map { it to chapterLists[it].orEmpty() })
		}.filter { it.isNotEmpty() }
			// Branch starting at the lowest chapter first, so the whole list still reads oldest first.
			.sortedBy { branch -> branch.minOfOrNull { ch -> ch.number.takeIf { it > 0f } ?: Float.MAX_VALUE } }
			.flatten()
			.distinctBy { it.id }
	}

	private fun mergeChapters(branch: String, sources: List<Pair<Edition, List<JSONObject>>>): List<MangaChapter> {
		val keys = HashSet<Pair<Int, Float>>()
		val numbers = HashSet<Float>()
		val result = ArrayList<MangaChapter>()
		for ((index, pair) in sources.withIndex()) {
			val (edition, items) = pair
			val primary = index == 0
			for (jo in items) {
				val id = jo.getStringOrNull("id") ?: continue
				val number = (jo.getFloatOrNull("chaNum") ?: jo.getFloatOrNull("serial"))?.takeIf { it > 0f } ?: 0f
				val volume = jo.getFloatOrNull("volNum")?.toInt()?.coerceAtLeast(0) ?: 0
				if (number > 0f) {
					if (primary) {
						if (!keys.add(volume to number)) continue
					} else {
						if (number in numbers || !keys.add(volume to number)) continue
					}
					numbers.add(number)
				} else if (!primary) {
					continue
				}
				val url = "/chapter/$id"
				result += MangaChapter(
					id = uid(id, url),
					title = chapterTitle(jo, number),
					number = number,
					volume = volume,
					url = url,
					scanlator = edition.label,
					uploadDate = jo.getLongOrDefault("datePublic", 0L).takeIf { it > 0L }
						?: jo.getLongOrDefault("dateCreate", 0L),
					branch = branch,
					source = source,
				)
			}
		}
		val resetsOnNewVolume = sources.firstOrNull()?.first?.resetsOnNewVolume == true
		return if (resetsOnNewVolume) {
			result.sortedWith(compareBy<MangaChapter> { it.volume }.thenBy { it.number })
		} else {
			result.sortedWith(compareBy<MangaChapter> { it.number }.thenBy { it.volume })
		}
	}

	private fun chapterTitle(jo: JSONObject, number: Float): String? {
		val name = jo.getStringOrNull("dname")?.unescape()?.trim()?.nullIfEmpty()
		val title = jo.getStringOrNull("title")?.unescape()?.trim()?.nullIfEmpty()
		return when {
			name != null && title != null && !name.contains(title, ignoreCase = true) -> "$name: $title"
			name != null -> name
			title != null -> title
			number > 0f -> "Chapter ${number.toString().removeSuffix(".0")}"
			else -> null
		}
	}

	private suspend fun fetchEdition(comicId: String): Edition? {
		val node = retrying {
			graphQL(EDITION_QUERY, JSONObject().put("id", comicId))
		}.optJSONObject("get_comicNode")?.optJSONObject("data") ?: return null
		val isLive = node.optBoolean("isPublic", true) &&
			(node.getStringOrNull("dbStatus") ?: "normal") == "normal"
		val chapterCount = node.optInt("chaps_normal", 0)
		if (!isLive || chapterCount <= 0) {
			return null
		}
		return Edition(
			id = comicId,
			language = node.getStringOrNull("translatedLanguage"),
			label = node.getStringOrNull("subName")?.unescape()?.replace(whitespaceRegex, " ")?.trim()
				?.takeUnless { it.isEmpty() || it.startsWith("src-site:") },
			chapterCount = chapterCount,
			lastPublic = node.optJSONObject("chapterNode_up_to")?.optJSONObject("data")
				?.getLongOrDefault("datePublic", 0L) ?: 0L,
			resetsOnNewVolume = node.optBoolean("is_chaNumResetOnNewVol", false),
			ranges = node.optJSONArray("chaNums_range")?.mapJSONNotNull { range ->
				val from = range.optInt("from", -1)
				val to = range.optInt("to", -1)
				if (from in 0..to) from..to else null
			}.orEmpty(),
		)
	}

	private suspend fun fetchChapterList(comicId: String): List<JSONObject> {
		val result = ArrayList<JSONObject>()
		var page = 1
		while (page <= MAX_CHAPTER_PAGES) {
			val select = JSONObject()
				.put("comic_id", comicId)
				.put("page", page)
				.put("size", CHAPTER_PAGE_SIZE)
				.put("sortby", "chapter_desc")
			val list = retrying {
				graphQL(CHAPTER_LIST_QUERY, JSONObject().put("select", select))
			}.optJSONObject("get_comic_chapterList_uniqList") ?: break
			list.optJSONArray("items")?.mapJSONNotNullTo(result) { it.optJSONObject("data") }
			val next = list.optJSONObject("paging")?.optInt("next", 0) ?: 0
			if (next <= page) break
			page = next
		}
		return result
	}

	// ============================== Pages ==============================

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val chapterId = chapter.url.removeSuffix("/").substringAfterLast("/")
		val urls = graphQL(PAGES_QUERY, JSONObject().put("id", chapterId))
			.optJSONObject("get_chapterNode")
			?.optJSONObject("data")
			?.optJSONArray("imageUrls")
			?: throw NotFoundException("Chapter not found", chapter.url.toAbsoluteUrl(domain))
		val chapterKey = chapterId.takeIf { it.matches(idRegex) }?.toLongOrNull(36)
		return urls.toStringList().mapIndexed { index, url ->
			val imageUrl = url.toAbsoluteUrl(domain)
			MangaPage(
				id = chapterKey?.let { generateUid(it * MAX_PAGES + index) } ?: generateUid(imageUrl),
				url = imageUrl,
				preview = null,
				source = source,
			)
		}
	}

	override suspend fun resolveLink(resolver: LinkResolver, link: HttpUrl): Manga? {
		val segments = link.pathSegments
		if (segments.size < 2 || segments[0] != "title") {
			return null
		}
		val titleId = segments[1].substringBefore('-').takeIf { it.isNotBlank() } ?: return null
		val url = "/title/$titleId"
		val stub = Manga(
			id = uid(titleId, url),
			title = titleId,
			altTitles = emptySet(),
			url = url,
			publicUrl = "https://$domain$url",
			rating = RATING_UNKNOWN,
			contentRating = null,
			coverUrl = null,
			tags = emptySet(),
			state = null,
			authors = emptySet(),
			source = source,
		)
		return getDetails(stub)
	}

	// ============================== Helpers ==============================

	/**
	 * The site's ids are base-36 strings handed out in sequence, and [generateUid] of a string collides for
	 * neighbours such as `on4bkq6` and `on4bm36`. Their numeric value is unique.
	 */
	private fun uid(id: String, url: String): Long = id.takeIf { it.matches(idRegex) }?.toLongOrNull(36)
		?.let { generateUid(it) }
		?: generateUid(url)

	private suspend fun graphQL(query: String, variables: JSONObject): JSONObject {
		val payload = JSONObject()
			.put("query", query)
			.put("variables", variables)
		val json = webClient.httpPost("https://$domain/query/".toHttpUrl(), payload).parseJson()
		json.optJSONArray("errors")?.let { errors ->
			if (errors.length() > 0) throw GraphQLException(errors)
		}
		return json.optJSONObject("data") ?: JSONObject()
	}

	/** One retry for a request among the many a title's details need, so a single hiccup doesn't fail it. */
	private suspend fun <T> retrying(block: suspend () -> T): T = try {
		block()
	} catch (e: IOException) {
		if (e is GraphQLException) throw e
		block()
	}

	private class Edition(
		val id: String,
		val language: String?,
		val label: String?,
		val chapterCount: Int,
		val lastPublic: Long,
		val resetsOnNewVolume: Boolean,
		val ranges: List<IntRange>,
	) {

		fun numbers(): Set<Int> {
			val result = HashSet<Int>()
			for (range in ranges) {
				if (result.size + (range.last - range.first) > MAX_RANGE_NUMBERS) break
				result.addAll(range)
			}
			return result
		}
	}

	private fun languageName(code: String?): String {
		if (code.isNullOrBlank() || code == "_t") {
			return "Other"
		}
		val locale = code.toLocale()
		val name = locale.getDisplayName(locale)
		return if (name.isBlank() || name.equals(code, ignoreCase = true)) code else name.toTitleCase(locale)
	}

	private fun String.toLocale(): Locale = Locale.forLanguageTag(replace('_', '-'))

	private fun Locale.toLangCode(): String {
		val tag = toLanguageTag().lowercase(Locale.ROOT).replace('-', '_')
		return if (tag in LANGUAGES) tag else language
	}

	private fun JSONObject.getFloatOrNull(name: String): Float? =
		if (isNull(name)) null else optDouble(name).takeUnless { it.isNaN() }?.toFloat()

	private fun JSONArray?.toStringList(): List<String> {
		if (this == null) return emptyList()
		val result = ArrayList<String>(length())
		for (i in 0 until length()) {
			if (isNull(i)) continue
			optString(i).takeUnless { it.isBlank() }?.let(result::add)
		}
		return result
	}

	private fun String.unescape(): String = Parser.unescapeEntities(this, false)

	private fun String.slugToTitle(): String = split('_').joinToString(" ") { it.toTitleCase(Locale.ENGLISH) }

	private companion object {

		const val MAX_CONCURRENT_REQUESTS = 6
		const val CHAPTER_PAGE_SIZE = 1000
		const val MAX_CHAPTER_PAGES = 20
		const val MAX_RANGE_NUMBERS = 20_000
		const val MAX_PAGES = 10_000L

		val whitespaceRegex = Regex("\\s+")
		val idRegex = Regex("[1-9a-z][0-9a-z]{0,11}")

		val TITLE_FIELDS = """
			title
			alt_titles
			native_title
			romanized_title
			authors
			artists
			status
			content_rating_id
			genre_ids
			format_ids
			vote_val
			cover_local_url
			cover_url
		"""

		val BROWSE_QUERY = $$"""
			query get_title_browse_items($select: Title_Browse_Select) {
				get_title_browse_items(select: $select) {
					id
					data {
						$$TITLE_FIELDS
					}
				}
			}
		"""

		val TITLE_QUERY = $$"""
			query get_title_titleNode($id: ID!) {
				get_title_titleNode(id: $id) {
					id
					data {
						$$TITLE_FIELDS
						description
						is_merged
						merged_to
						comic_ids
					}
				}
			}
		"""

		val EDITION_QUERY = $$"""
			query get_comicNode($id: ID!) {
				get_comicNode(id: $id) {
					id
					data {
						subName
						dbStatus
						isPublic
						translatedLanguage
						chaps_normal
						is_chaNumResetOnNewVol
						chaNums_range { from to }
						chapterNode_up_to { data { datePublic } }
					}
				}
			}
		"""

		val CHAPTER_LIST_QUERY = $$"""
			query get_comic_chapterList_uniqList($select: Select_Comic_ChapterList_UniqList) {
				get_comic_chapterList_uniqList(select: $select) {
					paging { next total }
					items {
						id
						data {
							id
							dname
							title
							chaNum
							volNum
							serial
							dateCreate
							datePublic
						}
					}
				}
			}
		"""

		val PAGES_QUERY = $$"""
			query get_chapterNode($id: ID!) {
				get_chapterNode(id: $id) {
					id
					data {
						imageUrls
					}
				}
			}
		"""

		/** Translation languages that have titles on the site (codes as the API uses them). */
		val LANGUAGES = setOf(
			"en", "fr", "es", "es_419", "pt", "pt_br", "ja", "ko", "zh", "zh_hk", "ru", "id", "vi", "it", "pl",
			"tr", "de", "th", "uk", "hu", "ar", "fa", "cs", "mn", "bg", "ro", "ca", "hi", "fil", "he", "my",
			"sk", "ms", "kk", "km", "bn", "nl", "el", "sr", "ka", "ur", "fi", "sv", "az", "da", "eo", "eu",
			"la", "lt", "jv", "te", "si", "uz", "cv", "et", "be", "hr", "ml", "ne", "no", "ga", "sl", "sq",
			"gl", "ta", "ceb", "sh",
		)

		/** Genres and formats that have titles, both filtered with `incGenres` / `excGenres`. */
		val TAGS = listOf(
			"1_koma" to "1-Koma",
			"4_koma" to "4-Koma",
			"action" to "Action",
			"adaptation" to "Adaptation",
			"adult" to "Adult",
			"adventure" to "Adventure",
			"age_gap" to "Age Gap",
			"aliens" to "Aliens",
			"animals" to "Animals",
			"anthology" to "Anthology",
			"art_by_ai" to "Art-by-AI",
			"artbook" to "Artbook",
			"award_winning" to "Award Winning",
			"bara" to "Bara",
			"beasts" to "Beasts",
			"blackmail" to "Blackmail",
			"bloody" to "Bloody",
			"bodyswap" to "Bodyswap",
			"boys" to "Boys",
			"boys_love" to "Boys Love",
			"brocon_siscon" to "Brocon Siscon",
			"cars" to "Cars",
			"cheating_infidelity" to "Cheating/Infidelity",
			"childhood_friends" to "Childhood Friends",
			"college_life" to "College Life",
			"comedy" to "Comedy",
			"comic" to "Comic",
			"contest_winning" to "Contest Winning",
			"cooking" to "Cooking",
			"crime" to "Crime",
			"crossdressing" to "Crossdressing",
			"cultivation" to "Cultivation",
			"death_game" to "Death Game",
			"degeneratemc" to "Degeneratemc",
			"delinquents" to "Delinquents",
			"dementia" to "Dementia",
			"demons" to "Demons",
			"doujinshi" to "Doujinshi",
			"drama" to "Drama",
			"dungeons" to "Dungeons",
			"ecchi" to "Ecchi",
			"emperors_daughter" to "Emperor's Daughter",
			"fan_colored" to "Fan Colored",
			"fantasy" to "Fantasy",
			"fanwork" to "Fanwork",
			"female_protagonists" to "Female Protagonists",
			"fetish" to "Fetish",
			"full_color" to "Full Color",
			"futa" to "Futa",
			"game" to "Game",
			"genderswap" to "Genderswap",
			"ghosts" to "Ghosts",
			"girls" to "Girls",
			"girls_love" to "Girls Love",
			"gore" to "Gore",
			"gyaru" to "Gyaru",
			"harem" to "Harem",
			"harlequin" to "Harlequin",
			"hentai" to "Hentai",
			"historical" to "Historical",
			"horror" to "Horror",
			"incest" to "Incest",
			"isekai" to "Isekai",
			"kids" to "Kids",
			"light_novel" to "Light Novel",
			"loli" to "Loli",
			"long_strip" to "Long Strip",
			"longstrip" to "Longstrip",
			"mafia" to "Mafia",
			"magic" to "Magic",
			"magical_girls" to "Magical Girls",
			"male_protagonists" to "Male Protagonists",
			"martial_arts" to "Martial Arts",
			"master_servant" to "Master-Servant",
			"mature" to "Mature",
			"mecha" to "Mecha",
			"medical" to "Medical",
			"military" to "Military",
			"monster_girls" to "Monster Girls",
			"monsters" to "Monsters",
			"music" to "Music",
			"mystery" to "Mystery",
			"netorare_ntr" to "Netorare/NTR",
			"netori" to "Netori",
			"ninja" to "Ninja",
			"office_workers" to "Office Workers",
			"official_colored" to "Official Colored",
			"omegaverse" to "Omegaverse",
			"oneshot" to "Oneshot",
			"parody" to "Parody",
			"partially_colored" to "Partially Colored",
			"partially_colored_webtoon" to "Partially Colored Webtoon",
			"philosophical" to "Philosophical",
			"police" to "Police",
			"post_apocalyptic" to "Post-Apocalyptic",
			"psychological" to "Psychological",
			"regression" to "Regression",
			"reincarnation" to "Reincarnation",
			"revenge" to "Revenge",
			"reverse_harem" to "Reverse Harem",
			"reverse_isekai" to "Reverse Isekai",
			"romance" to "Romance",
			"royal_family" to "Royal Family",
			"royalty" to "Royalty",
			"samurai" to "Samurai",
			"school_life" to "School Life",
			"sci_fi" to "Sci-Fi",
			"sexual_violence" to "Sexual Violence",
			"shota" to "Shota",
			"shoujo_ai" to "Shoujo Ai",
			"shounen_ai" to "Shounen Ai",
			"showbiz" to "Showbiz",
			"slice_of_life" to "Slice of Life",
			"sm_bdsm_sub_dom" to "SM/BDSM/SUB-DOM",
			"smut" to "Smut",
			"space" to "Space",
			"sports" to "Sports",
			"spy" to "Spy",
			"step_family" to "Step-family",
			"super_power" to "Super Power",
			"superhero" to "Superhero",
			"supernatural" to "Supernatural",
			"survival" to "Survival",
			"teacher_student" to "Teacher-Student",
			"thriller" to "Thriller",
			"time_travel" to "Time Travel",
			"tower_climbing" to "Tower Climbing",
			"traditional_games" to "Traditional Games",
			"tragedy" to "Tragedy",
			"transmigration" to "Transmigration",
			"vampires" to "Vampires",
			"video_games" to "Video Games",
			"villainess" to "Villainess",
			"violence" to "Violence",
			"virtual_reality" to "Virtual Reality",
			"web_comic" to "Web Comic",
			"web_novel" to "Web Novel",
			"webtoon" to "Webtoon",
			"wuxia" to "Wuxia",
			"xianxia" to "Xianxia",
			"xuanhuan" to "Xuanhuan",
			"yakuzas" to "Yakuzas",
			"youkai" to "Youkai",
			"zombies" to "Zombies",
		)

		val TAGS_MAP = TAGS.toMap()
	}
}
