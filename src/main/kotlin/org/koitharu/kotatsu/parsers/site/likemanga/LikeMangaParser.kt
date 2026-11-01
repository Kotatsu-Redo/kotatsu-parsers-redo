package org.koitharu.kotatsu.parsers.site.likemanga

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.Headers
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.network.UserAgents
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.mapJSON
import org.koitharu.kotatsu.parsers.util.json.mapJSONNotNull
import java.text.SimpleDateFormat
import java.util.*

/**
 * LikeManga moved to mgread.io and now runs on the Init Manga WordPress theme, with other urls than the old site:
 * `/manga/<slug>/` and `/manga/<slug>/chapter-<n>/`, instead of `/<slug>-<id>/` and `/<slug>-<id>/chapter-<n>-<id>/`.
 */
internal abstract class LikeMangaParser(
	context: MangaLoaderContext,
	source: MangaParserSource,
	domain: String,
	pageSize: Int = 24,
	searchPageSize: Int = 10,
) : PagedMangaParser(context, source, pageSize, searchPageSize) {

	override val configKeyDomain = ConfigKey.Domain(domain)
	override val userAgentKey = ConfigKey.UserAgent(UserAgents.CHROME_MOBILE)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
		keys.add(ConfigKey.InterceptCloudflare(defaultValue = true))
	}

	override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
		.set("Referer", "https://$domain/")
		.build()

	override val availableSortOrders: Set<SortOrder> =
		EnumSet.of(
			SortOrder.UPDATED,
			SortOrder.POPULARITY,
			SortOrder.NEWEST,
			SortOrder.NEWEST_ASC,
			SortOrder.RATING,
			SortOrder.POPULARITY_TODAY,
			SortOrder.POPULARITY_WEEK,
			SortOrder.POPULARITY_MONTH,
		)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isMultipleTagsSupported = true,
			isSearchSupported = true,
		)

	// The filter also offers other states, but no title is in any of them
	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchAvailableTags(),
		availableStates = EnumSet.of(MangaState.ONGOING),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		if (!filter.query.isNullOrEmpty()) {
			return search(page, filter.query)
		}
		val url = buildString {
			append("https://")
			append(domain)
			append("/advanced-filter/")
			if (page > 1) {
				append("page/")
				append(page)
				append('/')
			}
			append("?sort=")
			append(
				when (order) {
					SortOrder.NEWEST -> "new"
					SortOrder.NEWEST_ASC -> "old"
					SortOrder.POPULARITY -> "views"
					SortOrder.POPULARITY_TODAY -> "views_day"
					SortOrder.POPULARITY_WEEK -> "views_week"
					SortOrder.POPULARITY_MONTH -> "views_month"
					SortOrder.RATING -> "rating"
					else -> "updated"
				},
			)
			filter.tags.forEach { tag ->
				append('&')
				append("genre[]".urlEncoded())
				append('=')
				append(tag.key)
			}
			if (MangaState.ONGOING in filter.states) {
				append("&status=ongoing")
			}
		}
		val doc = webClient.httpGet(url, getRequestHeaders()).parseHtml()
		return doc.select("div.manga-item-details").map { card ->
			val link = card.selectFirstOrThrow("h2 a")
			newManga(
				href = link.attrAsRelativeUrl("href"),
				title = link.text(),
				coverUrl = card.selectFirst("img")?.src(),
				isAdult = card.selectFirst(".badge-age")?.text() == ADULT_BADGE,
			)
		}.withoutAnime()
	}

	private suspend fun search(page: Int, query: String): List<Manga> {
		val url = "https://$domain/wp-json/initlise/v1/search?term=${query.urlEncoded()}&page=$page"
		return webClient.httpGet(url, getRequestHeaders()).parseJsonArray()
			.mapJSONNotNull { json ->
				// The endpoint also finds other post types, such as stickers
				if (json.getString("post_type") != "manga") {
					return@mapJSONNotNull null
				}
				newManga(
					href = json.getString("url").toRelativeUrl(domain),
					title = Jsoup.parseBodyFragment(json.getString("title")).text(),
					coverUrl = json.optString("thumb").nullIfEmpty(),
					isAdult = false,
				)
			}.withoutAnime()
	}

	private fun newManga(href: String, title: String, coverUrl: String?, isAdult: Boolean) = Manga(
		id = generateUid(href),
		title = title,
		altTitles = emptySet(),
		url = href,
		publicUrl = href.toAbsoluteUrl(domain),
		rating = RATING_UNKNOWN,
		contentRating = if (isAdult) ContentRating.ADULT else null,
		coverUrl = coverUrl,
		tags = emptySet(),
		state = null,
		authors = emptySet(),
		source = source,
	)

	// Anime episodes are posted among the series
	private fun List<Manga>.withoutAnime() = filterNot { it.url.startsWith("/manga/anime-") }

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/advanced-filter/", getRequestHeaders()).parseHtml()
		return doc.select("input[name='genre[]'][data-genre-name][value]:not([value=anime])").mapToSet { input ->
			MangaTag(
				key = input.attr("value"),
				title = input.attr("data-genre-name"),
				source = source,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val url = manga.url.toCurrentMangaUrl()
		val fullUrl = url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(fullUrl, getRequestHeaders()).parseHtml()
		val mangaId = POST_ID_REGEX.find(doc.body().className())?.groupValues?.get(1)
			?: throw ParseException("Cannot find the manga id", fullUrl)
		val isAdult = doc.selectFirst("div.story-cover-wrap:has(a.story-cover) .badge-age")?.text() == ADULT_BADGE
		return manga.copy(
			url = url,
			publicUrl = fullUrl,
			title = doc.selectFirst("h1#manga-title")?.textOrNull() ?: manga.title,
			altTitles = doc.selectFirst("#comic-othername")?.text()?.split(';')
				?.mapNotNullTo(LinkedHashSet()) { it.trim().nullIfEmpty() }
				.orEmpty(),
			coverUrl = doc.selectFirst("meta[property=og:image]")?.attr("content")?.nullIfEmpty() ?: manga.coverUrl,
			tags = doc.select("#genre-tags a:not(.uk-disabled)").mapToSet { a -> a.parseTag() },
			state = when (doc.selectFirst("#manga-status")?.text()?.lowercase(Locale.ROOT)) {
				"ongoing", "caught up" -> MangaState.ONGOING
				"completed" -> MangaState.FINISHED
				"season end", "source hiatus" -> MangaState.PAUSED
				"dropped" -> MangaState.ABANDONED
				else -> null
			},
			contentRating = if (isAdult) ContentRating.ADULT else manga.contentRating,
			description = doc.selectFirst("#manga-description")?.html(),
			chapters = loadChapters(mangaId, url),
		)
	}

	private fun Element.parseTag() = MangaTag(
		key = attr("href").removeSuffix('/').substringAfterLast('/'),
		title = text().toTitleCase(),
		source = source,
	)

	private suspend fun loadChapters(mangaId: String, mangaUrl: String): List<MangaChapter> {
		val firstPage = loadChaptersPage(mangaId, 1)
		// The biggest titles have dozens of pages
		val otherPages = (2..firstPage.getInt("total_pages")).chunked(CHAPTER_PAGES_AT_ONCE).flatMap { pages ->
			coroutineScope {
				pages.map { page -> async { loadChaptersPage(mangaId, page) } }.awaitAll()
			}
		}
		val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).apply {
			timeZone = TimeZone.getTimeZone("GMT+07:00")
		}
		return (listOf(firstPage) + otherPages)
			.flatMap { page -> page.getJSONArray("items").mapJSON { it } }
			// These take coins and an account to read
			.filter { it.getString("lock_type") == "none" || it.getBoolean("is_purchased") }
			.map { json ->
				val url = "$mangaUrl${json.getString("slug").trim('/')}/"
				MangaChapter(
					id = generateUid(url),
					title = json.optString("title").trim().nullIfEmpty(),
					number = json.getDouble("number").toFloat(),
					volume = 0,
					url = url,
					scanlator = null,
					uploadDate = dateFormat.parseSafe(json.optString("created_at")),
					branch = null,
					source = source,
				)
			}
			.reversed()
	}

	private suspend fun loadChaptersPage(mangaId: String, page: Int): JSONObject = webClient.httpGet(
		"https://$domain/wp-json/initmanga/v1/chapters?manga_id=$mangaId&per_page=50&paged=$page",
		getRequestHeaders(),
	).parseJson()

	/**
	 * Chapters can be served encrypted, and only the site's own reader opens them. Those load in a WebView,
	 * where that reader runs as usual, and the images are read from the page once it has decrypted them.
	 */
	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val url = chapter.url.toCurrentChapterUrl().toAbsoluteUrl(domain)
		val doc = webClient.httpGet(url, getRequestHeaders()).parseHtml()
		val images = doc.select("div#chapter-content > img").map { it.requireSrc() }
			.ifEmpty { loadImagesInReader(url) }
		return images.map { imageUrl ->
			MangaPage(
				id = generateUid(imageUrl),
				url = imageUrl,
				preview = null,
				source = source,
			)
		}
	}

	private suspend fun loadImagesInReader(url: String): List<String> {
		val raw = context.evaluateJs(url, READER_IMAGES_SCRIPT, READER_TIMEOUT_MS)
			?: throw ParseException("The chapter's images did not load in time", url)
		// A string from a WebView arrives JSON-quoted
		val images = JSONArray(JSONTokener(raw).nextValue() as? String ?: raw)
		return List(images.length()) { images.getString(it) }
	}

	// Entries saved from the old site: the slug is what still leads to the title
	private fun String.toCurrentMangaUrl(): String =
		LEGACY_MANGA_URL_REGEX.matchEntire(this)?.let { "/manga/${it.groupValues[1]}/" } ?: this

	private fun String.toCurrentChapterUrl(): String =
		LEGACY_CHAPTER_URL_REGEX.matchEntire(this)?.let { "/manga/${it.groupValues[1]}/${it.groupValues[2]}/" } ?: this

	private companion object {

		const val ADULT_BADGE = "18+"
		const val CHAPTER_PAGES_AT_ONCE = 5
		const val READER_TIMEOUT_MS = 30_000L

		// Runs repeatedly until it returns something: empty while the reader hasn't decrypted the chapter.
		// Images further down the page keep their url in an attribute until they are scrolled to,
		// and a canvas holds its url base64-encoded and reversed.
		const val READER_IMAGES_SCRIPT = """
			(() => {
				const images = [...document.querySelectorAll('#chapter-content img, #chapter-content canvas[data-enc]')]
					.map((el) => el.tagName === 'CANVAS'
						? [...atob(el.getAttribute('data-enc'))].reverse().join('')
						: el.getAttribute('data-original-src') || el.getAttribute('data-src') || el.getAttribute('src'))
					.filter((src) => src && !src.startsWith('data:'));
				return images.length > 0 ? JSON.stringify(images) : null;
			})()
		"""
		val POST_ID_REGEX = Regex("""postid-(\d+)""")
		val LEGACY_MANGA_URL_REGEX = Regex("""/([^/]+)-\d+/?""")
		val LEGACY_CHAPTER_URL_REGEX = Regex("""/([^/]+)-\d+/(chapter-[^/-]+)(?:-[^/]*)?/?""")
	}
}
