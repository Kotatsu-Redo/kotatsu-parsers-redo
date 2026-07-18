package org.koitharu.kotatsu.parsers.site.zeistmanga.id

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.site.zeistmanga.ZeistMangaParser
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.asTypedList
import java.text.SimpleDateFormat

@MangaSourceParser("REYUME", "ReYume", "id")
internal class ReYume(context: MangaLoaderContext) :
	ZeistMangaParser(context, MangaParserSource.REYUME, "www.re-yume.my.id") {

	override val selectTags = "a[rel=tag]"
	override val selectPage = "#zeist-manga-reader img"

	override fun parseMangaList(json: JSONArray): List<Manga> {
		return super.parseMangaList(json).map { 
			val cleanUrl = it.url.substringBefore("?m=1").substringBefore("&m=1")
			val cleanPublic = it.publicUrl.substringBefore("?m=1").substringBefore("&m=1")
			it.copy(
				url = cleanUrl,
				publicUrl = cleanPublic,
				id = generateUid(cleanUrl)
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
		val baseDetails = super.getDetails(manga)
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		
		val title = doc.selectFirst("h1[itemprop=name], h1.post-title, h1#post-title")?.text() ?: baseDetails.title
		var desc = baseDetails.description
		if (desc.isNullOrEmpty()) {
			desc = doc.getElementById("synopsis")?.text() 
				?: doc.getElementById("syn_bod")?.text()
				?: doc.selectFirst(".sinopsis")?.text()
				?: ""
		}
		
		val authorText = doc.getElementById("tauther")?.text() ?: doc.getElementById("tauthers")?.text()
		val authors = if (!authorText.isNullOrEmpty()) setOf(authorText) else baseDetails.authors

		val stateText = doc.select("span.capitalize, span.uppercase, span[data-bg]").map { it.text().lowercase() }.firstOrNull { 
			it in ongoing || it in finished || it in abandoned || it in paused 
		}
		val state = when (stateText) {
			in ongoing -> MangaState.ONGOING
			in finished -> MangaState.FINISHED
			in abandoned -> MangaState.ABANDONED
			in paused -> MangaState.PAUSED
			else -> baseDetails.state
		}
		
		val chaptersDeferred = async { loadChapters(manga.url, doc) }

		manga.copy(
			title = title,
			description = desc,
			authors = authors,
			state = state,
			tags = doc.select(selectTags).mapToSet { a ->
				MangaTag(
					key = a.attr("href").substringAfterLast("label/").substringBefore("?"),
					title = a.text().toTitleCase(),
					source = source,
				)
			},
			chapters = chaptersDeferred.await()
		)
	}

	override suspend fun loadChapters(mangaUrl: String, doc: Document): List<MangaChapter> {
		val label = doc.selectFirst(".chapter_get")?.attr("data-labelchapter")
			?: doc.selectFirst(".libraryAdd")?.attr("data-title")
			?: doc.html().substringAfter("clwd.run('", "").substringBefore("');").takeIf { it.isNotEmpty() }
			?: return super.loadChapters(mangaUrl, doc)

		val url = buildString {
			append("https://")
			append(domain)
			append("/feeds/posts/default/-/")
			append(label.urlEncoded())
			append("?alt=json&orderby=published&max-results=9999")
		}
		
		val json = webClient.httpGet(url).parseJson().getJSONObject("feed").getJSONArray("entry").asTypedList<JSONObject>().reversed()
		val dateFormat = SimpleDateFormat(datePattern, sourceLocale)
		val mangaTitle = doc.selectFirst("h1[itemprop=name], h1.post-title, h1#post-title")?.text().orEmpty()
		
		return json.mapIndexedNotNull { i, j ->
			val name = j.getJSONObject("title").getString("\$t")
			val prefixToStrip = if (mangaTitle.isNotEmpty() && name.contains(mangaTitle, ignoreCase = true)) {
				mangaTitle
			} else if (label.isNotEmpty() && name.contains(label, ignoreCase = true)) {
				label
			} else {
				""
			}
			
			val chapterName = if (prefixToStrip.isNotEmpty() && name.contains(prefixToStrip, ignoreCase = true)) {
				name.replace(prefixToStrip, "", ignoreCase = true)
					.trim()
					.trim('-', ',', '~', ':')
					.trim()
					.takeIf { it.isNotEmpty() } ?: name
			} else {
				name
			}
			val href = j.getJSONArray("link").asTypedList<JSONObject>().first { it.getString("rel") == "alternate" }.getString("href")
			val dateText = j.getJSONObject("published").getString("\$t").substringBefore("T")
			val slug = mangaUrl.substringAfterLast('/')
			val slugChapter = href.substringAfterLast('/')
			if (slug == slugChapter) return@mapIndexedNotNull null
			MangaChapter(
				id = generateUid(href),
				url = href,
				title = chapterName,
				number = i + 1f,
				volume = 0,
				branch = null,
				uploadDate = dateFormat.parseSafe(dateText),
				scanlator = null,
				source = source,
			)
		}
	}

	override suspend fun fetchAvailableTags(): Set<MangaTag> {
		val genres = listOf(
			"Action", "Adventure", "Comedy", "Drama", "Fantasy", "Horror", "Mystery", 
			"Psychological", "Romance", "Sci-Fi", "Slice of Life", "Supernatural", 
			"Thriller", "Tragedy", "Isekai", "Magic", "Shounen", "Seinen", "Shoujo", 
			"Josei", "Martial Arts", "Historical", "School Life"
		)
		return genres.mapToSet { tag ->
			MangaTag(key = tag, title = tag, source = source)
		}
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()

		val textarea = doc.getElementById("zeist-raw-data")
		if (textarea != null) {
			val rawHtml = textarea.text()
			val imgRegex = Regex("""<img[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
			val images = imgRegex.findAll(rawHtml).mapNotNull { matchResult ->
				val url = matchResult.groupValues[1]
				MangaPage(
					id = generateUid(url),
					url = url,
					preview = null,
					source = source,
				)
			}.toList()
			
			if (images.isNotEmpty()) {
				return images
			}
		}

		return super.getPages(chapter)
	}
}
