package org.koitharu.kotatsu.parsers.site.en

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.ContentType
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
import org.koitharu.kotatsu.parsers.util.attrAsRelativeUrl
import org.koitharu.kotatsu.parsers.util.attrOrNull
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.insertCookies
import org.koitharu.kotatsu.parsers.util.mapChapters
import org.koitharu.kotatsu.parsers.util.mapNotNullToSet
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.oneOrThrowIfMany
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.parseRaw
import org.koitharu.kotatsu.parsers.util.parseSafe
import org.koitharu.kotatsu.parsers.util.src
import org.koitharu.kotatsu.parsers.util.textOrNull
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.toTitleCase
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("MANGAHERE", "MangaHere", "en")
internal class MangaHere(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.MANGAHERE, pageSize = 70, searchPageSize = 12) {

	override val configKeyDomain = ConfigKey.Domain("www.mangahere.cc")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = true,
			isAuthorSearchSupported = true,
			isYearSupported = true,
		)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
		.add("Referer", "https://$domain/")
		.add("Cookie", "isAdult=1")
		.build()

	override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions(
		availableTags = GENRES.mapTo(LinkedHashSet(GENRES.size)) { (name, id) ->
			MangaTag(
				key = id.toString(),
				title = name,
				source = source,
			)
		},
		availableStates = EnumSet.of(
			MangaState.ONGOING,
			MangaState.FINISHED,
		),
		availableContentTypes = EnumSet.of(
			ContentType.MANGA,
			ContentType.MANHWA,
			ContentType.MANHUA,
		),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		context.cookieJar.insertCookies(domain, "isAdult=1")
		val hasFilter = !filter.query.isNullOrBlank() ||
			filter.tags.isNotEmpty() ||
			filter.tagsExclude.isNotEmpty() ||
			filter.states.isNotEmpty() ||
			filter.types.isNotEmpty() ||
			!filter.author.isNullOrBlank() ||
			filter.year != 0

		if (!hasFilter) {
			val suffix = if (order == SortOrder.UPDATED) "?latest" else ""
			val doc = webClient.httpGet("https://$domain/directory/$page.htm$suffix").parseHtml()
			return doc.select(".manga-list-1-list li").mapNotNull { parseDirectoryItem(it) }
		}

		// Search page size is 12 even when query is blank (filter-only search uses paginator with pageSize=70 unless adjusted)
		val urlBuilder = "https://$domain/search".toHttpUrl().newBuilder()
			.addQueryParameter("page", page.toString())
			.addQueryParameter("title", filter.query?.trim().orEmpty())
			.addQueryParameter("stype", "1")

		filter.types.oneOrThrowIfMany()?.let { type ->
			val typeCode = when (type) {
				ContentType.MANGA -> "1"
				ContentType.MANHWA -> "2"
				ContentType.MANHUA -> "3"
				else -> "0"
			}
			urlBuilder.addQueryParameter("type", typeCode)
		}

		filter.states.oneOrThrowIfMany()?.let { state ->
			val stCode = when (state) {
				MangaState.ONGOING -> "1"
				MangaState.FINISHED -> "2"
				else -> "0"
			}
			urlBuilder.addQueryParameter("st", stCode)
		}

		if (filter.tags.isNotEmpty()) {
			urlBuilder.addQueryParameter("genres", filter.tags.joinToString(",") { it.key })
		}
		if (filter.tagsExclude.isNotEmpty()) {
			urlBuilder.addQueryParameter("nogenres", filter.tagsExclude.joinToString(",") { it.key })
		}
		if (!filter.author.isNullOrBlank()) {
			urlBuilder.addQueryParameter("author_method", "cw")
			urlBuilder.addQueryParameter("author", filter.author!!.trim())
		}
		if (filter.year != 0) {
			urlBuilder.addQueryParameter("released_method", "eq")
			urlBuilder.addQueryParameter("released", filter.year.toString())
		}

		val doc = webClient.httpGet(urlBuilder.build()).parseHtml()
		return doc.select(".manga-list-4-list > li").mapNotNull { parseSearchItem(it) }
	}

	override suspend fun getDetails(manga: Manga): Manga {
		context.cookieJar.insertCookies(domain, "isAdult=1")
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val title = doc.selectFirst("span.detail-info-right-title-font")?.textOrNull()?.trim()
			?: manga.title
		val author = doc.select(".detail-info-right-say a").mapNotNullToSet { a ->
			a.textOrNull()?.trim()?.nullIfEmpty()
		}
		val genreByName = GENRES.associate { (name, id) -> name.lowercase(Locale.ENGLISH) to id.toString() }
		val tags = doc.select(".detail-info-right-tag-list a").mapNotNullToSet { a ->
			val raw = a.textOrNull()?.trim()?.nullIfEmpty() ?: return@mapNotNullToSet null
			val key = genreByName[raw.lowercase(Locale.ENGLISH)] ?: raw.lowercase(Locale.ENGLISH)
			MangaTag(
				key = key,
				title = raw.toTitleCase(sourceLocale),
				source = source,
			)
		}
		val statusTip = doc.selectFirst("span.detail-info-right-title-tip")?.textOrNull().orEmpty()
		val state = when {
			statusTip.contains("ongoing", ignoreCase = true) -> MangaState.ONGOING
			statusTip.contains("completed", ignoreCase = true) -> MangaState.FINISHED
			else -> manga.state
		}
		val desc = doc.selectFirst("p.fullcontent")?.textOrNull()
			?: doc.selectFirst(".detail-info-right-content")?.textOrNull()
			?: manga.description.orEmpty()
		val cover = doc.selectFirst("img.detail-info-cover-img")?.src()?.toAbsoluteUrl(domain)
			?: manga.coverUrl
		val isAdult = tags.any { it.title in ADULT_TAG_NAMES }

		val dateFormat = SimpleDateFormat("MMM dd,yyyy", Locale.ENGLISH)
		val chapterElements = doc.select("ul.detail-main-list > li")
		val chapters = chapterElements.mapChapters(reversed = true) { i, li ->
			val a = li.selectFirst("a") ?: return@mapChapters null
			val href = a.attrAsRelativeUrl("href")
			val chTitle = a.selectFirst("p.title3")?.textOrNull()?.trim() ?: a.text().trim()
			val dateText = a.selectFirst("p.title2")?.textOrNull()?.trim()
			MangaChapter(
				id = generateUid(href),
				title = chTitle,
				number = i + 1f,
				volume = 0,
				url = href,
				scanlator = null,
				uploadDate = parseChapterDate(dateFormat, dateText),
				branch = null,
				source = source,
			)
		}

		return manga.copy(
			title = title,
			coverUrl = cover,
			authors = author.ifEmpty { manga.authors },
			tags = tags.ifEmpty { manga.tags },
			state = state,
			description = desc,
			contentRating = if (isAdult) ContentRating.ADULT else manga.contentRating,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		context.cookieJar.insertCookies(domain, "isAdult=1")
		val chapterUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(chapterUrl).parseHtml()

		// Webtoon long-strip mode
		if (doc.selectFirst("script[src*=chapter_bar]") != null) {
			val packed = doc.select("script")
				.map { it.data() }
				.firstOrNull { it.contains("function(p,a,c,k,e,d)") }
				?: throw ParseException("Webtoon image script not found", chapterUrl)
			val unpacked = unpackJs(packed)
			val rawUrls = unpacked.substringAfter("newImgs=['", "")
				.substringBefore("'];", "")
				.split("','")
				.filter { it.isNotBlank() }
			return rawUrls.map { u ->
				val full = if (u.startsWith("//")) "https:$u" else u.toAbsoluteUrl(domain)
				MangaPage(
					id = generateUid(full),
					url = full,
					preview = null,
					source = source,
				)
			}
		}

		// Standard page-by-page mode via chapterfun.ashx
		val html = doc.html()
		val cid = CHAPTER_ID_REGEX.find(html)?.groupValues?.get(1)
			?: throw ParseException("chapterid not found", chapterUrl)
		val imageCount = IMAGE_COUNT_REGEX.find(html)?.groupValues?.get(1)?.toIntOrNull()
			?: doc.select(".pager-list-left > span a[data-page]")
				.mapNotNull { it.attrOrNull("data-page")?.toIntOrNull() }
				.maxOrNull()
			?: throw ParseException("Page count not found", chapterUrl)

		val secretKey = extractSecretKey(html)
		val pageBase = chapterUrl.substringBeforeLast('/')

		return (1..imageCount).map { pageNum ->
			val funUrl = "$pageBase/chapterfun.ashx".toHttpUrl().newBuilder()
				.addQueryParameter("cid", cid)
				.addQueryParameter("page", pageNum.toString())
				.addQueryParameter("key", secretKey)
				.fragment(chapterUrl)
				.build()
				.toString()
			MangaPage(
				id = generateUid("$cid:$pageNum"),
				url = funUrl,
				preview = null,
				source = source,
			)
		}
	}

	override suspend fun getPageUrl(page: MangaPage): String {
		if (!page.url.contains("/chapterfun.ashx")) {
			return page.url.toAbsoluteUrl(domain)
		}
		val parsed = page.url.toHttpUrl()
		val referer = parsed.fragment ?: "https://$domain/"
		val cleanUrl = parsed.newBuilder().fragment(null).build()
		val ajaxHeaders = getRequestHeaders().newBuilder()
			.set("Referer", referer)
			.set("Accept", "*/*")
			.set("X-Requested-With", "XMLHttpRequest")
			.build()

		for (attempt in 0..1) {
			val target = if (attempt == 0) {
				cleanUrl
			} else {
				cleanUrl.newBuilder().setQueryParameter("key", "").build()
			}
			val raw = webClient.httpGet(target, ajaxHeaders).parseRaw().trim()
			if (raw.isNotEmpty()) {
				return parseChapterFunResponse(raw, cleanUrl.toString())
			}
		}
		throw ParseException("Empty chapterfun.ashx response", cleanUrl.toString())
	}

	private fun parseDirectoryItem(li: Element): Manga? {
		val a = li.selectFirst("a[href]") ?: return null
		val href = a.attrAsRelativeUrl("href")
		val title = a.attrOrNull("title")?.trim()?.nullIfEmpty()
			?: li.selectFirst(".manga-list-1-item-title")?.textOrNull()?.trim()?.nullIfEmpty()
			?: return null
		val cover = li.selectFirst("img.manga-list-1-cover, img")?.src()?.toAbsoluteUrl(domain)
		return Manga(
			id = generateUid(href),
			url = href,
			publicUrl = href.toAbsoluteUrl(domain),
			coverUrl = cover,
			title = title,
			altTitles = emptySet(),
			rating = RATING_UNKNOWN,
			contentRating = null,
			tags = emptySet(),
			state = null,
			authors = emptySet(),
			source = source,
		)
	}

	private fun parseSearchItem(li: Element): Manga? {
		val a = li.selectFirst(".manga-list-4-item-title > a, a[href]") ?: return null
		val href = a.attrAsRelativeUrl("href")
		val title = a.attrOrNull("title")?.trim()?.nullIfEmpty()
			?: a.textOrNull()?.trim()?.nullIfEmpty()
			?: return null
		val cover = li.selectFirst("img.manga-list-4-cover, img")?.src()?.toAbsoluteUrl(domain)
		return Manga(
			id = generateUid(href),
			url = href,
			publicUrl = href.toAbsoluteUrl(domain),
			coverUrl = cover,
			title = title,
			altTitles = emptySet(),
			rating = RATING_UNKNOWN,
			contentRating = null,
			tags = emptySet(),
			state = null,
			authors = emptySet(),
			source = source,
		)
	}

	private fun parseChapterDate(dateFormat: SimpleDateFormat, raw: String?): Long {
		val text = raw?.trim()?.nullIfEmpty() ?: return 0L
		if (text.contains("Today", ignoreCase = true) || text.contains(" ago", ignoreCase = true)) {
			return Calendar.getInstance().apply {
				set(Calendar.HOUR_OF_DAY, 0)
				set(Calendar.MINUTE, 0)
				set(Calendar.SECOND, 0)
				set(Calendar.MILLISECOND, 0)
			}.timeInMillis
		}
		if (text.contains("Yesterday", ignoreCase = true)) {
			return Calendar.getInstance().apply {
				add(Calendar.DAY_OF_MONTH, -1)
				set(Calendar.HOUR_OF_DAY, 0)
				set(Calendar.MINUTE, 0)
				set(Calendar.SECOND, 0)
				set(Calendar.MILLISECOND, 0)
			}.timeInMillis
		}
		if (DATE_REGEX.matches(text)) {
			return dateFormat.parseSafe(text)
		}
		return 0L
	}

	private fun extractSecretKey(html: String): String {
		val evalIdx = html.indexOf("eval(function(p,a,c,k,e,d)")
		if (evalIdx == -1) return ""
		val endIdx = html.indexOf("</script>", evalIdx).takeIf { it > evalIdx } ?: html.length
		val unpacked = unpackJs(html.substring(evalIdx, endIdx))
		if (unpacked.isEmpty()) return ""
		val expr = unpacked.substringBefore(';')
		val parts = SINGLE_QUOTED_REGEX.findAll(expr).map { it.groupValues[1] }.toList()
		return if (parts.isNotEmpty()) parts.joinToString("") else ""
	}

	private fun parseChapterFunResponse(raw: String, sourceUrl: String): String {
		val unpacked = unpackJs(raw)
		val pix = PIX_REGEX.find(unpacked)?.groupValues?.get(1)
			?: throw ParseException("Missing pix host in chapterfun", sourceUrl)
		val pvalue = PVALUE_REGEX.find(unpacked)?.groupValues?.get(1)
			?: throw ParseException("Missing pvalue image in chapterfun", sourceUrl)
		val combined = if (pvalue.startsWith("http") || pvalue.startsWith("//")) pvalue else "$pix$pvalue"
		return if (combined.startsWith("//")) "https:$combined" else combined
	}

	internal fun unpackJs(packed: String): String {
		val funcIdx = packed.indexOf("function(p,a,c,k,e,d)")
		val searchFrom = if (funcIdx >= 0) funcIdx else 0
		val bodyEnd = packed.indexOf("return p}", searchFrom)
			.takeIf { it >= 0 }
			?.let { it + "return p}".length }
			?: packed.indexOf("}('", searchFrom).takeIf { it >= 0 }?.plus(1)
			?: return ""

		val openParen = packed.indexOf('(', bodyEnd)
		if (openParen == -1) return ""

		val (payload, afterPayload) = readJsSingleQuoted(packed, openParen + 1) ?: return ""
		val (radix, afterRadix) = readJsIntArg(packed, afterPayload) ?: return ""
		val (count, afterCount) = readJsIntArg(packed, afterRadix) ?: return ""
		val (symtabRaw, _) = readJsSingleQuoted(packed, skipCommaAndWhitespace(packed, afterCount)) ?: return ""
		if (radix < 2 || count <= 0) return payload

		val symtab = symtabRaw.split('|')
		val dict = HashMap<String, String>(count)
		for (i in 0 until count) {
			val token = encodeBase(i, radix)
			val word = symtab.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: token
			dict[token] = word
		}

		val sb = StringBuilder(payload.length + 256)
		var i = 0
		val n = payload.length
		while (i < n) {
			val c = payload[i]
			if (c.isWordChar()) {
				val start = i
				i++
				while (i < n && payload[i].isWordChar()) {
					i++
				}
				val token = payload.substring(start, i)
				sb.append(dict[token] ?: token)
			} else {
				sb.append(c)
				i++
			}
		}
		return sb.toString()
	}

	private fun Char.isWordChar(): Boolean =
		(this in 'a'..'z') || (this in 'A'..'Z') || (this in '0'..'9') || this == '_'

	private fun skipCommaAndWhitespace(s: String, start: Int): Int {
		var i = start
		while (i < s.length && (s[i].isWhitespace() || s[i] == ',')) {
			i++
		}
		return i
	}

	private fun readJsSingleQuoted(s: String, start: Int): Pair<String, Int>? {
		var i = start
		while (i < s.length && s[i].isWhitespace()) i++
		if (i >= s.length || (s[i] != '\'' && s[i] != '"')) return null
		val quote = s[i]
		i++
		val out = StringBuilder()
		while (i < s.length) {
			val c = s[i]
			if (c == '\\' && i + 1 < s.length) {
				val next = s[i + 1]
				when (next) {
					'\'', '"', '\\' -> out.append(next)
					'n' -> out.append('\n')
					'r' -> out.append('\r')
					't' -> out.append('\t')
					else -> {
						out.append('\\')
						out.append(next)
					}
				}
				i += 2
			} else if (c == quote) {
				return out.toString() to (i + 1)
			} else {
				out.append(c)
				i++
			}
		}
		return null
	}

	private fun readJsIntArg(s: String, start: Int): Pair<Int, Int>? {
		var i = skipCommaAndWhitespace(s, start)
		val numStart = i
		while (i < s.length && s[i].isDigit()) {
			i++
		}
		if (i == numStart) return null
		val value = s.substring(numStart, i).toIntOrNull() ?: return null
		return value to i
	}

	private fun encodeBase(num: Int, radix: Int): String {
		if (num == 0) return "0"
		val chars = StringBuilder(4)
		var value = num
		while (value > 0) {
			val rem = value % radix
			val digit = if (rem > 35) {
				(rem + 29).toChar()
			} else if (rem < 10) {
				('0'.code + rem).toChar()
			} else {
				('a'.code + (rem - 10)).toChar()
			}
			chars.append(digit)
			value /= radix
		}
		return chars.reverse().toString()
	}

	private companion object {
		val CHAPTER_ID_REGEX = Regex("""chapterid\s*=\s*(\d+)""")
		val IMAGE_COUNT_REGEX = Regex("""imagecount\s*=\s*(\d+)""")
		val DATE_REGEX = Regex("""^[A-Za-z]{3}\s+\d{1,2},\d{4}$""")
		val SINGLE_QUOTED_REGEX = Regex("""'([^']*)'""")
		val PIX_REGEX = Regex("""pix\s*=\s*"([^"]+)"""")
		val PVALUE_REGEX = Regex("""pvalue\s*=\s*\[\s*"([^"]+)"""")
		val ADULT_TAG_NAMES = setOf("Adult", "Mature", "Smut", "Yaoi", "Shotacon", "Lolicon")
		val GENRES = listOf(
			"Action" to 1,
			"Adventure" to 2,
			"Comedy" to 3,
			"Fantasy" to 4,
			"Historical" to 5,
			"Horror" to 6,
			"Martial Arts" to 7,
			"Mystery" to 8,
			"Romance" to 9,
			"Shounen Ai" to 10,
			"Supernatural" to 11,
			"Drama" to 12,
			"Shounen" to 13,
			"School Life" to 14,
			"Shoujo" to 15,
			"Gender Bender" to 16,
			"Josei" to 17,
			"Psychological" to 18,
			"Seinen" to 19,
			"Slice Of Life" to 20,
			"Sci-Fi" to 21,
			"Ecchi" to 22,
			"Harem" to 23,
			"Shoujo Ai" to 24,
			"Yuri" to 25,
			"Mature" to 26,
			"Tragedy" to 27,
			"Yaoi" to 28,
			"Doujinshi" to 29,
			"Sports" to 30,
			"Adult" to 31,
			"One Shot" to 32,
			"Smut" to 33,
			"Mecha" to 34,
			"Webtoons" to 37,
		)
	}
}
