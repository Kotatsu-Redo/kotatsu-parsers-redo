package org.koitharu.kotatsu.parsers.site.mangak

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.json.mapJSON
import org.koitharu.kotatsu.parsers.util.json.mapJSONNotNullToSet
import org.koitharu.kotatsu.parsers.util.json.mapJSONToSet
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

internal abstract class MangaKParser(
	context: MangaLoaderContext,
	source: MangaParserSource,
	domain: String,
) : PagedMangaParser(context, source, pageSize = 24) {

	override val configKeyDomain = ConfigKey.Domain(domain)

	private val apiUrl: String get() = "https://api.$domain"

	private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

	/** Demographics the site's titles are tagged with; empty when its API has no such data. */
	protected open val supportedDemographics: Set<Demographic> = EnumSet.of(
		Demographic.SHOUJO,
		Demographic.SEINEN,
		Demographic.SHOUNEN,
		Demographic.JOSEI,
	)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	// The API has no alphabetical order, and "added_date" answers with a server error.
	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.NEWEST,
		SortOrder.RATING,
		SortOrder.POPULARITY,
		SortOrder.POPULARITY_TODAY,
		SortOrder.POPULARITY_WEEK,
		SortOrder.POPULARITY_MONTH,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = true,
			isAuthorSearchSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchTags(),
		// The site also shows hiatus and cancelled, but the API rejects them.
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
		availableContentTypes = EnumSet.of(
			ContentType.MANGA,
			ContentType.MANHWA,
			ContentType.MANHUA,
		),
		availableDemographics = supportedDemographics,
		availableContentRating = EnumSet.of(
			ContentRating.SAFE,
			ContentRating.SUGGESTIVE,
			ContentRating.ADULT,
		),
	)

	private suspend fun fetchTags(): Set<MangaTag> {
		val json = webClient.httpGet("$apiUrl/genres").parseJson()
		return json.getJSONObject("data").getJSONArray("items").mapJSONToSet { item ->
			MangaTag(
				title = item.getString("name").toTitleCase(sourceLocale),
				key = item.getString("slug"),
				source = source,
			)
		}
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append(apiUrl)
			append("/titles/search?page=")
			append(page)
			append("&limit=")
			append(pageSize)

			val query = filter.query?.toSearchQuery()
			if (query.isNullOrEmpty()) {
				append("&sort=")
				append(
					when (order) {
						SortOrder.POPULARITY -> "popular"
						SortOrder.POPULARITY_TODAY -> "views_today"
						SortOrder.POPULARITY_WEEK -> "views_7days"
						SortOrder.POPULARITY_MONTH -> "views_30days"
						SortOrder.NEWEST -> "newest"
						SortOrder.RATING -> "rating"
						else -> "latest"
					},
				)
			} else {
				// Without a sort, the API ranks results by relevance ("best match" on the site).
				append("&q=")
				append(query.urlEncoded())
			}

			filter.author?.trim()?.takeIf { it.isNotEmpty() }?.let { author ->
				append("&author=")
				append(author.urlEncoded())
			}

			appendListParam("genres", filter.tags.map { it.key })
			appendListParam("exclude", filter.tagsExclude.map { it.key })

			// Types, content ratings and demographics take comma separated lists, matching any of them.
			appendListParam(
				"type",
				filter.types.mapNotNull {
					when (it) {
						ContentType.MANGA -> "manga"
						ContentType.MANHWA -> "manhwa"
						ContentType.MANHUA -> "manhua"
						else -> null
					}
				},
			)
			appendListParam(
				"content_rating",
				filter.contentRating.map {
					when (it) {
						ContentRating.SAFE -> "safe"
						ContentRating.SUGGESTIVE -> "suggestive"
						ContentRating.ADULT -> "erotica,pornographic"
					}
				},
			)
			appendListParam(
				"demographic",
				filter.demographics.mapNotNull {
					when (it) {
						Demographic.SHOUJO -> "shoujo"
						Demographic.SEINEN -> "seinen"
						Demographic.SHOUNEN -> "shounen"
						Demographic.JOSEI -> "josei"
						else -> null
					}
				},
			)

			// The API takes a single status; both offered ones together are every title.
			when (filter.states.singleOrNull()) {
				MangaState.ONGOING -> append("&status=ongoing")
				MangaState.FINISHED -> append("&status=completed")
				else -> Unit
			}
		}

		val json = webClient.httpGet(url).parseJson()
		return json.getJSONObject("data").getJSONArray("items").mapJSON { item ->
			item.toManga()
		}
	}

	private fun StringBuilder.appendListParam(name: String, values: Collection<String>) {
		if (values.isNotEmpty()) {
			append('&')
			append(name)
			append('=')
			append(values.joinToString(","))
		}
	}

	/**
	 * The API answers HTTP 400 to queries longer than [MAX_QUERY_LENGTH] characters (its message
	 * claims 200), so long titles are cut after the last whole word that fits. Punctuation is fine.
	 */
	private fun String.toSearchQuery(): String {
		val query = trim().replace(WHITESPACE_REGEX, " ")
		if (query.length <= MAX_QUERY_LENGTH) {
			return query
		}
		val words = query.take(MAX_QUERY_LENGTH + 1).substringBeforeLast(' ', "").trimEnd()
		if (words.isNotEmpty()) {
			return words
		}
		val cut = query.take(MAX_QUERY_LENGTH)
		return if (cut.last().isHighSurrogate()) cut.dropLast(1) else cut
	}

	private fun JSONObject.toManga(): Manga {
		val relativeUrl = getString("url")
		return Manga(
			id = generateUid(getString("id")),
			title = getString("name"),
			altTitles = parseAltTitles(),
			url = getString("id"),
			publicUrl = "https://$domain$relativeUrl",
			rating = optDouble("rating", 0.0).toRating(),
			contentRating = getStringOrNull("content_rating").toContentRating(),
			coverUrl = optString("cover").nullIfEmpty(),
			tags = parseTags(),
			state = optString("status").toMangaState(),
			authors = emptySet(),
			description = optString("summary").nullIfEmpty(),
			source = source,
		)
	}

	private fun JSONObject.parseAltTitles(): Set<String> {
		val result = mutableSetOf<String>()
		optJSONArray("alt_names")?.let { arr ->
			for (i in 0 until arr.length()) {
				arr.optJSONObject(i)?.optString("name")?.nullIfEmpty()?.let { result.add(it) }
			}
		}
		optString("alt_name").nullIfEmpty()?.let { result.add(it) }
		return result
	}

	private fun JSONObject.parseTags(): Set<MangaTag> =
		optJSONArray("genres")?.mapJSONNotNullToSet { genre ->
			val slug = genre.optString("slug").nullIfEmpty() ?: return@mapJSONNotNullToSet null
			MangaTag(genre.getString("name").toTitleCase(sourceLocale), slug, source)
		}.orEmpty()

	// Links carry the slug (https://<domain>/<slug>[/<chapter>]), while Manga.url is the API id.
	override suspend fun resolveLink(resolver: LinkResolver, link: HttpUrl): Manga? {
		if (link.host.removePrefix("www.") != domain) return null
		val slug = link.pathSegments.firstOrNull()?.nullIfEmpty() ?: return null
		val id = runCatchingCancellable {
			webClient.httpGet("$apiUrl/titles/by-slug/${slug.urlEncoded()}").parseJson()
				.getJSONObject("data")
				.getString("new_url") // "/titles/<id>-<slug>"
				.substringAfter("/titles/")
				.substringBefore('/')
				.substringBefore('-')
		}.getOrNull()?.nullIfEmpty() ?: return null
		return resolver.resolveManga(this, url = id)
	}

	override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
		val json = webClient.httpGet("$apiUrl/titles/${manga.url}").parseJson()
			.getJSONObject("data").getJSONObject("title")

		val cv = json.optLong("cv")
		val chaptersDeferred = async { fetchChapters(manga.url, cv) }

		val authors = json.optJSONArray("authors")?.mapJSONNotNullToSet {
			it.optString("name").nullIfEmpty()
		}.orEmpty()
		val artists = json.optJSONArray("artists")?.mapJSONNotNullToSet {
			it.optString("name").nullIfEmpty()
		}.orEmpty()

		manga.copy(
			title = json.optString("name").nullIfEmpty() ?: manga.title,
			altTitles = json.parseAltTitles().ifEmpty { manga.altTitles },
			description = json.optString("summary").nullIfEmpty() ?: manga.description,
			tags = json.parseTags().ifEmpty { manga.tags },
			authors = authors + artists,
			state = json.optString("status").toMangaState() ?: manga.state,
			rating = json.optDouble("rating", 0.0).toRating(),
			contentRating = json.getStringOrNull("content_rating").toContentRating() ?: manga.contentRating,
			chapters = chaptersDeferred.await(),
		)
	}

	private suspend fun fetchChapters(id: String, cv: Long): List<MangaChapter> {
		val json = webClient.httpGet("$apiUrl/titles/$id/chapters?cv=$cv").parseJson()
		// The API lists newest first; the app expects oldest first.
		val chapters = json.getJSONObject("data").getJSONArray("chapters").mapJSON { it }
			.distinctBy { it.getString("id") }
			.asReversed()
		val usedIds = HashSet<Long>(chapters.size)
		return chapters.mapIndexed { index, chapter ->
			val url = chapter.getString("url")
			MangaChapter(
				id = uniqueChapterId(chapter.getString("id"), url, usedIds),
				title = chapter.optString("name").nullIfEmpty(),
				number = chapter.optDouble("chapter_number", Double.NaN)
					.takeUnless { it.isNaN() }
					?.toFloat()
					?: (index + 1).toFloat(),
				volume = 0,
				url = url,
				scanlator = null,
				uploadDate = dateFormat.parseSafe(chapter.optString("updated_at").substringBefore('.')),
				branch = null,
				source = source,
			)
		}
	}

	/**
	 * [generateUid] is a polynomial hash, and the chapter ids of a title share a prefix, so ids that
	 * differ in two neighbouring characters collide ("QDwlnxN2" and "QDwlnwm2"). Called oldest first,
	 * the first chapter keeps its id based uid, so existing ids don't change; later ones use the url.
	 */
	private fun uniqueChapterId(apiId: String, url: String, usedIds: MutableSet<Long>): Long {
		var id = generateUid(apiId)
		var attempt = 0
		while (!usedIds.add(id)) {
			id = generateUid(if (attempt == 0) url else "$url#$attempt")
			attempt++
		}
		return id
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet("https://$domain${chapter.url}").parseHtml()
		val raw = doc.selectFirst("script#__NEXT_DATA__")?.data()
			?: throw ParseException("Cannot find chapter data", chapter.url)
		val images = JSONObject(raw)
			.getJSONObject("props")
			.getJSONObject("pageProps")
			.getJSONObject("initialChapter")
			.getJSONArray("images")
		return (0 until images.length()).map { i ->
			val imageUrl = images.getString(i)
			MangaPage(
				id = generateUid(imageUrl),
				url = imageUrl,
				preview = null,
				source = source,
			)
		}
	}

	private fun Double.toRating() = if (this > 0.0) (this / 5.0).toFloat() else RATING_UNKNOWN

	private fun String?.toContentRating() = when (this) {
		"safe" -> ContentRating.SAFE
		"suggestive" -> ContentRating.SUGGESTIVE
		"erotica", "pornographic" -> ContentRating.ADULT
		else -> null
	}

	private fun String?.toMangaState() = when (this?.lowercase(Locale.US)) {
		"ongoing" -> MangaState.ONGOING
		"completed" -> MangaState.FINISHED
		"hiatus" -> MangaState.PAUSED
		"cancelled" -> MangaState.ABANDONED
		else -> null
	}

	private companion object {

		const val MAX_QUERY_LENGTH = 50

		val WHITESPACE_REGEX = Regex("\\s+")
	}
}
