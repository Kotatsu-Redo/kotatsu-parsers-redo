package org.koitharu.kotatsu.parsers.site.id

import okhttp3.Headers
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("RYUKOMIK", "Ryukomik", "id")
internal class Ryukomik(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.RYUKOMIK, 50) {

	override val configKeyDomain = ConfigKey.Domain("ryukomik.my.id")

	// JSON API used by the website (the "komiku" catalog section)
	private val apiBase = "https://api.ryukomik.web.id/komiku"

	private val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.ROOT)

	override fun getRequestHeaders(): Headers = Headers.Builder()
		.add("Referer", "https://$domain/")
		.build()

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.ALPHABETICAL,
		SortOrder.UPDATED,
	)

	// the API cannot filter by genre or state, so the alphabetical
	// catalog is used as the default listing
	override val defaultSortOrder: SortOrder
		get() = SortOrder.ALPHABETICAL

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = false,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableContentTypes = EnumSet.of(
			ContentType.MANGA,
			ContentType.MANHWA,
			ContentType.MANHUA,
		),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val query = filter.query
		val type = filter.types.oneOrThrowIfMany()
		val json: JSONObject = when {
			!query.isNullOrEmpty() -> {
				// the search endpoint returns all matches at once
				if (page > 1) return emptyList()
				webClient.httpGet("$apiBase/search?q=${query.urlEncoded()}").parseJson()
			}

			order == SortOrder.UPDATED && type == null -> {
				// the latest endpoint is a single unpaginated list
				if (page > 1) return emptyList()
				webClient.httpGet("$apiBase/terbaru").parseJson()
			}

			else -> {
				val url = buildString {
					append(apiBase)
					append("/list?page=").append(page)
					when (type) {
						ContentType.MANGA -> append("&tipe=manga")
						ContentType.MANHWA -> append("&tipe=manhwa")
						ContentType.MANHUA -> append("&tipe=manhua")
						else -> Unit
					}
				}
				webClient.httpGet(url).parseJson()
			}
		}
		val data = json.optJSONArray("data") ?: return emptyList()
		return (0 until data.length()).mapNotNull { i -> parseManga(data.getJSONObject(i)) }
	}

	private fun parseManga(obj: JSONObject): Manga? {
		val slug = obj.optString("slug").ifBlank {
			val link = obj.optString("link").ifBlank { obj.optString("detail_link") }
			link.trimEnd('/').substringAfterLast('/')
		}
		if (slug.isBlank()) return null
		val title = obj.optString("title").trim()
		if (title.isBlank()) return null
		val url = "/manga/$slug"
		return Manga(
			id = generateUid(url),
			title = title,
			altTitles = emptySet(),
			url = url,
			publicUrl = "https://$domain/komik/komiku/$slug",
			rating = RATING_UNKNOWN,
			contentRating = ContentRating.SAFE,
			coverUrl = obj.optString("image").ifBlank { null },
			tags = emptySet(),
			state = parseState(obj.optString("status")),
			authors = emptySet(),
			source = source,
		)
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val slug = manga.url.trimEnd('/').substringAfterLast('/')
		val json = webClient.httpGet("$apiBase/detail/$slug").parseJson()
		if (!json.optBoolean("success", false)) return manga
		val data = json.optJSONObject("data") ?: return manga

		val authors = data.optString("Pengarang")
			.split(',')
			.map { it.trim() }
			.filter { it.isNotEmpty() && !it.equals("-", ignoreCase = true) }
			.toSet()
		val genres = data.optString("genres")
			.split(',')
			.map { it.trim() }
			.filter { it.isNotEmpty() }
			.toSet()
		val tags = genres.mapTo(HashSet()) {
			MangaTag(title = it, key = it.lowercase(Locale.ROOT), source = source)
		}
		val chapters = parseChapterList(data.optJSONArray("chapters"))

		val title = data.optString("title").trim()
			.removePrefix("Komik ")
			.trim()
			.ifBlank { manga.title }

		return manga.copy(
			title = title,
			description = data.optString("synopsis").trim().ifBlank { null },
			state = parseState(data.optString("status")),
			authors = authors,
			tags = tags,
			coverUrl = data.optString("thumbnail").ifBlank { manga.coverUrl },
			chapters = chapters,
		)
	}

	private fun parseChapterList(arr: JSONArray?): List<MangaChapter> {
		if (arr == null) return emptyList()
		return (0 until arr.length()).mapNotNull { i ->
			val obj = arr.getJSONObject(i)
			val slug = obj.optString("slug").ifBlank {
				obj.optString("link").trimEnd('/').substringAfterLast('/')
			}
			if (slug.isBlank()) return@mapNotNull null
			val title = obj.optString("title").trim()
			val number = Regex("""(\d+(?:\.\d+)?)""").find(
				title.ifBlank { slug },
			)?.value?.toFloatOrNull() ?: 0f
			MangaChapter(
				id = generateUid(slug),
				title = title.ifBlank { null },
				number = number,
				volume = 0,
				url = "/chapter/$slug",
				scanlator = null,
				uploadDate = dateFormat.parseSafe(obj.optString("date")),
				branch = null,
				source = source,
			)
		}.sortedBy { it.number }
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val slug = chapter.url.trimEnd('/').substringAfterLast('/')
		val json = webClient.httpGet("$apiBase/chapter/$slug").parseJson()
		if (!json.optBoolean("success", false)) return emptyList()
		val images = json.optJSONArray("images") ?: return emptyList()
		return (0 until images.length()).mapNotNull { i ->
			val url = images.optString(i)
			if (url.isBlank()) return@mapNotNull null
			MangaPage(
				id = generateUid("${chapter.url}#$i"),
				url = url,
				preview = null,
				source = source,
			)
		}
	}

	private fun parseState(text: String): MangaState? = when {
		text.contains("ongoing", ignoreCase = true) -> MangaState.ONGOING
		text.contains("completed", ignoreCase = true) ||
			text.contains("tamat", ignoreCase = true) ||
			text.contains("end", ignoreCase = true) -> MangaState.FINISHED
		text.contains("hiatus", ignoreCase = true) -> MangaState.PAUSED
		else -> null
	}
}
