package org.koitharu.kotatsu.parsers.site.en

import okhttp3.Headers.Companion.headersOf
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.Broken
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
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
import org.koitharu.kotatsu.parsers.util.attrAsAbsoluteUrlOrNull
import org.koitharu.kotatsu.parsers.util.attrAsRelativeUrlOrNull
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.LinkResolver
import org.koitharu.kotatsu.parsers.util.mapChapters
import org.koitharu.kotatsu.parsers.util.mapToSet
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.parseRaw
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.toRelativeUrl
import org.koitharu.kotatsu.parsers.util.urlEncoded
import org.koitharu.kotatsu.parsers.util.json.getFloatOrDefault
import org.koitharu.kotatsu.parsers.util.json.getIntOrDefault
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.json.mapJSONNotNull
import java.util.EnumSet
import java.util.Locale

@Broken
@MangaSourceParser("COMIKURO", "Comikuro", "en")
internal class Comikuro(context: MangaLoaderContext) : PagedMangaParser(context, MangaParserSource.COMIKURO, 21) {

	override val configKeyDomain = ConfigKey.Domain("comikuro.to")

	private val apiDomain = "api.comikuro.to"

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.RATING,
		SortOrder.NEWEST,
		SortOrder.ALPHABETICAL,
	)

	override val filterCapabilities: MangaListFilterCapabilities =
		MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
		)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
		keys.add(ConfigKey.InterceptCloudflare(defaultValue = true))
	}

	override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions(
		availableStates = EnumSet.of(
			MangaState.ONGOING,
			MangaState.FINISHED,
			MangaState.ABANDONED,
			MangaState.PAUSED,
		),
		availableContentTypes = EnumSet.of(
			ContentType.MANGA,
			ContentType.MANHWA,
			ContentType.MANHUA,
			ContentType.OTHER,
		),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val target = "https://api.comick.dev/v1.0/search".toHttpUrl().newBuilder().apply {
			addQueryParameter("limit", pageSize.toString())
			addQueryParameter("page", page.toString())
			addQueryParameter("content_rating", "safe")
			addQueryParameter("sort", order.toApiSort())

			filter.query?.trim()?.nullIfEmpty()?.let {
				addQueryParameter("q", it)
			}
			filter.types.firstOrNull()?.toCountryParam()?.let {
				addQueryParameter("country", it)
			}
			filter.states.firstOrNull()?.toStatusParam()?.let {
				addQueryParameter("status", it)
			}
		}.build()

		val url = buildProxyUrl(target)
		val raw = webClient.httpGet(url, proxyHeaders).parseRaw()
		if (isCloudflareChallengePage(raw)) {
			requestCloudflareVerification("https://$domain/")
		}
		return JSONArray(raw)
			.mapJSONNotNull { it.toManga() }
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val detailUrl = manga.detailUrl()
		val document = loadRenderedDocument(
			url = detailUrl,
			readySelector = "p.manga-synopsis-text, .manga-chapters-grid a[href^=\"/read/\"]",
		)
		val description = document.selectFirst("p.manga-synopsis-text")?.html()?.nullIfEmpty()
			?: manga.description
		return manga.copy(
			title = document.selectFirst("h1")?.text()?.nullIfEmpty() ?: manga.title,
			description = description,
			tags = parseTags(document).ifEmpty { manga.tags },
			coverUrl = document.selectFirst("img[alt*=cover i], img[src*=meo.comick i]")
				?.attrAsAbsoluteUrlOrNull("src")
				?: manga.coverUrl,
			chapters = parseChapters(document),
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val document = loadRenderedDocument(
			url = chapter.url.toAbsoluteUrl(domain),
			readySelector = ".manga-reader-container img[src], .reader-page img[src]",
		)
		val pages = document.select(".manga-reader-container img[src], .reader-page img[src]")
			.mapNotNull { it.attrAsAbsoluteUrlOrNull("src") }
			.filter { it.startsWith("https://storage.comikuro.to/", ignoreCase = true) }
			.distinct()
			.map { imageUrl ->
				MangaPage(
					id = generateUid(imageUrl),
					url = imageUrl,
					preview = null,
					source = source,
				)
			}
		if (pages.isEmpty() && isCloudflareChallengePage(document.outerHtml())) {
			requestCloudflareVerification(chapter.url.toAbsoluteUrl(domain))
		}
		return pages
	}

	override suspend fun getPageUrl(page: MangaPage): String = page.url

	override suspend fun getRelatedManga(seed: Manga): List<Manga> = emptyList()

	override fun getRequestHeaders() = super.getRequestHeaders().newBuilder()
		.add("Referer", "https://$domain/")
		.build()

	override suspend fun resolveLink(resolver: LinkResolver, link: HttpUrl): Manga? {
		val slug = when (link.pathSegments.firstOrNull()) {
			"manga", "comic", "title" -> link.pathSegments.getOrNull(1)
			else -> null
		}?.nullIfEmpty() ?: return null
		return Manga(
			id = generateUid(slug),
			title = slug.replace('-', ' ').replaceFirstChar { it.titlecase(sourceLocale) },
			altTitles = emptySet(),
			url = slug,
			publicUrl = "https://$domain/manga/$slug",
			rating = RATING_UNKNOWN,
			contentRating = ContentRating.SAFE,
			coverUrl = null,
			tags = emptySet(),
			state = null,
			authors = emptySet(),
			source = source,
		)
	}

	private fun buildProxyUrl(target: HttpUrl): HttpUrl {
		return "https://$apiDomain/api/_proxy/proxy".toHttpUrl().newBuilder()
			.addQueryParameter("url", target.toString())
			.build()
	}

	private fun JSONObject.toManga(): Manga? {
		val slug = getStringOrNull("slug") ?: return null
		val title = getStringOrNull("title") ?: return null
		val cover = optJSONArray("md_covers")?.optJSONObject(0)?.getStringOrNull("b2key")
			?.let { "https://meo.comick.pictures/$it" }
		val altTitles = optJSONArray("md_titles")?.mapJSONNotNull {
			it.getStringOrNull("title")?.takeUnless { altTitle -> altTitle.equals(title, ignoreCase = true) }
		}?.toSet().orEmpty()
		return Manga(
			id = generateUid(slug),
			title = title,
			altTitles = altTitles,
			url = slug,
			publicUrl = "https://$domain/manga/$slug",
			rating = getFloatOrDefault("rating", RATING_UNKNOWN).takeIf { it > 0f }?.div(10f) ?: RATING_UNKNOWN,
			contentRating = ContentRating.SAFE,
			coverUrl = cover,
			tags = optJSONArray("genres")?.let { ids ->
				val idSet = buildSet {
					for (i in 0 until ids.length()) {
						add(ids.optInt(i))
					}
				}
				genresById.filterKeys { it in idSet }.values.toSet()
			}.orEmpty(),
			state = getIntOrDefault("status", 0).toMangaState(),
			authors = emptySet(),
			description = getStringOrNull("desc"),
			source = source,
		)
	}

	private fun parseChapters(document: Document): List<MangaChapter> {
		return document.select(".manga-chapters-grid a[href^=\"/read/\"], a[href^=\"/read/\"]:has(h3)").mapChapters(reversed = true) { index, element ->
			val href = element.attrAsRelativeUrlOrNull("href") ?: return@mapChapters null
			val title = element.selectFirst("h3")?.text()?.nullIfEmpty() ?: "Chapter ${index + 1}"
			val scanlator = element.select("p").firstOrNull()?.text()?.nullIfEmpty()
				?.takeUnless { it.equals("Unknown group", ignoreCase = true) }
			MangaChapter(
				id = generateUid(href),
				title = title,
				number = chapterNumberRegex.find(title)?.groupValues?.get(1)?.toFloatOrNull() ?: index + 1f,
				volume = 0,
				url = href,
				scanlator = scanlator,
				uploadDate = 0L,
				branch = null,
				source = source,
			)
		}
	}

	private fun parseTags(document: Document): Set<MangaTag> {
		return document.select("a[href*=/genre/], a[href*='genres='], button").mapToSet { element ->
			val text = element.text().trim().nullIfEmpty() ?: return@mapToSet null
			val key = text.toGenreKey()
			if (key in genreKeys) {
				MangaTag(key = key, title = text, source = source)
			} else {
				null
			}
		}.filterNotNull().toSet()
	}

	private suspend fun loadRenderedDocument(url: String, readySelector: String): Document {
		val script = """
			(() => {
				const root = document.documentElement;
				const html = (root && root.outerHTML) || '';
				const text = ((document.body && document.body.innerText) || (root && root.innerText) || '');
				const lower = (document.title + '\n' + text + '\n' + html).toLowerCase();
				const challengeDetected = () => {
					return document.querySelector('script[src*="challenge-platform"]') !== null ||
						document.querySelector('script[src*="turnstile"]') !== null ||
						document.querySelector('iframe[src*="challenges.cloudflare.com"]') !== null ||
						document.querySelector('[src*="challenges.cloudflare.com"]') !== null ||
						document.querySelector('.cf-turnstile') !== null ||
						document.querySelector('input[name="cf-turnstile-response"]') !== null ||
						document.getElementById('challenge-error-title') !== null ||
						document.getElementById('challenge-error-text') !== null ||
						document.querySelector('form[action*="__cf_chl"]') !== null ||
						document.querySelector('.cf-browser-verification') !== null ||
						((lower.includes('just a moment') || lower.includes('checking your browser')) && lower.includes('cloudflare')) ||
						lower.includes('private access token challenge') ||
						lower.includes('challenge-platform') ||
						lower.includes('challenges.cloudflare.com') ||
						lower.includes('cf-turnstile') ||
						lower.includes('turnstile') ||
						lower.includes('cf-chl-opt');
				};
				if (challengeDetected()) {
					return "CLOUDFLARE_BLOCKED";
				}
				if (document.querySelector('$readySelector') !== null) {
					return html;
				}
				return null;
			})();
		""".trimIndent()
		val rawResult = context.evaluateJs(url, script, 30000L).orEmpty().decodeWebViewString()
		logReturnedPageContent(url, rawResult)
		if (rawResult == CLOUDFLARE_BLOCKED || isCloudflareChallengePage(rawResult)) {
			requestCloudflareVerification(url)
		}
		return Jsoup.parse(rawResult, url)
	}

	private fun logReturnedPageContent(url: String, html: String) {
		println("COMIKURO_DEBUG: returned page url=$url length=${html.length}")
		if (html.isEmpty()) {
			println("COMIKURO_DEBUG: returned page content is empty")
			return
		}
		html.chunked(LOG_CHUNK_SIZE).forEachIndexed { index, chunk ->
			println("COMIKURO_DEBUG: returned page chunk=${index + 1} $chunk")
		}
	}

	private fun requestCloudflareVerification(url: String): Nothing {
		try {
			context.requestBrowserAction(this, url)
		} catch (e: UnsupportedOperationException) {
			throw ParseException(cloudflareMessage, url, e)
		}
	}

	private fun isCloudflareChallengePage(html: String): Boolean {
		if (html.isBlank()) {
			return false
		}
		val lower = html.lowercase(Locale.US)
		return lower.contains("<title>access denied") ||
			lower.contains("<title>just a moment") ||
			((lower.contains("just a moment") || lower.contains("checking your browser")) &&
				lower.contains("cloudflare")) ||
			lower.contains("cf-browser-verification") ||
			lower.contains("cf-chl-opt") ||
			lower.contains("window._cf_chl_opt") ||
			lower.contains("/cdn-cgi/challenge-platform/") ||
			lower.contains("challenge-platform") ||
			lower.contains("challenges.cloudflare.com") ||
			lower.contains("cf-turnstile") ||
			lower.contains("turnstile") ||
			lower.contains("private access token challenge") ||
			lower.contains("form action=\"/cdn-cgi/l/chk_captcha") ||
			lower.contains("challenge-error-title") ||
			lower.contains("challenge-error-text")
	}

	private fun Manga.detailUrl(): String {
		return when {
			url.startsWith("/") -> url.toAbsoluteUrl(domain)
			url.startsWith("http://") || url.startsWith("https://") -> url
			else -> "https://$domain/manga/${url.urlEncoded()}"
		}
	}

	private fun SortOrder.toApiSort(): String = when (this) {
		SortOrder.POPULARITY -> "user_follow_count"
		SortOrder.RATING -> "rating"
		SortOrder.NEWEST -> "created_at"
		SortOrder.ALPHABETICAL -> "title"
		else -> "uploaded"
	}

	private fun ContentType.toCountryParam(): String? = when (this) {
		ContentType.MANGA -> "jp"
		ContentType.MANHWA -> "kr"
		ContentType.MANHUA -> "cn"
		ContentType.OTHER -> "others"
		else -> null
	}

	private fun MangaState.toStatusParam(): String? = when (this) {
		MangaState.ONGOING -> "1"
		MangaState.FINISHED -> "2"
		MangaState.ABANDONED -> "3"
		MangaState.PAUSED -> "4"
		else -> null
	}

	private fun Int.toMangaState(): MangaState? = when (this) {
		1 -> MangaState.ONGOING
		2 -> MangaState.FINISHED
		3 -> MangaState.ABANDONED
		4 -> MangaState.PAUSED
		else -> null
	}

	private fun String.decodeWebViewString(): String {
		if (length < 2 || first() != '"' || last() != '"') {
			return this
		}
		return substring(1, lastIndex)
			.replace("\\\"", "\"")
			.replace("\\\\", "\\")
			.replace("\\/", "/")
			.replace("\\n", "\n")
			.replace("\\r", "\r")
			.replace("\\t", "\t")
			.replace(unicodeEscapeRegex) { match ->
				match.groupValues[1].toInt(16).toChar().toString()
			}
	}

	private companion object {

		const val cloudflareMessage =
			"Cloudflare verification is required. Open the source in the in-app browser, complete the check, then try again."

		const val CLOUDFLARE_BLOCKED = "CLOUDFLARE_BLOCKED"

		const val LOG_CHUNK_SIZE = 3500

		val proxyHeaders = headersOf(
			"Referer", "https://comikuro.to/",
			"Origin", "https://comikuro.to",
		)

		val chapterNumberRegex = Regex("""(?:chapter|ch\.?)\s*([0-9]+(?:\.[0-9]+)?)""", RegexOption.IGNORE_CASE)

		val unicodeEscapeRegex = Regex("""\\u([0-9A-Fa-f]{4})""")

		val genres = setOf(
			"action" to "Action",
			"adventure" to "Adventure",
			"comedy" to "Comedy",
			"crime" to "Crime",
			"gender-bender" to "Gender Bender",
			"isekai" to "Isekai",
			"medical" to "Medical",
			"romance" to "Romance",
			"shounen-ai" to "Shounen Ai",
			"superhero" to "Superhero",
			"yaoi" to "Yaoi",
			"drama" to "Drama",
			"gore" to "Gore",
			"magical-girls" to "Magical Girls",
			"mystery" to "Mystery",
			"sci-fi" to "Sci-Fi",
			"slice-of-life" to "Slice of Life",
			"supernatural" to "Supernatural",
			"thriller" to "Thriller",
			"yuri" to "Yuri",
			"ecchi" to "Ecchi",
			"historical" to "Historical",
			"mature" to "Mature",
			"philosophical" to "Philosophical",
			"sexual-violence" to "Sexual Violence",
			"smut" to "Smut",
			"tragedy" to "Tragedy",
			"fantasy" to "Fantasy",
			"horror" to "Horror",
			"mecha" to "Mecha",
			"psychological" to "Psychological",
			"shoujo-ai" to "Shoujo Ai",
			"sports" to "Sports",
			"wuxia" to "Wuxia",
		).mapToSet { (key, title) ->
			MangaTag(
				key = key,
				title = title,
				source = MangaParserSource.COMIKURO,
			)
		}

		val genreKeys = genres.mapToSet { it.key }

		val genresById = mapOf(
			244 to "Action",
			247 to "Adventure",
			249 to "Comedy",
			257 to "Drama",
			250 to "Fantasy",
			252 to "Romance",
			269 to "Sports",
			274 to "Supernatural",
			279 to "Isekai",
			281 to "Thriller",
			282 to "Wuxia",
			298 to "Slice of Life",
			310 to "Tragedy",
		).mapValues { (id, title) ->
			MangaTag(
				key = title.toGenreKey(),
				title = title,
				source = MangaParserSource.COMIKURO,
			)
		}

		fun String.toGenreKey(): String = lowercase(Locale.US)
			.replace("&", "and")
			.replace(Regex("""[^a-z0-9]+"""), "-")
			.trim('-')
	}
}
