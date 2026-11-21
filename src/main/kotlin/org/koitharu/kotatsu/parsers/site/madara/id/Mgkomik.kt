package org.koitharu.kotatsu.parsers.site.madara.id

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.Headers
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.exception.AuthRequiredException
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.util.*
import java.util.*

@MangaSourceParser("MGKOMIK", "MgKomik", "id")
internal class Mgkomik(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.MGKOMIK, "id.mgkomik.cc", 20) {

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(ConfigKey.InterceptCloudflare(defaultValue = true))
	}

	override val tagPrefix = "genres/"
	override val listUrl = "komik/"
	override val datePattern = "dd MMM yy"
	override val stylePage = ""
	override val sourceLocale: Locale = Locale.ENGLISH
	override val withoutAjax = true

	override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
		.add("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
		.add("Referer", "https://$domain/")
		.apply {
			val cookies = context.cookieJar.getCookies(domain)
			val cfCookie = cookies.filter { it.name.startsWith("cf_") || it.name.startsWith("__cf") }
				.joinToString("; ") { "${it.name}=${it.value}" }
			if (cfCookie.isNotEmpty()) {
				add("Cookie", cfCookie)
			}
		}
		.build()

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val pages = page + 1
		val url = buildString {
			append("https://")
			append(domain)

			if (pages > 1) {
				append("/page/")
				append(pages.toString())
			}
			append("/?s=")

			filter.query?.let {
				append(filter.query.urlEncoded())
			}

			append("&post_type=wp-manga")

			if (filter.tags.isNotEmpty()) {
				filter.tags.forEach {
					append("&genre[]=")
					append(it.key)
				}
			}

			filter.states.forEach {
				append("&status[]=")
				when (it) {
					MangaState.ONGOING -> append("on-going")
					MangaState.FINISHED -> append("end")
					MangaState.ABANDONED -> append("canceled")
					MangaState.PAUSED -> append("on-hold")
					MangaState.UPCOMING -> append("upcoming")
					else -> throw IllegalArgumentException("$it not supported")
				}
			}

			filter.contentRating.oneOrThrowIfMany()?.let {
				append("&adult=")
				append(
					when (it) {
						ContentRating.SAFE -> "0"
						ContentRating.ADULT -> "1"
						else -> ""
					},
				)
			}

			if (filter.year != 0) {
				append("&release=")
				append(filter.year.toString())
			}

			if (!filter.author.isNullOrEmpty()) {
				filter.author.let {
					append("&author=")
					append(it.lowercase().replace(" ", "-"))
				}
			}

			append("&m_orderby=")
			when (order) {
				SortOrder.POPULARITY -> append("views")
				SortOrder.UPDATED -> append("latest")
				SortOrder.NEWEST -> append("new-manga")
				SortOrder.ALPHABETICAL -> append("alphabet")
				SortOrder.RATING -> append("rating")
				SortOrder.RELEVANCE -> {}
				else -> {}
			}
		}
		val doc = loadRenderedDocument(url, "div.c-tabs-item__content, div.page-item-detail, .search-wrap")
		return parseMangaList(doc)
	}

	override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
		val fullUrl = manga.url.toAbsoluteUrl(domain)
		val doc = loadRenderedDocument(fullUrl, "div.summary_content, div.listing-chapters_wrap, h1, .post-title")

		val href = doc.selectFirst("head meta[property='og:url']")?.attr("content")?.toRelativeUrl(domain) ?: manga.url
		val testCheckAsync = doc.select(selectTestAsync)
		val chaptersDeferred = if (testCheckAsync.isEmpty()) {
			async { loadChapters(href, doc) }
		} else {
			async { getChapters(manga, doc) }
		}

		val desc = doc.select(selectDesc).html()
		val stateDiv = doc.selectFirst(selectState)?.selectLast("div.summary-content")
		val state = stateDiv?.let {
			when (it.text().lowercase()) {
				in ongoing -> MangaState.ONGOING
				in finished -> MangaState.FINISHED
				in abandoned -> MangaState.ABANDONED
				in paused -> MangaState.PAUSED
				else -> null
			}
		}
		val alt = doc.body().select(selectAlt).firstOrNull()?.tableValue()?.textOrNull()

		manga.copy(
			title = doc.selectFirst(selectTitle)?.textOrNull() ?: manga.title,
			url = href,
			publicUrl = href.toAbsoluteUrl(domain),
			tags = doc.body().select(selectGenre).mapToSet { a -> createMangaTag(a) }.filterNotNull().toSet(),
			description = desc,
			altTitles = setOfNotNull(alt),
			state = state,
			chapters = chaptersDeferred.await(),
			contentRating = if (doc.selectFirst(".adult-confirm") != null || isNsfwSource) {
				ContentRating.ADULT
			} else {
				ContentRating.SAFE
			},
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = loadRenderedDocument(fullUrl, "div.reading-content, div.page-break")
		val chapterProtector = doc.getElementById("chapter-protector-data")
		if (chapterProtector == null) {
			if (doc.selectFirst(selectRequiredLogin) != null) {
				throw AuthRequiredException(source)
			}
			val root = doc.body().selectFirst(selectBodyPage) ?: throw ParseException(
				"No image found, try to log in",
				fullUrl,
			)
			return root.select(selectPage).flatMap { div ->
				div.selectOrThrow("img").map { img ->
					val url = img.requireSrc().toRelativeUrl(domain)
					MangaPage(
						id = generateUid(url),
						url = url,
						preview = null,
						source = source,
					)
				}
			}
		} else {
			return super.getPages(chapter)
		}
	}

	override suspend fun fetchAvailableTags(): Set<MangaTag> {
		val doc = loadRenderedDocument("https://$domain/$listUrl", "div.genres_wrap, ul.second-menu, ul.sub-menu")
		val body = doc.body()
		val root1 = body.selectFirst("header")?.selectFirst("ul.second-menu")
		val root2 = body.selectFirst("div.genres_wrap")?.selectFirst("ul.list-unstyled")
		if (root1 == null && root2 == null) {
			doc.parseFailed("Root not found")
		}
		val list = root1?.select("li").orEmpty() + root2?.select("li").orEmpty()
		val keySet = HashSet<String>(list.size)
		return list.mapNotNullToSet { li ->
			val a = li.selectFirst("a") ?: return@mapNotNullToSet null
			val href = a.attr("href").removeSuffix('/').substringAfterLast(tagPrefix, "")
			if (href.isEmpty() || !keySet.add(href)) {
				return@mapNotNullToSet null
			}
			MangaTag(
				key = href,
				title = a.ownText().ifEmpty {
					a.selectFirst(".menu-image-title")?.textOrNull()
				}?.toTitleCase(sourceLocale) ?: return@mapNotNullToSet null,
				source = source,
			)
		}
	}

	private suspend fun loadRenderedDocument(url: String, readySelector: String): Document {
		val direct = runCatching {
			webClient.httpGet(url).parseHtml()
		}.getOrNull()

		if (direct != null && direct.selectFirst(readySelector) != null && !isCloudflareChallengePage(direct.outerHtml())) {
			return direct
		}

		val script = """
			(() => {
				const hasContent = document.querySelector('$readySelector') !== null;
				if (hasContent) {
					window.stop();
					const elementsToRemove = document.querySelectorAll('script, iframe, object, embed');
					elementsToRemove.forEach(el => el.remove());
					return document.documentElement ? document.documentElement.outerHTML : "";
				}
				const title = document.title ? document.title.toLowerCase() : '';
				if (title.includes('access denied') || document.querySelector('div.cf-error-details') !== null) {
					return 'CLOUDFLARE_BLOCKED';
				}
				return null;
			})();
		""".trimIndent()

		val rawHtml = runCatching {
			context.evaluateJs(url, script, 30000L)
		}.getOrNull().orEmpty().decodeWebViewString()

		if (rawHtml == "CLOUDFLARE_BLOCKED") {
			requestCloudflareVerification(url)
		}

		if (rawHtml.isNotBlank() && !isCloudflareChallengePage(rawHtml)) {
			val doc = Jsoup.parse(rawHtml, url)
			if (doc.selectFirst(readySelector) != null) {
				return doc
			}
		}

		requestCloudflareVerification(url)
	}

	private fun requestCloudflareVerification(url: String, cause: Throwable? = null): Nothing {
		try {
			context.requestCloudflareVerification(this, url)
		} catch (e: UnsupportedOperationException) {
			throw ParseException(
				"Cloudflare verification is required. Open MgKomik in WebView and retry.",
				url,
				cause ?: e,
			)
		}
		throw ParseException("Cloudflare verification is required. Open MgKomik in WebView and retry.", url, cause)
	}

	private fun isCloudflareChallengePage(html: String): Boolean {
		if (html.isBlank()) return false
		val lower = html.lowercase(Locale.US)
		return lower.contains("<title>access denied") ||
			lower.contains("<title>just a moment") ||
			lower.contains("<title>attention required") ||
			((lower.contains("just a moment") || lower.contains("checking your browser") || lower.contains("attention required")) &&
				lower.contains("cloudflare")) ||
			lower.contains("cf-browser-verification") ||
			lower.contains("cf-chl-opt") ||
			lower.contains("window._cf_chl_opt") ||
			lower.contains("/cdn-cgi/challenge-platform/") ||
			lower.contains("challenge-platform") ||
			lower.contains("challenges.cloudflare.com") ||
			lower.contains("cf-turnstile") ||
			lower.contains("cf-mitigated")
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
			.replace(Regex("""\\u([0-9A-Fa-f]{4})""")) { match ->
				match.groupValues[1].toInt(16).toChar().toString()
			}
	}
}
