package org.koitharu.kotatsu.parsers.site.id

import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.AuthRequiredException
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import org.koitharu.kotatsu.parsers.webview.InterceptionConfig
import java.io.IOException
import java.net.URLDecoder
import java.net.UnknownHostException
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@MangaSourceParser("SOFTKOMIK", "Softkomik", "id")
internal class Softkomik(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.SOFTKOMIK, pageSize = 24) {

	override val configKeyDomain = ConfigKey.Domain("softkomik.app", "softkomik.online")

	private val crypto = CryptoAES(context)
	private val sessionCache = ConcurrentHashMap<String, Session>()
	private val tagsCache = ConcurrentHashMap<String, Set<MangaTag>>()

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.POPULARITY,
		SortOrder.NEWEST,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
		)

	override fun getRequestHeaders(): Headers = Headers.Builder()
		.add("User-Agent", normalizeUserAgent(config[userAgentKey]) ?: config[userAgentKey])
		.add("Referer", "https://$domain/")
		.build()

	override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions(
		availableTags = fetchAvailableTags(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
		availableContentTypes = EnumSet.of(
			ContentType.MANGA,
			ContentType.MANHUA,
			ContentType.MANHWA,
		),
	)

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		tagsCache["all"]?.let { return it }
		val tags = runCatchingCancellable {
			val doc = webClient.httpGet(urlBuilder().addPathSegments("komik/library").build()).parseHtml()
			val pageProps = extractPageProps(doc) ?: return@runCatchingCancellable emptySet()
			val arr = pageProps.optJSONArray("genre") ?: return@runCatchingCancellable emptySet()
			arr.mapJSONNotNullToSet { jo ->
				val value = jo.getStringOrNull("value") ?: jo.getStringOrNull("label")
					?: return@mapJSONNotNullToSet null
				val label = jo.getStringOrNull("label") ?: value
				MangaTag(title = label.toTitleCase(), key = value, source = source)
			}
		}.getOrDefault(emptySet())
		if (tags.isNotEmpty()) {
			tagsCache["all"] = tags
		}
		return tags
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		if (!filter.query.isNullOrEmpty()) {
			if (page > 1) return emptyList()
			return searchByQuery(filter.query)
		}

		val url = urlBuilder().apply {
			addPathSegments("komik/library")
			addQueryParameter("page", page.toString())
			addQueryParameter(
				"sortBy",
				when (order) {
					SortOrder.POPULARITY -> "popular"
					else -> "newKomik"
				},
			)
			filter.states.oneOrThrowIfMany()?.let { state ->
				val statusParam = when (state) {
					MangaState.ONGOING -> "ongoing"
					MangaState.FINISHED -> "tamat"
					else -> null
				}
				if (statusParam != null) {
					addQueryParameter("status", statusParam)
				}
			}
			filter.types.oneOrThrowIfMany()?.let { type ->
				val typeParam = when (type) {
					ContentType.MANGA -> "manga"
					ContentType.MANHUA -> "manhua"
					ContentType.MANHWA -> "manhwa"
					else -> null
				}
				if (typeParam != null) {
					addQueryParameter("type", typeParam)
				}
			}
			filter.tags.oneOrThrowIfMany()?.let { tag ->
				addQueryParameter("genre", tag.key)
			}
		}.build()

		val doc = webClient.httpGet(url).parseHtml()
		val pageProps = extractPageProps(doc) ?: return emptyList()
		val libData = pageProps.optJSONObject("libData") ?: pageProps
		val maxPage = libData.getIntOrDefault("maxPage", page)
		if (page > maxPage) return emptyList()

		val dataArray = libData.optJSONArray("data") ?: return emptyList()
		return parseListData(dataArray)
	}

	private suspend fun searchByQuery(query: String): List<Manga> {
		val url = urlBuilder().apply {
			addPathSegments("api/content/search")
			addQueryParameter("name", query.trim().take(100))
		}.build()

		val headers = Headers.Builder()
			.add("Accept", "application/json")
			.add("X-Requested-With", "XMLHttpRequest")
			.add("Referer", "https://$domain/")
			.build()

		val json = webClient.httpGet(url, headers).parseJson()
		val dataArray = json.optJSONArray("data") ?: return emptyList()
		return parseListData(dataArray)
	}

	private fun parseListData(dataArray: JSONArray): List<Manga> = dataArray.mapJSONNotNull { jo ->
		val slug = jo.getStringOrNull("title_slug") ?: jo.getStringOrNull("id") ?: return@mapJSONNotNull null
		val relativeUrl = "/$slug"
		Manga(
			id = generateUid(relativeUrl),
			title = jo.getStringOrNull("title")?.trim() ?: return@mapJSONNotNull null,
			altTitles = emptySet(),
			url = relativeUrl,
			publicUrl = relativeUrl.toAbsoluteUrl(domain),
			rating = RATING_UNKNOWN,
			contentRating = ContentRating.SAFE,
			coverUrl = jo.getStringOrNull("gambar")?.removePrefix("/")?.let { "$COVER_URL/$it" },
			tags = emptySet(),
			state = parseStatus(jo.getStringOrNull("status")),
			authors = emptySet(),
			source = source,
		)
	}

	override suspend fun resolveLink(resolver: LinkResolver, link: HttpUrl): Manga? {
		val slug = link.pathSegments.firstOrNull()?.takeIf { it.isNotBlank() && it !in SITE_PAGES } ?: return null
		val relativeUrl = "/$slug"
		val stub = Manga(
			id = generateUid(relativeUrl),
			title = "",
			altTitles = emptySet(),
			url = relativeUrl,
			publicUrl = relativeUrl.toAbsoluteUrl(domain),
			rating = RATING_UNKNOWN,
			contentRating = ContentRating.SAFE,
			coverUrl = null,
			tags = emptySet(),
			state = null,
			authors = emptySet(),
			source = source,
		)
		return getDetails(stub)
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val pageProps = extractPageProps(doc)
			?: throw ParseException("Could not find page data", manga.publicUrl)
		val detail = pageProps.optJSONObject("data")
			?: throw ParseException("Could not find manga data", manga.publicUrl)

		val title = detail.getStringOrNull("title")?.trim() ?: manga.title
		val coverUrl = detail.getStringOrNull("gambar")?.removePrefix("/")?.let { "$COVER_URL/$it" } ?: manga.coverUrl
		val description = detail.getStringOrNull("sinopsis")
		val author = detail.getStringOrNull("author")?.trim()
		val state = parseStatus(detail.getStringOrNull("status"))

		val tags = LinkedHashSet<MangaTag>()
		val cachedTagsByTitle = tagsCache["all"]?.associateBy { it.title.lowercase() }
		detail.optJSONArray("Genre")?.let { arr ->
			for (i in 0 until arr.length()) {
				val name = arr.optString(i, "").trim()
				if (name.isNotEmpty()) {
					val tag = cachedTagsByTitle?.get(name.lowercase())
						?: MangaTag(title = name.toTitleCase(), key = name, source = source)
					tags.add(tag)
				}
			}
		}

		val slug = manga.url.trim('/').substringBefore('/')
		val chapters = fetchChapterList(slug)

		return manga.copy(
			title = title,
			coverUrl = coverUrl,
			description = description,
			tags = tags,
			authors = setOfNotNull(author),
			state = state,
			chapters = chapters,
		)
	}

	private suspend fun fetchChapterList(slug: String): List<MangaChapter> {
		val url = "$API_URL/komik/$slug/chapter?limit=9999999"
		val json = executeApiJsonGet(
			url = url,
			sessionEndpoint = SESSION_LIST_ENDPOINT,
			webViewUrl = "https://$domain/$slug",
			referer = "https://$domain/$slug",
		)
		val chapterArray = json.optJSONArray("chapter") ?: return emptyList()

		val seenIds = HashSet<Long>(chapterArray.length())
		val seenNumbers = HashSet<Float>(chapterArray.length())
		val chapters = ArrayList<MangaChapter>(chapterArray.length())
		for (i in 0 until chapterArray.length()) {
			val ch = chapterArray.optJSONObject(i) ?: continue
			val chStr = ch.getStringOrNull("chapter") ?: continue
			val chapterUrl = "/$slug/chapter/$chStr"
			val id = generateUid(chapterUrl)
			if (!seenIds.add(id)) continue
			val number = parseChapterNumber(chStr)
			if (number > 0f && !seenNumbers.add(number)) continue
			chapters.add(
				MangaChapter(
					id = id,
					title = "Chapter ${formatChapterDisplay(chStr)}",
					url = chapterUrl,
					number = number,
					volume = 0,
					scanlator = null,
					uploadDate = 0L,
					branch = null,
					source = source,
				),
			)
		}
		chapters.sortBy { it.number }
		return chapters
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val chapterPageUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(chapterPageUrl).parseHtml()
		val pageProps = extractPageProps(doc)
			?: throw ParseException("Could not find chapter page data", chapterPageUrl)

		val chapterData = pageProps.optJSONObject("data")?.optJSONObject("data")
		val slug = chapter.url.trim('/').substringBefore("/chapter/")
		val chNum = chapter.url.substringAfterLast("/chapter/").trim('/')

		var imageSrc = pageProps.optJSONArray("imgList")?.takeIf { it.length() > 0 }
			?: chapterData?.optJSONArray("imageSrc")?.takeIf { it.length() > 0 }

		if (imageSrc == null || imageSrc.length() == 0) {
			val id = chapterData?.getStringOrNull("_id")
				?: throw ParseException("Could not find chapter ID", chapterPageUrl)
			imageSrc = fetchChapterImages(slug, chNum, id, chapterPageUrl)
		}

		if (imageSrc.length() == 0) {
			if (getBearerTokenFromCookie() == null) {
				throw AuthRequiredException(source)
			}
			throw ParseException("Chapter is empty or requires login in WebView", chapterPageUrl)
		}

		val imageBaseUrl = when {
			chapterData?.getBooleanOrDefault("backBS3", false) == true -> PRIMARY_CDN_URL
			chapterData?.getBooleanOrDefault("storageInter2", false) == true -> SECONDARY_CDN_URL
			else -> PRIMARY_CDN_URL
		}

		val pages = ArrayList<MangaPage>(imageSrc.length())
		for (i in 0 until imageSrc.length()) {
			val path = imageSrc.optString(i, "").removePrefix("/")
			if (path.isEmpty()) continue
			pages.add(
				MangaPage(
					id = generateUid(path),
					url = "$imageBaseUrl/$path?id=$IMAGE_ID_PARAM",
					preview = null,
					source = source,
				),
			)
		}
		return pages
	}

	private suspend fun fetchChapterImages(
		slug: String,
		chapter: String,
		id: String,
		referer: String,
	): JSONArray {
		val url = "$API_URL/komik/$slug/chapter/$chapter/imgs/$id"
		val authToken = getBearerTokenFromCookie()
		val extraHeaders = if (authToken != null) {
			mapOf("Authorization" to authToken)
		} else {
			emptyMap()
		}
		val json = executeApiJsonGet(
			url = url,
			sessionEndpoint = SESSION_IMAGE_ENDPOINT,
			webViewUrl = referer,
			referer = referer,
			extraHeaders = extraHeaders,
		)
		return json.optJSONArray("imageSrc") ?: JSONArray()
	}

	private suspend fun executeApiJsonGet(
		url: String,
		sessionEndpoint: String,
		webViewUrl: String,
		referer: String,
		extraHeaders: Map<String, String> = emptyMap(),
	): JSONObject {
		val session = getSession(sessionEndpoint, webViewUrl)
		val headers = buildApiHeaders(session, referer, extraHeaders)
		return runCatchingCancellable {
			webClient.httpGet(url, headers).parseJson()
		}.getOrElse { e ->
			sessionCache.remove(sessionEndpoint)
			val refreshed = getSessionViaWebView(sessionEndpoint, webViewUrl) ?: throw e
			webClient.httpGet(url, buildApiHeaders(refreshed, referer, extraHeaders)).parseJson()
		}
	}

	private fun buildApiHeaders(
		session: Session,
		referer: String,
		extraHeaders: Map<String, String>,
	): Headers = Headers.Builder().apply {
		add("X-Token", session.token.cleanB64())
		add("X-Sign", session.sign.take(64))
		if (session.contentToken != null && session.contentSign != null) {
			add("X-Content-Token", session.contentToken)
			add("X-Content-Sign", session.contentSign)
		}
		add("Accept", "application/json")
		add("Referer", referer)
		add("Origin", "https://$domain")
		extraHeaders.forEach { (k, v) -> set(k, v) }
	}.build()

	private suspend fun getSession(endpoint: String, webViewUrl: String): Session {
		sessionCache[endpoint]?.takeIf { it.ex > System.currentTimeMillis() + 30_000L }?.let { return it }

		val apiHeaders = Headers.Builder()
			.add("Accept", "application/json")
			.add("Content-Type", "application/json")
			.add("X-Requested-With", "XMLHttpRequest")
			.add("Referer", "https://$domain/")
			.add("Origin", "https://$domain")
			.build()

		val baseHttpUrl = "https://$domain/".toHttpUrl()
		val hasCookies = context.cookieJar.loadForRequest(baseHttpUrl)
			.any { it.name == "AhyyL" || it.name == "zEm9be" || it.name == "zEm983" }

		if (!hasCookies) {
			runCatchingCancellable { webClient.httpGet("https://$domain/").close() }
			runCatchingCancellable { webClient.httpGet("https://$domain/api/me", apiHeaders).close() }
		}

		val directSession = runCatchingCancellable {
			val res = webClient.httpGet("https://$domain$endpoint", apiHeaders).parseJson()
			val contentAccess = res.optJSONObject("contentAccess")
			Session(
				ex = res.getLongOrDefault("ex", System.currentTimeMillis() + TimeUnit.HOURS.toMillis(2)),
				token = res.getStringOrNull("token").orEmpty(),
				sign = res.getStringOrNull("sign").orEmpty(),
				contentToken = contentAccess?.getStringOrNull("token"),
				contentSign = contentAccess?.getStringOrNull("sign"),
			)
		}.getOrNull()

		if (directSession != null && directSession.token.isNotEmpty() && directSession.sign.isNotEmpty()) {
			sessionCache[endpoint] = directSession
			return directSession
		}

		return getSessionViaWebView(endpoint, webViewUrl)
			?: throw ParseException("Failed to obtain Softkomik session", "https://$domain$endpoint")
	}

	private suspend fun getSessionViaWebView(endpoint: String, webViewUrl: String): Session? {
		val captured = runCatchingCancellable {
			context.interceptWebViewRequests(
				url = webViewUrl,
				config = InterceptionConfig(
					timeoutMs = 15_000L,
					maxRequests = 5,
					urlPattern = Regex("""${Regex.escape(API_URL)}.*"""),
				),
			)
		}.getOrNull() ?: return null

		for (req in captured) {
			val token = req.headers.entries.firstOrNull { it.key.equals("X-Token", ignoreCase = true) }?.value
			val sign = req.headers.entries.firstOrNull { it.key.equals("X-Sign", ignoreCase = true) }?.value
			if (!token.isNullOrEmpty() && !sign.isNullOrEmpty()) {
				val contentToken = req.headers.entries
					.firstOrNull { it.key.equals("X-Content-Token", ignoreCase = true) }?.value
				val contentSign = req.headers.entries
					.firstOrNull { it.key.equals("X-Content-Sign", ignoreCase = true) }?.value
				val session = Session(
					ex = System.currentTimeMillis() + TimeUnit.HOURS.toMillis(2),
					token = token,
					sign = sign,
					contentToken = contentToken,
					contentSign = contentSign,
				)
				sessionCache[endpoint] = session
				return session
			}
		}
		return null
	}

	private fun getBearerTokenFromCookie(): String? {
		val cookies = context.cookieJar.loadForRequest("https://$domain/".toHttpUrl())
		val cookieToken = cookies.firstOrNull { it.name == "tokkey" } ?: return null
		if (cookieToken.expiresAt <= System.currentTimeMillis()) return null
		return runCatching {
			URLDecoder.decode(cookieToken.value, Charsets.UTF_8.name())
		}.getOrDefault(cookieToken.value)
	}

	override fun intercept(chain: Interceptor.Chain): Response {
		val originalRequest = chain.request()
		val userAgent = originalRequest.header("User-Agent")
		val normalizedUserAgent = normalizeUserAgent(userAgent)

		val request = if (normalizedUserAgent != userAgent && normalizedUserAgent != null) {
			originalRequest.newBuilder()
				.header("User-Agent", normalizedUserAgent)
				.build()
		} else {
			originalRequest
		}

		val response = try {
			chain.proceed(request)
		} catch (_: UnknownHostException) {
			null
		}

		if (response?.isSuccessful == true && !response.isTrapImage()) {
			return response
		}

		val requestUrlStr = request.url.toString()
		val currentHost = CDN_URLS.firstOrNull { requestUrlStr.startsWith(it) }
		if (currentHost == null) {
			return response ?: throw UnknownHostException(request.url.host)
		}

		response?.close()

		val imagePath = requestUrlStr.removePrefix(currentHost).removePrefix("/")
		val otherHosts = CDN_URLS.filter { it != currentHost }

		var latestResponse: Response? = null
		for (newHost in otherHosts) {
			latestResponse?.close()
			val newUrl = "$newHost/$imagePath".toHttpUrl()
			latestResponse = try {
				chain.proceed(request.newBuilder().url(newUrl).build())
			} catch (_: UnknownHostException) {
				null
			}
			if (latestResponse?.isSuccessful == true && !latestResponse.isTrapImage()) {
				return latestResponse
			}
		}

		if (latestResponse?.isTrapImage() == true) {
			latestResponse.close()
			throw IOException("Image blocked by site.")
		}

		return latestResponse ?: throw UnknownHostException("All CDN hosts failed for: $imagePath")
	}

	private fun Response.isTrapImage(): Boolean = request.url.encodedPath.endsWith(TRAP_IMAGE_PATH)

	private fun extractPageProps(doc: Document): JSONObject? {
		val raw = doc.selectFirst("script#__NEXT_DATA__")?.data() ?: return null
		val pageProps = runCatching {
			JSONObject(raw).optJSONObject("props")?.optJSONObject("pageProps")
		}.getOrNull() ?: return null

		val stk = pageProps.getStringOrNull("_stk") ?: return pageProps
		return runCatching {
			JSONObject(crypto.decrypt(stk, PAGE_PROPS_KEY))
		}.getOrNull()
	}

	private fun normalizeUserAgent(userAgent: String?): String? {
		if (userAgent.isNullOrBlank()) return null
		return userAgent
			.replace(USER_AGENT_MOBILE_SAFARI_REGEX, "")
			.trim()
			.ifEmpty { null }
	}

	private fun parseStatus(text: String?): MangaState? = when {
		text == null -> null
		text.contains("ongoing", ignoreCase = true) -> MangaState.ONGOING
		text.contains("tamat", ignoreCase = true) ||
			text.contains("completed", ignoreCase = true) -> MangaState.FINISHED
		else -> null
	}

	private fun parseChapterNumber(chStr: String): Float {
		chStr.toFloatOrNull()?.let { return it }
		val parts = chStr.split(".")
		val major = parts.getOrNull(0)?.toIntOrNull()
		val minor = parts.getOrNull(1)?.takeWhile { it.isDigit() }
		if (major != null && !minor.isNullOrEmpty()) {
			"$major.$minor".toFloatOrNull()?.let { return it }
		}
		return chStr.substringBefore('.').toFloatOrNull() ?: 0f
	}

	private fun formatChapterDisplay(chStr: String): String {
		val parts = chStr.split(".")
		val numPart = parts[0].toFloatOrNull() ?: return chStr
		val formatted = if (numPart == numPart.toLong().toFloat()) {
			numPart.toLong().toString()
		} else {
			numPart.toString().trimEnd('0').trimEnd('.')
		}
		val suffix = parts.drop(1).joinToString(".")
		return if (suffix.isNotEmpty()) "$formatted.$suffix" else formatted
	}

	private fun String.cleanB64(): String = substringBefore('=').let {
		it + "=".repeat((4 - (it.length % 4)) % 4)
	}

	private class Session(
		val ex: Long,
		val token: String,
		val sign: String,
		val contentToken: String? = null,
		val contentSign: String? = null,
	)

	private companion object {
		const val API_URL = "https://v2.softkomik.org"
		const val COVER_URL = "https://cover.softdevices.my.id/softkomik-cover"
		const val PRIMARY_CDN_URL = "https://psy1.komik.im"
		const val SECONDARY_CDN_URL = "https://image.komik.im/softkomik"
		val CDN_URLS = listOf(PRIMARY_CDN_URL, SECONDARY_CDN_URL)

		const val SESSION_LIST_ENDPOINT = "/api/session/aksjkas"
		const val SESSION_IMAGE_ENDPOINT = "/api/session/chapter/oaisos"

		const val PAGE_PROPS_KEY = "nYTInq9MwR4pWa8yeXtu/2iO3t9nGP65WHpWEvqpEdU="
		const val IMAGE_ID_PARAM = "T4Kmwztku"
		const val TRAP_IMAGE_PATH = "/baca-image.jpeg"

		val SITE_PAGES = setOf("akun", "img", "komik", "u")
		val USER_AGENT_MOBILE_SAFARI_REGEX = Regex("""\s*Mobile Safari/\d+(?:\.\d+)*""", RegexOption.IGNORE_CASE)
	}
}
