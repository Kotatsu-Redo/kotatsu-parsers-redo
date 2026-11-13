package org.koitharu.kotatsu.parsers.site.all

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.json.mapJSONNotNull
import java.util.*

/**
 * Bato.to successor on xbato.co.uk (xcat.tv / XBatCat GraphQL stack is dead).
 */
@MangaSourceParser("XBATCAT", "BatoTo")
internal class BatoToParser(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.XBATCAT, pageSize = 20) {

	override val configKeyDomain = ConfigKey.Domain("xbato.co.uk")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.NEWEST,
		SortOrder.POPULARITY,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			// Required for TAG to reach getList; the site still browses one genre at a time.
			isMultipleTagsSupported = true,
		)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
		.set("Referer", "https://$domain/")
		.build()

	override suspend fun getFilterOptions(): MangaListFilterOptions {
		val doc = webClient.httpGet("https://$domain/home", getRequestHeaders()).parseHtml()
		val tags = doc.select("a[href*=/genre/]").mapNotNullToSet { a ->
			val href = a.attr("href")
			val key = href.substringAfter("/genre/").substringBefore('/').substringBefore('?').nullIfEmpty()
				?: return@mapNotNullToSet null
			val title = a.text().trim().nullIfEmpty() ?: key.replace('-', ' ').toTitleCase(Locale.ENGLISH)
			MangaTag(title = title, key = key, source = source)
		}
		return MangaListFilterOptions(availableTags = tags)
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = when {
			!filter.query.isNullOrBlank() -> {
				"https://$domain/filter".toHttpUrl().newBuilder().apply {
					addQueryParameter("keyword", filter.query)
					filter.tags.firstOrNull()?.let { addQueryParameter("genres", it.key) }
					if (page > 1) addQueryParameter("page", page.toString())
				}.build().toString()
			}
			filter.tags.isNotEmpty() -> {
				val tag = filter.tags.first().key
				if (page > 1) "https://$domain/genre/$tag/page/$page" else "https://$domain/genre/$tag"
			}
			else -> {
				// /updated currently lists broken 404 slugs on the site; /newest is the live feed.
				val path = when (order) {
					SortOrder.NEWEST -> "added"
					SortOrder.POPULARITY -> "filter"
					else -> "newest"
				}
				when {
					path == "filter" -> {
						"https://$domain/filter".toHttpUrl().newBuilder().apply {
							if (page > 1) addQueryParameter("page", page.toString())
						}.build().toString()
					}
					page > 1 -> "https://$domain/$path/page/$page"
					else -> "https://$domain/$path"
				}
			}
		}
		return parseMangaList(webClient.httpGet(url, getRequestHeaders()).parseHtml())
	}

	private fun parseMangaList(doc: Document): List<Manga> {
		val result = LinkedHashMap<String, Manga>()
		doc.select("div.unit.item-, div.unit").forEach { unit ->
			val anchor = unit.selectFirst("a[href*=/manga/]") ?: return@forEach
			val href = anchor.attrAsRelativeUrlOrNull("href") ?: return@forEach
			val slug = href.substringAfter("/manga/").substringBefore('/').substringBefore('?').nullIfEmpty()
				?: return@forEach
			if (slug in result) return@forEach
			val title = unit.selectFirst("div.info > a[href*=/manga/]")?.text()?.trim()?.nullIfEmpty()
				?: anchor.selectFirst("img")?.attr("alt")?.trim()?.nullIfEmpty()
				?: slug.replace('-', ' ').toTitleCase(Locale.ENGLISH)
			val cover = unit.selectFirst("img")?.let { img ->
				img.attr("abs:data-src").nullIfEmpty()
					?: img.attr("abs:data-fallback").nullIfEmpty()
					?: img.attr("abs:src").nullIfEmpty()
			}?.takeUnless { it.startsWith("data:") }
			val url = "/manga/$slug"
			result[slug] = Manga(
				id = generateUid(url),
				title = title,
				altTitles = emptySet(),
				url = url,
				publicUrl = "https://$domain$url",
				rating = RATING_UNKNOWN,
				contentRating = null,
				coverUrl = cover,
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				source = source,
			)
		}
		return result.values.toList()
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain), getRequestHeaders()).parseHtml()
		val ld = doc.select("script[type=application/ld+json]").firstNotNullOfOrNull { el ->
			runCatching {
				val json = JSONObject(el.data())
				if (json.optString("@type") == "ComicSeries") json else null
			}.getOrNull()
		}

		val title = ld?.getStringOrNull("name")?.nullIfEmpty()
			?: doc.selectFirst("h1")?.text()?.trim()?.nullIfEmpty()
			?: manga.title
		val description = ld?.getStringOrNull("description")
			?: doc.selectFirst("meta[name=description]")?.attr("content")?.nullIfEmpty()
		val cover = ld?.getStringOrNull("image")
			?: ld?.getStringOrNull("thumbnailUrl")
			?: manga.coverUrl
		val authors = buildSet {
			ld?.optJSONArray("author")?.mapJSONNotNull { it.getStringOrNull("name") }?.let { addAll(it) }
		}
		val tags = buildSet {
			ld?.optJSONArray("genre")?.let { arr ->
				for (i in 0 until arr.length()) {
					val name = arr.optString(i).nullIfEmpty() ?: continue
					add(
						MangaTag(
							title = name,
							key = name.lowercase(Locale.ENGLISH).replace(' ', '-'),
							source = source,
						),
					)
				}
			}
			doc.select("a[href*=/genre/]").forEach { a ->
				val key = a.attr("href").substringAfter("/genre/").substringBefore('/').substringBefore('?')
					.nullIfEmpty() ?: return@forEach
				val name = a.text().trim().nullIfEmpty() ?: return@forEach
				add(MangaTag(title = name, key = key, source = source))
			}
		}
		val state = when {
			doc.text().contains("Ongoing", ignoreCase = true) -> MangaState.ONGOING
			doc.text().contains("Completed", ignoreCase = true) -> MangaState.FINISHED
			doc.text().contains("Hiatus", ignoreCase = true) -> MangaState.PAUSED
			doc.text().contains("Cancelled", ignoreCase = true) ||
				doc.text().contains("Canceled", ignoreCase = true) -> MangaState.ABANDONED
			else -> null
		}
		val altTitles = buildSet {
			ld?.optString("alternateName")?.nullIfEmpty()?.let { add(it) }
		}

		return manga.copy(
			title = title,
			altTitles = altTitles.ifEmpty { manga.altTitles },
			coverUrl = cover ?: manga.coverUrl,
			largeCoverUrl = cover,
			description = description,
			authors = authors.ifEmpty { manga.authors },
			tags = tags.ifEmpty { manga.tags },
			state = state ?: manga.state,
			chapters = parseChapters(doc, manga.url.substringAfter("/manga/").substringBefore('/')),
		)
	}

	private fun parseChapters(doc: Document, slug: String): List<MangaChapter> {
		val chapters = LinkedHashMap<String, MangaChapter>()

		fun addChapter(chapterSlug: String, numberHint: Float?) {
			val url = "/read/$slug/$chapterSlug"
			if (url in chapters) return
			val number = numberHint
				?: chapterSlug.removePrefix("chapter-").toFloatOrNull()
				?: 0f
			chapters[url] = MangaChapter(
				id = generateUid(url),
				title = chapterSlug.replace('-', ' ').toTitleCase(Locale.ENGLISH),
				number = number,
				volume = 0,
				url = url,
				scanlator = null,
				uploadDate = 0L,
				branch = null,
				source = source,
			)
		}

		val mapJson = CHAPTER_MAP_REGEX.find(doc.html())?.groupValues?.get(1)
		if (mapJson != null) {
			runCatching {
				val obj = JSONObject(mapJson)
				obj.keys().forEach { key ->
					val chapterSlug = obj.getString(key)
					addChapter(chapterSlug, key.toFloatOrNull())
				}
			}
		}

		doc.select("a[href*=/read/$slug/]").forEach { a ->
			val href = a.attrAsRelativeUrlOrNull("href") ?: return@forEach
			val chapterSlug = href.substringAfter("/read/$slug/").substringBefore('/').substringBefore('?')
				.nullIfEmpty() ?: return@forEach
			addChapter(chapterSlug, null)
		}

		WINDOW_URL_REGEX.findAll(doc.html()).forEach { match ->
			val href = match.groupValues[1]
			val chapterSlug = href.substringAfter("/read/$slug/").substringBefore('/').substringBefore('?')
				.nullIfEmpty() ?: return@forEach
			addChapter(chapterSlug, null)
		}

		return chapters.values.sortedBy { it.number }
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain), getRequestHeaders()).parseHtml()
		return doc.select("div.chapter-images-container img.image-thumb, div.pages img.image-thumb").mapNotNull { img ->
			val url = img.imageUrl() ?: return@mapNotNull null
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}

	private fun Element.imageUrl(): String? {
		val candidates = listOf(
			attr("abs:data-src"),
			attr("abs:data-fallback"),
			attr("abs:src"),
		)
		return candidates.firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
	}

	private companion object {
		val CHAPTER_MAP_REGEX = Regex("""window\.chapterSlugByChapterId\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL)
		val WINDOW_URL_REGEX = Regex("""window\.(?:first|last)ChapterUrl\s*=\s*"([^"]+)"""")
	}
}
