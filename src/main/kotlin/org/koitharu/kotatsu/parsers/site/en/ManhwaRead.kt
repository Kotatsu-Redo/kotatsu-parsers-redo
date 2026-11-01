package org.koitharu.kotatsu.parsers.site.en

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.Headers
import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Demographic
import org.koitharu.kotatsu.parsers.model.Favicon
import org.koitharu.kotatsu.parsers.model.Favicons
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
import org.koitharu.kotatsu.parsers.util.attrAsRelativeUrlOrNull
import org.koitharu.kotatsu.parsers.util.attrOrNull
import org.koitharu.kotatsu.parsers.util.findGroupValue
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.mapChapters
import org.koitharu.kotatsu.parsers.util.mapNotNullToSet
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.oneOrThrowIfMany
import org.koitharu.kotatsu.parsers.util.ownTextOrNull
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.parseSafe
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.parsers.util.src
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy
import org.koitharu.kotatsu.parsers.util.textOrNull
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.toTitleCase
import org.koitharu.kotatsu.parsers.util.urlEncoded
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.round

@MangaSourceParser("MANHWAREAD", "ManhwaRead", "en", ContentType.HENTAI)
internal class ManhwaRead(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.MANHWAREAD, pageSize = 24) {

	override val configKeyDomain: ConfigKey.Domain = ConfigKey.Domain("manhwaread.com", "manhwaread.org")

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
		.add("Referer", "https://$domain/")
		.build()

