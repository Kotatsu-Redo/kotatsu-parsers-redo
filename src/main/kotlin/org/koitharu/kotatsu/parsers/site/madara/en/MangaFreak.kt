package org.koitharu.kotatsu.parsers.site.madara.en

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("MANGAFREAK", "MangaFreak", "en")
internal class MangaFreak(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.MANGAFREAK, 20) {

	override val configKeyDomain = ConfigKey.Domain(
		"ww2.mangafreak.me",
		"mangafreak.me",
	)

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isMultipleTagsSupported = false,
			isTagsExclusionSupported = false,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableStates = EnumSet.of(MangaState.FINISHED, MangaState.ONGOING),
		availableContentTypes = EnumSet.of(ContentType.MANGA, ContentType.MANHWA),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		if ((filter.query.isNullOrBlank().not() || filter.hasNonSearchOptions()) && page > 1) {
			return emptyList()
		}

		val url = when {
			filter.query.isNullOrBlank().not() || filter.hasNonSearchOptions() -> {
				buildSearchUrl(filter)
			}

			order == SortOrder.POPULARITY -> "https://$domain/Genre/All/$page"
			else -> if (page == 1) "https://$domain" else "https://$domain/Latest_Releases/$page"
		}

		val doc = webClient.httpGet(url).parseHtml()
		return when {
			filter.query.isNullOrBlank().not() || filter.hasNonSearchOptions() -> parseSearchItems(doc)
			order == SortOrder.POPULARITY -> parsePopularItems(doc)
			else -> parseLatestItems(doc)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()

		return manga.copy(
			title = doc.selectFirst("div.manga_series_data h5")?.textOrNull() ?: manga.title,
			coverUrl = doc.selectFirst("div.manga_series_image img")?.src() ?: manga.coverUrl,
			state = parseState(doc.selectFirst("div.manga_series_data > div:eq(2)")?.text()),
			authors = setOfNotNull(doc.selectFirst("div.manga_series_data > div:eq(3)")?.textOrNull()),
			tags = doc.select("div.series_sub_genre_list a").mapToSet { a ->
				createTag(a.text())
			},
			description = doc.selectFirst("div.manga_series_description p")?.textOrNull(),
			chapters = doc.select("div.manga_series_list tr:has(a)").mapChapters(reversed = true) { _, tr ->
				val link = tr.selectFirst("a") ?: return@mapChapters null
				val href = link.attrAsRelativeUrl("href")
				val chapterTitle = tr.select("td:eq(0)").text().ifBlank { link.text().ifBlank { "Chapter" } }
				MangaChapter(
					id = generateUid(href),
					title = chapterTitle,
					number = parseChapterNumber(chapterTitle),
					volume = 0,
					url = href,
					scanlator = null,
					uploadDate = dateFormat.parseSafe(tr.select("td:eq(1)").text()),
					branch = null,
					source = source,
				)
			},
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
		return doc.select("img#gohere[src]").mapIndexed { index, img ->
			val url = img.requireSrc().toRelativeUrl(domain)
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}

	override fun getRequestHeaders() = super.getRequestHeaders().newBuilder()
		.add("Referer", "https://$domain/")
		.build()

	private fun parsePopularItems(doc: Document): List<Manga> {
		return doc.select("div.ranking_item").mapNotNull { item ->
			createMangaFromElement(item, "a")
		}
	}

	private fun parseLatestItems(doc: Document): List<Manga> {
		return doc.select("div.latest_item, div.latest_releases_item").mapNotNull { item ->
			val link = if (item.hasClass("latest_item")) {
				item.selectFirst("a.name")
			} else {
				item.selectFirst("a")
			} ?: return@mapNotNull null

			val href = link.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
			val title = link.text().ifBlank { return@mapNotNull null }

			Manga(
				id = generateUid(href),
				title = title,
				altTitles = emptySet(),
				url = href,
				publicUrl = href.toAbsoluteUrl(item.host ?: domain),
				rating = RATING_UNKNOWN,
				contentRating = sourceContentRating,
				coverUrl = mapLatestCover(item.selectFirst("img")?.src()),
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				source = source,
			)
		}
	}

	private fun parseSearchItems(doc: Document): List<Manga> {
		return doc.select("div.manga_search_item, div.mangaka_search_item").mapNotNull { item ->
			createMangaFromElement(item, "h3 a, h5 a")
		}
	}

	private fun createMangaFromElement(element: Element, linkSelector: String): Manga? {
		val a = element.selectFirst(linkSelector) ?: return null
		val href = a.attrAsRelativeUrlOrNull("href") ?: return null
		val title = a.text().ifBlank { return null }

		return Manga(
			id = generateUid(href),
			title = title,
			altTitles = emptySet(),
			url = href,
			publicUrl = href.toAbsoluteUrl(element.host ?: domain),
			rating = RATING_UNKNOWN,
			contentRating = sourceContentRating,
			coverUrl = element.selectFirst("img")?.src(),
			tags = emptySet(),
			state = null,
			authors = emptySet(),
			source = source,
		)
	}

	private fun mapLatestCover(url: String?): String? {
		url ?: return null
		val parsed = url.toHttpUrlOrNull() ?: return url
		if (parsed.pathSegments.firstOrNull() != "mini_images" || parsed.pathSegments.size < 2) {
			return url
		}

		val slug = parsed.pathSegments[1]
		return parsed.newBuilder()
			.encodedPath("/")
			.addPathSegment("manga_images")
			.addPathSegment("$slug.jpg")
			.build()
			.toString()
	}

	private fun buildSearchUrl(filter: MangaListFilter): String {
		val pathSegments = mutableListOf<String>()

		filter.query?.trim()?.takeIf { it.isNotEmpty() }?.let { q ->
			pathSegments += "Find"
			pathSegments += q.urlEncoded()
		}

		val includeKeys = filter.tags.mapTo(HashSet()) { it.key }
		val excludeKeys = filter.tagsExclude.mapTo(HashSet()) { it.key }
		val genreFlags = GENRES.joinToString("") { (key, _) ->
			when {
				includeKeys.contains(key) -> "1"
				excludeKeys.contains(key) -> "2"
				else -> "0"
			}
		}
		if (genreFlags.any { it != '0' }) {
			pathSegments += "Genre"
			pathSegments += genreFlags
		}

		val status = when {
			filter.states.contains(MangaState.FINISHED) && !filter.states.contains(MangaState.ONGOING) -> "1"
			filter.states.contains(MangaState.ONGOING) && !filter.states.contains(MangaState.FINISHED) -> "2"
			else -> "0"
		}
		if (status != "0") {
			pathSegments += "Status"
			pathSegments += status
		}

		val type = when {
			filter.types.contains(ContentType.MANGA) && !filter.types.contains(ContentType.MANHWA) -> "2"
			filter.types.contains(ContentType.MANHWA) && !filter.types.contains(ContentType.MANGA) -> "1"
			else -> "0"
		}
		if (type != "0") {
			pathSegments += "Type"
			pathSegments += type
		}

		return buildString {
			append("https://")
			append(domain)
			if (pathSegments.isNotEmpty()) {
				append("/")
				append(pathSegments.joinToString("/"))
			}
		}
	}

	private fun parseState(text: String?): MangaState? {
		val normalized = text?.uppercase(Locale.US).orEmpty()
		return when {
			normalized.contains("ON-GOING") || normalized.contains("ONGOING") -> MangaState.ONGOING
			normalized.contains("COMPLETED") -> MangaState.FINISHED
			else -> null
		}
	}

	private fun parseChapterNumber(name: String): Float {
		val match = chapterNumberRegex.find(name) ?: return -1f
		val base = match.groupValues[1].toFloatOrNull() ?: return -1f
		val suffix = match.groupValues[2]
		if (suffix.isEmpty() || suffix.startsWith(".")) {
			return match.value.toFloatOrNull() ?: base
		}

		var decimal = "0."
		suffix.forEach { c ->
			decimal += (c.code - 'a'.code + 1)
		}
		return base + (decimal.toFloatOrNull() ?: 0f)
	}

	private fun createTag(raw: String): MangaTag {
		val title = raw.trim()
		val key = title.lowercase(Locale.US)
			.replace(Regex("""\s+"""), "-")
		return MangaTag(
			key = key,
			title = title.toTitleCase(),
			source = source,
		)
	}

	private companion object {
		private val dateFormat = SimpleDateFormat("yyyy/MM/dd", Locale.US)
		private val chapterNumberRegex = Regex("""(\d+)(\.\d+|[a-i]+\b)?""")
		private val GENRES = listOf(
			"act" to "Act",
			"adult" to "Adult",
			"adventure" to "Adventure",
			"ancients" to "Ancients",
			"animated" to "Animated",
			"comedy" to "Comedy",
			"demons" to "Demons",
			"drama" to "Drama",
			"ecchi" to "Ecchi",
			"fantasy" to "Fantasy",
			"gender-bender" to "Gender Bender",
			"harem" to "Harem",
			"horror" to "Horror",
			"josei" to "Josei",
			"magic" to "Magic",
			"martial-arts" to "Martial Arts",
			"mature" to "Mature",
			"mecha" to "Mecha",
			"military" to "Military",
			"mystery" to "Mystery",
			"one-shot" to "One Shot",
			"psychological" to "Psychological",
			"romance" to "Romance",
			"school-life" to "School Life",
			"sci-fi" to "Sci Fi",
			"seinen" to "Seinen",
			"shoujo" to "Shoujo",
			"shoujoai" to "Shoujoai",
			"shounen" to "Shounen",
			"shounenai" to "Shounenai",
			"slice-of-life" to "Slice Of Life",
			"smut" to "Smut",
			"sports" to "Sports",
			"super-power" to "Super Power",
			"supernatural" to "Supernatural",
			"tragedy" to "Tragedy",
			"vampire" to "Vampire",
			"yaoi" to "Yaoi",
			"yuri" to "Yuri",
		)
	}
}
