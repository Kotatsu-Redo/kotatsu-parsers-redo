package org.koitharu.kotatsu.parsers.site.madara.en

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.util.mapNotNullToSet
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.toTitleCase

@MangaSourceParser("NOVELCROW", "NovelCrow", "en", ContentType.HENTAI)
internal class Novelcrow(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.NOVELCROW, "novelcrow.com", 18) {

	// Cloudflare blocks admin-ajax.php and /manga/, the search page lists everything
	override val withoutAjax = true

	// Lazy-loaded pages also hold a copy of the image inside <noscript>
	override val selectPage = "div.page-break > img"

	override fun parseMangaList(doc: Document): List<Manga> {
		doc.select(".manga-title-badges").remove()
		return super.parseMangaList(doc)
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val details = super.getDetails(manga)
		val description = details.description?.let { html ->
			Jsoup.parseBodyFragment(html).body().apply { select(".ai-viewports, .code-block").remove() }.html()
		}
		return details.copy(description = description)
	}

	override suspend fun fetchAvailableTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/?s=&post_type=wp-manga").parseHtml()
		return doc.select("input[name='genre[]']").mapNotNullToSet { input ->
			val key = input.attr("value")
			if (key.isEmpty()) return@mapNotNullToSet null
			MangaTag(
				key = key,
				title = input.parent()?.selectFirst("label")?.text()?.toTitleCase(sourceLocale) ?: key,
				source = source,
			)
		}
	}
}
