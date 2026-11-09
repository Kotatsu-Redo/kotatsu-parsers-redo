package org.koitharu.kotatsu.parsers.site.mangareader.es

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser
import org.koitharu.kotatsu.parsers.util.*

@MangaSourceParser("MANGATV", "MangaTv", "es")
internal class MangaTv(context: MangaLoaderContext) :
	MangaReaderParser(context, MangaParserSource.MANGATV, "mangatv.net", pageSize = 25, searchPageSize = 25) {
	override val listUrl = "/lista"
	override val detailsDescriptionSelector = "b:contains(Sinopsis) + span"
	override val filterCapabilities: MangaListFilterCapabilities
		get() = super.filterCapabilities.copy(
			isTagsExclusionSupported = false,
		)
	override val datePattern = "yyyy-MM-dd"

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://")
			append(domain)

			when {

				!filter.query.isNullOrEmpty() -> {
					append("/lista?s=")
					append(filter.query.urlEncoded())
					append("&page=")
					append(page.toString())
				}

				else -> {
					append(listUrl)

					append("/?order=")
					append(
						when (order) {
							SortOrder.ALPHABETICAL -> "title"
							SortOrder.ALPHABETICAL_DESC -> "titlereverse"
							SortOrder.NEWEST -> "latest"
							SortOrder.POPULARITY -> "popular"
							SortOrder.UPDATED -> "update"
							else -> ""
						},
					)

					filter.tags.forEach {
						append("&")
						append("genre[]".urlEncoded())
						append("=")
						append(it.key)
					}

					filter.tagsExclude.forEach {
						append("&")
						append("genre[]".urlEncoded())
						append("=-")
						append(it.key)
					}

					if (filter.states.isNotEmpty()) {
						filter.states.oneOrThrowIfMany()?.let {
							append("&status=")
							when (it) {
								MangaState.ONGOING -> append("ongoing")
								MangaState.FINISHED -> append("completed")
								MangaState.PAUSED -> append("hiatus")
								else -> append("")
							}
						}
					}

					append("&page=")
					append(page.toString())
				}
			}
		}
		return parseMangaList(webClient.httpGet(url).parseHtml())
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val chapterUrl = chapter.url.toAbsoluteUrl(domain)
		val docs = webClient.httpGet(chapterUrl).parseHtml()

		val script = docs.select("script").firstOrNull {
			val data = it.data()
			(data.contains("ReaderBlob") || data.contains("ts_reader")) && data.contains("eval(function")
		} ?: docs.select("script").firstOrNull {
			val data = it.data()
			data.contains("ReaderBlob") || data.contains("ts_reader")
		} ?: error("Image script not found")

		val scriptData = script.data()
		val unpackedData = if (scriptData.contains("eval(function") && scriptData.contains(".split(")) {
			unpackScript(scriptData)
		} else {
			scriptData
		}

		val imageUrls = ArrayList<String>()

		if (unpackedData.contains("ReaderBlob")) {
			runCatching {
				val readerBlobJsonStr = unpackedData.substringAfter("ReaderBlob.start(").substringBefore(")")
				val blobJson = JSONObject(readerBlobJsonStr)
				val kStr = blobJson.getString("k")
				val ivStr = if (blobJson.has("iv")) blobJson.getString("iv") else blobJson.getString("V")
				val cStr = blobJson.getString("c")

				val keyBytes = context.decodeBase64(kStr)
				val ivBytes = context.decodeBase64(ivStr)
				val cipherBytes = context.decodeBase64(cStr)

				val cipher = Cipher.getInstance("AES/GCM/NoPadding")
				val secretKey = SecretKeySpec(keyBytes, "AES")
				val spec = GCMParameterSpec(128, ivBytes)
				cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
				val decryptedBytes = cipher.doFinal(cipherBytes)
				val decryptedJsonStr = String(decryptedBytes, Charsets.UTF_8)

				val decryptedJson = JSONObject(decryptedJsonStr)
				val pArray = decryptedJson.getJSONArray("p")
				for (i in 0 until pArray.length()) {
					imageUrls.add(pArray.getString(i))
				}
			}
		}

		if (imageUrls.isEmpty()) {
			val imagesRegex = Regex("""["']images["']\s*:\s*\[(.*?)\]""", RegexOption.IGNORE_CASE)
			val imagesMatch = imagesRegex.find(unpackedData)
			if (imagesMatch != null) {
				val imagesRawString = imagesMatch.groupValues[1]
				val urlRegex = Regex("""["']([^"']+)["']""")
				val rawUrls = urlRegex.findAll(imagesRawString).map { it.groupValues[1] }.toList()

				for (rawUrl in rawUrls) {
					val cleanUrl = rawUrl.replace("\\", "")
					if (cleanUrl.contains("readerarea.svg")) continue

					var decodedUrl = cleanUrl
					if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://") && !cleanUrl.startsWith("//") && !cleanUrl.startsWith("/")) {
						decodedUrl = runCatching {
							String(context.decodeBase64(cleanUrl))
						}.getOrDefault(cleanUrl)
					}

					if (decodedUrl.isNotBlank() && !decodedUrl.contains("readerarea.svg")) {
						imageUrls.add(decodedUrl)
					}
				}
			}
		}

		if (imageUrls.isEmpty()) error("The image URLs could not be extracted")

		val pages = ArrayList<MangaPage>(imageUrls.size)
		for (rawUrl in imageUrls) {
			var decodedUrl = rawUrl
			if (decodedUrl.startsWith("//")) {
				decodedUrl = "https:$decodedUrl"
			}
			pages.add(
				MangaPage(
					id = generateUid(decodedUrl),
					url = decodedUrl,
					preview = null,
					source = source,
				),
			)
		}
		return pages
	}

	private fun unpackScript(packedScript: String): String {
		val regex = Regex("""(?s)[}]\s*\(\s*['"](.*?)['"]\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*['"](.*?)['"]\.split""")
		val match = regex.find(packedScript) ?: error("Formato Packer no found")

		var payload = match.groupValues[1]
		val base = match.groupValues[2].toInt()
		val dictionary = match.groupValues[4].split("|")

		val lookUp = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

		fun toBase(num: Int, baseToUse: Int): String {
			if (num == 0) return "0"
			var n = num
			var res = ""
			while (n > 0) {
				res = lookUp[n % baseToUse] + res
				n /= baseToUse
			}
			return res
		}

		for (i in dictionary.indices.reversed()) {
			val word = dictionary[i]
			if (word.isNotEmpty()) {
				val encodedKey = toBase(i, base)
				payload = payload.replace(Regex("""\b${Regex.escape(encodedKey)}\b""")) { word }
			}
		}

		return payload
			.replace("\\/", "/")
			.replace("\\'", "'")
			.replace("\\\"", "\"")
	}

}
