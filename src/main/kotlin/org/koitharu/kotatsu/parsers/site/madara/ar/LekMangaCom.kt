package org.koitharu.kotatsu.parsers.site.madara.ar

import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat

@MangaSourceParser("LEKMANGACOM", "LekMangaCom", "ar")
internal class LekMangaCom(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.LEKMANGACOM, "lekmanga.site") {

	// lekmanga.com redirects to lekmanga.site, which turns POST requests into GET. Mirrors with the same paths.
	override val configKeyDomain = ConfigKey.Domain("lekmanga.site", "mangalik.net", "like-manga.net")
	override val listUrl = "manga/"
	override val postReq = true

	// A few genres exist twice on the site under different slugs
	override suspend fun fetchAvailableTags(): Set<MangaTag> =
		super.fetchAvailableTags().distinctBy { it.title }.toSet()

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		// Some pages have a second tag first, with a "?post_type=wp-manga&p=<id>" url
		val href = doc.select("head meta[property='og:url']").lastOrNull()?.attr("content")?.toRelativeUrl(domain) ?: manga.url
		val chapters = if (doc.select(selectTestAsync).isEmpty()) loadChapters(href, doc) else getChapters(manga, doc)
		val state = doc.selectFirst(selectState)?.selectLast("div.summary-content")?.let {
			when (it.text().lowercase()) {
				in ongoing -> MangaState.ONGOING
				in finished -> MangaState.FINISHED
				in abandoned -> MangaState.ABANDONED
				in paused -> MangaState.PAUSED
				else -> null
			}
		}
		return manga.copy(
			// The first <h1> of the page is the site's <noscript> notice
			title = doc.selectFirst(".post-title h1")?.textOrNull() ?: manga.title,
			url = href,
			publicUrl = href.toAbsoluteUrl(domain),
			tags = doc.body().select(selectGenre).mapNotNullToSet { a -> createMangaTag(a) },
			description = doc.select(selectDesc).html(),
			altTitles = setOfNotNull(doc.body().select(selectAlt).firstOrNull()?.tableValue()?.textOrNull()),
			state = state,
			chapters = chapters,
			contentRating = if (doc.selectFirst(".adult-confirm") != null) ContentRating.ADULT else ContentRating.SAFE,
		)
	}

	override suspend fun loadChapters(mangaUrl: String, document: Document): List<MangaChapter> {
		val mangaId = document.select("div#manga-chapters-holder").attr("data-id")
		val url = "https://$domain/wp-admin/admin-ajax.php"
		val postData = "action=manga_get_chapters&manga=$mangaId"
		val doc = webClient.httpPost(url, postData).parseHtml()
		val dateFormat = SimpleDateFormat(datePattern, sourceLocale)
		return doc.select(selectChapter).mapChapters { i, li ->
			val a = li.selectFirst("a")
			val href = a?.attrAsRelativeUrlOrNull("href") ?: li.parseFailed("Link is missing")
			val link = href + stylePage
			val dateText = li.selectFirst("a.c-new-tag")?.attr("title") ?: li.selectFirst(selectDate)?.text()
			val name = a.selectFirst("p")?.text() ?: a.ownText()
			MangaChapter(
				id = generateUid(href),
				url = link,
				title = name,
				number = i + 1f,
				volume = 0,
				branch = null,
				uploadDate = parseChapterDate(
					dateFormat,
					dateText,
				),
				scanlator = null,
				source = source,
			)
		}
	}
}
