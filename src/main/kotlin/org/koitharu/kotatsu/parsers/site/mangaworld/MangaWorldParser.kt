package org.koitharu.kotatsu.parsers.site.mangaworld

import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

internal abstract class MangaWorldParser(
	context: MangaLoaderContext,
	source: MangaParserSource,
	domain: String,
	// Host the app built chapter ids from before the site moved: keeping it keeps history, bookmarks and downloads valid.
	private val legacyDomain: String,
	pageSize: Int = 16,
) : PagedMangaParser(context, source, pageSize) {

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.POPULARITY,
		SortOrder.POPULARITY_ASC,
		SortOrder.ALPHABETICAL,
		SortOrder.NEWEST,
		SortOrder.NEWEST_ASC,
		SortOrder.ALPHABETICAL_DESC,
		SortOrder.UPDATED,
	)

	override val defaultSortOrder: SortOrder
		get() = SortOrder.ALPHABETICAL

	override val configKeyDomain = ConfigKey.Domain(domain)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isMultipleTagsSupported = true,
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isYearSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchAvailableTags(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED, MangaState.ABANDONED, MangaState.PAUSED),
		availableContentTypes = EnumSet.of(
			ContentType.MANGA,
			ContentType.MANHUA,
			ContentType.MANHWA,
			ContentType.ONE_SHOT,
			ContentType.OTHER,
		),
	)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {

		// Handle UPDATED sort without filters - use homepage
		if (order == SortOrder.UPDATED && filter.query == null && filter.tags.isEmpty() && filter.states.isEmpty() && filter.types.isEmpty() && filter.year == 0) {
			return parseMangaList(webClient.httpGet("https://$domain/?page=$page").parseHtml())
		}

		val url =
			buildString {
				append("https://")
				append(domain)
				append("/archive?&page=")
				append(page.toString())

				filter.query?.let {
					append("&keyword=")
					append(filter.query.urlEncoded())
				}

				filter.tags.forEach {
					append("&genre=")
					append(it.key.urlEncoded())
				}

				when (order) {
					SortOrder.POPULARITY -> append("&sort=most_read")
					SortOrder.POPULARITY_ASC -> append("&sort=less_read")
					SortOrder.ALPHABETICAL -> append("&sort=a-z")
					SortOrder.NEWEST -> append("&sort=newest")
					SortOrder.NEWEST_ASC -> append("&sort=oldest")
					SortOrder.ALPHABETICAL_DESC -> append("&sort=z-a")
					else -> append("&sort=a-z")
				}

				filter.states.forEach {
					when (it) {
						MangaState.ONGOING -> append("&status=ongoing")
						MangaState.FINISHED -> append("&status=completed")
						MangaState.ABANDONED -> append("&status=dropped")
						MangaState.PAUSED -> append("&status=paused")
						else -> {}
					}
				}

				filter.types.forEach {
					append("&type=")
					append(
						when (it) {
							ContentType.MANGA -> "manga"
							ContentType.MANHUA -> "manhua"
							ContentType.MANHWA -> "manhwa"
							ContentType.ONE_SHOT -> "oneshot"
							ContentType.OTHER -> "thai&type=vietnamese"
							else -> ""
						},
					)
				}

				if (filter.year != 0) {
					append("&year=")
					append(filter.year)
				}

				// author ( not query but same to tags )
				// filter.author.forEach {
				// 	append("&author=")
				// 	append(it.key)
				// }

				// artist ( not query but same to tags )
				// filter.artist.forEach {
				// 	append("&artist=")
				// 	append(it.key)
				// }

			}
		val doc = webClient.httpGet(url).parseHtml()
		return parseMangaList(doc)
	}

	private fun parseMangaList(doc: Document): List<Manga> {
		return doc.select("article.card").map { div ->
			val href = div.selectFirstOrThrow("a.card-cover").attrAsRelativeUrl("href")
			val tags = div.select("a[href*=archive?genre=]")
				.mapToSet {
					val genreKey = it.attr("href").substringAfter("genre=").substringBefore("&")
					MangaTag(
						key = genreKey,
						title = it.text().toTitleCase(sourceLocale),
						source = source
					)
				}
			val author = div.selectFirst("a[href*=archive?author=]")?.text()
			Manga(
				id = generateUid(href),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				coverUrl = div.selectFirst(".card-cover img")?.attrOrNull("src"),
				title = div.selectFirst(".card-title")?.text().orEmpty(),
				altTitles = emptySet(),
				rating = div.selectFirst(".rating-chip strong")?.text()?.toFloatOrNull()?.div(10f) ?: RATING_UNKNOWN,
				tags = tags,
				authors = setOfNotNull(author),
				state =
					when (div.selectFirst(".card-tags .status")?.text()?.lowercase()) {
						"in corso" -> MangaState.ONGOING
						"finito" -> MangaState.FINISHED
						"droppato" -> MangaState.ABANDONED
						"in pausa" -> MangaState.PAUSED
						else -> null
					},
				source = source,
				contentRating = if (isNsfwSource) ContentRating.ADULT else null,
			)
		}
	}


	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/").parseHtml()
		return doc.select(".dd-genres a[href*=genre=]").mapToSet {
			MangaTag(
				key = it.attr("href").substringAfterLast('='),
				title = it.text().toTitleCase(sourceLocale),
				source = source,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		return manga.copy(
			altTitles = doc.selectFirst("p.manga-alt")?.ownText()?.split(", ")
				?.mapNotNullToSet { it.trim().nullIfEmpty() }.orEmpty(),
			description = doc.selectFirst(".manga-plot")?.text().orEmpty(),
			chapters =
				doc.select(".ch-row a.ch").mapChapters(reversed = true) { i, a ->
					val url = a.attrAsRelativeUrl("href").substringBefore('?')
					MangaChapter(
						id = generateUid("https://$legacyDomain$url"),
						title = a.selectFirst(".ch-name")?.textOrNull(),
						number = i + 1f,
						volume = a.parents().firstOrNull { it.hasClass("volume") }
							?.selectFirst(".volume-name")?.text()?.filter { it.isDigit() }?.toIntOrNull() ?: 0,
						url = "$url?style=list",
						scanlator = null,
						uploadDate =
							SimpleDateFormat("dd MMMM yyyy", Locale.ITALIAN).parseSafe(
								a.selectFirst(".ch-date")?.text(),
							),
						branch = null,
						source = source,
					)
				},
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
		return doc.select("img.reader-page").map { img ->
			val urlPage = img.requireSrc().toRelativeUrl(domain)
			MangaPage(
				id = generateUid(urlPage),
				url = urlPage,
				preview = null,
				source = source,
			)
		}
	}
}
