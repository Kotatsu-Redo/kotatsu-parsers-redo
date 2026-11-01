package org.koitharu.kotatsu.parsers.site.all

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response
import org.jsoup.HttpStatusException
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.TooManyRequestExceptions
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
import org.koitharu.kotatsu.parsers.network.UserAgents
import org.koitharu.kotatsu.parsers.util.attrAsRelativeUrl
import org.koitharu.kotatsu.parsers.util.attrOrNull
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.mapNotNullToSet
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.oneOrThrowIfMany
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.parseRaw
import org.koitharu.kotatsu.parsers.util.src
import org.koitharu.kotatsu.parsers.util.textOrNull
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.toTitleCase
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("HENTAIPAW", "HentaiPaw", type = ContentType.HENTAI)
internal class HentaiPaw(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.HENTAIPAW, 30) {

	override val configKeyDomain = ConfigKey.Domain("hentaipaw.com")

	override val userAgentKey = ConfigKey.UserAgent(UserAgents.CHROME_DESKTOP)

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.POPULARITY,
		SortOrder.POPULARITY_WEEK,
		SortOrder.POPULARITY_TODAY,
		SortOrder.NEWEST,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = false,
		)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
		.add("Referer", "https://$domain/")
		.build()

	override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions(
		availableTags = fetchAvailableTags(),
		availableLocales = LANGUAGE_IDS.keys,
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val query = filter.query?.trim()
		val targetLocale = filter.locale
		val url = when {
			!query.isNullOrEmpty() -> {
				"https://$domain/articles/search".toHttpUrl().newBuilder()
					.addQueryParameter("keyword", query)
					.addQueryParameter("page", page.toString())
					.build()
					.toString()
			}

			filter.tags.isNotEmpty() -> {
				val tag = filter.tags.oneOrThrowIfMany()!!
				"https://$domain/tags/${tag.key}?page=$page"
			}

			targetLocale != null -> {
				val langId = LANGUAGE_IDS[targetLocale]
					?: LANGUAGE_IDS.entries.firstOrNull { it.key.language == targetLocale.language }?.value
					?: return emptyList()
				"https://$domain/languages/$langId?page=$page"
			}

			order == SortOrder.POPULARITY -> {
				"https://$domain/articles/rank?t=monthly&page=$page"
			}

			order == SortOrder.POPULARITY_WEEK -> {
				"https://$domain/articles/rank?t=weekly&page=$page"
			}

			order == SortOrder.POPULARITY_TODAY -> {
				"https://$domain/articles/rank?t=daily&page=$page"
			}

			else -> {
				"https://$domain/?t=all&page=$page"
			}
		}

		val doc = httpGetThrottled(url).parseHtml()
		return parseArticleCards(doc)
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val cleanUrl = "/" + manga.url.trim('/')
		val articleId = cleanUrl.substringAfterLast('/')
		val doc = httpGetThrottled(cleanUrl.toAbsoluteUrl(domain)).parseHtml()

		val title = doc.selectFirst("h1")?.textOrNull()?.trim()?.nullIfEmpty() ?: manga.title
		val coverUrl = doc.selectFirst("img[src*=/$articleId/thumbnails/cover], a[href*=/viewer?] img")?.src()
			?.toAbsoluteUrl(domain)
			?: manga.coverUrl

		val infoRoot = doc.getElementById("article-tag-information")
		val artists = infoRoot?.select("a[href^=/artists/]")?.mapNotNullToSet {
			it.textOrNull()?.trim()?.nullIfEmpty()
		}.orEmpty()
		val groups = infoRoot?.select("a[href^=/groups/]")?.mapNotNullToSet {
			it.textOrNull()?.trim()?.nullIfEmpty()
		}.orEmpty()
		val tags = infoRoot?.select("a[href^=/tags/]")?.mapNotNullToSet { a ->
			val key = a.attr("href").removePrefix("/tags/").substringBefore('?').trim().nullIfEmpty()
				?: return@mapNotNullToSet null
			val label = a.textOrNull()?.trim()?.nullIfEmpty() ?: return@mapNotNullToSet null
			MangaTag(
				key = key,
				title = label.toTitleCase(Locale.ENGLISH),
				source = source,
			)
		}.orEmpty()

		val parodies = infoRoot?.select("a[href^=/parodies/]")?.mapNotNull { it.textOrNull()?.trim()?.nullIfEmpty() }.orEmpty()
		val language = infoRoot?.select("a[href^=/languages/]")?.firstOrNull()?.textOrNull()?.trim()?.nullIfEmpty()
		val descParts = ArrayList<String>(3)
		if (language != null) descParts.add("Language: $language")
		if (parodies.isNotEmpty()) descParts.add("Parody: ${parodies.joinToString()}")
		if (groups.isNotEmpty()) descParts.add("Group: ${groups.joinToString()}")

		val viewerUrl = "/viewer?articleId=$articleId&page=1"
		val chapter = MangaChapter(
			id = generateUid(viewerUrl),
			title = language ?: "Full Gallery",
			number = 1f,
			volume = 0,
			url = viewerUrl,
			scanlator = groups.firstOrNull(),
			uploadDate = 0L,
			branch = null,
			source = source,
		)

		return manga.copy(
			id = generateUid(cleanUrl),
			url = cleanUrl,
			publicUrl = cleanUrl.toAbsoluteUrl(domain),
			title = title,
			coverUrl = coverUrl,
			tags = tags.ifEmpty { manga.tags },
			authors = (artists + groups).ifEmpty { manga.authors },
			state = MangaState.FINISHED,
			description = descParts.joinToString("\n"),
			contentRating = ContentRating.ADULT,
			chapters = listOf(chapter),
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val articleId = chapter.url.substringAfter("articleId=", "").substringBefore('&')
		val raw = httpGetThrottled(chapter.url.toAbsoluteUrl(domain)).parseRaw()
		val normalized = raw.replace("\\/", "/")
		val regex = Regex("""https://cdn\.imagedeliveries\.com/${Regex.escape(articleId)}/[a-f0-9]+/(\d+)\.[a-zA-Z0-9]+""")

		val byPageNum = LinkedHashMap<Int, String>()
		for (match in regex.findAll(normalized)) {
			val pageNum = match.groupValues[1].toIntOrNull() ?: continue
			byPageNum.putIfAbsent(pageNum, match.value)
		}

		return byPageNum.entries
			.sortedBy { it.key }
			.map { (_, imgUrl) ->
				MangaPage(
					id = generateUid(imgUrl),
					url = imgUrl,
					preview = null,
					source = source,
				)
			}
	}

	override suspend fun getRelatedManga(seed: Manga): List<Manga> {
		val doc = httpGetThrottled(seed.url.toAbsoluteUrl(domain)).parseHtml()
		return parseArticleCards(doc).filter { it.url != seed.url }
	}

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		val doc = httpGetThrottled("https://$domain/tags/popular").parseHtml()
		val seenKeys = HashSet<String>()
		val seenTitles = HashSet<String>()
		return doc.select("a[href^=/tags/]").mapNotNullToSet { a ->
			val key = a.attr("href").removePrefix("/tags/").substringBefore('?').trim()
			if (key.isEmpty() || !key.all(Char::isDigit) || !seenKeys.add(key)) {
				return@mapNotNullToSet null
			}
			val label = a.attrOrNull("title")?.trim()?.nullIfEmpty()
				?: a.textOrNull()?.trim()?.nullIfEmpty()
				?: return@mapNotNullToSet null
			val title = label.toTitleCase(Locale.ENGLISH)
			if (!seenTitles.add(title.lowercase(Locale.ENGLISH))) {
				return@mapNotNullToSet null
			}
			MangaTag(
				key = key,
				title = title,
				source = source,
			)
		}
	}

	private fun parseArticleCards(doc: Document): List<Manga> {
		val root = doc.select("div.grid").lastOrNull() ?: doc
		val seen = HashSet<String>()
		return root.select("a[href^=/articles/]").mapNotNull { a ->
			val rel = a.attrAsRelativeUrl("href").substringBefore('?')
			val idPart = rel.removePrefix("/articles/")
			if (idPart.isEmpty() || !idPart.all(Char::isDigit) || !seen.add(rel)) {
				return@mapNotNull null
			}
			val titleEl = a.selectFirst("div[title]")
			val title = titleEl?.attrOrNull("title")?.trim()?.nullIfEmpty()
				?: titleEl?.textOrNull()?.trim()?.nullIfEmpty()
				?: a.selectFirst("img")?.attrOrNull("alt")?.substringBeforeLast(',')?.trim()?.nullIfEmpty()
				?: return@mapNotNull null
			val cover = a.selectFirst("img")?.src()?.toAbsoluteUrl(domain)
			Manga(
				id = generateUid(rel),
				url = rel,
				publicUrl = rel.toAbsoluteUrl(domain),
				coverUrl = cover,
				title = title,
				altTitles = emptySet(),
				rating = RATING_UNKNOWN,
				contentRating = ContentRating.ADULT,
				tags = emptySet(),
				state = MangaState.FINISHED,
				authors = emptySet(),
				source = source,
			)
		}
	}

	private suspend fun httpGetThrottled(url: String): Response {
		for (attempt in 0..2) {
			requestMutex.withLock {
				val waitMs = MIN_REQUEST_INTERVAL_MS - (System.currentTimeMillis() - lastRequestAt)
				if (waitMs > 0L) {
					delay(waitMs)
				}
				lastRequestAt = System.currentTimeMillis()
			}
			try {
				return webClient.httpGet(url)
			} catch (e: TooManyRequestExceptions) {
				if (attempt == 2) throw e
				delay((e.getRetryDelay().takeIf { it > 0L } ?: BACKOFF_MS) + 250L)
			} catch (e: HttpStatusException) {
				if (e.statusCode != 429 || attempt == 2) throw e
				delay(BACKOFF_MS * (attempt + 1))
			}
		}
		error("Unreachable")
	}

	private companion object {
		const val MIN_REQUEST_INTERVAL_MS = 400L
		const val BACKOFF_MS = 2_000L
		val requestMutex = Mutex()
		var lastRequestAt = 0L

		val LANGUAGE_IDS = linkedMapOf(
			Locale.ENGLISH to 2,
			Locale.JAPANESE to 1,
			Locale.CHINESE to 3,
			Locale.KOREAN to 4,
			Locale("es") to 5,
			Locale("ru") to 6,
			Locale("pt") to 7,
			Locale.FRENCH to 8,
			Locale("th") to 9,
			Locale("vi") to 10,
			Locale.ITALIAN to 11,
			Locale.GERMAN to 12,
		)
	}
}
