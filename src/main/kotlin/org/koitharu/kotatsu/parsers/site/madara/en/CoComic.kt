package org.koitharu.kotatsu.parsers.site.madara.en

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.toTitleCase

@MangaSourceParser("COCOMIC", "CoComic", "en", ContentType.HENTAI)
internal class CoComic(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.COCOMIC, "cocomic.co") {

	override val withoutAjax: Boolean
		get() = true

	override val selectChapter: String = "li.wp-manga-chapter:not(.premium):not(.premium-block)"

	// The site lists posts that have no title, and repeats rows within a page when sorted by latest.
	override fun parseMangaList(doc: Document): List<Manga> =
		super.parseMangaList(doc).filter { it.title.isNotBlank() }.distinctBy { it.id }

	override suspend fun fetchAvailableTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/?s=&post_type=wp-manga").parseHtml()
		val result = LinkedHashSet<MangaTag>()
		val seenKeys = HashSet<String>()
		val seenNorm = HashSet<String>()
		for (input in doc.select("input[name='genre[]']")) {
			val key = input.attr("value").trim()
			val rawTitle = input.nextElementSibling()?.text()?.trim() ?: continue
			if (!isValidGenre(key, rawTitle)) continue
			val title = rawTitle.toTitleCase(sourceLocale)
			val norm = normalizeGenre(title)
			if (seenKeys.add(key) && seenNorm.add(norm)) {
				result.add(MangaTag(key = key, title = title, source = source))
			}
		}
		return result
	}

	override suspend fun createMangaTag(a: Element): MangaTag? {
		val key = a.attr("href").removeSuffix("/").substringAfterLast('/').trim()
		val rawTitle = a.text().trim()
		if (!isValidGenre(key, rawTitle)) return null
		return MangaTag(
			key = key,
			title = rawTitle.toTitleCase(sourceLocale),
			source = source,
		)
	}

	private fun isValidGenre(key: String, rawTitle: String): Boolean {
		if (key.length !in 2..25 || rawTitle.length !in 2..25) return false
		val kl = key.lowercase(sourceLocale)
		val tl = rawTitle.lowercase(sourceLocale)
		if (kl in JUNK_KEYS || tl in JUNK_TITLES) return false
		if (!CLEAN_GENRE_REGEX.matches(rawTitle)) return false
		if (CHAPTER_JUNK_REGEX.containsMatchIn(rawTitle) || CHAPTER_JUNK_REGEX.containsMatchIn(key)) return false
		return true
	}

	private fun normalizeGenre(title: String): String {
		val letters = NON_ALPHA_REGEX.replace(title.lowercase(sourceLocale), "")
		return if (letters.length > 4 && letters.endsWith('s') && !letters.endsWith("ss")) {
			letters.dropLast(1)
		} else {
			letters
		}
	}

	private companion object {
		val CLEAN_GENRE_REGEX = Regex("""^[A-Za-z][A-Za-z &'/\-]{1,24}$""")
		val CHAPTER_JUNK_REGEX = Regex("""^(?:ch|chap|chapter|ep|episode|extra|vol|volume|part|side)(?:[\s.\-_]|\d|$)""", RegexOption.IGNORE_CASE)
		val NON_ALPHA_REGEX = Regex("""[^a-z]""")
		val JUNK_KEYS = setOf(
			"18", "cocomic", "completed", "ongoing", "free", "offical", "official-colored",
			"official-trans", "manhwa-hot", "si-fi", "uncategorized", "liexing", "ping-ping-jun",
			"tsundere-gong", "contest-winning", "imageset", "artbook", "fan-colored", "full-color",
			"long-strip", "web-comic",
		)
		val JUNK_TITLES = setOf(
			"cocomic", "completed", "ongoing", "free", "offical", "official colored",
			"official trans", "manhwa hot", "si-fi", "uncategorized", "liexing", "ping ping jun",
			"tsundere-gong", "contest winning", "imageset", "artbook", "fan-colored", "full color",
			"long strip", "web comic",
		)
	}
}
