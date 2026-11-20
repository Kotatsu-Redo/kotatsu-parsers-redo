package org.koitharu.kotatsu.parsers.site.ko

import androidx.collection.ArraySet
import androidx.collection.arraySetOf
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.HttpStatusException
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.AbstractMangaParser
import org.koitharu.kotatsu.parsers.exception.AuthRequiredException
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.network.UserAgents
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import java.net.ProtocolException
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("NAVER_WEBTOON", "Naver Webtoon", "ko", type = ContentType.MANHWA)
internal class NaverWebtoonParser(context: MangaLoaderContext) :
	AbstractMangaParser(context, MangaParserSource.NAVER_WEBTOON) {

	override val configKeyDomain = ConfigKey.Domain("comic.naver.com")

	// Mobile UAs redirect comic.naver.com to m.comic.naver.com, breaking domain validation and omitting PC-only titles.
	override val userAgentKey = ConfigKey.UserAgent(UserAgents.CHROME_DESKTOP)

	private val mobileDomain: String
		get() = getDomain("m")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.POPULARITY,
		SortOrder.UPDATED,
		SortOrder.RATING,
		SortOrder.POPULARITY_WEEK,
	)

	override val defaultSortOrder: SortOrder = SortOrder.POPULARITY

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			// Kotatsu only forwards filter.tags to getList when this flag is enabled;
			// the backend accepts one genre per request, so getList uses the first selected tag.
			isMultipleTagsSupported = true,
		)

	private val genreTags: Set<MangaTag> by lazy {
		arraySetOf(
			MangaTag("Romance", "PURE", source),
			MangaTag("Fantasy", "FANTASY", source),
			MangaTag("Action", "ACTION", source),
			MangaTag("Slice of Life", "DAILY", source),
			MangaTag("Thriller", "THRILL", source),
			MangaTag("Comedy", "COMIC", source),
			MangaTag("Historical", "HISTORICAL", source),
			MangaTag("Drama", "DRAMA", source),
			MangaTag("Sensibility", "SENSIBILITY", source),
			MangaTag("Sports", "SPORTS", source),
		)
	}

	private val genreTagsByKey: Map<String, MangaTag> by lazy {
		genreTags.associateBy { it.key }
	}

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = genreTags,
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED, MangaState.PAUSED),
		availableContentRating = EnumSet.of(ContentRating.SAFE, ContentRating.ADULT),
	)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override fun getRequestHeaders(): Headers = Headers.Builder()
		.add("User-Agent", config[userAgentKey])
		.add("Referer", "https://$domain/")
		.add("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7")
		.build()

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		val builder = request.newBuilder()
		val existingUa = request.header("User-Agent")
		if (existingUa == null || existingUa.startsWith("okhttp/", ignoreCase = true)) {
			builder.header("User-Agent", config[userAgentKey])
		}
		if (request.header("Referer") == null) {
			val referer = if (request.url.host.equals(mobileDomain, ignoreCase = true)) {
				"https://$mobileDomain/"
			} else {
				"https://$domain/"
			}
			builder.header("Referer", referer)
		}
		return chain.proceed(builder.build())
	}

	override suspend fun getFavicons(): Favicons = Favicons(
		favicons = setOf(Favicon(FAVICON_URL, 96, null)),
		referer = "https://$domain/",
	)

	override suspend fun getPageUrl(page: MangaPage): String = page.url.toAbsoluteUrl(IMAGE_DOMAIN)

	override suspend fun getList(offset: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val query = filter.query?.trim().orEmpty()
		if (query.isNotEmpty()) {
			return fetchPagedList(offset, SEARCH_PAGE_SIZE, "searchList") { page ->
				"https://$domain/api/search/webtoon?keyword=${query.urlEncoded()}&page=$page"
			}
		}
		if (filter.tags.isNotEmpty()) {
			val genreTag = genreTagsByKey[filter.tags.first().key] ?: return emptyList()
			val sortParam = mapSortOrder(order)
			return fetchPagedList(offset, GENRE_PAGE_SIZE, "titleList", defaultTag = genreTag) { page ->
				"https://$domain/api/webtoon/titlelist/genre?genre=${genreTag.key}&page=$page&order=$sortParam"
			}
		}
		val finishedOnly = MangaState.FINISHED in filter.states &&
			MangaState.ONGOING !in filter.states &&
			MangaState.PAUSED !in filter.states
		if (finishedOnly) {
			val sortParam = mapSortOrder(order)
			return fetchPagedList(offset, FINISHED_PAGE_SIZE, "titleList", defaultFinished = true) { page ->
				"https://$domain/api/webtoon/titlelist/finished?page=$page&order=$sortParam"
			}
		}
		return fetchWeekdayList(offset, order, filter)
	}

	private suspend inline fun fetchPagedList(
		offset: Int,
		pageSize: Int,
		arrayKey: String,
		defaultTag: MangaTag? = null,
		defaultFinished: Boolean = false,
		urlForPage: (Int) -> String,
	): List<Manga> {
		val page = (offset / pageSize) + 1
		val sliceStart = offset % pageSize
		val json = webClient.httpGet(urlForPage(page), apiHeaders()).parseJson()
		if (isPastTotalPages(json, page)) return emptyList()
		val array = json.optJSONArray(arrayKey) ?: return emptyList()
		val items = array.mapJSONNotNull {
			parseMangaSummary(it, defaultTag = defaultTag, defaultFinished = defaultFinished)
		}
		return if (sliceStart == 0) items else items.drop(sliceStart)
	}

	private suspend fun fetchWeekdayList(
		offset: Int,
		order: SortOrder,
		filter: MangaListFilter,
	): List<Manga> {
		val url = "https://$domain/api/webtoon/titlelist/weekday?order=${mapSortOrder(order)}"
		val json = webClient.httpGet(url, apiHeaders()).parseJson()
		val map = json.optJSONObject("titleListMap") ?: return emptyList()
		val buckets = WEEKDAY_KEYS.mapNotNull { key -> map.optJSONArray(key) }
		if (buckets.isEmpty()) return emptyList()

		val seenIds = HashSet<Long>()
		val combined = ArrayList<Manga>()
		val maxBucketSize = buckets.maxOf { it.length() }
		for (i in 0 until maxBucketSize) {
			for (bucket in buckets) {
				if (i >= bucket.length()) continue
				val jo = bucket.optJSONObject(i) ?: continue
				val titleId = jo.optLong("titleId", -1L)
				if (titleId <= 0L || !seenIds.add(titleId)) continue
				val manga = parseMangaSummary(jo) ?: continue
				val matchesRating = filter.contentRating.isEmpty() || manga.contentRating in filter.contentRating
				val matchesState = filter.states.isEmpty() || manga.state in filter.states
				if (matchesRating && matchesState) {
					combined.add(manga)
				}
			}
		}
		if (offset >= combined.size) return emptyList()
		return combined.drop(offset).take(GENRE_PAGE_SIZE)
	}

	override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
		val titleId = manga.url.toLongOrNull()
			?: throw ParseException("Invalid titleId: ${manga.url}", manga.publicUrl)

		val infoDeferred = async {
			webClient.httpGet(
				"https://$domain/api/article/list/info?titleId=$titleId",
				apiHeaders(),
			).parseJson()
		}
		val chaptersDeferred = async { fetchChapters(titleId) }

		val info = infoDeferred.await()
		val chapters = chaptersDeferred.await()

		val title = info.getStringOrNull("titleName") ?: manga.title
		val coverUrl = info.getStringOrNull("thumbnailUrl")?.toAbsoluteUrl(IMAGE_DOMAIN) ?: manga.coverUrl
		val largeCoverUrl = info.getStringOrNull("posterThumbnailUrl")
			?.toAbsoluteUrl(IMAGE_DOMAIN)
			?.takeIf { it != coverUrl }
			?: manga.largeCoverUrl

		val ageType = info.optJSONObject("age")?.getStringOrNull("type")
		val contentRating = if (ageType == AGE_RATE_18) {
			ContentRating.ADULT
		} else {
			manga.contentRating ?: ContentRating.SAFE
		}

		manga.copy(
			id = generateUid(titleId),
			title = title,
			url = titleId.toString(),
			publicUrl = buildPublicUrl(titleId),
			contentRating = contentRating,
			coverUrl = coverUrl,
			largeCoverUrl = largeCoverUrl,
			tags = parseInfoGenres(info).ifEmpty { manga.tags },
			state = parseSeriesState(info, defaultFinished = false, fallback = manga.state),
			authors = parseAuthors(info).ifEmpty { manga.authors },
			description = info.getStringOrNull("synopsis") ?: manga.description,
			chapters = chapters,
		)
	}

	private suspend fun fetchChapters(titleId: Long): List<MangaChapter> = coroutineScope {
		val firstPage = fetchArticleListPage(titleId, 1)
		val totalPages = firstPage.optJSONObject("pageInfo")
			?.getIntOrDefault("totalPages", 1)
			?.coerceAtLeast(1)
			?: 1

		val pageObjects = ArrayList<JSONObject>(totalPages)
		pageObjects.add(firstPage)
		if (totalPages > 1) {
			// Cap concurrent page requests to avoid Naver HTTP/1.1 keep-alive frame corruption.
			for (batch in (2..totalPages).chunked(CHAPTER_PAGE_BATCH_SIZE)) {
				val remaining = batch.map { page ->
					async { fetchArticleListPage(titleId, page) }
				}.awaitAll()
				pageObjects.addAll(remaining)
			}
		}

		val chapters = ArrayList<MangaChapter>()
		val seenChapterIds = HashSet<Long>()
		for (pageJson in pageObjects) {
			val articles = pageJson.optJSONArray("articleList") ?: continue
			for (i in 0 until articles.length()) {
				val jo = articles.optJSONObject(i) ?: continue
				// Paid DailyPass episodes redirect to a browser alert instead of viewer images.
				if (jo.getBooleanOrDefault("charge", false)) continue
				val no = jo.getIntOrDefault("no", 0)
				if (no <= 0) continue
				val chapterId = generateUid("$titleId-$no")
				if (!seenChapterIds.add(chapterId)) continue
				chapters.add(
					MangaChapter(
						id = chapterId,
						title = jo.getStringOrNull("subtitle")?.trim(),
						number = no.toFloat(),
						volume = 0,
						url = "$titleId/$no",
						scanlator = null,
						uploadDate = parseEpisodeDate(jo.getStringOrNull("serviceDateDescription")),
						branch = null,
						source = source,
					),
				)
			}
		}
		chapters.sortBy(MangaChapter::number)
		chapters
	}

	private suspend fun fetchArticleListPage(titleId: Long, page: Int): JSONObject {
		val url = "https://$domain/api/article/list?titleId=$titleId&page=$page&sort=ASC"
		var lastError: ProtocolException? = null
		for (attempt in 0 until 2) {
			val headers = if (attempt == 0) {
				apiHeaders()
			} else {
				apiHeaders().newBuilder().add("Connection", "close").build()
			}
			try {
				return webClient.httpGet(url, headers).parseJson()
			} catch (e: HttpStatusException) {
				if (e.statusCode == 401 || e.statusCode == 403) {
					throw AuthRequiredException(source, e)
				}
				throw e
			} catch (e: ProtocolException) {
				lastError = e
				if (attempt == 0) delay(150)
			}
		}
		throw checkNotNull(lastError)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val parts = chapter.url.split('/')
		val titleId = parts.getOrNull(0)?.toLongOrNull()
		val no = parts.getOrNull(1)?.toIntOrNull()
		if (titleId == null || no == null) {
			throw ParseException("Invalid chapter url: ${chapter.url}", chapter.url)
		}
		val viewerUrl = "https://$domain/webtoon/detail?titleId=$titleId&no=$no"
		val response = webClient.httpGet(viewerUrl, getRequestHeaders())
		val finalUrl = response.request.url
		if (finalUrl.host.equals(LOGIN_HOST, ignoreCase = true)) {
			response.close()
			throw AuthRequiredException(source)
		}
		if (finalUrl.encodedPath == "/webtoon/list") {
			response.close()
			throw ParseException("Paid or locked episode", viewerUrl)
		}

		val doc = response.parseHtml()
		val elements = doc.select("div.wt_viewer img[id^=content_image_]").ifEmpty {
			doc.select("div.wt_viewer img[alt=comic content]")
		}
		val pages = elements.mapIndexedNotNull { index, img ->
			val rawUrl = img.attr("data-src").ifBlank { img.attr("src") }.trim()
			if (rawUrl.isEmpty() || rawUrl.contains("bg_transparency") || rawUrl.contains("/static/agerate/")) {
				return@mapIndexedNotNull null
			}
			MangaPage(
				id = generateUid("${chapter.id}-$index"),
				url = rawUrl,
				preview = null,
				source = source,
			)
		}
		if (pages.isEmpty()) {
			throw ParseException("No episode images found (may require login or purchase)", viewerUrl)
		}
		return pages
	}

	override suspend fun resolveLink(resolver: LinkResolver, link: HttpUrl): Manga? {
		val host = link.host
		if (!host.equals(domain, ignoreCase = true) && !host.equals(mobileDomain, ignoreCase = true)) {
			return null
		}
		if (!link.encodedPath.startsWith("/webtoon/")) return null
		val titleId = link.queryParameter("titleId")?.toLongOrNull()?.takeIf { it > 0L } ?: return null
		val stub = Manga(
			id = generateUid(titleId),
			title = "",
			altTitles = emptySet(),
			url = titleId.toString(),
			publicUrl = buildPublicUrl(titleId),
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

	private fun parseMangaSummary(
		jo: JSONObject,
		defaultTag: MangaTag? = null,
		defaultFinished: Boolean = false,
	): Manga? {
		val titleId = jo.optLong("titleId", -1L)
		if (titleId <= 0L) return null
		val title = jo.getStringOrNull("titleName") ?: return null
		val adult = jo.getBooleanOrDefault("adult", false)
		val starScore = jo.getDoubleOrDefault("starScore", 0.0)
		val rating = if (starScore > 0.0) (starScore / 10.0).toFloat().coerceIn(0f, 1f) else RATING_UNKNOWN
		val tags = parseSummaryGenres(jo, defaultTag)
		return Manga(
			id = generateUid(titleId),
			title = title,
			altTitles = emptySet(),
			url = titleId.toString(),
			publicUrl = buildPublicUrl(titleId),
			rating = rating,
			contentRating = if (adult) ContentRating.ADULT else ContentRating.SAFE,
			coverUrl = jo.getStringOrNull("thumbnailUrl")?.toAbsoluteUrl(IMAGE_DOMAIN),
			tags = tags,
			state = parseSeriesState(jo, defaultFinished = defaultFinished, fallback = null),
			authors = parseAuthors(jo),
			source = source,
		)
	}

	private fun parseSummaryGenres(jo: JSONObject, defaultTag: MangaTag?): Set<MangaTag> {
		val result = ArraySet<MangaTag>()
		defaultTag?.let(result::add)
		jo.optJSONArray("genreList")?.let { arr ->
			for (i in 0 until arr.length()) {
				val type = arr.optJSONObject(i)?.getStringOrNull("type") ?: continue
				genreTagsByKey[type]?.let(result::add)
			}
		}
		jo.optJSONArray("tagList")?.let { arr ->
			collectGenreTagsFromCurationArray(arr, result)
		}
		return result
	}

	private fun parseInfoGenres(info: JSONObject): Set<MangaTag> {
		val result = ArraySet<MangaTag>()
		info.optJSONArray("curationTagList")?.let { arr ->
			collectGenreTagsFromCurationArray(arr, result)
		}
		info.optJSONObject("gfpAdCustomParam")?.optJSONArray("genreTypes")?.let { arr ->
			for (i in 0 until arr.length()) {
				val code = arr.optString(i)
				genreTagsByKey[code]?.let(result::add)
			}
		}
		return result
	}

	private fun collectGenreTagsFromCurationArray(arr: JSONArray, destination: MutableSet<MangaTag>) {
		for (i in 0 until arr.length()) {
			val curationType = arr.optJSONObject(i)?.getStringOrNull("curationType") ?: continue
			if (curationType.startsWith("GENRE_")) {
				val code = curationType.removePrefix("GENRE_")
				genreTagsByKey[code]?.let(destination::add)
			}
		}
	}

	private fun parseAuthors(jo: JSONObject): Set<String> {
		val artists = jo.optJSONArray("communityArtists")
		if (artists != null && artists.length() > 0) {
			val result = LinkedHashSet<String>(artists.length())
			for (i in 0 until artists.length()) {
				val name = artists.optJSONObject(i)?.getStringOrNull("name")?.trim() ?: continue
				result.add(name)
			}
			if (result.isNotEmpty()) return result
		}
		val rawAuthor = jo.getStringOrNull("author") ?: jo.getStringOrNull("displayAuthor") ?: return emptySet()
		return rawAuthor.split('/').mapNotNullTo(LinkedHashSet()) { it.trim().nullIfEmpty() }
	}

	private fun parseSeriesState(
		jo: JSONObject,
		defaultFinished: Boolean,
		fallback: MangaState?,
	): MangaState? {
		val finished = jo.getBooleanOrDefault("finish", jo.getBooleanOrDefault("finished", defaultFinished))
		val rest = jo.getBooleanOrDefault("rest", false)
		return when {
			finished -> MangaState.FINISHED
			rest -> MangaState.PAUSED
			jo.has("finish") || jo.has("finished") || defaultFinished -> MangaState.ONGOING
			else -> fallback
		}
	}

	private fun parseEpisodeDate(raw: String?): Long {
		val trimmed = raw?.trim()?.takeIf { DATE_REGEX.matches(it) } ?: return 0L
		return synchronized(EPISODE_DATE_FORMAT) {
			EPISODE_DATE_FORMAT.parseSafe(trimmed)
		}
	}

	private fun isPastTotalPages(json: JSONObject, page: Int): Boolean {
		val totalPages = json.optJSONObject("pageInfo")?.getIntOrDefault("totalPages", 0) ?: 0
		return totalPages > 0 && page > totalPages
	}

	private fun mapSortOrder(order: SortOrder): String = when (order) {
		SortOrder.POPULARITY -> "VIEW"
		SortOrder.UPDATED -> "UPDATE"
		SortOrder.RATING -> "STAR"
		SortOrder.POPULARITY_WEEK -> "USER"
		else -> "VIEW"
	}

	private fun apiHeaders(): Headers = getRequestHeaders().newBuilder()
		.add("Accept", "application/json")
		.build()

	private fun buildPublicUrl(titleId: Long): String = "https://$domain/webtoon/list?titleId=$titleId"

	private companion object {
		const val IMAGE_DOMAIN = "image-comic.pstatic.net"
		const val LOGIN_HOST = "nid.naver.com"
		const val FAVICON_URL = "https://shared-comic.pstatic.net/favicon/favicon_96x96.ico"
		const val AGE_RATE_18 = "RATE_18"
		const val GENRE_PAGE_SIZE = 25
		const val FINISHED_PAGE_SIZE = 45
		const val SEARCH_PAGE_SIZE = 10
		const val CHAPTER_PAGE_BATCH_SIZE = 4

		val WEEKDAY_KEYS = arrayOf(
			"MONDAY",
			"TUESDAY",
			"WEDNESDAY",
			"THURSDAY",
			"FRIDAY",
			"SATURDAY",
			"SUNDAY",
		)

		val DATE_REGEX = Regex("^\\d{2}\\.\\d{2}\\.\\d{2}$")
		val EPISODE_DATE_FORMAT = SimpleDateFormat("yy.MM.dd", Locale.KOREA).apply {
			timeZone = TimeZone.getTimeZone("Asia/Seoul")
		}
	}
}
