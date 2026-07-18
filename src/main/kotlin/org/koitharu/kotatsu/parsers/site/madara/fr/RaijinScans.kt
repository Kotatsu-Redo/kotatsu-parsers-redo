package org.koitharu.kotatsu.parsers.site.madara.fr

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
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
import org.koitharu.kotatsu.parsers.model.YEAR_UNKNOWN
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.util.attrAsRelativeUrl
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.mapChapters
import org.koitharu.kotatsu.parsers.util.mapNotNullToSet
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.selectFirstOrThrow
import org.koitharu.kotatsu.parsers.util.selectLast
import org.koitharu.kotatsu.parsers.util.src
import org.koitharu.kotatsu.parsers.util.textOrNull
import org.koitharu.kotatsu.parsers.util.getCookies
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.toTitleCase
import org.koitharu.kotatsu.parsers.util.urlEncoded
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.EnumSet
import java.util.Locale
import java.util.Base64

@MangaSourceParser("RAIJINSCANS", "RaijinScans", "fr")
internal class RaijinScans(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.RAIJINSCANS, "raijin-scans.fr", 21) {

	private val descriptionScriptRegex = """content\.innerHTML = `([\s\S]+?)`;""".toRegex()
	override val datePattern = "dd/MM/yyyy"
	override val withoutAjax = true
	override val listUrl = ""
	override val tagPrefix = "genre/"
	override val selectBodyPage = "div.protected-image-data"
	override val selectChapter = "ul.scroll-sm li.item"
	override val selectDate = "span:nth-of-type(2)"
	override val selectPage = "div.protected-image-data"
	override val selectGenre = "div.genre-list div.genre-link"
	override val selectDesc = "div.description-content"
	override val selectState = "div.stat-item:has(span:contains(État du titre)) span.manga"

