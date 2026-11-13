package org.koitharu.kotatsu.parsers.site.id

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.NotFoundException
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.asTypedList
import org.koitharu.kotatsu.parsers.util.json.getFloatOrDefault
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.json.mapJSONNotNull
import org.koitharu.kotatsu.parsers.util.json.mapJSONNotNullToSet
import org.koitharu.kotatsu.parsers.util.json.toJSONArrayOrNull
import java.text.SimpleDateFormat
import java.util.*

/**
 * The site has no API to ask any more: what a page shows comes inside the page, in the payload its scripts hand to
 * Next.js, and that is where everything is read from.
 */
@MangaSourceParser("VORATOON", "Voratoon", "id")
internal class Voratoon(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.VORATOON, pageSize = 30) {

	override val configKeyDomain = ConfigKey.Domain("voratoon.co")

	// Earlier versions offered these hosts, and the app keeps a saved domain: it would stay on a dead one.
	private val siteDomain: String
		get() = domain.takeUnless { it == "voratoon.id" || it == "voratoon.com" || it.endsWith(".voratoon.com") }
			?: configKeyDomain.defaultValue

	// The default Referer comes from the saved domain
	override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
		.set("Referer", "https://$siteDomain/")
		.build()

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
		SortOrder.NEWEST,
		SortOrder.ALPHABETICAL,
	)

	override val filterCapabilities = MangaListFilterCapabilities(
		isSearchSupported = true,
		isMultipleTagsSupported = true,
		isTagsExclusionSupported = false,
		isSearchWithFiltersSupported = true,
		isYearSupported = false,
	)

	@Volatile
	private var tagsCache: Set<MangaTag>? = null

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = tagsCache ?: loadPage("https://$siteDomain/browse", "series", "seriesMeta").let { page ->
			parseTags(page.data.optJSONArray("genres")).also { if (it.isNotEmpty()) tagsCache = it }
		},
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
		val url = "https://$siteDomain/browse".toHttpUrl().newBuilder().apply {
			addQueryParameter("page", page.toString())
			addQueryParameter(
				"sort",
				when (order) {
					SortOrder.POPULARITY -> "popular"
					SortOrder.RATING -> "rating"
					SortOrder.NEWEST -> "newseries"
					SortOrder.ALPHABETICAL -> "az"
					else -> "new"
				},
			)
			filter.query?.takeIf { it.isNotBlank() }?.let { addQueryParameter("q", it.trim()) }
			// Genres go by name, and a title has to be of all of them
			if (filter.tags.isNotEmpty()) {
				addQueryParameter("genre", filter.tags.joinToString(",") { it.title.lowercase(Locale.ROOT) })
			}
			filter.states.oneOrThrowIfMany()?.let { state ->
				addQueryParameter(
					"status",
					when (state) {
						MangaState.ONGOING -> "ongoing"
						MangaState.FINISHED -> "completed"
						MangaState.PAUSED -> "hiatus"
						MangaState.ABANDONED -> "cancelled"
						else -> return emptyList()
					},
				)
			}
			filter.types.oneOrThrowIfMany()?.let { type ->
				addQueryParameter(
					"format",
					when (type) {
						ContentType.MANGA -> "manga"
						ContentType.MANHWA -> "manhwa"
						ContentType.MANHUA -> "manhua"
						else -> return emptyList()
					},
				)
			}
		}.build()
		// A page past the last one comes with no titles
		val result = loadPage(url.toString(), "series", "seriesMeta")
		return result.data.getJSONArray("series").mapJSONNotNull { result.toManga(it) }
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val slug = manga.url.substringAfterLast('/')
		val page = loadPage("https://$siteDomain/series/${slug.urlEncoded()}", "series", "chapters")
		val series = page.data.optJSONArray("series")?.optJSONObject(0)
		val details = series?.let { page.toManga(it) }
			?: throw ParseException("No series in the page", manga.url)
		val data = series.getJSONObject("data")
		return manga.copy(
			title = details.title,
			publicUrl = details.publicUrl,
			altTitles = details.altTitles,
			coverUrl = details.coverUrl ?: manga.coverUrl,
			largeCoverUrl = page.text(data, "backgroundImage"),
			rating = details.rating,
			tags = details.tags,
			state = details.state,
			authors = details.authors,
			description = page.text(data, "synopsis"),
			chapters = parseChapters(page, slug),
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val parts = chapter.url.trim('/').split('/')
		if (parts.size < 4 || parts[0] != "series" || (parts[2] != "chapters" && parts[2] != "chapter")) {
			throw ParseException("Invalid chapter url", chapter.url)
		}
		val slug = parts[1]
		val storedChapter = parts[3]
		val directResult = runCatchingCancellable { fetchChapterPages(slug, storedChapter) }
		if (directResult.isSuccess) return directResult.getOrThrow()

		// Compatibility with chapter URLs created by an older parser, which stored the chapter's id here.
		val legacyId = storedChapter.toLongOrNull() ?: throw directResult.exceptionOrNull()!!
		val chapters = loadPage("https://$siteDomain/series/${slug.urlEncoded()}", "series", "chapters")
			.data.optJSONArray("chapters")
		val legacyChapter = chapters?.mapJSONNotNull { it.takeIf { c -> c.optLong("id") == legacyId } }?.firstOrNull()
			?: throw directResult.exceptionOrNull()!!
		return fetchChapterPages(slug, formatChapterNumber(parseChapterNumber(legacyChapter)))
	}

	private suspend fun fetchChapterPages(slug: String, chapterNumber: String): List<MangaPage> {
		val url = "https://$siteDomain/series/${slug.urlEncoded()}/chapter/${chapterNumber.urlEncoded()}"
		val chapter = loadPage(url, "chapter", "chapters").data.optJSONObject("chapter")
			?: throw NotFoundException("No chapter in the page", url)
		val images = chapter.optJSONObject("data")?.optJSONArray("images")?.asTypedList<String>().orEmpty()
		return images.mapNotNull { it.trim().nullIfEmpty() }.distinct().map { imageUrl ->
			MangaPage(
				id = generateUid(imageUrl),
				url = imageUrl,
				preview = null,
				source = source,
			)
		}
	}

	private fun PageData.toManga(jo: JSONObject): Manga? {
		val id = jo.optLong("id")
		if (id <= 0L) return null
		val data = jo.optJSONObject("data") ?: return null
		val slug = text(data, "slug") ?: id.toString()
		return Manga(
			id = generateUid(id),
			title = text(data, "title") ?: "Untitled",
			altTitles = setOfNotNull(text(data, "nativeTitle")),
			url = "/series/$slug",
			publicUrl = "https://$siteDomain/series/$slug",
			rating = data.getFloatOrDefault("rating", 0f).let { if (it > 0f) it / 10f else RATING_UNKNOWN },
			contentRating = ContentRating.SAFE,
			coverUrl = text(data, "coverImage"),
			tags = parseTags(data.optJSONArray("genres")),
			state = when (text(data, "status")?.lowercase(Locale.ROOT)) {
				"ongoing" -> MangaState.ONGOING
				"completed" -> MangaState.FINISHED
				"hiatus" -> MangaState.PAUSED
				"cancelled", "canceled" -> MangaState.ABANDONED
				else -> null
			},
			authors = setOfNotNull(text(data, "author")?.trim()?.nullIfEmpty()),
			source = source,
		)
	}

	private fun parseChapters(page: PageData, slug: String): List<MangaChapter> {
		val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT)
		val chapters = page.data.optJSONArray("chapters") ?: return emptyList()
		return chapters.mapJSONNotNull { jo ->
			val id = jo.optLong("id")
			if (id <= 0L) return@mapJSONNotNull null
			val number = parseChapterNumber(jo)
			val chapterNumber = formatChapterNumber(number)
			val name = jo.optJSONObject("data")?.let { page.text(it, "title") }
			MangaChapter(
				id = generateUid(id),
				title = if (name == null) "Chapter $chapterNumber" else "Chapter $chapterNumber: $name",
				number = number,
				volume = 0,
				url = "/series/$slug/chapter/$chapterNumber",
				scanlator = null,
				// 2026-08-16T17:21:23.416+00:00
				uploadDate = dateFormat.parseSafe(jo.getStringOrNull("createdAt")),
				branch = null,
				source = source,
			)
		}.sortedWith(
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

	private fun parseTags(arr: JSONArray?): Set<MangaTag> {
		return arr?.mapJSONNotNullToSet { jo ->
			val id = jo.optLong("id")
			val name = jo.optJSONObject("data")?.getStringOrNull("name")
			if (id > 0L && name != null) MangaTag(title = name, key = id.toString(), source = source) else null
		}.orEmpty()
	}

	/**
	 * Finds what a page shows: the first object of its payload that has all of [keys].
	 *
	 * The payload is a list of rows, `id:value`, a row to a line. A text of 1024 characters or more is not written
	 * where it belongs but in a row of its own, `id:T<length in bytes, in hex>,<the text>`, with no line break
	 * after it, and `"$id"` stands in its place.
	 */
	private suspend fun loadPage(url: String, vararg keys: String): PageData {
		val doc = webClient.httpGet(url, getRequestHeaders()).parseHtml()
		val payload = StringBuilder()
		for (element in doc.select("script")) {
			val script = element.data()
			if (!script.startsWith(PAYLOAD_PUSH)) continue
			val chunk = script.substring(PAYLOAD_PUSH.length).substringBeforeLast(')').toJSONArrayOrNull() ?: continue
			(chunk.opt(1) as? String)?.let(payload::append)
		}
		val bytes = payload.toString().toByteArray(Charsets.UTF_8)
		val texts = HashMap<String, String>()
		var pos = 0
		while (pos < bytes.size) {
			val colon = bytes.indexOf(':', pos)
			if (colon < 0) break
			if (bytes.getOrNull(colon + 1) == 'T'.code.toByte()) {
				val comma = bytes.indexOf(',', colon)
				val length = if (comma < 0) null else String(bytes, colon + 2, comma - colon - 2).toIntOrNull(16)
				if (length == null || comma + 1 + length > bytes.size) break
				texts[String(bytes, pos, colon - pos)] = String(bytes, comma + 1, length, Charsets.UTF_8)
				pos = comma + 1 + length
				continue
			}
			val end = bytes.indexOf('\n', colon).let { if (it < 0) bytes.size else it }
			val row = String(bytes, colon + 1, end - colon - 1, Charsets.UTF_8)
			pos = end + 1
			if (keys.any { "\"$it\":" !in row }) continue
			val value = runCatching { JSONTokener(row).nextValue() }.getOrNull() ?: continue
			findObject(value, keys)?.let { return PageData(it, texts) }
		}
		// The site answers 200 with its "not found" page for a title or a chapter it does not have
		throw NotFoundException("Nothing to read in the page", url)
	}

	private fun ByteArray.indexOf(char: Char, from: Int): Int {
		val byte = char.code.toByte()
		for (i in from until size) {
			if (this[i] == byte) return i
		}
		return -1
	}

	private fun findObject(node: Any?, keys: Array<out String>): JSONObject? = when (node) {
		is JSONObject -> if (keys.all(node::has)) {
			node
		} else {
			node.keys().asSequence().firstNotNullOfOrNull { findObject(node.opt(it), keys) }
		}

		is JSONArray -> (0 until node.length()).firstNotNullOfOrNull { findObject(node.opt(it), keys) }
		else -> null
	}

	private class PageData(val data: JSONObject, private val texts: Map<String, String>) {

		// "$$..." is a text that begins with "$", "$id" one kept in a row of its own, "$undefined" no value at all
		fun text(jo: JSONObject, name: String): String? {
			val value = jo.getStringOrNull(name) ?: return null
			return when {
				!value.startsWith('$') -> value
				value.startsWith("$$") -> value.substring(1)
				else -> texts[value.substring(1)]?.nullIfEmpty()
			}
		}
	}

	private companion object {

		const val PAYLOAD_PUSH = "self.__next_f.push("
	}
}
