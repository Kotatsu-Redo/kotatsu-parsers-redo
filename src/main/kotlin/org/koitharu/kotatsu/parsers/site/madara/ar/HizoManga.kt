package org.koitharu.kotatsu.parsers.site.madara.ar

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.network.UserAgents
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.util.*

/**
 * The site also hosts novels, which are most of what it updates. Their chapters are text, so only the titles
 * with image chapters are listed. The page of a title is the site's own design, unlike the rest of the theme.
 */
@MangaSourceParser("HIZOMANGA", "HizoManga", "ar")
internal class HizoManga(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.HIZOMANGA, "hizomanga.net") {

	// The site sends whoever says it is an Android WebView ("; wv" in the user agent, which is what the app says by
	// default) to /no-webview.html, which sends it there again without end. The tests never saw it: they say Firefox.
	override val userAgentKey = ConfigKey.UserAgent(UserAgents.CHROME_MOBILE)

	override val datePattern = "yyyy-MM-dd"
	override val listUrl = "series/"
	override val mangaChaptersOnly = true

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		return manga.copy(
			title = doc.selectFirst("div.manga-title > h2")?.textOrNull() ?: manga.title,
			coverUrl = doc.selectFirst("div.summary_image img")?.src() ?: manga.coverUrl,
			tags = doc.select(selectGenre).mapNotNullToSet { a -> createMangaTag(a) },
			authors = doc.select("div.manga-author a").mapNotNullToSet { it.textOrNull() },
			description = doc.selectFirst("div.manga-excerpt div.excerpt-content")?.html(),
			state = when (doc.selectFirst("div.manga-status")?.text()?.trim()?.lowercase().orEmpty()) {
				in ongoing -> MangaState.ONGOING
				in finished -> MangaState.FINISHED
				"متوقفة", in paused -> MangaState.PAUSED
				in abandoned -> MangaState.ABANDONED
				in upcoming -> MangaState.UPCOMING
				else -> null
			},
			contentRating = if (doc.selectFirst(".adult-confirm") != null || isNsfwSource) {
				ContentRating.ADULT
			} else {
				ContentRating.SAFE
			},
			chapters = getChapters(manga, doc),
		)
	}
}
