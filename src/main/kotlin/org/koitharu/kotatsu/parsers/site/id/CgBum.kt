package org.koitharu.kotatsu.parsers.site.id

import okhttp3.Headers
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.toJSONObjectOrNull
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy
import java.util.*

@MangaSourceParser("CGBUM", "CGBUM", "id", ContentType.HENTAI)
internal class CgBum(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.CGBUM, pageSize = 24) {

	override val configKeyDomain = ConfigKey.Domain("cgbum.com")

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	// The image CDN (cdnN.cgbum.com) answers 403 unless the request carries a Referer from the site
	// and the `cgbum_reader_ts` cookie, which the reader page sets for .cgbum.com (see getPages).
	override fun getRequestHeaders(): Headers = Headers.Builder()
		.add("User-Agent", config[userAgentKey])
		.add("Referer", "https://$domain/")
		.build()

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.NEWEST,
		SortOrder.NEWEST_ASC,
	)

	override val filterCapabilities = MangaListFilterCapabilities(
		isMultipleTagsSupported = true,
		isSearchSupported = true,
		isSearchWithFiltersSupported = false,
	)

	private val availableTags = suspendLazy(initializer = ::fetchTags)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = availableTags.get(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
		availableContentTypes = EnumSet.of(
			ContentType.MANGA,
			ContentType.MANHWA,
			ContentType.MANHUA,
			ContentType.HENTAI,
		),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val query = filter.query
		val url = buildString {
			append("https://").append(domain)
			if (!query.isNullOrEmpty()) {
				// The search page takes no filters or sort order
				append("/cari?q=").append(query.urlEncoded())
				append("&page=").append(page)
				return@buildString
			}
			append("/daftar-komik?page=").append(page)
			append("&sort=").append(
				when (order) {
					SortOrder.POPULARITY -> "views"
					SortOrder.NEWEST -> "newest"
					SortOrder.NEWEST_ASC -> "oldest"
					else -> "latest"
				},
			)
			filter.types.firstOrNull()?.let { type ->
				append("&type=").append(
					when (type) {
						ContentType.MANHWA -> "manhwa"
						ContentType.MANHUA -> "manhua"
						ContentType.HENTAI -> "pornhwa"
						else -> "manga"
					},
				)
			}
			filter.states.firstOrNull()?.let { state ->
				append("&status=").append(if (state == MangaState.FINISHED) "tamat" else "ongoing")
			}
			// The site matches every selected genre (AND) and only uses the first three
			filter.tags.take(3).forEach { tag ->
				append("&genres%5B%5D=").append(tag.key.urlEncoded())
			}
		}
		val doc = webClient.httpGet(url).parseHtml()
		return doc.select(".comic-grid article.comic-card").mapNotNull { parseCard(it) }
	}

	private fun parseCard(card: Element): Manga? {
		val a = card.selectFirst(".comic-card-title a") ?: card.selectFirst("a.comic-card-cover") ?: return null
		val href = a.attrAsRelativeUrlOrNull("href") ?: return null
		return Manga(
			id = generateUid(href),
			url = href,
			publicUrl = href.toAbsoluteUrl(domain),
			title = a.text().trim().ifEmpty { card.selectFirst("img")?.attr("alt").orEmpty() },
			altTitles = emptySet(),
			coverUrl = card.selectFirst("img")?.src(),
			rating = RATING_UNKNOWN,
			tags = emptySet(),
			state = parseState(card.selectFirst(".badge-status")),
			authors = emptySet(),
			contentRating = if (card.isAdult()) ContentRating.ADULT else null,
			source = source,
		)
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val info = doc.selectFirstOrThrow("article.comic-detail")
		val ld = doc.select("script[type=\"application/ld+json\"]").firstNotNullOfOrNull { script ->
			script.data().toJSONObjectOrNull()?.takeIf { it.optString("@type") == "ComicSeries" }
		}
		val cover = info.selectFirst(".comic-cover")
		val title = ld?.optString("name")?.takeIf { it.isNotBlank() }
			?: info.selectFirst("h1")?.text()?.removeSuffix(" Bahasa Indonesia")?.trim()?.takeIf { it.isNotEmpty() }
			?: manga.title
		val author = info.metaRow("Author")
		val tags = info.select(".comic-genres a.genre-pill").mapNotNullToSet { a ->
			val name = a.text().trim()
			if (name.isEmpty()) null else MangaTag(title = name.toTitleCase(sourceLocale), key = name, source = source)
		}
		val description = info.selectFirst(".synopsis-content")?.html()
			?.replace(Regex("""\s*~\s*CGBUM\s*$"""), "")
		return manga.copy(
			title = title,
			altTitles = setOfNotNull(info.selectFirst(".comic-alt-title")?.text()?.trim()?.takeIf { it.isNotEmpty() }),
			coverUrl = cover?.selectFirst("img")?.src() ?: manga.coverUrl,
			authors = author?.split(',')?.mapNotNullToSet { it.trim().takeIf { a -> a.isNotEmpty() && a != "-" } }
				.orEmpty(),
			tags = tags,
			state = parseState(cover?.selectFirst(".badge-status")) ?: manga.state,
			description = description,
			contentRating = if (cover?.isAdult() == true) ContentRating.ADULT else manga.contentRating,
			chapters = getChapters(doc),
		)
	}

	private suspend fun getChapters(doc: Document): List<MangaChapter> {
		val list = doc.selectFirst(".chapter-list") ?: return emptyList()
		// Site order: newest first
		val chapters = ArrayList<MangaChapter>()
		list.select(".chapter-grid a.ch-grid-item").mapNotNullTo(chapters) { a ->
			val href = a.attrAsRelativeUrlOrNull("href") ?: return@mapNotNullTo null
			createChapter(
				url = href,
				chapter = a.attr("data-chapter"),
				sort = a.attr("data-chapter-sort").toDoubleOrNull(),
				label = a.text(),
			)
		}
		// The detail page usually lists every chapter; the rest comes in batches from chapter-batch.php
		val total = list.attr("data-ch-total").toIntOrNull() ?: 0
		val slug = list.attr("data-ch-slug")
		val endpoint = list.attrAsAbsoluteUrlOrNull("data-ch-endpoint")
		var offset = list.attr("data-ch-offset").toIntOrNull() ?: chapters.size
		while (offset < total && slug.isNotEmpty() && endpoint != null) {
			val json = webClient.httpGet("$endpoint?slug=${slug.urlEncoded()}&offset=$offset&limit=100").parseJson()
			val items = json.optJSONArray("items") ?: break
			for (i in 0 until items.length()) {
				val item = items.optJSONObject(i) ?: continue
				chapters += parseBatchChapter(item, slug) ?: continue
			}
			val next = json.optInt("next_offset", offset + items.length())
			if (items.length() == 0 || next <= offset || !json.optBoolean("has_more", false)) break
			offset = next
		}
		return chapters.distinctBy { it.url }.reversed()
	}

	private fun parseBatchChapter(item: JSONObject, slug: String): MangaChapter? {
		val chapter = item.optString("chapter").trim()
		if (chapter.isEmpty()) return null
		val url = item.optString("url").takeIf { it.isNotBlank() }?.toRelativeUrl(domain)
			?: "/baca/$slug/chapter/$chapter"
		val title = item.optString("title").trim()
		return createChapter(
			url = url,
			chapter = chapter,
			sort = if (item.has("chapter_sort")) item.optDouble("chapter_sort") else null,
			label = item.optString("label").ifBlank {
				if (title.isEmpty()) "Ch. $chapter" else "Ch. $chapter - $title"
			},
		)
	}

	private fun createChapter(url: String, chapter: String, sort: Double?, label: String): MangaChapter {
		// "1053-5" is chapter 1053.5 (the site sorts it as 1053.005)
		val number = Regex("""^(\d+)[-.](\d+)$""").find(chapter)?.let { m ->
			"${m.groupValues[1]}.${m.groupValues[2]}".toFloatOrNull()
		} ?: sort?.takeIf { !it.isNaN() }?.toFloat()
		?: Regex("""\d+(\.\d+)?""").find(chapter)?.value?.toFloatOrNull()
		?: 0f
		return MangaChapter(
			id = generateUid(url),
			title = label.trim().ifEmpty { "Ch. $chapter" },
			number = number,
			volume = 0,
			url = url,
			scanlator = null,
			uploadDate = 0L,
			branch = null,
			source = source,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		// Loading the reader page also sets the cgbum_reader_ts cookie the image CDN requires
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
		val proxyBase = doc.select("script").firstNotNullOfOrNull { script ->
			Regex("""imageProxyBase\s*:\s*"([^"]*)"""").find(script.data())?.groupValues?.get(1)
		}?.takeIf { it.isNotBlank() }?.trimEnd('/') ?: "https://$domain"
		return doc.select("#readerImages .page-container").mapNotNull { container ->
			val url = container.attrAsAbsoluteUrlOrNull("data-url")
				?: container.attrOrNull("data-path")?.let { "$proxyBase/img.php?data=${it.urlEncoded()}" }
				?: container.selectFirst("img")?.src()
				?: return@mapNotNull null
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}

	private suspend fun fetchTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/daftar-komik").parseHtml()
		// Genres are matched case-insensitively and some exist twice ("Action" and "action")
		return doc.select("#genreList input[name='genres[]']")
			.mapNotNull { it.attrOrNull("value") }
			.distinctBy { it.lowercase(sourceLocale) }
			.mapTo(LinkedHashSet()) { name ->
				MangaTag(title = name.toTitleCase(sourceLocale), key = name, source = source)
			}
	}

	private fun Element.isAdult(): Boolean =
		attr("data-adult") == "1" || selectFirst(".badge-pornhwa") != null

	private fun Element.metaRow(label: String): String? = select(".comic-meta-simple .meta-row")
		.firstOrNull { it.selectFirst(".meta-label")?.text()?.equals(label, ignoreCase = true) == true }
		?.selectFirst(".meta-value")?.text()?.trim()?.takeIf { it.isNotEmpty() && it != "-" }

	private fun parseState(badge: Element?): MangaState? {
		badge ?: return null
		val text = badge.className() + " " + badge.text()
		return when {
			text.contains("completed", ignoreCase = true) || text.contains("tamat", ignoreCase = true) -> MangaState.FINISHED
			text.contains("ongoing", ignoreCase = true) -> MangaState.ONGOING
			text.contains("hiatus", ignoreCase = true) -> MangaState.PAUSED
			else -> null
		}
	}
}
