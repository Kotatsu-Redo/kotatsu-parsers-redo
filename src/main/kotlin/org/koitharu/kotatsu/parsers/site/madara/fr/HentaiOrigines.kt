package org.koitharu.kotatsu.parsers.site.madara.fr

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat

@MangaSourceParser("HENTAIORIGINES", "HentaiOrigines", "fr", ContentType.HENTAI)
internal class HentaiOrigines(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.HENTAIORIGINES, "hentai-origines.com") {

	override val datePattern = "d MMMM yyyy"

	override val selectDesc = "div.ori-sr-syn-texte"
	override val selectGenre = "a.ori-sr-genre"
	override val selectChapter = "div.ori-chl-row"
	override val selectBodyPage = "main.ori-lec-scene div.reading-content"

	override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
		val fullUrl = manga.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(fullUrl).parseHtml()

		val href = doc.selectFirst("head meta[property='og:url']")?.attr("content")?.toRelativeUrl(domain) ?: manga.url
		val chaptersDeferred = async { loadChapters(href, doc) }

		val desc = doc.select(selectDesc).html()

		val state = doc.selectFirst("span.ori-sr-badge-statut")?.let {
			when (it.text().lowercase()) {
				in ongoing -> MangaState.ONGOING
				in finished -> MangaState.FINISHED
				in abandoned -> MangaState.ABANDONED
				in paused -> MangaState.PAUSED
				else -> null
			}
		}

		manga.copy(
			title = doc.selectFirst("h1")?.textOrNull() ?: manga.title,
			url = href,
			publicUrl = href.toAbsoluteUrl(domain),
			tags = doc.body().select(selectGenre).mapToSet { a -> createMangaTag(a) }.filterNotNull().toSet(),
			description = desc,
			altTitles = emptySet(),
			state = state,
			chapters = chaptersDeferred.await(),
			contentRating = if (doc.selectFirst(".adult-confirm") != null || isNsfwSource) {
				ContentRating.ADULT
			} else {
				ContentRating.SAFE
			},
		)
	}

	override suspend fun getChapters(manga: Manga, doc: Document): List<MangaChapter> = doc.parseChapters()

	override suspend fun loadChapters(mangaUrl: String, document: Document): List<MangaChapter> {
		val url = mangaUrl.toAbsoluteUrl(domain).removeSuffix('/') + "/ajax/chapters/"
		val doc = webClient.httpPost(url, emptyMap()).parseHtml()
		return doc.parseChapters()
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(fullUrl).parseHtml()
		val root = doc.body().selectFirst(selectBodyPage) ?: throw ParseException(
			"No image found, try to log in",
			fullUrl,
		)
		return root.select(selectPage).flatMap { div ->
			div.selectOrThrow("img").map { img ->
				val url = img.attr("src").trim().toRelativeUrl(domain)
				MangaPage(
					id = generateUid(url),
					url = url,
					preview = null,
					source = source,
				)
			}
		}
	}

	override suspend fun getRelatedManga(seed: Manga): List<Manga> {
		val doc = webClient.httpGet(seed.url.toAbsoluteUrl(domain)).parseHtml()
		val root = doc.body().selectFirst("div.ori-sr-meme-grid") ?: return emptyList()
		return root.select("a.ori-sr-meme-card").mapNotNull { a ->
			val href = a.attrAsRelativeUrl("href")
			Manga(
				id = generateUid(href),
				url = href,
				publicUrl = href.toAbsoluteUrl(a.host ?: domain),
				altTitles = emptySet(),
				title = a.selectFirst("span.ori-sr-meme-titre")?.text().orEmpty(),
				authors = emptySet(),
				coverUrl = a.selectFirst("img")?.src(),
				tags = emptySet(),
				rating = RATING_UNKNOWN,
				state = null,
				contentRating = if (isNsfwSource) ContentRating.ADULT else null,
				source = source,
			)
		}
	}

	private fun Document.parseChapters(): List<MangaChapter> {
		val dateFormat = SimpleDateFormat(datePattern, sourceLocale)
		return select("div.ori-chl-row").mapChapters(reversed = true) { i, row ->
			val a = row.selectFirstOrThrow("a.ori-chl-corps")
			val href = a.attrAsRelativeUrl("href")
			val link = href + stylePage
			val name = a.selectFirst("span.ori-chl-nom-long")?.text().orEmpty().ifEmpty {
				row.attr("data-nom")
			}
			val dateText = row.selectFirst("span.ori-chl-date")?.attr("title")
			MangaChapter(
				id = generateUid(href),
				url = link,
				title = name,
				number = i + 1f,
				volume = 0,
				branch = null,
				uploadDate = parseChapterDate(dateFormat, dateText),
				scanlator = null,
				source = source,
			)
		}
	}
}
