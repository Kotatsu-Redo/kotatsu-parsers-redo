package org.koitharu.kotatsu.parsers.site.all

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.HttpStatusException
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.NotFoundException
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Demographic
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.network.OkHttpWebClient
import org.koitharu.kotatsu.parsers.network.WebClient
import org.koitharu.kotatsu.parsers.util.LinkResolver
import org.koitharu.kotatsu.parsers.util.await
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.oneOrThrowIfMany
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.parseSafe
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale
import java.util.TimeZone

private const val DOMAIN = "mangaball.com"
private const val PAGE_SIZE = 24

internal abstract class MangaBallParser(
	context: MangaLoaderContext,
	source: MangaParserSource,
	private val siteLanguages: Set<String>,
) : PagedMangaParser(context, source, pageSize = PAGE_SIZE), Interceptor {

	override val configKeyDomain = ConfigKey.Domain(DOMAIN)

	private val showSuspiciousContentKey = ConfigKey.ShowSuspiciousContent(false)

	private val httpClient: OkHttpClient by lazy {
		context.httpClient.newBuilder()
			.addInterceptor(this)
			.build()
	}

	private val client: WebClient by lazy { OkHttpWebClient(httpClient, source) }

	private val tagsCache = suspendLazy(initializer = ::loadTags)

	private val chapterDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).apply {
		timeZone = TimeZone.getTimeZone("UTC")
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.RATING,
		SortOrder.NEWEST,
		SortOrder.ALPHABETICAL,
		SortOrder.ALPHABETICAL_DESC,
		SortOrder.RELEVANCE,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = true,
		)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
		keys.add(showSuspiciousContentKey)
	}

	override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions(
		availableTags = tagsCache.get(),
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
			ContentType.COMICS,
		),
		availableDemographics = EnumSet.of(
			Demographic.SHOUNEN,
			Demographic.SHOUJO,
			Demographic.SEINEN,
			Demographic.JOSEI,
		),
	)

	private suspend fun loadTags(): Set<MangaTag> {
		val data = client.httpGet("$API_URL/tag/get-grouped", getApiHeaders()).parseJson().getJSONObject("data")
		val result = LinkedHashSet<MangaTag>()
		for (group in data.keys()) {
			result += data.optJSONArray(group).toMangaTags()
		}
		return result
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val query = filter.query?.trim().orEmpty()
		if (query.startsWith("http://") || query.startsWith("https://")) {
			return if (page == 1) resolveSearchUrl(query) else emptyList()
		}
		val url = "$API_URL/title/search-advanced".toHttpUrl().newBuilder().apply {
			addQueryParameter("page", page.toString())
			addQueryParameter("limit", PAGE_SIZE.toString())
			addQueryParameter("adult_mode", if (config[showSuspiciousContentKey]) "all" else "no_18")
			query.nullIfEmpty()?.let { addQueryParameter("keyword", it) }
			if (filter.tags.isNotEmpty()) {
				addQueryParameter("included_tags", filter.tags.joinToString(",") { it.key })
				addQueryParameter("tag_mode", "AND")
			}
			if (filter.tagsExclude.isNotEmpty()) {
				addQueryParameter("excluded_tags", filter.tagsExclude.joinToString(",") { it.key })
			}
			stateValue(filter.states.oneOrThrowIfMany())?.let { addQueryParameter("status", it) }
			typeValue(filter.types.oneOrThrowIfMany())?.let { addQueryParameter("type", it) }
			demographicValue(filter.demographics.oneOrThrowIfMany())?.let {
				addQueryParameter("publicationDemographic", it)
			}
			// The API only ranks by relevance when sort_by is omitted, which needs a keyword. A keyword
			// search with the default order (UPDATED) is ranked by relevance too, otherwise the exact
			// title is buried under every recently updated title sharing a word with it.
			if (query.isEmpty() || (order != SortOrder.RELEVANCE && order != SortOrder.UPDATED)) {
				val (sortBy, sortOrder) = sortValue(order)
				addQueryParameter("sort_by", sortBy)
				addQueryParameter("sort_order", sortOrder)
			}
		}.build()
		val data = client.httpGet(url, getApiHeaders()).parseJson().optJSONArray("data") ?: return emptyList()
		return List(data.length()) { i ->
			data.optJSONObject(i)?.let(::parseManga)
		}.filterNotNull()
	}

	override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
		// Manga.url is the site slug ("<name>-<ObjectId>"). The chapter listing only accepts the bare
		// ObjectId, so it is taken from the slug when possible and loaded alongside the details.
		val knownId = titleIdFromUrl(manga.url)
		val chaptersDeferred = knownId?.let { async { getChapters(it) } }
		val data = getTitle(knownId ?: manga.url)
		val titleId = data.getTitleId() ?: knownId ?: manga.url
		val chapters = chaptersDeferred?.await() ?: getChapters(titleId)
		parseDetails(manga, data, titleId, chapters)
	}

	private suspend fun getTitle(idOrSlug: String): JSONObject {
		val url = "$API_URL/title/detail/$idOrSlug"
		return client.httpGet(url, getApiHeaders()).parseJson().optJSONObject("data")
			?: throw NotFoundException("Title not found", url)
	}

	private fun parseDetails(manga: Manga, data: JSONObject, titleId: String, chapters: List<MangaChapter>): Manga {
		val tags = data.optJSONArray("tags").toMangaTags()
		val cover = data.extractCover()
		return manga.copy(
			title = data.getStringOrNull("name") ?: manga.title,
			altTitles = data.optJSONArray("alternateName").toStringSet().ifEmpty { manga.altTitles },
			publicUrl = getMangaUrl(titleId),
			coverUrl = cover ?: manga.coverUrl,
			largeCoverUrl = cover ?: manga.largeCoverUrl,
			tags = tags.ifEmpty { manga.tags },
			state = stateFromStatus(data.getStringOrNull("status")) ?: manga.state,
			authors = data.optJSONArray("author").toNameSet().ifEmpty { manga.authors },
			description = data.optJSONArray("description").toStringSet().joinToString("\n\n").nullIfEmpty()
				?: manga.description,
			contentRating = data.contentRating(tags) ?: manga.contentRating,
			chapters = chapters,
		)
	}

	private suspend fun getChapters(titleId: String): List<MangaChapter> {
		val json = httpPostJson(
			"$API_URL/chapter/chapter-listing-by-title-id",
			JSONObject().put("title_id", titleId),
		)
		val data = json.optJSONArray("data") ?: return emptyList()
		val candidates = ArrayList<ChapterCandidate>(data.length())
		for (i in 0 until data.length()) {
			val item = data.optJSONObject(i) ?: continue
			val language = item.getStringOrNull("lang") ?: continue
			if (language !in siteLanguages) {
				continue
			}
			val chapterId = item.getStringOrNull("id") ?: item.getStringOrNull("_id") ?: continue
			val number = item.optDouble("number", 0.0).toFloat().takeUnless { it.isNaN() } ?: 0f
			val volume = item.optDouble("volume", 0.0).toInt()
			val scanlator = item.optJSONObject("group")?.getStringOrNull("name")?.trim()?.nullIfEmpty()
			val numberText = number.toString().removeSuffix(".0")
			candidates += ChapterCandidate(
				language = language,
				chapter = MangaChapter(
					id = generateUid(chapterId),
					title = buildChapterTitle(numberText, volume, item.getStringOrNull("name").orEmpty()),
					number = number,
					volume = volume,
					url = chapterId,
					scanlator = scanlator,
					uploadDate = chapterDateFormat.parseSafe(
						item.getStringOrNull("created_at") ?: item.getStringOrNull("updated_at"),
					),
					branch = language.takeIf { siteLanguages.size > 1 },
					source = source,
				),
			)
		}
		// Several groups often upload the same chapter: keep one per number, preferring the group that
		// covers most of the title so the list does not jump between translations.
		val scanlatorCounts = candidates.groupingBy {
			"${it.language}|${it.chapter.scanlator.orEmpty()}"
		}.eachCount()
		return candidates
			.groupBy { "${it.language}|${it.chapter.number}" }
			.values
			.mapNotNull { group ->
				group.maxWithOrNull { left, right ->
					compareChapterCandidates(left, right, scanlatorCounts)
				}?.chapter
			}
			.sortedWith(
				compareBy<MangaChapter> { it.number }
					.thenBy { if (it.volume > 0) 1 else 0 }
					.thenBy { it.uploadDate }
					.thenBy { it.branch.orEmpty() }
					.thenBy { it.title.orEmpty() },
			)
	}

	private fun buildChapterTitle(numberText: String, volume: Int, rawName: String): String {
		val baseTitle = buildString {
			if (volume > 0) {
				append("Vol. ")
				append(volume)
				append(' ')
			}
			append("Chapter ")
			append(numberText)
		}
		val name = rawName.trim()
		if (name.isEmpty()) {
			return baseTitle
		}
		val compactName = name.replace(WHITESPACE_REGEX, " ")
		val normalized = CHAPTER_PREFIX_REGEX.find(compactName)?.let { match ->
			val volumePrefix = match.groups[1]?.value?.trim().orEmpty()
			buildString {
				if (volumePrefix.isNotEmpty()) {
					append(volumePrefix.replaceFirst(VOLUME_PREFIX_REGEX, "Vol."))
					append(' ')
				}
				append("Chapter ")
				append(compactName.removeRange(match.range))
			}
		}?.trim() ?: compactName.trim()
		return if (normalized.startsWith("Chapter $numberText", ignoreCase = true) ||
			normalized.startsWith("Vol. $volume Chapter $numberText", ignoreCase = true)
		) {
			normalized
		} else {
			"$baseTitle: $normalized"
		}
	}

	private fun compareChapterCandidates(
		left: ChapterCandidate,
		right: ChapterCandidate,
		scanlatorCounts: Map<String, Int>,
	): Int {
		val leftChapter = left.chapter
		val rightChapter = right.chapter
		val leftScore = scanlatorCounts["${left.language}|${leftChapter.scanlator.orEmpty()}"] ?: 0
		val rightScore = scanlatorCounts["${right.language}|${rightChapter.scanlator.orEmpty()}"] ?: 0
		if (leftScore != rightScore) {
			return leftScore.compareTo(rightScore)
		}
		if ((leftChapter.volume > 0) != (rightChapter.volume > 0)) {
			return if (leftChapter.volume == 0) 1 else -1
		}
		val leftTitle = leftChapter.title.orEmpty()
		val rightTitle = rightChapter.title.orEmpty()
		if (leftTitle.length != rightTitle.length) {
			return rightTitle.length.compareTo(leftTitle.length)
		}
		if (leftChapter.uploadDate != rightChapter.uploadDate) {
			return leftChapter.uploadDate.compareTo(rightChapter.uploadDate)
		}
		return rightChapter.id.compareTo(leftChapter.id)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val pages = getChapterDetail(chapter.url).optJSONArray("pages") ?: return emptyList()
		return List(pages.length()) { i ->
			val url = pages.getString(i)
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}

	private suspend fun getChapterDetail(chapterId: String): JSONObject {
		val url = "$API_URL/chapter-detail".toHttpUrl().newBuilder()
			.addQueryParameter("chapter_id", chapterId)
			.build()
		return client.httpGet(url, getApiHeaders()).parseJson()
			.optJSONObject("data")
			?.optJSONObject("chapter")
			?: throw NotFoundException("Chapter not found", url.toString())
	}

	override suspend fun resolveLink(resolver: LinkResolver, link: HttpUrl): Manga? = resolveMangaFromUrl(link)

	private suspend fun resolveMangaFromUrl(link: HttpUrl): Manga? {
		if (link.host != domain && link.host != "www.$domain" && link.host != LEGACY_DOMAIN) {
			return null
		}
		val idOrSlug = when (link.pathSegments.firstOrNull()) {
			"title-detail" -> link.pathSegments.getOrNull(1)
			"chapter-detail" -> link.pathSegments.getOrNull(1)?.nullIfEmpty()?.let {
				getChapterDetail(it).getStringOrNull("title_id")
			}

			else -> null
		}?.nullIfEmpty() ?: return null
		// Old mangaball.net links carry "<name>-<ObjectId>"; the id is the reliable key.
		val data = getTitle(titleIdFromUrl(idOrSlug) ?: idOrSlug)
		val manga = parseManga(data) ?: return null
		val titleId = data.getTitleId() ?: return null
		return parseDetails(manga, data, titleId, getChapters(titleId))
	}

	private suspend fun resolveSearchUrl(url: String): List<Manga> {
		val link = url.toHttpUrlOrNull() ?: return emptyList()
		return listOfNotNull(resolveMangaFromUrl(link))
	}

	/**
	 * The API rejects any Content-Type other than a bare `application/json` (HTTP 400), while
	 * [WebClient.httpPost] sends `application/json; charset=utf-8`, so the request is built here.
	 */
	private suspend fun httpPostJson(url: String, body: JSONObject): JSONObject {
		val request = Request.Builder()
			.url(url)
			.post(body.toString().toByteArray().toRequestBody(JSON_MEDIA_TYPE))
			.headers(getApiHeaders())
			.header("Origin", BASE_URL)
			.tag(MangaSource::class.java, source)
			.build()
		val response = httpClient.newCall(request).await()
		if (!response.isSuccessful) {
			response.close()
			throw HttpStatusException(response.message, response.code, url)
		}
		return response.parseJson()
	}

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		if (request.header("Referer").isNullOrEmpty() &&
			(request.url.host == domain || request.url.host.endsWith(PAGE_HOST_SUFFIX))
		) {
			return chain.proceed(
				request.newBuilder()
					.header("Referer", "$BASE_URL/")
					.build(),
			)
		}
		return chain.proceed(request)
	}

	private fun parseManga(item: JSONObject): Manga? {
		val titleId = item.getTitleId() ?: return null
		// Same url as the previous mangaball.net parser ("<name>-<ObjectId>"), so ids stay stable.
		val slug = item.getStringOrNull("slug")?.let {
			if (it.endsWith(titleId)) it else "$it-$titleId"
		} ?: titleId
		val tags = item.optJSONArray("tags").toMangaTags()
		val cover = item.extractCover()
		return Manga(
			id = generateUid(slug),
			title = item.getStringOrNull("name") ?: slug,
			altTitles = item.optJSONArray("alternateName").toStringSet(),
			url = slug,
			publicUrl = getMangaUrl(titleId),
			rating = RATING_UNKNOWN,
			contentRating = item.contentRating(tags),
			coverUrl = cover,
			tags = tags,
			state = stateFromStatus(item.getStringOrNull("status")),
			authors = item.optJSONArray("author").toNameSet(),
			largeCoverUrl = cover,
			source = source,
		)
	}

	private fun JSONObject.getTitleId(): String? = getStringOrNull("id") ?: getStringOrNull("_id")

	private fun titleIdFromUrl(url: String): String? = url.substringAfterLast('-').takeIf { OBJECT_ID_REGEX.matches(it) }

	private fun JSONObject.contentRating(tags: Set<MangaTag>): ContentRating? = if (
		optBoolean("is18plus", false) || tags.any { it.title in ADULT_TAG_TITLES }
	) {
		ContentRating.ADULT
	} else {
		null
	}

	private fun getMangaUrl(titleId: String): String = "$BASE_URL/title-detail/$titleId"

	private fun getApiHeaders(): Headers = getRequestHeaders().newBuilder()
		.set("Accept", "application/json")
		.set("Referer", "$BASE_URL/")
		.build()

	/**
	 * Mirrors the site's own cover priority: the self-hosted cover first (served with a `Referer`,
	 * which [intercept] adds), then the external mirrors.
	 */
	private fun JSONObject.extractCover(): String? {
		val image = optJSONObject("image") ?: return getStringOrNull("cover")
		image.optJSONObject("cover")?.getStringOrNull("path")
			?.replace('\\', '/')
			?.trim()
			?.nullIfEmpty()
			?.let { return if (it.startsWith("http")) it else "$COVER_BASE_URL/${it.trimStart('/')}" }
		return image.getStringOrNull("file")
			?: image.getStringOrNull("cdn_mangadex")
			?: image.getStringOrNull("cdn_mangaupdate")
			?: image.getStringOrNull("cdn_mangaupdates")
			?: getStringOrNull("cover")
	}

	private fun stateFromStatus(status: String?): MangaState? = when (status) {
		"ongoing" -> MangaState.ONGOING
		"completed" -> MangaState.FINISHED
		"hiatus", "on_hold" -> MangaState.PAUSED
		"cancelled" -> MangaState.ABANDONED
		else -> null
	}

	private fun JSONArray?.toMangaTags(): Set<MangaTag> {
		if (this == null) {
			return emptySet()
		}
		val result = LinkedHashSet<MangaTag>(length())
		for (i in 0 until length()) {
			val tag = optJSONObject(i) ?: continue
			val id = tag.getStringOrNull("id") ?: tag.getStringOrNull("_id") ?: continue
			val name = tag.getStringOrNull("name") ?: continue
			result += MangaTag(key = id, title = name, source = source)
		}
		return result
	}

	private fun JSONArray?.toStringSet(): Set<String> {
		if (this == null) {
			return emptySet()
		}
		val result = LinkedHashSet<String>(length())
		for (i in 0 until length()) {
			optString(i).trim().nullIfEmpty()?.let(result::add)
		}
		return result
	}

	private fun JSONArray?.toNameSet(): Set<String> {
		if (this == null) {
			return emptySet()
		}
		val result = LinkedHashSet<String>(length())
		for (i in 0 until length()) {
			optJSONObject(i)?.getStringOrNull("name")?.trim()?.nullIfEmpty()?.let(result::add)
		}
		return result
	}

	private fun sortValue(order: SortOrder): Pair<String, String> = when (order) {
		SortOrder.UPDATED -> "lastupdate" to "desc"
		SortOrder.POPULARITY -> "views" to "desc"
		SortOrder.RATING -> "rating" to "desc"
		SortOrder.NEWEST -> "created_at" to "desc"
		SortOrder.ALPHABETICAL -> "name" to "asc"
		SortOrder.ALPHABETICAL_DESC -> "name" to "desc"
		else -> "lastupdate" to "desc"
	}

	private fun stateValue(state: MangaState?): String? = when (state) {
		MangaState.ONGOING -> "ongoing"
		MangaState.FINISHED -> "completed"
		MangaState.PAUSED -> "hiatus"
		MangaState.ABANDONED -> "cancelled"
		else -> null
	}

	private fun typeValue(type: ContentType?): String? = when (type) {
		ContentType.MANGA -> "manga"
		ContentType.MANHWA -> "manhwa"
		ContentType.MANHUA -> "manhua"
		ContentType.COMICS -> "comics"
		else -> null
	}

	private fun demographicValue(demographic: Demographic?): String? = when (demographic) {
		Demographic.SHOUNEN -> "shounen"
		Demographic.SHOUJO -> "shoujo"
		Demographic.SEINEN -> "seinen"
		Demographic.JOSEI -> "josei"
		else -> null
	}

	private data class ChapterCandidate(
		val language: String,
		val chapter: MangaChapter,
	)

	companion object {

		private const val BASE_URL = "https://$DOMAIN"
		private const val API_URL = "$BASE_URL/api/v1"
		private const val LEGACY_DOMAIN = "mangaball.net"
		private const val PAGE_HOST_SUFFIX = ".poke-black-and-white.net"
		private const val COVER_BASE_URL = "https://bulbasaur$PAGE_HOST_SUFFIX/covers"
		private val JSON_MEDIA_TYPE = "application/json".toMediaType()
		private val OBJECT_ID_REGEX = Regex("[a-fA-F0-9]{24}")
		private val WHITESPACE_REGEX = Regex("\\s+")
		private val CHAPTER_PREFIX_REGEX = Regex("^(Vol\\.?\\s*\\d+\\s+)?Ch(?:apter)?\\.?\\s*", RegexOption.IGNORE_CASE)
		private val VOLUME_PREFIX_REGEX = Regex("(?i)^vol\\.?")
		private val ADULT_TAG_TITLES = setOf(
			"Adult",
			"Ecchi",
			"Mature",
			"Smut",
			"Hentai",
			"Manhwa 18+",
			"Sexual Violence",
		)
	}

	@MangaSourceParser("MANGABALL_AR", "Manga Ball (Arabic)", "ar")
	class Arabic(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_AR, setOf("ar"))

	@MangaSourceParser("MANGABALL_BG", "Manga Ball (Bulgarian)", "bg")
	class Bulgarian(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_BG, setOf("bg"))

	@MangaSourceParser("MANGABALL_BN", "Manga Ball (Bengali)", "bn")
	class Bengali(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_BN, setOf("bn"))

	@MangaSourceParser("MANGABALL_CA", "Manga Ball (Catalan)", "ca")
	class Catalan(context: MangaLoaderContext) : MangaBallParser(
		context,
		MangaParserSource.MANGABALL_CA,
		setOf("ca", "ca-ad", "ca-es", "ca-fr", "ca-it", "ca-pt"),
	)

	@MangaSourceParser("MANGABALL_CS", "Manga Ball (Czech)", "cs")
	class Czech(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_CS, setOf("cs"))

	@MangaSourceParser("MANGABALL_DA", "Manga Ball (Danish)", "da")
	class Danish(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_DA, setOf("da"))

	@MangaSourceParser("MANGABALL_DE", "Manga Ball (German)", "de")
	class German(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_DE, setOf("de"))

	@MangaSourceParser("MANGABALL_EL", "Manga Ball (Greek)", "el")
	class Greek(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_EL, setOf("el"))

	@MangaSourceParser("MANGABALL_EN", "Manga Ball (English)", "en")
	class English(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_EN, setOf("en"))

	@MangaSourceParser("MANGABALL_ES", "Manga Ball (Spanish)", "es")
	class Spanish(context: MangaLoaderContext) : MangaBallParser(
		context,
		MangaParserSource.MANGABALL_ES,
		setOf("es", "es-ar", "es-mx", "es-es", "es-la", "es-419"),
	)

	@MangaSourceParser("MANGABALL_FA", "Manga Ball (Persian)", "fa")
	class Persian(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_FA, setOf("fa"))

	@MangaSourceParser("MANGABALL_FI", "Manga Ball (Finnish)", "fi")
	class Finnish(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_FI, setOf("fi"))

	@MangaSourceParser("MANGABALL_FR", "Manga Ball (French)", "fr")
	class French(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_FR, setOf("fr"))

	@MangaSourceParser("MANGABALL_HE", "Manga Ball (Hebrew)", "he")
	class Hebrew(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_HE, setOf("he"))

	@MangaSourceParser("MANGABALL_HI", "Manga Ball (Hindi)", "hi")
	class Hindi(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_HI, setOf("hi"))

	@MangaSourceParser("MANGABALL_HU", "Manga Ball (Hungarian)", "hu")
	class Hungarian(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_HU, setOf("hu"))

	@MangaSourceParser("MANGABALL_ID", "Manga Ball (Indonesian)", "id")
	class Indonesian(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_ID, setOf("id"))

	@MangaSourceParser("MANGABALL_IT", "Manga Ball (Italian)", "it")
	class Italian(context: MangaLoaderContext) : MangaBallParser(
		context,
		MangaParserSource.MANGABALL_IT,
		setOf("it", "it-it"),
	)

	@MangaSourceParser("MANGABALL_IS", "Manga Ball (Icelandic)", "is")
	class Icelandic(context: MangaLoaderContext) : MangaBallParser(
		context,
		MangaParserSource.MANGABALL_IS,
		setOf("ib", "ib-is", "is"),
	)

	@MangaSourceParser("MANGABALL_JA", "Manga Ball (Japanese)", "ja")
	class Japanese(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_JA, setOf("ja", "jp"))

	@MangaSourceParser("MANGABALL_KO", "Manga Ball (Korean)", "ko")
	class Korean(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_KO, setOf("ko", "kr"))

	@MangaSourceParser("MANGABALL_KN", "Manga Ball (Kannada)", "kn")
	class Kannada(context: MangaLoaderContext) : MangaBallParser(
		context,
		MangaParserSource.MANGABALL_KN,
		setOf("kn", "kn-in", "kn-my", "kn-sg", "kn-tw"),
	)

	@MangaSourceParser("MANGABALL_ML", "Manga Ball (Malayalam)", "ml")
	class Malayalam(context: MangaLoaderContext) : MangaBallParser(
		context,
		MangaParserSource.MANGABALL_ML,
		setOf("ml", "ml-in", "ml-my", "ml-sg", "ml-tw"),
	)

	@MangaSourceParser("MANGABALL_MS", "Manga Ball (Malay)", "ms")
	class Malay(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_MS, setOf("ms"))

	@MangaSourceParser("MANGABALL_NE", "Manga Ball (Nepali)", "ne")
	class Nepali(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_NE, setOf("ne"))

	@MangaSourceParser("MANGABALL_NL", "Manga Ball (Dutch)", "nl")
	class Dutch(context: MangaLoaderContext) : MangaBallParser(
		context,
		MangaParserSource.MANGABALL_NL,
		setOf("nl", "nl-be"),
	)

	@MangaSourceParser("MANGABALL_NO", "Manga Ball (Norwegian)", "no")
	class Norwegian(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_NO, setOf("no"))

	@MangaSourceParser("MANGABALL_PL", "Manga Ball (Polish)", "pl")
	class Polish(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_PL, setOf("pl"))

	@MangaSourceParser("MANGABALL_PTBR", "Manga Ball (Portuguese)", "pt")
	class PortugueseBrazil(context: MangaLoaderContext) : MangaBallParser(
		context,
		MangaParserSource.MANGABALL_PTBR,
		setOf("pt-br", "pt-pt"),
	)

	@MangaSourceParser("MANGABALL_RO", "Manga Ball (Romanian)", "ro")
	class Romanian(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_RO, setOf("ro"))

	@MangaSourceParser("MANGABALL_RU", "Manga Ball (Russian)", "ru")
	class Russian(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_RU, setOf("ru"))

	@MangaSourceParser("MANGABALL_SK", "Manga Ball (Slovak)", "sk")
	class Slovak(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_SK, setOf("sk"))

	@MangaSourceParser("MANGABALL_SL", "Manga Ball (Slovenian)", "sl")
	class Slovenian(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_SL, setOf("sl"))

	@MangaSourceParser("MANGABALL_SQ", "Manga Ball (Albanian)", "sq")
	class Albanian(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_SQ, setOf("sq"))

	@MangaSourceParser("MANGABALL_SR", "Manga Ball (Serbian)", "sr")
	class Serbian(context: MangaLoaderContext) : MangaBallParser(
		context,
		MangaParserSource.MANGABALL_SR,
		setOf("sr", "sr-cyrl"),
	)

	@MangaSourceParser("MANGABALL_SV", "Manga Ball (Swedish)", "sv")
	class Swedish(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_SV, setOf("sv"))

	@MangaSourceParser("MANGABALL_TA", "Manga Ball (Tamil)", "ta")
	class Tamil(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_TA, setOf("ta"))

	@MangaSourceParser("MANGABALL_TH", "Manga Ball (Thai)", "th")
	class Thai(context: MangaLoaderContext) : MangaBallParser(
		context,
		MangaParserSource.MANGABALL_TH,
		setOf("th", "th-hk", "th-kh", "th-la", "th-my", "th-sg"),
	)

	@MangaSourceParser("MANGABALL_TR", "Manga Ball (Turkish)", "tr")
	class Turkish(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_TR, setOf("tr"))

	@MangaSourceParser("MANGABALL_UK", "Manga Ball (Ukrainian)", "uk")
	class Ukrainian(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_UK, setOf("uk"))

	@MangaSourceParser("MANGABALL_VI", "Manga Ball (Vietnamese)", "vi")
	class Vietnamese(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_VI, setOf("vi"))

	@MangaSourceParser("MANGABALL_ZH", "Manga Ball (Chinese)", "zh")
	class Chinese(context: MangaLoaderContext) : MangaBallParser(
		context,
		MangaParserSource.MANGABALL_ZH,
		setOf("cn", "zh", "zh-cn", "zh-hk", "zh-mo", "zh-sg", "zh-tw"),
	)
}
