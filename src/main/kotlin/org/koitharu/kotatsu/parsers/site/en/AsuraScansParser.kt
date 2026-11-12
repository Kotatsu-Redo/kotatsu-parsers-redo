package org.koitharu.kotatsu.parsers.site.en

import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.bitmap.Bitmap
import org.koitharu.kotatsu.parsers.bitmap.Rect
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.NotFoundException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("ASURASCANS", "AsuraComic", "en")
internal class AsuraScansParser(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.ASURASCANS, pageSize = 20) {

	override val configKeyDomain = ConfigKey.Domain("asurascans.com")

	private val effectiveDomain: String
		get() {
			val host = domain
			return if (
				host.equals("asuracomic.net", ignoreCase = true) ||
				host.equals("www.asuracomic.net", ignoreCase = true)
			) {
				"asurascans.com"
			} else {
				host
			}
		}

	private val apiUrl: String
		get() {
			val host = effectiveDomain
			return if (host.equals("www.asurascans.com", ignoreCase = true)) {
				"https://api.asurascans.com/api"
			} else {
				"https://api.$host/api"
			}
		}

	@Volatile
	private var cachedSlugToken: String? = null

	private val rateLimitLock = Any()
	private val requestTimestampsMs = kotlin.collections.ArrayDeque<Long>(RATE_LIMIT_PERMITS)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
		.set("Referer", "https://$effectiveDomain/")
		.build()

	override suspend fun getPageUrl(page: MangaPage): String = page.url.toAbsoluteUrl(effectiveDomain)

