package org.koitharu.kotatsu.parsers.site.iken.en

import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.site.iken.IkenParser
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.json.asTypedList
import org.koitharu.kotatsu.parsers.util.json.getBooleanOrDefault
import org.koitharu.kotatsu.parsers.util.json.getFloatOrDefault
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.json.mapJSONNotNull
import org.koitharu.kotatsu.parsers.util.json.mapJSONToSet
import org.koitharu.kotatsu.parsers.util.mapChapters
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.oneOrThrowIfMany
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.parseJsonArray
import org.koitharu.kotatsu.parsers.util.parseSafe
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.toTitleCase
import org.koitharu.kotatsu.parsers.util.urlEncoded
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("SANASCANS", "Sana Scans", "en")
internal class SanaScans(context: MangaLoaderContext) :
	IkenParser(context, MangaParserSource.SANASCANS, "sanascans.com", 18, true) {

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.NEWEST,
		SortOrder.ALPHABETICAL,
		SortOrder.ALPHABETICAL_DESC,
	)

	override suspend fun getFilterOptions(): MangaListFilterOptions = super.getFilterOptions().copy(
		availableContentTypes = EnumSet.of(
			ContentType.MANGA,
			ContentType.MANHWA,
		),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val (orderBy, orderDirection) = when (order) {
			SortOrder.UPDATED -> "updatedAt" to "desc"
			SortOrder.POPULARITY -> "totalViews" to "desc"
			SortOrder.NEWEST -> "createdAt" to "desc"
			SortOrder.ALPHABETICAL -> "postTitle" to "asc"
			SortOrder.ALPHABETICAL_DESC -> "postTitle" to "desc"
			else -> "updatedAt" to "desc"
		}
		val url = buildString {
			append("https://")
			append(defaultDomain)
			append("/api/query?page=")
			append(page)
			append("&perPage=")
			append(pageSize)
			append("&searchTerm=")
			filter.query?.trim()?.let { append(it.urlEncoded()) }
			if (filter.tags.isNotEmpty()) {
				append("&genreIds=")
				filter.tags.joinTo(this, ",") { it.key }
			}
			append("&seriesType=")
			filter.types.oneOrThrowIfMany()?.let {
				append(
					when (it) {
						ContentType.MANGA -> "MANGA"
						ContentType.MANHWA -> "MANHWA"
						else -> ""
					},
				)
			}
			append("&seriesStatus=")
			filter.states.oneOrThrowIfMany()?.let {
				append(
					when (it) {
						MangaState.ONGOING -> "ONGOING"
						MangaState.FINISHED -> "COMPLETED"
						MangaState.UPCOMING -> "COMING_SOON"
						MangaState.ABANDONED -> "DROPPED"
						else -> ""
					},
				)
			}
			append("&orderBy=")
			append(orderBy)
			append("&orderDirection=")
			append(orderDirection)
		}
		return parseMangaList(webClient.httpGet(url, apiHeaders).parseJson())
	}

	override suspend fun fetchAvailableTags(): Set<MangaTag> {
		val array = webClient.httpGet("https://$defaultDomain/api/genres", apiHeaders).parseJsonArray()
		return array.mapJSONToSet { jo ->
			MangaTag(
				key = jo.getInt("id").toString(),
				title = jo.getString("name").trim().toTitleCase(sourceLocale),
				source = source,
			)
		}
	}

	override fun parseMangaList(json: JSONObject): List<Manga> {
		return json.getJSONArray("posts").mapJSONNotNull { jo ->
			if (jo.getBooleanOrDefault("isNovel", false) ||
				jo.getStringOrNull("seriesType").equals("NOVEL", ignoreCase = true)
			) {
				return@mapJSONNotNull null
			}
			val slug = jo.getString("slug")
			val url = "/series/$slug"
			val isAdult = jo.getBooleanOrDefault("adult", false)
			val author = jo.getStringOrNull("author")?.trim()?.nullIfEmpty()
			val description = jo.getStringOrNull("postContent")
				?: jo.getStringOrNull("description")
				?: ""
			Manga(
				id = generateUid(url),
				url = url,
				publicUrl = url.toAbsoluteUrl(domain),
				coverUrl = jo.getStringOrNull("featuredImage")?.nullIfEmpty(),
				title = jo.getString("postTitle").trim(),
				altTitles = parseAltTitles(jo.getStringOrNull("alternativeTitles")),
				description = description,
				rating = RATING_UNKNOWN,
				tags = parseGenres(jo),
				authors = setOfNotNull(author),
				state = parseSeriesState(jo.getStringOrNull("seriesStatus")),
				source = source,
				contentRating = if (isAdult) ContentRating.ADULT else null,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val rawSlug = manga.url.removePrefix("/series/").trimEnd('/')
		val postJson = webClient.httpGet(
			"https://$defaultDomain/api/post?postSlug=${rawSlug.urlEncoded()}",
			apiHeaders,
		).parseJson().getJSONObject("post")

		val postId = postJson.getLong("id")
		val slug = postJson.getStringOrNull("slug")?.nullIfEmpty() ?: rawSlug
		val url = "/series/$slug"
		val author = postJson.getStringOrNull("author")?.trim()?.nullIfEmpty()
		val artist = postJson.getStringOrNull("artist")?.trim()?.nullIfEmpty()
		val description = postJson.getStringOrNull("postContent")
			?: postJson.getStringOrNull("description")
			?: manga.description.orEmpty()

		val chaptersResp = webClient.httpGet(
			"https://$defaultDomain/api/chapters?postId=$postId&skip=0&take=900&order=desc&userid=",
			apiHeaders,
		).parseJson().getJSONObject("post")
		val rawChapters = chaptersResp.getJSONArray("chapters").asTypedList<JSONObject>()
			.filter { !it.getBooleanOrDefault("isLocked", false) && it.opt("isAccessible") != false }

		val dateFormat = SimpleDateFormat(datePattern, Locale.ENGLISH)
		val chapters = rawChapters.mapChapters(reversed = true) { _, ch ->
			val chapterSlug = ch.getString("slug")
			val num = ch.getFloatOrDefault("number", 0f)
			val chTitle = ch.getStringOrNull("title")?.trim()?.nullIfEmpty()
			val dateStr = ch.getStringOrNull("createdAt")?.substringBefore('T')
			MangaChapter(
				id = ch.getLong("id"),
				title = chTitle,
				number = num,
				volume = 0,
				url = "/series/$slug/$chapterSlug",
				scanlator = null,
				uploadDate = if (dateStr != null && ISO_DATE_REGEX.matches(dateStr)) {
					dateFormat.parseSafe(dateStr)
				} else {
					0L
				},
				branch = null,
				source = source,
			)
		}

		return manga.copy(
			id = generateUid(url),
			url = url,
			publicUrl = url.toAbsoluteUrl(domain),
			title = postJson.getStringOrNull("postTitle")?.trim()?.nullIfEmpty() ?: manga.title,
			altTitles = parseAltTitles(postJson.getStringOrNull("alternativeTitles")).ifEmpty { manga.altTitles },
			coverUrl = postJson.getStringOrNull("featuredImage")?.nullIfEmpty() ?: manga.coverUrl,
			description = description,
			tags = parseGenres(postJson).ifEmpty { manga.tags },
			authors = setOfNotNull(author, artist).ifEmpty { manga.authors },
			state = parseSeriesState(postJson.getStringOrNull("seriesStatus")) ?: manga.state,
			chapters = chapters,
		)
	}

	private fun parseGenres(jo: JSONObject): Set<MangaTag> {
		val arr = jo.optJSONArray("genres") ?: return emptySet()
		return arr.mapJSONNotNull { g ->
			val id = g.optInt("id", -1)
			val name = g.getStringOrNull("name")?.trim()?.nullIfEmpty() ?: return@mapJSONNotNull null
			MangaTag(
				key = if (id > 0) id.toString() else name.lowercase(sourceLocale),
				title = name.toTitleCase(sourceLocale),
				source = source,
			)
		}.toSet()
	}

	private fun parseAltTitles(raw: String?): Set<String> {
		if (raw.isNullOrBlank()) return emptySet()
		return raw.split(',', ';', '|', '/')
			.mapNotNull { it.trim().nullIfEmpty() }
			.toSet()
	}

	private fun parseSeriesState(status: String?): MangaState? = when (status) {
		"ONGOING" -> MangaState.ONGOING
		"COMPLETED" -> MangaState.FINISHED
		"HIATUS" -> MangaState.PAUSED
		"DROPPED", "CANCELLED" -> MangaState.ABANDONED
		"COMING_SOON" -> MangaState.UPCOMING
		else -> null
	}

	private companion object {
		val ISO_DATE_REGEX = Regex("""^\d{4}-\d{2}-\d{2}$""")
	}
}
