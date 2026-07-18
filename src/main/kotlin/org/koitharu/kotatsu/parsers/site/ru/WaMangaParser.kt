package org.koitharu.kotatsu.parsers.site.ru

import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("WAMANGA", "WaManga", "ru", type = ContentType.MANGA)
internal class WaMangaParser(
	context: MangaLoaderContext,
) : PagedMangaParser(context, MangaParserSource.WAMANGA, pageSize = 20) {

	override val configKeyDomain = ConfigKey.Domain("wamanga.ru")

	// The API returns the catalog in alphabetical order; no server-side sorting.
	override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.ALPHABETICAL)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions()

	private fun apiUrl() = "https://${domain}/api/v1"

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val offset = (page - 1) * pageSize
		val url = buildString {
			append(apiUrl())
			append("/manga?limit=")
			append(pageSize)
			append("&offset=")
			append(offset)
			filter.query?.takeIf { it.isNotEmpty() }?.let {
				append("&query=")
				append(it.urlEncoded())
			}
		}
		return webClient.httpGet(url).parseJsonArray().mapJSON { parseManga(it) }
	}

	private fun parseManga(jo: JSONObject): Manga {
		val id = jo.getString("id")
		val slug = jo.getString("slug")
		return Manga(
			id = generateUid(id),
			url = id,
			publicUrl = "https://${domain}/manga/$slug",
			title = jo.getString("title"),
			altTitles = collectAltTitles(jo),
			coverUrl = jo.getStringOrNull("coverUrl")?.toAbsoluteUrl(domain),
			largeCoverUrl = jo.getStringOrNull("imageUrl")?.toAbsoluteUrl(domain),
			tags = parseTags(jo),
			state = parseState(jo.getStringOrNull("statusTitle")),
			authors = jo.optJSONArray("authors")?.asTypedList<String>()?.toSet().orEmpty(),
			rating = RATING_UNKNOWN,
			contentRating = if (jo.getBooleanOrDefault("isAdult", false)) {
				ContentRating.ADULT
			} else {
				ContentRating.SAFE
			},
			source = source,
		)
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val jo = webClient.httpGet("${apiUrl()}/manga/${manga.url}").parseJson()
		val chaptersJson = webClient.httpGet("${apiUrl()}/manga/${manga.url}/chapters").parseJsonArray()
		val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", sourceLocale)
		val scanlator = jo.optJSONArray("teams")
			?.mapJSON { it.getStringOrNull("name") }
			?.filterNotNull()
			?.joinToString()
			?.takeUnless { it.isEmpty() }
		return manga.copy(
			altTitles = collectAltTitles(jo),
			description = jo.getStringOrNull("description"),
			tags = parseTags(jo),
			authors = jo.optJSONArray("authors")?.asTypedList<String>()?.toSet().orEmpty(),
			state = parseState(jo.getStringOrNull("statusTitle")),
			largeCoverUrl = jo.getStringOrNull("imageUrl")?.toAbsoluteUrl(domain) ?: manga.largeCoverUrl,
			chapters = chaptersJson.asTypedList<JSONObject>().mapChapters { _, it ->
				val chapterId = it.getString("id")
				val position = it.getFloatOrDefault("position", 0f)
				MangaChapter(
					id = generateUid(chapterId),
					url = chapterId,
					title = it.getStringOrNull("title")?.takeUnless { t -> t.isEmpty() },
					number = position,
					volume = 0,
					scanlator = scanlator,
					uploadDate = dateFormat.parseSafe(it.getStringOrNull("createdAt")),
					branch = null,
					source = source,
				)
			},
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		return webClient.httpGet("${apiUrl()}/chapters/${chapter.url}")
			.parseJson()
			.getJSONArray("files")
			.mapJSON { file ->
				val img = file.getString("diskFile").toAbsoluteUrl(domain)
				MangaPage(
					id = generateUid(img),
					url = img,
					preview = null,
					source = source,
				)
			}
	}

	private fun collectAltTitles(jo: JSONObject): Set<String> = buildSet {
		jo.getStringOrNull("titleEnglish")?.takeUnless { it.isEmpty() }?.let(::add)
		jo.optJSONArray("alternateTitles")?.asTypedList<String>()?.forEach { if (it.isNotEmpty()) add(it) }
	}

	private fun parseTags(jo: JSONObject): Set<MangaTag> {
		return jo.optJSONArray("genres")?.asTypedList<String>()?.mapNotNullToSet { name ->
			if (name.isEmpty()) {
				null
			} else {
				MangaTag(
					title = name.toTitleCase(sourceLocale),
					key = name,
					source = source,
				)
			}
		}.orEmpty()
	}

	private fun parseState(status: String?) = when (status?.lowercase(sourceLocale)) {
		"ongoing" -> MangaState.ONGOING
		"finished", "completed" -> MangaState.FINISHED
		"abandoned" -> MangaState.ABANDONED
		"paused", "frozen" -> MangaState.PAUSED
		else -> null
	}
}
