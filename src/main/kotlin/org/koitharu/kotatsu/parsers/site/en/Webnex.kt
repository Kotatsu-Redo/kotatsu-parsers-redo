package org.koitharu.kotatsu.parsers.site.en

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl
import okhttp3.Response
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.asTypedList
import org.koitharu.kotatsu.parsers.util.json.toJSONObjectOrNull
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

@MangaSourceParser("WEBNEX", "Webnex", "en")
internal class Webnex(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.WEBNEX, pageSize = 36) {

	override val configKeyDomain = ConfigKey.Domain("webnex.cc")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.NEWEST,
		SortOrder.NEWEST_ASC,
		SortOrder.ALPHABETICAL,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = true,
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isYearRangeSupported = true,
			isOriginalLocaleSupported = true,
			isAuthorSearchSupported = true,
		)

	private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
		timeZone = TimeZone.getTimeZone("UTC")
	}

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = TAGS.mapToSet { (key, title) -> MangaTag(title = title, key = key, source = source) },
		availableStates = EnumSet.of(
			MangaState.ONGOING,
			MangaState.FINISHED,
			MangaState.PAUSED,
			MangaState.ABANDONED,
		),
		availableContentRating = EnumSet.of(ContentRating.SAFE, ContentRating.SUGGESTIVE, ContentRating.ADULT),
		availableContentTypes = EnumSet.of(ContentType.MANGA, ContentType.MANHWA, ContentType.MANHUA),
		availableDemographics = EnumSet.of(
			Demographic.SHOUNEN,
			Demographic.SHOUJO,
			Demographic.SEINEN,
			Demographic.JOSEI,
		),
		availableLocales = setOf(Locale.JAPANESE, Locale.KOREAN, Locale.CHINESE, Locale("zh", "HK")),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = urlBuilder().addPathSegment("browse").apply {
			addQueryParameter(
				"sort",
				when (order) {
					SortOrder.POPULARITY -> "coverage"
					SortOrder.NEWEST -> "year_newest"
					SortOrder.NEWEST_ASC -> "year_oldest"
					SortOrder.ALPHABETICAL -> "title"
					else -> "updated"
				},
			)
			addFilter(filter)
			if (page > 1) {
				addQueryParameter("page", page.toString())
			}
		}.build()
		val doc = webClient.httpGet(url).parseDocument()
		// A page past the end answers with the last page instead of an empty one
		if (page > 1 && doc.selectFirst(".pagination-page [aria-current=page]")?.text()?.toIntOrNull() != page) {
			return emptyList()
		}
		return doc.select("li.card-item a.card-link").map { a ->
			val href = a.attrAsRelativeUrl("href")
			Manga(
				id = generateUid(href),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				title = a.selectFirstOrThrow(".card-title").text(),
				altTitles = emptySet(),
				coverUrl = a.selectFirst("img.cover-img")?.src(),
				rating = RATING_UNKNOWN,
				contentRating = null,
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				source = source,
			)
		}
	}

	private fun HttpUrl.Builder.addFilter(filter: MangaListFilter) {
		filter.query?.takeUnless { it.isBlank() }?.let {
			// The site adds "(year · author)" to tell apart titles of the same name; its search doesn't know it
			addQueryParameter("q", it.replace(TITLE_DISAMBIGUATION, "").trim())
		}
		filter.author?.takeUnless { it.isBlank() }?.let { addQueryParameter("author", it.trim()) }
		for (tag in filter.tags) {
			addQueryParameter(tag.key.substringBefore(':'), tag.key.substringAfter(':'))
		}
		for (tag in filter.tagsExclude) {
			addQueryParameter("x" + tag.key.substringBefore(':'), tag.key.substringAfter(':'))
		}
		for (state in filter.states) {
			addQueryParameter(
				"status",
				when (state) {
					MangaState.ONGOING -> "ongoing"
					MangaState.FINISHED -> "completed"
					MangaState.PAUSED -> "hiatus"
					MangaState.ABANDONED -> "cancelled"
					else -> continue
				},
			)
		}
		for (type in filter.types) {
			addQueryParameter(
				"kind",
				when (type) {
					ContentType.MANGA -> "manga"
					ContentType.MANHWA -> "manhwa"
					ContentType.MANHUA -> "manhua"
					else -> continue
				},
			)
		}
		for (demographic in filter.demographics) {
			addQueryParameter(
				"demographic",
				when (demographic) {
					Demographic.SHOUNEN -> "shounen"
					Demographic.SHOUJO -> "shoujo"
					Demographic.SEINEN -> "seinen"
					Demographic.JOSEI -> "josei"
					else -> continue
				},
			)
		}
		// Every value means "up to this rating"; without one the site stops at suggestive
		when {
			ContentRating.ADULT in filter.contentRating -> "pornographic"
			ContentRating.SUGGESTIVE in filter.contentRating -> "suggestive"
			ContentRating.SAFE in filter.contentRating -> "safe"
			else -> null
		}?.let { addQueryParameter("rating", it) }
		(filter.originalLocale ?: filter.locale)?.let {
			addQueryParameter("language", it.toLanguageTag().lowercase(Locale.ROOT))
		}
		if (filter.yearFrom != YEAR_UNKNOWN) {
			addQueryParameter("from", filter.yearFrom.toString())
		}
		if (filter.yearTo != YEAR_UNKNOWN) {
			addQueryParameter("to", filter.yearTo.toString())
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val url = manga.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(url).parseDocument()
		val lastPage = doc.select(".pagination-page a[href$=#chapters]")
			.maxOfOrNull { it.text().toIntOrNull() ?: 1 } ?: 1
		val otherPages = coroutineScope {
			(2..lastPage).map { page ->
				async { webClient.httpGet("$url?page=$page").parseDocument() }
			}.awaitAll()
		}
		val title = doc.selectFirstOrThrow("h1.series-title").text()
		val credits = doc.select(".series-credits a[href*=author=], .series-credits a[href*=artist=]")
		// Status, type, year, original language, demographic, and the content rating when it isn't safe
		val facts = doc.select(".series-badges > li").eachText().map { it.lowercase(Locale.ROOT) }
		return manga.copy(
			title = title,
			altTitles = doc.select(".series-alt-name").eachText().filterTo(LinkedHashSet()) { it != title },
			coverUrl = doc.selectFirst(".series-top-cover img")?.src() ?: manga.coverUrl,
			description = doc.selectFirst(".series-desc")?.html().orEmpty(),
			authors = credits.eachText().toSet(),
			tags = parseTags(doc),
			state = when (doc.selectFirst(".series-badges li:has(.dot)")?.text()?.lowercase(Locale.ROOT)) {
				"ongoing" -> MangaState.ONGOING
				"completed" -> MangaState.FINISHED
				"hiatus" -> MangaState.PAUSED
				"cancelled", "dropped" -> MangaState.ABANDONED
				else -> null
			},
			contentRating = when {
				"pornographic" in facts || "erotica" in facts -> ContentRating.ADULT
				"suggestive" in facts -> ContentRating.SUGGESTIVE
				"safe" in facts -> ContentRating.SAFE
				else -> manga.contentRating
			},
			// Out of 10, and a dash while nobody has rated the series
			rating = doc.selectFirst(".cover-score strong")?.text()?.toFloatOrNull()?.div(10f) ?: RATING_UNKNOWN,
			// The site lists the newest chapter first
			chapters = (listOf(doc) + otherPages).flatMap(::parseChapters).distinctBy { it.url }.asReversed(),
		)
	}

	// The page shows a part of the tags as links; all of them are only in its Next.js payload
	private fun parseTags(doc: Document): Set<MangaTag> {
		val payload = doc.select("script").firstNotNullOfOrNull { TAGS_PAYLOAD.find(it.data()) }
		val shown = doc.select("a.chip[href*=genre=], a.chip[href*=tag=]").mapNotNull { a ->
			val param = if ("genre=" in a.attr("href")) "genre" else "tag"
			a.text().takeUnless { it.isEmpty() }?.let { "$param:$it" to it }
		}
		val all = payload?.let { match ->
			listOf("genre" to match.groupValues[1], "tag" to match.groupValues[2]).flatMap { (param, values) ->
				TAGS_PAYLOAD_ITEM.findAll(values).map { "$param:${it.groupValues[1]}" to it.groupValues[1] }
			}
		}.orEmpty()
		return (shown + all).distinctBy { (_, title) -> title }.mapToSet { (key, title) ->
			MangaTag(title = title, key = key, source = source)
		}
	}

	// A row is one chapter: the upload the site picked, plus the other groups' uploads in its dropdown.
	// Each group gets its own branch, with a list like the one the site shows when a group is picked.
	private fun parseChapters(doc: Document): List<MangaChapter> {
		// Hidden rows are decoys that the site's own page never shows
		return doc.select("li.ch-row:not([aria-hidden=true]):not([style*=display:none])").flatMap { row ->
			val link = row.selectFirstOrThrow("a.ch-link")
			val titleElement = link.selectFirst(".ch-title")
			// The number comes after "Ch. " for the eye and "Chapter " for a screen reader, both in the same element
			val numberText = link.selectFirst(".ch-num")?.ownText()?.trim().orEmpty()
			val name = titleElement?.text()?.nullIfEmpty()
			val title = when {
				numberText.isEmpty() || titleElement?.hasClass("is-plain") == true -> name
				name == null -> "Chapter $numberText"
				else -> "Chapter $numberText: $name"
			}
			val number = numberText.toFloatOrNull() ?: 0f
			val picked = newChapter(
				href = link.attrAsRelativeUrl("href"),
				title = title,
				number = number,
				group = row.selectFirst(".ch-source, .ch-source-name")?.text(),
				date = dateFormat.parseSafe(row.selectFirst("time.ch-time")?.attr("datetime")),
			)
			val others = row.select(".ch-sources-menu a.ch-sources-item").map { item ->
				newChapter(
					href = item.attrAsRelativeUrl("href"),
					title = title,
					number = number,
					group = item.selectFirst(".ch-sources-name")?.text(),
					date = parseTimeAgo(item),
				)
			}
			(listOf(picked) + others).distinctBy { it.branch }
		}
	}

	private fun newChapter(href: String, title: String?, number: Float, group: String?, date: Long): MangaChapter {
		// After a chapter of some group was read, the site adds "?group=" to every link of the series, for its reader
		// to go on with that group. The same upload must stay the same chapter whether it is there or not.
		val url = href.substringBefore('?')
		return MangaChapter(
			id = generateUid(url),
			title = title,
			number = number,
			volume = 0,
			url = url,
			scanlator = group,
			uploadDate = date,
			branch = group,
			source = source,
		)
	}

	// The dropdown only has a relative time ("4w ago")
	private fun parseTimeAgo(item: Element): Long {
		val match = TIME_AGO.matchEntire(item.selectFirst(".ch-sources-time")?.text().orEmpty()) ?: return 0L
		val days = when (match.groupValues[2]) {
			"y" -> 365f
			"mo" -> 30f
			"w" -> 7f
			"d" -> 1f
			"h" -> 1f / 24f
			else -> 1f / 1440f
		}
		return System.currentTimeMillis() - (match.groupValues[1].toInt() * days * TimeUnit.DAYS.toMillis(1)).toLong()
	}

	// The site gives a chapter's page list to its reader in a browser only, and answers anything else with
	// "Open this chapter on webnex.cc to read". So the chapter is opened in a browser, where the list is asked for.
	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val readerUrl = chapter.url.toAbsoluteUrl(domain)
		val raw = context.evaluateJs(readerUrl, READER_PAGES_SCRIPT, READER_TIMEOUT_MS)
			?: throw ParseException("The chapter did not open in the browser in time", readerUrl)
		// A string from a WebView arrives JSON-quoted
		val answer = JSONObject(JSONTokener(raw).nextValue() as? String ?: raw)
		val body = answer.optString("body").toJSONObjectOrNull()
		val pages = body?.optJSONArray("pages")?.asTypedList<String>().orEmpty()
		if (pages.isEmpty()) {
			val message = body?.optString("message")?.nullIfEmpty()
				?: answer.optString("error").nullIfEmpty()
				?: "The site gave no pages for this chapter (HTTP ${answer.optInt("status")})"
			throw ParseException(message, readerUrl)
		}
		return pages.mapIndexed { index, url ->
			MangaPage(
				// The image links change on every load
				id = generateUid("${chapter.url}/$index"),
				url = url.toAbsoluteUrl(domain),
				preview = null,
				source = source,
			)
		}
	}

	// Next.js streams late parts of the page as hidden chunks that its JS moves into <template> placeholders
	private fun Response.parseDocument(): Document = parseHtml().apply {
		select("div[hidden][id^=S:]").forEach { chunk ->
			val placeholder = getElementById("P:${chunk.id().removePrefix("S:")}") ?: return@forEach
			placeholder.parent()?.insertChildren(placeholder.siblingIndex(), chunk.childNodes())
			placeholder.remove()
			chunk.remove()
		}
	}

	private companion object {

		const val READER_TIMEOUT_MS = 30_000L

		// Runs repeatedly until it returns something. The reader showing a page tells that the site has let this
		// browser in, which is when its page list can be asked for; the wait is for a reader that looks different.
		const val READER_PAGES_SCRIPT = """
			(() => {
				if (!location.pathname.startsWith('/read/')) return null;
				const state = window.__kotatsuReader || (window.__kotatsuReader = { since: Date.now() });
				if (!state.asked && (document.querySelector('.reader-page img') || Date.now() - state.since > 10000)) {
					state.asked = true;
					const pagesUrl = document.documentElement.innerHTML.match(/pagesUrl\\?":\\?"([^"\\]+)/);
					fetch(pagesUrl ? pagesUrl[1] : location.pathname + '/pages')
						.then((response) => response.text()
							.then((body) => JSON.stringify({ status: response.status, body: body })))
						.catch((error) => JSON.stringify({ error: String(error) }))
						.then((answer) => { state.answer = answer; });
				}
				return state.answer || null;
			})()
		"""

		val TITLE_DISAMBIGUATION = Regex("""\s*\(\d{4}(\s*·[^)]*)?\)\s*$""")
		val TIME_AGO = Regex("""(\d+)(mo|y|w|d|h|m) ago""")
		val TAGS_PAYLOAD = Regex("""\\"genres\\":\[(.*?)],\\"tags\\":\[(.*?)]""")
		val TAGS_PAYLOAD_ITEM = Regex("""\\"(.*?)\\"""")

		val TAGS: List<Pair<String, String>> = listOf(
			"Action", "Adventure", "Boys' Love", "Comedy", "Crime", "Drama", "Ecchi", "Fantasy",
			"Gender Bender", "Girls' Love", "Gore", "Historical", "Horror", "Isekai", "Josei",
			"Magical Girls", "Mature", "Mecha", "Medical", "Mystery", "Philosophical",
			"Psychological", "Romance", "Sci-Fi", "Seinen", "Sexual Violence", "Shoujo",
			"Shoujo Ai", "Shounen", "Shounen Ai", "Slice of Life", "Sports", "Superhero",
			"Thriller", "Tragedy", "Wuxia", "Yaoi", "Yuri",
		).map { "genre:$it" to it } + listOf(
			"4-Koma", "Adaptation", "Anthology", "Award Winning", "Doujinshi", "Fan Colored",
			"Full Color", "Long Strip", "Official Colored", "Oneshot", "Self-Published",
			"User Created", "Web Comic",
			"Aliens", "Animals", "Cooking", "Crossdressing", "Delinquents", "Demons", "Genderswap",
			"Ghosts", "Gyaru", "Harem", "Incest", "Mafia", "Magic", "Martial Arts",
			"Military", "Monster Girls", "Monsters", "Music", "Ninja", "Office Workers", "Police",
			"Post-Apocalyptic", "Reincarnation", "Reverse Harem", "Samurai", "School Life",
			"Supernatural", "Survival", "Time Travel", "Traditional Games", "Vampires",
			"Video Games", "Villainess", "Virtual Reality", "Zombies",
		).map { "tag:$it" to it }
	}
}
