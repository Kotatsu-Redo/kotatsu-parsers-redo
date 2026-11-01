package org.koitharu.kotatsu.parsers.site.id

import okhttp3.Headers
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.attrAsAbsoluteUrlOrNull
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.mapNotNullToSet
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.oneOrThrowIfMany
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.src
import org.koitharu.kotatsu.parsers.util.textOrNull
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.toRelativeUrl
import org.koitharu.kotatsu.parsers.util.toTitleCase
import org.koitharu.kotatsu.parsers.util.urlEncoded
import java.util.EnumSet

@MangaSourceParser("KOMIKAPK", "KomikAPK", "id")
internal class KomikApk(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.KOMIKAPK, 21) {

	override val configKeyDomain = ConfigKey.Domain("komikapk.app")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
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
		availableContentTypes = EnumSet.of(
			ContentType.MANGA,
			ContentType.MANHWA,
			ContentType.MANHUA,
			ContentType.HENTAI,
		),
		availableContentRating = EnumSet.of(
			ContentRating.SAFE,
			ContentRating.ADULT,
		),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val query = filter.query?.trim()
		if (!query.isNullOrEmpty()) {
			val doc = webClient.httpGet(
				"https://$domain/pencarian?q=${query.urlEncoded()}&is-adult=on",
			).parseHtml()
			val all = parseCardList(doc)
			return all.drop((page - 1) * pageSize).take(pageSize)
		}

		if (order == SortOrder.POPULARITY && filter.tags.isEmpty() && filter.types.isEmpty()) {
			val doc = webClient.httpGet("https://$domain/trending").parseHtml()
			val all = parseCardList(doc)
			return all.drop((page - 1) * pageSize).take(pageSize)
		}

		val selectedType = filter.types.oneOrThrowIfMany()
		val typeSegment = when (selectedType) {
			ContentType.MANGA -> "manga"
			ContentType.MANHWA -> "manhwa"
			ContentType.MANHUA -> "manhua"
			else -> "semua"
		}
		val tagSegment = when {
			filter.tags.isNotEmpty() -> filter.tags.oneOrThrowIfMany()!!.key
			selectedType == ContentType.HENTAI -> "adult"
			else -> "semua"
		}
		val includeAdult = filter.contentRating != setOf(ContentRating.SAFE)
		val adultSuffix = if (includeAdult) "?include_adult=true" else ""
		val url = "https://$domain/pustaka/$typeSegment/$tagSegment/terbaru/$page$adultSuffix"
		val doc = webClient.httpGet(url).parseHtml()
		return parseCardList(doc)
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val cleanUrl = "/" + manga.url.trim('/').let {
			if (it.startsWith("komik/")) it else "komik/$it"
		}
		val doc = webClient.httpGet(cleanUrl.toAbsoluteUrl(domain)).parseHtml()

		val title = doc.selectFirst("h1")?.textOrNull()?.trim()?.nullIfEmpty() ?: manga.title
		val coverUrl = doc.selectFirst("figure img[src*=-cover/], figure img")?.src()?.toAbsoluteUrl(domain)
			?: manga.coverUrl
		val tags = doc.select("a[href*=/pustaka/semua/]").mapNotNullToSet { a ->
			val slug = a.attr("href").substringAfter("/pustaka/semua/", "")
				.substringBefore('/')
				.trim()
				.nullIfEmpty()
				?: return@mapNotNullToSet null
			if (slug == "semua") return@mapNotNullToSet null
			val label = a.textOrNull()?.trim()?.nullIfEmpty() ?: slug
			MangaTag(
				key = slug,
				title = label.toTitleCase(sourceLocale),
				source = source,
			)
		}
		val description = doc.selectFirst("div.font-display.mt-5.text-center")?.textOrNull()?.trim()
			?: manga.description.orEmpty()
		val author = AUTHOR_REGEX.find(description)?.groupValues?.get(1)?.trim()?.nullIfEmpty()
		val isAdult = tags.any { it.key in ADULT_TAG_KEYS }

		val uploaderLinks = doc.select("details ul.dropdown-content a[href*=?uploader=]")
		val uploaders = uploaderLinks.mapNotNull { a ->
			a.attr("href").substringAfter("uploader=", "").substringBefore('&').nullIfEmpty()
		}.distinct()

		val currentUploader = doc.select("span:containsOwn(BY:) + span").firstOrNull()?.textOrNull()?.trim()?.nullIfEmpty()
		val allChapters = ArrayList<MangaChapter>()
		val multipleBranches = uploaders.size > 1

		if (uploaders.size <= 1) {
			allChapters.addAll(parseChaptersFromDoc(doc, currentUploader, useBranch = false))
		} else {
			for (uploader in uploaders) {
				val branchDoc = if (uploader == currentUploader) {
					doc
				} else {
					webClient.httpGet("${cleanUrl.toAbsoluteUrl(domain)}?uploader=${uploader.urlEncoded()}").parseHtml()
				}
				allChapters.addAll(parseChaptersFromDoc(branchDoc, uploader, useBranch = multipleBranches))
			}
		}

