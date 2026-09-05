package org.koitharu.kotatsu.parsers.site.all

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.HttpStatusException
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.exception.TooManyRequestExceptions
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.network.UserAgents
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import java.util.EnumSet
import java.util.LinkedHashMap
import java.util.Locale

internal abstract class OniSagaParser(
	context: MangaLoaderContext,
	source: MangaParserSource,
	private val languageCode: String,
) : PagedMangaParser(context, source, PAGE_SIZE), Interceptor {

	override val configKeyDomain = ConfigKey.Domain("onisaga.com")
	override val userAgentKey = ConfigKey.UserAgent(UserAgents.CHROME_DESKTOP)

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.RATING,
		SortOrder.NEWEST,
		SortOrder.ALPHABETICAL,
	)

	override val filterCapabilities = MangaListFilterCapabilities(
		isMultipleTagsSupported = true,
		isTagsExclusionSupported = true,
		isSearchSupported = true,
		isSearchWithFiltersSupported = true,
		isYearSupported = true,
	)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
		keys.add(ConfigKey.InterceptCloudflare(defaultValue = true))
	}

	override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
		.add("Referer", "https://$domain/")
		.build()

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		val originalUrl = unwrapImageProxy(request.url)
		val encodedReferer = originalUrl.fragment
			?.takeIf { it.startsWith(IMAGE_REFERER_FRAGMENT) }
			?.removePrefix(IMAGE_REFERER_FRAGMENT)
		val referer = encodedReferer?.let {
			runCatching { context.decodeBase64(it).toString(Charsets.UTF_8) }.getOrNull()
		}
		val imageRequest = if (referer == null) {
			request
		} else {
			request.newBuilder()
				.url(originalUrl.newBuilder().fragment(null).build())
				.header("Referer", referer)
				.build()
		}
		return chain.proceed(imageRequest)
	}

	private fun unwrapImageProxy(url: HttpUrl): HttpUrl = when (url.host) {
		"wsrv.nl" -> url.queryParameter("url")?.toHttpUrlOrNull() ?: url
		"v.recipes" -> url.toString()
			.substringAfter("https://v.recipes/i/", "")
			.takeIf(String::isNotEmpty)
			?.toHttpUrlOrNull()
			?: url
		else -> url
	}

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = GENRES.mapTo(linkedSetOf()) {
			MangaTag(key = it.second, title = it.first, source = source)
		},
		availableStates = EnumSet.of(
			MangaState.ONGOING,
			MangaState.FINISHED,
			MangaState.PAUSED,
			MangaState.ABANDONED,
		),
		availableContentRating = EnumSet.of(ContentRating.SAFE, ContentRating.ADULT),
		availableContentTypes = EnumSet.of(
			ContentType.MANGA,
			ContentType.MANHWA,
			ContentType.MANHUA,
			ContentType.NOVEL,
			ContentType.ONE_SHOT,
			ContentType.DOUJINSHI,
		),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val query = filter.query?.trim()?.nullIfEmpty()
		val pageUrl = if (query == null) {
			"https://$domain/browse"
		} else {
			"https://$domain".toHttpUrl().newBuilder()
				.addPathSegment("search")
				.addPathSegment(query)
				.build()
				.toString()
		}
		val updates = filter.toLivewireUpdates(order)
		val stateKey = "$pageUrl\n${updates.cacheKey()}"
		val contentRating = filter.contentRating.oneOrThrowIfMany()
		val listKey = "${source.name}\n$page\n$stateKey\n${contentRating?.name.orEmpty()}"
		getCachedMangaList(listKey)?.let { return it }
		return getMangaListLock(listKey).withLock {
			getCachedMangaList(listKey) ?: loadListPage(page, pageUrl, stateKey, updates, contentRating).also {
				cacheMangaList(listKey, it)
			}
		}
	}

	private suspend fun loadListPage(
		page: Int,
		pageUrl: String,
		stateKey: String,
		updates: LivewireUpdates,
		contentRating: ContentRating?,
	): List<Manga> {
		val topMangaUrl = if (pageUrl == "https://$domain/browse") updates.toTopMangaUrl(domain, page) else null
		if (topMangaUrl != null) {
			val rankedManga = runCatchingCancellable {
				parseRankedMangaList(webClient.httpGet(topMangaUrl).parseHtml())
					.filter { contentRating == null || it.contentRating == contentRating }
			}.getOrNull()
			if (!rankedManga.isNullOrEmpty()) return rankedManga
		}
		val directPage = page == 1 && updates.isDefault()
		var initialState = getCachedState(INITIAL_LIST_STATES, pageUrl)
		var initialDocument: Document? = null
		if (directPage || initialState == null) {
			initialDocument = webClient.httpGet(pageUrl).parseHtml()
			initialState = initialDocument.extractLivewireState(POST_FILTER_COMPONENT)
			if (initialState != null) cacheState(INITIAL_LIST_STATES, pageUrl, initialState)
		}
		val document = if (directPage) {
			checkNotNull(initialDocument)
		} else {
			val state = getCachedState(ACTIVE_LIST_STATES, stateKey)
				?: initialState
				?: throw ParseException("Could not find Livewire browse state", pageUrl)
			val result = fetchLivewirePage(pageUrl, state, page, updates)
			cacheState(ACTIVE_LIST_STATES, stateKey, result.state)
			result.document
		}
		return parseMangaList(document).filter {
			contentRating == null || it.contentRating == contentRating
		}
	}

	private suspend fun fetchLivewirePage(
		pageUrl: String,
		state: LivewireState,
		page: Int,
		updates: LivewireUpdates,
	): LivewirePage {
		val payload = createLivewirePayload(
			state = state,
			updates = updates.toJson(),
			calls = listOf(LivewireCall("gotoPage", JSONArray().put(page.toString()))),
		)
		val response = livewireRequestLock.withLock {
			webClient.httpPost(
				"https://$domain/livewire/update".toHttpUrl(),
				payload,
				livewireHeaders(pageUrl),
			).parseJson()
		}
		val component = response.firstComponent()
			?: throw ParseException("Empty Livewire browse response", pageUrl)
		val html = component
			.optJSONObject("effects")
			?.getStringOrNull("html")
			.orEmpty()
		val nextState = component.getStringOrNull("snapshot")?.let { LivewireState(it, state.token) } ?: state
		return LivewirePage(Jsoup.parseBodyFragment(html, "https://$domain"), nextState)
	}

	private fun parseMangaList(document: Document): List<Manga> =
		document.select("div.relative.group").mapNotNull { it.toManga() }.distinctBy(Manga::url)

	private fun parseRankedMangaList(document: Document): List<Manga> = document
		.select("a[href*='/manga/']")
		.mapNotNull { link ->
			val url = link.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
			val segments = url.substringBefore('?').trim('/').split('/')
			if (segments.firstOrNull() != "manga" || segments.getOrNull(1).isNullOrEmpty()) {
				return@mapNotNull null
			}
			val image = link.selectFirst("img")
			val title = link.selectFirst("[data-flux-heading], h2, h3, h4, h5")
				?.text()
				?.trim()
				?.nullIfEmpty()
				?: image?.attr("alt")
					?.removeSuffix(" manga cover")
					?.trim()
					?.nullIfEmpty()
				?: RANKED_TITLE_REGEX.find(link.text().trim())?.groupValues?.get(1)?.trim()?.nullIfEmpty()
				?: segments[1].replace('-', ' ').replaceFirstChar { it.titlecase(sourceLocale) }
			Manga(
				id = generateUid(url),
				title = title,
				altTitles = emptySet(),
				url = url,
				publicUrl = url.toAbsoluteUrl(domain),
				rating = RATING_UNKNOWN,
				contentRating = if ("18+" in link.text()) ContentRating.ADULT else ContentRating.SAFE,
				coverUrl = image?.resolveImageUrl(),
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				source = source,
			)
		}
		.distinctBy(Manga::url)

	private fun Element.toManga(): Manga? {
		val link = if (tagName() == "a") this else selectFirst("a[href*=\"/manga/\"]") ?: return null
		val url = link.attrAsRelativeUrlOrNull("href") ?: return null
		val segments = url.substringBefore('?').trim('/').split('/')
		if (segments.firstOrNull() != "manga" || segments.getOrNull(1).isNullOrEmpty()) return null
		val titleElement = selectFirst("div[data-flux-heading], h3, h4") ?: selectFirst("a[title]") ?: link
		val title = titleElement.attr("title").ifEmpty { titleElement.text() }.trim().nullIfEmpty() ?: return null
		val isAdult = selectFirst("span:containsOwn(18+)") != null
		return Manga(
			id = generateUid(url),
			title = title,
			altTitles = emptySet(),
			url = url,
			publicUrl = url.toAbsoluteUrl(domain),
			rating = RATING_UNKNOWN,
			contentRating = if (isAdult) ContentRating.ADULT else ContentRating.SAFE,
			coverUrl = selectFirst("img[alt]:not([alt='']), img")?.resolveImageUrl(),
			tags = emptySet(),
			state = null,
			authors = emptySet(),
			source = source,
		)
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val detailsUrl = manga.url.toAbsoluteUrl(domain)
		val detailsKey = "${source.name}\n$detailsUrl"
		getCachedMangaDetails(detailsKey)?.let { return it }
		return getMangaDetailsLock(detailsKey).withLock {
			getCachedMangaDetails(detailsKey) ?: loadMangaDocument(detailsUrl)?.let { document ->
				parseDetails(document, manga).copy(chapters = fetchChapters(document))
			}?.also { cacheMangaDetails(detailsKey, it) } ?: manga
		}
	}

	private suspend fun loadMangaDocument(url: String): Document? {
		getCachedMangaDocument(url)?.let { return it }
		return getMangaDocumentLock(url).withLock {
			getCachedMangaDocument(url) ?: loadDetailsDocument(url)?.also {
				cacheMangaDocument(url, it)
			}
		}
	}

	private suspend fun loadDetailsDocument(url: String): Document? {
		repeat(DETAILS_RETRIES) { attempt ->
			try {
				return webClient.httpGet(url).parseHtml()
			} catch (error: HttpStatusException) {
				if (error.statusCode !in 500..599) return null
				if (attempt == 0) resetServerSession()
			} catch (error: TooManyRequestExceptions) {
				if (attempt + 1 < DETAILS_RETRIES) {
					delay(maxOf(DETAILS_RETRY_DELAY_MILLIS, error.getRetryDelay()))
				}
				return@repeat
			}
			if (attempt + 1 < DETAILS_RETRIES) {
				delay(DETAILS_RETRY_DELAY_MILLIS * (attempt + 1))
			}
		}
		return null
	}

	private fun resetServerSession() = synchronized(serverSessionLock) {
		val now = System.currentTimeMillis()
		if (now - lastServerSessionResetAt < SERVER_SESSION_RESET_COOLDOWN_MILLIS) return@synchronized
		context.cookieJar.insertCookies(
			domain,
			"XSRF-TOKEN=; Max-Age=0; Path=/; Secure; SameSite=Lax",
			"onisaga_session=; Max-Age=0; Path=/; HttpOnly; Secure; SameSite=Lax",
		)
		synchronized(INITIAL_LIST_STATES) { INITIAL_LIST_STATES.clear() }
		synchronized(ACTIVE_LIST_STATES) { ACTIVE_LIST_STATES.clear() }
		synchronized(MANGA_LISTS) { MANGA_LISTS.clear() }
		synchronized(MANGA_DETAILS) { MANGA_DETAILS.clear() }
		synchronized(MANGA_DOCUMENTS) { MANGA_DOCUMENTS.clear() }
		synchronized(readerTokens) { readerTokens.clear() }
		synchronized(readerPageUrls) { readerPageUrls.clear() }
		synchronized(readerChapterStates) { readerChapterStates.clear() }
		lastServerSessionResetAt = now
	}

	private fun parseDetails(document: Document, fallback: Manga): Manga {
		val infoSection = document.selectFirst("div.flex.flex-col.md\\:flex-row")
		val title = (
			infoSection?.select("h1")
				?.asSequence()
				?.mapNotNull { it.text().trim().nullIfEmpty() }
				?.firstOrNull { !it.equals("Search", ignoreCase = true) }
				?: document.select("h1")
					.asSequence()
					.mapNotNull { it.text().trim().nullIfEmpty() }
					.firstOrNull { !it.equals("Search", ignoreCase = true) }
			)
			?: fallback.title
		val cover = document.selectFirst(".w-32 > picture img, div.flex.flex-col.md\\:flex-row picture img")
			?.resolveImageUrl()
			?: fallback.coverUrl
		val altTitles = document.selectFirst("p[class*=\"text-[13px]\"]")?.text()
			?.split(INTERPUNCT_REGEX)
			?.mapNotNull { it.trim().nullIfEmpty() }
			?.filterNotTo(linkedSetOf()) { it.equals(title, ignoreCase = true) }
			.orEmpty()
		val rating = document.selectFirst("span.text-xs")?.text()
			?.let { RATING_REGEX.find(it)?.groupValues?.get(1)?.toFloatOrNull() }
			?.takeIf { it > 0f }
			?.div(10f)
			?: fallback.rating
		val tags = infoSection?.select("a[href*=\"/genre/\"]")?.mapNotNullTo(linkedSetOf()) { element ->
			val name = element.text().trim().nullIfEmpty() ?: return@mapNotNullTo null
			val id = element.attr("href").substringBefore('?').trimEnd('/').substringAfterLast('/').nullIfEmpty()
				?: GENRES.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second
				?: return@mapNotNullTo null
			MangaTag(id, name, source)
		}.orEmpty()
		return fallback.copy(
			title = title,
			altTitles = altTitles,
			coverUrl = cover,
			largeCoverUrl = cover,
			rating = rating,
			contentRating = if (infoSection?.selectFirst("span:containsOwn(18+)") != null) {
				ContentRating.ADULT
			} else {
				ContentRating.SAFE
			},
			tags = tags,
			state = parseState(document),
			authors = infoSection?.select("a[href*=\"/author/\"]")
				?.mapNotNullTo(linkedSetOf()) { it.text().trim().nullIfEmpty() }
				.orEmpty(),
			description = document.selectFirst("p.leading-relaxed")?.text()?.nullIfEmpty(),
		)
	}

	private fun parseState(document: Document): MangaState? {
		val text = (
			document.selectFirst("span:has(> span.size-1\\.5)")?.text()
				?: document.selectFirst(
					"span.inline-flex:matchesOwn(Completed|Ongoing|Hiatus|Cancelled|Releasing)",
				)?.text()
			)?.lowercase(Locale.ROOT)
			?: return null
		return when {
			"ongoing" in text || "releasing" in text -> MangaState.ONGOING
			"completed" in text -> MangaState.FINISHED
			"hiatus" in text -> MangaState.PAUSED
			"cancelled" in text || "dropped" in text -> MangaState.ABANDONED
			else -> null
		}
	}

	private suspend fun fetchChapters(document: Document): List<MangaChapter> {
		val languages = document.parseChapterLanguages()
		// The picker lists every language this manga has, so an absent code means there is nothing to read.
		if (languages.isNotEmpty() && languageCode !in languages) return emptyList()
		val state = document.extractLivewireState(CHAPTER_LIST_COMPONENT)
		val activeLanguage = state?.activeChapterLanguage()
			?: languages.keys.firstOrNull()
			?: DEFAULT_CHAPTER_LANGUAGE
		val expected = languages[languageCode] ?: 0
		val pageChapters = parseChapters(document)
		if (state == null) {
			return if (languageCode == activeLanguage) sortChapters(pageChapters) else emptyList()
		}
		val start = if (languageCode == activeLanguage) {
			// The whole feed is already rendered into the details page: no request at all.
			if (expected in 1..pageChapters.size) return sortChapters(pageChapters)
			ChapterLoadResult(pageChapters, state, document.hasMoreChapters())
		} else {
			runCatchingCancellable {
				switchChapterLanguage(
					referer = document.location(),
					state = state,
					loadCalls = loadCallCount(expected, CHAPTERS_PER_LOAD),
				)
			}.getOrNull() ?: return emptyList()
		}
		val chapters = runCatchingCancellable {
			fetchChapterBatch(document.location(), start, expected)
		}.getOrElse { start }.chapters
		return sortChapters(chapters)
	}

	// Every response re-renders the whole feed, so asking past what the picker says exists just re-downloads
	// megabytes of identical html. chaptersLoaded counts pages of CHAPTERS_PER_LOAD.
	private fun loadCallCount(expected: Int, loaded: Int): Int {
		if (expected <= 0) return CHAPTER_LOAD_BATCH
		val remaining = expected - loaded
		if (remaining <= 0) return 0
		return ((remaining + CHAPTERS_PER_LOAD - 1) / CHAPTERS_PER_LOAD).coerceAtMost(CHAPTER_LOAD_BATCH)
	}

	private fun sortChapters(chapters: List<MangaChapter>): List<MangaChapter> = chapters
		.distinctBy(MangaChapter::url)
		.sortedBy(MangaChapter::number)

	private suspend fun switchChapterLanguage(
		referer: String,
		state: LivewireState,
		loadCalls: Int,
	): ChapterLoadResult? {
		val response = livewireRequestLock.withLock {
			webClient.httpPost(
				"https://$domain/livewire/update".toHttpUrl(),
				createLivewirePayload(
					state = state,
					calls = buildList(loadCalls + 1) {
						add(LivewireCall("setLanguage", JSONArray().put(languageCode)))
						repeat(loadCalls) { add(LivewireCall("loadMoreChapters", JSONArray())) }
					},
				),
				livewireHeaders(referer),
			).parseJson()
		}
		val component = response.firstComponent() ?: return null
		val nextState = component.getStringOrNull("snapshot")?.let { LivewireState(it, state.token) } ?: return null
		// An unsupported language resets the filter instead of switching to it.
		if (nextState.activeChapterLanguage() != languageCode) return null
		val html = component.optJSONObject("effects")?.getStringOrNull("html") ?: return null
		val chapterDocument = Jsoup.parseBodyFragment(html, "https://$domain")
		return ChapterLoadResult(
			chapters = parseChapters(chapterDocument),
			state = nextState,
			hasMore = chapterDocument.hasMoreChapters(),
		)
	}

	private suspend fun fetchChapterBatch(
		referer: String,
		start: ChapterLoadResult,
		expected: Int,
	): ChapterLoadResult {
		if (!start.hasMore) return start
		var state = start.state
		var chapters = start.chapters
		var previousSize = chapters.size
		repeat(MAX_CHAPTER_REQUESTS) {
			val calls = loadCallCount(expected, chapters.size)
			if (calls == 0) return ChapterLoadResult(chapters, state, false)
			val response = livewireRequestLock.withLock {
				webClient.httpPost(
					"https://$domain/livewire/update".toHttpUrl(),
					createLivewirePayload(
						state = state,
						calls = List(calls) { LivewireCall("loadMoreChapters", JSONArray()) },
					),
					livewireHeaders(referer),
				).parseJson()
			}
			val component = response.firstComponent() ?: return ChapterLoadResult(chapters, state, true)
			val html = component.optJSONObject("effects")?.getStringOrNull("html")
				?: return ChapterLoadResult(chapters, state, true)
			val chapterDocument = Jsoup.parseBodyFragment(html, "https://$domain")
			val nextState = component.getStringOrNull("snapshot")
				?.let { LivewireState(it, state.token) }
				?: return ChapterLoadResult(chapters, state, chapterDocument.hasMoreChapters())
			val parsed = parseChapters(chapterDocument)
			// The load button stays in the markup, a feed that stopped growing is the real end of the list.
			if (parsed.size <= previousSize) return ChapterLoadResult(chapters, nextState, false)
			chapters = parsed
			previousSize = parsed.size
			state = nextState
			if (!chapterDocument.hasMoreChapters()) return ChapterLoadResult(chapters, state, false)
		}
		return ChapterLoadResult(chapters, state, true)
	}

	private fun parseChapters(document: Document): List<MangaChapter> {
		val raw = ArrayList<RawChapter>()
		document.select("a.gap-4:has(div[data-flux-heading])").forEach { element ->
			val url = element.attrAsRelativeUrlOrNull("href")?.takeIf { "/read/" in it } ?: return@forEach
			raw.add(
				RawChapter(
					number = element.chapterNumber() ?: return@forEach,
					url = url,
					date = parseRelativeDate(element.chapterDateText()),
					group = null,
				),
			)
		}
		document.select("ui-dropdown:has(button div[data-flux-heading])").forEach { dropdown ->
			val button = dropdown.selectFirst("button") ?: return@forEach
			val number = button.chapterNumber() ?: return@forEach
			val date = parseRelativeDate(button.chapterDateText())
			dropdown.select("ui-menu a[data-flux-menu-item]").forEach { link ->
				val url = link.attrAsRelativeUrlOrNull("href")?.takeIf { "/read/" in it } ?: return@forEach
				val group = (
					link.selectFirst("span.text-sm")?.text()
						?: link.selectFirst("div.flex.items-center.gap-2 > span:not(.ml-auto)")?.text()
					).orEmpty()
					.trim()
					.takeUnless { it.isEmpty() || it.equals("Unknown group", ignoreCase = true) }
				raw.add(RawChapter(number, url, date, group))
			}
		}
		return raw.distinctBy(RawChapter::number).map { chapter ->
			MangaChapter(
				id = generateUid(chapter.url),
				title = null,
				number = chapter.number,
				volume = 0,
				url = chapter.url,
				scanlator = chapter.group,
				uploadDate = chapter.date,
				branch = null,
				source = source,
			)
		}
	}

	// Chapter rows carry no language marker, the picker is the only place the available languages are listed,
	// and its badge is that language's chapter count, which is exactly what parseChapters yields for it.
	private fun Document.parseChapterLanguages(): Map<String, Int> {
		val languages = LinkedHashMap<String, Int>()
		for (button in select("button")) {
			val action = button.attributes()
				.firstOrNull { it.key.endsWith("click") && "setLanguage" in it.value }
				?.value ?: continue
			val code = SET_LANGUAGE_REGEX.find(action)
				?.groupValues?.get(1)
				?.lowercase(Locale.ROOT)
				?.nullIfEmpty()
				?: continue
			languages[code] = button.selectFirst("[data-flux-badge]")
				?.text()
				?.filter(Char::isDigit)
				?.toIntOrNull()
				?: 0
		}
		return languages
	}

	private fun LivewireState.activeChapterLanguage(): String? = runCatchingCancellable {
		val data = JSONObject(snapshot).optJSONObject("data") ?: return@runCatchingCancellable null
		// setLanguage sticks to the session, so "language" survives onto manga that do not have it at all and
		// would mislabel their feed. "activeLanguage" is the language the returned chapters are really in.
		data.livewireString("activeLanguage") ?: data.livewireString("language")
	}.getOrNull()

	private fun JSONObject.livewireString(key: String): String? = when (val value = opt(key)) {
		is String -> value
		is JSONArray -> value.optString(0)
		else -> null
	}?.trim()?.lowercase(Locale.ROOT)?.nullIfEmpty()

	private fun Document.hasMoreChapters(): Boolean = select("button").any {
		it.text().contains("load more chapters", ignoreCase = true)
	} || select("*").any { element ->
		element.attributes().any { attribute ->
			attribute.key.endsWith("click") && attribute.value.contains("loadMoreChapters")
		}
	}

	private fun Element.chapterNumber(): Float? {
		val heading = selectFirst("div[data-flux-heading]")?.text()
		val fallback = selectFirst("div.w-10")?.text()
		val text = heading?.replaceFirst(Regex("""^Chapter\s+""", RegexOption.IGNORE_CASE), "")
			?.trim()
			?.nullIfEmpty()
			?: fallback
		return text?.toFloatOrNull() ?: text?.let {
			CHAPTER_NUMBER_REGEX.find(it)?.groupValues?.get(1)?.toFloatOrNull()
		}
	}

	private fun Element.chapterDateText(): String = selectFirst("p[data-flux-text]")?.text()
		?.replace(" - ", " · ")
		?.split(INTERPUNCT_REGEX)
		?.firstOrNull { part ->
			val text = part.lowercase(Locale.ROOT)
			"ago" in text || "today" in text || "yesterday" in text
		}
		.orEmpty()

	private fun parseRelativeDate(value: String): Long {
		val text = value.lowercase(Locale.ROOT)
		if (text.isEmpty()) return 0L
		val now = System.currentTimeMillis()
		if ("today" in text) return now
		if ("yesterday" in text) return now - DAY_MILLIS
		val match = RELATIVE_DATE_REGEX.find(text) ?: return 0L
		val amount = match.groupValues[1].toLongOrNull() ?: return 0L
		val multiplier = when (match.groupValues[2]) {
			"minute" -> MINUTE_MILLIS
			"hour" -> HOUR_MILLIS
			"day" -> DAY_MILLIS
			"week" -> WEEK_MILLIS
			"month" -> MONTH_MILLIS
			"year" -> YEAR_MILLIS
			else -> return 0L
		}
		return now - amount * multiplier
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val chapterUrl = chapter.url.toAbsoluteUrl(domain)
		val state = getCachedReaderState(chapterUrl) ?: getReaderChapterLock(chapterUrl).withLock {
			getCachedReaderState(chapterUrl) ?: loadReaderState(chapterUrl)
		}
		val orders = state.orders
		return orders.map { order ->
			val key = "$chapterUrl#$order"
			MangaPage(
				id = generateUid(key),
				url = key,
				preview = null,
				source = source,
			)
		}
	}

	override suspend fun getPageUrl(page: MangaPage): String = getReaderPageLock(page.url).withLock {
		getCachedPageUrl(page.url)?.let { return@withLock it }
		resolvePageUrl(page).also { cachePageUrl(page.url, it) }
	}

	private suspend fun resolvePageUrl(page: MangaPage): String {
		val chapterUrl = page.url.substringBeforeLast('#')
		val order = page.url.substringAfterLast('#').toIntOrNull()
			?: throw ParseException("Invalid reader page", page.url)
		val chapterId = chapterUrl.toHttpUrl().pathSegments.lastOrNull()?.nullIfEmpty()
			?: throw ParseException("Invalid chapter URL", chapterUrl)
		val apiUrl = "https://$domain/api/chapter/$chapterId/page/$order"
		repeat(READER_RETRIES) {
			var shouldRefreshToken = false
			val imageUrl = try {
				val token = getReaderToken(chapterUrl) ?: loadReaderToken(chapterUrl)
				awaitReaderSignRequestSlot()
				val response = webClient.httpGet(apiUrl, readerHeaders(token, chapterUrl))
				clearReaderSignBackoff()
				response.header("x-reader-token-next")?.nullIfEmpty()?.let {
					putReaderToken(chapterUrl, it)
				}
				val json = response.parseJson()
				json.getStringOrNull("url")?.toAbsoluteUrl(domain) ?: run {
					val message = json.getStringOrNull("message")
					if (message?.contains("token", ignoreCase = true) == true) {
						removeReaderToken(chapterUrl)
						shouldRefreshToken = true
						null
					} else {
						throw ParseException(message ?: "Reader API error", apiUrl)
					}
				}
			} catch (error: HttpStatusException) {
				when (error.statusCode) {
					401, 403 -> {
						removeReaderToken(chapterUrl)
						shouldRefreshToken = true
					}
					429 -> {
						synchronized(readerSignStateLock) {
							readerSignBackoffUntil = maxOf(
								readerSignBackoffUntil,
								System.currentTimeMillis() + READER_429_BACKOFF_MILLIS,
							).coerceAtMost(System.currentTimeMillis() + READER_MAX_BACKOFF_MILLIS)
						}
					}
					else -> throw error
				}
				null
			} catch (error: TooManyRequestExceptions) {
				registerReaderSignBackoff(error)
				null
			}
			if (imageUrl != null) {
				val encodedReferer = context.encodeBase64(chapterUrl.toByteArray(Charsets.UTF_8))
				return "${imageUrl.substringBefore('#')}#$IMAGE_REFERER_FRAGMENT$encodedReferer"
			}
			if (shouldRefreshToken) loadReaderToken(chapterUrl)
		}
		throw ParseException("Failed to fetch image after $READER_RETRIES attempts", apiUrl)
	}

	private suspend fun awaitReaderSignRequestSlot() {
		// Waited without the rate lock: every pending page shares one backoff instead of queueing up
		// behind it one after another, which is what turned a single 429 into a stalled reader.
		while (true) {
			val backoff = synchronized(readerSignStateLock) {
				readerSignBackoffUntil - System.currentTimeMillis()
			}
			if (backoff <= 0L) break
			delay(backoff.coerceAtMost(READER_MAX_BACKOFF_MILLIS))
		}
		readerSignRateLock.withLock {
			val spacing = synchronized(readerSignStateLock) {
				lastReaderSignRequestStartedAt + READER_SIGN_REQUEST_INTERVAL_MILLIS - System.currentTimeMillis()
			}
			if (spacing > 0L) delay(spacing)
			synchronized(readerSignStateLock) {
				lastReaderSignRequestStartedAt = System.currentTimeMillis()
			}
		}
	}

	// The backoff only ever moved forward and nothing ever cleared it, so it outlived the throttling.
	private fun clearReaderSignBackoff() = synchronized(readerSignStateLock) {
		readerSignBackoffUntil = 0L
	}

	private suspend fun loadReaderToken(chapterUrl: String): String {
		return getReaderChapterLock(chapterUrl).withLock {
			getReaderToken(chapterUrl) ?: loadReaderState(chapterUrl).token.nullIfEmpty()
				?: throw ParseException("Could not refresh reader token", chapterUrl)
		}
	}

	private suspend fun loadReaderState(chapterUrl: String): ReaderState {
		repeat(READER_RETRIES) { attempt ->
			try {
				val body = webClient.httpGet(chapterUrl).parseRaw()
				val token = extractReaderToken(body, chapterUrl)
				val orders = body.parsePageOrders()
				if (token != null && orders.isNotEmpty()) {
					putReaderToken(chapterUrl, token)
					return ReaderState(token, orders).also { cacheReaderState(chapterUrl, it) }
				}
				if (attempt + 1 < READER_RETRIES) {
					delay(READER_TOKEN_RETRY_MILLIS)
				}
			} catch (error: TooManyRequestExceptions) {
				if (attempt + 1 < READER_RETRIES) {
					delay(readerRetryDelay(error))
				}
			}
		}
		return ReaderState("", emptyList()).also {
			cacheReaderState(chapterUrl, it, READER_UNAVAILABLE_CACHE_TTL_MILLIS)
		}
	}

	private fun String.parsePageOrders(): List<Int> {
		val strict = PAGE_ORDER_REGEX.findAll(this)
			.mapNotNull { it.groupValues[1].toIntOrNull() }
			.distinct()
			.sorted()
			.toList()
		if (strict.isNotEmpty()) return strict
		return PAGE_ORDER_FALLBACK_REGEX.findAll(this)
			.mapNotNull { it.groupValues[1].toIntOrNull() }
			.distinct()
			.sorted()
			.toList()
	}

	private fun getReaderToken(chapterUrl: String): String? = synchronized(readerTokens) {
		readerTokens[chapterUrl]
	}

	private fun putReaderToken(chapterUrl: String, token: String) = synchronized(readerTokens) {
		readerTokens[chapterUrl] = token
	}

	private fun removeReaderToken(chapterUrl: String) = synchronized(readerTokens) {
		readerTokens.remove(chapterUrl)
	}

	private fun getReaderChapterLock(chapterUrl: String): Mutex = synchronized(readerChapterLocks) {
		readerChapterLocks.getOrPut(chapterUrl, ::Mutex)
	}

	private fun getCachedReaderState(chapterUrl: String): ReaderState? = synchronized(readerChapterStates) {
		val cached = readerChapterStates[chapterUrl] ?: return@synchronized null
		if (cached.expiresAt <= System.currentTimeMillis()) {
			readerChapterStates.remove(chapterUrl)
			null
		} else {
			cached.state
		}
	}

	private fun cacheReaderState(
		chapterUrl: String,
		state: ReaderState,
		ttlMillis: Long = READER_STATE_CACHE_TTL_MILLIS,
	) = synchronized(readerChapterStates) {
		readerChapterStates[chapterUrl] = CachedReaderState(
			state = state,
			expiresAt = System.currentTimeMillis() + ttlMillis,
		)
	}

	private fun registerReaderSignBackoff(error: TooManyRequestExceptions) {
		val retryDelay = readerRetryDelay(error)
		synchronized(readerSignStateLock) {
			readerSignBackoffUntil = maxOf(
				readerSignBackoffUntil,
				System.currentTimeMillis() + retryDelay,
			)
		}
	}

	private fun readerRetryDelay(error: TooManyRequestExceptions): Long =
		((error.getRetryDelay().takeIf { it > 0L } ?: READER_429_BACKOFF_MILLIS) + READER_RETRY_MARGIN_MILLIS)
			.coerceAtMost(READER_MAX_BACKOFF_MILLIS)

	private fun extractReaderToken(body: String, chapterUrl: String): String? {
		READER_TOKEN_REGEX.find(body)?.groupValues?.get(1)?.nullIfEmpty()?.let { return it }
		val chapterId = chapterUrl.toHttpUrlOrNull()?.pathSegments?.lastOrNull()?.nullIfEmpty() ?: return null
		return READER_TOKEN_CANDIDATE_REGEX.findAll(body).firstNotNullOfOrNull { match ->
			val decoded = runCatching {
				context.decodeBase64(match.value).toString(Charsets.UTF_8)
			}.getOrNull()
			match.value.takeIf { decoded?.startsWith("$chapterId|") == true }
		}
	}

	private fun getReaderPageLock(pageKey: String): Mutex = synchronized(readerPageLocks) {
		readerPageLocks.getOrPut(pageKey, ::Mutex)
	}

	private fun getCachedPageUrl(pageKey: String): String? = synchronized(readerPageUrls) {
		val cached = readerPageUrls[pageKey] ?: return@synchronized null
		if (cached.expiresAt <= System.currentTimeMillis()) {
			readerPageUrls.remove(pageKey)
			null
		} else {
			cached.url
		}
	}

	private fun cachePageUrl(pageKey: String, url: String) = synchronized(readerPageUrls) {
		val now = System.currentTimeMillis()
		val signedExpiry = url.substringBefore('#').toHttpUrlOrNull()
			?.queryParameter("exp")
			?.toLongOrNull()
			?.times(1_000L)
			?.minus(READER_PAGE_EXPIRY_MARGIN_MILLIS)
		val expiresAt = signedExpiry?.takeIf { it > now } ?: (now + READER_PAGE_FALLBACK_TTL_MILLIS)
		readerPageUrls[pageKey] = CachedPageUrl(url, expiresAt)
	}

	private fun readerHeaders(token: String, referer: String): Headers = getRequestHeaders().newBuilder()
		.set("X-Reader-Token", token)
		.set("Sec-Fetch-Mode", "cors")
		.set("Sec-Fetch-Site", "same-origin")
		.set("Referer", referer)
		.build()

	override suspend fun getRelatedManga(seed: Manga): List<Manga> {
		val document = loadMangaDocument(seed.url.toAbsoluteUrl(domain)) ?: return emptyList()
		val heading = document.select("div[data-flux-heading], h3, h2").firstOrNull {
			val text = it.text().lowercase(Locale.ROOT)
			"recommended" in text || "related" in text || "you may also like" in text
		} ?: return emptyList()
		val section = heading.parents().firstOrNull {
			it.select("div.relative.group, a[href*='/manga/']").size > 1
		} ?: return emptyList()
		return section.select("div.relative.group, a[href*='/manga/']:has(img)")
			.mapNotNull { it.toManga() }
			.filterNot { it.url == seed.url }
			.distinctBy(Manga::url)
	}

	override suspend fun resolveLink(resolver: LinkResolver, link: HttpUrl): Manga? {
		if (link.host != domain) return null
		val segments = link.pathSegments.filter(String::isNotEmpty)
		val mangaUrl = when (segments.firstOrNull()) {
			"manga" -> "https://$domain/manga/${segments.getOrNull(1) ?: return null}"
			"read" -> webClient.httpGet(link).parseHtml()
				.selectFirst("a[href*=\"/manga/\"]")?.absUrl("href")?.nullIfEmpty()
				?: return null
			else -> return null
		}
		val document = loadMangaDocument(mangaUrl) ?: return null
		val slug = mangaUrl.toHttpUrl().pathSegments.last()
		val stub = Manga(
			id = generateUid("/manga/$slug"),
			title = slug.replace('-', ' ').replaceFirstChar { it.titlecase(sourceLocale) },
			altTitles = emptySet(),
			url = "/manga/$slug",
			publicUrl = mangaUrl,
			rating = RATING_UNKNOWN,
			contentRating = null,
			coverUrl = null,
			tags = emptySet(),
			state = null,
			authors = emptySet(),
			source = source,
		)
		return parseDetails(document, stub)
	}

	private fun Element.resolveImageUrl(): String? {
		for (attribute in IMAGE_ATTRIBUTES) {
			val value = attr(attribute)
			if (value.isNotEmpty() && !value.startsWith("data:")) {
				return absUrl(attribute).nullIfEmpty() ?: value.toAbsoluteUrl(domain)
			}
		}
		return null
	}

	private fun Document.extractLivewireState(componentName: String): LivewireState? {
		val token = selectFirst("meta[name=csrf-token]")?.attr("content")?.nullIfEmpty()
			?: selectFirst("input[name=_token]")?.attr("value")?.nullIfEmpty()
			?: return null
		for (element in select("*")) {
			val snapshot = element.attributes().firstOrNull { it.key.endsWith("snapshot") }?.value ?: continue
			if (componentName in snapshot) return LivewireState(snapshot, token)
		}
		return null
	}

	private fun livewireHeaders(referer: String): Headers = getRequestHeaders().newBuilder()
		.set("X-Livewire", "")
		.set("Accept", "application/json")
		.set("X-Requested-With", "XMLHttpRequest")
		.set("Origin", "https://$domain")
		.set("Referer", referer.substringBefore('?'))
		.build()

	private fun createLivewirePayload(
		state: LivewireState,
		updates: JSONObject = JSONObject(),
		calls: List<LivewireCall>,
	): JSONObject = JSONObject()
		.put("_token", state.token)
		.put(
			"components",
			JSONArray().put(
				JSONObject()
					.put("snapshot", state.snapshot)
					.put("updates", updates)
					.put("calls", JSONArray().apply {
						for (call in calls) {
							put(
								JSONObject()
									.put("type", "call")
									.put("path", "")
									.put("method", call.method)
									.put("params", call.params),
							)
						}
					}),
			),
		)

	private fun getCachedState(
		cache: MutableMap<String, CachedLivewireState>,
		key: String,
	): LivewireState? = synchronized(cache) {
		val cached = cache[key] ?: return@synchronized null
		if (System.currentTimeMillis() - cached.createdAt > LIST_STATE_CACHE_TTL) {
			cache.remove(key)
			null
		} else {
			cached.state
		}
	}

	private fun cacheState(
		cache: MutableMap<String, CachedLivewireState>,
		key: String,
		state: LivewireState,
	) = synchronized(cache) {
		cache[key] = CachedLivewireState(state, System.currentTimeMillis())
	}

	private fun getMangaListLock(key: String): Mutex = synchronized(MANGA_LIST_LOCKS) {
		MANGA_LIST_LOCKS.getOrPut(key, ::Mutex)
	}

	private fun getCachedMangaList(key: String): List<Manga>? = synchronized(MANGA_LISTS) {
		val cached = MANGA_LISTS[key] ?: return@synchronized null
		if (System.currentTimeMillis() - cached.createdAt > MANGA_LIST_CACHE_TTL) {
			MANGA_LISTS.remove(key)
			null
		} else {
			cached.manga
		}
	}

	private fun cacheMangaList(key: String, manga: List<Manga>) = synchronized(MANGA_LISTS) {
		MANGA_LISTS[key] = CachedMangaList(manga, System.currentTimeMillis())
	}

	private fun getMangaDetailsLock(key: String): Mutex = synchronized(MANGA_DETAILS_LOCKS) {
		MANGA_DETAILS_LOCKS.getOrPut(key, ::Mutex)
	}

	private fun getCachedMangaDetails(key: String): Manga? = synchronized(MANGA_DETAILS) {
		val cached = MANGA_DETAILS[key] ?: return@synchronized null
		if (System.currentTimeMillis() - cached.createdAt > MANGA_DETAILS_CACHE_TTL) {
			MANGA_DETAILS.remove(key)
			null
		} else {
			cached.manga
		}
	}

	private fun cacheMangaDetails(key: String, manga: Manga) = synchronized(MANGA_DETAILS) {
		MANGA_DETAILS[key] = CachedMangaDetails(manga, System.currentTimeMillis())
	}

	private fun getMangaDocumentLock(url: String): Mutex = synchronized(MANGA_DOCUMENT_LOCKS) {
		MANGA_DOCUMENT_LOCKS.getOrPut(url, ::Mutex)
	}

	private fun getCachedMangaDocument(url: String): Document? = synchronized(MANGA_DOCUMENTS) {
		val cached = MANGA_DOCUMENTS[url] ?: return@synchronized null
		if (System.currentTimeMillis() - cached.createdAt > MANGA_DOCUMENT_CACHE_TTL) {
			MANGA_DOCUMENTS.remove(url)
			null
		} else {
			cached.document
		}
	}

	private fun cacheMangaDocument(url: String, document: Document) = synchronized(MANGA_DOCUMENTS) {
		MANGA_DOCUMENTS[url] = CachedMangaDocument(document, System.currentTimeMillis())
	}

	private fun JSONObject.firstComponent(): JSONObject? = optJSONArray("components")?.optJSONObject(0)

	private fun MangaListFilter.toLivewireUpdates(order: SortOrder): LivewireUpdates {
		val selectedYear = year.takeUnless { it == YEAR_UNKNOWN }
		return LivewireUpdates(
			platform = types.oneOrThrowIfMany()?.toPlatform().orEmpty(),
			status = states.oneOrThrowIfMany()?.toStatus().orEmpty(),
			sort = order.toLivewireSort(),
			releaseStart = selectedYear?.let { "$it-01-01" },
			releaseEnd = selectedYear?.let { "$it-12-31" },
			genres = tags.map(MangaTag::key),
			excludedGenres = tagsExclude.map(MangaTag::key),
		)
	}

	private fun SortOrder.toLivewireSort(): String = when (this) {
		SortOrder.POPULARITY -> "view"
		SortOrder.RATING -> "vote_average"
		SortOrder.NEWEST -> "release_date"
		SortOrder.ALPHABETICAL -> "title"
		else -> "created_at"
	}

	private fun ContentType.toPlatform(): String? = when (this) {
		ContentType.MANGA -> "MANGA"
		ContentType.MANHWA -> "MANHWA"
		ContentType.MANHUA -> "MANHUA"
		ContentType.NOVEL -> "NOVEL"
		ContentType.ONE_SHOT -> "ONE-SHOT"
		ContentType.DOUJINSHI -> "DOUJINSHI"
		else -> null
	}

	private fun MangaState.toStatus(): String? = when (this) {
		MangaState.ONGOING -> "ongoing"
		MangaState.FINISHED -> "completed"
		MangaState.PAUSED -> "hiatus"
		MangaState.ABANDONED -> "cancelled"
		else -> null
	}

	private data class LivewireState(val snapshot: String, val token: String)
	private data class LivewirePage(val document: Document, val state: LivewireState)
	private data class CachedLivewireState(val state: LivewireState, val createdAt: Long)
	private data class CachedMangaList(val manga: List<Manga>, val createdAt: Long)
	private data class CachedMangaDetails(val manga: Manga, val createdAt: Long)
	private data class CachedMangaDocument(val document: Document, val createdAt: Long)
	private data class CachedPageUrl(val url: String, val expiresAt: Long)
	private data class ReaderState(val token: String, val orders: List<Int>)
	private data class CachedReaderState(val state: ReaderState, val expiresAt: Long)
	private data class LivewireCall(val method: String, val params: JSONArray)
	private data class ChapterLoadResult(
		val chapters: List<MangaChapter>,
		val state: LivewireState,
		val hasMore: Boolean,
	)
	private data class RawChapter(val number: Float, val url: String, val date: Long, val group: String?)

	private data class LivewireUpdates(
		val platform: String = "",
		val status: String = "",
		val sort: String = "created_at",
		val minimumChapters: String = "",
		val group: String? = null,
		val releaseStart: String? = null,
		val releaseEnd: String? = null,
		val genres: List<String> = emptyList(),
		val excludedGenres: List<String> = emptyList(),
	) {
		fun cacheKey(): String = listOf(
			platform,
			status,
			sort,
			minimumChapters,
			group.orEmpty(),
			releaseStart.orEmpty(),
			releaseEnd.orEmpty(),
			genres.sorted().joinToString(","),
			excludedGenres.sorted().joinToString(","),
		).joinToString("|")

		fun isDefault(): Boolean = platform.isEmpty() &&
			status.isEmpty() &&
			sort == "created_at" &&
			minimumChapters.isEmpty() &&
			group == null &&
			releaseStart == null &&
			releaseEnd == null &&
			genres.isEmpty() &&
			excludedGenres.isEmpty()

		fun toTopMangaUrl(domain: String, page: Int): String? {
			if (
				platform.isNotEmpty() || status.isNotEmpty() || minimumChapters.isNotEmpty() || group != null ||
				releaseStart != null || releaseEnd != null || genres.isNotEmpty() || excludedGenres.isNotEmpty()
			) {
				return null
			}
			val topSort = when (sort) {
				"view" -> null
				"vote_average" -> "rated"
				else -> return null
			}
			return "https://$domain/top-manga".toHttpUrl().newBuilder().apply {
				if (page > 1) addQueryParameter("page", page.toString())
				if (topSort != null) addQueryParameter("sort", topSort)
			}.build().toString()
		}

		fun toJson(): JSONObject = JSONObject()
			.put("platform", platform)
			.put("status", status)
			.put("sort", sort)
			.put("min_chapters", minimumChapters)
			.put("group", group ?: JSONObject.NULL)
			.put("release_start", releaseStart ?: JSONObject.NULL)
			.put("release_end", releaseEnd ?: JSONObject.NULL)
			.put("genre", JSONArray(genres))
			.put("excludeGenre", JSONArray(excludedGenres))
	}

	// languageCode is the site's own filter value, as used by setLanguage('..').
	@MangaSourceParser("ONISAGA_EN", "OniSaga (English)", "en")
	class English(context: MangaLoaderContext) : OniSagaParser(context, MangaParserSource.ONISAGA_EN, "en")

	@MangaSourceParser("ONISAGA_FR", "OniSaga (Français)", "fr")
	class French(context: MangaLoaderContext) : OniSagaParser(context, MangaParserSource.ONISAGA_FR, "fr")

	@MangaSourceParser("ONISAGA_JA", "OniSaga (日本語)", "ja")
	class Japanese(context: MangaLoaderContext) : OniSagaParser(context, MangaParserSource.ONISAGA_JA, "ja")

	@MangaSourceParser("ONISAGA_PT_BR", "OniSaga (Português Brasileiro)", "pt")
	class BrazilianPortuguese(context: MangaLoaderContext) :
		OniSagaParser(context, MangaParserSource.ONISAGA_PT_BR, "pt-br")

	@MangaSourceParser("ONISAGA_PT", "OniSaga (Português)", "pt")
	class Portuguese(context: MangaLoaderContext) : OniSagaParser(context, MangaParserSource.ONISAGA_PT, "pt")

	@MangaSourceParser("ONISAGA_ES_419", "OniSaga (Español Latinoamérica)", "es")
	class LatinAmericanSpanish(context: MangaLoaderContext) :
		OniSagaParser(context, MangaParserSource.ONISAGA_ES_419, "es-la")

	@MangaSourceParser("ONISAGA_ES", "OniSaga (Español)", "es")
	class Spanish(context: MangaLoaderContext) : OniSagaParser(context, MangaParserSource.ONISAGA_ES, "es")

	private companion object {
		const val PAGE_SIZE = 24
		const val CHAPTER_LOAD_BATCH = 50
		const val CHAPTERS_PER_LOAD = 100
		const val MAX_CHAPTER_REQUESTS = 4
		const val DETAILS_RETRIES = 3
		const val DETAILS_RETRY_DELAY_MILLIS = 300L
		const val SERVER_SESSION_RESET_COOLDOWN_MILLIS = 5_000L
		const val READER_RETRIES = 4
		// 300 requests is the site-wide budget and /livewire/update spends from it too, so leave headroom.
		const val READER_SIGN_REQUEST_INTERVAL_MILLIS = 300L
		// The endpoint answers Retry-After: 3 when it throttles, a flat 10s just burned every retry.
		const val READER_429_BACKOFF_MILLIS = 4_000L
		const val READER_MAX_BACKOFF_MILLIS = 15_000L
		const val READER_TOKEN_RETRY_MILLIS = 250L
		const val READER_RETRY_MARGIN_MILLIS = 250L
		const val READER_TOKEN_CACHE_SIZE = 16
		const val READER_PAGE_CACHE_SIZE = 512
		// Only used when the signed url carries no usable exp: re-sign soon rather than serve a dead url.
		const val READER_PAGE_FALLBACK_TTL_MILLIS = 60_000L
		const val READER_PAGE_EXPIRY_MARGIN_MILLIS = 30_000L
		const val READER_STATE_CACHE_TTL_MILLIS = 10 * 60_000L
		const val READER_UNAVAILABLE_CACHE_TTL_MILLIS = 30_000L
		const val LIST_STATE_CACHE_SIZE = 12
		const val LIST_STATE_CACHE_TTL = 15 * 60_000L
		const val MANGA_LIST_CACHE_SIZE = 32
		const val MANGA_LIST_CACHE_TTL = 60_000L
		const val MANGA_DETAILS_CACHE_SIZE = 32
		const val MANGA_DETAILS_CACHE_TTL = 60_000L
		const val MANGA_DOCUMENT_CACHE_TTL = 60_000L
		const val IMAGE_REFERER_FRAGMENT = "onisaga-ref:"
		const val POST_FILTER_COMPONENT = "post-filter"
		const val CHAPTER_LIST_COMPONENT = "manga.chapter-list"
		const val DEFAULT_CHAPTER_LANGUAGE = "en"
		const val MINUTE_MILLIS = 60_000L
		const val HOUR_MILLIS = 3_600_000L
		const val DAY_MILLIS = 86_400_000L
		const val WEEK_MILLIS = 604_800_000L
		const val MONTH_MILLIS = 2_592_000_000L
		const val YEAR_MILLIS = 31_536_000_000L

		val readerSignRateLock = Mutex()
		val livewireRequestLock = Mutex()
		val readerSignStateLock = Any()
		val serverSessionLock = Any()

		@Volatile
		var lastServerSessionResetAt = 0L

		@Volatile
		var lastReaderSignRequestStartedAt = 0L

		@Volatile
		var readerSignBackoffUntil = 0L

		val readerTokens = object : LinkedHashMap<String, String>(READER_TOKEN_CACHE_SIZE, 0.75f, true) {
			override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
				size > READER_TOKEN_CACHE_SIZE
		}

		val readerPageUrls =
			object : LinkedHashMap<String, CachedPageUrl>(READER_PAGE_CACHE_SIZE, 0.75f, true) {
				override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedPageUrl>?): Boolean =
					size > READER_PAGE_CACHE_SIZE
			}

		val readerPageLocks = object : LinkedHashMap<String, Mutex>(READER_PAGE_CACHE_SIZE, 0.75f, true) {
			override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Mutex>?): Boolean =
				size > READER_PAGE_CACHE_SIZE
		}

		val readerChapterStates =
			object : LinkedHashMap<String, CachedReaderState>(READER_TOKEN_CACHE_SIZE, 0.75f, true) {
				override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedReaderState>?): Boolean =
					size > READER_TOKEN_CACHE_SIZE
			}

		val readerChapterLocks = object : LinkedHashMap<String, Mutex>(READER_TOKEN_CACHE_SIZE, 0.75f, true) {
			override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Mutex>?): Boolean =
				size > READER_TOKEN_CACHE_SIZE
		}

		val IMAGE_ATTRIBUTES = arrayOf("data-src", "data-lazy-src", "src")
		val INITIAL_LIST_STATES =
			object : LinkedHashMap<String, CachedLivewireState>(LIST_STATE_CACHE_SIZE, 0.75f, true) {
				override fun removeEldestEntry(
					eldest: MutableMap.MutableEntry<String, CachedLivewireState>?,
				): Boolean = size > LIST_STATE_CACHE_SIZE
			}
		val ACTIVE_LIST_STATES =
			object : LinkedHashMap<String, CachedLivewireState>(LIST_STATE_CACHE_SIZE, 0.75f, true) {
				override fun removeEldestEntry(
					eldest: MutableMap.MutableEntry<String, CachedLivewireState>?,
				): Boolean = size > LIST_STATE_CACHE_SIZE
			}
		val MANGA_LISTS = object : LinkedHashMap<String, CachedMangaList>(MANGA_LIST_CACHE_SIZE, 0.75f, true) {
			override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedMangaList>?): Boolean =
				size > MANGA_LIST_CACHE_SIZE
		}
		val MANGA_LIST_LOCKS = object : LinkedHashMap<String, Mutex>(MANGA_LIST_CACHE_SIZE, 0.75f, true) {
			override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Mutex>?): Boolean =
				size > MANGA_LIST_CACHE_SIZE
		}
		val MANGA_DETAILS = object :
			LinkedHashMap<String, CachedMangaDetails>(MANGA_DETAILS_CACHE_SIZE, 0.75f, true) {
			override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedMangaDetails>?): Boolean =
				size > MANGA_DETAILS_CACHE_SIZE
		}
		val MANGA_DETAILS_LOCKS = object : LinkedHashMap<String, Mutex>(MANGA_DETAILS_CACHE_SIZE, 0.75f, true) {
			override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Mutex>?): Boolean =
				size > MANGA_DETAILS_CACHE_SIZE
		}
		val MANGA_DOCUMENTS = object :
			LinkedHashMap<String, CachedMangaDocument>(MANGA_DETAILS_CACHE_SIZE, 0.75f, true) {
			override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedMangaDocument>?): Boolean =
				size > MANGA_DETAILS_CACHE_SIZE
		}
		val MANGA_DOCUMENT_LOCKS = object : LinkedHashMap<String, Mutex>(MANGA_DETAILS_CACHE_SIZE, 0.75f, true) {
			override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Mutex>?): Boolean =
				size > MANGA_DETAILS_CACHE_SIZE
		}
		val READER_TOKEN_REGEX = Regex("""readerToken["']?\s*:\s*["']([^"']+)["']""")
		val READER_TOKEN_CANDIDATE_REGEX = Regex("""[A-Za-z0-9+/]{80,}={0,2}""")
		// The reader page carries a JSON array of page descriptors. A bare `order:` also matches CSS and
		// other json, inventing page numbers that can never be signed and never finish loading.
		val PAGE_ORDER_REGEX = Regex("""["]order["]\s*:\s*(\d+)\s*,\s*["]is_spread["]""")
		val PAGE_ORDER_FALLBACK_REGEX = Regex("""["]order["]\s*:\s*(\d+)""")
		val CHAPTER_NUMBER_REGEX = Regex("""(?:Chapter\s+)?([\d.]+)""", RegexOption.IGNORE_CASE)
		val RELATIVE_DATE_REGEX = Regex("""(\d+)\s+(minute|hour|day|week|month|year)s?\s+ago""")
		val RATING_REGEX = Regex("""(\d+(?:\.\d+)?)""")
		val RANKED_TITLE_REGEX = Regex(
			"""^\d+\s+(.+?)\s+(?:Ongoing|Completed|Hiatus|Releasing|Cancelled)\b""",
			RegexOption.IGNORE_CASE,
		)
		val SET_LANGUAGE_REGEX = Regex("""setLanguage\(\s*['"]([^'"]+)['"]""")
		val INTERPUNCT_REGEX = Regex("""\s*·\s*""")

		val GENRES = """
			Action:1|Adaptation:61|Adult:67|Adventure:6|Aliens:84|Avant Garde:43|Award Winning:78|
			Boys Love:31|Comedy:2|Comics:90|Crazy MC:59|Crime:98|Demon:57|Demons:5|Doujinshi:79|
			Drama:15|Dungeons:56|Ecchi:29|Erotica:68|Fantasy:7|Full Color:62|Game:46|Gender Bender:75|
			Genderswap:63|Genius MC:49|Girls Love:28|Gore:80|Gourmet:42|Harem:37|Hentai:76|
			Historical:66|Horror:16|Isekai:3|Iyashikei:34|Josei:35|Kids:38|Lolicon:70|Long Strip:64|
			Magic:8|Magical Girls:99|Mahou Shoujo:41|Martial Arts:11|Mature:45|Mecha:36|Medical:101|
			Military:17|Monster Girls:88|Monsters:81|Murim:47|Music:30|Mystery:19|Necromancer:54|
			Overpowered:55|Parody:12|Philosophical:100|Post-Apocalyptic:85|Psychological:18|
			Regression:52|Reincarnation:48|Revenge:51|Reverse Harem:44|Romance:20|Samurai:86|
			School:21|School Life:24|Sci-Fi:13|Seinen:14|Self-Published:82|Shotacon:77|Shoujo:27|
			Shoujo Ai:73|Shounen:4|Shounen Ai:72|Slice of Life:26|Smut:69|Space:22|Sports:32|
			Super Power:9|Superhero:89|Supernatural:10|Survival:87|Suspense:39|System:50|Thriller:40|
			Time Travel:23|Tower:58|Tragedy:25|Vampire:33|Villain:53|Violence:60|Web Comic:65|
			Wuxia:113|Yaoi:74|Yuri:71
		""".trimIndent()
			.replace("\n", "")
			.split('|')
			.map { value -> value.substringBefore(':').trim() to value.substringAfter(':').trim() }
	}
}
