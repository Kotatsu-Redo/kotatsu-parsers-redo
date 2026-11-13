package org.koitharu.kotatsu.parsers.site.en

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.bitmap.Bitmap
import org.koitharu.kotatsu.parsers.bitmap.Rect
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.getFloatOrDefault
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.json.mapJSONNotNull
import org.koitharu.kotatsu.parsers.util.json.mapJSONNotNullToSet
import org.koitharu.kotatsu.parsers.util.json.toStringSet
import java.text.SimpleDateFormat
import java.util.*

/**
 * The site's own pages (`/comics/...`) are behind a Cloudflare challenge. The JSON API its reader uses
 * (`api.<domain>/api`) is not, and serves everything: the catalogue, a series, its chapters and their images.
 */
@MangaSourceParser("ASURASCANS", "AsuraComic", "en")
internal class AsuraScansParser(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.ASURASCANS, pageSize = 20), Interceptor {

	override val configKeyDomain = ConfigKey.Domain("asurascans.com")

	private val apiUrl get() = "https://api.$domain/api"

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.RATING,
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.NEWEST,
		SortOrder.ALPHABETICAL_DESC,
		SortOrder.ALPHABETICAL,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isMultipleTagsSupported = true,
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchTags(),
		availableStates = EnumSet.of(
			MangaState.ONGOING,
			MangaState.FINISHED,
			MangaState.ABANDONED,
			MangaState.PAUSED,
		),
		availableContentTypes = EnumSet.of(
			ContentType.MANGA,
			ContentType.MANHWA,
			ContentType.MANHUA,
		),
	)

	private var tagsCache: Set<MangaTag>? = null

	private suspend fun fetchTags(): Set<MangaTag> {
		tagsCache?.let { return it }
		val tags = webClient.httpGet("$apiUrl/genres").parseJson().optJSONArray("data")?.toTags().orEmpty()
		tagsCache = tags
		return tags
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = "$apiUrl/series".toHttpUrl().newBuilder().apply {
			addQueryParameter("offset", ((page - 1) * pageSize).toString())
			addQueryParameter("limit", pageSize.toString())

			if (!filter.query.isNullOrBlank()) {
				addQueryParameter("search", filter.query)
			}

			if (filter.tags.isNotEmpty()) {
				addQueryParameter("genres", filter.tags.joinToString(",") { it.key })
			}

			filter.states.oneOrThrowIfMany()?.let {
				addQueryParameter(
					"status",
					when (it) {
						MangaState.ONGOING -> "ongoing"
						MangaState.FINISHED -> "completed"
						MangaState.ABANDONED -> "dropped"
						MangaState.PAUSED -> "hiatus"
						else -> throw IllegalArgumentException("$it not supported")
					},
				)
			}

			filter.types.oneOrThrowIfMany()?.let {
				addQueryParameter(
					"type",
					when (it) {
						ContentType.MANGA -> "manga"
						ContentType.MANHWA -> "manhwa"
						ContentType.MANHUA -> "manhua"
						else -> throw IllegalArgumentException("$it not supported")
					},
				)
			}

			val (sort, direction) = when (order) {
				SortOrder.RATING -> "rating" to "desc"
				SortOrder.POPULARITY -> "popular" to "desc"
				// By the date the series was added
				SortOrder.NEWEST -> "update" to "desc"
				SortOrder.ALPHABETICAL_DESC -> "title" to "desc"
				SortOrder.ALPHABETICAL -> "title" to "asc"
				else -> "latest" to "desc"
			}
			addQueryParameter("sort", sort)
			addQueryParameter("order", direction)
		}.build()
		// `data` is null when nothing matches
		val data = webClient.httpGet(url).parseJson().optJSONArray("data") ?: return emptyList()
		return data.mapJSONNotNull { it.toManga() }
	}

	/**
	 * Asura appends the same site-wide token to every series slug on its pages
	 * (`/comics/<slug>-00dcbf97`, identical across all series) and rotates it
	 * whenever the site is redeployed. The API names a series by the bare slug,
	 * which is what the stored urls (and so the ids) are made of, and the site
	 * answers a url without the token with a redirect to the current one.
	 */
	private fun JSONObject.toManga(): Manga? {
		val slug = getStringOrNull("slug") ?: return null
		val url = "/comics/$slug"
		val rating = getFloatOrDefault("rating", 0f)
		return Manga(
			id = generateUid(url),
			url = url,
			publicUrl = url.toAbsoluteUrl(domain),
			coverUrl = getStringOrNull("cover"),
			title = getStringOrNull("title") ?: return null,
			altTitles = optJSONArray("alt_titles")?.toStringSet().orEmpty(),
			rating = if (rating > 0f) (rating / 10f).coerceAtMost(1f) else RATING_UNKNOWN,
			tags = optJSONArray("genres")?.toTags().orEmpty(),
			authors = setOfNotNull(
				getStringOrNull("author")?.trim()?.nullIfEmpty(),
				getStringOrNull("artist")?.trim()?.nullIfEmpty(),
			),
			state = when (getStringOrNull("status")?.lowercase(Locale.ENGLISH)) {
				"ongoing" -> MangaState.ONGOING
				"completed" -> MangaState.FINISHED
				"hiatus" -> MangaState.PAUSED
				"dropped", "axed" -> MangaState.ABANDONED
				else -> null
			},
			description = getStringOrNull("description"),
			source = source,
			contentRating = if (isNsfwSource) ContentRating.ADULT else null,
		)
	}

	private fun JSONArray.toTags(): Set<MangaTag> = mapJSONNotNullToSet { json ->
		MangaTag(
			key = json.getStringOrNull("slug") ?: return@mapJSONNotNullToSet null,
			title = json.getStringOrNull("name") ?: return@mapJSONNotNullToSet null,
			source = source,
		)
	}

	override suspend fun getDetails(manga: Manga): Manga {
		// A slug saved with an old token is still answered, with a redirect
		val response = webClient.httpGet("$apiUrl/series/${manga.url.toSeriesSlug()}").parseJson()
		// Some series come wrapped in `data`, others don't
		val json = (response.optJSONObject("data") ?: response).getJSONObject("series")
		val details = json.toManga() ?: throw ParseException("No series in the response", manga.url)
		return manga.copy(
			title = details.title,
			altTitles = details.altTitles,
			publicUrl = details.publicUrl,
			coverUrl = details.coverUrl ?: manga.coverUrl,
			rating = details.rating,
			tags = details.tags,
			authors = details.authors,
			state = details.state,
			description = details.description,
			chapters = loadChapters(json.getString("slug")),
		)
	}

	private suspend fun loadChapters(slug: String): List<MangaChapter> {
		val data = webClient.httpGet("$apiUrl/series/$slug/chapters").parseJson().optJSONArray("data")
			?: return emptyList()
		val dateFormat = newDateFormat()
		val now = System.currentTimeMillis()
		return data.mapJSONNotNull { json ->
			// The newest chapters are first sold as early access, and can only be read with a paid account
			val isLocked = json.optBoolean("is_premium") || json.optBoolean("is_locked") ||
				dateFormat.parseSafe(json.getStringOrNull("early_access_until")) > now
			if (isLocked) {
				return@mapJSONNotNull null
			}
			val number = json.optString("number").removeSuffix(".0").nullIfEmpty() ?: return@mapJSONNotNull null
			val url = "/comics/$slug/chapter/$number"
			val title = json.getStringOrNull("title")?.trim()?.nullIfEmpty()
			MangaChapter(
				id = generateUid(url),
				title = if (title != null) "Chapter $number - $title" else "Chapter $number",
				number = number.toFloatOrNull() ?: 0f,
				volume = 0,
				url = url,
				scanlator = null,
				uploadDate = dateFormat.parseSafe(
					json.getStringOrNull("published_at") ?: json.getStringOrNull("created_at"),
				),
				branch = null,
				source = source,
			)
		}.reversed()
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val (slug, number) = chapterUrlRegex.find(chapter.url)?.destructured
			?: throw ParseException("Unexpected chapter url: ${chapter.url}", chapter.url)
		val response = webClient.httpGet("$apiUrl/series/$slug/chapters/$number").parseJson()
		val data = response.optJSONObject("data") ?: response
		val pages = data.optJSONObject("chapter")?.optJSONArray("pages")
		if (pages == null || pages.length() == 0) {
			if (data.optBoolean("is_locked")) {
				throw ParseException(
					"This chapter is in early access, it can only be read with a paid account for now",
					chapter.url,
				)
			}
			return emptyList()
		}
		return pages.mapJSONNotNull { json ->
			val url = json.getStringOrNull("url") ?: return@mapJSONNotNull null
			val tiles = json.optJSONArray("tiles")
			MangaPage(
				id = generateUid(url),
				// What the interceptor needs to put a scrambled image back in order, never sent to the server
				url = if (tiles != null && tiles.length() > 1) {
					val columns = json.optInt("tile_cols", DEFAULT_TILE_COLUMNS)
					val rows = json.optInt("tile_rows", DEFAULT_TILE_ROWS)
					"$url#$TILES_FRAGMENT${columns}x$rows:${tiles.join(",")}"
				} else {
					url
				},
				preview = null,
				source = source,
			)
		}
	}

	/**
	 * A page can be served cut into a grid of tiles in a shuffled order: `tiles[i]` is where the tile at
	 * position `i` of the served image belongs.
	 */
	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		val response = chain.proceed(request)
		val fragment = request.url.fragment
		if (fragment == null || !fragment.startsWith(TILES_FRAGMENT) || !response.isSuccessful) {
			return response
		}
		val (grid, order) = fragment.removePrefix(TILES_FRAGMENT).split(':').takeIf { it.size == 2 }
			?: return response
		val columns = grid.substringBefore('x').toIntOrNull() ?: return response
		val rows = grid.substringAfter('x').toIntOrNull() ?: return response
		val tiles = order.split(',').map { it.toIntOrNull() ?: return response }
		if (columns <= 0 || rows <= 0 || tiles.any { it !in 0 until columns * rows }) {
			return response
		}
		return context.redrawImageResponse(response) { bitmap -> unscramble(bitmap, tiles, columns, rows) }
	}

	private fun unscramble(source: Bitmap, tiles: List<Int>, columns: Int, rows: Int): Bitmap {
		val tileWidth = source.width / columns
		val tileHeight = source.height / rows
		val result = context.createBitmap(tileWidth * columns, tileHeight * rows)
		for ((from, to) in tiles.withIndex()) {
			val srcX = (from % columns) * tileWidth
			val srcY = (from / columns) * tileHeight
			val dstX = (to % columns) * tileWidth
			val dstY = (to / columns) * tileHeight
			result.drawBitmap(
				source,
				Rect(srcX, srcY, srcX + tileWidth, srcY + tileHeight),
				Rect(dstX, dstY, dstX + tileWidth, dstY + tileHeight),
			)
		}
		return result
	}

	// Links copied from the site carry the slug token, and can be those of a chapter
	override suspend fun resolveLink(resolver: LinkResolver, link: HttpUrl): Manga? {
		val segments = link.pathSegments
		if (segments.firstOrNull() != "comics") return null
		val slug = segments.getOrNull(1)?.replace(slugTokenRegex, "")?.nullIfEmpty() ?: return null
		return resolver.resolveManga(this, url = "/comics/$slug")
	}

	private fun String.toSeriesSlug(): String {
		val segments = substringBefore('?').substringBefore('#').split('/').filter { it.isNotEmpty() }
		val index = segments.indexOfFirst { it == "comics" || it == "series" }
		return segments.getOrNull(index + 1) ?: throw ParseException("Unexpected series url: $this", this)
	}

	// Dates come with a varying number of fraction digits, or none
	private fun newDateFormat() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).apply {
		timeZone = TimeZone.getTimeZone("UTC")
	}

	private companion object {
		private const val TILES_FRAGMENT = "tiles="
		private const val DEFAULT_TILE_COLUMNS = 4
		private const val DEFAULT_TILE_ROWS = 5
		private val slugTokenRegex = Regex("""-[0-9a-f]{8}$""")
		private val chapterUrlRegex = Regex("""/(?:comics|series)/([^/]+)/chapter/([^/?#]+)""")
	}
}
