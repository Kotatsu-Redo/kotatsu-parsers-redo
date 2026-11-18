package org.koitharu.kotatsu.parsers.site.mangareader.es

import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.asTypedList
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@MangaSourceParser("LECTORHENTAI", "LectorHentai", "es", ContentType.HENTAI)
internal class LectorHentai(context: MangaLoaderContext) :
	MangaReaderParser(context, MangaParserSource.LECTORHENTAI, "lectorhentai.com", pageSize = 25, searchPageSize = 25) {

	override val listUrl = "/tipo/all"
	override val datePattern = "yyyy-MM-dd HH:mm:ss"

	override fun getRequestHeaders(): Headers = Headers.Builder()
		.add("User-Agent", config[userAgentKey])
		.add("Referer", "https://$domain/")
		.build()

	override fun intercept(chain: Interceptor.Chain): Response {
		val response = super.intercept(chain)
		if (response.isSuccessful && response.mimeType == null) {
			val path = response.request.url.encodedPath.lowercase(Locale.ROOT)
			val mimeType = when {
				path.endsWith(".webp") -> "image/webp"
				path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
				path.endsWith(".png") -> "image/png"
				path.endsWith(".gif") -> "image/gif"
				path.endsWith(".avif") -> "image/avif"
				else -> null
			}
			if (mimeType != null) {
				return response.newBuilder()
					.setHeader("Content-Type", mimeType)
					.build()
			}
		}
		return response
	}

	override val filterCapabilities: MangaListFilterCapabilities
		get() = super.filterCapabilities.copy(
			isTagsExclusionSupported = false,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = getOrCreateTagMap().values.toSet(),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = urlBuilder().apply {
			addPathSegments("tipo/all")
			addQueryParameter("lenguaje", SITE_LANG)
			if (!filter.query.isNullOrEmpty()) {
				addQueryParameter("s", filter.query)
			} else {
				addQueryParameter(
					"order",
					when (order) {
						SortOrder.ALPHABETICAL -> "title"
						SortOrder.ALPHABETICAL_DESC -> "titlereverse"
						SortOrder.NEWEST -> "latest"
						SortOrder.POPULARITY -> "popular"
						SortOrder.UPDATED -> "update"
						else -> ""
					},
				)
				filter.tags.forEach { addQueryParameter("genre[]", it.key) }
			}
			addQueryParameter("page", page.toString())
		}.build()
		return parseMangaList(webClient.httpGet(url).parseHtml())
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val docs = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val dateFormat = SimpleDateFormat(datePattern, sourceLocale).apply {
			timeZone = TimeZone.getTimeZone("Europe/Madrid")
		}
		val chapterLink = docs.selectFirstOrThrow("a.leer")
		val chapterUrl = chapterLink.attrAsRelativeUrl("href")
		val uploadDate = dateFormat.parseSafe(
			docs.selectFirst(".fmed:contains(Publicado) time")?.attrOrNull("datetime"),
		)
		val chapters = listOf(
			MangaChapter(
				id = generateUid(chapterUrl),
				title = "Chapter",
				url = chapterUrl,
				number = 1f,
				volume = 0,
				scanlator = null,
				uploadDate = uploadDate,
				branch = null,
				source = source,
			),
		)
		return parseInfo(docs, manga, chapters)
	}

	override suspend fun parseInfo(docs: Document, manga: Manga, chapters: List<MangaChapter>): Manga {
		val tagMap = getOrCreateTagMap()
		val tagLookup = tagMap.values.associateBy { it.key.lowercase(Locale.ROOT) }
		val tags = docs.select(".wd-full:has(b:contains(Generos)) a, .wd-full:has(b:contains(Tags)) a")
			.mapNotNullToSet { el ->
				val text = el.textOrNull() ?: return@mapNotNullToSet null
				tagMap[text] ?: tagLookup[text.lowercase(Locale.ROOT)]
			}

		val authors = docs.select(".wd-full:has(b:contains(Artista)) a")
			.mapNotNullToSet { it.textOrNull() }

		val title = docs.selectFirst(".wd-full:has(b:contains(Título)) span")?.textOrNull()
			?: manga.title

		val rating = docs.selectFirst("[class^=resultMedia]")?.textOrNull()
			?.let(RATING_REGEX::find)
			?.groupValues
			?.getOrNull(1)
			?.toFloatOrNull()
			?.div(5f)
			?.coerceIn(0f, 1f)
			?: RATING_UNKNOWN

		val description = buildList {
			docs.selectFirst(".imptdt:contains(Tipo) a")?.textOrNull()?.let { add("Tipo: $it") }
			docs.selectFirst(".imptdt:contains(Sección) a")?.textOrNull()?.let { add("Sección: $it") }
			val magazines = docs.select(".wd-full:has(b:contains(Revistas)) a")
				.mapNotNull { it.textOrNull()?.takeUnless { v -> v == "-" } }
			if (magazines.isNotEmpty()) {
				add("Revistas: ${magazines.joinToString()}")
			}
			docs.selectFirst(detailsDescriptionSelector)?.textOrNull()?.let(::add)
		}.joinToString("\n").nullIfEmpty()

		return manga.copy(
			title = title,
			publicUrl = manga.url.toAbsoluteUrl(domain),
			coverUrl = docs.selectFirst(".thumb img")?.src() ?: manga.coverUrl,
			rating = rating,
			description = description,
			state = MangaState.FINISHED,
			authors = authors,
			contentRating = ContentRating.ADULT,
			tags = tags,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val chapterUrl = chapter.url.toAbsoluteUrl(domain)
		val docs = webClient.httpGet(chapterUrl).parseHtml()
		val packedScript = docs.select("script")
			.map { it.data() }
			.firstOrNull { "eval(function(p,a,c,k,e,d)" in it && "ReaderBlob" in it }
			?: docs.parseFailed("ReaderBlob script not found")

		val unpacked = unpackScript(packedScript)
		val blobJsonStr = unpacked.substringAfter("ReaderBlob.start(").substringBefore(")")
		val blobJson = JSONObject(blobJsonStr)
		val keyBytes = context.decodeBase64(blobJson.getString("k"))
		val ivStr = blobJson.getStringOrNull("iv") ?: blobJson.getString("V")
		val ivBytes = context.decodeBase64(ivStr)
		val cipherBytes = context.decodeBase64(blobJson.getString("c"))

		val cipher = Cipher.getInstance("AES/GCM/NoPadding")
		cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, ivBytes))
		val decryptedJson = JSONObject(String(cipher.doFinal(cipherBytes), Charsets.UTF_8))

		val rawPages = decryptedJson.getJSONArray("p").asTypedList<String>()
		if (rawPages.isEmpty()) {
			docs.parseFailed("Decrypted page list is empty")
		}
		return rawPages.map { rawUrl ->
			val imageUrl = when {
				rawUrl.startsWith("//") -> "https:$rawUrl"
				rawUrl.startsWith("/") -> rawUrl.toAbsoluteUrl(domain)
				else -> rawUrl
			}
			MangaPage(
				id = generateUid(imageUrl),
				url = imageUrl,
				preview = null,
				source = source,
			)
		}
	}

	override suspend fun resolveLink(resolver: LinkResolver, link: HttpUrl): Manga? {
		val segments = link.pathSegments
		if (segments.size < 3 || segments[0] !in LINK_SEGMENTS) {
			return null
		}
		val url = "/manga/${segments[1]}/${segments[2]}"
		val doc = webClient.httpGet(url.toAbsoluteUrl(domain)).parseHtml()
		if (doc.selectFirst(".imptdt:contains(Idioma) i")?.textOrNull() != SITE_LANG) {
			return null
		}
		return resolver.resolveManga(this, url, generateUid(url))
	}

	private fun unpackScript(packedScript: String): String {
		val match = PACKER_REGEX.find(packedScript)
			?: throw IllegalStateException("Packer format not found")
		val payload = match.groupValues[1]
		val base = match.groupValues[2].toInt()
		val dictionary = match.groupValues[4].split('|')
		val tokenMap = HashMap<String, String>(dictionary.size)
		for (i in dictionary.indices) {
			val word = dictionary[i]
			if (word.isNotEmpty()) {
				tokenMap[encodeBase(i, base)] = word
			}
		}
		return WORD_REGEX.replace(payload) { tokenMap[it.value] ?: it.value }
			.replace("\\/", "/")
			.replace("\\'", "'")
			.replace("\\\"", "\"")
	}

	private fun encodeBase(num: Int, base: Int): String {
		if (num == 0) return "0"
		var n = num
		val sb = StringBuilder()
		while (n > 0) {
			sb.append(BASE62_ALPHABET[n % base])
			n /= base
		}
		return sb.reverse().toString()
	}

	private companion object {
		const val SITE_LANG = "Español"
		const val BASE62_ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
		val LINK_SEGMENTS = setOf("manga", "read")
		val RATING_REGEX = Regex("""([\d.]+)/5\s*\((\d+)""")
		val PACKER_REGEX = Regex("""(?s)[}]\s*\(\s*['"](.*?)['"]\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*['"](.*?)['"]\.split""")
		val WORD_REGEX = Regex("""\b[0-9a-zA-Z]+\b""")
	}
}