	override suspend fun getFavicons(): Favicons = Favicons(
		listOf(
			Favicon("https://mancover.xyz/cover/2024/12/manhwaread-150x150.png", 32, "icon"),
			Favicon("https://mancover.xyz/cover/2024/12/manhwaread-350x350.png", 192, "icon"),
		),
		domain,
	)

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.UPDATED_ASC,
		SortOrder.NEWEST,
		SortOrder.NEWEST_ASC,
		SortOrder.POPULARITY,
		SortOrder.POPULARITY_ASC,
		SortOrder.POPULARITY_TODAY,
		SortOrder.POPULARITY_WEEK,
		SortOrder.POPULARITY_MONTH,
		SortOrder.POPULARITY_YEAR,
		SortOrder.RATING,
		SortOrder.RATING_ASC,
		SortOrder.ALPHABETICAL,
		SortOrder.ALPHABETICAL_DESC,
		SortOrder.RELEVANCE,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = true,
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isYearSupported = true,
			isAuthorSearchSupported = true,
		)

	private data class TagRegistry(
		val availableTags: Set<MangaTag>,
		val idsByLowerTitle: Map<String, String>,
	)

	private val tagRegistry = suspendLazy(initializer = ::fetchTagRegistry)
	private val resolvedIdsCache = ConcurrentHashMap<String, String>()
	private val slugsByKey = ConcurrentHashMap<String, Pair<String, String>>()

	override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions(
		availableTags = tagRegistry.get().availableTags,
		availableStates = EnumSet.of(
			MangaState.ONGOING,
			MangaState.FINISHED,
			MangaState.PAUSED,
			MangaState.ABANDONED,
		),
		availableDemographics = EnumSet.of(
			Demographic.SEINEN,
			Demographic.SHOUNEN,
			Demographic.JOSEI,
		),
	)

	override suspend fun getListPage(
		page: Int,
		order: SortOrder,
		filter: MangaListFilter,
	): List<Manga> {
		val singleSlugTag = filter.singleSlugOnlyTag()
		if (singleSlugTag != null) {
			val (taxPath, slug) = singleSlugTag
			val url = buildString {
				append("https://").append(domain).append('/').append(taxPath).append('/').append(slug).append('/')
				if (page > 1) {
					append("page/").append(page).append('/')
				}
				val sortQuery = order.toSortQuery()
				if (sortQuery.isNotEmpty()) {
					append('?').append(sortQuery)
				}
			}
			val doc = webClient.httpGet(url).parseHtml()
			return parseMangaList(doc, ".manga-grid .manga-item")
		}

		val authorParam = if (!filter.author.isNullOrBlank()) {
			val authorQuery = filter.author.trim()
			findTermId(authorQuery, "manga_author")?.let { "authors[]=$it" }
				?: findTermId(authorQuery, "manga_artist")?.let { "artists[]=$it" }
				?: return emptyList()
		} else {
			null
		}

		val hasAdvancedFilters = !filter.query.isNullOrEmpty() ||
			filter.tags.isNotEmpty() ||
			filter.tagsExclude.isNotEmpty() ||
			filter.states.isNotEmpty() ||
			filter.demographics.isNotEmpty() ||
			filter.year != YEAR_UNKNOWN ||
			authorParam != null

		val queries = ArrayList<String>()
		queries.add("s=${filter.query?.trim()?.urlEncoded().orEmpty()}")

		if (hasAdvancedFilters) {
			queries.add("keyword_mode=contains")
			queries.add("s_mode=AND")
		}

		filter.states.oneOrThrowIfMany()?.let { state ->
			val statusVal = when (state) {
				MangaState.ONGOING -> "ongoing"
				MangaState.FINISHED -> "completed"
				MangaState.PAUSED -> "on-hold"
				MangaState.ABANDONED -> "canceled"
				else -> null
			}
			if (statusVal != null) {
				queries.add("status=$statusVal")
			}
		}

		if (filter.year != YEAR_UNKNOWN) {
			queries.add("year_range=${filter.year}-${filter.year}")
		}

		if (authorParam != null) {
			queries.add(authorParam)
		}

		for (demographic in filter.demographics) {
			when (demographic) {
				Demographic.SEINEN -> queries.add("including[]=$GENRE_ID_SEINEN")
				Demographic.SHOUNEN -> queries.add("including[]=$GENRE_ID_SHOUNEN")
				Demographic.JOSEI -> queries.add("including[]=$GENRE_ID_JOSEI")
				else -> {}
			}
		}

		for (tag in filter.tags) {
			val id = resolveTagId(tag) ?: continue
			queries.add("including[]=$id")
		}

		for (tag in filter.tagsExclude) {
			val id = resolveTagId(tag) ?: continue
			queries.add("excluding[]=$id")
		}

		if (!(order == SortOrder.RELEVANCE && !filter.query.isNullOrEmpty())) {
			val sortQuery = order.toSortQuery()
			if (sortQuery.isNotEmpty()) {
				queries.add(sortQuery)
			}
		}

		val url = buildString {
			append("https://").append(domain)
			if (page > 1) {
				append("/page/").append(page)
			}
			append("/?")
			append(queries.joinToString("&"))
		}

		val doc = webClient.httpGet(url).parseHtml()
		return parseMangaList(doc, ".manga-grid .manga-item")
	}

	private fun parseMangaList(doc: Document, selector: String): List<Manga> {
		return doc.select(selector).mapNotNull { item ->
			val linkEl = item.selectFirst("a.manga-item__link")
				?: item.selectFirst("a[href*='/manhwa/']")
				?: return@mapNotNull null
			val href = linkEl.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
			val title = linkEl.text().trim().ifEmpty {
				item.selectFirst("img")?.attrOrNull("alt").orEmpty()
			}
			if (title.isEmpty()) {
				return@mapNotNull null
			}
			val altTitles = item.selectFirst(".alt-title")?.textOrNull()
				?.split('|')
				?.mapNotNullToSet { it.trim().nullIfEmpty() }
				.orEmpty()
			val authors = item.select(".author-artist span")
				.mapNotNullToSet { it.text().trim().nullIfEmpty() }
			val rating = item.selectFirst(".manga-item__rating span")?.text()
				?.toFloatOrNull()
				?.takeIf { it > 0f }
				?.div(5f)
				?: RATING_UNKNOWN
			val state = parseState(item.selectFirst(".manga-status")?.attrOrNull("data-status"))
			val coverUrl = item.selectFirst(".manga-item__img img, img")?.src()

			Manga(
				id = generateUid(href),
				title = title,
				altTitles = altTitles,
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				rating = rating,
				contentRating = ContentRating.ADULT,
				coverUrl = coverUrl,
				tags = emptySet(),
				state = state,
				authors = authors,
				source = source,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val summary = doc.selectFirst("#mangaSummary") ?: doc

		val title = summary.selectFirst(".manga-titles h1")?.textOrNull()?.trim() ?: manga.title
		val altTitles = summary.selectFirst(".manga-titles h2")?.textOrNull()
			?.split('|')
			?.mapNotNullToSet { it.trim().nullIfEmpty() }
			?: manga.altTitles

		val coverImg = summary.selectFirst("img[src*='mancover'], .flex.justify-center img, img")?.src()
		val rating = summary.selectFirst(".rating__current")?.text()
			?.toFloatOrNull()
			?.takeIf { it > 0f }
			?.div(5f)
			?: manga.rating

		val state = parseState(summary.selectFirst(".manga-status")?.attrOrNull("data-status")) ?: manga.state

		val authors = summary.select("a[href*='/author/'], a[href*='/artist/']").mapNotNullToSet { a ->
			(a.selectFirst("span:nth-child(2)")?.textOrNull() ?: a.ownTextOrNull())?.trim()?.nullIfEmpty()
		}.ifEmpty { manga.authors }

		val tagsByTitle = LinkedHashMap<String, MangaTag>()

		for (genreLink in summary.select("a[href*='/genre/']")) {
			val rawName = genreLink.textOrNull()?.trim() ?: continue
			val tagTitle = rawName.toTitleCase(sourceLocale)
			val lower = tagTitle.lowercase(Locale.ROOT)
			val slug = genreLink.attrOrNull("href")?.trimEnd('/')?.substringAfterLast('/')?.nullIfEmpty() ?: continue
			slugsByKey.putIfAbsent(lower, "genre" to slug)
			tagsByTitle.putIfAbsent(lower, MangaTag(title = tagTitle, key = lower, source = source))
		}

		for (tagLink in summary.select("a[href*='/tag/']")) {
			val rawName = (tagLink.selectFirst("span:nth-child(2)")?.textOrNull() ?: tagLink.ownTextOrNull())?.trim() ?: continue
			val tagTitle = rawName.toTitleCase(sourceLocale)
			val lower = tagTitle.lowercase(Locale.ROOT)
			val slug = tagLink.attrOrNull("href")?.trimEnd('/')?.substringAfterLast('/')?.nullIfEmpty() ?: continue
			slugsByKey.putIfAbsent(lower, "tag" to slug)
			tagsByTitle.putIfAbsent(lower, MangaTag(title = tagTitle, key = lower, source = source))
		}

		val description = summary.selectFirst("#mangaDesc .manga-desc__content")?.html()?.trim().orEmpty()

		val utc = TimeZone.getTimeZone("UTC")
		val updatedDateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ENGLISH).apply { timeZone = utc }
		val chapterDateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.ENGLISH).apply { timeZone = utc }

		val updatedText = summary.selectFirst("div.text-primary:contains(Updated:)")
			?.nextElementSibling()
			?.text()
			?.trim()
		val updatedDate = if (!updatedText.isNullOrEmpty() && REGEX_UPDATED_DATE.matches(updatedText)) {
			updatedDateFormat.parseSafe(updatedText)
		} else {
			0L
		}

		val usedNumbers = HashSet<Float>()
		val chapters = doc.select("#chaptersList a.chapter-item").mapChapters { _, a ->
			val href = a.attrAsRelativeUrlOrNull("href") ?: return@mapChapters null
			val chapterName = a.selectFirst(".chapter-item__name")?.textOrNull()?.trim()
			val rawNumber = parseChapterNumber(chapterName, href)
			var number = rawNumber
			if (number > 0f) {
				while (!usedNumbers.add(number)) {
					number = round((number + 0.01f) * 100f) / 100f
				}
			}
			val dateText = a.selectFirst(".chapter-item__date")?.text()?.trim()
			val uploadDate = when {
				dateText.isNullOrEmpty() -> 0L
				dateText.equals("New", ignoreCase = true) -> updatedDate
				REGEX_CHAPTER_DATE.matches(dateText) -> chapterDateFormat.parseSafe(dateText)
				else -> 0L
			}
			MangaChapter(
				id = generateUid(href),
				title = chapterName,
				number = number,
				volume = 0,
				url = href,
				scanlator = null,
				uploadDate = uploadDate,
				branch = null,
				source = source,
			)
		}

		return manga.copy(
			title = title,
			altTitles = altTitles,
			coverUrl = manga.coverUrl?.nullIfEmpty() ?: coverImg,
			largeCoverUrl = coverImg ?: manga.largeCoverUrl,
			rating = rating,
			contentRating = ContentRating.ADULT,
			tags = tagsByTitle.values.toSet(),
			state = state,
			authors = authors,
			description = description,
			chapters = chapters,
		)
	}

	override suspend fun getRelatedManga(seed: Manga): List<Manga> {
		val doc = webClient.httpGet(seed.url.toAbsoluteUrl(domain)).parseHtml()
		return parseMangaList(doc, ".swiper-wrapper .manga-item")
			.filterNot { it.id == seed.id }
			.distinctBy { it.id }
	}

	override suspend fun resolveLink(resolver: LinkResolver, link: HttpUrl): Manga? {
		val host = link.host.removePrefix("www.")
		if (host !in configKeyDomain.presetValues && host != domain.removePrefix("www.")) {
			return null
		}
		val segments = link.pathSegments
		if (segments.firstOrNull() != "manhwa") {
			return null
		}
		val slug = segments.getOrNull(1)?.takeIf { it.isNotBlank() && it != "page" } ?: return null
		val relativeUrl = "/manhwa/$slug/"
		return getDetails(
			Manga(
				id = generateUid(relativeUrl),
				title = slug,
				altTitles = emptySet(),
				url = relativeUrl,
				publicUrl = relativeUrl.toAbsoluteUrl(domain),
				rating = RATING_UNKNOWN,
				contentRating = ContentRating.ADULT,
				coverUrl = null,
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				largeCoverUrl = null,
				description = null,
				chapters = null,
				source = source,
			),
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
		val rawJson = doc.select("script").firstNotNullOfOrNull { script ->
			script.data().findGroupValue(REGEX_CHAPTER_DATA)
		} ?: throw ParseException("Cannot find chapterData in chapter page", chapter.url)

		val chapterObj = JSONObject(rawJson)
		val base = chapterObj.getString("base").trimEnd('/')
		val encodedData = chapterObj.getString("data").trim()
		if (encodedData.isEmpty()) {
			return emptyList()
		}
		val padded = encodedData.padEnd((encodedData.length + 3) / 4 * 4, '=')
		val decodedJson = context.decodeBase64(padded).toString(Charsets.UTF_8)
		val pagesArray = JSONArray(decodedJson)

		val result = ArrayList<MangaPage>(pagesArray.length())
		for (i in 0 until pagesArray.length()) {
			val item = pagesArray.getJSONObject(i)
			val src = item.optString("src").trim().trimStart('/')
			if (src.isEmpty() || src.endsWith(".mp4", ignoreCase = true)) {
				continue
			}
			val imageUrl = "$base/$src"
			result.add(
				MangaPage(
					id = generateUid(src),
					url = imageUrl,
					preview = null,
					source = source,
				),
			)
		}
		return result
	}

	private data class RawTerm(
		val id: String,
		val title: String,
		val count: Int,
	)

	private suspend fun fetchTagRegistry(): TagRegistry {
		val (genreResponse, firstTagResponse) = coroutineScope {
			// These two must not swallow errors: an empty registry would stay cached, and a Cloudflare
			// challenge would never reach the app.
			val g = async { requestTaxonomyPage("manga_genre", offset = 0, includeTotal = true) }
			val t = async { requestTaxonomyPage("manga_tag", offset = 0, includeTotal = true) }
			g.await() to t.await()
		}
		val totalTags = firstTagResponse.optInt("total", DEFAULT_TOTAL_TAGS)

		val rawTerms = ArrayList<RawTerm>(totalTags + 40)
		genreResponse.optJSONArray("results")?.let { rawTerms.addAll(parseTermsArray(it)) }
		firstTagResponse.optJSONArray("results")?.let { rawTerms.addAll(parseTermsArray(it)) }

		val offsets = (40 until totalTags step 40).toList()
		for (chunk in offsets.chunked(TAG_FETCH_CONCURRENCY)) {
			val chunkResults = coroutineScope {
				chunk.map { offset ->
					async {
						fetchTaxonomyPage("manga_tag", offset = offset, includeTotal = false)
							?.optJSONArray("results")
							?.let(::parseTermsArray)
							.orEmpty()
					}
				}.awaitAll()
			}
			for (list in chunkResults) {
				rawTerms.addAll(list)
			}
		}

		val bestByTitle = LinkedHashMap<String, RawTerm>(rawTerms.size)
		for (term in rawTerms) {
			val lower = term.title.lowercase(Locale.ROOT)
			val existing = bestByTitle[lower]
			if (existing == null || term.count > existing.count) {
				bestByTitle[lower] = term
			}
		}

		val idsByLowerTitle = HashMap<String, String>(bestByTitle.size)
		val available = LinkedHashSet<MangaTag>()

		for ((lower, term) in bestByTitle) {
			idsByLowerTitle[lower] = term.id
			resolvedIdsCache[lower] = term.id
			if (term.count >= MIN_POPULAR_TAG_COUNT) {
				available.add(
					MangaTag(
						title = term.title,
						key = lower,
						source = source,
					),
				)
			}
		}

		return TagRegistry(
			availableTags = available,
			idsByLowerTitle = idsByLowerTitle,
		)
	}

	private suspend fun fetchTaxonomyPage(
		taxonomy: String,
		offset: Int,
		includeTotal: Boolean,
	): JSONObject? = runCatchingCancellable {
		requestTaxonomyPage(taxonomy, offset, includeTotal)
	}.getOrNull()

	private suspend fun requestTaxonomyPage(
		taxonomy: String,
		offset: Int,
		includeTotal: Boolean,
	): JSONObject {
		val extra = if (includeTotal) "total" else ""
		val url = "https://$domain/wp-admin/admin-ajax.php?action=search_manga_terms&search=&taxonomy=$taxonomy&offset=$offset&extra_fields=$extra&hide_empty=1"
		return webClient.httpGet(url).parseJson()
	}

	private fun parseTermsArray(array: JSONArray): List<RawTerm> {
		val list = ArrayList<RawTerm>(array.length())
		for (i in 0 until array.length()) {
			val item = array.optJSONObject(i) ?: continue
			val id = item.opt("id")?.toString()?.takeIf { it.isNotBlank() } ?: continue
			val unescaped = Parser.unescapeEntities(item.optString("text"), false).trim()
			if (unescaped.isEmpty()) continue
			val title = unescaped.toTitleCase(sourceLocale)
			val count = parseTermCount(item.optString("count"))
			list.add(RawTerm(id = id, title = title, count = count))
		}
		return list
	}

	private fun parseTermCount(raw: String): Int {
		val s = raw.trim().uppercase(Locale.ROOT)
		if (s.isEmpty()) return 0
		if (s.endsWith('K')) {
			return s.dropLast(1).toFloatOrNull()?.times(1000f)?.toInt() ?: 0
		}
		return s.toIntOrNull() ?: 0
	}

	private suspend fun resolveTagId(tag: MangaTag): String? {
		val lowerTitle = tag.key.ifEmpty { tag.title.lowercase(Locale.ROOT) }
		tagRegistry.peek()?.idsByLowerTitle?.get(lowerTitle)?.let { return it }
		resolvedIdsCache[lowerTitle]?.let { return it }

		val taxonomyHint = when (slugsByKey[lowerTitle]?.first) {
			"genre" -> "manga_genre"
			"tag" -> "manga_tag"
			else -> null
		}
		val resolved = if (taxonomyHint != null) {
			findTermId(tag.title, taxonomyHint)
				?: findTermId(tag.title, if (taxonomyHint == "manga_genre") "manga_tag" else "manga_genre")
		} else {
			findTermId(tag.title, "manga_tag") ?: findTermId(tag.title, "manga_genre")
		}
		if (resolved != null) {
			resolvedIdsCache[lowerTitle] = resolved
		}
		return resolved
	}

	private suspend fun findTermId(name: String, taxonomy: String): String? = runCatchingCancellable {
		val url = "https://$domain/wp-admin/admin-ajax.php?action=search_manga_terms&search=${name.urlEncoded()}&taxonomy=$taxonomy&offset=0&extra_fields=&hide_empty=1"
		val results = webClient.httpGet(url).parseJson().optJSONArray("results") ?: return@runCatchingCancellable null
		var fallbackId: String? = null
		for (i in 0 until results.length()) {
			val item = results.optJSONObject(i) ?: continue
			val id = item.opt("id")?.toString()?.takeIf { it.isNotBlank() } ?: continue
			val text = Parser.unescapeEntities(item.optString("text"), false).trim()
			if (text.equals(name, ignoreCase = true)) {
				return@runCatchingCancellable id
			}
			if (fallbackId == null && text.contains(name, ignoreCase = true)) {
				fallbackId = id
			}
		}
		fallbackId
	}.getOrNull()

	private fun MangaListFilter.singleSlugOnlyTag(): Pair<String, String>? {
		if (tags.size != 1 ||
			!query.isNullOrEmpty() ||
			tagsExclude.isNotEmpty() ||
			states.isNotEmpty() ||
			demographics.isNotEmpty() ||
			year != YEAR_UNKNOWN ||
			!author.isNullOrBlank()
		) {
			return null
		}
		val tag = tags.first()
		val lowerTitle = tag.key.ifEmpty { tag.title.lowercase(Locale.ROOT) }
		if (tagRegistry.peek()?.idsByLowerTitle?.containsKey(lowerTitle) == true || resolvedIdsCache.containsKey(lowerTitle)) {
			return null
		}
		return slugsByKey[lowerTitle]
	}

	private fun SortOrder.toSortQuery(): String = when (this) {
		SortOrder.UPDATED -> "sortby=release"
		SortOrder.UPDATED_ASC -> "sortby=release&order=asc"
		SortOrder.NEWEST -> "sortby=new"
		SortOrder.NEWEST_ASC -> "sortby=new&order=asc"
		SortOrder.POPULARITY -> "sortby=all_top"
		SortOrder.POPULARITY_ASC -> "sortby=all_top&order=asc"
		SortOrder.POPULARITY_TODAY -> "sortby=daily_top"
		SortOrder.POPULARITY_WEEK -> "sortby=weekly_top"
		SortOrder.POPULARITY_MONTH -> "sortby=monthly_top"
		SortOrder.POPULARITY_YEAR -> "sortby=yearly_top"
		SortOrder.RATING -> "sortby=rating"
		SortOrder.RATING_ASC -> "sortby=rating&order=asc"
		SortOrder.ALPHABETICAL -> "sortby=alphabet&order=asc"
		SortOrder.ALPHABETICAL_DESC -> "sortby=alphabet&order=desc"
		SortOrder.RELEVANCE -> "sortby=release"
		else -> ""
	}

	private fun parseState(status: String?): MangaState? = when (status?.lowercase(Locale.ROOT)) {
		"ongoing" -> MangaState.ONGOING
		"completed" -> MangaState.FINISHED
		"on-hold", "hiatus" -> MangaState.PAUSED
		"canceled", "cancelled", "dropped" -> MangaState.ABANDONED
		else -> null
	}

	private fun parseChapterNumber(chapterName: String?, href: String): Float {
		val sources = listOfNotNull(chapterName, href)
		for (text in sources) {
			REGEX_CHAPTER_DECIMAL.find(text)?.let { match ->
				val whole = match.groupValues[1]
				val frac = match.groupValues[2]
				"$whole.$frac".toFloatOrNull()?.let { return it }
			}
		}
		for (text in sources) {
			REGEX_CHAPTER_INT.find(text)?.let { match ->
				match.groupValues[1].toFloatOrNull()?.let { return it }
			}
		}
		return 0f
	}

	private companion object {
		private const val DEFAULT_TOTAL_TAGS = 1650
		private const val TAG_FETCH_CONCURRENCY = 21
		private const val MIN_POPULAR_TAG_COUNT = 20

		private const val GENRE_ID_SEINEN = "5557"
		private const val GENRE_ID_SHOUNEN = "5541"
		private const val GENRE_ID_JOSEI = "5508"

		private val REGEX_CHAPTER_DATA = Regex("""var\s+chapterData\s*=\s*(\{.*?\})\s*;""", RegexOption.DOT_MATCHES_ALL)
		private val REGEX_CHAPTER_DECIMAL = Regex(
			"""\b(?:chapter|episode|ch|ep)(?:\.|\s|[-_])+\s*(\d+)[x.](\d+)""",
			RegexOption.IGNORE_CASE,
		)
		private val REGEX_CHAPTER_INT = Regex(
			"""\b(?:chapter|episode|ch|ep)(?:\.|\s|[-_])+\s*(\d+)""",
			RegexOption.IGNORE_CASE,
		)
		private val REGEX_CHAPTER_DATE = Regex("""^\d{2}/\d{2}/\d{4}$""")
		private val REGEX_UPDATED_DATE = Regex("""^\d{2}/\d{2}/\d{4}\s+\d{2}:\d{2}$""")
	}
}
