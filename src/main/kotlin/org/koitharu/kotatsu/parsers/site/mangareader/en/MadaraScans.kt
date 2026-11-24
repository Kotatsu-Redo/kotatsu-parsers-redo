package org.koitharu.kotatsu.parsers.site.mangareader.en

import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser
import org.koitharu.kotatsu.parsers.util.attrAsAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.attrAsRelativeUrl
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.mapChapters
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.parseSafe
import org.koitharu.kotatsu.parsers.util.src
import org.koitharu.kotatsu.parsers.util.textOrNull
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.toTitleCase
import java.text.SimpleDateFormat
import java.util.Locale

@MangaSourceParser("MADARASCANS", "Madara Scans", "en")
internal class MadaraScans(context: MangaLoaderContext) :
	MangaReaderParser(context, MangaParserSource.MADARASCANS, "madarascans.net", pageSize = 20, searchPageSize = 10) {

	override val listUrl = "/series"
	override val datePattern = "yyyy/MM/dd"
	override val selectTestScript = "script:containsData(ts_reader.run)"

	override val filterCapabilities: MangaListFilterCapabilities
		get() = super.filterCapabilities.copy(
			isTagsExclusionSupported = false,
		)

	override fun parseMangaList(docs: Document): List<Manga> {
		return docs.select("div.listupd > div, div.legend-inner").mapNotNull { element ->
			val link = element.selectFirst("h3.card-v-title > a, h3.legend-title > a") ?: return@mapNotNull null
			val relativeUrl = link.attrAsRelativeUrl("href")
			Manga(
				id = generateUid(relativeUrl),
				url = relativeUrl,
				title = link.text().trim(),
				altTitles = emptySet(),
				publicUrl = link.attrAsAbsoluteUrl("href"),
				rating = element.selectFirst(".numscore, .score")?.text()?.toFloatOrNull()?.div(10f) ?: RATING_UNKNOWN,
				contentRating = sourceContentRating,
				coverUrl = element.selectFirst("img")?.src(),
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				source = source,
			)
		}.distinctBy { it.url }
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val docs = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val dateFormat = SimpleDateFormat(datePattern, Locale.US)
		val chapters = docs.select(".ch-item.free").toList().mapChapters(reversed = true) { _, element: Element ->
			parseChapter(element, dateFormat)
		}

		val tags = docs.select(".lh-genres > .lh-genre-tag").mapTo(LinkedHashSet<MangaTag>()) { tag ->
			MangaTag(
				key = tag.attr("href").substringAfterLast('/').trim('/').ifBlank { tag.text().trim().lowercase(sourceLocale) },
				title = tag.text().trim().toTitleCase(sourceLocale),
				source = source,
			)
		}

		return manga.copy(
			title = docs.selectFirst("h1.lh-title")?.text()?.trim().orEmpty().ifEmpty { manga.title },
			description = docs.selectFirst("div.lh-story > #manga-story")?.text()?.trim()?.nullIfEmpty(),
			coverUrl = docs.selectFirst(".lh-poster > img")?.src() ?: manga.coverUrl,
			tags = tags,
			state = parseState(docs.selectFirst("span.status-badge-lux")?.text()),
			contentRating = ContentRating.SAFE,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val docs = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
		val scriptJson = docs.select("script").firstNotNullOfOrNull { script ->
			READER_PAYLOAD_REGEX.find(script.data())?.groupValues?.getOrNull(1)
		}

		if (scriptJson == null) {
			return super.getPages(chapter)
		}

		val images = JSONObject(scriptJson)
			.getJSONArray("sources")
			.getJSONObject(0)
			.getJSONArray("images")

		return List(images.length()) { index ->
			val url = images.getString(index)
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}

	private fun parseChapter(element: Element, dateFormat: SimpleDateFormat): MangaChapter? {
		val link = element.selectFirst("a") ?: return null
		val relativeUrl = link.attrAsRelativeUrl("href")
		val title = element.selectFirst(".ch-num")?.textOrNull()?.trim()
			?: link.text().trim().nullIfEmpty()
		val number = CHAPTER_NUMBER_REGEX.find(title.orEmpty())?.groupValues?.getOrNull(1)?.toFloatOrNull() ?: 0f
		val date = element.selectFirst(".ch-date")?.text()?.trim()
		return MangaChapter(
			id = generateUid(relativeUrl),
			title = title,
			number = number,
			volume = 0,
			url = relativeUrl,
			scanlator = null,
			uploadDate = dateFormat.parseSafe(date),
			branch = null,
			source = source,
		)
	}

	private fun parseState(value: String?): MangaState? {
		return when (value?.trim()?.lowercase(sourceLocale)) {
			"ongoing" -> MangaState.ONGOING
			"completed", "complete" -> MangaState.FINISHED
			"hiatus" -> MangaState.PAUSED
			"dropped", "cancelled", "canceled" -> MangaState.ABANDONED
			else -> null
		}
	}

	private companion object {
		private val CHAPTER_NUMBER_REGEX = Regex("""(\d+(?:\.\d+)?)""")
		private val READER_PAYLOAD_REGEX = Regex("""ts_reader\.run\((\{.*?\})\)""", RegexOption.DOT_MATCHES_ALL)
	}
}
