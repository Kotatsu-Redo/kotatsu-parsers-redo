package org.koitharu.kotatsu.parsers.site.en

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
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
import org.koitharu.kotatsu.parsers.util.attrAsRelativeUrl
import org.koitharu.kotatsu.parsers.util.attrAsRelativeUrlOrNull
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.mapChapters
import org.koitharu.kotatsu.parsers.util.mapToSet
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.parseSafe
import org.koitharu.kotatsu.parsers.util.selectFirstOrThrow
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("MANGAKATANA", "MangaKatana", "en")
internal class MangaKatana(context: MangaLoaderContext) : PagedMangaParser(context, MangaParserSource.MANGAKATANA, 20) {

	override val configKeyDomain = ConfigKey.Domain("mangakatana.com")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.NEWEST,
		SortOrder.ALPHABETICAL,
	)

	override val filterCapabilities: MangaListFilterCapabilities =
		MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = false,
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = true,
		)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions(
		availableTags = genres,
		availableStates = EnumSet.of(
			MangaState.ONGOING,
			MangaState.FINISHED,
			MangaState.ABANDONED,
		),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = when {
			!filter.query.isNullOrBlank() -> {
				"https://$domain/page/$page".toHttpUrl().newBuilder()
					.addQueryParameter("search", filter.query)
					.addQueryParameter("search_by", "book_name")
					.build()
			}

			filter.hasNonSearchOptions() || order != SortOrder.UPDATED -> {
				"https://$domain/manga/page/$page".toHttpUrl().newBuilder().apply {
					addQueryParameter("filter", "1")
					addQueryParameter("include_mode", "and")
					addQueryParameter(
						"order",
						when (order) {
							SortOrder.NEWEST -> "new"
							SortOrder.ALPHABETICAL -> "az"
							else -> "latest"
						},
					)
					if (filter.tags.isNotEmpty()) {
						addQueryParameter("include", filter.tags.joinToString("_") { it.key })
					}
					if (filter.tagsExclude.isNotEmpty()) {
						addQueryParameter("exclude", filter.tagsExclude.joinToString("_") { it.key })
					}
					filter.states.firstOrNull()?.let {
						addQueryParameter(
							"status",
							when (it) {
								MangaState.ABANDONED -> "cancelled"
								MangaState.ONGOING -> "ongoing"
								MangaState.FINISHED -> "completed"
								else -> ""
							},
						)
					}
				}.build()
			}

			else -> "https://$domain/page/$page".toHttpUrl()
		}

		return parseMangaList(webClient.httpGet(url).parseHtml())
	}

	private fun parseMangaList(document: Document): List<Manga> {
		return document.select("div#book_list > div.item").mapNotNull { element ->
			parseMangaListItem(element)
		}
	}

	private fun parseMangaListItem(element: Element): Manga? {
		val a = element.selectFirst("div.text > h3 > a") ?: return null
		val href = a.attrAsRelativeUrlOrNull("href") ?: return null
		return Manga(
			id = generateUid(href),
			title = a.ownText(),
			altTitles = emptySet(),
			url = href,
			publicUrl = href.toAbsoluteUrl(domain),
			rating = RATING_UNKNOWN,
			contentRating = null,
			coverUrl = element.selectFirst("img")?.attrAsAbsoluteUrlOrNull("src"),
			tags = emptySet(),
			state = null,
			authors = emptySet(),
			source = source,
		)
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val document = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val altTitle = document.select(".alt_name").text().nullIfEmpty()
		val description = buildString {
			append(document.select(".summary > p").html())
			if (altTitle != null) {
				append("<br><br><b>Alt name(s):</b> ")
				append(altTitle)
			}
		}.nullIfEmpty()
		return manga.copy(
			title = document.selectFirst("h1.heading")?.text() ?: manga.title,
			altTitles = setOfNotNull(altTitle),
			description = description,
			state = parseState(document.select(".value.status").text()),
			tags = document.select(".genres > a").mapToSet { a ->
				MangaTag(
					key = a.attr("href").substringAfter("/genre/").trim('/'),
					title = a.text(),
					source = source,
				)
			},
			authors = document.select(".author").eachText().toSet(),
			coverUrl = parseThumbnail(document) ?: manga.coverUrl,
			chapters = parseChapters(document),
		)
	}

	private fun parseState(status: String): MangaState? = when {
		status.contains("Ongoing", ignoreCase = true) -> MangaState.ONGOING
		status.contains("Completed", ignoreCase = true) -> MangaState.FINISHED
		status.contains("Cancelled", ignoreCase = true) -> MangaState.ABANDONED
		else -> null
	}

	private fun parseThumbnail(document: Document): String? =
		document.selectFirst("div.media div.cover img")?.attrAsAbsoluteUrlOrNull("src")

	private fun parseChapters(document: Document): List<MangaChapter> {
		return document.select("tr:has(.chapter)").mapChapters(reversed = true) { i, element ->
			val a = element.selectFirst("a") ?: return@mapChapters null
			val href = a.attrAsRelativeUrl("href")
			val name = a.text()
			MangaChapter(
				id = generateUid(href),
				title = name,
				number = chapterNumberRegex.find(name)?.groupValues?.get(1)?.toFloatOrNull() ?: i + 1f,
				volume = 0,
				url = href,
				scanlator = null,
				uploadDate = dateFormat.parseSafe(element.select(".update_time").text()),
				branch = null,
				source = source,
			)
		}
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val document = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
		val imageScript = document.select("script:containsData(data-src)").firstOrNull()?.data()
			?: return emptyList()
		val imageArrayName = imageArrayNameRegex.find(imageScript)?.groupValues?.get(1)
			?: return emptyList()
		val imageArrayRegex = Regex("""var\s+$imageArrayName\s*=\s*\[([^\[]*)]""")

		return imageArrayRegex.find(imageScript)?.groupValues?.get(1)?.let { rawImages ->
			imageUrlRegex.findAll(rawImages).mapIndexed { index, match ->
				val url = match.groupValues[1]
				MangaPage(
					id = generateUid(url),
					url = url,
					preview = null,
					source = source,
				)
			}.toList()
		}.orEmpty()
	}

	private companion object {

		val dateFormat = SimpleDateFormat("MMM-dd-yyyy", Locale.US)
		val imageArrayNameRegex = Regex("""data-src['"],\s*(\w+)""")
		val imageUrlRegex = Regex("""'([^']*)'""")
		val chapterNumberRegex = Regex("""(?:chapter|ch\.?)\s*([0-9]+(?:\.[0-9]+)?)""", RegexOption.IGNORE_CASE)

		val genres = setOf(
			"4-koma" to "4 koma",
			"action" to "Action",
			"adult" to "Adult",
			"adventure" to "Adventure",
			"artbook" to "Artbook",
			"award-winning" to "Award winning",
			"comedy" to "Comedy",
			"cooking" to "Cooking",
			"doujinshi" to "Doujinshi",
			"drama" to "Drama",
			"ecchi" to "Ecchi",
			"erotica" to "Erotica",
			"fantasy" to "Fantasy",
			"gender-bender" to "Gender Bender",
			"gore" to "Gore",
			"harem" to "Harem",
			"historical" to "Historical",
			"horror" to "Horror",
			"isekai" to "Isekai",
			"josei" to "Josei",
			"loli" to "Loli",
			"manhua" to "Manhua",
			"manhwa" to "Manhwa",
			"martial-arts" to "Martial Arts",
			"mecha" to "Mecha",
			"medical" to "Medical",
			"music" to "Music",
			"mystery" to "Mystery",
			"one-shot" to "One shot",
			"overpowered-mc" to "Overpowered MC",
			"psychological" to "Psychological",
			"reincarnation" to "Reincarnation",
			"romance" to "Romance",
			"school-life" to "School Life",
			"sci-fi" to "Sci-fi",
			"seinen" to "Seinen",
			"sexual-violence" to "Sexual violence",
			"shota" to "Shota",
			"shoujo" to "Shoujo",
			"shoujo-ai" to "Shoujo Ai",
			"shounen" to "Shounen",
			"shounen-ai" to "Shounen Ai",
			"slice-of-life" to "Slice of Life",
			"sports" to "Sports",
			"super-power" to "Super power",
			"supernatural" to "Supernatural",
			"survival" to "Survival",
			"time-travel" to "Time Travel",
			"tragedy" to "Tragedy",
			"webtoon" to "Webtoon",
			"yaoi" to "Yaoi",
			"yuri" to "Yuri",
		).mapToSet { (key, title) ->
			MangaTag(
				key = key,
				title = title,
				source = MangaParserSource.MANGAKATANA,
			)
		}
	}
}
