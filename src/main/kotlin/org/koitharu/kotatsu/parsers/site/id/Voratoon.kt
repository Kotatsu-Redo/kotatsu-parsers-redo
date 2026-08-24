package org.koitharu.kotatsu.parsers.site.id

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

	override val configKeyDomain = ConfigKey.Domain("api.voratoon.com", "v1.voratoon.com")

	override suspend fun getFavicons(): Favicons {
		return Favicons(
			listOf(
				Favicon(
					url = "https://v1.voratoon.com/icon.png",
					size = 512,
					rel = null,
				),
			),
			referer = "https://v1.voratoon.com",
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
		isSearchWithFiltersSupported = false,
		isYearSupported = false,
	)

	private val tagsCache = ConcurrentHashMap<String, Set<MangaTag>>()

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		tagsCache["all"]?.let { return it }
		return runCatching {
			val json = webClient.httpGet("https://$domain/genres").parseJson()
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
			SortOrder.POPULARITY -> "totalViews"
			SortOrder.RATING -> "rating"
			SortOrder.ALPHABETICAL -> "title"
			else -> "latest"
		}
		val json = webClient.httpGet(buildListUrl(page, sort, filter)).parseJson()
		val data = json.optJSONArray("data") ?: return emptyList()
		return parseList(data)
	}

	private fun buildListUrl(page: Int, sort: String, filter: MangaListFilter): String = buildString {
		append("https://").append(domain).append("/series")
		append("?take=").append(pageSize)
		append("&page=").append(page)
		append("&sort=").append(sort)
		append("&sortOrder=desc")
		append("&includeMeta=true")
		append("&takeChapter=1")
		filter.query?.takeIf { it.isNotBlank() }?.let {
			append("&title=").append(it.urlEncoded())
		}
		// API accepts a single genre value only
		filter.tags.firstOrNull()?.let {
			append("&filter=genreIds%3D%3D").append(it.key.urlEncoded())
		}
		// API accepts a single format value only
		filter.types.firstOrNull()?.let {
			append("&filter=format%3D%3D").append(it.name.lowercase())
		}
		// API accepts a single status value only
		filter.states.firstOrNull()?.let {
			append("&filter=status%3D%3D").append(it.toApiValue())
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val slug = manga.url.substringAfterLast('/')
		val json = webClient.httpGet(buildDetailsUrl(slug)).parseJson()
		val item = json.optJSONArray("data")?.optJSONObject(0) ?: return manga
		val data = item.optJSONObject("data") ?: return manga
		return manga.copy(
			title = data.optString("title").ifBlank { manga.title },
			altTitles = setOfNotNull(data.optString("nativeTitle").ifBlank { null }),
			coverUrl = data.optString("coverImage").ifEmpty { manga.coverUrl },
			largeCoverUrl = data.optString("backgroundImage").ifEmpty { null },
			rating = data.optDouble("rating", 0.0).let { if (it > 0f) it.toFloat() / 10f else RATING_UNKNOWN },
			tags = parseTags(data.optJSONArray("genres")),
			state = parseState(data.optString("status")),
			authors = parseAuthors(data.optString("author")),
			description = data.optString("synopsis").ifEmpty { null },
			chapters = parseChapters(item.optJSONArray("chapters"), slug),
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val parts = chapter.url.trim('/').split('/')
		if (parts.size < 4 || parts[0] != "series" || parts[2] != "chapters") {
			return error("Voratoon: invalid chapter url: ${chapter.url}")
		}
		val slug = parts[1]
		val chapterId = parts[3].toLongOrNull() ?: return emptyList()
		val json = webClient.httpGet(buildDetailsUrl(slug)).parseJson()
		val item = json.optJSONArray("data")?.optJSONObject(0) ?: return emptyList()
		val chapters = item.optJSONArray("chapters") ?: return emptyList()
		for (i in 0 until chapters.length()) {
			val jo = chapters.optJSONObject(i) ?: continue
			if (jo.optLong("id") != chapterId) continue
			val images = jo.optJSONObject("dataImages") ?: return emptyList()
			val keys = images.keys().asSequence().sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }.toList()
			val result = ArrayList<MangaPage>(keys.size)
			keys.forEach { key ->
				val url = images.optString(key)
				if (url.isNotBlank()) {
					result.add(MangaPage(id = generateUid(url), url = url, preview = null, source = source))
				}
			}
			return result
		}
		return emptyList()
	}

	private fun buildDetailsUrl(slug: String): String = buildString {
		append("https://").append(domain).append("/series")
		append("?take=1&page=1&includeMeta=true&takeChapter=100")
		append("&filter=slug%3D%3D").append(slug.urlEncoded())
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
			publicUrl = "https://v1.voratoon.com/series/$slug",
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
			val data = jo.optJSONObject("data")
			result.add(
				MangaChapter(
					id = generateUid(id),
					title = data?.optString("title")?.takeIf { it.isNotBlank() },
					number = jo.optDouble("chapterIndex", 0.0).toFloat(),
					volume = 0,
					url = "/series/$slug/chapters/$id",
					scanlator = null,
					uploadDate = parseChapterDate(jo.optString("createdAt")),
					branch = null,
					source = source,
				),
			)
		}
		return result
	}

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
		"canceled" -> MangaState.ABANDONED
		else -> null
	}

	private fun MangaState.toApiValue(): String = when (this) {
		MangaState.ONGOING -> "ongoing"
		MangaState.FINISHED -> "completed"
		MangaState.PAUSED -> "hiatus"
		MangaState.ABANDONED -> "canceled"
		else -> ""
	}

	private fun parseAuthors(author: String): Set<String> {
		if (author.isBlank()) return emptySet()
		return setOf(author.trim())
	}
}
