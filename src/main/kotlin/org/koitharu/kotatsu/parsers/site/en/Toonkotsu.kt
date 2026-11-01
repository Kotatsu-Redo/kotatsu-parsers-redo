package org.koitharu.kotatsu.parsers.site.en

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.HttpStatusException
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy
import java.util.*
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

@MangaSourceParser("TOONKOTSU", "Toonkotsu", "en")
internal class Toonkotsu(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.TOONKOTSU, pageSize = 42) {

	override val configKeyDomain = ConfigKey.Domain("toonkotsu.com")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.RATING,
		SortOrder.NEWEST,
		SortOrder.NEWEST_ASC,
		SortOrder.ALPHABETICAL,
		SortOrder.ALPHABETICAL_DESC,
		SortOrder.ADDED,
		SortOrder.RELEVANCE,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = true,
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isYearRangeSupported = true,
			isOriginalLocaleSupported = true,
			isAuthorSearchSupported = true,
		)

	private val facets = suspendLazy {
		webClient.httpGet("https://$domain/cdn/v1/facets?sfw=0").parseJson().getJSONArray("genres")
	}

	// Without the sfw parameter the lists also have the 18+ titles, and with it these tags list nothing
	private val adultTags = suspendLazy {
		facets.get().mapJSONNotNullToSet { tag ->
			tag.getString("name").takeIf { tag.getBooleanOrDefault("isAdult", false) || it in ADULT_TAGS }
		}
	}

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = facets.get().mapJSONNotNullToSet { tag ->
			tag.getString("name").takeUnless { it in HIDDEN_TAGS }?.let(::newTag)
		},
		availableStates = EnumSet.of(
			MangaState.ONGOING,
			MangaState.FINISHED,
			MangaState.PAUSED,
			MangaState.ABANDONED,
		),
		availableContentRating = EnumSet.of(ContentRating.SAFE, ContentRating.ADULT),
		availableContentTypes = EnumSet.of(
			ContentType.MANGA,
			ContentType.MANHWA,
			ContentType.MANHUA,
			ContentType.COMICS,
			ContentType.OTHER,
		),
		availableDemographics = EnumSet.of(
			Demographic.SHOUNEN,
			Demographic.SHOUJO,
			Demographic.SEINEN,
			Demographic.JOSEI,
			Demographic.NONE,
		),
		availableLocales = setOf(Locale.JAPANESE, Locale.KOREAN, Locale.CHINESE, Locale.ENGLISH),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = urlBuilder().addPathSegments("cdn/v1/browse").apply {
			addQueryParameter(
				"sort",
				when (order) {
					SortOrder.POPULARITY -> "popular"
					SortOrder.RATING -> "score"
					SortOrder.NEWEST, SortOrder.NEWEST_ASC -> "year"
					SortOrder.ALPHABETICAL, SortOrder.ALPHABETICAL_DESC -> "title"
					SortOrder.ADDED -> "new"
					SortOrder.RELEVANCE -> "relevance"
					else -> "latest"
				},
			)
			addQueryParameter(
				"order",
				if (order == SortOrder.NEWEST_ASC || order == SortOrder.ALPHABETICAL) "asc" else "desc",
			)
			addQueryParameter("page", page.toString())
			addQueryParameter("limit", pageSize.toString())
			addFilter(filter)
			val adult = ContentRating.ADULT in filter.contentRating ||
				(filter.tags.isNotEmpty() && filter.tags.any { it.key in adultTags.get() })
			if (!adult) {
				addQueryParameter("sfw", "1")
			}
		}.build()
		return webClient.httpGet(url).parseJson().getJSONArray("items").mapJSON(::parseManga)
	}

	private fun HttpUrl.Builder.addFilter(filter: MangaListFilter) {
		filter.query?.takeUnless { it.isBlank() }?.let { addQueryParameter("q", it.trim()) }
		filter.author?.takeUnless { it.isBlank() }?.let { addQueryParameter("author", it.trim()) }
		addList("genres", filter.tags.map { it.key })
		addList("exclude", filter.tagsExclude.map { it.key })
		addList(
			"status",
			filter.states.mapNotNull {
				when (it) {
					MangaState.ONGOING -> "Ongoing"
					MangaState.FINISHED -> "Complete"
					MangaState.PAUSED -> "Hiatus"
					MangaState.ABANDONED -> "Canceled"
					else -> null
				}
			},
		)
		addList(
			"type",
			filter.types.mapNotNull {
				when (it) {
					ContentType.MANGA -> "Manga"
					ContentType.MANHWA -> "Manhwa"
					ContentType.MANHUA -> "Manhua"
					ContentType.COMICS -> "OEL"
					ContentType.OTHER -> "Other"
					else -> null
				}
			},
		)
		addList(
			"demographic",
			filter.demographics.mapNotNull {
				when (it) {
					Demographic.SHOUNEN -> "Shounen"
					Demographic.SHOUJO -> "Shoujo"
					Demographic.SEINEN -> "Seinen"
					Demographic.JOSEI -> "Josei"
					Demographic.NONE -> "none"
					else -> null
				}
			},
		)
		(filter.originalLocale ?: filter.locale)?.let { addQueryParameter("originalLanguage", it.language) }
		if (filter.yearFrom != YEAR_UNKNOWN) {
			addQueryParameter("yearFrom", filter.yearFrom.toString())
		}
		if (filter.yearTo != YEAR_UNKNOWN) {
			addQueryParameter("yearTo", filter.yearTo.toString())
		}
	}

	private fun HttpUrl.Builder.addList(name: String, values: Collection<String>) {
		if (values.isNotEmpty()) {
			addQueryParameter(name, values.joinToString(","))
		}
	}

	private fun parseManga(json: JSONObject): Manga {
		val url = "/title/${json.getString("hid")}"
		val slug = json.getStringOrNull("slug")
		// The site's own links use the other id, /title/<hid> redirects to them
		val publicPath = json.getStringOrNull("code")?.let { code ->
			if (slug.isNullOrEmpty()) "/title/$code" else "/title/$code-$slug"
		} ?: url
		val spoilers = json.optJSONArray("spoilerTags")?.asTypedList<String>().orEmpty().toSet()
		return Manga(
			id = generateUid(url),
			url = url,
			publicUrl = publicPath.toAbsoluteUrl(domain),
			title = json.getString("title"),
			altTitles = emptySet(),
			coverUrl = json.getStringOrNull("cover")?.toAbsoluteUrl(domain),
			rating = json.getIntOrDefault("score", -1).let { if (it >= 0) it / 100f else RATING_UNKNOWN },
			contentRating = if (json.getBooleanOrDefault("adult", false)) ContentRating.ADULT else ContentRating.SAFE,
			tags = json.optJSONArray("tags")?.asTypedList<String>().orEmpty()
				.filterNot { it in spoilers || it in HIDDEN_TAGS }
				.mapToSet(::newTag),
			state = when (json.getStringOrNull("status")) {
				"Ongoing" -> MangaState.ONGOING
				"Complete" -> MangaState.FINISHED
				"Hiatus" -> MangaState.PAUSED
				"Canceled" -> MangaState.ABANDONED
				else -> null
			},
			authors = json.optJSONArray("authors")?.asTypedList<String>().orEmpty().toSet(),
			source = source,
		)
	}

	private fun newTag(name: String) = MangaTag(title = name, key = name, source = source)

	override suspend fun getDetails(manga: Manga): Manga {
		val id = manga.url.substringAfter("/title/").substringBefore('/').substringBefore('-')
		val data = webClient.httpGet("https://$domain/cdn/v1/series/$id").parseJson()
		val series = data.getJSONObject("series")
		val parsed = parseManga(series)
		val titles = series.optJSONObject("titles")
		val altTitles = listOfNotNull(
			titles?.getStringOrNull("native"),
			titles?.getStringOrNull("romaji"),
			titles?.getStringOrNull("english"),
		) + series.optJSONArray("alt")?.asTypedList<String>().orEmpty()
		val description = series.getStringOrNull("description")?.takeUnless { it.isBlank() }
			?: runCatchingCancellable {
				webClient.httpGet("https://$domain/api/v1/series/$id/mangabaka").parseJson()
					.getStringOrNull("description")
			}.getOrNull()
		return manga.copy(
			title = parsed.title,
			altTitles = (altTitles - parsed.title).toSet(),
			coverUrl = parsed.coverUrl ?: manga.coverUrl,
			// The size the site's own title page shows
			largeCoverUrl = parsed.coverUrl?.substringBeforeLast('/')?.let { "$it/720.webp" },
			rating = parsed.rating,
			contentRating = parsed.contentRating,
			tags = parsed.tags,
			state = parsed.state,
			authors = parsed.authors,
			description = description.orEmpty(),
			chapters = parseChapters(parsed.url, data.getJSONArray("chapters")),
		)
	}

	override suspend fun getRelatedManga(seed: Manga): List<Manga> {
		val id = seed.url.substringAfter("/title/").substringBefore('/').substringBefore('-')
		return webClient.httpGet("https://$domain/cdn/v1/series/$id").parseJson()
			.optJSONArray("recs")?.mapJSON(::parseManga).orEmpty()
	}

	// One upload per chapter and group, which is what the site's list shows, and a branch per group
	private fun parseChapters(mangaUrl: String, chapters: JSONArray): List<MangaChapter> {
		val uploads = chapters.mapJSON { Upload(it, parent = null) to it }.flatMap { (chapter, json) ->
			listOf(chapter) + json.optJSONArray("alts")?.mapJSON { Upload(it, parent = chapter) }.orEmpty()
		}
		// The site splits rows by volume when 3+ chapter numbers span several volumes
		val byVolume = uploads.filter { it.number != null && it.volumeNumber != null }
			.groupBy({ it.number }, { it.volumeNumber })
			.count { (_, volumes) -> volumes.distinct().size > 1 } >= 3
		val picks = uploads.sortedWith(compareBy<Upload>({ it.partial }, { it.parentId != null }))
			.distinctBy { it.row(byVolume, byGroup = true) }
			.toSet()
		val complete = picks.filterNot { it.partial }.mapToSet { it.row(byVolume, byGroup = false) }
		// The site hides a partial-only row when another group has the full chapter.
		// It lists the newest chapter first.
		return uploads.filter { it in picks && (!it.partial || it.row(byVolume, byGroup = false) !in complete) }
			.asReversed()
			.map { it.toChapter(mangaUrl) }
			.distinctBy { if (it.number > 0f) Triple(it.branch, it.volume, it.number) else it.url }
	}

	private inner class Upload(json: JSONObject, parent: Upload?) {
		val id: Long = json.getLong("id")
		val parentId: Long? = parent?.id
		val number: Float? = parent?.number ?: (json.opt("n") as? Number)?.toFloat()
		val volume: String? = parent?.volume ?: json.getStringOrNull("vol")?.trim()?.nullIfEmpty()
		val volumeNumber: Float? = volume?.toFloatOrNull()
		val label: String? = json.getStringOrNull("label") ?: parent?.label
		val date: Long = json.getLongOrDefault("at", parent?.date ?: 0L)
		val group: String? = json.optJSONObject("group")?.getStringOrNull("name")
		val groupKey: String? = json.optJSONObject("group")?.getStringOrNull("slug") ?: group
		val partial: Boolean = json.getBooleanOrDefault("partial", false)

		fun row(byVolume: Boolean, byGroup: Boolean): List<Any?> = listOf(
			volumeNumber?.takeIf { byVolume } ?: volume?.lowercase(Locale.ROOT)?.takeIf { byVolume },
			number ?: parentId ?: id,
			groupKey?.takeIf { byGroup },
		)

		fun toChapter(mangaUrl: String): MangaChapter {
			val url = "$mangaUrl/$id"
			return MangaChapter(
				id = generateUid(url),
				title = label,
				number = number ?: 0f,
				volume = volumeNumber?.toInt() ?: 0,
				url = url,
				scanlator = if (partial) group?.let { "$it (incomplete)" } else group,
				uploadDate = date,
				branch = group,
				source = source,
			)
		}
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val id = chapter.url.substringAfterLast('/')
		val response = try {
			webClient.httpGet("https://$domain/api/manga/chapter/$id")
		} catch (e: HttpStatusException) {
			// The site's "Confirm you're human" reader check, it's passed on the chapter's page
			if (e.statusCode == 429) {
				context.requestBrowserAction(this, chapter.url.toAbsoluteUrl(domain))
			}
			throw e
		}
		return response.parseJson().getJSONObject("chapter").getJSONArray("pages").mapJSONIndexed { index, page ->
			val src = page.getString("src")
			// The images are encrypted and the keys only come with this list, see intercept
			val keys = listOfNotNull(page.getString("k"), page.getString("n"), page.getStringOrNull("t"))
			MangaPage(
				// A chapter can have the same image twice, such as a credits page
				id = generateUid("${chapter.url}/$index"),
				url = src.toAbsoluteUrl(domain) + "#" + keys.joinToString("."),
				preview = null,
				source = source,
			)
		}
	}

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		val fragment = request.url.fragment
		val response = chain.proceed(request)
		if (fragment == null || !request.url.encodedPath.startsWith("/cdn/e/") || !response.isSuccessful) {
			return response
		}
		val parts = fragment.split('.', limit = 3)
		val cipher = Cipher.getInstance("AES/CTR/NoPadding")
		cipher.init(
			Cipher.DECRYPT_MODE,
			SecretKeySpec(decodeBase64Url(parts[0]), "AES"),
			IvParameterSpec(decodeBase64Url(parts[1]).copyOf(16)),
		)
		val image = response.use { cipher.doFinal(it.requireBody().bytes()) }
		// The type in the page list can be wrong, some JPEG images are listed as PNG
		val type = when {
			image.getOrNull(0) == 0xFF.toByte() && image.getOrNull(1) == 0xD8.toByte() -> "image/jpeg"
			image.getOrNull(0) == 0x89.toByte() && image.getOrNull(1) == 0x50.toByte() -> "image/png"
			else -> parts.getOrNull(2) ?: "image/jpeg"
		}
		return response.newBuilder()
			.header("Content-Type", type)
			.body(image.toResponseBody(type.toMediaTypeOrNull()))
			.build()
	}

	private fun decodeBase64Url(value: String): ByteArray {
		val standard = value.replace('-', '+').replace('_', '/')
		return context.decodeBase64(standard.padEnd((standard.length + 3) / 4 * 4, '='))
	}

	private companion object {

		// Tags the site's own tag picker leaves out
		val HIDDEN_TAGS = setOf("Shounen", "Shoujo", "Seinen", "Josei", "Lolicon", "Shotacon", "Other")

		val ADULT_TAGS = setOf("Hentai", "Adult", "Smut")
	}
}
