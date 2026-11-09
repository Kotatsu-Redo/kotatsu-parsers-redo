package org.koitharu.kotatsu.parsers.site.comicaso.id

import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.LinkResolver
import org.koitharu.kotatsu.parsers.util.await
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.oneOrThrowIfMany
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.toTitleCase
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.EnumSet
import java.util.LinkedHashSet
import java.util.Locale

@MangaSourceParser("COMICAZEN", "Comicazen", "id")
internal class Comicazen(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.COMICAZEN, PAGE_SIZE, PAGE_SIZE) {

	override val configKeyDomain = ConfigKey.Domain(WEB_DOMAIN)

	override val sourceLocale: Locale = Locale("id")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.NEWEST,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isMultipleTagsSupported = false,
			isTagsExclusionSupported = false,
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
		)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
		.add("Accept", "application/json, text/plain, */*")
		.add("Origin", "https://$domain")
		.add("Referer", "https://$domain/")
		.build()

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchAvailableTags(),
		// The new site only exposes a server-side filter for completed titles.
		availableStates = EnumSet.of(MangaState.FINISHED),
		availableContentTypes = EnumSet.of(
			ContentType.MANGA,
			ContentType.MANHWA,
			ContentType.MANHUA,
		),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val state = filter.states.oneOrThrowIfMany()
		val mode = when {
			state == MangaState.FINISHED -> "completed"
			order == SortOrder.NEWEST -> "new"
			else -> "update"
		}
		val type = when (filter.types.oneOrThrowIfMany()) {
			ContentType.MANGA -> "manga"
			ContentType.MANHWA -> "manhwa"
			ContentType.MANHUA -> "manhua"
			else -> "all"
		}
		val url = apiUrl("home.php") {
			addQueryParameter("source", API_SOURCE)
			addQueryParameter("q", filter.query?.trim().orEmpty())
			addQueryParameter("mode", mode)
			addQueryParameter("type", type)
			filter.tags.oneOrThrowIfMany()?.let { addQueryParameter("genre", it.key) }
			addQueryParameter("limit", pageSize.toString())
			addQueryParameter("offset", ((page - 1).coerceAtLeast(0) * pageSize).toString())
		}
		val json = apiGet(url, "https://$domain/")
		val items = json.optJSONArray("data") ?: JSONArray()
		return buildList(items.length()) {
			for (i in 0 until items.length()) {
				items.optJSONObject(i)?.let { add(it.toManga()) }
			}
		}.let { mangas ->
			if (state == MangaState.ONGOING) {
				mangas.filter { it.state == MangaState.ONGOING }
			} else {
				mangas
			}
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val slug = mangaSlug(manga.url)
		val publicUrl = mangaPublicUrl(slug)
		val json = fetchManga(slug, publicUrl)
		val title = json.string("title", "name") ?: manga.title
		val alternative = json.string("alternative", "alternative_titles")
		val synopsis = json.string("synopsis", "description")
		val description = buildString {
			synopsis?.let(::append)
			if (alternative != null) {
				if (isNotEmpty()) append("\n\n")
				append("Alternative: ").append(alternative)
			}
		}.takeIf(String::isNotBlank)
		val chaptersArray = json.optJSONArray("chapters") ?: JSONArray()
		val chapters = buildList(chaptersArray.length()) {
			for (i in 0 until chaptersArray.length()) {
				val chapter = chaptersArray.optJSONObject(i) ?: continue
				if (chapter.isMiniOnly()) continue
				val chapterSlug = chapter.string("slug", "chapter_slug") ?: continue
				val chapterTitle = chapter.string("title", "name", "chapter_title") ?: chapterSlug
				val token = chapter.string("chapter_token", "token")
				add(
					MangaChapter(
						id = generateUid("/komik/$slug/$chapterSlug/"),
						title = chapterTitle,
						number = extractChapterNumber(chapterTitle),
						volume = 0,
						url = chapterInternalUrl(slug, chapterSlug, token),
						scanlator = null,
						uploadDate = chapter.firstTimestamp("date", "updated_at", "created_at"),
						branch = null,
						source = source,
					),
				)
			}
		}.sortedByDescending { it.number }

		return manga.copy(
			title = title,
			altTitles = alternative.toAltTitles(),
			url = "/komik/$slug/",
			publicUrl = publicUrl,
			coverUrl = json.string("thumbnail", "thumb", "cover") ?: manga.coverUrl,
			tags = json.toTags(),
			state = parseState(json.string("status")),
			authors = setOfNotNull(
				json.string("author"),
				json.string("artist")?.takeUnless { it == json.string("author") },
			),
			description = description,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val ref = parseChapterRef(chapter.url)
		val browserUrl = chapterPublicUrl(ref.mangaSlug, ref.chapterSlug)
		var token = ref.token ?: fetchChapterToken(ref.mangaSlug, ref.chapterSlug, browserUrl)
		var apiUrl = chapterApiUrl(ref.mangaSlug, ref.chapterSlug, token)
		var response = apiGet(apiUrl, browserUrl, allowApiError = true)

		if (!response.optBoolean("ok")) {
			val refreshedToken = fetchChapterToken(ref.mangaSlug, ref.chapterSlug, browserUrl)
			if (!refreshedToken.isNullOrBlank() && refreshedToken != token) {
				token = refreshedToken
				apiUrl = chapterApiUrl(ref.mangaSlug, ref.chapterSlug, token)
				response = apiGet(apiUrl, browserUrl, allowApiError = true)
			}
		}
		ensureApiSuccess(response, apiUrl)

		val data = response.optJSONObject("data")
			?: response.optJSONObject("chapter")
			?: response
		val images = data.optJSONArray("images") ?: JSONArray()
		return buildList(images.length()) {
			for (i in 0 until images.length()) {
				val imageUrl = when (val image = images.opt(i)) {
					is String -> image
					is JSONObject -> image.string("url", "src", "image", "image_url").orEmpty()
					else -> ""
				}.trim()
				if (imageUrl.isNotEmpty()) {
					add(
						MangaPage(
							id = generateUid(imageUrl),
							url = imageUrl,
							preview = null,
							source = source,
						),
					)
				}
			}
		}
	}

	override suspend fun resolveLink(resolver: LinkResolver, link: HttpUrl): Manga? {
		if (link.host != domain) return null
		val slug = when {
			link.queryParameter("page") == "manga" &&
				link.queryParameter("source").orEmpty().let { it.isEmpty() || it == API_SOURCE } ->
				link.queryParameter("slug")

			link.pathSegments.firstOrNull() == "komik" -> link.pathSegments.getOrNull(1)
			else -> null
		}?.takeIf(String::isNotBlank) ?: return null
		return getDetails(mangaSeed(slug, slug))
	}

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		val url = apiUrl("genres.php") {
			addQueryParameter("source", API_SOURCE)
		}
		val genres = apiGet(url, "https://$domain/").optJSONArray("genres") ?: JSONArray()
		return buildSet {
			for (i in 0 until genres.length()) {
				val item = genres.optJSONObject(i) ?: continue
				val title = item.string("genre") ?: continue
				val key = item.string("genre_slug") ?: title.lowercase(sourceLocale)
				add(MangaTag(key = key, title = title.toTitleCase(sourceLocale), source = source))
			}
		}
	}

	private suspend fun fetchManga(slug: String, browserUrl: String): JSONObject {
		val url = apiUrl("manga.php") {
			addQueryParameter("source", API_SOURCE)
			addQueryParameter("slug", slug)
			addQueryParameter("platform", "web")
		}
		val response = apiGet(url, browserUrl)
		return response.optJSONObject("data")
			?: response.optJSONObject("manga")
			?: response
	}

	private suspend fun fetchChapterToken(
		mangaSlug: String,
		chapterSlug: String,
		browserUrl: String,
	): String? {
		val chapters = fetchManga(mangaSlug, browserUrl).optJSONArray("chapters") ?: return null
		for (i in 0 until chapters.length()) {
			val chapter = chapters.optJSONObject(i) ?: continue
			if (chapter.string("slug", "chapter_slug") == chapterSlug) {
				return chapter.string("chapter_token", "token")
			}
		}
		return null
	}

	private suspend fun apiGet(
		url: String,
		browserUrl: String,
		allowApiError: Boolean = false,
	): JSONObject {
		val request = Request.Builder()
			.get()
			.url(url)
			.headers(getRequestHeaders())
			.tag(MangaSource::class.java, source)
			.build()
		val (code, json) = context.httpClient.newCall(request).await().use { response ->
			response.code to runCatching {
				JSONObject(response.body.string())
			}.getOrElse { cause ->
				throw ParseException("Invalid Comicaso API response", url, cause)
			}
		}
		if (json.optBoolean("need_challenge")) {
			requestVerification(browserUrl)
		}
		if (code !in 200..299) {
			throw ParseException(
				json.string("message") ?: "Comicaso API returned HTTP $code",
				url,
			)
		}
		if (!allowApiError) {
			ensureApiSuccess(json, url)
		}
		return json
	}

	private fun ensureApiSuccess(json: JSONObject, url: String) {
		if (!json.optBoolean("ok")) {
			throw ParseException(
				json.string("message") ?: "Comicaso API returned an error",
				url,
			)
		}
	}

	private fun requestVerification(url: String): Nothing = try {
		context.requestBrowserAction(this, url)
	} catch (e: UnsupportedOperationException) {
		throw ParseException(
			"Human verification required. Open Comicaso in the browser, complete the slider, and retry.",
			url,
			e,
		)
	}

	private fun apiUrl(path: String, builder: HttpUrl.Builder.() -> Unit): String =
		"https://$API_DOMAIN/$path".toHttpUrl().newBuilder().apply(builder).build().toString()

	private fun chapterApiUrl(mangaSlug: String, chapterSlug: String, token: String?): String =
		apiUrl("chapter.php") {
			addQueryParameter("source", API_SOURCE)
			addQueryParameter("manga", mangaSlug)
			addQueryParameter("chapter", chapterSlug)
			addQueryParameter("platform", "web")
			if (!token.isNullOrBlank()) addQueryParameter("token", token)
		}

	private fun mangaPublicUrl(slug: String): String = "https://$domain/".toHttpUrl().newBuilder()
		.addQueryParameter("page", "manga")
		.addQueryParameter("source", API_SOURCE)
		.addQueryParameter("slug", slug)
		.build()
		.toString()

	private fun chapterPublicUrl(mangaSlug: String, chapterSlug: String): String =
		"https://$domain/".toHttpUrl().newBuilder()
			.addQueryParameter("page", "chapter")
			.addQueryParameter("source", API_SOURCE)
			.addQueryParameter("manga", mangaSlug)
			.addQueryParameter("chapter", chapterSlug)
			.build()
			.toString()

	private fun chapterInternalUrl(mangaSlug: String, chapterSlug: String, token: String?): String {
		val url = "https://$domain".toHttpUrl().newBuilder()
			.addPathSegment("komik")
			.addPathSegment(mangaSlug)
			.addPathSegment(chapterSlug)
			.apply {
				if (!token.isNullOrBlank()) addQueryParameter("token", token)
			}
			.build()
		return buildString {
			append(url.encodedPath)
			url.encodedQuery?.let { append('?').append(it) }
		}
	}

	private fun parseChapterRef(url: String): ChapterRef {
		val parsed = url.toAbsoluteUrl(domain).toHttpUrlOrNull()
			?: throw ParseException("Invalid chapter URL", url)
		val mangaSlug: String?
		val chapterSlug: String?
		if (parsed.queryParameter("page") == "chapter") {
			mangaSlug = parsed.queryParameter("manga")
			chapterSlug = parsed.queryParameter("chapter")
		} else {
			val komikIndex = parsed.pathSegments.indexOf("komik")
			mangaSlug = if (komikIndex >= 0) parsed.pathSegments.getOrNull(komikIndex + 1) else null
			chapterSlug = if (komikIndex >= 0) parsed.pathSegments.getOrNull(komikIndex + 2) else null
		}
		if (mangaSlug.isNullOrBlank() || chapterSlug.isNullOrBlank()) {
			throw ParseException("Invalid chapter URL", url)
		}
		return ChapterRef(mangaSlug, chapterSlug, parsed.queryParameter("token"))
	}

	private fun mangaSlug(url: String): String {
		val parsed = url.toAbsoluteUrl(domain).toHttpUrlOrNull()
		val slug = when {
			parsed?.queryParameter("page") == "manga" -> parsed.queryParameter("slug")
			else -> parsed?.pathSegments?.let { segments ->
				val komikIndex = segments.indexOf("komik")
				if (komikIndex >= 0) segments.getOrNull(komikIndex + 1) else null
			}
		}
		return slug?.takeIf(String::isNotBlank)
			?: throw ParseException("Invalid manga URL", url)
	}

	private fun JSONObject.toManga(): Manga {
		val slug = string("slug", "manga_slug")
			?: throw ParseException("Missing manga slug", API_DOMAIN)
		val title = string("title", "name") ?: slug
		return mangaSeed(slug, title).copy(
			altTitles = string("alternative", "alternative_titles").toAltTitles(),
			coverUrl = string("thumbnail", "thumb", "cover"),
			tags = toTags(),
			state = parseState(string("status")),
			authors = setOfNotNull(
				string("author"),
				string("artist")?.takeUnless { it == string("author") },
			),
		)
	}

	private fun mangaSeed(slug: String, title: String): Manga = Manga(
		id = generateUid("/komik/$slug/"),
		url = "/komik/$slug/",
		title = title,
		altTitles = emptySet(),
		publicUrl = mangaPublicUrl(slug),
		rating = RATING_UNKNOWN,
		contentRating = null,
		coverUrl = null,
		tags = emptySet(),
		state = null,
		authors = emptySet(),
		source = source,
	)

	private fun JSONObject.toTags(): Set<MangaTag> {
		val genres = optJSONArray("genres") ?: return emptySet()
		return buildSet {
			for (i in 0 until genres.length()) {
				val genre = genres.optString(i).trim().takeIf(String::isNotEmpty) ?: continue
				add(
					MangaTag(
						key = genre.lowercase(sourceLocale),
						title = genre.toTitleCase(sourceLocale),
						source = source,
					),
				)
			}
		}
	}

	private fun JSONObject.string(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
		optString(key).trim().takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
	}

	private fun JSONObject.firstTimestamp(vararg keys: String): Long {
		for (key in keys) {
			val timestamp = parseTimestamp(opt(key))
			if (timestamp != 0L) return timestamp
		}
		return 0L
	}

	private fun JSONObject.isMiniOnly(): Boolean {
		if (optBoolean("mini_only")) return true
		return when (val security = opt("chapter_security")) {
			null, JSONObject.NULL, false, 0, 0L, "", "0", "false" -> false
			else -> true
		}
	}

	private fun parseTimestamp(value: Any?): Long {
		if (value == null || value == JSONObject.NULL) return 0L
		if (value is Number) return value.toLong().toMillisTimestamp()
		val text = value.toString().trim()
		if (text.isEmpty()) return 0L
		text.toLongOrNull()?.let { return it.toMillisTimestamp() }
		return runCatching {
			Instant.parse(text).toEpochMilli()
		}.recoverCatching {
			LocalDateTime.parse(
				text.replace(' ', 'T'),
				DateTimeFormatter.ISO_LOCAL_DATE_TIME,
			).toInstant(ZoneOffset.UTC).toEpochMilli()
		}.getOrDefault(0L)
	}

	private fun Long.toMillisTimestamp(): Long = when {
		this <= 0L -> 0L
		this > 10_000_000_000L -> this
		else -> this * 1000L
	}

	private fun String?.toAltTitles(): Set<String> = this
		?.lineSequence()
		?.map(String::trim)
		?.filter(String::isNotEmpty)
		?.toCollection(LinkedHashSet())
		.orEmpty()

	private fun parseState(status: String?): MangaState? = when (status?.lowercase(Locale.ROOT)) {
		"on-going", "ongoing", "ongoing series" -> MangaState.ONGOING
		"end", "ended", "complete", "completed", "finished", "tamat" -> MangaState.FINISHED
		else -> null
	}

	private fun extractChapterNumber(title: String): Float =
		CHAPTER_NUMBER_REGEX.find(title)
			?.value
			?.replace(',', '.')
			?.toFloatOrNull()
			?: 0f

	private data class ChapterRef(
		val mangaSlug: String,
		val chapterSlug: String,
		val token: String?,
	)

	private companion object {
		const val PAGE_SIZE = 16
		const val WEB_DOMAIN = "v3.comicaso.pro"
		const val API_DOMAIN = "api.comicaso.pro"
		const val API_SOURCE = "comicazen"
		val CHAPTER_NUMBER_REGEX = Regex("""[\d]+(?:[.,]\d+)?""")
	}
}