	override fun getRequestHeaders() = super.getRequestHeaders().newBuilder()
		.apply {
			val cookies = context.cookieJar.getCookies(domain)
			val cookieString = cookies.filter { cookie ->
				cookie.name == "cf_clearance" ||
				cookie.name.startsWith("__cf") ||
				cookie.name.startsWith("cf_")
			}.joinToString("; ") { c -> "${c.name}=${c.value}" }

			if (cookieString.isNotEmpty()) {
				add("Cookie", cookieString)
			}
		}
		.build()

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.ALPHABETICAL,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isMultipleTagsSupported = true,
			isYearSupported = true,
			isSearchWithFiltersSupported = true,
		)

	private lateinit var tagMap: Map<String, String>

	override suspend fun getFilterOptions(): MangaListFilterOptions {
		val availableTags = fetchAvailableTags()
		tagMap = availableTags.associateBy({ it.title }, { it.key })

		return MangaListFilterOptions(
			availableTags = availableTags,
			availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
			availableContentTypes = EnumSet.of(
				ContentType.MANGA,
				ContentType.MANHWA,
				ContentType.MANHUA,
			),
		)
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://$domain")
			if (page > 0) append("/page/${page + 1}")

			append("?post_type=wp-manga&s=")
			filter.query?.let { append(it.urlEncoded()) }

			if (filter.year != YEAR_UNKNOWN) append("&release[]=${filter.year}")
			if (!filter.tags.isEmpty()) {
				append("&genre_mode=and")
				filter.tags.forEach { append("&genre[]=${it.key}") }
			}

			filter.states.forEach {
				val status = when (it) {
					MangaState.ONGOING -> "on-going"
					MangaState.FINISHED -> "end"
					else -> ""
				}
				if (status.isNotEmpty()) append("&status[]=$status")
			}

			val sortOrder = when (order) {
				SortOrder.POPULARITY -> "most_viewed"
				SortOrder.UPDATED -> "recently_added"
				SortOrder.ALPHABETICAL -> "title_az"
				else -> "recently_added"
			}
			if (sortOrder.isNotEmpty()) append("&sort=$sortOrder")
		}

		val doc = webClient.httpGet(url).parseHtml()
		return parseMangaList(doc)
	}


	override fun parseMangaList(doc: Document): List<Manga> {
		val elements = doc.select("div.original.card-lg div.unit")
		return elements.map { element ->
			val linkElement =
				element.selectFirst("a.c-title") ?: element.selectFirst("div.info > a") ?: element.selectFirst("a")
				?: error("link not found")

			val href = linkElement.attrAsRelativeUrl("href")
			val title = linkElement.text()
			val cover = element.selectLast("div.poster-image-wrapper > img")?.src()

			Manga(
				id = generateUid(href),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				coverUrl = cover,
				title = title,
				altTitles = emptySet(),
				rating = RATING_UNKNOWN,
				tags = emptySet(),
				authors = emptySet(),
				state = null,
				source = source,
				contentRating = ContentRating.SAFE,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()

		val title = doc.selectFirst("h1.serie-title")?.text() ?: manga.title
		val cover = doc.selectFirst("img.cover")?.src() ?: manga.coverUrl

		val author = doc.selectFirst("div.stat-item:has(span:contains(Auteur)) span.stat-value")?.text()

		val scriptDescription = doc.select("script:containsData(content.innerHTML)")
			.firstNotNullOfOrNull { descriptionScriptRegex.find(it.data())?.groupValues?.get(1)?.trim() }
		val description = scriptDescription ?: doc.selectFirst(selectDesc)?.text()

		// Ensure tagMap is initialized
		if (!::tagMap.isInitialized) {
			val availableTags = fetchAvailableTags()
			tagMap = availableTags.associateBy({ it.title }, { it.key })
		}

		val genres = doc.select(selectGenre).mapNotNullToSet { a ->
			val genreTitle = a.text().trim().toTitleCase()

			if (genreTitle.isBlank()) {
				null // Skip empty genres
			} else {
				val genreId = tagMap[genreTitle]

				if (genreId != null) {
					MangaTag(
						key = genreId,
						title = genreTitle,
						source = source,
					)
				} else {
					// Genre not found in filter options, skip it gracefully
					null
				}
			}
		}

		val state = doc.selectFirst(selectState)?.text()?.lowercase()?.let { stateText ->
			when {
				"en cours" in stateText -> MangaState.ONGOING
				"terminé" in stateText -> MangaState.FINISHED
				else -> null
			}
		}

		val rating = doc.select(".vote-count").textOrNull()?.toFloat()?.div(10f) ?: RATING_UNKNOWN

		return manga.copy(
			title = title,
			coverUrl = cover,
			authors = setOfNotNull(author),
			description = description,
			tags = genres,
			state = state,
			chapters = getChapters(manga, doc),
			rating = rating,
		)
	}

	override suspend fun getChapters(manga: Manga, doc: Document): List<MangaChapter> {
		return doc.select(selectChapter).mapChapters(reversed = true) { i, element ->
			val link = element.selectFirstOrThrow("a")
			val href = link.attrAsRelativeUrl("href")
			val name = link.attr("title").trim()
			val dateText = link.selectFirst(selectDate)?.text()

			MangaChapter(
				id = generateUid(href),
				title = name,
				number = i + 1f,
				volume = 0,
				url = href,
				uploadDate = parseRelativeDate(dateText ?: ""),
				source = source,
				scanlator = null,
				branch = null,
			)
		}
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val chapterUrl = chapter.url.toAbsoluteUrl(domain)

		// First try the standard HTTP approach (faster, works when Cloudflare cookies are present)
		val doc = webClient.httpGet(chapterUrl).parseHtml()

		// Check if we're blocked by Cloudflare challenge
		if (isCloudflareChallenge(doc)) {
			// Use evaluateJs with MutationObserver + scroll to collect all images
			try {
				val jsPages = loadChapterPagesViaJs(chapterUrl)
				if (jsPages.isNotEmpty()) {
					return jsPages
				}
			} catch (e: Exception) {
				// evaluateJs failed, try browser action below
			}
			try {
				context.requestBrowserAction(this, chapterUrl)
			} catch (e: UnsupportedOperationException) {
				// Browser action not available, continue with other fallbacks
			}
		}

		// Try the AJAX config approach with pagination (returns ALL images)
		decodeRjAjaxConfig(doc)?.let { config ->
			val headers = getRequestHeaders().newBuilder()
				.set("Referer", chapterUrl)
				.set("X-Requested-With", "XMLHttpRequest")
				.build()

			val dataKey = config.responseKeys.getString(1)       // "rj060d55fa4391"
			val imagesKey = config.responseKeys.getString(2)      // "rj8ea926f36da4"
			val imageKey = config.responseKeys.getString(4)       // "rj85e00919da50"
			val totalKey = config.responseKeys.getString(6)       // "rjca9c22650203"
			val nextOffsetKey = config.responseKeys.getString(7)  // "rja9c30b918277"
			val nextTokenKey = config.responseKeys.getString(8)   // "rjb3e1f5b395ac"
			val hasMoreKey = config.responseKeys.getString(9)     // "rjfd0394ec019b"

			val allPages = mutableListOf<MangaPage>()
			val currentForm = LinkedHashMap(config.form)

			while (true) {
				val response = webClient.httpPost(config.ajaxUrl.toHttpUrl(), currentForm, headers).parseJson()
				val data = response.optJSONObject(dataKey) ?: break
				val pages = data.optJSONArray(imagesKey) ?: break

				allPages.addAll(pages.mapJsonPages(imageKey))

				val hasMore = data.optBoolean(hasMoreKey, false)
				if (!hasMore) break

				val nextOffset = data.optString(nextOffsetKey, "")
				val nextToken = data.optString(nextTokenKey, "")
				if (nextOffset.isEmpty()) break

				currentForm[config.fieldOffsetKey] = nextOffset
				currentForm[config.fieldTokenKey] = nextToken
			}

			if (allPages.isNotEmpty()) return allPages
		}

		// Try Base64 encoded data-src
		val base64Pages = doc.select(selectPage).mapNotNull { element ->
			val encodedUrl = element.attr("data-src").takeIf { it.isNotBlank() } ?: return@mapNotNull null
			val imageUrl = String(context.decodeBase64(encodedUrl))
			MangaPage(
				id = generateUid(imageUrl),
				url = imageUrl,
				preview = null,
				source = source,
			)
		}
		if (base64Pages.isNotEmpty()) {
			return base64Pages
		}

		// Last resort: partial images from the HTML (may be incomplete due to lazy loading)
		val loadedImages = doc.select("figure.page img[src], div.reading-content img[src], div.protected-image-data img[src]")
		if (loadedImages.isNotEmpty()) {
			return loadedImages.mapNotNull { img ->
				val imageUrl = img.attr("src").takeIf { it.isNotBlank() } ?: return@mapNotNull null
				MangaPage(
					id = generateUid(imageUrl),
					url = imageUrl,
					preview = null,
					source = source,
				)
			}
		}

		return emptyList()
	}

	private suspend fun loadChapterPagesViaJs(chapterUrl: String): List<MangaPage> {
		val loadScript = """
			(() => {
				const isChallenge = () => {
					const title = document.title.toLowerCase();
					return title.includes('just a moment') ||
						title.includes('un instant') ||
						title.includes('vérification') ||
						document.querySelector('script[src*="challenge-platform"]') !== null ||
						document.querySelector('form[action*="__cf_chl"]') !== null;
				};

				if (isChallenge()) {
					return "CLOUDFLARE_CHALLENGE";
				}

				// Initialize state on first poll
				if (!window.__rjCollector) {
					window.__rjCollector = {
						urls: [],
						seen: new Set(),
						stableCount: 0,
						lastCount: 0,
						startTime: Date.now()
					};

					// Collect existing images
					document.querySelectorAll('img[src]').forEach(img => {
						const src = img.getAttribute('src');
						if (src && src.startsWith('http') && !window.__rjCollector.seen.has(src)) {
							window.__rjCollector.seen.add(src);
							window.__rjCollector.urls.push(src);
						}
					});

					// MutationObserver to catch images added by AJAX
					const observer = new MutationObserver((mutations) => {
						for (const m of mutations) {
							for (const node of m.addedNodes) {
								if (node.nodeType !== 1) continue;
								if (node.tagName === 'IMG') {
									const src = node.getAttribute('src');
									if (src && src.startsWith('http') && !window.__rjCollector.seen.has(src)) {
										window.__rjCollector.seen.add(src);
										window.__rjCollector.urls.push(src);
									}
								}
								if (node.querySelectorAll) {
									node.querySelectorAll('img[src]').forEach(img => {
										const src = img.getAttribute('src');
										if (src && src.startsWith('http') && !window.__rjCollector.seen.has(src)) {
											window.__rjCollector.seen.add(src);
											window.__rjCollector.urls.push(src);
										}
									});
								}
							}
						}
					});
					observer.observe(document.body, { childList: true, subtree: true });
				}

				const state = window.__rjCollector;

				// Scroll down to trigger the site's AJAX image loading
				window.scrollBy(0, window.innerHeight * 2);

				// Check progress
				if (state.urls.length > state.lastCount) {
					state.lastCount = state.urls.length;
					state.stableCount = 0;
				} else {
					state.stableCount++;
				}

				const elapsed = Date.now() - state.startTime;

				// Done if: images stable for 8 polls AND at least 1 image, OR timeout
				if ((state.stableCount >= 8 && state.urls.length > 0) || elapsed > 80000) {
					const result = JSON.stringify(state.urls);
					delete window.__rjCollector;
					return result;
				}

				// Keep polling
				return null;
			})()
		""".trimIndent()

		val rawJson = try {
			context.evaluateJs(chapterUrl, loadScript, timeout = 90000L)
		} catch (e: Exception) {
			return emptyList()
		} ?: return emptyList()

		if (rawJson == "CLOUDFLARE_CHALLENGE") {
			return emptyList()
		}

		return try {
			val urls = JSONArray(rawJson)
			(0 until urls.length()).mapNotNull { i ->
				val imageUrl = urls.optString(i).takeIf { it.isNotBlank() } ?: return@mapNotNull null
				MangaPage(
					id = generateUid(imageUrl),
					url = imageUrl,
					preview = null,
					source = source,
				)
			}
		} catch (e: Exception) {
			emptyList()
		}
	}

	private fun isCloudflareChallenge(doc: Document): Boolean {
		val title = doc.title().lowercase()
		if (title.contains("just a moment") || title.contains("un instant") || title.contains("vérification")) {
			return true
		}
		if (doc.selectFirst("script[src*=challenge-platform]") != null) return true
		if (doc.selectFirst("form[action*=__cf_chl]") != null) return true
		if (doc.getElementById("challenge-error-title") != null) return true
		if (doc.getElementById("challenge-error-text") != null) return true
		return false
	}

	private fun JSONArray.mapJsonPages(imageKey: String): List<MangaPage> {
		return (0 until length()).mapNotNull { i ->
			val imageUrl = optJSONObject(i)?.optString(imageKey)?.takeIf { it.isNotBlank() }
				?: return@mapNotNull null
			MangaPage(
				id = generateUid(imageUrl),
				url = imageUrl,
				preview = null,
				source = source,
			)
		}
	}

	private fun decodeRjAjaxConfig(doc: Document): RjAjaxConfig? {
		val payloadJson = doc.select("script")
			.firstNotNullOfOrNull { script -> RJ_PAYLOAD_REGEX.find(script.data())?.value }
			?: return null
		val payload = JSONObject(payloadJson)
		val fragments = payload.getJSONObject("c")
		val base64 = buildString {
			payload.getString("m").split('|').forEach { token -> append(fragments.optString(token)) }
		}
		if (base64.isBlank()) return null

		val inner = JSONObject(decodeBase64Chunk(base64))
		val firstOrder = inner.getJSONArray("m")
		val scrambled = inner.getJSONArray("d")
		val secondOrder = inner.getJSONArray("l")
		val intermediate = arrayOfNulls<Any>(firstOrder.length())
		for (i in 0 until firstOrder.length()) {
			intermediate[firstOrder.getInt(i)] = scrambled.get(i)
		}
		val values = arrayOfNulls<Any>(secondOrder.length())
		for (j in 0 until secondOrder.length()) {
			values[j] = intermediate[secondOrder.getInt(j)]
		}

		val fieldNames = values[13] as? JSONArray ?: return null
		val responseKeys = values[14] as? JSONArray ?: return null
		val ajaxUrl = values[0]?.jsonStringOrNull() ?: return null
		val action = values[12]?.jsonStringOrNull() ?: return null
		val empty = values[1]?.jsonStringOrNull().orEmpty()
		val form = LinkedHashMap<String, String>(fieldNames.length() + 1)
		form["action"] = action
		form[fieldNames.getString(0)] = empty
		form[fieldNames.getString(1)] = values[2].jsonStringOrEmpty()
		form[fieldNames.getString(2)] = values[3].jsonStringOrEmpty()
		form[fieldNames.getString(3)] = values[4].jsonStringOrEmpty()
		form[fieldNames.getString(4)] = values[5].jsonStringOrEmpty()
		form[fieldNames.getString(5)] = values[6].jsonStringOrEmpty()
		form[fieldNames.getString(6)] = values[8].jsonStringOrEmpty()
		form[fieldNames.getString(7)] = values[9].jsonStringOrEmpty()
		form[fieldNames.getString(8)] = values[7].jsonStringOrEmpty()
		form[fieldNames.getString(9)] = empty
		return RjAjaxConfig(
			ajaxUrl = ajaxUrl,
			form = form,
			responseKeys = responseKeys,
			fieldOffsetKey = fieldNames.getString(6),  // offset field name
			fieldTokenKey = fieldNames.getString(9),   // token field name
		)
	}

	private fun decodeBase64Chunk(value: String): String {
		val padded = value + "=".repeat((4 - value.length % 4) % 4)
		return String(Base64.getDecoder().decode(padded))
	}

	private fun Any?.jsonStringOrEmpty(): String = jsonStringOrNull().orEmpty()

	private fun Any?.jsonStringOrNull(): String? = when (this) {
		null, JSONObject.NULL -> null
		else -> toString()
	}

	override suspend fun fetchAvailableTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/?post_type=wp-manga&s=").parseHtml()

		return doc.select("ul.dropdown-menu.c1 li input[type=checkbox][name='genre[]']").mapNotNullToSet { input ->
			val value = input.attr("value")
			val label = input.nextElementSibling()?.text()?.trim()

			if (value.isNotEmpty() && !label.isNullOrEmpty()) {
				MangaTag(
					key = value,
					title = label.toTitleCase(),
					source = source,
				)
			} else {
				null
			}
		}
	}

	private fun parseRelativeDate(date: String): Long {
		val lcDate = date.lowercase(Locale.FRENCH).trim()
		val cal = Calendar.getInstance()
		val number = """(\d+)""".toRegex().find(lcDate)?.value?.toIntOrNull()

		return when {
			"aujourd'hui" in lcDate -> cal.timeInMillis
			"hier" in lcDate -> cal.apply { add(Calendar.DAY_OF_MONTH, -1) }.timeInMillis
			number != null -> when {
				("h" in lcDate || "heure" in lcDate) && "chapitre" !in lcDate -> cal.apply {
					add(
						Calendar.HOUR_OF_DAY,
						-number,
					)
				}.timeInMillis

				"min" in lcDate -> cal.apply { add(Calendar.MINUTE, -number) }.timeInMillis
				"jour" in lcDate || lcDate.endsWith("j") -> cal.apply {
					add(
						Calendar.DAY_OF_MONTH,
						-number,
					)
				}.timeInMillis

				"semaine" in lcDate -> cal.apply { add(Calendar.WEEK_OF_YEAR, -number) }.timeInMillis
				"mois" in lcDate || (lcDate.endsWith("m") && "min" !in lcDate) -> cal.apply {
					add(
						Calendar.MONTH,
						-number,
					)
				}.timeInMillis

				"an" in lcDate -> cal.apply { add(Calendar.YEAR, -number) }.timeInMillis
				else -> 0L
			}

			else -> parseChapterDate(SimpleDateFormat(datePattern, sourceLocale), date)
		}
	}

	private data class RjAjaxConfig(
		val ajaxUrl: String,
		val form: Map<String, String>,
		val responseKeys: JSONArray,
		val fieldOffsetKey: String,   // fieldNames[6] - offset field in form data
		val fieldTokenKey: String,    // fieldNames[9] - token field in form data
	)

	private companion object {
		private val RJ_PAYLOAD_REGEX = Regex("""\{[^}]*"m":"[^"]*","c":\{[^}]*\}\}""")
	}
}
