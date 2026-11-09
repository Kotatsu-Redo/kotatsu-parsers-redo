package org.koitharu.kotatsu.parsers.site.id

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap

@MangaSourceParser("VORATOON", "Voratoon", "id")
internal class Voratoon(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.VORATOON, pageSize = 30) {

	// The JSON API sits behind the site's own proxy now; api.voratoon.com and the vN hosts answer 403.
	override val configKeyDomain = ConfigKey.Domain("voratoon.co")

	// Earlier versions offered these hosts, and the app keeps a saved domain: it would stay on a dead one.
	private val siteDomain: String
		get() = domain.takeUnless { it == "voratoon.id" || it == "voratoon.com" || it.endsWith(".voratoon.com") }
			?: configKeyDomain.defaultValue

	private val apiUrl: String
		get() = "https://$siteDomain/api/proxy"

	// The proxy refuses any other origin, and the default Referer comes from the saved domain
	override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
		.set("Referer", "https://$siteDomain/")
		.build()

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		val response = chain.proceed(request)
		if (response.code != 403 || request.url.host != siteDomain || !request.url.encodedPath.startsWith("/api/")) {
			return response
		}
		response.close()
		// The proxy only answers with the vt_proxy cookie, which the site sets on its pages.
		chain.proceed(
			request.newBuilder()
				.url("https://$siteDomain/")
				.removeHeader("Accept")
				.build(),
		).close()
		return chain.proceed(request)
	}

	override suspend fun getFavicons(): Favicons {
		return Favicons(
			listOf(
				Favicon(
					url = "https://$siteDomain/icon.png",
					size = 64,
					rel = null,
				),
			),
			referer = "https://$siteDomain",
		)
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.RATING,
		SortOrder.ALPHABETICAL,
	)

	override val filterCapabilities = MangaListFilterCapabilities(
		isSearchSupported = true,
		isMultipleTagsSupported = true,
		isTagsExclusionSupported = false,
		isSearchWithFiltersSupported = true,
		isYearSupported = false,
	)

	private val tagsCache = ConcurrentHashMap<String, Set<MangaTag>>()

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		tagsCache["all"]?.let { return it }
		return runCatching {
			val json = webClient.httpGet("$apiUrl/genres").parseJson()
			val arr = json.optJSONArray("data") ?: return@runCatching emptySet<MangaTag>()
			val tags = LinkedHashSet<MangaTag>(arr.length())
			for (i in 0 until arr.length()) {
				val jo = arr.optJSONObject(i) ?: continue
				val id = jo.optLong("id")
				val name = jo.optJSONObject("data")?.optString("name").orEmpty()
				if (id > 0L && name.isNotBlank()) {
					tags.add(MangaTag(title = name, key = id.toString(), source = source))
				}
			}
			tags
		}.getOrDefault(emptySet()).also { tagsCache["all"] = it }
	}

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchAvailableTags(),
		availableContentTypes = EnumSet.of(
			ContentType.MANGA,
			ContentType.MANHWA,
			ContentType.MANHUA,
		),
		availableStates = EnumSet.of(
			MangaState.ONGOING,
			MangaState.FINISHED,
			MangaState.PAUSED,
			MangaState.ABANDONED,
		),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val sort = when (order) {
			SortOrder.UPDATED -> "latest"
			SortOrder.POPULARITY -> "popularity"
			SortOrder.RATING -> "rating"
			SortOrder.ALPHABETICAL -> "title"
			else -> "latest"
		}
		val json = webClient.httpGet(buildListUrl(page, sort, filter)).parseJson()
		val data = json.optJSONArray("data") ?: return emptyList()
		return parseList(data)
	}

	private fun buildListUrl(page: Int, sort: String, filter: MangaListFilter): String = buildString {
		append(apiUrl).append("/series")
		append("?take=").append(pageSize)
		append("&page=").append(page)
		append("&sort=").append(sort)
		append("&sortOrder=desc")
		append("&includeMeta=true")
		append("&takeChapter=1")
		filter.query?.takeIf { it.isNotBlank() }?.let {
			append("&title=").append(it.urlEncoded())
		}
		// RSQL-like expression in a single param: ';' is AND, '=in=(a,b)' is OR.
		// A repeated 'filter' param is not combined by the API.
		val conditions = ArrayList<String>()
		filter.tags.mapTo(conditions) { "genreIds==${it.key}" }
		if (filter.types.isNotEmpty()) {
			conditions += filter.types.joinToString(",", "format=in=(", ")") { it.name.lowercase() }
		}
		if (filter.states.isNotEmpty()) {
			conditions += filter.states.joinToString(",", "status=in=(", ")") { it.toApiValue() }
		}
		if (conditions.isNotEmpty()) {
			append("&filter=").append(conditions.joinToString(";").urlEncoded())
		}
	}

	override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
		val slug = manga.url.substringAfterLast('/')
		val detailsDeferred = async { webClient.httpGet(buildDetailsUrl(slug)).parseJson() }
		val chaptersDeferred = async { webClient.httpGet(buildChaptersUrl(slug)).parseJson() }
		val json = detailsDeferred.await()
		val item = json.optJSONArray("data")?.optJSONObject(0) ?: return@coroutineScope manga
		val data = item.optJSONObject("data") ?: return@coroutineScope manga
		val chapters = chaptersDeferred.await().optJSONArray("data")
		manga.copy(
			title = data.optString("title").ifBlank { manga.title },
			publicUrl = "https://$siteDomain/series/$slug",
			altTitles = setOfNotNull(data.optString("nativeTitle").ifBlank { null }),
			coverUrl = data.optString("coverImage").ifEmpty { manga.coverUrl },
			largeCoverUrl = data.optString("backgroundImage").ifEmpty { null },
			rating = data.optDouble("rating", 0.0).let { if (it > 0f) it.toFloat() / 10f else RATING_UNKNOWN },
			tags = parseTags(data.optJSONArray("genres")),
			state = parseState(data.optString("status")),
			authors = parseAuthors(data.optString("author")),
			description = data.optString("synopsis").ifEmpty { null },
			chapters = parseChapters(chapters, slug),
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val parts = chapter.url.trim('/').split('/')
		if (parts.size < 4 || parts[0] != "series" || (parts[2] != "chapters" && parts[2] != "chapter")) {
			return error("Voratoon: invalid chapter url: ${chapter.url}")
		}
		val slug = parts[1]
		val storedChapter = parts[3]
		val directResult = runCatching { fetchChapterPages(slug, storedChapter) }
		if (directResult.isSuccess) return directResult.getOrThrow()

		// Compatibility with chapter URLs created by the previous parser, which stored the API id here.
		val legacyId = storedChapter.toLongOrNull() ?: throw directResult.exceptionOrNull()!!
		val chapterNumber = findChapterNumber(slug, legacyId) ?: throw directResult.exceptionOrNull()!!
		return fetchChapterPages(slug, formatChapterNumber(chapterNumber))
	}

	private fun buildDetailsUrl(slug: String): String = buildString {
		append(apiUrl).append("/series")
		append("?take=1&page=1&includeMeta=true&takeChapter=1")
		append("&filter=slug%3D%3D").append(slug.urlEncoded())
	}

	private fun buildChaptersUrl(slug: String): String =
		"$apiUrl/series/${slug.urlEncoded()}/chapters"

	private suspend fun fetchChapterPages(slug: String, chapterNumber: String): List<MangaPage> {
		val url = "${buildChaptersUrl(slug)}/${chapterNumber.urlEncoded()}"
		val root = webClient.httpGet(url).parseJson()
		val images = root.optJSONObject("data")
			?.optJSONObject("data")
			?.optJSONArray("images")
			?: return emptyList()
		return List(images.length()) { index -> images.optString(index).trim() }
			.filter { it.isNotEmpty() }
			.distinct()
			.map { imageUrl ->
				MangaPage(
					id = generateUid(imageUrl),
					url = imageUrl,
					preview = null,
					source = source,
				)
			}
	}

	private suspend fun findChapterNumber(slug: String, chapterId: Long): Float? {
		val chapters = webClient.httpGet(buildChaptersUrl(slug)).parseJson().optJSONArray("data")
			?: return null
		for (index in 0 until chapters.length()) {
			val chapter = chapters.optJSONObject(index) ?: continue
			if (chapter.optLong("id") == chapterId) {
				return parseChapterNumber(chapter)
			}
		}
		return null
	}

	private fun parseManga(jo: JSONObject): Manga? {
		val id = jo.optLong("id")
		if (id <= 0L) return null
		val data = jo.optJSONObject("data") ?: return null
		val slug = data.optString("slug").ifBlank { id.toString() }
		return Manga(
			id = generateUid(id),
			title = data.optString("title").ifBlank { "Untitled" },
			altTitles = setOfNotNull(data.optString("nativeTitle").ifBlank { null }),
			url = "/series/$slug",
			publicUrl = "https://$siteDomain/series/$slug",
			rating = data.optDouble("rating", 0.0).let { if (it > 0f) it.toFloat() / 10f else RATING_UNKNOWN },
			contentRating = ContentRating.SAFE,
			coverUrl = data.optString("coverImage").ifEmpty { null },
			tags = parseTags(data.optJSONArray("genres")),
			state = parseState(data.optString("status")),
			authors = parseAuthors(data.optString("author")),
			source = source,
		)
	}

	private fun parseList(dataArray: JSONArray): List<Manga> {
		val result = ArrayList<Manga>(dataArray.length())
		for (i in 0 until dataArray.length()) {
			dataArray.optJSONObject(i)?.let { parseManga(it)?.let { m -> result.add(m) } }
		}
		return result
	}

	private fun parseChapters(arr: JSONArray?, slug: String): List<MangaChapter> {
		if (arr == null) return emptyList()
		val result = ArrayList<MangaChapter>(arr.length())
		for (i in 0 until arr.length()) {
			val jo = arr.optJSONObject(i) ?: continue
			val id = jo.optLong("id")
			if (id <= 0L) continue
			val number = parseChapterNumber(jo)
			val chapterNumber = formatChapterNumber(number)
			result.add(
				MangaChapter(
					id = generateUid(id),
					title = "Chapter $chapterNumber",
					number = number,
					volume = 0,
					url = "/series/$slug/chapter/$chapterNumber",
					scanlator = null,
					uploadDate = parseChapterDate(jo.optString("createdAt")),
					branch = null,
					source = source,
				),
			)
		}
		return result.sortedWith(
			compareBy<MangaChapter> { it.number }
				.thenBy { it.uploadDate }
				.thenBy { it.id },
		)
	}

	private fun parseChapterNumber(chapter: JSONObject): Float {
		val topLevel = chapter.optDouble("chapterIndex", Double.NaN)
		return if (topLevel.isNaN()) {
			chapter.optJSONObject("data")?.optDouble("index", 0.0)?.toFloat() ?: 0f
		} else {
			topLevel.toFloat()
		}
	}

	private fun formatChapterNumber(number: Float): String =
		if (number % 1f == 0f) number.toInt().toString() else number.toString()

	// createdAt example: 2026-08-16T17:21:23.416+00:00
	private fun parseChapterDate(value: String): Long {
		if (value.isEmpty()) return 0L
		return runCatching {
			SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT).parse(value)?.time ?: 0L
		}.getOrDefault(0L)
	}

	private fun parseTags(arr: JSONArray?): Set<MangaTag> {
		if (arr == null) return emptySet()
		val tags = LinkedHashSet<MangaTag>(arr.length())
		for (i in 0 until arr.length()) {
			val jo = arr.optJSONObject(i) ?: continue
			val genreId = jo.optLong("id")
			val name = jo.optJSONObject("data")?.optString("name").orEmpty()
			if (genreId > 0L && name.isNotBlank()) {
				tags.add(MangaTag(title = name, key = genreId.toString(), source = source))
			}
		}
		return tags
	}

	private fun parseState(status: String): MangaState? = when (status.lowercase()) {
		"ongoing" -> MangaState.ONGOING
		"completed" -> MangaState.FINISHED
		"hiatus" -> MangaState.PAUSED
		"cancelled", "canceled" -> MangaState.ABANDONED
		else -> null
	}

	private fun MangaState.toApiValue(): String = when (this) {
		MangaState.ONGOING -> "ongoing"
		MangaState.FINISHED -> "completed"
		MangaState.PAUSED -> "hiatus"
		MangaState.ABANDONED -> "cancelled"
		else -> ""
	}

	private fun parseAuthors(author: String): Set<String> {
		if (author.isBlank()) return emptySet()
		return setOf(author.trim())
	}
}
