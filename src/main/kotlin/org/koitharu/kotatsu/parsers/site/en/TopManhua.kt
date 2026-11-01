package org.koitharu.kotatsu.parsers.site.en

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.jsoup.HttpStatusException
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.NotFoundException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.getBooleanOrDefault
import org.koitharu.kotatsu.parsers.util.json.getFloatOrDefault
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.json.mapJSON
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale
import java.util.TimeZone

@MangaSourceParser("TOPMANHUA", "ManhuaTop", "en")
internal class TopManhua(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.TOPMANHUA, pageSize = 24) {

	override val configKeyDomain = ConfigKey.Domain("manhuatop.org")

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
		.set("Referer", "https://$domain/")
		.build()

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.UPDATED_ASC,
		SortOrder.POPULARITY,
		SortOrder.POPULARITY_ASC,
		SortOrder.POPULARITY_WEEK,
		SortOrder.POPULARITY_MONTH,
		SortOrder.POPULARITY_YEAR,
		SortOrder.RATING,
		SortOrder.RATING_ASC,
		SortOrder.NEWEST,
		SortOrder.NEWEST_ASC,
		SortOrder.ALPHABETICAL,
		SortOrder.ALPHABETICAL_DESC,
		SortOrder.RELEVANCE,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isMultipleTagsSupported = true,
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
		)

	// Tag keys stay the genre slugs of the Madara version. The site filters by numeric id, and a few
	// slugs belong to two terms, so each slug maps to all of its terms.
	private val genres = suspendLazy(soft = true) { fetchGenres() }

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = genres.get().values.mapNotNullToSet { terms ->
			val main = terms.maxBy { it.count }
			if (main.count > 0) {
				MangaTag(title = main.name.toTitleCase(sourceLocale), key = main.slug, source = source)
			} else {
				null
			}
		},
		availableStates = EnumSet.of(
			MangaState.ONGOING,
			MangaState.FINISHED,
			MangaState.PAUSED,
			MangaState.ABANDONED,
		),
		availableContentTypes = EnumSet.of(ContentType.MANGA, ContentType.MANHWA, ContentType.MANHUA),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val keyword = filter.query?.trim().orEmpty()
		if (keyword.length == 1) {
			return emptyList() // the site refuses queries shorter than 2 characters
		}
		val listing = "https://$domain".toHttpUrl().newBuilder().apply {
			if (keyword.isNotEmpty()) {
				addPathSegment("search").addPathSegment(keyword).addPathSegment("")
				if (page > 1) addQueryParameter("page", page.toString())
			} else {
				addPathSegment("series").addPathSegment("")
				if (page > 1) addPathSegment("page").addPathSegment(page.toString()).addPathSegment("")
			}
			sortParams(order)?.let { (sort, direction) ->
				addQueryParameter("sort", sort)
				direction?.let { addQueryParameter("order", it) }
			}
			filter.states.oneOrThrowIfMany()?.let { state ->
				addQueryParameter(
					"status",
					when (state) {
						MangaState.ONGOING -> "on-going"
						MangaState.FINISHED -> "end"
						MangaState.PAUSED -> "on-hold"
						MangaState.ABANDONED -> "canceled"
						else -> throw IllegalArgumentException("Unsupported state $state")
					},
				)
			}
			filter.types.oneOrThrowIfMany()?.let { type ->
				addQueryParameter(
					"manga_type",
					when (type) {
						ContentType.MANGA -> "Manga"
						ContentType.MANHWA -> "Manhwa"
						ContentType.MANHUA -> "Manhua"
						else -> throw IllegalArgumentException("Unsupported type $type")
					},
				)
			}
			if (filter.tags.isNotEmpty()) {
				val terms = genres.get()
				val ids = filter.tags.flatMap { tag -> terms[tag.key].orEmpty() }.map { it.id }
				require(ids.size <= MAX_GENRES) { "Select up to $MAX_GENRES genres" }
				if (ids.isNotEmpty()) addEncodedQueryParameter("genre", ids.joinToString(","))
			}
		}.build()
		val root = try {
			fetchDiscovery(listing)
		} catch (e: HttpStatusException) {
			// Past its last page the catalogue answers 404, or 422 when the page is far beyond it.
			if (page > 1 && (e.statusCode == 404 || e.statusCode == 422)) return emptyList()
			throw e
		}
		return root.getJSONArray("items").mapJSON { parseCard(it) }
	}

	private fun sortParams(order: SortOrder): Pair<String, String?>? = when (order) {
		SortOrder.UPDATED -> "latest_update" to "desc"
		SortOrder.UPDATED_ASC -> "latest_update" to "asc"
		SortOrder.POPULARITY -> "views" to "desc"
		SortOrder.POPULARITY_ASC -> "views" to "asc"
		SortOrder.POPULARITY_WEEK -> "trending-week" to null
		SortOrder.POPULARITY_MONTH -> "trending-month" to null
		SortOrder.POPULARITY_YEAR -> "trending-year" to null
		SortOrder.RATING -> "rating" to "desc"
		SortOrder.RATING_ASC -> "rating" to "asc"
		SortOrder.NEWEST -> "newest" to "desc"
		SortOrder.NEWEST_ASC -> "newest" to "asc"
		SortOrder.ALPHABETICAL -> "title" to "asc"
		SortOrder.ALPHABETICAL_DESC -> "title" to "desc"
		else -> null // relevance for a text search, latest update otherwise
	}

	private fun parseCard(card: JSONObject): Manga {
		val path = card.getString("path")
		return Manga(
			id = generateUid(path),
			title = card.getString("title"),
			altTitles = emptySet(),
			url = path,
			publicUrl = path.toAbsoluteUrl(domain),
			rating = card.getFloatOrDefault("rating", 0f).div(5f).takeIf { it > 0f } ?: RATING_UNKNOWN,
			contentRating = null,
			coverUrl = card.getStringOrNull("thumbnail"),
			tags = emptySet(),
			state = when (card.getStringOrNull("status")) {
				"on-going" -> MangaState.ONGOING
				"end" -> MangaState.FINISHED
				"on-hold" -> MangaState.PAUSED
				"canceled" -> MangaState.ABANDONED
				else -> null
			},
			authors = emptySet(),
			source = source,
		)
	}

	override suspend fun getDetails(manga: Manga): Manga {
		// The url of a renamed title stays as saved, since the ids of the entry and its chapters come from it.
		var path = manga.url
		val doc = try {
			fetchDocument(path)
		} catch (e: NotFoundException) {
			path = findRenamedPath(path) ?: throw e
			fetchDocument(path)
		}
		val tags = doc.select(".genre-chips a").mapNotNullToSet { a ->
			MangaTag(
				title = a.textOrNull()?.toTitleCase(sourceLocale) ?: return@mapNotNullToSet null,
				key = a.attr("href").removeSuffix("/").substringAfterLast('/'),
				source = source,
			)
		}
		return manga.copy(
			title = doc.selectFirst("h1#manga-title")?.textOrNull() ?: manga.title,
			altTitles = doc.selectFirst("p.alternative-title")?.text()?.split(" / ")
				?.mapNotNullToSet { it.trim().nullIfEmpty() }
				.orEmpty(),
			publicUrl = path.toAbsoluteUrl(domain),
			rating = doc.selectFirst(".detail-stats .card-rating")?.text()?.substringBefore(' ')
				?.toFloatOrNull()?.div(5f)?.takeIf { it > 0f }
				?: manga.rating,
			contentRating = if (tags.any { it.key in ADULT_GENRES }) ContentRating.ADULT else ContentRating.SAFE,
			coverUrl = doc.selectFirst(".detail-cover img")?.src() ?: manga.coverUrl,
			tags = tags,
			state = when (doc.selectFirst(".detail-stats .status-badge")?.text()?.lowercase(sourceLocale)) {
				"ongoing" -> MangaState.ONGOING
				"completed" -> MangaState.FINISHED
				"on hold" -> MangaState.PAUSED
				"canceled" -> MangaState.ABANDONED
				else -> manga.state
			},
			authors = (doc.metadataLinks("Author") + doc.metadataLinks("Artist")).toSet(),
			description = doc.selectFirst(".rich-content.synopsis")?.html().orEmpty(),
			chapters = fetchChapters(path, manga.url),
		)
	}

	// "Updating" is the site's placeholder value.
	private fun Document.metadataLinks(name: String): List<String> =
		select(".metadata-list dt:matchesOwn(^$name$) + dd a").eachText().filterNot { it == "Updating" }

	private suspend fun fetchChapters(path: String, savedPath: String): List<MangaChapter> {
		val first = fetchChapterPage(path, 1)
		val items = ArrayList(first.getJSONArray("items").mapJSON { it })
		if (first.getBooleanOrDefault("hasMore", false)) {
			val lastPage = (first.getInt("total") + items.size - 1) / items.size
			// A long series takes dozens of pages at about 2 seconds each, so a few load at once.
			for (pages in (2..lastPage).chunked(CHAPTER_PAGES_AT_ONCE)) {
				coroutineScope {
					pages.map { page -> async { fetchChapterPage(path, page) } }.awaitAll()
				}.forEach { root -> items += root.getJSONArray("items").mapJSON { it } }
			}
		}

		val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply {
			timeZone = TimeZone.getTimeZone("UTC")
		}
		// The site lists newest first. Items without a path are "Reader link unavailable" on the site.
		return items.mapChapters(reversed = true) { i, item ->
			val chapterPath = item.getStringOrNull("path") ?: return@mapChapters null
			MangaChapter(
				// Same as the Madara version: from the path, without the query it kept in the url.
				id = generateUid(savedPath + chapterPath.removePrefix(path)),
				title = listOfNotNull(
					item.getStringOrNull("title"),
					item.getStringOrNull("extra")?.nullIfEmpty(),
				).joinToString(" - ").nullIfEmpty(),
				number = i + 1f,
				volume = 0,
				url = chapterPath,
				scanlator = null,
				uploadDate = dateFormat.parseSafe(item.getStringOrNull("date")),
				branch = null,
				source = source,
			)
		}
	}

	private suspend fun fetchChapterPage(path: String, page: Int): JSONObject {
		val series = "${path.toAbsoluteUrl(domain)}?tab=chapters&chapter_page=$page".toHttpUrl()
		return webClient.httpGet(apiUrl("reader", series, "chapters")).parseJson()
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		// Chapters saved by the Madara version end in "?style=list", which the API refuses.
		val path = chapter.url.substringBefore('?')
		val images = try {
			fetchImages(path)
		} catch (e: NotFoundException) {
			val mangaPath = path.removeSuffix("/").substringBeforeLast('/') + "/"
			val renamed = findRenamedPath(mangaPath) ?: throw e
			fetchImages(renamed + path.removePrefix(mangaPath))
		}
		return withoutMissingImages(images).map { url ->
			MangaPage(id = generateUid(url), url = url, preview = null, source = source)
		}
	}

	/**
	 * The site lists some images of a chapter before they exist on its image host, and those answer 404 until
	 * they arrive, if they ever do. Leaves them out so the reader doesn't stop at a broken page; a later open
	 * of the chapter picks up any that have arrived by then. Anything but a clear 404 keeps the image.
	 */
	private suspend fun withoutMissingImages(images: List<String>): List<String> {
		val available = images.chunked(IMAGE_CHECKS_AT_ONCE).flatMap { chunk ->
			coroutineScope {
				chunk.map { url ->
					async {
						url.takeUnless {
							runCatchingCancellable { webClient.httpHead(url).close() }
								.exceptionOrNull() is NotFoundException
						}
					}
				}.awaitAll().filterNotNull()
			}
		}
		// A host that answers 404 to everything says nothing about the images themselves
		return available.ifEmpty { images }
	}

	private suspend fun fetchImages(path: String): List<String> {
		val images = ArrayList<String>()
		var page = 1
		do {
			val content = path.toAbsoluteUrl(domain).toHttpUrl().newBuilder().apply {
				if (page > 1) addQueryParameter("content_page", page.toString())
			}.build()
			val root = webClient.httpGet(apiUrl("reader", content, "content")).parseJson()
			images += root.getJSONArray("images").mapJSON { it.getString("url") }
			page++
		} while (root.getBooleanOrDefault("hasMore", false))
		return images
	}

	/**
	 * The site renamed the slugs of some titles (`martial-peak` is now `martial-peak-series`) and the old
	 * ones answer 404. Finds the current path of a saved one by searching for the title its slug spells.
	 */
	private suspend fun findRenamedPath(path: String): String? {
		val slug = path.removeSuffix("/").substringAfterLast('/')
		val search = "https://$domain".toHttpUrl().newBuilder()
			.addPathSegment("search")
			.addPathSegment(slug.replace('-', ' '))
			.addPathSegment("")
			.build()
		return fetchDiscovery(search).getJSONArray("items")
			.mapJSON { it }
			.singleOrNull { it.getString("title").lettersAndDigits() == slug.lettersAndDigits() }
			?.getString("path")
	}

	private fun String.lettersAndDigits(): String = filter(Char::isLetterOrDigit).lowercase(Locale.ROOT)

	private suspend fun fetchDocument(path: String): Document =
		webClient.httpGet(path.toAbsoluteUrl(domain)).parseHtml()

	private suspend fun fetchDiscovery(listing: HttpUrl): JSONObject =
		webClient.httpGet(apiUrl("discovery", listing)).parseJson()

	private suspend fun fetchGenres(): Map<String, List<Genre>> {
		val terms = ArrayList<Genre>()
		var page = 1
		do {
			val root = webClient.httpGet("https://$domain/api/genres?page=$page").parseJson()
			terms += root.getJSONArray("terms").mapJSON { term ->
				Genre(
					id = term.getString("id"),
					slug = term.getString("slug"),
					name = term.getString("name"),
					count = term.optInt("count"),
				)
			}
			page++
		} while (root.getBooleanOrDefault("hasMore", false))
		return terms.groupBy { it.slug }
	}

	// The API takes the url of the page it serves.
	private fun apiUrl(endpoint: String, page: HttpUrl, resource: String? = null): HttpUrl =
		"https://$domain/api/$endpoint".toHttpUrl().newBuilder().apply {
			resource?.let { addQueryParameter("resource", it) }
			addQueryParameter("path", page.encodedPath + page.encodedQuery?.let { "?$it" }.orEmpty())
		}.build()

	private class Genre(val id: String, val slug: String, val name: String, val count: Int)

	private companion object {

		const val MAX_GENRES = 10
		const val CHAPTER_PAGES_AT_ONCE = 3
		const val IMAGE_CHECKS_AT_ONCE = 8
		val ADULT_GENRES = setOf("adult", "mature", "smut")
	}
}
