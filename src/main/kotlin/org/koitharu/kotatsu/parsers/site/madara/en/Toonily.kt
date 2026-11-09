package org.koitharu.kotatsu.parsers.site.madara.en

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.util.*
import java.text.Normalizer
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("TOONILY", "Toonily", "en")
internal class Toonily(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.TOONILY, "toonily.com", pageSize = 18) {

	override val listUrl = "search/"
	override val datePattern = "MMM d, yy"
	override val withoutAjax = true
	override val authorSearchSupported = true

	init {
		paginator.firstPage = 1
		searchPaginator.firstPage = 1
		// Without it the site hides its mature titles (most of the catalogue) from listings and search
		context.cookieJar.insertCookies(domain, "toonily-mature=1")
	}

	// The search page has no release year filter
	override val filterCapabilities: MangaListFilterCapabilities
		get() = super.filterCapabilities.copy(isYearSupported = false)

	override suspend fun getFilterOptions() = super.getFilterOptions().copy(
		availableStates = EnumSet.of(
			MangaState.ONGOING,
			MangaState.FINISHED,
			MangaState.ABANDONED,
			MangaState.PAUSED,
		),
		// The site's "Family Friendly" filter (adult=0) only matches a handful of titles
		availableContentRating = EnumSet.of(ContentRating.ADULT),
	)