		return manga.copy(
			id = generateUid(cleanUrl),
			url = cleanUrl,
			publicUrl = cleanUrl.toAbsoluteUrl(domain),
			title = title,
			coverUrl = coverUrl,
			tags = tags.ifEmpty { manga.tags },
			authors = setOfNotNull(author).ifEmpty { manga.authors },
			description = description,
			contentRating = if (isAdult) ContentRating.ADULT else manga.contentRating,
			chapters = allChapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
		return doc.select("section img[src*=-chapter/], section img[src]").mapNotNull { img ->
			val src = img.src()?.takeIf { !it.endsWith("/loading.gif") && !it.endsWith("/cover_global.png") }
				?: return@mapNotNull null
			val full = src.toAbsoluteUrl(domain)
			MangaPage(
				id = generateUid(full),
				url = full,
				preview = null,
				source = source,
			)
		}
	}

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/pustaka/semua/semua/terbaru/1").parseHtml()
		val seenKeys = HashSet<String>()
		val seenTitles = HashSet<String>()
		return doc.select("a[href*=/pustaka/semua/]").mapNotNullToSet { a ->
			val slug = a.attr("href")
				.substringAfter("/pustaka/semua/", "")
				.substringBefore('/')
				.substringBefore('?')
				.trim()
				.nullIfEmpty()
				?: return@mapNotNullToSet null
			if (slug == "semua" || !seenKeys.add(slug)) return@mapNotNullToSet null
			val rawTitle = a.textOrNull()?.trim()?.nullIfEmpty() ?: slug.replace('-', ' ')
			val title = rawTitle.lowercase(sourceLocale).toTitleCase(sourceLocale)
			if (!seenTitles.add(title.lowercase(sourceLocale))) return@mapNotNullToSet null
			MangaTag(
				key = slug,
				title = title,
				source = source,
			)
		}
	}

	private fun parseCardList(doc: Document): List<Manga> {
		val seen = HashSet<String>()
		return doc.body().select("a[href*=/komik/]").mapNotNull { a ->
			parseCard(a, seen)
		}
	}

	private fun parseCard(a: Element, seen: MutableSet<String>): Manga? {
		val abs = a.attrAsAbsoluteUrlOrNull("href") ?: return null
		val rel = abs.toRelativeUrl(domain).substringBefore('?')
		val segments = rel.trim('/').split('/')
		if (segments.size != 2 || segments[0] != "komik") return null
		val normUrl = "/komik/${segments[1]}"
		if (!seen.add(normUrl)) return null

		val title = a.selectFirst("div.font-semibold, div.line-clamp-2")?.textOrNull()?.trim()?.nullIfEmpty()
			?: return null
		val cover = a.selectFirst("img")?.src()?.toAbsoluteUrl(domain)
		return Manga(
			id = generateUid(normUrl),
			url = normUrl,
			publicUrl = normUrl.toAbsoluteUrl(domain),
			coverUrl = cover,
			title = title,
			altTitles = emptySet(),
			rating = RATING_UNKNOWN,
			contentRating = null,
			tags = emptySet(),
			state = null,
			authors = emptySet(),
			source = source,
		)
	}

	private fun parseChaptersFromDoc(
		doc: Document,
		uploader: String?,
		useBranch: Boolean,
	): List<MangaChapter> {
		val links = doc.select("div.join-vertical a[href*=/komik/]")
		val seenNumbers = HashSet<Float>(links.size)
		return links.mapIndexedNotNull { idx, a ->
			val href = a.attrAsAbsoluteUrlOrNull("href")?.toRelativeUrl(domain) ?: return@mapIndexedNotNull null
			val chTitle = a.textOrNull()?.trim()?.nullIfEmpty() ?: "Chapter ${idx + 1}"
			val parsedNum = CHAPTER_NUM_REGEX.find(chTitle)?.groupValues?.get(1)?.toFloatOrNull()
				?: CHAPTER_NUM_REGEX.find(href.substringAfterLast('/'))?.groupValues?.get(1)?.toFloatOrNull()
			val number = if (parsedNum != null && parsedNum > 0f && seenNumbers.add(parsedNum)) {
				parsedNum
			} else {
				0f
			}
			MangaChapter(
				id = generateUid(href),
				title = chTitle,
				number = number,
				volume = 0,
				url = href,
				scanlator = uploader,
				uploadDate = 0L,
				branch = if (useBranch) uploader else null,
				source = source,
			)
		}
	}

	private companion object {
		val AUTHOR_REGEX = Regex("""dibuat oleh komikus bernama\s+(.+?)\s+ini bercerita""", RegexOption.IGNORE_CASE)
		val CHAPTER_NUM_REGEX = Regex("""(\d+(?:\.\d+)?)""")
		val ADULT_TAG_KEYS = setOf("adult", "hentai", "smut", "mature", "18+", "ecchi")
	}
}
