package org.koitharu.kotatsu.parsers.site.pt

import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import okhttp3.Headers
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaParser
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.ContentRating
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
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.urlEncoded
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("NEXUSMANGAS_PTBR", "Nexus Mangás", "pt")
internal class NexusMangas(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.NEXUSMANGAS_PTBR, PAGE_SIZE) {

	override val configKeyDomain = ConfigKey.Domain("www.nexusmangas.com")

	override val defaultSortOrder: SortOrder
		get() = SortOrder.POPULARITY

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.POPULARITY,
		SortOrder.RELEVANCE,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(isSearchSupported = true)

	override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
		.set("Referer", "https://$domain/")
		.set("Origin", "https://$domain")
		.set("Accept-Language", "pt-BR,pt;q=0.9")
		.build()

	override suspend fun getFilterOptions() = MangaListFilterOptions()

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val query = filter.query?.trim()?.takeIf(String::isNotEmpty)
		val url = if (query == null) {
			"https://$domain/ranking"
		} else {
			"https://$domain/search?q=" + query.urlEncoded()
		}
		val script = if (query == null) {
			rankingScript(page)
		} else {
			searchScript(query)
		}
		val document = loadRenderedDocument(url, script)
		ensureAccessIsAvailable(document, url)
		val manga = parseMangaList(document)
		return if (query == null) manga else manga.drop((page - 1) * PAGE_SIZE).take(PAGE_SIZE)
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val mangaUrl = manga.publicUrl.ifBlank { manga.url.toAbsoluteUrl(domain) }
		val document = loadRenderedDocument(mangaUrl, DETAILS_SCRIPT)
		ensureAccessIsAvailable(document, mangaUrl)
		val chapterScan = document.selectFirst("[data-kotatsu-chapter-scan]")
		val expectedChapterCount = chapterScan?.attr("data-total")?.toIntOrNull()
		val capturedChapterCount = chapterScan?.attr("data-captured")?.toIntOrNull()
		if (expectedChapterCount != null && capturedChapterCount != null && capturedChapterCount < expectedChapterCount) {
			throw ParseException(
				"Nexus Mangás loaded $capturedChapterCount of $expectedChapterCount chapters",
				mangaUrl,
			)
		}

		val bodyText = document.body()?.wholeText().orEmpty()
		val title = document.selectFirst("main h1, h1")?.text()?.trim()?.takeIf(String::isNotEmpty)
			?: manga.title
		val description = bodyText.substringAfter("SINOPSE DETALHADA", "")
			.substringBefore("AVALIAÇÃO NEXUS")
			.trim()
			.takeIf(String::isNotEmpty)
		val tags = document.select("a[href*='/search?genre='], a[href*='/search?theme=']")
			.mapNotNullTo(linkedSetOf()) { link ->
				val label = link.text().trim().trimStart('#')
				val path = link.attrAsRelativeUrl("href")
				if (label.isEmpty() || path.isEmpty()) {
					null
				} else {
					MangaTag(
						key = path,
						title = label.lowercase(Locale.ROOT).split(' ').joinToString(" ") {
							it.replaceFirstChar { char -> char.titlecase(Locale.ROOT) }
						},
						source = source,
					)
				}
			}
		val author = bodyText.substringAfter("AUTOR", "")
			.substringBefore("ARTISTA")
			.trim()
			.takeIf(String::isNotEmpty)
			?.let(::setOf)
		val coverUrl = document.select("img[alt]")
			.firstOrNull { it.attr("alt").equals(title, ignoreCase = true) }
			?.let(::imageUrl)
		val allText = document.body()?.text().orEmpty()