	override suspend fun fetchAvailableTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/search/").parseHtml()
		val tags = doc.select(".genres-dropdown .genre-item").mapNotNullToSet { label ->
			val input = label.selectFirst("input[name='genre[]']") ?: return@mapNotNullToSet null
			val key = input.attr("value")
			val title = label.selectFirst("span")?.text() ?: key.toTitleCase()
			MangaTag(
				key = key,
				title = title,
				source = source,
			)
		}
		return tags.ifEmpty { super.fetchAvailableTags() }
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		// Same paths as the site's own pager: /search/[<query slug>/][page/<n>/]
		val url = urlBuilder().apply {
			addPathSegment("search")
			val slug = filter.query?.toSearchSlug()
			if (!slug.isNullOrEmpty()) {
				addPathSegment(slug)
			}
			if (page > 1) {
				addPathSegment("page")
				addPathSegment(page.toString())
			}
			addPathSegment("")

			if (!filter.author.isNullOrBlank()) {
				addQueryParameter("author", filter.author)
			}

			filter.tags.forEachIndexed { i, tag ->
				addQueryParameter("genre[$i]", tag.key)
			}

			filter.states.mapNotNull { state ->
				when (state) {
					MangaState.ONGOING -> "on-going"
					MangaState.FINISHED -> "end"
					MangaState.ABANDONED -> "canceled"
					MangaState.PAUSED -> "on-hold"
					else -> null
				}
			}.forEachIndexed { i, value ->
				addQueryParameter("status[$i]", value)
			}

			if (filter.contentRating.oneOrThrowIfMany() == ContentRating.ADULT) {
				addQueryParameter("adult", "1")
			}

			val orderParam = when (order) {
				SortOrder.UPDATED -> "latest"
				SortOrder.POPULARITY -> "views"
				SortOrder.RATING -> "rating"
				SortOrder.NEWEST -> "new-manga"
				SortOrder.ALPHABETICAL -> "alphabet"
				SortOrder.RELEVANCE -> null
				else -> "latest"
			}
			if (orderParam != null) {
				addQueryParameter("m_orderby", orderParam)
			}
		}.build()
		val doc = webClient.httpGet(url).parseHtml()
		return parseMangaList(doc)
	}

	override fun parseMangaList(doc: Document): List<Manga> {
		val items = doc.select(".page-item-detail, div.page-item-detail.manga").ifEmpty {
			doc.select("div.row.c-tabs-item__content")
		}
		return items.mapNotNull { div ->
			val a = div.selectFirst(".post-title a") ?: div.selectFirst(".item-thumb a") ?: div.selectFirst("a") ?: return@mapNotNull null
			val rawHref = a.attr("href").trim()
			if (rawHref.isEmpty()) return@mapNotNull null
			val href = rawHref.toRelativeUrl(domain)
			val title = (div.selectFirst(".post-title a") ?: div.selectFirst(".post-title") ?: div.selectFirst("h3, h4"))?.text().orEmpty()
			if (title.isEmpty()) return@mapNotNull null
			val rating = (div.selectFirst("#averagerate") ?: div.selectFirst("[property=ratingValue]") ?: div.selectFirst("span.total_votes"))
				?.text()?.toFloatOrNull()?.div(5f) ?: RATING_UNKNOWN
			Manga(
				id = generateUid(href),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				coverUrl = div.selectFirst("img")?.src(),
				title = title,
				altTitles = emptySet(),
				rating = rating,
				tags = emptySet(),
				authors = emptySet(),
				state = null,
				source = source,
				contentRating = null,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
		val fullUrl = manga.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(fullUrl).parseHtml()

		// Old /webtoon/<slug>/ urls redirect to /serie/<slug>-<hash>/: keep the saved url (and so the id) as is
		val href = doc.selectFirst("head meta[property='og:url']")?.attr("content")?.toRelativeUrl(domain) ?: manga.url

		val chaptersDeferred = if (doc.select(selectTestAsync).isEmpty()) {
			async { loadChapters(href, doc) }
		} else {
			async { getChapters(manga, doc) }
		}

		val titleEl = doc.selectFirst(".post-title h1, h1")
		titleEl?.select(".manga-title-badges")?.remove()
		val title = titleEl?.textOrNull() ?: manga.title

		val desc = doc.select(selectDesc).html()

		val alt = doc.selectFirst(".post-content_item:contains(Alt) .summary-content")?.textOrNull()
			?: doc.body().select(selectAlt).firstOrNull()?.textOrNull()

		val authors = doc.select(".author-content a, .artist-content a").mapNotNullToSet { it.textOrNull() }

		val tags = doc.body().select(selectGenre).mapNotNullToSet { createMangaTag(it) }

		val stateDiv = doc.selectFirst(selectState)?.selectLast("div.summary-content, .summary-content")
		val state = stateDiv?.let {
			when (it.text().lowercase()) {
				in ongoing -> MangaState.ONGOING
				in finished -> MangaState.FINISHED
				in abandoned -> MangaState.ABANDONED
				in paused -> MangaState.PAUSED
				in upcoming -> MangaState.UPCOMING
				else -> null
			}
		}

		val isAdult = doc.selectFirst(".adult-confirm, .manga-title-badges.adult, .manga-title-badges:contains(18+)") != null ||
			tags.any { it.key.contains("adult", ignoreCase = true) || it.key.contains("mature", ignoreCase = true) }

		val rating = (doc.selectFirst("#averagerate") ?: doc.selectFirst("[property=ratingValue]") ?: doc.selectFirst(".post-total-rating .total_votes"))
			?.text()?.toFloatOrNull()?.div(5f) ?: manga.rating

		manga.copy(
			title = title,
			publicUrl = href.toAbsoluteUrl(domain),
			rating = rating,
			tags = tags,
			description = desc,
			altTitles = setOfNotNull(alt),
			authors = authors,
			state = state,
			chapters = chaptersDeferred.await(),
			contentRating = if (isAdult || isNsfwSource) ContentRating.ADULT else ContentRating.SAFE,
		)
	}

	// The site redirects ?s=<query> to /search/<slug>/, the slug being WordPress' sanitize_title() of the query
	private fun String.toSearchSlug(): String = Normalizer.normalize(lowercase(Locale.ROOT), Normalizer.Form.NFD)
		.replace(diacriticsRegex, "")
		.replace("æ", "ae")
		.replace("œ", "oe")
		.replace("ø", "o")
		.replace("ß", "s")
		.replace(htmlEntityRegex, "")
		.replace(dashLikeRegex, " ")
		.replace(slugDropRegex, "")
		.trim()
		.replace(slugSeparatorRegex, "-")
		.trim('-')

	private companion object {

		val diacriticsRegex = Regex("\\p{Mn}+")
		val htmlEntityRegex = Regex("&#?[a-z0-9]+;")
		val dashLikeRegex = Regex("[./\\u00A0\\u2013\\u2014]")
		val slugDropRegex = Regex("[^\\p{L}\\p{N}\\s_-]")
		val slugSeparatorRegex = Regex("[\\s-]+")
	}
}
