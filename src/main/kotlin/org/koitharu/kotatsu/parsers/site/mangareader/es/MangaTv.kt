package org.koitharu.kotatsu.parsers.site.mangareader.es

import java.util.Base64
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser
import org.koitharu.kotatsu.parsers.util.*

@MangaSourceParser("MANGATV", "MangaTv", "es")
internal class MangaTv(context: MangaLoaderContext) :
	MangaReaderParser(context, MangaParserSource.MANGATV, "www.mangatv.net", pageSize = 25, searchPageSize = 25) {
	override val listUrl = "/lista"
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
            data.contains("eval(function") && data.contains(".split(")
        } ?: error("Image script not found")

        val packedData = script.data()
        val unpackedData = unpackScript(packedData)

        val imagesRegex = Regex("""["']images["']\s*:\s*\[(.*?)\]""", RegexOption.IGNORE_CASE)
        val imagesMatch = imagesRegex.find(unpackedData)
            ?: error("The array 'images' was not found in the deobfuscated code")

        val imagesRawString = imagesMatch.groupValues[1]

        val urlRegex = Regex("""["']([^"']+)["']""")
        val encodedUrls = urlRegex.findAll(imagesRawString).map { it.groupValues[1] }.toList()

        if (encodedUrls.isEmpty()) error("The URLs could not be extracted")

        val pages = ArrayList<MangaPage>(encodedUrls.size)

        for (encodedUrl in encodedUrls) {
            val cleanBase64 = encodedUrl.replace("\\", "")

            val decodedBytes = Base64.getDecoder().decode(cleanBase64)
            var decodedUrl = String(decodedBytes)

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
        val match = regex.find(packedScript) ?: error("Formato Packer no encontrado")

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
