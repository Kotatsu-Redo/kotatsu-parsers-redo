package org.koitharu.kotatsu.parsers.site.mangahub

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.LinkedHashSet
import java.util.Locale

internal abstract class MangaHubParser(
	context: MangaLoaderContext,
	source: MangaParserSource,
	private val siteDomain: String,
	private val mangaSource: String,
) : PagedMangaParser(context, source, pageSize = PAGE_SIZE) {

	override val configKeyDomain = ConfigKey.Domain(siteDomain)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.POPULARITY,
		SortOrder.UPDATED,
		SortOrder.ALPHABETICAL,
		SortOrder.NEWEST,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isMultipleTagsSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = TAGS.mapTo(LinkedHashSet(TAGS.size)) { (title, key) ->
			MangaTag(
				key = key,
				title = title,
				source = source,
			)
		},
	)

	override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
		.add("Referer", "https://$domain/")
		.add("Origin", "https://$domain")
		.add("Accept", "application/json, text/plain, */*")
		.build()

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val query = filter.query?.trim().orEmpty()
		val genre = filter.tags
			.map { it.key.trim() }
			.filter { it.isNotEmpty() }
			.joinToString(",")
			.ifEmpty { "all" }
		val orderParam = when (order) {
			SortOrder.UPDATED -> "LATEST"
			SortOrder.ALPHABETICAL -> "ALPHABET"
			SortOrder.NEWEST -> "NEW"
			else -> "POPULAR"
		}

		val data = postGraphQl(
			query = searchQuery(
				mangaSource = mangaSource,
				query = query,
				genre = genre,
				order = orderParam,
				page = page,
			),
			refreshUrl = null,
		)

		val rows = data.optJSONObject("search")
			?.optJSONArray("rows")
			?: JSONArray()

		val seenSignatures = LinkedHashSet<String>(rows.length())
		val mangas = ArrayList<Manga>(rows.length())

		for (i in 0 until rows.length()) {
			val item = rows.optJSONObject(i) ?: continue
			val slug = item.optString("slug").takeIf { it.isNotBlank() } ?: continue
			val title = item.optString("title").takeIf { it.isNotBlank() } ?: continue
			val signature = buildString {
				append(item.optString("author"))
				append(item.opt("latestChapter")?.toString().orEmpty())
				append(item.optString("genres"))
			}
			if (!seenSignatures.add(signature)) {
				continue
			}
			val relativeUrl = "/manga/$slug"
			val coverPath = item.optString("image").trim().trimStart('/')
			val coverUrl = if (coverPath.isBlank()) null else "$THUMB_CDN/$coverPath"
			mangas += Manga(
				id = generateUid(relativeUrl),
				url = relativeUrl,
				publicUrl = relativeUrl.toAbsoluteUrl(domain),
				title = title,
				altTitles = emptySet(),
				rating = RATING_UNKNOWN,
				contentRating = null,
				coverUrl = coverUrl,
				largeCoverUrl = coverUrl,
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				source = source,
			)
		}
		return mangas
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val slug = manga.url.substringAfterLast('/')
		val mangaPublicUrl = manga.url.toAbsoluteUrl(domain)

		val detailsData = postGraphQl(
			query = mangaDetailsQuery(mangaSource = mangaSource, slug = slug),
			refreshUrl = mangaPublicUrl,
		)
		val chapterData = postGraphQl(
			query = mangaChapterListQuery(mangaSource = mangaSource, slug = slug),
			refreshUrl = mangaPublicUrl,
		)

		val rawManga = detailsData.optJSONObject("manga")
		val rawChapters = chapterData.optJSONObject("manga")
			?.optJSONArray("chapters")
			?: JSONArray()

		val title = rawManga?.optString("title").takeUnless { it.isNullOrBlank() } ?: manga.title
		val coverPath = rawManga?.optString("image").orEmpty().trim().trimStart('/')
		val coverUrl = if (coverPath.isBlank()) manga.coverUrl else "$THUMB_CDN/$coverPath"
		val status = when (rawManga?.optString("status")?.lowercase(Locale.ROOT)) {
			"ongoing" -> MangaState.ONGOING
			"completed" -> MangaState.FINISHED
			else -> manga.state
		}

		val tags = rawManga?.optString("genres")
			?.split(',')
			.orEmpty()
			.mapNotNull { raw ->
				val name = raw.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
				MangaTag(
					key = name.lowercase(Locale.ROOT).replace("[^a-z0-9]+".toRegex(), "-").trim('-'),
					title = name,
					source = source,
				)
			}.toSet()

		val chapters = ArrayList<MangaChapter>(rawChapters.length())
		for (i in 0 until rawChapters.length()) {
			val chapter = rawChapters.optJSONObject(i) ?: continue
			val chapterNumber = chapter.optDouble("number", Double.NaN)
				.takeUnless(Double::isNaN)?.toFloat() ?: continue
			val numberText = chapterNumber.toNumberText()
			val titleText = chapter.optString("title").trim()
			val chapterTitle = if (titleText.isBlank()) {
				"Chapter $numberText"
			} else if (titleText.contains(numberText)) {
				titleText
			} else {
				"Chapter $numberText - $titleText"
			}
			val chapterUrl = "$slug$CHAPTER_URL_DELIMITER$chapterNumber"
			val chapterDate = DATE_FORMAT.parseSafe(chapter.optString("date"))
			chapters += MangaChapter(
				id = generateUid(chapterUrl),
				title = chapterTitle,
				number = chapterNumber,
				volume = 0,
				url = chapterUrl,
				scanlator = null,
				uploadDate = chapterDate,
				branch = null,
				source = source,
			)
		}
		chapters.sortBy { it.number }

		val description = buildString {
			rawManga?.optString("description")
				?.takeIf { it.isNotBlank() }
				?.let(::append)
			rawManga?.optString("alternativeTitle")
				?.takeIf { it.isNotBlank() }
				?.let { alt ->
					if (isNotBlank()) append("\n\n")
					append("Alternative Name: ")
					append(alt)
				}
		}.ifBlank { manga.description }

		val authors = linkedSetOf<String>().apply {
			rawManga?.optString("author")?.takeIf { it.isNotBlank() }?.let(::add)
			rawManga?.optString("artist")?.takeIf { it.isNotBlank() }?.let(::add)
		}

		return manga.copy(
			title = title,
			publicUrl = mangaPublicUrl,
			coverUrl = coverUrl,
			largeCoverUrl = coverUrl,
			description = description,
			state = status,
			authors = if (authors.isEmpty()) manga.authors else authors,
			tags = if (tags.isNullOrEmpty()) manga.tags else tags,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val splitIndex = chapter.url.indexOf(CHAPTER_URL_DELIMITER)
		if (splitIndex < 1) {
			return emptyList()
		}
		val slug = chapter.url.substring(0, splitIndex)
		val chapterNumber = chapter.url.substring(splitIndex + CHAPTER_URL_DELIMITER.length).toFloatOrNull()
			?: return emptyList()

		val refreshUrl = "https://$domain/chapter/$slug/chapter-${chapterNumber.toNumberText()}"
		val data = postGraphQl(
			query = pagesQuery(
				mangaSource = mangaSource,
				slug = slug,
				number = chapterNumber,
			),
			refreshUrl = refreshUrl,
		)
		val chapterObj = data.optJSONObject("chapter") ?: return emptyList()
		val pagesRaw = chapterObj.optString("pages").takeIf { it.isNotBlank() } ?: return emptyList()
		val pagesJson = runCatching { JSONObject(pagesRaw) }.getOrNull() ?: return emptyList()
		val pagePrefix = pagesJson.optString("p").trim().trimStart('/')
		val images = pagesJson.optJSONArray("i") ?: JSONArray()

		val pages = ArrayList<MangaPage>(images.length())
		for (i in 0 until images.length()) {
			val image = images.optString(i).trim().trimStart('/')
			if (image.isEmpty()) continue
			val imageUrl = "$IMG_CDN/$pagePrefix$image"
			pages += MangaPage(
				id = generateUid(imageUrl),
				url = imageUrl,
				preview = null,
				source = source,
			)
		}
		return pages
	}

	private suspend fun postGraphQl(query: String, refreshUrl: String?): JSONObject {
		var mhubAccess = ensureMhubAccessCookie(refreshUrl)
		repeat(2) { attempt ->
			val payload = JSONObject().put("query", query)
			val response = webClient.httpPost(
				GRAPHQL_ENDPOINT.toHttpUrl(),
				payload,
				getGraphQlHeaders(mhubAccess),
			).parseJson()

			val errors = response.optJSONArray("errors")
			if (errors == null || errors.length() == 0) {
				return response.optJSONObject("data")
					?: throw ParseException("MangaHub API response has no data", GRAPHQL_ENDPOINT)
			}

			val message = buildString {
				for (i in 0 until errors.length()) {
					val item = errors.optJSONObject(i)
					val text = item?.optString("message").orEmpty()
					if (text.isBlank()) continue
					if (isNotBlank()) append('\n')
					append(text)
				}
			}.ifBlank { errors.toString() }

			val isRecoverable = message.contains("rate limit", ignoreCase = true) ||
				message.contains("api key", ignoreCase = true) ||
				message.contains("mhub_access", ignoreCase = true)

			if (attempt == 0 && isRecoverable) {
				refreshMhubAccessCookie(refreshUrl)
				mhubAccess = ensureMhubAccessCookie(refreshUrl)
				return@repeat
			}
			throw ParseException(message, GRAPHQL_ENDPOINT)
		}
		throw ParseException("MangaHub API request failed", GRAPHQL_ENDPOINT)
	}

	private fun getGraphQlHeaders(mhubAccess: String): Headers = getRequestHeaders().newBuilder()
		.set("Accept", "application/json")
		.set("Content-Type", "application/json")
		.set("x-mhub-access", mhubAccess)
		.build()

	private suspend fun ensureMhubAccessCookie(refreshUrl: String?): String {
		val existing = getMhubAccessCookie()
		if (!existing.isNullOrBlank()) {
			return existing
		}
		refreshMhubAccessCookie(refreshUrl)
		return getMhubAccessCookie()
			?.takeIf { it.isNotBlank() }
			?: throw ParseException("MangaHub access cookie is missing", "https://$domain/")
	}

	private suspend fun refreshMhubAccessCookie(refreshUrl: String?) {
		val target = refreshUrl?.takeIf { it.isNotBlank() } ?: "https://$domain/"
		runCatching {
			webClient.httpGet(target.toHttpUrl(), getRequestHeaders()).close()
		}.onFailure {
			webClient.httpGet("https://$domain/").close()
		}
	}

	private fun getMhubAccessCookie(): String? {
		return context.cookieJar.getCookies(domain)
			.firstOrNull { it.name == "mhub_access" && it.value.isNotBlank() }
			?.value
	}

	private fun String.gqlEscape(): String {
		return replace("\\", "\\\\")
			.replace("\"", "\\\"")
			.replace("\n", " ")
			.replace("\r", " ")
	}

	private fun Float.toNumberText(): String {
		return if (this % 1f == 0f) this.toInt().toString() else this.toString()
	}

	private fun searchQuery(
		mangaSource: String,
		query: String,
		genre: String,
		order: String,
		page: Int,
	): String = """
		{
			search(x: $mangaSource, q: "${query.gqlEscape()}", genre: "${genre.gqlEscape()}", mod: $order, offset: ${(page - 1) * PAGE_SIZE}) {
				rows {
					title,
					author,
					slug,
					image,
					genres,
					latestChapter
				}
			}
		}
	""".trimIndent()

	private fun mangaDetailsQuery(mangaSource: String, slug: String): String = """
		{
			manga(x: $mangaSource, slug: "${slug.gqlEscape()}") {
				title,
				slug,
				status,
				image,
				author,
				artist,
				genres,
				description,
				alternativeTitle
			}
		}
	""".trimIndent()

	private fun mangaChapterListQuery(mangaSource: String, slug: String): String = """
		{
			manga(x: $mangaSource, slug: "${slug.gqlEscape()}") {
				slug,
				chapters {
					number,
					title,
					date
				}
			}
		}
	""".trimIndent()

	private fun pagesQuery(mangaSource: String, slug: String, number: Float): String = """
		{
			chapter(x: $mangaSource, slug: "${slug.gqlEscape()}", number: $number) {
				pages,
				mangaID,
				number,
				manga {
					slug
				}
			}
		}
	""".trimIndent()

	private companion object {
		private const val PAGE_SIZE = 30
		private const val GRAPHQL_ENDPOINT = "https://api.mghcdn.com/graphql"
		private const val IMG_CDN = "https://imgx.mghcdn.com"
		private const val THUMB_CDN = "https://thumb.mghcdn.com"
		private const val CHAPTER_URL_DELIMITER = "::"
		private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ENGLISH)

		private val TAGS = listOf(
			"4-Koma" to "4-koma",
			"Action" to "action",
			"Adaptation" to "adaptation",
			"Adventure" to "adventure",
			"Adult" to "adult",
			"Aliens" to "aliens",
			"Animals" to "animals",
			"Anthology" to "anthology",
			"Award Winning" to "award-winning",
			"Comedy" to "comedy",
			"Cooking" to "cooking",
			"Crime" to "crime",
			"Crossdressing" to "crossdressing",
			"Cultivation" to "cultivation",
			"Demons" to "demons",
			"Doujinshi" to "doujinshi",
			"Drama" to "drama",
			"Dungeons" to "dungeons",
			"Ecchi" to "ecchi",
			"Fantasy" to "fantasy",
			"Food" to "food",
			"Full Color" to "full-color",
			"Game" to "game",
			"Gender bender" to "gender-bender",
			"Ghosts" to "ghosts",
			"Gore" to "gore",
			"Harem" to "harem",
			"Harlequin" to "harlequin",
			"Historical" to "historical",
			"Horror" to "horror",
			"Isekai" to "isekai",
			"Josei" to "josei",
			"Magic" to "magic",
			"Manga" to "manga",
			"Manhua" to "manhua",
			"Manhwa" to "manhwa",
			"Martial Arts" to "martial-arts",
			"Mature" to "mature",
			"Medical" to "medical",
			"Mecha" to "mecha",
			"Military" to "military",
			"Monsters" to "monsters",
			"Music" to "music",
			"Mystery" to "mystery",
			"Office Workers" to "office-workers",
			"Oneshot" to "oneshot",
			"Overpowered" to "overpowered",
			"Philosophical" to "philosophical",
			"Police" to "police",
			"Post-Apocalyptic" to "post-apocalyptic",
			"Psychological" to "psychological",
			"R-18" to "r-18",
			"Rebirth" to "rebirth",
			"Reincarnation" to "reincarnation",
			"Reverse Harem" to "reverse-harem",
			"Revenge" to "revenge",
			"Romance" to "romance",
			"Samurai" to "samurai",
			"School Life" to "school-life",
			"Sci-Fi" to "sci-fi",
			"Seinen" to "seinen",
			"Shoujo" to "shoujo",
			"Shoujo ai" to "shoujo-ai",
			"Shounen" to "shounen",
			"Shounen ai" to "shounen-ai",
			"Slice of life" to "slice-of-life",
			"Smut" to "smut",
			"Sports" to "sports",
			"Super Power" to "super-power",
			"Superhero" to "superhero",
			"Supernatural" to "supernatural",
			"Survival" to "survival",
			"System" to "system",
			"Thriller" to "thriller",
			"Time Travel" to "time-travel",
			"Tragedy" to "tragedy",
			"Vampires" to "vampires",
			"Villainess" to "villainess",
			"Violence" to "violence",
			"Web Comic" to "web-comic",
			"Webtoon" to "webtoon",
			"Wuxia" to "wuxia",
			"Xianxia" to "xianxia",
			"Xuanhuan" to "xuanhuan",
			"Yaoi" to "yaoi",
			"Yuri" to "yuri",
			"Zombies" to "zombies",
		)
	}
}
