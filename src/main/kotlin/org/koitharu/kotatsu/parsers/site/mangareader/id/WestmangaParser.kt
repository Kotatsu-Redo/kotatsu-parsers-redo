package org.koitharu.kotatsu.parsers.site.mangareader.id

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.HttpStatusException
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import java.util.*

@MangaSourceParser("WESTMANGA", "WestManga", "id")
internal class WestmangaParser(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.WESTMANGA, pageSize = 20) {

	// The API moved from data.mantweh.online onto the site's own domain.
	override val configKeyDomain = ConfigKey.Domain("v1.westmanga.my", "v1.westmanga.top")

	private var genresCache: Set<MangaTag>? = null
	private val mutex = Mutex()

	private val visitTokenMutex = Mutex()
	private var visitToken: String? = null
	private var visitTokenExpiresAt = 0L

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
		// Every title needs a WebView to open, which a background check of a whole library shouldn't run
		keys.add(ConfigKey.DisableUpdateChecking(defaultValue = true))
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.ALPHABETICAL,
		SortOrder.NEWEST,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isMultipleTagsSupported = true,
			isSearchSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchGenres(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED, MangaState.PAUSED),
		availableContentTypes = EnumSet.of(
			ContentType.MANGA,
			ContentType.MANHWA,
			ContentType.MANHUA,
		),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = "https://$domain/api/contents".toHttpUrl().newBuilder().apply {
			if (!filter.query.isNullOrEmpty()) {
				addQueryParameter("q", filter.query)
			}
			addQueryParameter("page", page.toString())
			addQueryParameter("per_page", "20")
			addQueryParameter("type", "Comic")

			addQueryParameter(
				"orderBy",
				when (order) {
					SortOrder.POPULARITY -> "Popular"
					SortOrder.ALPHABETICAL -> "Az"
					SortOrder.NEWEST -> "Added"
					else -> "Update"
				},
			)

			filter.tags.forEach { tag ->
				addQueryParameter("genre[]", tag.key)
			}

			filter.states.oneOrThrowIfMany()?.let {
				addQueryParameter("status", when (it) {
					MangaState.ONGOING -> "Ongoing"
					MangaState.FINISHED -> "Completed"
					MangaState.PAUSED -> "Hiatus"
					else -> return@let
				})
			}

			filter.types.oneOrThrowIfMany()?.let {
				addQueryParameter("country", when (it) {
					ContentType.MANGA -> "JP"
					ContentType.MANHWA -> "KR"
					ContentType.MANHUA -> "CN"
					else -> return@let
				})
			}
		}.build()

		val response = browserApiGet(url)
		val mangaArray = response.optJSONArray("data") ?: return emptyList()

