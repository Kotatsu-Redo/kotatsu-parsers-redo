package org.koitharu.kotatsu.parsers.site.en

import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.SinglePageMangaParser
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.attrAsRelativeUrl
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.ownTextOrNull
import org.koitharu.kotatsu.parsers.util.parseFailed
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.requireSrc
import org.koitharu.kotatsu.parsers.util.src
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import java.util.EnumSet

@MangaSourceParser("TWOKINDS", "Twokinds", "en", ContentType.COMICS)
internal class Twokinds(context: MangaLoaderContext) :
	SinglePageMangaParser(context, MangaParserSource.TWOKINDS) {

	override val configKeyDomain = ConfigKey.Domain("twokinds.keenspot.com")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.NEWEST)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
		)

	override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions(
		availableTags = defaultTags(),
	)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override suspend fun getList(order: SortOrder, filter: MangaListFilter): List<Manga> {
		val q = filter.query?.trim()
		if (!q.isNullOrEmpty() && !COMIC_TITLE.contains(q, ignoreCase = true) && !AUTHOR_NAME.contains(q, ignoreCase = true)) {
			return emptyList()
		}
		val doc = webClient.httpGet("https://$domain$ARCHIVE_PATH").parseHtml()
		return listOf(buildManga(doc, includeChapters = false))
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet("https://$domain$ARCHIVE_PATH").parseHtml()
		return buildManga(doc, includeChapters = true)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val encodedPages = chapter.url.substringAfter("pages=", "")
		val slugs = encodedPages.split(',').filter { it.isNotBlank() }
		if (slugs.isEmpty()) {
			val chId = chapter.url.substringAfter("ch=", "").substringBefore('&')
			val doc = webClient.httpGet("https://$domain$ARCHIVE_PATH").parseHtml()
			val section = doc.selectFirst("section.chapter[data-ch-id=$chId]")
				?: throw IllegalStateException("Chapter section not found: $chId")
			return section.select("aside.chapter-links > a").map { a ->
				val rel = a.attrAsRelativeUrl("href")
				MangaPage(
					id = generateUid(rel),
					url = rel,
					preview = a.selectFirst("img")?.src(),
					source = source,
				)
			}
		}
		return slugs.map { slug ->
			val rel = "/comic/$slug/"
			MangaPage(
				id = generateUid(rel),
				url = rel,
				preview = null,
				source = source,
			)
		}
	}

	override suspend fun getPageUrl(page: MangaPage): String {
		if (page.url.startsWith("http://") || page.url.startsWith("https://")) {
			return page.url
		}
		val doc = webClient.httpGet(page.url.toAbsoluteUrl(domain)).parseHtml()
		val img = doc.selectFirst("#content article img, article.comic img, main article img")
			?: doc.parseFailed("Comic image not found")
		return img.requireSrc().toAbsoluteUrl(domain)
	}

	override suspend fun getRelatedManga(seed: Manga): List<Manga> = emptyList()

	private fun buildManga(doc: Document, includeChapters: Boolean): Manga {
		val sections = doc.select("section.chapter")
		val coverUrl = sections.lastOrNull()
			?.select("aside.chapter-links > a img")
			?.lastOrNull()
			?.src()
			?.toAbsoluteUrl(domain)

		val chapters = if (includeChapters) {
			sections.mapIndexedNotNull { index, section ->
				val links = section.select("aside.chapter-links > a")
				if (links.isEmpty()) return@mapIndexedNotNull null
				val chId = section.attr("data-ch-id").ifBlank { (index + 1).toString() }
				val title = section.selectFirst("h2")?.ownTextOrNull()?.trim()?.nullIfEmpty()
					?: "Chapter ${index + 1}"
				val slugs = links.mapNotNull { a ->
					a.attr("href").trim('/').substringAfter("comic/", "").nullIfEmpty()
				}
				if (slugs.isEmpty()) return@mapIndexedNotNull null
				val chUrl = "$ARCHIVE_PATH?ch=$chId&pages=${slugs.joinToString(",")}"
				MangaChapter(
					id = generateUid("$ARCHIVE_PATH#ch-$chId"),
					title = title,
					number = index + 1f,
					volume = 0,
					url = chUrl,
					scanlator = null,
					uploadDate = 0L,
					branch = null,
					source = source,
				)
			}
		} else {
			null
		}

		val descMeta = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()?.nullIfEmpty()
		return Manga(
			id = generateUid(ARCHIVE_PATH),
			url = ARCHIVE_PATH,
			publicUrl = "https://$domain$ARCHIVE_PATH",
			coverUrl = coverUrl,
			title = COMIC_TITLE,
			altTitles = emptySet(),
			rating = RATING_UNKNOWN,
			contentRating = ContentRating.SUGGESTIVE,
			tags = defaultTags(),
			state = MangaState.ONGOING,
			authors = setOf(AUTHOR_NAME),
			description = descMeta ?: DEFAULT_DESCRIPTION,
			chapters = chapters,
			source = source,
		)
	}

	private fun defaultTags(): Set<MangaTag> = setOf(
		MangaTag(title = "Fantasy", key = "fantasy", source = source),
		MangaTag(title = "Adventure", key = "adventure", source = source),
		MangaTag(title = "Romance", key = "romance", source = source),
		MangaTag(title = "Webcomic", key = "webcomic", source = source),
	)

	private companion object {
		const val ARCHIVE_PATH = "/archive/"
		const val COMIC_TITLE = "Twokinds"
		const val AUTHOR_NAME = "Tom Fischbach"
		const val DEFAULT_DESCRIPTION =
			"Twokinds is a fantasy webcomic by Tom Fischbach following amnesiac mage Trace Legacy and the Keidran Flora across a divided world."
	}
}