		return manga.copy(
			title = title,
			coverUrl = coverUrl ?: manga.coverUrl,
			description = description ?: manga.description,
			tags = tags.ifEmpty { manga.tags },
			authors = author ?: manga.authors,
			state = parseState(allText) ?: manga.state,
			rating = Regex("AVALIAÇÃO NEXUS\\s+([0-5](?:[.,][0-9]+)?)", RegexOption.IGNORE_CASE)
				.find(allText)
				?.groupValues
				?.getOrNull(1)
				?.replace(',', '.')
				?.toFloatOrNull()
				?.div(5f)
				?: manga.rating.takeIf { it != RATING_UNKNOWN }
				?: RATING_UNKNOWN,
			chapters = parseChapters(document, manga),
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val chapterUrl = chapter.url.toAbsoluteUrl(domain)
		val document = loadRenderedDocument(chapterUrl, PAGES_SCRIPT)
		ensureAccessIsAvailable(document, chapterUrl)

		val pages = document.select("img[data-kotatsu-page-url]")
			.mapNotNull { image ->
				val url = image.attr("data-kotatsu-page-url")
					.takeIf { it.isNotBlank() && !it.startsWith("data:") }
					?: return@mapNotNull null
				MangaPage(
					id = generateUid(url),
					url = url,
					preview = null,
					source = source,
				)
			}
			.distinctBy { it.url }
		if (pages.isEmpty()) {
			throw ParseException("Nexus Mangás did not return any chapter pages", chapterUrl)
		}
		return pages
	}

	private suspend fun loadRenderedDocument(url: String, script: String): Document {
		val raw = context.evaluateJs(url, script, WEBVIEW_TIMEOUT)
			?: throw ParseException("Nexus Mangás did not return page content", url)
		val html = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull() ?: raw
		return Jsoup.parse(html, url)
	}

	private fun parseMangaList(document: Document): List<Manga> {
		return document.select("a[href*='/obra/']")
			.mapNotNull { link ->
				val url = link.attrAsRelativeUrl("href").substringBefore('#').substringBefore('?')
				if (!url.startsWith("/obra/")) return@mapNotNull null

				val linkText = link.text().trim()
				val heading = link.selectFirst("h2, h3, h4")
				val title = heading?.attr("title")?.trim()?.takeIf(String::isNotEmpty)
					?: heading?.text()?.trim()?.takeIf(String::isNotEmpty)
					?: link.attr("aria-label").takeIf(String::isNotEmpty)
					?: cleanCardTitle(linkText)
				if (title.isBlank()) return@mapNotNull null

				val image = link.selectFirst("img")?.let(::imageUrl)
				val statusText = (
					linkText + " " + link.select("span").joinToString(" ") { it.text() }
				).lowercase(Locale.ROOT)
				Manga(
					id = generateUid(url),
					title = title,
					altTitles = emptySet(),
					url = url,
					publicUrl = url.toAbsoluteUrl(domain),
					rating = RATING_UNKNOWN,
					contentRating = parseContentRating(statusText),
					coverUrl = image,
					tags = emptySet(),
					state = parseState(statusText),
					authors = emptySet(),
				description = null,
				chapters = null,
				source = source,
				)
			}
			.distinctBy { it.url }
	}

	private fun parseChapters(document: Document, manga: Manga): List<MangaChapter> {
		val slug = manga.url.substringBefore('?').substringAfterLast('/').ifBlank { return emptyList() }
		val parsed = document.getAllElements().mapNotNull { element ->
			val match = CHAPTER_LINE_REGEX.matchEntire(element.ownText().trim()) ?: return@mapNotNull null
			val numberText = match.groupValues[1].replace(',', '.')
			val number = numberText.toFloatOrNull() ?: return@mapNotNull null
			val path = "/capitulo/$slug/$numberText"
			val row = element.parents().firstOrNull { ancestor ->
				ancestor.hasClass("cursor-pointer") && ancestor.text().contains(numberText)
			}
			val title = match.groupValues.getOrNull(2)?.trim()?.takeIf(String::isNotEmpty)
				?: row?.selectFirst("span[class*=truncate]")?.text()?.trim()?.replaceFirst(Regex("^[-–—]\\s*"), "")
					?.takeIf(String::isNotEmpty)
			MangaChapter(
				id = generateUid(path),
				title = title,
				number = number,
				volume = 0,
				url = path,
				scanlator = null,
				uploadDate = parseUploadDate(row ?: element),
				branch = null,
				source = source,
			)
		}
		return parsed
			.distinctBy { it.url }
			.sortedBy { it.number }
	}

