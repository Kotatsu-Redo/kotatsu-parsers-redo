package org.koitharu.kotatsu.parsers.site.en

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import org.jsoup.Jsoup
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.model.YEAR_UNKNOWN
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.json.asTypedList
import org.koitharu.kotatsu.parsers.util.json.getFloatOrDefault
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.json.mapJSON
import org.koitharu.kotatsu.parsers.util.json.mapJSONNotNull
import org.koitharu.kotatsu.parsers.util.mapNotNullToSet
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.oneOrThrowIfMany
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.parseSafe
import org.koitharu.kotatsu.parsers.util.src
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.toTitleCase
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("KAPPABEAST", "KappaBeast", "en")
internal class KappaBeast(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.KAPPABEAST, 20) {

	override val configKeyDomain = ConfigKey.Domain("kappabeast.com")

	private val cdnUrl: String
		get() = "https://strapi.$domain"

	private val apiUrl: String
		get() = "$cdnUrl/api"

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.NEWEST,
		SortOrder.ALPHABETICAL,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isMultipleTagsSupported = true,
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isYearSupported = true,
		)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions(
		availableTags = fetchAvailableTags(),
		availableStates = EnumSet.of(
			MangaState.ONGOING,
			MangaState.FINISHED,
			MangaState.PAUSED,
		),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val urlBuilder = "$apiUrl/mangas".toHttpUrl().newBuilder()
			.addQueryParameter("pagination[page]", page.toString())
			.addQueryParameter("pagination[pageSize]", pageSize.toString())
			.addQueryParameter("populate[media][populate]", "*")
			.addQueryParameter("populate[category][fields][0]", "name")

		val query = filter.query?.trim()
		if (!query.isNullOrEmpty()) {
			urlBuilder.addQueryParameter("filters[title][\$containsi]", query)
		}

		filter.tags.forEachIndexed { index, tag ->
			urlBuilder.addQueryParameter("filters[category][name][\$in][$index]", tag.key)
		}

		if (filter.year != YEAR_UNKNOWN) {
			urlBuilder.addQueryParameter("filters[releaseYear][\$eq]", filter.year.toString())
		}

		filter.states.oneOrThrowIfMany()?.let { state ->
			val statusVal = when (state) {
				MangaState.ONGOING -> "Ongoing"
				MangaState.FINISHED -> "Completed"
				MangaState.PAUSED -> "Hiatus"
				else -> null
			}
			if (statusVal != null) {
				urlBuilder.addQueryParameter("filters[manga_status][\$eq]", statusVal)
			}
		}

		val sortParam = when (order) {
			SortOrder.UPDATED -> "updatedAt:desc"
			SortOrder.NEWEST -> "createdAt:desc"
			SortOrder.ALPHABETICAL -> "title:asc"
			SortOrder.POPULARITY -> null
			else -> "updatedAt:desc"
		}
		if (sortParam != null) {
			urlBuilder.addQueryParameter("sort[0]", sortParam)
		}

		val json = webClient.httpGet(urlBuilder.build()).parseJson()
		return json.getJSONArray("data").mapJSON { parseManga(it) }
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val slug = manga.url.removePrefix("/series/").trimEnd('/')
		val url = "$apiUrl/mangas".toHttpUrl().newBuilder()
			.addQueryParameter("filters[slug][\$eq]", slug)
			.addQueryParameter("populate[media][populate]", "*")
			.addQueryParameter("populate[category][fields][0]", "name")
			.addQueryParameter("populate[chapters][fields][0]", "number")
			.addQueryParameter("populate[chapters][fields][1]", "title")
			.addQueryParameter("populate[chapters][fields][2]", "createdAt")
			.addQueryParameter("populate[chapters][fields][3]", "documentId")
			.addQueryParameter("populate[chapters][sort][0]", "number:asc")
			.addQueryParameter("pagination[pageSize]", "1")
			.build()

		val item = webClient.httpGet(url).parseJson()
			.getJSONArray("data")
			.optJSONObject(0)
			?: throw NoSuchElementException("Manga not found: $slug")

		val base = parseManga(item)
		val mangaDocId = item.getString("documentId")
		val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH)
		val rawChapters = item.optJSONArray("chapters")?.asTypedList<JSONObject>().orEmpty()

		val seenNumbers = HashSet<Float>(rawChapters.size)
		val chapters = rawChapters.mapIndexed { idx, ch ->
			val rawNum = ch.getFloatOrDefault("number", idx + 1f)
			val num = if (seenNumbers.add(rawNum)) rawNum else idx + 1f
			val numLabel = if (rawNum % 1f == 0f) rawNum.toInt().toString() else rawNum.toString()
			val chDocId = ch.getStringOrNull("documentId").orEmpty()
			val rawTitle = ch.getStringOrNull("title")?.trim()?.nullIfEmpty()
			val fullTitle = buildString {
				append("Chapter ")
				append(numLabel)
				if (rawTitle != null && !rawTitle.equals("Chapter $numLabel", ignoreCase = true)) {
					append(" - ")
					append(rawTitle)
				}
			}
			val dateStr = ch.getStringOrNull("createdAt")?.substringBefore('T')
			val chUrl = "/reader/$slug/$numLabel#$mangaDocId:$chDocId"
			MangaChapter(
				id = generateUid(chUrl),
				title = fullTitle,
				number = num,
				volume = 0,
				url = chUrl,
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

		return base.copy(chapters = chapters)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val fragment = chapter.url.substringAfter('#', "")
		val mangaDocId = fragment.substringBefore(':')
		val chDocId = fragment.substringAfter(':', "")
		val chNum = chapter.url.substringBefore('#').substringAfterLast('/')

		val htmlContent = if (chDocId.isNotEmpty()) {
			runCatching {
				webClient.httpGet("$apiUrl/chapters/$chDocId")
					.parseJson()
					.optJSONObject("data")
					?.getStringOrNull("htmlContent")
			}.getOrNull()
		} else {
			null
		} ?: run {
			val fallbackUrl = "$apiUrl/chapters".toHttpUrl().newBuilder()
				.addQueryParameter("filters[manga][documentId][\$eq]", mangaDocId)
				.addQueryParameter("filters[number][\$eq]", chNum)
				.addQueryParameter("pagination[pageSize]", "1")
				.build()
			webClient.httpGet(fallbackUrl)
				.parseJson()
				.optJSONArray("data")
				?.optJSONObject(0)
				?.getStringOrNull("htmlContent")
				.orEmpty()
		}

		val doc = Jsoup.parseBodyFragment(htmlContent, "https://$domain/")
		val links = doc.select("div.separator > a[href]").mapNotNull { a ->
			upgradeBloggerUrl(a.absUrl("href").ifBlank { a.attr("href") })
		}.ifEmpty {
			doc.select("img").mapNotNull { img ->
				img.src()?.let(::upgradeBloggerUrl)
			}
		}

		return links.map { imgUrl ->
			MangaPage(
				id = generateUid(imgUrl),
				url = imgUrl,
				preview = null,
				source = source,
			)
		}
	}

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		val url = "$apiUrl/categories".toHttpUrl().newBuilder()
			.addQueryParameter("pagination[pageSize]", "100")
			.addQueryParameter("filters[manga][id][\$notNull]", "true")
			.build()
		val json = webClient.httpGet(url).parseJson()
		val seenTitles = HashSet<String>()
		return json.getJSONArray("data").mapJSONNotNull { cat ->
			val name = cat.getStringOrNull("name")?.trim()?.nullIfEmpty() ?: return@mapJSONNotNull null
			val title = name.toTitleCase(sourceLocale)
			if (!seenTitles.add(title.lowercase(sourceLocale))) return@mapJSONNotNull null
			MangaTag(
				key = name,
				title = title,
				source = source,
			)
		}.toSet()
	}

	private fun parseManga(jo: JSONObject): Manga {
		val slug = jo.getString("slug")
		val url = "/series/$slug"
		val coverImageObj = jo.optJSONArray("media")
			?.optJSONObject(0)
			?.optJSONObject("coverImage")
		val formatsObj = coverImageObj?.optJSONObject("formats")
		val thumbRel = formatsObj?.optJSONObject("small")?.getStringOrNull("url")?.nullIfEmpty()
			?: formatsObj?.optJSONObject("medium")?.getStringOrNull("url")?.nullIfEmpty()
			?: coverImageObj?.getStringOrNull("url")?.nullIfEmpty()
		val fullRel = formatsObj?.optJSONObject("large")?.getStringOrNull("url")?.nullIfEmpty()
			?: coverImageObj?.getStringOrNull("url")?.nullIfEmpty()
		val coverUrl = thumbRel?.let { if (it.startsWith("http")) it else "$cdnUrl$it" }
		val largeCoverUrl = fullRel?.let { if (it.startsWith("http")) it else "$cdnUrl$it" }
			?.takeIf { it != coverUrl }
		val author = jo.getStringOrNull("author")?.trim()?.nullIfEmpty()
		val artist = jo.getStringOrNull("artist")?.trim()?.nullIfEmpty()
		val tags = jo.optJSONArray("category")?.mapJSONNotNull { c ->
			val name = c.getStringOrNull("name")?.trim()?.nullIfEmpty() ?: return@mapJSONNotNull null
			MangaTag(
				key = name,
				title = name.toTitleCase(sourceLocale),
				source = source,
			)
		}?.toSet().orEmpty()
		val isAdult = tags.any { it.key.equals("Adult", true) || it.key.equals("Smut", true) || it.key.equals("Mature", true) }

		val altTitles = jo.getStringOrNull("altTitle")
			?.split(',')
			?.mapNotNullToSet { it.trim().nullIfEmpty() }
			.orEmpty()

		return Manga(
			id = generateUid(url),
			url = url,
			publicUrl = url.toAbsoluteUrl(domain),
			coverUrl = coverUrl,
			largeCoverUrl = largeCoverUrl,
			title = jo.getString("title").trim(),
			altTitles = altTitles,
			rating = RATING_UNKNOWN,
			contentRating = if (isAdult) ContentRating.ADULT else null,
			tags = tags,
			state = when (jo.getStringOrNull("manga_status")) {
				"Ongoing" -> MangaState.ONGOING
				"Completed" -> MangaState.FINISHED
				"Hiatus" -> MangaState.PAUSED
				"Dropped", "Cancelled" -> MangaState.ABANDONED
				else -> null
			},
			authors = setOfNotNull(author, artist),
			description = jo.getStringOrNull("description")?.trim().orEmpty(),
			source = source,
		)
	}

	private fun upgradeBloggerUrl(rawUrl: String): String? {
		val trimmed = rawUrl.trim().nullIfEmpty() ?: return null
		val parsed = trimmed.toHttpUrlOrNull() ?: return trimmed
		if ((parsed.host.endsWith("googleusercontent.com") || parsed.host.endsWith("blogspot.com")) &&
			parsed.pathSegments.size >= 5 &&
			BLOGGER_SIZE_REGEX.matches(parsed.pathSegments[4])
		) {
			return parsed.newBuilder().setPathSegment(4, "s0").build().toString()
		}
		return trimmed
	}

	private companion object {
		val ISO_DATE_REGEX = Regex("""^\d{4}-\d{2}-\d{2}$""")
		val BLOGGER_SIZE_REGEX = Regex("""^[sw]\d+.*$""")
	}
}
