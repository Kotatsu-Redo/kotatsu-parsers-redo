package org.koitharu.kotatsu.parsers.site.ru

import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.ParseException
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
import org.koitharu.kotatsu.parsers.util.LinkResolver
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.map
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.parseJsonArray
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy
import org.koitharu.kotatsu.parsers.util.toTitleCase
import java.time.Instant
import java.util.EnumSet
import java.util.LinkedHashSet
import java.util.Locale

@MangaSourceParser("ZENMANGA", "InkStory", "ru")
internal class ZenMangaParser(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.ZENMANGA, pageSize = PAGE_SIZE) {

	init {
		setFirstPage(0)
	}

	override val configKeyDomain = ConfigKey.Domain("inkstory.net")

	private val tags = suspendLazy(initializer = ::fetchTags)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
		.set("Referer", "https://$domain/")
		.set("Origin", "https://$domain")
		.set("Accept", "application/json, text/plain, */*")
		.build()

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.POPULARITY_ASC,
		SortOrder.RATING,
		SortOrder.RATING_ASC,
		SortOrder.NEWEST,
		SortOrder.NEWEST_ASC,
	)

	override val filterCapabilities = MangaListFilterCapabilities(
		isSearchSupported = true,
		isSearchWithFiltersSupported = true,
		isMultipleTagsSupported = true,
		isTagsExclusionSupported = true,
		isYearSupported = true,
		isYearRangeSupported = true,
		isOriginalLocaleSupported = true,
	)

	override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions(
		availableTags = tags.get(),
		availableStates = EnumSet.of(
			MangaState.ONGOING,
			MangaState.FINISHED,
			MangaState.PAUSED,
			MangaState.UPCOMING,
		),
		availableContentRating = EnumSet.allOf(ContentRating::class.java),
		availableLocales = ORIGINAL_LOCALES,
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		if (order == SortOrder.UPDATED && filter.isEmpty()) {
			return getLatestUpdates(page)
		}
		val url = HttpUrl.Builder()
			.scheme("https")
			.host(API_DOMAIN)
			.addPathSegments("v2/books")
			.addQueryParameter("size", PAGE_SIZE.toString())
			.addQueryParameter("page", page.toString())
			.addQueryParameter("sort", order.toApiSort())
			.apply {
				filter.query?.trim()?.takeIf(String::isNotEmpty)?.let {
					addQueryParameter("search", it)
				}
				filter.tags.forEach { addQueryParameter("labelsInclude", it.key) }
				filter.tagsExclude.forEach { addQueryParameter("labelsExclude", it.key) }
				filter.states.mapNotNull { it.toApiStatus() }.forEach {
					addQueryParameter("status", it)
				}
				filter.contentRating.flatMap { it.toApiContentStatuses() }.distinct().forEach {
					addQueryParameter("contentStatus", it)
				}
				filter.originalLocale?.toApiCountry()?.let {
					addQueryParameter("country", it)
				}
				when {
					filter.year != YEAR_UNKNOWN -> {
						addQueryParameter("yearMin", filter.year.toString())
						addQueryParameter("yearMax", filter.year.toString())
					}
					else -> {
						if (filter.yearFrom != YEAR_UNKNOWN) {
							addQueryParameter("yearMin", filter.yearFrom.toString())
						}
						if (filter.yearTo != YEAR_UNKNOWN) {
							addQueryParameter("yearMax", filter.yearTo.toString())
						}
					}
				}
			}
			.build()
		val books = webClient.httpGet(url, getRequestHeaders()).parseJsonArray()
		return List(books.length()) { index -> parseManga(books.getJSONObject(index)) }
	}

	private suspend fun getLatestUpdates(page: Int): List<Manga> {
		val url = HttpUrl.Builder()
			.scheme("https")
			.host(API_DOMAIN)
			.addPathSegments("v2/chapter-update-feed")
			.addQueryParameter("onlyBorderChapters", "true")
			.addQueryParameter("page", page.toString())
			.addQueryParameter("size", PAGE_SIZE.toString())
			.build()
		val updates = webClient.httpGet(url, getRequestHeaders()).parseJsonArray()
		return List(updates.length()) { index ->
			parseManga(updates.getJSONObject(index).getJSONObject("book"))
		}
	}

	private fun parseManga(book: JSONObject): Manga {
		val slug = book.getString("slug")
		val publicUrl = "https://$domain/content/$slug"
		val title = resolveTitle(book.optJSONObject("name"), slug)
		val rating = book.optDoubleOrNull("averageRating")
			?.div(10.0)
			?.toFloat()
			?.coerceIn(0f, 1f)
			?: RATING_UNKNOWN
		return Manga(
			id = generateUid("https://$LEGACY_ID_DOMAIN/content/$slug"),
			url = "/content/$slug",
			publicUrl = publicUrl,
			title = title,
			altTitles = parseAltTitles(book, title),
			rating = rating,
			contentRating = parseContentRating(book.getStringOrNull("contentStatus")),
			coverUrl = book.getStringOrNull("poster"),
			largeCoverUrl = book.getStringOrNull("poster"),
			tags = emptySet(),
			state = parseState(book.getStringOrNull("status")),
			authors = emptySet(),
			source = source,
		)
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val slug = manga.url.substringBefore('#').trimEnd('/').substringAfterLast('/')
		if (slug.isBlank()) {
			throw ParseException("Cannot parse InkStory slug", manga.url)
		}
		val book = webClient.httpGet(apiUrl("v2/books/$slug"), getRequestHeaders()).parseJson()
		val bookId = book.getString("id")
		val chapters = fetchChapters(bookId, slug, fetchBranchNames(bookId))
		val parsed = parseManga(book)

		return manga.copy(
			publicUrl = parsed.publicUrl,
			title = parsed.title,
			altTitles = parsed.altTitles,
			rating = parsed.rating,
			contentRating = parsed.contentRating,
			coverUrl = parsed.coverUrl ?: manga.coverUrl,
			largeCoverUrl = parsed.largeCoverUrl ?: manga.largeCoverUrl,
			description = buildDescription(book),
			tags = parseBookTags(book),
			state = parsed.state,
			authors = parseAuthors(book),
			chapters = chapters,
		)
	}

	private suspend fun fetchBranchNames(bookId: String): Map<String, String?> {
		val url = apiUrl("v2/branches").newBuilder()
			.addQueryParameter("bookId", bookId)
			.addQueryParameter("moderationStatus", "APPROVED")
			.build()
		val branches = webClient.httpGet(url, getRequestHeaders()).parseJsonArray()
		return buildMap(branches.length()) {
			for (index in 0 until branches.length()) {
				val branch = branches.getJSONObject(index)
				val id = branch.getStringOrNull("id") ?: continue
				val publishers = branch.optJSONArray("publishers") ?: JSONArray()
				val names = LinkedHashSet<String>(publishers.length())
				for (publisherIndex in 0 until publishers.length()) {
					publishers.optJSONObject(publisherIndex)
						?.getStringOrNull("name")
						?.trim()
						?.takeIf(String::isNotEmpty)
						?.let(names::add)
				}
				put(id, names.joinToString(", ").ifBlank { null })
			}
		}
	}

	private suspend fun fetchChapters(
		bookId: String,
		slug: String,
		branchNames: Map<String, String?>,
	): List<MangaChapter> {
		val url = apiUrl("v2/chapters").newBuilder()
			.addQueryParameter("bookId", bookId)
			.addQueryParameter("moderationStatus", "APPROVED")
			.build()
		val chapters = webClient.httpGet(url, getRequestHeaders()).parseJsonArray()
		return List(chapters.length()) { index ->
			val chapter = chapters.getJSONObject(index)
			val id = chapter.getString("id")
			val branchId = chapter.getStringOrNull("branchId")
			val branchName = branchId?.let { branchNames[it] ?: "Ветка $it" }
			MangaChapter(
				id = generateUid(id),
				url = "/content/$slug/$id",
				title = sequenceOf(chapter.getStringOrNull("name"), chapter.getStringOrNull("title"))
					.firstOrNull { !it.isNullOrBlank() }
					?.trim(),
				number = chapter.optDoubleOrNull("number")?.toFloat() ?: 0f,
				volume = chapter.optDoubleOrNull("volume")?.toInt() ?: 0,
				uploadDate = parseDate(chapter.getStringOrNull("createdAt")),
				scanlator = branchName,
				branch = branchName,
				source = source,
			)
		}.sortedWith(
			compareBy<MangaChapter> { it.volume }
				.thenBy { it.number }
				.thenBy { it.uploadDate },
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val chapterId = chapter.url.substringBefore('#').trimEnd('/').substringAfterLast('/')
		if (chapterId.isBlank()) {
			throw ParseException("Cannot parse InkStory chapter id", chapter.url)
		}
		val data = webClient.httpGet(apiUrl("v2/chapters/$chapterId"), getRequestHeaders()).parseJson()
		val pages = data.optJSONArray("pages") ?: return emptyList()
		return List(pages.length()) { index -> pages.getJSONObject(index) }
			.sortedBy { it.optInt("index", Int.MAX_VALUE) }
			.mapNotNull { page ->
				val imageUrl = page.getStringOrNull("image")?.takeIf(String::isNotBlank)
					?: return@mapNotNull null
				val normalizedUrl = normalizeImageUrl(imageUrl)
				MangaPage(
					id = generateUid(page.getStringOrNull("id") ?: normalizedUrl),
					url = normalizedUrl,
					preview = null,
					source = source,
				)
			}
	}

	override suspend fun resolveLink(resolver: LinkResolver, link: HttpUrl): Manga? {
		val contentIndex = link.pathSegments.indexOf("content")
		val slug = link.pathSegments.getOrNull(contentIndex + 1)?.takeIf(String::isNotBlank) ?: return null
		val url = "/content/$slug"
		return resolver.resolveManga(this, url = url, id = generateUid("https://$LEGACY_ID_DOMAIN$url"))
	}

	private suspend fun fetchTags(): Set<MangaTag> {
		val labels = webClient.httpGet(apiUrl("v2/labels"), getRequestHeaders()).parseJsonArray()
		return buildSet(labels.length()) {
			for (index in 0 until labels.length()) {
				val label = labels.getJSONObject(index)
				if (label.getStringOrNull("kind") != "GENRE") continue
				val slug = label.getStringOrNull("slug")?.takeIf(String::isNotBlank) ?: continue
				val name = label.getStringOrNull("name")?.takeIf(String::isNotBlank) ?: continue
				add(MangaTag(key = slug, title = name.toTitleCase(sourceLocale), source = source))
			}
		}.sortedBy { it.title }.toCollection(LinkedHashSet())
	}

	private fun parseBookTags(book: JSONObject): Set<MangaTag> {
		val labels = book.optJSONArray("labels") ?: return emptySet()
		return buildSet(labels.length()) {
			for (index in 0 until labels.length()) {
				val label = labels.getJSONObject(index)
				val slug = label.getStringOrNull("slug") ?: continue
				val name = label.getStringOrNull("name") ?: continue
				add(MangaTag(key = slug, title = name.toTitleCase(sourceLocale), source = source))
			}
		}
	}

	private fun parseAuthors(book: JSONObject): Set<String> {
		val relations = book.optJSONArray("relations") ?: return emptySet()
		return buildSet {
			for (index in 0 until relations.length()) {
				val relation = relations.getJSONObject(index)
				if (relation.getStringOrNull("type") !in AUTHOR_RELATION_TYPES) continue
				relation.optJSONObject("publisher")
					?.getStringOrNull("name")
					?.trim()
					?.takeIf(String::isNotEmpty)
					?.let(::add)
			}
		}
	}

	private fun buildDescription(book: JSONObject): String? = buildString {
		book.getStringOrNull("description")?.trim()?.takeIf(String::isNotEmpty)?.let(::append)
		val links = book.optJSONArray("externalLinks")
		if (links != null) {
			val values = LinkedHashSet<String>(links.length())
			for (index in 0 until links.length()) {
				links.optString(index).trim().takeIf(String::isNotEmpty)?.let(values::add)
			}
			if (values.isNotEmpty()) {
				if (isNotEmpty()) append("\n\n")
				append("Внешние ссылки:\n")
				append(values.joinToString("\n"))
			}
		}
	}.ifBlank { null }

	private fun parseAltTitles(book: JSONObject, primaryTitle: String): Set<String> = buildSet {
		book.optJSONObject("name")?.let { names ->
			arrayOf("ru", "en", "original").forEach { key ->
				names.getStringOrNull(key)?.trim()?.takeIf(String::isNotEmpty)?.let(::add)
			}
		}
		book.optJSONArray("altNames")?.let { names ->
			for (index in 0 until names.length()) {
				names.optJSONObject(index)
					?.getStringOrNull("name")
					?.trim()
					?.takeIf(String::isNotEmpty)
					?.let(::add)
			}
		}
	}.filterNotTo(LinkedHashSet()) { it.equals(primaryTitle, ignoreCase = true) }

	private fun resolveTitle(name: JSONObject?, fallbackSlug: String): String =
		name?.getStringOrNull("ru")?.takeIf(String::isNotBlank)
			?: name?.getStringOrNull("en")?.takeIf(String::isNotBlank)
			?: name?.getStringOrNull("original")?.takeIf(String::isNotBlank)
			?: fallbackSlug

	private fun parseState(status: String?): MangaState? = when (status) {
		"ONGOING" -> MangaState.ONGOING
		"DONE" -> MangaState.FINISHED
		"FROZEN" -> MangaState.PAUSED
		"ANNOUNCE" -> MangaState.UPCOMING
		else -> null
	}

	private fun parseContentRating(status: String?): ContentRating? = when (status) {
		"SAFE" -> ContentRating.SAFE
		"UNSAFE" -> ContentRating.SUGGESTIVE
		"EROTIC", "PORNOGRAPHIC" -> ContentRating.ADULT
		else -> null
	}

	private fun parseDate(value: String?): Long = value?.let {
		runCatching { Instant.parse(it).toEpochMilli() }.getOrDefault(0L)
	} ?: 0L

	private fun normalizeImageUrl(rawUrl: String): String =
		if (detectImageCodec(rawUrl) == ImageCodec.SEC) replaceFileNameMode(rawUrl, 'x') else rawUrl

	private fun detectImageCodec(imageUrl: String): ImageCodec? {
		val fileName = imageUrl.substringAfterLast('/').substringBefore('?').substringBefore('#')
		val baseName = fileName.substringBeforeLast('.', missingDelimiterValue = fileName)
		if (baseName.length != IMAGE_NAME_LENGTH) return null
		return when (baseName.getOrNull(IMAGE_MODE_INDEX)) {
			's' -> ImageCodec.SEC
			'x' -> ImageCodec.XOR
			else -> null
		}
	}

	private fun replaceFileNameMode(imageUrl: String, replacementMode: Char): String {
		val parsed = imageUrl.toHttpUrlOrNull() ?: return imageUrl
		val fileName = parsed.pathSegments.lastOrNull() ?: return imageUrl
		val baseName = fileName.substringBeforeLast('.', missingDelimiterValue = fileName)
		if (baseName.length != IMAGE_NAME_LENGTH) return imageUrl
		val extension = fileName.substringAfterLast('.', missingDelimiterValue = "")
		val updatedBaseName = baseName.replaceRange(
			IMAGE_MODE_INDEX,
			IMAGE_MODE_INDEX + 1,
			replacementMode.toString(),
		)
		val updatedName = if (extension.isEmpty()) updatedBaseName else "$updatedBaseName.$extension"
		return parsed.newBuilder()
			.setPathSegment(parsed.pathSize - 1, updatedName)
			.build()
			.toString()
	}

	private fun decodeXor(payload: ByteArray): ByteArray {
		val key = SECRET_KEY.toByteArray(Charsets.UTF_8)
		return ByteArray(payload.size) { index ->
			(payload[index].toInt() xor key[index % key.size].toInt()).toByte()
		}
	}

	private fun looksLikeImage(payload: ByteArray): Boolean {
		if (payload.size < MIN_IMAGE_SIGNATURE_SIZE) return false
		if (payload[0] == 0xFF.toByte() && payload[1] == 0xD8.toByte() && payload[2] == 0xFF.toByte()) {
			return true
		}
		if (
			payload[0] == 0x89.toByte() && payload[1] == 0x50.toByte() &&
			payload[2] == 0x4E.toByte() && payload[3] == 0x47.toByte()
		) {
			return true
		}
		if (
			payload[0] == 0x47.toByte() && payload[1] == 0x49.toByte() &&
			payload[2] == 0x46.toByte() && payload[3] == 0x38.toByte()
		) {
			return true
		}
		return payload[0] == 0x52.toByte() && payload[1] == 0x49.toByte() &&
			payload[2] == 0x46.toByte() && payload[3] == 0x46.toByte() &&
			payload[8] == 0x57.toByte() && payload[9] == 0x45.toByte() &&
			payload[10] == 0x42.toByte() && payload[11] == 0x50.toByte()
	}

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		val response = chain.proceed(request)
		if (!response.isSuccessful || detectImageCodec(request.url.toString()) != ImageCodec.XOR) {
			return response
		}
		return response.map { body ->
			val encrypted = body.bytes()
			if (encrypted.isEmpty()) {
				encrypted.toResponseBody(body.contentType())
			} else {
				val decoded = decodeXor(encrypted)
				if (looksLikeImage(decoded)) {
					decoded.toResponseBody(body.contentType() ?: "image/jpeg".toMediaTypeOrNull())
				} else {
					encrypted.toResponseBody(body.contentType())
				}
			}
		}
	}

	private fun apiUrl(path: String): HttpUrl = "https://$API_DOMAIN/$path".toHttpUrl()

	private fun JSONObject.optDoubleOrNull(key: String): Double? =
		if (has(key) && !isNull(key)) optDouble(key).takeUnless(Double::isNaN) else null

	private fun SortOrder.toApiSort(): String = when (this) {
		SortOrder.POPULARITY_ASC -> "viewsCount,asc"
		SortOrder.RATING -> "averageRating,desc"
		SortOrder.RATING_ASC -> "averageRating,asc"
		SortOrder.NEWEST -> "createdAt,desc"
		SortOrder.NEWEST_ASC -> "createdAt,asc"
		SortOrder.UPDATED -> "createdAt,desc"
		else -> "viewsCount,desc"
	}

	private fun MangaState.toApiStatus(): String? = when (this) {
		MangaState.ONGOING -> "ONGOING"
		MangaState.FINISHED -> "DONE"
		MangaState.PAUSED -> "FROZEN"
		MangaState.UPCOMING -> "ANNOUNCE"
		else -> null
	}

	private fun ContentRating.toApiContentStatuses(): List<String> = when (this) {
		ContentRating.SAFE -> listOf("SAFE")
		ContentRating.SUGGESTIVE -> listOf("UNSAFE")
		ContentRating.ADULT -> listOf("EROTIC", "PORNOGRAPHIC")
	}

	private fun Locale.toApiCountry(): String? = when (language) {
		"ru" -> "RUSSIA"
		"ja" -> "JAPAN"
		"ko" -> "KOREA"
		"zh" -> "CHINA"
		else -> null
	}

	private enum class ImageCodec {
		SEC,
		XOR,
	}

	private companion object {
		private const val API_DOMAIN = "api.inkstory.net"
		private const val LEGACY_ID_DOMAIN = "inkstory.me"
		private const val PAGE_SIZE = 30
		private const val IMAGE_NAME_LENGTH = 36
		private const val IMAGE_MODE_INDEX = 14
		private const val MIN_IMAGE_SIGNATURE_SIZE = 12
		private const val SECRET_KEY = "UySkp0BzPhwlvP2V"

		private val AUTHOR_RELATION_TYPES = setOf("AUTHOR", "ARTIST")
		private val ORIGINAL_LOCALES = setOf(
			Locale.forLanguageTag("ru"),
			Locale.forLanguageTag("ja"),
			Locale.forLanguageTag("ko"),
			Locale.forLanguageTag("zh"),
		)
	}
}
