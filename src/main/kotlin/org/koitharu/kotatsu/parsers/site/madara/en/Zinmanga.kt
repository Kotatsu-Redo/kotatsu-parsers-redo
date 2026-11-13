package org.koitharu.kotatsu.parsers.site.madara.en

import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.exception.NotFoundException
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.model.YEAR_UNKNOWN
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import java.text.SimpleDateFormat
import java.util.EnumSet

/**
 * The site was rebuilt and is no longer a WordPress: it keeps the urls and the markup of Madara, but lists,
 * filters and chapters behave in its own way. Its address is the one with `www`: the bare domain only redirects
 * there, and the image hosts turn down any other Referer.
 */
@MangaSourceParser("ZINMANGA", "ZinManga.net", "en")
internal class Zinmanga(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.ZINMANGA, "www.zinmanga.net") {
	override val datePattern = "yyyy-MM-dd"
	override val withoutAjax = true

	// The reader page carries a ubiquitous `<p class="tooltip login-required">`
	// tooltip on its bookmark/comment buttons. The Madara default treats any
	// `.login-required` element as a hard content block and throws
	// AuthRequiredException ("needs to connect") before parsing the images,
	// so narrow the check to the real block marker only.
	override val selectRequiredLogin = ".content-blocked"

	override fun getRequestHeaders() = super.getRequestHeaders().newBuilder()
		.set("Referer", "https://www.zinmanga.net/")
		.build()

	/**
	 * The site's API takes 60 requests a minute and turns down everything over that for a while, so requests
	 * to it wait for their turn once they come close to that rate.
	 */
	override fun intercept(chain: Interceptor.Chain): Response {
		if (chain.request().url.encodedPath.startsWith("/api/")) {
			synchronized(apiRequestTimes) {
				var now = System.currentTimeMillis()
				while (apiRequestTimes.isNotEmpty() && now - apiRequestTimes.first() >= API_RATE_PERIOD_MS) {
					apiRequestTimes.removeFirst()
				}
				if (apiRequestTimes.size >= API_RATE_LIMIT) {
					Thread.sleep(API_RATE_PERIOD_MS - (now - apiRequestTimes.removeFirst()))
					now = System.currentTimeMillis()
				}
				apiRequestTimes.addLast(now)
			}
		}
		return super.intercept(chain)
	}

	override suspend fun getFilterOptions() = super.getFilterOptions().copy(
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED, MangaState.ABANDONED),
	)

	/**
	 * A sort order is honoured by the catalogue and by a genre's page, but not by the search, which lists the
	 * latest updates first and is the only one of the three to take a query and the other filters.
	 */
	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val pageNumber = page + 1
		val sort = when (order) {
			SortOrder.POPULARITY -> "views"
			SortOrder.NEWEST -> "new-manga"
			SortOrder.ALPHABETICAL -> "alphabet"
			SortOrder.RATING -> "rating"
			else -> null
		}
		val isSortable = filter.query.isNullOrEmpty() && filter.tags.size <= 1 && filter.states.isEmpty() &&
			filter.contentRating.isEmpty() && filter.year == YEAR_UNKNOWN && filter.author.isNullOrEmpty()
		if (sort != null && isSortable) {
			val tag = filter.tags.firstOrNull()
			val path = if (tag != null) tagPrefix + tag.key else "manga"
			try {
				return parseMangaList(webClient.httpGet("https://$domain/$path/page/$pageNumber?m_orderby=$sort").parseHtml())
			} catch (e: NotFoundException) {
				// The pages of a few genres are broken on the site itself, while the search still lists them
				if (tag == null) throw e
			}
		}
		val url = buildString {
			append("https://")
			append(domain)
			append("/page/")
			append(pageNumber)
			append("?s=")
			append(filter.query.orEmpty().urlEncoded())
			append("&post_type=wp-manga")
			filter.tags.forEach {
				append("&genre%5B%5D=")
				append(it.key)
			}
			filter.states.forEach {
				append("&status%5B%5D=")
				append(
					when (it) {
						MangaState.ONGOING -> "ongoing"
						MangaState.FINISHED -> "completed"
						MangaState.ABANDONED -> "drop"
						else -> throw IllegalArgumentException("$it not supported")
					},
				)
			}
			filter.contentRating.oneOrThrowIfMany()?.let {
				append("&adult=")
				append(if (it == ContentRating.ADULT) "1" else "0")
			}
			if (filter.year != YEAR_UNKNOWN) {
				append("&release=")
				append(filter.year)
			}
			if (!filter.author.isNullOrEmpty()) {
				append("&author=")
				append(filter.author.lowercase().replace(' ', '-').urlEncoded())
			}
		}
		return parseMangaList(webClient.httpGet(url).parseHtml())
	}

	override fun parseMangaList(doc: Document): List<Manga> {
		// "NEW" and "HOT" badges sit inside the titles
		doc.select(".manga-title-badges").remove()
		return super.parseMangaList(doc)
	}

	// The site no longer embeds chapters in the manga page; they are loaded
	// from a paginated JSON API (/api/comics/{slug}/chapters).
	override suspend fun getChapters(manga: Manga, doc: Document): List<MangaChapter> {
		// Entries opened before the address had its `www` were saved with an absolute url
		val mangaUrl = manga.url.toRelativePath().removeSuffix("/")
		val slug = mangaUrl.substringAfterLast('/')
		val dateFormat = SimpleDateFormat(datePattern, sourceLocale)
		val collected = ArrayList<MangaChapter>()
		var page = 1
		while (true) {
			val url = "https://$domain/api/comics/$slug/chapters?per_page=$CHAPTERS_PER_PAGE&order=desc&page=$page"
			val data = webClient.httpGet(url).parseJson().getJSONObject("data")
			data.getJSONArray("chapters").mapJSON { jo ->
				val chapterSlug = jo.getString("chapter_slug")
				val href = "$mangaUrl/$chapterSlug" + stylePage
				collected += MangaChapter(
					id = generateUid("$mangaUrl/$chapterSlug"),
					title = jo.getStringOrNull("chapter_name"),
					number = jo.getFloatOrDefault("chapter_num", 0f),
					volume = 0,
					url = href,
					uploadDate = parseChapterDate(
						dateFormat,
						jo.getStringOrNull("updated_at")?.substringBefore('T'),
					),
					source = source,
					scanlator = null,
					branch = null,
				)
			}
			val lastPage = data.getIntOrDefault("last_page", page)
			if (page >= lastPage) break
			page++
		}
		// API returns newest-first; reverse so chapters are ordered oldest-first.
		return collected.asReversed().distinctBy { it.id }
	}

	private fun String.toRelativePath(): String = if (startsWith('/')) this else "/" + substringAfter("://").substringAfter('/')

	private companion object {
		// The most the API gives at once, its default is 10
		const val CHAPTERS_PER_PAGE = 100

		// A little under the limit, as the site's own pages may be calling the API from the same address
		const val API_RATE_LIMIT = 50
		const val API_RATE_PERIOD_MS = 60_000L
		val apiRequestTimes = ArrayDeque<Long>()
	}
}