	private fun parseUploadDate(element: Element): Long {
		val date = DATE_REGEX.find(element.text())?.value ?: return 0L
		return runCatching { SimpleDateFormat(DATE_FORMAT_PATTERN, Locale.ROOT).parse(date)?.time }.getOrNull() ?: 0L
	}

	private fun cleanCardTitle(text: String): String {
		var value = text.replace(Regex("^\\s*#?\\d+\\s*"), "").trim()
		val typeLabel = Regex("(?i)\\s+(?:MANGA|MANHWA|MANHUA|WEBTOON|NOVEL)\\b").find(value)
		if (typeLabel != null) value = value.substring(0, typeLabel.range.first)
		value = value.replace(Regex("(?i)\\s+\\d[\\d.,]*\\s+views?\\b.*$"), "").trim()
		val words = value.split(Regex("\\s+")).filter(String::isNotBlank)
		for (size in words.size / 2 downTo 1) {
			if (words.take(size).joinToString(" ").equals(words.drop(size).take(size).joinToString(" "), true)) {
				return words.take(size).joinToString(" ")
			}
		}
		return value
	}

	private fun imageUrl(element: Element): String? {
		val value = sequenceOf("data-kotatsu-page-url", "data-src", "data-lazy-src", "data-original", "src")
			.map(element::attr)
			.firstOrNull(String::isNotBlank)
			?.takeUnless { it.startsWith("data:") }
			?: return null
		return value.toAbsoluteUrl(domain)
	}

	private fun parseState(text: String): MangaState? {
		val normalized = text.lowercase(Locale.ROOT)
		return when {
			"concluída" in normalized || "concluído" in normalized ||
				"finalizado" in normalized || "finalizada" in normalized -> MangaState.FINISHED
			ACTIVE_STATUS_REGEX.containsMatchIn(normalized) -> MangaState.ONGOING
			"em andamento" in normalized || "em lançamento" in normalized ||
				"andamento" in normalized -> MangaState.ONGOING
			else -> null
		}
	}

	private fun parseContentRating(text: String): ContentRating? {
		val normalized = text.lowercase(Locale.ROOT)
		return when {
			"hentai" in normalized -> ContentRating.ADULT
			"ecchi" in normalized -> ContentRating.SUGGESTIVE
			else -> null
		}
	}

	private fun ensureAccessIsAvailable(document: Document, url: String) {
		val text = document.body()?.text().orEmpty()
		if (text.contains("Realize uma ação para continuar acessando o conteúdo deste site", ignoreCase = true) ||
			text.contains("Acesso a todo o site por 24 horas", ignoreCase = true)
		) {
			context.requestBrowserAction(this, url)
		}
	}

	private fun searchScript(query: String): String = SEARCH_SCRIPT.replace("__NEXUS_QUERY__", JSONObject.quote(query))

	private fun rankingScript(page: Int): String = RANKING_SCRIPT.replace("__NEXUS_PAGE__", page.toString())