		return mangaArray.mapJSON { jo ->
			val slug = jo.getString("slug")
			Manga(
				id = generateUid(slug),
				title = jo.getString("title"),
				altTitles = emptySet(),
				url = "/manga/$slug/",
				publicUrl = "https://$domain/comic/$slug",
				rating = RATING_UNKNOWN,
				contentRating = null,
				coverUrl = jo.getStringOrNull("cover")?.toAbsoluteUrl(domain),
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				source = source,
			)
		}
	}

	// A shared link has the site's /comic/<slug>, where a saved title keeps /manga/<slug>/
	override suspend fun resolveLink(resolver: LinkResolver, link: HttpUrl): Manga? {
		if (link.pathSegments.firstOrNull() != "comic") return null
		val slug = link.pathSegments.getOrNull(1)?.nullIfEmpty() ?: return null
		return resolver.resolveManga(this, url = "/manga/$slug/", id = generateUid(slug))
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val slug = manga.url.removeSurrounding("/manga/", "/")
		val url = "https://$domain/api/comic/$slug".toHttpUrl()

		val response = browserApiGet(url)
		val data = response.getJSONObject("data")

		val tags = buildSet {
			when (data.getStringOrNull("country_id") ?: data.getStringOrNull("country")) {
				"JP" -> add(MangaTag("Manga", "manga", source))
				"CN" -> add(MangaTag("Manhua", "manhua", source))
				"KR" -> add(MangaTag("Manhwa", "manhwa", source))
			}
			if (data.optBoolean("color", false)) {
				add(MangaTag("Colored", "colored", source))
			}
			data.optJSONArray("genres")?.let { genres ->
				for (i in 0 until genres.length()) {
					val genre = genres.getJSONObject(i)
					add(MangaTag(
						title = genre.getString("name").toTitleCase(Locale.ROOT),
						key = genre.getInt("id").toString(),
						source = source,
					))
				}
			}
		}

		val description = buildString {
			data.getStringOrNull("sinopsis")?.let { synopsis ->
				append(org.jsoup.Jsoup.parseBodyFragment(synopsis).wholeText().trim())
			}
			data.getStringOrNull("alternative_name")?.let { alt ->
				append("\n\nAlternative Name: ")
				append(alt.trim())
			}
		}

		val state = when (data.getStringOrNull("status")?.lowercase(Locale.ROOT)) {
			"ongoing" -> MangaState.ONGOING
			"completed" -> MangaState.FINISHED
			"hiatus" -> MangaState.PAUSED
			else -> null
		}

		val chapters = data.optJSONArray("chapters")?.mapJSON { chapterObj ->
			val chapterSlug = chapterObj.getString("slug")
			val updatedAt = chapterObj.optJSONObject("updated_at")?.getLongOrDefault("time", 0L)
				?: chapterObj.getLongOrDefault("updated_at", 0L)
			MangaChapter(
				id = generateUid(chapterSlug),
				url = "/$chapterSlug/",
				title = "Chapter ${chapterObj.getString("number")}",
				number = chapterObj.getString("number").toFloatOrNull() ?: 0f,
				volume = 0,
				branch = null,
				uploadDate = updatedAt * 1000,
				scanlator = null,
				source = source,
			)
		} ?: emptyList()

		return manga.copy(
			title = data.getString("title"),
			coverUrl = data.getStringOrNull("cover")?.toAbsoluteUrl(domain) ?: manga.coverUrl,
			tags = tags,
			authors = setOfNotNull(data.getStringOrNull("author")),
			description = description.takeIf { it.isNotEmpty() },
			state = state,
			chapters = chapters.reversed(),
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val slug = chapter.url.removeSurrounding("/", "/")
		val url = "https://$domain/api/v/$slug".toHttpUrl()

		val response = browserApiGet(url)
		val images = response.optJSONObject("data")?.getJSONArray("images")
			?: response.getJSONArray("images")

		val pages = ArrayList<MangaPage>(images.length())
		for (i in 0 until images.length()) {
			val imageUrl = images.getString(i).toAbsoluteUrl(domain)
			pages.add(
				MangaPage(
					id = generateUid(imageUrl),
					url = imageUrl,
					preview = null,
					source = source,
				)
			)
		}
		return pages
	}

	private suspend fun fetchGenres(): Set<MangaTag> = mutex.withLock {
		genresCache?.let { return@withLock it }

		val url = "https://$domain/api/contents/genres".toHttpUrl()
		val response = apiGet(url)
		val genresArray = response.optJSONArray("data") ?: return@withLock emptySet()

		val genres = genresArray.mapJSONToSet { genreObj ->
			MangaTag(
				title = genreObj.getString("name").toTitleCase(Locale.ROOT),
				key = genreObj.getInt("id").toString(),
				source = source,
			)
		}

		genresCache = genres
		return@withLock genres
	}

	/**
	 * Cloudflare blocks the content endpoints for anything but a browser, so these go through a WebView,
	 * from a small page of the site to avoid loading the whole app and its ads for every request.
	 */
	private suspend fun browserApiGet(url: HttpUrl): JSONObject = try {
		fetchInBrowser(url, getVisitToken())
	} catch (e: HttpStatusException) {
		if (e.statusCode != 403 && e.statusCode != 404) throw e
		fetchInBrowser(url, getVisitToken(forceRefresh = true))
	}

	private suspend fun fetchInBrowser(url: HttpUrl, token: String?): JSONObject {
		val target = url.toString()
		val script = BROWSER_FETCH_SCRIPT
			.replace("%HOST%", JSONObject.quote(url.host))
			.replace("%URL%", JSONObject.quote(target))
			.replace("%TOKEN%", JSONObject.quote(token.orEmpty()))
		val raw = context.evaluateJs("https://$domain/robots.txt", script, BROWSER_TIMEOUT_MS)
			?: throw ParseException("The site did not answer in time", target)
		// A string from a WebView arrives JSON-quoted
		val result = JSONObject(JSONTokener(raw).nextValue() as? String ?: raw)
		val status = result.getInt("status")
		if (status != 200) {
			throw HttpStatusException("HTTP $status", status, target)
		}
		return JSONObject(result.getString("body"))
	}

	private suspend fun apiGet(url: HttpUrl): JSONObject = try {
		webClient.httpGet(url, createApiHeaders(getVisitToken())).parseJson()
	} catch (e: HttpStatusException) {
		if (e.statusCode != 403 && e.statusCode != 404) throw e
		webClient.httpGet(url, createApiHeaders(getVisitToken(forceRefresh = true))).parseJson()
	}

	private suspend fun getVisitToken(forceRefresh: Boolean = false): String? = visitTokenMutex.withLock {
		val now = System.currentTimeMillis()
		if (!forceRefresh && visitToken != null && now < visitTokenExpiresAt - VISIT_TOKEN_MARGIN_MS) {
			return@withLock visitToken
		}
		val response = runCatchingCancellable {
			webClient.httpGet("https://$domain/api/visit-token".toHttpUrl(), createApiHeaders(null)).parseJson()
		}.getOrNull()
		val token = response?.takeIf { it.optBoolean("status") }?.getStringOrNull("token")
		if (token != null) {
			visitToken = token
			visitTokenExpiresAt = response.optLong("expires_at").takeIf { it > 0L }?.times(1000L)
				?: (now + VISIT_TOKEN_DEFAULT_TTL_MS)
		} else if (forceRefresh) {
			visitToken = null
			visitTokenExpiresAt = 0L
		}
		return@withLock visitToken
	}

	private fun createApiHeaders(token: String?): Headers = Headers.Builder()
		.add("User-Agent", config[userAgentKey])
		.add("Referer", "https://$domain/")
		.add("Accept", "application/json")
		.apply { token?.let { add("X-WM-Visit", it) } }
		.build()

	private companion object {

		const val VISIT_TOKEN_MARGIN_MS = 5 * 60 * 1000L
		const val VISIT_TOKEN_DEFAULT_TTL_MS = 60 * 60 * 1000L
		const val BROWSER_TIMEOUT_MS = 30_000L

		// Runs when the page has loaded, and repeatedly after that until it returns something. A synchronous
		// request answers on that first run, where an asynchronous one would wait for the next round of polling.
		// The host check skips the blank page the WebView shows before the site has loaded.
		const val BROWSER_FETCH_SCRIPT = """
			(() => {
				if (location.hostname !== %HOST%) return null;
				if (window.__wmResult) return window.__wmResult;
				const request = new XMLHttpRequest();
				let result;
				try {
					request.open('GET', %URL%, false);
					request.setRequestHeader('Accept', 'application/json');
					const token = %TOKEN%;
					if (token) request.setRequestHeader('X-WM-Visit', token);
					request.send();
					result = { status: request.status, body: request.responseText };
				} catch (e) {
					result = { status: 0, body: String(e) };
				}
				window.__wmResult = JSON.stringify(result);
				return window.__wmResult;
			})()
		"""
	}
}