	override suspend fun getFavicons(): Favicons = runCatchingCancellable {
		FaviconParser(webClient, effectiveDomain).parseFavicons()
	}.getOrElse {
		Favicons.single("https://$effectiveDomain/favicon.ico")
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.RATING,
		SortOrder.ALPHABETICAL,
		SortOrder.ALPHABETICAL_DESC,
		SortOrder.NEWEST,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isMultipleTagsSupported = true,
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = availableTags,
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
		),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		fetchListFromApi(page, order, filter)?.let { return it }
		return fetchListFromBrowseHtml(page, order, filter)
	}

	private suspend fun fetchListFromApi(
		page: Int,
		order: SortOrder,
		filter: MangaListFilter,
	): List<Manga>? {
		val selectedState = filter.states.oneOrThrowIfMany()
		if (selectedState == MangaState.UPCOMING) {
			return null
		}
		val selectedType = filter.types.oneOrThrowIfMany()
		val url = "$apiUrl/series".toHttpUrl().newBuilder().apply {
			addQueryParameter("offset", ((page - 1).coerceAtLeast(0) * pageSize).toString())
			addQueryParameter("limit", pageSize.toString())
			if (!filter.query.isNullOrBlank()) {
				addQueryParameter("search", filter.query)
			}
			if (filter.tags.isNotEmpty()) {
				addQueryParameter("genres", filter.tags.joinToString(",") { it.key })
			}
			selectedState?.let { state ->
				addQueryParameter(
					"status",
					when (state) {
						MangaState.ONGOING -> "ongoing"
						MangaState.FINISHED -> "completed"
						MangaState.ABANDONED -> "dropped"
						MangaState.PAUSED -> "hiatus"
						else -> return null
					},
				)
			}
			selectedType?.let { type ->
				addQueryParameter(
					"type",
					when (type) {
						ContentType.MANGA -> "manga"
						ContentType.MANHWA -> "manhwa"
						ContentType.MANHUA -> "manhua"
						else -> return null
					},
				)
			}
			val (sortValue, orderValue) = when (order) {
				SortOrder.UPDATED -> "latest" to "desc"
				SortOrder.POPULARITY -> "popular" to "desc"
				SortOrder.RATING -> "rating" to "desc"
				SortOrder.ALPHABETICAL -> "title" to "asc"
				SortOrder.ALPHABETICAL_DESC -> "title" to "desc"
				SortOrder.NEWEST -> "update" to "desc"
				else -> "latest" to "desc"
			}
			addQueryParameter("sort", sortValue)
			addQueryParameter("order", orderValue)
		}.build()

		val json = runCatchingCancellable {
			webClient.httpGet(url).parseJson()
		}.getOrNull() ?: return null
		val data = json.optJSONArray("data") ?: return null
		val host = effectiveDomain
		return data.mapJSONNotNull { item ->
			val publicUrlPath = item.getStringOrNull("public_url") ?: item.getStringOrNull("publicUrl")
			rememberSlugToken(publicUrlPath)
			val cleanSlug = item.getStringOrNull("slug")?.trim('/')?.stripSlugToken()?.nullIfEmpty()
				?: publicUrlPath?.let(::normalizeRelativePath)?.removePrefix("/comics/")?.trim('/')?.nullIfEmpty()
				?: return@mapJSONNotNull null
			val href = "/comics/$cleanSlug"
			val title = item.getStringOrNull("title")?.trim()?.nullIfEmpty() ?: return@mapJSONNotNull null
			val cover = item.getStringOrNull("cover")
				?: item.getStringOrNull("coverUrl")
				?: item.getStringOrNull("cover_url")
			val rawRating = item.getStringOrNull("rating")?.toFloatOrNull()
				?: item.optDouble("rating", Double.NaN).takeIf { !it.isNaN() }?.toFloat()
			Manga(
				id = generateUid(href),
				url = href,
				publicUrl = (publicUrlPath?.nullIfEmpty() ?: href).toAbsoluteUrl(host),
				coverUrl = cover,
				title = title,
				altTitles = emptySet(),
				rating = normalizeRating(rawRating),
				tags = parseApiGenres(item.optJSONArray("genres")),
				authors = emptySet(),
				state = parseMangaState(item.getStringOrNull("status")),
				source = source,
				contentRating = if (isNsfwSource) ContentRating.ADULT else null,
			)
		}
	}

	private suspend fun fetchListFromBrowseHtml(
		page: Int,
		order: SortOrder,
		filter: MangaListFilter,
	): List<Manga> {
		val host = effectiveDomain
		val url = "https://$host/browse".toHttpUrl().newBuilder().apply {
			addQueryParameter("page", page.toString())

			if (!filter.query.isNullOrBlank()) {
				addQueryParameter("search", filter.query)
			}

			if (filter.tags.isNotEmpty()) {
				addQueryParameter("genres", filter.tags.joinToString(",") { it.key })
			}

			filter.states.oneOrThrowIfMany()?.let {
				addQueryParameter(
					"status",
					when (it) {
						MangaState.ONGOING -> "ongoing"
						MangaState.FINISHED -> "completed"
						MangaState.ABANDONED -> "dropped"
						MangaState.PAUSED -> "hiatus"
						MangaState.UPCOMING -> "coming soon"
						else -> throw IllegalArgumentException("$it not supported")
					},
				)
			}

			filter.types.oneOrThrowIfMany()?.let {
				addQueryParameter(
					"types",
					when (it) {
						ContentType.MANGA -> "manga"
						ContentType.MANHWA -> "manhwa"
						ContentType.MANHUA -> "manhua"
						else -> throw IllegalArgumentException("$it not supported")
					},
				)
			}

			addQueryParameter(
				"sort",
				when (order) {
					SortOrder.RATING -> "rating"
					SortOrder.UPDATED -> ""
					SortOrder.POPULARITY -> "popular"
					SortOrder.ALPHABETICAL_DESC -> "desc"
					SortOrder.ALPHABETICAL -> "asc"
					SortOrder.NEWEST -> "update"
					else -> "update"
				},
			)
		}.build()
		val doc = webClient.httpGet(url).parseHtml()
		return doc.select("#series-grid .series-card").mapNotNull { card ->
			val link = card.selectFirst("a[href*=/comics/]") ?: return@mapNotNull null
			val rawHref = link.attrAsRelativeUrl("href")
			rememberSlugToken(rawHref)
			val href = normalizeRelativePath(rawHref)
			Manga(
				id = generateUid(href),
				url = href,
				publicUrl = rawHref.toAbsoluteUrl(host),
				coverUrl = card.selectFirst("img")?.src(),
				title = card.selectFirst("h3")?.text()?.trim().orEmpty(),
				altTitles = emptySet(),
				rating = normalizeRating(card.selectFirst("div.absolute.top-2.right-2 span")?.text()?.toFloatOrNull()),
				tags = emptySet(),
				authors = emptySet(),
				state = parseMangaState(card.select("div.p-3 span").lastOrNull()?.text()),
				source = source,
				contentRating = if (isNsfwSource) ContentRating.ADULT else null,
			)
		}
	}

	private val availableTags by lazy(LazyThreadSafetyMode.NONE) {
		ASURA_GENRES.mapTo(LinkedHashSet(ASURA_GENRES.size)) { title ->
			MangaTag(
				key = title.toAsuraGenreKey(),
				title = title,
				source = source,
			)
		}
	}

	private val tagMap by lazy(LazyThreadSafetyMode.NONE) {
		availableTags.associateByTo(LinkedHashMap(availableTags.size)) {
			it.title.lowercase(Locale.ENGLISH)
		}
	}

	private val regexDate = """(\d+)(st|nd|rd|th)""".toRegex()
	private val chapterDateFormat = SimpleDateFormat("MMM d, yyyy", Locale.US)

	override suspend fun getDetails(manga: Manga): Manga {
		val incomingToken = extractSlugToken(manga.url) ?: extractSlugToken(manga.publicUrl)
		rememberSlugToken(manga.url)
		rememberSlugToken(manga.publicUrl)

		val cleanMangaPath = normalizeRelativePath(manga.url)
		val seriesSlug = cleanMangaPath.substringAfter("/comics/", "").substringBefore('/').nullIfEmpty()
		val cutoffTime = System.currentTimeMillis() - CHAPTER_HIDE_WINDOW_MS

		if (seriesSlug != null) {
			fetchDetailsFromApi(manga, seriesSlug, cutoffTime)?.let { return it }
		}

		val doc = fetchSeriesDocument(cleanMangaPath, seriesSlug, incomingToken)
		return parseDetailsFromDocument(manga, doc, seriesSlug, cutoffTime)
	}

	private suspend fun fetchSeriesDocument(
		cleanMangaPath: String,
		seriesSlug: String?,
		incomingToken: String?,
	): Document {
		val host = effectiveDomain
		val token = cachedSlugToken ?: incomingToken
		if (seriesSlug != null && token != null) {
			val tokenizedUrl = "https://$host/comics/$seriesSlug-$token"
			try {
				return webClient.httpGet(tokenizedUrl).parseHtml()
			} catch (_: NotFoundException) {
				if (cachedSlugToken == token) {
					cachedSlugToken = null
				}
			}
		}
		return webClient.httpGet(cleanMangaPath.toAbsoluteUrl(host)).parseHtml()
	}

	private fun parseDetailsFromDocument(
		manga: Manga,
		doc: Document,
		seriesSlug: String?,
		cutoffTime: Long,
	): Manga {
		val host = effectiveDomain
		val astroSeries = parseAstroSeriesDetails(doc)
		val astroPublicUrl = parseAstroPublicUrl(doc)
		rememberSlugToken(astroPublicUrl)

		val selectTag = doc.select("a[href*='/browse?genres='], div[class^=space] > div.flex > button.text-white")
		var tags = selectTag.mapNotNullToSet { element ->
			val title = element.text().trim().nullIfEmpty() ?: return@mapNotNullToSet null
			toMangaTag(title)
		}
		if (tags.isEmpty() && astroSeries != null) {
			tags = parseApiGenres(astroSeries.optJSONArray("genres"))
		}

		var authors = buildSet {
			doc.select("a[href*='/browse?author='], a[href*='/browse?artist=']").forEach { el ->
				el.text().trim().takeIf { it.isNotEmpty() && it != "_" }?.let(::add)
			}
			if (isEmpty()) {
				doc.selectFirst("div.grid > div:has(h3:eq(0):containsOwn(Author)) > h3:eq(1)")
					?.text()?.trim()?.takeIf { it.isNotEmpty() && it != "_" }?.let(::add)
			}
		}
		if (authors.isEmpty() && astroSeries != null) {
			authors = parseApiAuthors(astroSeries)
		}

		val title = doc.selectFirst("article h1, h1.text-xl.font-semibold")
			?.text()
			?.trim()
			?.nullIfEmpty()
			?: astroSeries?.getStringOrNull("title")?.trim()?.nullIfEmpty()
			?: manga.title

		var altTitles = doc.selectFirst("#alt-titles")
			?.text()
			.orEmpty()
			.split('•', '\n')
			.mapNotNullToSet { it.trim().nullIfEmpty() }
		if (altTitles.isEmpty() && astroSeries != null) {
			altTitles = parseAltTitles(
				astroSeries.getStringOrNull("alternativeTitles")
					?: astroSeries.getStringOrNull("alternative_titles"),
			)
		}

		val description = doc.selectFirst("#description-text")
			?.html()
			?.trim()
			?.nullIfEmpty()
			?: astroSeries?.getStringOrNull("description")
				?.let { Jsoup.parseBodyFragment(it).body().html().trim().nullIfEmpty() }
			?: doc.selectFirst("span.font-medium.text-sm")?.text()?.trim()?.nullIfEmpty()

		val state = parseMangaState(
			astroSeries?.getStringOrNull("status")
				?: doc.selectFirst("div.grid > div:has(h3:eq(0):containsOwn(Status)) > h3:eq(1)")?.text(),
		) ?: manga.state
		val rawRating = astroSeries?.getStringOrNull("rating")?.toFloatOrNull()
			?: astroSeries?.optDouble("rating", Double.NaN)?.takeIf { !it.isNaN() }?.toFloat()
			?: doc.selectFirst("span.ml-1.text-xs")?.text()?.toFloatOrNull()
		val rating = normalizeRating(rawRating).takeIf { it != RATING_UNKNOWN } ?: manga.rating
		val coverUrl = astroSeries?.getStringOrNull("coverUrl")
			?: astroSeries?.getStringOrNull("cover")
			?: manga.coverUrl
		val publicUrl = astroPublicUrl?.toAbsoluteUrl(host) ?: manga.publicUrl

		val htmlChapters = doc.select("a.group[href*=/chapter/]").mapChapters(reversed = true) { i, a ->
			val rawChapterHref = a.attrAsRelativeUrl("href")
			rememberSlugToken(rawChapterHref)
			val urlRelative = normalizeRelativePath(rawChapterHref)
			val titleElement = a.selectFirst("span.font-medium") ?: a.selectFirst("span")
			val chapterLabel = titleElement?.text()?.trim()?.nullIfEmpty()
			val chapterTitle = a.selectFirst("span.text-sm.text-white\\/50")
				?.text()
				?.trim()
				?.nullIfEmpty()
			val fullTitle = when {
				chapterLabel != null && chapterTitle != null -> "$chapterLabel - $chapterTitle"
				chapterLabel != null -> chapterLabel
				else -> chapterTitle
			}
			val chapterNumber = chapterNumberRegex.find(chapterLabel.orEmpty())
				?.groupValues
				?.getOrNull(1)
				?.toFloatOrNull()
				?: i + 1f
			val dateText = a.selectFirst("span.text-sm.text-white\\/40")
				?.text()
				?.trim()
			MangaChapter(
				id = generateUid(urlRelative),
				title = fullTitle,
				number = chapterNumber,
				volume = 0,
				url = urlRelative,
				scanlator = null,
				uploadDate = parseChapterDate(chapterDateFormat, dateText),
				branch = null,
				source = source,
			)
		}.filter { chapter ->
			chapter.uploadDate == 0L || chapter.uploadDate <= cutoffTime
		}

		val chapters = htmlChapters.ifEmpty {
			if (seriesSlug != null) {
				parseAstroChapters(doc, seriesSlug, cutoffTime).orEmpty()
			} else {
				emptyList()
			}
		}

		return manga.copy(
			publicUrl = publicUrl,
			title = title,
			coverUrl = coverUrl,
			rating = rating,
			altTitles = altTitles,
			description = description.orEmpty(),
			tags = tags,
			authors = authors,
			state = state,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val cleanPath = normalizeRelativePath(chapter.url)
		val pathMatch = chapterPathRegex.find(cleanPath)
		val seriesSlug = pathMatch?.groupValues?.getOrNull(1)
		val chapterNum = pathMatch?.groupValues?.getOrNull(2)

		if (seriesSlug != null && chapterNum != null) {
			when (val apiResult = fetchPagesFromApi(seriesSlug, chapterNum, null)) {
				is ApiPagesResult.Pages -> return apiResult.pages
				ApiPagesResult.Locked -> return emptyList()
				ApiPagesResult.Unavailable -> Unit
			}
		}

		val doc = fetchChapterDocument(cleanPath, seriesSlug, chapterNum)
		return parsePagesFromDocument(doc, seriesSlug, chapterNum) ?: emptyList()
	}

	private suspend fun fetchChapterDocument(
		cleanPath: String,
		seriesSlug: String?,
		chapterNum: String?,
	): Document {
		val host = effectiveDomain
		val token = cachedSlugToken
		if (seriesSlug != null && chapterNum != null && token != null) {
			val tokenizedUrl = "https://$host/comics/$seriesSlug-$token/chapter/$chapterNum"
			try {
				return webClient.httpGet(tokenizedUrl).parseHtml()
			} catch (_: NotFoundException) {
				if (cachedSlugToken == token) {
					cachedSlugToken = null
				}
			}
		}
		return try {
			webClient.httpGet(cleanPath.toAbsoluteUrl(host)).parseHtml()
		} catch (e: NotFoundException) {
			if (seriesSlug != null && chapterNum != null) {
				val liveSlug = resolveLiveSeriesSlug(seriesSlug)
				if (liveSlug != null && liveSlug != seriesSlug) {
					val liveUrl = "https://$host/comics/$liveSlug/chapter/$chapterNum"
					val liveDoc = runCatchingCancellable { webClient.httpGet(liveUrl).parseHtml() }.getOrNull()
					if (liveDoc != null) {
						return liveDoc
					}
				}
			}
			throw e
		}
	}

	override fun intercept(chain: Interceptor.Chain): Response {
		val originalRequest = chain.request()
		val fragment = originalRequest.url.fragment
		val isTiledImage = fragment != null && fragment.startsWith(TILES_FRAGMENT_PREFIX)
		val preparedRequest = prepareBrowserRequest(originalRequest, isTiledImage)
		if (!isTiledImage && !isImageRequestUrl(preparedRequest.url) && preparedRequest.url.scheme == "https") {
			acquireRateLimitPermit()
		}
		val response = chain.proceed(preparedRequest)
		if (!isTiledImage || !response.isSuccessful) {
			return response
		}
		return context.redrawImageResponse(response) { bitmap ->
			descrambleTiles(bitmap, fragment!!.removePrefix(TILES_FRAGMENT_PREFIX))
		}
	}

	private fun prepareBrowserRequest(request: Request, isTiledImage: Boolean): Request {
		val siteHost = effectiveDomain
		val rewrittenUrl = rewriteLegacyAsuraUrl(request.url, siteHost)
		val isImage = isTiledImage || isImageRequestUrl(rewrittenUrl)
		val isApi = !isImage && (
			rewrittenUrl.host.startsWith("api.", ignoreCase = true) ||
				rewrittenUrl.encodedPath.startsWith("/api/")
			)
		val builder = request.newBuilder()
			.url(rewrittenUrl)
			.removeHeader("Origin")
			.header("Referer", "https://$siteHost/")

		if (request.header("Accept-Language") == null) {
			builder.header("Accept-Language", "en-US,en;q=0.9")
		}
		if (request.header("Accept") == null) {
			val accept = when {
				isApi -> "*/*"
				isImage -> "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8"
				else -> "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8"
			}
			builder.header("Accept", accept)
		}
		if (!isApi && !isImage && request.header("Upgrade-Insecure-Requests") == null) {
			builder.header("Upgrade-Insecure-Requests", "1")
		}
		if (rewrittenUrl.scheme == "https") {
			if (request.header("Sec-Fetch-Site") == null) {
				val fetchSite = when {
					rewrittenUrl.host.equals(siteHost, ignoreCase = true) -> "same-origin"
					rewrittenUrl.host.endsWith(".$siteHost", ignoreCase = true) -> "same-site"
					else -> "cross-site"
				}
				builder.header("Sec-Fetch-Site", fetchSite)
			}
			if (request.header("Sec-Fetch-Mode") == null) {
				val fetchMode = when {
					isApi -> "cors"
					isImage -> "no-cors"
					else -> "navigate"
				}
				builder.header("Sec-Fetch-Mode", fetchMode)
			}
			if (!isApi && !isImage && request.header("Sec-Fetch-User") == null) {
				builder.header("Sec-Fetch-User", "?1")
			}
			if (request.header("Sec-Fetch-Dest") == null) {
				val fetchDest = when {
					isApi -> "empty"
					isImage -> "image"
					else -> "document"
				}
				builder.header("Sec-Fetch-Dest", fetchDest)
			}
		}
		return builder.build()
	}

	private fun rewriteLegacyAsuraUrl(url: HttpUrl, siteHost: String): HttpUrl {
		val host = url.host
		return when {
			host.equals("asuracomic.net", ignoreCase = true) ||
				host.equals("www.asuracomic.net", ignoreCase = true) ->
				url.newBuilder().host(siteHost).build()

			host.equals("api.asuracomic.net", ignoreCase = true) -> {
				val targetApiHost = if (siteHost.equals("www.asurascans.com", ignoreCase = true)) {
					"api.asurascans.com"
				} else {
					"api.$siteHost"
				}
				url.newBuilder().host(targetApiHost).build()
			}

			else -> url
		}
	}

	private fun isImageRequestUrl(url: HttpUrl): Boolean {
		val lowerPath = url.encodedPath.lowercase(Locale.ENGLISH)
		return lowerPath.endsWith(".webp") ||
			lowerPath.endsWith(".jpg") ||
			lowerPath.endsWith(".jpeg") ||
			lowerPath.endsWith(".png") ||
			lowerPath.endsWith(".avif") ||
			lowerPath.endsWith(".gif") ||
			"/covers/" in lowerPath ||
			"/asura-images/" in lowerPath ||
			"/storage/media/" in lowerPath
	}

	private fun acquireRateLimitPermit() {
		var sleepMs = 0L
		synchronized(rateLimitLock) {
			val now = System.currentTimeMillis()
			while (requestTimestampsMs.isNotEmpty() && now - requestTimestampsMs.first() >= RATE_LIMIT_WINDOW_MS) {
				requestTimestampsMs.removeFirst()
			}
			if (requestTimestampsMs.size >= RATE_LIMIT_PERMITS) {
				val earliest = requestTimestampsMs.first()
				sleepMs = (RATE_LIMIT_WINDOW_MS - (now - earliest)).coerceAtLeast(0L)
				val scheduledAt = now + sleepMs
				requestTimestampsMs.removeFirst()
				requestTimestampsMs.addLast(scheduledAt)
			} else {
				requestTimestampsMs.addLast(now)
			}
		}
		if (sleepMs > 0L) {
			Thread.sleep(sleepMs)
		}
	}

	private suspend fun parsePagesFromDocument(
		doc: Document,
		seriesSlug: String?,
		chapterNum: String?,
	): List<MangaPage>? {
		parseAstroReaderPages(doc)?.let { return it }
		parseDirectImagePages(doc)?.let { return it }
		parseNextFlightPages(doc)?.let { return it }

		val docSlug = doc.location().toHttpUrlOrNull()?.pathSegments?.let { segments ->
			val idx = segments.indexOf("comics")
			if (idx != -1) segments.getOrNull(idx + 1) else null
		}
		rememberSlugToken(docSlug)
		val resolvedChapterNum = chapterNum ?: doc.location().toHttpUrlOrNull()?.pathSegments?.let { segments ->
			val idx = segments.indexOf("chapter")
			if (idx != -1) segments.getOrNull(idx + 1) else null
		}
		if (resolvedChapterNum != null) {
			val scrapedToken = extractPageTokenFromDoc(doc)
			if (seriesSlug != null && scrapedToken != null && scrapedToken != DEFAULT_PAGE_TOKEN) {
				(fetchPagesFromApi(seriesSlug, resolvedChapterNum, doc) as? ApiPagesResult.Pages)?.pages?.let { return it }
			}
			if (docSlug != null && docSlug != seriesSlug) {
				(fetchPagesFromApi(docSlug, resolvedChapterNum, doc) as? ApiPagesResult.Pages)?.pages?.let { return it }
			}
		}
		return null
	}

	private fun parseAstroReaderPages(doc: Document): List<MangaPage>? {
		val islands = doc.select(
			"astro-island[component-url*='ChapterReader'], astro-island[opts*='ChapterReader'], astro-island[props*='pages']",
		)
		for (island in islands) {
			val rawProps = island.attrOrNull("props") ?: continue
			val propsObj = rawProps.toJSONObjectOrNull()
				?: rawProps.replace("&quot;", "\"").toJSONObjectOrNull()
			val unwrapped = (propsObj?.let(::unwrapAstro) as? JSONObject)
			val pagesArray = unwrapped?.optJSONArray("pages")
			if (pagesArray != null) {
				parsePageJsonArray(pagesArray)?.let { return it }
			}
			pageUrlRegex.findAll(rawProps.replace("&quot;", "\""))
				.map { it.groupValues[1] }
				.distinct()
				.toList()
				.takeIf { it.isNotEmpty() }
				?.let { return it.toMangaPages() }
		}
		return null
	}

	private fun parseAstroSeriesDetails(doc: Document): JSONObject? {
		val islands = doc.select("astro-island[props*='title'][props*='description']")
		for (island in islands) {
			val rawProps = island.attrOrNull("props") ?: continue
			val propsObj = rawProps.toJSONObjectOrNull()
				?: rawProps.replace("&quot;", "\"").toJSONObjectOrNull()
				?: continue
			val unwrapped = unwrapAstro(propsObj) as? JSONObject ?: continue
			if (unwrapped.has("title")) {
				return unwrapped
			}
		}
		return null
	}

	private fun parseAstroPublicUrl(doc: Document): String? {
		val islands = doc.select("astro-island[props*='publicUrl']")
		for (island in islands) {
			val rawProps = island.attrOrNull("props") ?: continue
			val propsObj = rawProps.toJSONObjectOrNull()
				?: rawProps.replace("&quot;", "\"").toJSONObjectOrNull()
				?: continue
			val unwrapped = unwrapAstro(propsObj) as? JSONObject ?: continue
			unwrapped.getStringOrNull("publicUrl")?.nullIfEmpty()?.let { return it }
		}
		return null
	}

	private fun parseDirectImagePages(doc: Document): List<MangaPage>? {
		val candidates = doc.select(
			"img[src*='/chapters/'], img[data-src*='/chapters/'], " +
				"div.py-8 img[src*='/storage/media/'], div.py-8 img[data-src*='/storage/media/'], " +
				"img[alt*='chapter page'], img[alt*='Chapter Page'], img[alt*='Chapter page']",
		)
		val urls = candidates.mapNotNull { img ->
			(img.src() ?: img.attrOrNull("data-src", "src")?.takeIf { it.startsWith("http") })
				?.takeUnless(::isNonPageImageUrl)
		}.distinct()

		return urls.takeIf { it.isNotEmpty() }?.toMangaPages()
	}

	private fun parseNextFlightPages(doc: Document): List<MangaPage>? {
		val scripts = doc.select("script")
		if (scripts.isEmpty()) return null
		val sb = StringBuilder()
		for (script in scripts) {
			val raw = script.data().substringBetween("self.__next_f.push(", ")", "").trim()
			if (raw.isEmpty()) continue
			val ja = raw.toJSONArrayOrNull() ?: continue
			for (i in 0 until ja.length()) {
				(ja.opt(i) as? String)?.let { sb.append(it) }
			}
		}
		if (sb.isEmpty()) return null
		val pages = TreeMap<Int, String>()
		for (line in sb.toString().split('\n')) {
			val obj = line.substringAfter(':').toJSONObjectOrNull() ?: continue
			if (obj.has("order") && obj.has("url")) {
				pages[obj.getInt("order")] = obj.getString("url")
			}
		}
		return pages.values.takeIf { it.isNotEmpty() }?.toMangaPages()
	}

	private fun extractPageTokenFromDoc(doc: Document?): String? {
		return doc?.select("script")
			?.firstNotNullOfOrNull { pageTokenRegex.find(it.data())?.groupValues?.getOrNull(1) }
	}

	private sealed interface ApiPagesResult {
		data class Pages(val pages: List<MangaPage>) : ApiPagesResult
		data object Locked : ApiPagesResult
		data object Unavailable : ApiPagesResult
	}

	private suspend fun fetchPagesFromApi(
		slug: String,
		chapterNum: String,
		doc: Document?,
	): ApiPagesResult {
		val pageToken = extractPageTokenFromDoc(doc) ?: DEFAULT_PAGE_TOKEN
		val headersBuilder = Headers.Builder().set("X-Page-Token", pageToken)
		val accessToken = findAccessTokenCookie()
		if (!accessToken.isNullOrBlank()) {
			headersBuilder.set("Authorization", "Bearer $accessToken")
		}
		val json = runCatchingCancellable {
			webClient.httpGet("$apiUrl/series/$slug/chapters/$chapterNum", headersBuilder.build()).parseJson()
		}.getOrNull() ?: return ApiPagesResult.Unavailable
		val dataObj = json.optJSONObject("data")
		if (dataObj?.getBooleanOrDefault("is_locked", false) == true || json.getBooleanOrDefault("is_locked", false)) {
			return ApiPagesResult.Locked
		}
		val pagesArray = dataObj?.optJSONObject("chapter")?.optJSONArray("pages")
			?: json.optJSONObject("chapter")?.optJSONArray("pages")
			?: dataObj?.optJSONArray("pages")
			?: json.optJSONArray("pages")
			?: return ApiPagesResult.Unavailable
		val pages = parsePageJsonArray(pagesArray)?.takeIf { it.isNotEmpty() } ?: return ApiPagesResult.Unavailable
		return ApiPagesResult.Pages(pages)
	}

	private fun parsePageJsonArray(pagesArray: JSONArray): List<MangaPage>? {
		data class IndexedPage(val order: Int, val url: String)
		val entries = ArrayList<IndexedPage>(pagesArray.length())
		for (i in 0 until pagesArray.length()) {
			when (val item = pagesArray.opt(i)) {
				is String -> if (item.isNotBlank()) entries.add(IndexedPage(i, item))
				is JSONObject -> {
					val rawUrl = item.getStringOrNull("url") ?: continue
					val order = item.getIntOrDefault("order", i)
					val url = attachTilesFragment(rawUrl, item)
					entries.add(IndexedPage(order, url))
				}
			}
		}
		if (entries.isEmpty()) return null
		entries.sortBy { it.order }
		return entries.map { it.url }.distinct().toMangaPages()
	}

	private fun attachTilesFragment(rawUrl: String, pageObj: JSONObject): String {
		val tilesArr = pageObj.optJSONArray("tiles") ?: return rawUrl
		if (tilesArr.length() == 0) return rawUrl
		val tiles = IntArray(tilesArr.length()) { idx ->
			tilesArr.optInt(idx, idx)
		}
		val cols = pageObj.getIntOrDefault("tile_cols", pageObj.getIntOrDefault("tileCols", DEFAULT_TILE_COLS))
		val rows = pageObj.getIntOrDefault("tile_rows", pageObj.getIntOrDefault("tileRows", DEFAULT_TILE_ROWS))
		if (cols <= 0 || rows <= 0) return rawUrl
		val httpUrl = rawUrl.toHttpUrlOrNull() ?: return rawUrl
		val payload = "$TILES_FRAGMENT_PREFIX${cols}x$rows:${tiles.joinToString(",")}"
		return httpUrl.newBuilder().fragment(payload).build().toString()
	}

	private fun descrambleTiles(bitmap: Bitmap, spec: String): Bitmap {
		val dimsPart = spec.substringBefore(':', "")
		val tilesPart = spec.substringAfter(':', "")
		val cols = dimsPart.substringBefore('x', "").toIntOrNull() ?: return bitmap
		val rows = dimsPart.substringAfter('x', "").toIntOrNull() ?: return bitmap
		if (cols <= 0 || rows <= 0 || tilesPart.isEmpty()) return bitmap
		val tiles = tilesPart.split(',').map { it.toIntOrNull() ?: return bitmap }
		val tileW = bitmap.width / cols
		val tileH = bitmap.height / rows
		val total = cols * rows
		if (tileW <= 0 || tileH <= 0) return bitmap

		val output = context.createBitmap(tileW * cols, tileH * rows)
		for (w in tiles.indices) {
			val j = tiles[w]
			if (w >= total || j !in 0 until total) continue
			val srcCol = w % cols
			val srcRow = w / cols
			val dstCol = j % cols
			val dstRow = j / cols
			val srcRect = Rect(srcCol * tileW, srcRow * tileH, (srcCol + 1) * tileW, (srcRow + 1) * tileH)
			val dstRect = Rect(dstCol * tileW, dstRow * tileH, (dstCol + 1) * tileW, (dstRow + 1) * tileH)
			output.drawBitmap(bitmap, srcRect, dstRect)
		}
		return output
	}

	private suspend fun fetchDetailsFromApi(manga: Manga, seriesSlug: String, cutoffTime: Long): Manga? {
		val series = fetchSeriesJson(seriesSlug) ?: return null
		val publicUrlPath = series.getStringOrNull("public_url") ?: series.getStringOrNull("publicUrl")
		rememberSlugToken(publicUrlPath)
		val liveSlug = publicUrlPath?.trim('/')?.substringAfter("comics/", "")?.substringBefore('/')?.nullIfEmpty()
		val chapters = fetchChaptersFromApi(seriesSlug, liveSlug, series, cutoffTime)
			?: fetchChaptersFromHtmlFallback(seriesSlug, cutoffTime)
			?: emptyList()
		val description = series.getStringOrNull("description")
			?.let { Jsoup.parseBodyFragment(it).body().html().trim().nullIfEmpty() }
			.orEmpty()
		val rawRating = series.getStringOrNull("rating")?.toFloatOrNull()
			?: series.optDouble("rating", Double.NaN).takeIf { !it.isNaN() }?.toFloat()
		val normalizedRating = normalizeRating(rawRating)
		return manga.copy(
			publicUrl = publicUrlPath?.nullIfEmpty()?.toAbsoluteUrl(effectiveDomain) ?: manga.publicUrl,
			title = series.getStringOrNull("title")?.trim()?.nullIfEmpty() ?: manga.title,
			coverUrl = series.getStringOrNull("cover")
				?: series.getStringOrNull("coverUrl")
				?: series.getStringOrNull("cover_url")
				?: manga.coverUrl,
			rating = if (normalizedRating != RATING_UNKNOWN) normalizedRating else manga.rating,
			altTitles = parseAltTitles(
				series.getStringOrNull("alternativeTitles") ?: series.getStringOrNull("alternative_titles"),
			),
			description = description,
			tags = parseApiGenres(series.optJSONArray("genres")),
			authors = parseApiAuthors(series),
			state = parseMangaState(series.getStringOrNull("status")) ?: manga.state,
			chapters = chapters,
		)
	}

	private suspend fun fetchChaptersFromHtmlFallback(seriesSlug: String, cutoffTime: Long): List<MangaChapter>? {
		val doc = runCatchingCancellable {
			fetchSeriesDocument("/comics/$seriesSlug", seriesSlug, cachedSlugToken)
		}.getOrNull() ?: return null
		return parseAstroChapters(doc, seriesSlug, cutoffTime)
	}

	private suspend fun fetchSeriesJson(seriesSlug: String): JSONObject? {
		val json = runCatchingCancellable {
			webClient.httpGet("$apiUrl/series/$seriesSlug").parseJson()
		}.getOrNull() ?: return null
		return json.optJSONObject("data")?.optJSONObject("series")
			?: json.optJSONObject("series")
			?: json.optJSONObject("data")?.takeIf { it.has("title") || it.has("slug") }
	}

	private suspend fun resolveLiveSeriesSlug(seriesSlug: String): String? {
		fetchSeriesJson(seriesSlug)?.let { series ->
			val publicUrl = series.getStringOrNull("public_url") ?: series.getStringOrNull("publicUrl")
			rememberSlugToken(publicUrl)
			val seg = publicUrl?.trim('/')?.substringAfter("comics/", "")?.substringBefore('/')?.nullIfEmpty()
			if (seg != null) return seg
		}
		return cachedSlugToken?.let { "$seriesSlug-$it" }
	}

	private fun parseAstroChapters(doc: Document, seriesSlug: String, cutoffTime: Long): List<MangaChapter>? {
		val islands = doc.select(
			"astro-island[component-url*='ChapterList'], astro-island[opts*='ChapterList'], astro-island[props*='chapters']",
		)
		for (island in islands) {
			val rawProps = island.attrOrNull("props") ?: continue
			val propsObj = rawProps.toJSONObjectOrNull()
				?: rawProps.replace("&quot;", "\"").toJSONObjectOrNull()
				?: continue
			val unwrapped = unwrapAstro(propsObj) as? JSONObject ?: continue
			val arr = unwrapped.optJSONArray("chapters") ?: continue
			parseApiChaptersArray(arr, seriesSlug, cutoffTime).takeIf { it.isNotEmpty() }?.let { return it }
		}
		return null
	}

	private suspend fun fetchChaptersFromApi(
		seriesSlug: String,
		liveSlug: String?,
		series: JSONObject,
		cutoffTime: Long,
	): List<MangaChapter>? {
		var bestArray = fetchRawChaptersArray(seriesSlug)
		val hasTokenizedLiveSlug = liveSlug != null && !liveSlug.equals(seriesSlug, ignoreCase = true)
		if (hasTokenizedLiveSlug && (bestArray == null || isChaptersArrayBehindSeries(bestArray, series))) {
			val liveArray = fetchRawChaptersArray(liveSlug)
			if (liveArray != null && (bestArray == null || maxChapterNumber(liveArray) >= maxChapterNumber(bestArray))) {
				bestArray = liveArray
			}
		}
		val arr = bestArray ?: return null
		return parseApiChaptersArray(arr, seriesSlug, cutoffTime)
	}

	private suspend fun fetchRawChaptersArray(slug: String): JSONArray? {
		val json = runCatchingCancellable {
			webClient.httpGet("$apiUrl/series/$slug/chapters").parseJson()
		}.getOrNull() ?: return null
		return json.optJSONArray("data")
			?: json.optJSONArray("chapters")
			?: json.optJSONObject("data")?.optJSONArray("chapters")
	}

	private fun maxChapterNumber(arr: JSONArray): Float {
		var max = -1f
		for (i in 0 until arr.length()) {
			val num = (arr.opt(i) as? JSONObject)?.getFloatOrDefault("number", -1f) ?: -1f
			if (num > max) max = num
		}
		return max
	}

	private fun isChaptersArrayBehindSeries(arr: JSONArray, series: JSONObject): Boolean {
		val latestArr = series.optJSONArray("latest_chapters") ?: series.optJSONArray("latestChapters") ?: return false
		val expectedMax = maxChapterNumber(latestArr)
		return expectedMax >= 0f && maxChapterNumber(arr) + 0.0001f < expectedMax
	}

	private fun findAccessTokenCookie(): String? {
		val primary = context.cookieJar.getCookies(effectiveDomain)
			.firstOrNull { it.name == "access_token" }
			?.value
		if (!primary.isNullOrBlank()) return primary
		val rawDomain = domain
		if (!rawDomain.equals(effectiveDomain, ignoreCase = true)) {
			return context.cookieJar.getCookies(rawDomain)
				.firstOrNull { it.name == "access_token" }
				?.value
		}
		return null
	}

	private fun parseApiChaptersArray(
		arr: JSONArray,
		seriesSlug: String,
		cutoffTime: Long,
	): List<MangaChapter> {
		val isLoggedIn = !findAccessTokenCookie().isNullOrBlank()
		val now = System.currentTimeMillis()
		return arr.mapJSONNotNull { obj ->
			val number = obj.getFloatOrDefault("number", -1f)
			if (number < 0f) return@mapJSONNotNull null
			val uploadDate = parseIsoDate(
				obj.getStringOrNull("published_at")
					?: obj.getStringOrNull("publishedAt")
					?: obj.getStringOrNull("created_at")
					?: obj.getStringOrNull("createdAt"),
			)
			if (!isLoggedIn && isChapterLocked(obj, uploadDate, now, cutoffTime)) {
				return@mapJSONNotNull null
			}

			val numberStr = number.formatSimple()
			val urlRelative = "/comics/$seriesSlug/chapter/$numberStr"
			val chapterTitle = obj.getStringOrNull("title")?.trim()?.nullIfEmpty()
			val fullTitle = if (chapterTitle != null) {
				"Chapter $numberStr - $chapterTitle"
			} else {
				"Chapter $numberStr"
			}
			MangaChapter(
				id = generateUid(urlRelative),
				title = fullTitle,
				number = number,
				volume = 0,
				url = urlRelative,
				scanlator = null,
				uploadDate = uploadDate,
				branch = null,
				source = source,
			)
		}.distinctBy { it.id }.sortedBy { it.number }
	}

	private fun isChapterLocked(
		obj: JSONObject,
		uploadDate: Long,
		now: Long,
		cutoffTime: Long,
	): Boolean {
		if (obj.getBooleanOrDefault("is_locked", false) || obj.getBooleanOrDefault("isLocked", false)) {
			return true
		}
		if (!obj.getStringOrNull("access_gate").isNullOrBlank() || !obj.getStringOrNull("accessGate").isNullOrBlank()) {
			return true
		}

		val rawUnlock = obj.getStringOrNull("early_access_until")
			?: obj.getStringOrNull("earlyAccessUntil")
			?: obj.getStringOrNull("unlock_time")
			?: obj.getStringOrNull("unlockTime")
			?: obj.getStringOrNull("available_at")
			?: obj.getStringOrNull("availableAt")
		if (rawUnlock != null) {
			val unlockAt = parseIsoDate(rawUnlock)
			// Asura leaves is_premium=true on formerly early-access chapters even after
			// early_access_until has elapsed and the chapter is publicly readable.
			return unlockAt <= 0L || unlockAt > now
		}

		if (obj.hasNonNull("is_locked") || obj.hasNonNull("isLocked")) {
			return false
		}

		val isPremium = obj.getBooleanOrDefault("is_premium", false) ||
			obj.getBooleanOrDefault("isPremium", false)
		if (isPremium) {
			val hasZeroPageCount = (obj.hasNonNull("page_count") && obj.getIntOrDefault("page_count", -1) == 0) ||
				(obj.hasNonNull("pageCount") && obj.getIntOrDefault("pageCount", -1) == 0)
			if (hasZeroPageCount) {
				return true
			}
			return uploadDate <= 0L || uploadDate > cutoffTime
		}

		val hasLockMetadata = obj.has("is_premium") || obj.has("isPremium") ||
			obj.has("is_locked") || obj.has("isLocked") ||
			obj.has("early_access_until") || obj.has("earlyAccessUntil") ||
			obj.has("unlock_time") || obj.has("unlockTime") ||
			obj.has("available_at") || obj.has("availableAt")
		return !hasLockMetadata && uploadDate > cutoffTime
	}

	private fun JSONObject.hasNonNull(name: String): Boolean =
		opt(name)?.takeUnless { it === JSONObject.NULL } != null

	private fun unwrapAstro(value: Any?): Any? = when (value) {
		is JSONArray -> when {
			value.length() == 1 && value.opt(0) is Number -> null
			value.length() == 2 && value.opt(0) is Number -> unwrapAstro(value.opt(1))
			else -> JSONArray().apply {
				for (i in 0 until value.length()) {
					put(unwrapAstro(value.opt(i)))
				}
			}
		}

		is JSONObject -> JSONObject().apply {
			for (key in value.keys()) {
				put(key, unwrapAstro(value.opt(key)))
			}
		}

		JSONObject.NULL -> null
		else -> value
	}

	private fun Collection<String>.toMangaPages(): List<MangaPage> = map { url ->
		MangaPage(
			id = generateUid(url),
			url = url,
			preview = null,
			source = source,
		)
	}

	private fun isNonPageImageUrl(url: String): Boolean {
		val lower = url.lowercase(Locale.ENGLISH)
		val fileName = lower.substringAfterLast('/')
		return lower.endsWith(".svg") ||
			"/covers/" in lower ||
			"/avatars/" in lower ||
			"/banners/" in lower ||
			"/ads/" in lower ||
			"/logos/" in lower ||
			fileName.startsWith("logo.")
	}

	private fun toMangaTag(title: String): MangaTag {
		return tagMap[title.lowercase(Locale.ENGLISH)] ?: MangaTag(
			key = title.toAsuraGenreKey(),
			title = title,
			source = source,
		)
	}

	private fun parseApiAuthors(series: JSONObject): Set<String> = buildSet {
		series.getStringOrNull("author")?.trim()?.takeIf { it.isNotEmpty() && it != "_" }?.let(::add)
		series.getStringOrNull("artist")?.trim()?.takeIf { it.isNotEmpty() && it != "_" }?.let(::add)
	}

	private fun parseApiGenres(genres: JSONArray?): Set<MangaTag> {
		if (genres == null) return emptySet()
		return genres.mapJSONNotNullToSet { g ->
			val name = g.getStringOrNull("name")?.trim()?.nullIfEmpty() ?: return@mapJSONNotNullToSet null
			val slug = g.getStringOrNull("slug")?.trim()?.nullIfEmpty()
			tagMap[name.lowercase(Locale.ENGLISH)] ?: MangaTag(
				key = slug ?: name.toAsuraGenreKey(),
				title = name,
				source = source,
			)
		}
	}

	private fun parseAltTitles(raw: String?): Set<String> {
		if (raw.isNullOrBlank()) return emptySet()
		val delimiter = if ('•' in raw) '•' else ','
		return raw.split(delimiter, '\n').mapNotNullToSet { it.trim().nullIfEmpty() }
	}

	private fun normalizeRating(raw: Float?): Float {
		if (raw == null || raw <= 0f || raw.isNaN()) return RATING_UNKNOWN
		return if (raw > 1f) (raw / 10f).coerceIn(0f, 1f) else raw.coerceIn(0f, 1f)
	}

	private fun parseMangaState(status: String?): MangaState? {
		return when (status?.trim()?.lowercase(Locale.ENGLISH)) {
			"ongoing", "season end" -> MangaState.ONGOING
			"completed" -> MangaState.FINISHED
			"hiatus" -> MangaState.PAUSED
			"dropped", "axed" -> MangaState.ABANDONED
			"coming soon" -> MangaState.UPCOMING
			else -> null
		}
	}

	private fun parseIsoDate(raw: String?): Long {
		val str = raw?.trim()?.nullIfEmpty() ?: return 0L
		val match = isoTimestampRegex.matchEntire(str) ?: return 0L
		return runCatching {
			val year = match.groupValues[1].toInt()
			val month = match.groupValues[2].toInt()
			val day = match.groupValues[3].toInt()
			val hour = match.groupValues[4].ifEmpty { "0" }.toInt()
			val minute = match.groupValues[5].ifEmpty { "0" }.toInt()
			val second = match.groupValues[6].ifEmpty { "0" }.toInt()
			val fraction = match.groupValues[7]
			val millis = if (fraction.isEmpty()) {
				0
			} else {
				fraction.take(3).padEnd(3, '0').toInt()
			}
			val calendar = GregorianCalendar(UTC_TIME_ZONE, Locale.ROOT).apply {
				isLenient = false
				clear()
				set(year, month - 1, day, hour, minute, second)
				set(Calendar.MILLISECOND, millis)
			}
			var epochMs = calendar.timeInMillis
			val offsetSign = match.groupValues[9]
			if (offsetSign.isNotEmpty()) {
				val offsetHours = match.groupValues[10].toInt()
				val offsetMinutes = match.groupValues[11].toInt()
				if (offsetHours > 23 || offsetMinutes > 59) return 0L
				val totalOffsetMs = (offsetHours * 60L + offsetMinutes) * 60_000L
				epochMs += if (offsetSign == "+") -totalOffsetMs else totalOffsetMs
			}
			epochMs
		}.getOrDefault(0L)
	}

	private fun extractSlugToken(urlOrPath: String?): String? {
		if (urlOrPath.isNullOrBlank()) return null
		return slugTokenExtractRegex.find(urlOrPath)?.groupValues?.getOrNull(1)
	}

	private fun rememberSlugToken(urlOrPath: String?) {
		extractSlugToken(urlOrPath)?.let { token ->
			cachedSlugToken = token
		}
	}

	private fun normalizeRelativePath(url: String): String {
		val rawPath = if (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) {
			url.toHttpUrlOrNull()?.encodedPath ?: url.toRelativeUrl(effectiveDomain)
		} else {
			url
		}
		val clean = rawPath.stripSlugToken()
		return when {
			clean.startsWith("/series/") -> "/comics/" + clean.removePrefix("/series/")
			clean.startsWith("/") -> clean
			else -> "/$clean"
		}
	}

	private fun String.toAsuraGenreKey(): String {
		return trim()
			.lowercase(Locale.ENGLISH)
			.replace(asuraGenreKeyRegex, "-")
			.trim('-')
	}

	private fun parseChapterDate(dateFormat: DateFormat, date: String?): Long {
		val value = date?.trim().orEmpty()
		if (value.isEmpty()) return 0L
		val lower = value.lowercase(Locale.ENGLISH)
		return when {
			lower == "last week" -> Calendar.getInstance().apply {
				add(Calendar.WEEK_OF_YEAR, -1)
			}.timeInMillis

			lower == "yesterday" -> Calendar.getInstance().apply {
				add(Calendar.DAY_OF_MONTH, -1)
			}.timeInMillis

			lower.endsWith(" ago") -> parseRelativeDate(lower)
			else -> synchronized(dateFormat) {
				dateFormat.parseSafe(value.replace(regexDate, "$1"))
			}
		}
	}

	private fun parseRelativeDate(date: String): Long {
		val number = Regex("""(\d+)""").find(date)?.value?.toIntOrNull() ?: return 0L
		val cal = Calendar.getInstance()
		return when {
			WordSet("second", "seconds").anyWordIn(date) -> cal.apply { add(Calendar.SECOND, -number) }.timeInMillis
			WordSet("minute", "minutes", "min", "mins").anyWordIn(date) -> cal.apply { add(Calendar.MINUTE, -number) }.timeInMillis
			WordSet("hour", "hours").anyWordIn(date) -> cal.apply { add(Calendar.HOUR, -number) }.timeInMillis
			WordSet("day", "days").anyWordIn(date) -> cal.apply { add(Calendar.DAY_OF_MONTH, -number) }.timeInMillis
			WordSet("week", "weeks").anyWordIn(date) -> cal.apply { add(Calendar.WEEK_OF_YEAR, -number) }.timeInMillis
			WordSet("month", "months").anyWordIn(date) -> cal.apply { add(Calendar.MONTH, -number) }.timeInMillis
			WordSet("year", "years").anyWordIn(date) -> cal.apply { add(Calendar.YEAR, -number) }.timeInMillis
			else -> 0L
		}
	}

	/**
	 * Asura appends a rotating site-wide 8-hex token to every series slug
	 * (`/comics/<slug>-00dcbf97`). Stored URLs drop the token so manga and
	 * chapter IDs remain stable across site redeploys, while outbound HTML
	 * fallback requests reattach the live token when known to avoid hitting
	 * uncached tokenless redirect routes.
	 */
	private fun String.stripSlugToken(): String = replace(slugTokenRegex, "")

	private companion object {
		private const val CHAPTER_HIDE_WINDOW_MS = 6L * 60L * 60L * 1000L
		private val UTC_TIME_ZONE: TimeZone = TimeZone.getTimeZone("GMT")
		private val isoTimestampRegex = Regex(
			"^(\\d{4})-(\\d{2})-(\\d{2})(?:[T\\s](\\d{2}):(\\d{2})(?::(\\d{2})(?:\\.(\\d+))?)?(Z|([+-])(\\d{2}):?(\\d{2}))?)?$",
			RegexOption.IGNORE_CASE,
		)
		private const val DEFAULT_PAGE_TOKEN = "asura-reader-2026"
		private const val TILES_FRAGMENT_PREFIX = "tiles="
		private const val DEFAULT_TILE_COLS = 4
		private const val DEFAULT_TILE_ROWS = 5
		private const val RATE_LIMIT_PERMITS = 2
		private const val RATE_LIMIT_WINDOW_MS = 1_500L
		private val slugTokenRegex = Regex("""-[0-9a-f]{8}(?=/|$)""")
		private val slugTokenExtractRegex = Regex("""(?:^|/(?:comics|series)/)[^/?#]+?-([0-9a-f]{8})(?=/|[?#]|$)""")
		private val chapterPathRegex = Regex("""^/comics/([^/]+)/chapter/([^/?#]+)""")
		private val chapterNumberRegex = Regex("""Chapter\s+(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
		private val pageUrlRegex = Regex(""""url":\s*\[0,\s*"([^"]+)"""")
		private val pageTokenRegex = Regex("""pageToken\s*[:=]\s*["']([^"']+)["']""")
		private val ASURA_GENRES = listOf(
			"Action",
			"Adventure",
			"Comedy",
			"Crazy MC",
			"Demon",
			"Dungeons",
			"Fantasy",
			"Game",
			"Genius MC",
			"Isekai",
			"Magic",
			"Murim",
			"Mystery",
			"Necromancer",
			"Overpowered",
			"Regression",
			"Reincarnation",
			"Revenge",
			"Romance",
			"School Life",
			"Sci-fi",
			"Shoujo",
			"Shounen",
			"System",
			"Tower",
			"Tragedy",
			"Villain",
			"Violence",
		)
		private val asuraGenreKeyRegex = Regex("[^a-z0-9]+")
	}
}