	private companion object {
		const val PAGE_SIZE = 10
		const val WEBVIEW_TIMEOUT = 45000L
		val CHAPTER_LINE_REGEX = Regex("(?i)^Cap\\.\\s*([0-9]+(?:[.,][0-9]+)?)(?:\\s*[-–]\\s*(.*))?$")
		val DATE_REGEX = Regex("\\b\\d{2}/\\d{2}/\\d{4}\\b")
		val ACTIVE_STATUS_REGEX = Regex("""\bativ[oa]s?\b""")
		const val DATE_FORMAT_PATTERN = "dd/MM/yyyy"

		val DETAILS_SCRIPT = """
			(() => {
				const key = "__nexusMangasDetailsState";
				const state = window[key] || (window[key] = {
					started: Date.now(),
					previousCount: 0,
					stablePasses: 0,
					chapters: new Map(),
					total: null,
				});
				const text = document.body?.innerText || "";
				const finish = () => {
					const root = document.documentElement?.cloneNode(true);
					const body = root?.querySelector("body");
					if (body) {
						const metadata = document.createElement("div");
						metadata.setAttribute("data-kotatsu-chapter-scan", "");
						metadata.setAttribute("data-total", state.total == null ? "" : String(state.total));
						metadata.setAttribute("data-captured", String(state.chapters.size));
						const rows = document.createElement("div");
						rows.setAttribute("data-kotatsu-extracted-chapters", "");
						rows.innerHTML = Array.from(state.chapters.values()).join("");
						metadata.appendChild(rows);
						body.appendChild(metadata);
					}
					return root?.outerHTML || document.documentElement?.outerHTML || "";
				};
				if (text.includes("Realize uma ação para continuar acessando") ||
					text.includes("Acesso a todo o site por 24 horas")) return finish();

				if (!document.querySelector("h1") || !text.toLocaleUpperCase().includes("CAPÍTULOS")) {
					return Date.now() - state.started > 25000 ? finish() : null;
				}

				const total = text.match(/(\d+)\s+lançamentos/i);
				if (total) state.total = Number(total[1]);
				const labels = Array.from(document.querySelectorAll("*")).filter(label => {
					const directText = Array.from(label.childNodes)
						.filter(node => node.nodeType === Node.TEXT_NODE)
						.map(node => (node.textContent || "").trim())
						.filter(Boolean)
						.join(" ");
					return /^Cap\.\s*\d+(?:[.,]\d+)?$/i.test(directText);
				});
				for (const label of labels) {
					const row = label.closest('div[class*="cursor-pointer"]');
					if (!row) continue;
					const number = label.textContent.trim();
					state.chapters.set(number + "|" + row.innerText.trim(), row.outerHTML);
				}
				if (labels.length === 0) {
					return Date.now() - state.started >= 40000 ? finish() : null;
				}
				const findScroller = label => {
					for (let element = label?.parentElement; element; element = element.parentElement) {
						const style = getComputedStyle(element);
						if (element.scrollHeight > element.clientHeight + 100 && /(auto|scroll)/.test(style.overflowY)) {
							return element;
						}
					}
					return null;
				};
				const scroller = findScroller(labels[0]);
				if (!scroller) return finish();

				if (state.chapters.size === state.previousCount) {
					state.stablePasses++;
				} else {
					state.previousCount = state.chapters.size;
					state.stablePasses = 0;
				}
				const atBottom = scroller.scrollTop + scroller.clientHeight >= scroller.scrollHeight - 2;
				if (atBottom) {
					if ((state.total != null && state.chapters.size >= state.total) ||
						(state.total == null && state.stablePasses >= 3) ||
						state.stablePasses >= 6 || Date.now() - state.started >= 40000) {
						return finish();
					}
					return null;
				}
				scroller.scrollTop = Math.min(
					scroller.scrollTop + Math.max(1, Math.floor(scroller.clientHeight * 0.65)),
					scroller.scrollHeight,
				);
				return null;
			})()
		""".trimIndent()

		val SEARCH_SCRIPT = """
			(() => {
				const query = __NEXUS_QUERY__;
				const key = "__nexusMangasSearchState";
				let state = window[key];
				if (!state || state.query !== query) {
					state = window[key] = { query, started: Date.now() };
				}
				const text = () => document.body?.innerText || "";
				const finish = () => document.documentElement?.outerHTML || "";
				const input = document.querySelector('input[type="search"], input[type="text"], input:not([type])');
				const pageText = text();
				const hasResultCount = /\b\d+\s+resultados?\b/i.test(pageText) ||
					/NENHUM RESULTADO/i.test(pageText);
				const inputMatchesQuery = !input ||
					input.value.trim().toLocaleLowerCase() === query.trim().toLocaleLowerCase();
				if (pageText.includes("Realize uma ação para continuar acessando") ||
					pageText.includes("Acesso a todo o site por 24 horas") ||
					(inputMatchesQuery && hasResultCount) ||
					Date.now() - state.started > 30000) {
					return finish();
				}
				return null;
			})()
		""".trimIndent()

		val RANKING_SCRIPT = """
			(() => {
				const targetPage = __NEXUS_PAGE__;
				const key = "__nexusMangasRankingState";
				const state = window[key] || (window[key] = {
					started: Date.now(),
					worksSelected: false,
					page: 1,
					pageRequested: false,
					previousLinks: "",
					selectRequested: false,
				});
				const text = () => document.body?.innerText || "";
				const finish = () => document.documentElement?.outerHTML || "";
				const findButton = label => Array.from(document.querySelectorAll("button"))
					.find(button => (button.innerText || "").trim().toLocaleUpperCase() === label);
				const links = () => Array.from(document.querySelectorAll('a[href*="/obra/"]'))
					.map(link => link.getAttribute("href") || "")
					.join("|");

				if (text().includes("Realize uma ação para continuar acessando") ||
					text().includes("Acesso a todo o site por 24 horas")) return finish();

				if (!state.worksSelected) {
					if (text().includes("OBRAS MAIS VISTAS")) {
						state.worksSelected = true;
					} else {
						const obrasButton = findButton("OBRAS");
						if (obrasButton && !state.selectRequested) {
							state.selectRequested = true;
							obrasButton.click();
							return null;
						}
						if (Date.now() - state.started < 25000) return null;
						return finish();
					}
				}

				if (state.page < targetPage) {
					if (!state.pageRequested) {
						const next = findButton("PRÓXIMA");
						if (!next || next.disabled) return finish();
						state.previousLinks = links();
						state.pageRequested = true;
						next.click();
						return null;
					}
					if (links() !== state.previousLinks) {
						state.page++;
						state.pageRequested = false;
					} else if (Date.now() - state.started < 25000) {
						return null;
					} else {
						return finish();
					}
				}

				if (document.querySelectorAll('a[href*="/obra/"]').length > 0 ||
					Date.now() - state.started >= 25000) return finish();
				return null;
			})()
		""".trimIndent()

		val PAGES_SCRIPT = """
			(() => {
				const key = "__nexusMangasPagesState";
				const state = window[key] || (window[key] = {
					started: Date.now(),
					previousCount: -1,
					stablePasses: 0,
				});
				const isBlocked = () => {
					const text = document.body?.innerText || "";
					return text.includes("Realize uma ação para continuar acessando") ||
						text.includes("Acesso a todo o site por 24 horas");
				};
				const pageImages = () => Array.from(document.querySelectorAll("img[alt]"))
					.filter(image => /^Página\s+\d+$/i.test((image.getAttribute("alt") || "").trim()));
				const pageUrl = image => [
					image.currentSrc,
					image.getAttribute("src"),
					image.getAttribute("data-src"),
					image.getAttribute("data-lazy-src"),
					image.getAttribute("data-original"),
				].find(url => url && !url.startsWith("data:")) || "";
				const finish = () => {
					for (const image of pageImages()) {
						const url = pageUrl(image);
						if (url) image.setAttribute("data-kotatsu-page-url", url);
					}
					return document.documentElement?.outerHTML || "";
				};

				if (isBlocked()) return finish();
				const images = pageImages();
				if (images.length === 0) {
					return Date.now() - state.started < 30000 ? null : finish();
				}

				for (const image of images) {
					image.scrollIntoView({ block: "center" });
					const url = pageUrl(image);
					if (url) image.setAttribute("data-kotatsu-page-url", url);
				}

				if (images.length === state.previousCount) {
					state.stablePasses++;
				} else {
					state.previousCount = images.length;
					state.stablePasses = 0;
				}
				if (state.stablePasses >= 2 || Date.now() - state.started >= 38000) return finish();
				return null;
			})()
		""".trimIndent()
	}
}
