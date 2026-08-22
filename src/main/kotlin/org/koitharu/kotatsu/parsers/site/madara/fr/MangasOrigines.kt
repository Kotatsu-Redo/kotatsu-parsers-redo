package org.koitharu.kotatsu.parsers.site.madara.fr

import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.util.*

@MangaSourceParser("MANGASORIGINES", "MangasOrigines.fr", "fr")
internal class MangasOrigines(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.MANGASORIGINES, "mangas-origines.fr") {
	override val datePattern = "dd/MM/yyyy"
	override val tagPrefix = "manga-genres/"
	override val listUrl = "oeuvre/"

	// The "child-origines" theme redesign puts the synopsis in <div class="ori-sr-syn-texte">,
	// which none of the default Madara selectors match (summaries came up empty). Keep the
	// base selectors as a fallback for any page still using the old markup.
	override val selectDesc =
		"div.ori-sr-syn-texte, div.description-summary div.summary__content, " +
			"div.summary_content div.post-content_item > h5 + div, div.summary_content div.manga-excerpt, " +
			"div.post-content div.manga-summary, div.post-content div.desc, div.c-page__content div.summary__content"

	// The "child-origines" theme loads chapters through a custom, nonce-protected
	// AJAX endpoint (the standard Madara /ajax/chapters/ and manga_get_chapters both
	// return 403/400). Instead we read the complete chapter list from the
	// <select class="single-chapter-select"> that every chapter page contains.
	override suspend fun getChapters(manga: Manga, doc: Document): List<MangaChapter> {
		return parseChaptersFromSelect(doc)
	}

	override suspend fun loadChapters(mangaUrl: String, document: Document): List<MangaChapter> {
		return parseChaptersFromSelect(document)
	}

	private suspend fun parseChaptersFromSelect(mangaDoc: Document): List<MangaChapter> {
		val anyChapterHref = mangaDoc.selectFirst("a[href*=/chapitre-]")
			?.attr("href")
			?.takeIf { it.isNotEmpty() }
			?: return emptyList()
		val chapterDoc = webClient.httpGet(anyChapterHref.toAbsoluteUrl(domain)).parseHtml()
		val seen = HashSet<String>()
		return chapterDoc.select("select.single-chapter-select option[data-redirect]")
			.mapNotNull { option ->
				val redirect = option.attr("data-redirect")
				if ("/chapitre-" !in redirect || redirect.contains("style=")) {
					return@mapNotNull null
				}
				val url = redirect.toRelativeUrl(domain)
				if (!seen.add(url)) {
					return@mapNotNull null
				}
				val numText = url.substringAfterLast("/chapitre-").removeSuffix("/")
				val number = numText.replace('-', '.').toFloatOrNull() ?: 0f
				MangaChapter(
					id = generateUid(url),
					title = null,
					number = number,
					volume = 0,
					url = url,
					uploadDate = 0L,
					source = source,
					scanlator = null,
					branch = null,
				)
			}
			.sortedBy { it.number }
	}

	// Images are <img class="wp-manga-chapter-img" data-src=" https://..."> — the reader now
	// lazy-loads them (real URL in data-src, empty src) and keeps the leading space in the URL.
	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(fullUrl).parseHtml()
		val images = doc.select("div.reading-content img.wp-manga-chapter-img")
			.ifEmpty { doc.select("img.wp-manga-chapter-img") }
		return images.mapNotNull { img ->
			val src = img.attr("data-src").trim().takeIf { it.isNotEmpty() }
				?: img.attr("src").trim().takeIf { it.isNotEmpty() }
				?: return@mapNotNull null
			val url = src.toRelativeUrl(domain)
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}
}
