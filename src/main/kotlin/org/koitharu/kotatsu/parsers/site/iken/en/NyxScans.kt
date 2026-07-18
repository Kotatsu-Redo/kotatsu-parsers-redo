package org.koitharu.kotatsu.parsers.site.iken.en

import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.iken.IkenParser
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.src
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.urlEncoded
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.json.mapJSONNotNull

@MangaSourceParser("NYXSCANS", "Nyx Scans", "en")
internal class NyxScans(context: MangaLoaderContext) :
	IkenParser(context, MangaParserSource.NYXSCANS, "nyxscans.com", 18, true) {

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		if (order == SortOrder.POPULARITY && filter.isEmpty()) {
			if (page != 1) {
				return emptyList()
			}
			return runCatching { parsePopularManga() }.getOrElse {
				super.getListPage(page, order, filter)
			}
		}
		return super.getListPage(page, order, filter)
	}

	override fun parseMangaList(json: JSONObject): List<Manga> {
		return super.parseMangaList(json).filterNot { isBlockedTitle(it.title) }
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val apiPages = runCatching { parsePagesFromApi(chapter) }.getOrElse { error ->
			if (error.message?.contains("unlock", ignoreCase = true) == true) {
				throw error
			}
			emptyList()
		}
		apiPages.takeIf { it.isNotEmpty() }?.let { return it }
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
		if (doc.selectFirst("svg.lucide-lock") != null) {
			throw Exception("Need to unlock chapter!")
		}
		val images = doc.select(selectPages)
			.mapNotNull { img -> img.src() }
			.distinct()
		if (images.isEmpty()) {
			throw Exception("Chapter images are unavailable")
		}
		return images.map { url ->
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}

	private suspend fun parsePagesFromApi(chapter: MangaChapter): List<MangaPage> {
		readChapterImages(chapter.id).takeIf { it.isNotEmpty() }?.let { return it }
		val pathParts = chapter.url.substringBefore('?').trim('/').split('/')
		if (pathParts.size < 3 || pathParts[0] != "series") return emptyList()
		val seriesSlug = pathParts[1]
		val chapterSlug = pathParts[2]
		val postId = findPostIdBySlug(seriesSlug) ?: return emptyList()
		val chaptersJson = webClient.httpGet(
			"https://$defaultDomain/api/chapters?postId=$postId&skip=0&take=900&order=desc&userid=",
		).parseJson()
		val chapterId = chaptersJson.optJSONObject("post")
			?.optJSONArray("chapters")
			?.mapJSONNotNull { item ->
				if (item.optString("slug") == chapterSlug) {
					item.optLong("id", 0L).takeIf { it > 0L }
				} else {
					null
				}
			}
			?.firstOrNull()
			?: return emptyList()
		return readChapterImages(chapterId)
	}

	private suspend fun findPostIdBySlug(seriesSlug: String): Long? {
		val json = webClient.httpGet(
			"https://$defaultDomain/api/query?page=1&perPage=5&searchTerm=${seriesSlug.urlEncoded()}",
		).parseJson()
		return json.optJSONArray("posts")
			?.mapJSONNotNull { post ->
				if (post.optString("slug") == seriesSlug) {
					post.optLong("id", 0L).takeIf { it > 0L }
				} else {
					null
				}
			}
			?.firstOrNull()
	}

	private suspend fun parsePopularManga(): List<Manga> {
		val json = webClient.httpGet("https://$domain").parseHtml().getNextJson("popularPosts")
		return JSONArray(json).mapJSONNotNull {
			val title = it.getString("postTitle")
			if (isBlockedTitle(title)) return@mapJSONNotNull null
			val url = "/series/${it.getString("slug")}"
			Manga(
				id = it.getLong("id"),
				url = url,
				publicUrl = url.toAbsoluteUrl(domain),
				coverUrl = it.getStringOrNull("featuredImage"),
				title = title,
				altTitles = emptySet(),
				description = null,
				rating = RATING_UNKNOWN,
				tags = emptySet(),
				authors = emptySet(),
				state = null,
				source = source,
				contentRating = null,
			)
		}
	}

	private fun isBlockedTitle(title: String): Boolean {
		return title.contains("[Novel]", ignoreCase = true)
	}
}
