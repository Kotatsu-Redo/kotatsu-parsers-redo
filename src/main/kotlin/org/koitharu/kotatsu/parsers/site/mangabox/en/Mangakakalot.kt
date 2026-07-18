package org.koitharu.kotatsu.parsers.site.mangabox.en

import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.model.search.MangaSearchQuery
import org.koitharu.kotatsu.parsers.model.search.MangaSearchQueryCapabilities
import org.koitharu.kotatsu.parsers.model.search.QueryCriteria.Include
import org.koitharu.kotatsu.parsers.model.search.QueryCriteria.Match
import org.koitharu.kotatsu.parsers.model.search.SearchCapability
import org.koitharu.kotatsu.parsers.model.search.SearchableField.*
import org.koitharu.kotatsu.parsers.site.mangabox.MangaboxParser
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("MANGAKAKALOT", "Mangakakalot.gg", "en")
internal class Mangakakalot(context: MangaLoaderContext) : MangaboxParser(context, MangaParserSource.MANGAKAKALOT) {

	override val configKeyDomain = ConfigKey.Domain(
		"www.mangakakalot.gg",
		"mangakakalot.gg",
	)

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.NEWEST,
	)

	override val searchQueryCapabilities: MangaSearchQueryCapabilities
		get() = MangaSearchQueryCapabilities(
			SearchCapability(
				field = TAG,
				criteriaTypes = setOf(Include::class),
				isMultiple = false,
			),
			SearchCapability(
				field = TITLE_NAME,
				criteriaTypes = setOf(Match::class),
				isMultiple = false,
				isExclusive = true,
			),
			SearchCapability(
				field = STATE,
				criteriaTypes = setOf(Include::class),
				isMultiple = false,
			),
		)

	override val otherDomain = "chapmanganato.com"
	override val listUrl = "/genre/all"

	// Based on change_alias function from MangaBox/Mangakakalot.
	private fun normalizeSearchQuery(query: String): String {
		var str = query.lowercase()
		str = str.replace("[àáạảãâầấậẩẫăằắặẳẵ]".toRegex(), "a")
		str = str.replace("[èéẹẻẽêềếệểễ]".toRegex(), "e")
		str = str.replace("[ìíịỉĩ]".toRegex(), "i")
		str = str.replace("[òóọỏõôồốộổỗơờớợởỡ]".toRegex(), "o")
		str = str.replace("[ùúụủũưừứựửữ]".toRegex(), "u")
		str = str.replace("[ỳýỵỷỹ]".toRegex(), "y")
		str = str.replace("đ".toRegex(), "d")
		str = str.replace(
			"""!|@|%|\^|\*|\(|\)|\+|=|<|>|\?|/|,|\.|:|;|'| |"|&|#|\[|]|~|-|$|_""".toRegex(),
			"_",
		)
		str = str.replace("_+_".toRegex(), "_")
		str = str.replace("""^_+|_+$""".toRegex(), "")
		return str
	}

	override suspend fun getListPage(query: MangaSearchQuery, page: Int): List<Manga> {
		val titleQuery = query.criteria.filterIsInstance<Match<*>>()
			.firstOrNull { it.field == TITLE_NAME }
			?.value
			?.toString()
			?.trim()

		val url = if (!titleQuery.isNullOrBlank()) {
			"https://$domain$searchUrl${normalizeSearchQuery(titleQuery)}?page=$page"
		} else {
			val tagKey = query.criteria.filterIsInstance<Include<*>>()
				.firstOrNull { it.field == TAG }
				?.values
				?.firstOrNull()
				.let {
					when (it) {
						is MangaTag -> it.key
						null -> "all"
						else -> it.toString()
					}
				}
				.substringAfter("/genre/")
				.substringBefore("?")
				.trim('/')
				.ifBlank { "all" }

			val stateParam = query.criteria.filterIsInstance<Include<*>>()
				.firstOrNull { it.field == STATE }
				?.values
				?.firstOrNull()
				.let {
					when (it) {
						MangaState.ONGOING -> "ongoing"
						MangaState.FINISHED -> "completed"
						else -> "all"
					}
				}

			val sortParam = when (query.order ?: SortOrder.UPDATED) {
				SortOrder.POPULARITY -> "topview"
				SortOrder.NEWEST -> "newest"
				else -> "latest"
			}

			val genrePath = if (tagKey == "all") "/genre" else "/genre/$tagKey"
			"https://$domain$genrePath?page=$page&type=$sortParam&state=$stateParam"
		}

		val doc = webClient.httpGet(url).parseHtml()
		return parseSearchResults(doc)
	}

	override suspend fun fetchAvailableTags(): Set<MangaTag> {
		val parsed = runCatching {
			webClient.httpGet("https://$domain/genre").parseHtml()
				.select("a[href*='/genre/'], a[href*='?category=']")
				.mapNotNull { a ->
					val key = a.attr("href")
						.substringAfter("/genre/")
						.substringAfterLast("category=", "")
						.substringBefore("?")
						.trim('/')
					if (key.isBlank() || key == "all") {
						null
					} else {
						MangaTag(
							key = key,
							title = a.text().trim().replaceFirstChar { it.uppercaseChar() },
							source = source,
						)
					}
				}
				.distinctBy { it.key }
				.toSet()
		}.getOrDefault(emptySet())

		if (parsed.isNotEmpty()) {
			return parsed
		}

		return FALLBACK_TAGS.mapToSet { (key, title) ->
			MangaTag(
				key = key,
				title = title,
				source = source,
			)
		}
	}

	override suspend fun getChapters(doc: Document): List<MangaChapter> {
		return doc.body().select(selectChapter).mapChapters(reversed = true) { i, li ->
			val a = li.selectFirstOrThrow("a")
			val href = a.attrAsRelativeUrl("href")
			val dateText = li.select(selectDate).last()?.text() ?: "0"
			val dateFormat = if (dateText.contains("-")) {
				SimpleDateFormat("MMM-dd-yy", sourceLocale)
			} else {
				SimpleDateFormat(datePattern, sourceLocale)
			}

			MangaChapter(
				id = generateUid(href),
				title = a.text(),
				number = i + 1f,
				volume = 0,
				url = href,
				uploadDate = parseChapterDate(
					dateFormat,
					dateText,
				),
				source = source,
				scanlator = null,
				branch = null,
			)
		}
	}

	companion object {
		private val FALLBACK_TAGS = listOf(
			"action" to "Action",
			"adult" to "Adult",
			"adventure" to "Adventure",
			"comedy" to "Comedy",
			"cooking" to "Cooking",
			"doujinshi" to "Doujinshi",
			"drama" to "Drama",
			"ecchi" to "Ecchi",
			"fantasy" to "Fantasy",
			"gender-bender" to "Gender bender",
			"harem" to "Harem",
			"historical" to "Historical",
			"horror" to "Horror",
			"isekai" to "Isekai",
			"josei" to "Josei",
			"manhua" to "Manhua",
			"manhwa" to "Manhwa",
			"martial-arts" to "Martial arts",
			"mature" to "Mature",
			"mecha" to "Mecha",
			"medical" to "Medical",
			"mystery" to "Mystery",
			"one-shot" to "One shot",
			"psychological" to "Psychological",
			"romance" to "Romance",
			"school-life" to "School life",
			"sci-fi" to "Sci fi",
			"seinen" to "Seinen",
			"shoujo" to "Shoujo",
			"shoujo-ai" to "Shoujo ai",
			"shounen" to "Shounen",
			"shounen-ai" to "Shounen ai",
			"slice-of-life" to "Slice of life",
			"smut" to "Smut",
			"sports" to "Sports",
			"supernatural" to "Supernatural",
			"tragedy" to "Tragedy",
			"webtoons" to "Webtoons",
			"yaoi" to "Yaoi",
			"yuri" to "Yuri",
		)
	}
}
