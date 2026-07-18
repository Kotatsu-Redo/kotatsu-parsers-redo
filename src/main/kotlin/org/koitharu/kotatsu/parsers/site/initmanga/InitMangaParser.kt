package org.koitharu.kotatsu.parsers.site.initmanga

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.HttpStatusException
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.exception.NotFoundException
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.attrAsAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.attrAsAbsoluteUrlOrNull
import org.koitharu.kotatsu.parsers.util.attrAsRelativeUrl
import org.koitharu.kotatsu.parsers.util.attrAsRelativeUrlOrNull
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.mapToSet
import org.koitharu.kotatsu.parsers.util.mimeType
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.oneOrThrowIfMany
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.parseRaw
import org.koitharu.kotatsu.parsers.util.parseSafe
import org.koitharu.kotatsu.parsers.util.requireSrc
import org.koitharu.kotatsu.parsers.util.src
import org.koitharu.kotatsu.parsers.util.textOrNull
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.toRelativeUrl
import org.koitharu.kotatsu.parsers.util.toTitleCase
import org.koitharu.kotatsu.parsers.util.urlEncoded
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.EnumSet
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

internal abstract class InitMangaParser(
	context: MangaLoaderContext,
	source: MangaParserSource,
	domain: String,
	pageSize: Int = 20,
	searchPageSize: Int = pageSize,
	private val mangaUrlDirectory: String = "seri",
	private val popularUrlSlug: String = mangaUrlDirectory,
	private val latestUrlSlug: String = "son-guncellemeler",
	private val isCloudflareProtected: Boolean = false,
) : PagedMangaParser(context, source, pageSize, searchPageSize) {

	override val configKeyDomain = ConfigKey.Domain(domain)

	private val tagMutex = Mutex()
	private var tagCache: Set<MangaTag>? = null

	private val chapterTooltipDateFormat = SimpleDateFormat("d MMMM yyyy HH:mm", Locale("tr"))
	private val chapterIsoDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT)
	private val fallbackEnglishDateFormat = SimpleDateFormat("MMMM d, yyyy h:mm a", Locale.ENGLISH)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
		if (isCloudflareProtected) {
			keys.add(ConfigKey.InterceptCloudflare(defaultValue = true))
		}
	}

	override val defaultSortOrder: SortOrder
		get() = SortOrder.POPULARITY

	override val availableSortOrders: Set<SortOrder>
		get() = EnumSet.of(SortOrder.POPULARITY, SortOrder.UPDATED)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
		)

	override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions(
		availableTags = getOrCreateTags(),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		return when {
			!filter.query.isNullOrEmpty() -> search(page, filter.query)
			filter.tags.isNotEmpty() -> getGenrePage(page, filter.tags.oneOrThrowIfMany())
			order == SortOrder.UPDATED -> getDirectoryPage(page, latestUrlSlug, alwaysPaged = true)
			else -> getDirectoryPage(page, popularUrlSlug, alwaysPaged = false)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val chapters = fetchChapters(manga.url)
		val description = doc.getElementById("manga-description")
			?.clone()
			?.apply { select("a, span").remove() }
			?.text()
			?.nullIfEmpty()

		return manga.copy(
			title = doc.selectFirst("#manga-title")?.text()?.trim().orEmpty().ifEmpty { manga.title },
			description = description,
			coverUrl = doc.selectFirst("div.story-cover-wrap img, a.story-cover img")?.src() ?: manga.coverUrl,
			tags = doc.select("#genre-tags a").mapToSet(::parseTag),
			contentRating = if (manga.contentRating == ContentRating.ADULT) {
				ContentRating.ADULT
			} else {
				ContentRating.SAFE
			},
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()

		val directPages = doc.getElementById("chapter-content")
			?.select("img")
			?.mapNotNull { it.src() }
			?.distinct()
			.orEmpty()
		if (directPages.isNotEmpty()) {
			return maybeExpandSequentialPages(directPages).map(::toMangaPage)
		}

		val html = doc.outerHtml()
		val directUrls = IMAGE_URL_REGEX.findAll(html)
			.map { it.value }
			.distinct()
			.toList()
		if (directUrls.isNotEmpty()) {
			return maybeExpandSequentialPages(directUrls).map(::toMangaPage)
		}

		val encryptedObject = extractEncryptedObject(doc) ?: return emptyList()
		val decrypted = decryptLayered(html, encryptedObject) ?: return emptyList()
		return maybeExpandSequentialPages(parseDecryptedPages(decrypted)).map(::toMangaPage)
	}

	private suspend fun getDirectoryPage(page: Int, slug: String, alwaysPaged: Boolean): List<Manga> {
		val url = buildString {
			append("https://")
			append(domain)
			append("/")
			append(slug.trim('/'))
			append("/")
			if (alwaysPaged || page > 1) {
				append("page/")
				append(page)
				append("/")
			}
		}
		val doc = webClient.httpGet(url).parseHtml()
		maybeCacheTags(doc)
		return parseMangaList(doc)
	}

	private suspend fun getGenrePage(page: Int, tag: MangaTag?): List<Manga> {
		tag ?: return emptyList()
		val baseUrl = tag.key.toAbsoluteUrl(domain).trimEnd('/')
		val finalUrl = if (page > 1) "$baseUrl/page/$page/" else "$baseUrl/"
		val doc = webClient.httpGet(finalUrl).parseHtml()
		maybeCacheTags(doc)
		return parseMangaList(doc)
	}

	private suspend fun search(page: Int, query: String): List<Manga> {
		val url = buildString {
			append("https://")
			append(domain)
			append("/wp-json/initlise/v1/search?term=")
			append(query.urlEncoded())
			append("&page=")
			append(page)
		}
		val raw = webClient.httpGet(url).parseRaw()
		if (raw.isBlank()) return emptyList()
		if (raw.trimStart().startsWith("<")) {
			val doc = Jsoup.parse(raw, "https://$domain/")
			maybeCacheTags(doc)
			return parseMangaList(doc)
		}

		val list = JSONArray(raw)
		return List(list.length()) { index -> list.getJSONObject(index) }.mapNotNull { json ->
			val fullUrl = json.optString("url").orEmpty()
			if (fullUrl.isBlank()) return@mapNotNull null

			val relativeUrl = fullUrl.toRelativeUrl(domain)
			Manga(
				id = generateUid(relativeUrl),
				title = Jsoup.parse(json.optString("title")).text().trim(),
				altTitles = emptySet(),
				url = relativeUrl,
				publicUrl = fullUrl,
				rating = RATING_UNKNOWN,
				contentRating = sourceContentRating,
				coverUrl = json.optString("thumb").nullIfEmpty(),
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				source = source,
			)
		}
	}

	private fun parseMangaList(document: Document): List<Manga> {
		val panels = document.select("div.manga-item-grid > div.uk-panel")
			.ifEmpty { document.select("div.uk-panel") }
			.filterNot { panel ->
				panel.hasClass("manga-item-ranking") ||
					panel.parents().any { parent ->
						parent.id() == "im-sidebar" ||
							parent.classNames().contains("sidebar-widget") ||
							parent.classNames().contains("top-manga-widget")
					}
			}

		return panels.mapNotNull { panel ->
			val link = panel.findSeriesLink() ?: return@mapNotNull null
			val relativeUrl = link.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
			val title = panel.extractSeriesTitle(link)
			if (title.isBlank()) return@mapNotNull null

			Manga(
				id = generateUid(relativeUrl),
				title = title,
				altTitles = emptySet(),
				url = relativeUrl,
				publicUrl = link.attrAsAbsoluteUrl("href"),
				rating = RATING_UNKNOWN,
				contentRating = when {
					sourceContentRating != null -> sourceContentRating
					panel.text().contains("18+") -> ContentRating.ADULT
					else -> null
				},
				coverUrl = panel.selectFirst("img")?.src(),
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				source = source,
			)
		}.distinctBy { it.url }
	}

	private suspend fun fetchChapters(mangaUrl: String): List<MangaChapter> {
		val chapters = ArrayList<MangaChapter>()
		val seenUrls = LinkedHashSet<String>()
		var page = 1

		while (true) {
			val url = if (page == 1) {
				mangaUrl.toAbsoluteUrl(domain)
			} else {
				"${mangaUrl.toAbsoluteUrl(domain).trimEnd('/')}/bolum/page/$page/"
			}
			val doc = webClient.httpGet(url).parseHtml()
			val items = doc.selectChapterItems(mangaUrl)
			if (items.isEmpty()) {
				break
			}

			val beforeCount = seenUrls.size
			chapters += items.mapNotNull { element ->
				val chapter = parseChapter(element) ?: return@mapNotNull null
				if (!seenUrls.add(chapter.url)) return@mapNotNull null
				chapter
			}

			if (seenUrls.size == beforeCount) {
				break
			}
			page++
		}

		return chapters.sortedWith(
			compareBy<MangaChapter> { it.number <= 0f }
				.thenBy { it.number }
				.thenBy { it.uploadDate.takeIf { date -> date > 0L } ?: Long.MAX_VALUE }
				.thenBy { it.title.orEmpty().lowercase(sourceLocale) },
		)
	}

	protected open val selectChapterItems = "div.chapter-item, div#chapters-list > a[href], #chapters-list > li > a[href]"

	private fun Document.selectChapterItems(mangaUrl: String): List<Element> {
		val items = select(selectChapterItems)
		if (items.isNotEmpty()) {
			return items
		}

		val mangaPath = mangaUrl.toAbsoluteUrl(domain)
			.toHttpUrlOrNull()
			?.encodedPath
			?.trimEnd('/')
			?: return emptyList()
		return select("a[href]").filter { anchor ->
			val chapterPath = anchor.attrAsAbsoluteUrlOrNull("href")
				?.toHttpUrlOrNull()
				?.encodedPath
				?.trimEnd('/')
				?: return@filter false
			if (!chapterPath.startsWith("$mangaPath/")) return@filter false

			val chapterSlug = chapterPath.removePrefix(mangaPath).trim('/')
			chapterSlug.isNotEmpty() &&
				!chapterSlug.startsWith("bolum/page/") &&
				CHAPTER_NUMBER_REGEX.containsMatchIn(chapterSlug)
		}.distinctBy { anchor ->
			anchor.attrAsRelativeUrlOrNull("href") ?: anchor.attr("href")
		}
	}

	private fun parseChapter(element: Element): MangaChapter? {
		val a = element.takeIf { it.tagName() == "a" && it.hasAttr("href") }
			?: element.selectFirst("a[href]")
			?: return null
		val chapterUrl = a.attrAsRelativeUrlOrNull("href") ?: return null
		val rawTitle = element.selectFirst("h3, h4")?.textOrNull()
			?: a.selectFirst("div.gap-2, #item-title, .chapter-title, h3, h4")?.textOrNull()
			?: a.attr("title").trim().nullIfEmpty()
			?: element.selectFirst("img[alt]")?.attr("alt")?.trim()?.nullIfEmpty()
			?: a.text().trim().nullIfEmpty()
		val chapterNumber = extractChapterNumber(chapterUrl, rawTitle)
		return MangaChapter(
			id = generateUid(chapterUrl),
			title = rawTitle ?: chapterNumber?.let { "Bölüm ${it.toChapterNumberString()}" }.orEmpty(),
			url = chapterUrl,
			number = chapterNumber ?: 0f,
			volume = 0,
			scanlator = null,
			uploadDate = parseChapterDate(element),
			branch = null,
			source = source,
		)
	}

	private fun parseChapterDate(element: Element): Long {
		val dateTime = element.selectFirst("time")?.attr("datetime")
		val tooltip = element.selectFirst("span[uk-tooltip]")?.attr("uk-tooltip")
			?.substringAfter("title:")
			?.substringBefore(';')
			?.trim()
		val textDate = element.selectFirst("div.gap-3 span, div:has( #item-title) span.mt-1, .chapter-date")
			?.textOrNull()

		return chapterIsoDateFormat.parseSafe(dateTime)
			.takeIf { it != 0L }
			?: chapterTooltipDateFormat.parseSafe(tooltip)
			.takeIf { it != 0L }
			?: fallbackEnglishDateFormat.parseSafe(tooltip)
			.takeIf { it != 0L }
			?: parseRelativeChapterDate(textDate)
	}

	private fun parseRelativeChapterDate(date: String?): Long {
		val text = date?.trim()?.lowercase(sourceLocale) ?: return 0L
		val amount = Regex("""\d+""").find(text)?.value?.toIntOrNull()
		if (amount == null) {
			return when {
				text.startsWith("yesterday") || text.startsWith("dün") -> Calendar.getInstance().apply {
					add(Calendar.DAY_OF_MONTH, -1)
				}.timeInMillis
				text.startsWith("today") || text.startsWith("bugün") -> System.currentTimeMillis()
				else -> 0L
			}
		}
		return Calendar.getInstance().apply {
			when {
				"dakika" in text || "minute" in text || "min" in text -> add(Calendar.MINUTE, -amount)
				"saat" in text || "hour" in text -> add(Calendar.HOUR_OF_DAY, -amount)
				"gün" in text || "day" in text -> add(Calendar.DAY_OF_MONTH, -amount)
				"hafta" in text || "week" in text -> add(Calendar.WEEK_OF_YEAR, -amount)
				"ay" in text || "month" in text -> add(Calendar.MONTH, -amount)
				"yıl" in text || "year" in text -> add(Calendar.YEAR, -amount)
				else -> return 0L
			}
		}.timeInMillis
	}

	private fun parseTag(anchor: Element): MangaTag {
		return MangaTag(
			title = anchor.text().trim().trimStart('#').toTitleCase(sourceLocale),
			key = anchor.attrAsRelativeUrl("href"),
			source = source,
		)
	}

	private suspend fun getOrCreateTags(): Set<MangaTag> = tagMutex.withLock {
		tagCache?.let { return@withLock it }
		val tags = runCatching {
			webClient.httpGet("https://$domain/").parseHtml().extractTags()
		}.getOrDefault(emptySet())
		tagCache = tags
		return@withLock tags
	}

	private fun maybeCacheTags(document: Document) {
		if (tagCache.isNullOrEmpty()) {
			val tags = document.extractTags()
			if (tags.isNotEmpty()) {
				tagCache = tags
			}
		}
	}

	private fun Document.extractTags(): Set<MangaTag> {
		val genreAnchors = select("ul.uk-list.uk-text-small li a, div#uk-tab-3 a, #genre-tags a")
		if (genreAnchors.isNotEmpty()) {
			return genreAnchors.mapNotNullTo(LinkedHashSet(), ::extractTagFromAnchor)
		}
		return select("a[href]").mapNotNullTo(LinkedHashSet(), ::extractTagFromAnchor)
	}

	private fun extractTagFromAnchor(anchor: Element): MangaTag? {
		val relativeUrl = anchor.attrAsRelativeUrlOrNull("href") ?: return null
		val path = relativeUrl.substringBefore('?').trimEnd('/')
		if (path.count { it == '/' } !in 2..3) return null
		if (!TAG_PATH_REGEX.matches(path)) return null

		val title = anchor.text().trim().trimStart('#').nullIfEmpty() ?: return null
		return MangaTag(
			title = title.toTitleCase(sourceLocale),
			key = relativeUrl,
			source = source,
		)
	}

	private fun extractChapterNumber(chapterUrl: String, rawTitle: String?): Float? {
		val fromUrl = CHAPTER_NUMBER_REGEX.find(chapterUrl.substringAfterLast('/').ifEmpty { chapterUrl })
			?.groupValues
			?.getOrNull(1)
			?.toNormalizedChapterNumber()
		if (fromUrl != null) {
			return fromUrl
		}

		return CHAPTER_NUMBER_REGEX.find(rawTitle.orEmpty())
			?.groupValues
			?.getOrNull(1)
			?.toNormalizedChapterNumber()
	}

	private fun String.toNormalizedChapterNumber(): Float? = replace(',', '.').toFloatOrNull()

	private fun Float.toChapterNumberString(): String = toString().removeSuffix(".0")

	private fun extractEncryptedObject(document: Document): JSONObject? {
		REGEX_ENCRYPTED_DATA.find(document.outerHtml())?.groupValues?.getOrNull(1)?.let {
			return JSONObject(it)
		}

		for (script in document.select("script[src^=data:text/javascript;base64,]")) {
			val encoded = script.attr("src").substringAfter("base64,")
			val decoded = runCatching {
				context.decodeBase64(encoded).toString(Charsets.UTF_8)
			}.getOrNull() ?: continue

			REGEX_ENCRYPTED_DATA.find(decoded)?.groupValues?.getOrNull(1)?.let {
				return JSONObject(it)
			}
			val inlineJson = decoded.substringAfter("InitMangaEncryptedChapter=", "")
				.substringBeforeLast(";")
				.trim()
			if (inlineJson.startsWith("{") && inlineJson.endsWith("}")) {
				return JSONObject(inlineJson)
			}
		}
		return null
	}

	private fun decryptLayered(html: String, encryptedObject: JSONObject): String? {
		val ciphertext = encryptedObject.optString("ciphertext").nullIfEmpty() ?: return null
		val ivHex = encryptedObject.optString("iv").nullIfEmpty() ?: return null
		val saltHex = encryptedObject.optString("salt").nullIfEmpty() ?: return null

		val rawKeyFromScript = Jsoup.parse(html).selectFirst("script#init-main-js-extra")
			?.attr("src")
			?.takeIf { it.contains("base64,") }
			?.substringAfter("base64,")
			?.let { context.decodeBase64(it).toString(Charsets.UTF_8) }
			?.let { REGEX_DECRYPTION_KEY_INSIDE.find(it)?.groupValues?.getOrNull(1) }

		val finalRawKey = rawKeyFromScript
			?: REGEX_SMART_KEY_HTML.find(html)?.groupValues?.getOrNull(1)
			?: return null

		val passphrase = context.decodeBase64(finalRawKey).toString(Charsets.UTF_8)
		return runCatching {
			decryptWithPassphrase(ciphertext, passphrase, saltHex, ivHex)
		}.getOrNull()?.takeIf { decrypted ->
			val trimmed = decrypted.trim()
			trimmed.startsWith("<") || trimmed.startsWith("[")
		}
	}

	private fun parseDecryptedPages(content: String): List<String> {
		val trimmed = content.trim()
		if (trimmed.startsWith("<")) {
			return Jsoup.parseBodyFragment(trimmed, "https://$domain/")
				.select("img")
				.mapNotNull { img ->
					img.attrAsAbsoluteUrlOrNull("data-src")
						?: img.attrAsAbsoluteUrlOrNull("src")
						?: img.attrAsAbsoluteUrlOrNull("data-lazy-src")
				}
		}

		return runCatching {
			val array = JSONArray(trimmed)
			List(array.length()) { index -> array.getString(index) }.map { src ->
				when {
					src.startsWith("//") -> "https:$src"
					src.startsWith("/") -> src.toAbsoluteUrl(domain)
					else -> src
				}
			}
		}.getOrDefault(emptyList())
	}

	private fun decryptWithPassphrase(
		ciphertextBase64: String,
		passphrase: String,
		saltHex: String,
		ivHex: String,
	): String {
		val salt = saltHex.hexToBytes()
		val iv = ivHex.hexToBytes()
		val ciphertext = context.decodeBase64(ciphertextBase64)

		val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
		val spec = PBEKeySpec(passphrase.toCharArray(), salt, 999, 256)
		val keyBytes = factory.generateSecret(spec).encoded
		val secretKey = SecretKeySpec(keyBytes, "AES")

		val cipher = Cipher.getInstance("AES/CBC/PKCS7Padding")
		cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(iv))
		return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
	}

	private fun String.hexToBytes(): ByteArray {
		check(length % 2 == 0) { "Hex string must have even length" }
		return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
	}

	private suspend fun maybeExpandSequentialPages(urls: List<String>): List<String> {
		if (urls.size != 1) return urls
		val firstUrl = urls.first()
		val match = INIT_MANGA_PAGE_SEQUENCE_REGEX.matchEntire(firstUrl) ?: return urls

		val prefix = match.groupValues[1]
		val initialNumber = match.groupValues[2]
		val suffix = match.groupValues[3]
		val startIndex = initialNumber.toIntOrNull() ?: return urls

		val expanded = ArrayList<String>(16)
		for (pageIndex in startIndex..MAX_PROBED_INIT_MANGA_PAGES) {
			val candidate = prefix + pageIndex.toString().padStart(initialNumber.length, '0') + suffix
			if (!doesPageExist(candidate)) {
				break
			}
			expanded += candidate
		}
		return expanded.ifEmpty { urls }
	}

	private suspend fun doesPageExist(url: String): Boolean {
		return runCatching {
			webClient.httpHead(url).use { response ->
				response.code in 200..299 && response.mimeType?.startsWith("image/") == true
			}
		}.recoverCatching { error ->
			when (error) {
				is NotFoundException -> false
				is HttpStatusException -> false
				else -> throw error
			}
		}.getOrDefault(false)
	}

	private fun toMangaPage(url: String): MangaPage = MangaPage(
		id = generateUid(url),
		url = url,
		preview = null,
		source = source,
	)

	private fun Element.findSeriesLink(): Element? = select("a[href]").firstOrNull { a ->
		val href = a.attr("href")
		href.contains("/$mangaUrlDirectory/") &&
			!href.contains("/$mangaUrlDirectory/page/") &&
			!href.contains("/bolum")
	}

	private fun Element.extractSeriesTitle(link: Element): String {
		return selectFirst(
			"h2 a, h2, h3 a, h3, h4 a, h4, a.uk-link-heading, strong.slider-title, strong.uk-h2",
		)
			?.text()
			?.trim()
			?.nullIfEmpty()
			?: link.attr("title").trim().nullIfEmpty()
			?: link.text().trim()
	}

	private companion object {
		private val REGEX_DECRYPTION_KEY_INSIDE =
			Regex("""["']?decryption_key["']?\s*[:=]\s*["']([^"']+)["']""")
		private val REGEX_SMART_KEY_HTML =
			Regex("""InitMangaData[\s\S]*?decryption_key["']?\s*[:=]\s*["']([^"']+)["']""")
		private val REGEX_ENCRYPTED_DATA =
			Regex("""var\s+InitMangaEncryptedChapter\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL)
		private val IMAGE_URL_REGEX = Regex("""https?://[^\s"'<>]+/wp-content/uploads/init-manga/[^\s"'<>]+""")
		private val INIT_MANGA_PAGE_SEQUENCE_REGEX =
			Regex("""(https?://[^\s"'<>]+/wp-content/uploads/init-manga/.*/)(\d+)(\.[A-Za-z0-9]+)$""")
		private val TAG_PATH_REGEX = Regex("""/(?:tur|genre|kategori|category)/[^/]+(?:/[^/]+)?""")
		private val CHAPTER_NUMBER_REGEX =
			Regex("""(?:bolum|bölüm|chapter|ch)[^0-9]*([0-9]+(?:[.,][0-9]+)?)""", RegexOption.IGNORE_CASE)
		private const val MAX_PROBED_INIT_MANGA_PAGES = 500
	}
}
