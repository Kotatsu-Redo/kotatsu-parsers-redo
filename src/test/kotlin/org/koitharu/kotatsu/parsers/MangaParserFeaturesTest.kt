package org.koitharu.kotatsu.parsers

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.jsoup.HttpStatusException
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.params.ParameterizedTest
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.mimeType
import org.koitharu.kotatsu.test_util.isDistinctBy
import org.koitharu.kotatsu.test_util.isUrlAbsolute
import java.io.IOException
import java.util.Calendar
import kotlin.time.Duration.Companion.minutes

/**
 * Checks everything a reader does with a source, through the API the app calls
 * (`getList` with a [MangaListFilter]): every sort order, every filter the source offers,
 * searching for several titles, opening several manga, and reading the first, a middle and the last
 * chapter with their images.
 *
 * [MangaParserTest] samples one of each; this goes through all of them. Each test collects every
 * problem it finds and fails once, listing them all. Tests of one source run one after another
 * (alongside [MangaParserTest]) so a site isn't hit by everything at once.
 */
@Execution(ExecutionMode.SAME_THREAD)
internal class MangaParserFeaturesTest {

	private val context = MangaLoaderContextMock
	private val timeout = 5.minutes

	@ParameterizedTest(name = "{index}|sorts|{0}")
	@MangaSources
	fun sorts(source: MangaParserSource) = runTest(timeout = timeout) {
		val parser = context.newParserInstance(source)
		val problems = Problems()
		for (order in parser.availableSortOrders.sortedBy { it.ordinal }) {
			problems.check("sort $order") {
				val list = parser.getList(0, order, MangaListFilter.EMPTY)
				// Relevance without a query legitimately returns nothing on some sites.
				if (order != SortOrder.RELEVANCE || list.isNotEmpty()) {
					checkList(list, source)
				}
			}
		}
		problems.assertNone()
	}

	@ParameterizedTest(name = "{index}|filters|{0}")
	@MangaSources
	fun filters(source: MangaParserSource) = runTest(timeout = timeout) {
		val parser = context.newParserInstance(source)
		val capabilities = parser.filterCapabilities
		val options = parser.getFilterOptions()
		val order = parser.defaultOrder()
		val problems = Problems()

		suspend fun list(filter: MangaListFilter) = parser.getList(0, order, filter)

		// Genres alone (first, middle and last offered): none may fail, and since a site can have
		// genres with no titles yet, at least one of them must list titles.
		val tags = options.availableTags.sortedBy { it.title }
		problems.checkEach("genre", tags.spread(3)) { tag ->
			list(MangaListFilter(tags = setOf(tag))).also { if (it.isNotEmpty()) checkList(it, source) }
		}
		if (capabilities.isMultipleTagsSupported && tags.size >= 2) {
			// Two genres together may match nothing, but must not fail.
			val pair = tags.spread(2).toSet()
			problems.check("genres ${pair.joinToString { it.title }} together") { list(MangaListFilter(tags = pair)) }
		}
		if (capabilities.isTagsExclusionSupported && tags.isNotEmpty()) {
			problems.check("excluding genre '${tags.first().title}'") {
				checkList(list(MangaListFilter(tagsExclude = setOf(tags.first()))), source)
			}
		}

		// Every status, rating, type and demographic offered must not fail, and at least one value
		// of each must list titles.
		problems.checkEach("status", options.availableStates) { list(MangaListFilter(states = setOf(it))) }
		problems.checkEach("content rating", options.availableContentRating) { list(MangaListFilter(contentRating = setOf(it))) }
		problems.checkEach("type", options.availableContentTypes) { list(MangaListFilter(types = setOf(it))) }
		problems.checkEach("demographic", options.availableDemographics) { list(MangaListFilter(demographics = setOf(it))) }

		for (locale in options.availableLocales.sortedBy { it.toLanguageTag() }.spread(2)) {
			problems.check("language $locale") { checkList(list(MangaListFilter(locale = locale)), source) }
		}
		if (capabilities.isOriginalLocaleSupported && options.availableLocales.isNotEmpty()) {
			val locale = options.availableLocales.first()
			problems.check("original language $locale") { list(MangaListFilter(originalLocale = locale)) }
		}
		val lastYear = Calendar.getInstance().get(Calendar.YEAR) - 1
		if (capabilities.isYearSupported) {
			problems.check("year $lastYear") { list(MangaListFilter(year = lastYear)) }
		}
		if (capabilities.isYearRangeSupported) {
			problems.check("years 2000-$lastYear") { checkList(list(MangaListFilter(yearFrom = 2000, yearTo = lastYear)), source) }
		}
		if (capabilities.isSearchWithFiltersSupported && tags.isNotEmpty()) {
			problems.check("search with genre '${tags.first().title}'") {
				list(MangaListFilter(query = "a", tags = setOf(tags.first())))
			}
		}
		problems.assertNone()
	}

	@ParameterizedTest(name = "{index}|searchTitles|{0}")
	@MangaSources
	fun searchTitles(source: MangaParserSource) = runTest(timeout = timeout) {
		val parser = context.newParserInstance(source)
		if (!parser.filterCapabilities.isSearchSupported) {
			return@runTest // the source offers no search
		}
		val order = if (SortOrder.RELEVANCE in parser.availableSortOrders) SortOrder.RELEVANCE else parser.defaultOrder()
		val problems = Problems()
		val subjects = parser.getList(0, parser.defaultOrder(), MangaListFilter.EMPTY).spread(3)
		check(subjects.isNotEmpty()) { "Nothing listed to search for" }
		for (subject in subjects) {
			problems.check("search '${subject.title}'") {
				// What a reader types: the full title, the title without punctuation, or a few of its words.
				val plain = subject.title.replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()
				val words = plain.split(' ').filter { it.length >= 3 }.sortedByDescending { it.length }.take(3)
				val queries = listOf(subject.title, plain, words.joinToString(" ")).filter { it.isNotBlank() }.distinct()
				val errors = mutableListOf<String>()
				var found = false
				for (query in queries) {
					try {
						val results = parser.getList(0, order, MangaListFilter(query = query))
						results.forEach { check(it.source == source) { "result from another source" } }
						if (results.any { it.url == subject.url }) {
							found = true
							break
						}
					} catch (e: CancellationException) {
						throw e
					} catch (e: Exception) {
						errors += "query '$query' failed: ${e.javaClass.simpleName}: ${e.message?.lineSequence()?.firstOrNull()}"
					}
				}
				// A query that errors is a bug even when a shorter one finds the title.
				check(errors.isEmpty()) { errors.first() }
				check(found) { "not found by its title or its words (${subject.publicUrl})" }
			}
		}
		problems.assertNone()
	}

	@ParameterizedTest(name = "{index}|detailsMany|{0}")
	@MangaSources
	fun detailsMany(source: MangaParserSource) = runTest(timeout = timeout) {
		val parser = context.newParserInstance(source)
		val problems = Problems()
		for (manga in parser.getList(0, parser.defaultOrder(), MangaListFilter.EMPTY).spread(3)) {
			problems.check("details of '${manga.title}' (${manga.publicUrl})") {
				val details = parser.getDetails(manga)
				check(details.id == manga.id && details.url == manga.url) { "id or url changed by getDetails" }
				check(details.title.isNotBlank()) { "blank title" }
				val chapters = details.chapters
				check(!chapters.isNullOrEmpty()) { "no chapters" }
				check(chapters.isDistinctBy { it.id }) { "duplicate chapter ids" }
				check(chapters.all { it.source == source && it.url.isNotBlank() }) { "chapter with wrong source or blank url" }
				val numbered = chapters.map { it.number }.filter { it > 0f }
				if (numbered.size >= 3) {
					// The app shows chapters in list order and expects oldest first.
					check(numbered.first() <= numbered.last()) {
						"chapters are newest first (${numbered.first()} ... ${numbered.last()}), the app expects oldest first"
					}
				}
			}
		}
		problems.assertNone()
	}

	@ParameterizedTest(name = "{index}|chaptersImages|{0}")
	@MangaSources
	fun chaptersImages(source: MangaParserSource) = runTest(timeout = timeout) {
		val parser = context.newParserInstance(source)
		val problems = Problems()
		// A manga with several chapters, so the first, a middle and the last one can be read.
		var best: Manga? = null
		for (manga in parser.getList(0, parser.defaultOrder(), MangaListFilter.EMPTY).take(6)) {
			val details = runCatching { parser.getDetails(manga) }.getOrNull() ?: continue
			if ((details.chapters?.size ?: 0) > (best?.chapters?.size ?: 0)) {
				best = details
			}
			if ((best?.chapters?.size ?: 0) >= 3) break
		}
		val manga = checkNotNull(best) { "No manga with chapters among the first listed" }
		val chapters = checkNotNull(manga.chapters)
		for (chapter in chapters.spread(3)) {
			val name = "'${manga.title}' chapter '${chapter.title ?: chapter.number}' (${chapter.url})"
			problems.check(name) {
				val pages = parser.getPages(chapter)
				check(pages.isNotEmpty()) { "no pages" }
				check(pages.isDistinctBy { it.id }) { "duplicate page ids" }
				for (page in pages.spread(3)) {
					val url = parser.getPageUrl(page)
					check(url.isUrlAbsolute()) { "page url is not absolute: $url" }
					checkImage(url, source)
				}
			}
		}
		problems.assertNone()
	}

	private fun MangaParser.defaultOrder(): SortOrder =
		SortOrder.entries.first { it in availableSortOrders }

	private fun checkList(list: List<Manga>, source: MangaSource) {
		check(list.isNotEmpty()) { "no titles listed" }
		check(list.isDistinctBy { it.id }) { "duplicate ids in the list" }
		for (item in list) {
			check(item.source == source) { "item from another source" }
			check(item.title.isNotBlank()) { "blank title at ${item.publicUrl}" }
			check(item.url.isNotBlank() && !item.url.isUrlAbsolute()) { "url not relative: ${item.url}" }
			check(item.publicUrl.isUrlAbsolute()) { "public url not absolute: ${item.publicUrl}" }
		}
	}

	/** Downloads [url] like the reader does (through the parser's interceptor) and checks it is an image. */
	private suspend fun checkImage(url: String, source: MangaSource) {
		context.doRequest(url, source).use { response ->
			check(response.isSuccessful) { "image HTTP ${response.code}: $url" }
			val bytes = checkNotNull(response.body).bytes()
			check(bytes.size > 100) { "image is only ${bytes.size} bytes: $url" }
			check(response.mimeType?.startsWith("image/") == true || bytes.looksLikeImage()) {
				"not an image (${response.mimeType}): $url"
			}
		}
	}

	private fun ByteArray.looksLikeImage(): Boolean {
		fun at(offset: Int, vararg bytes: Int) =
			size >= offset + bytes.size && bytes.indices.all { this[offset + it] == bytes[it].toByte() }
		return at(0, 0xFF, 0xD8, 0xFF) || // JPEG
			at(0, 0x89, 0x50, 0x4E, 0x47) || // PNG
			at(0, 0x47, 0x49, 0x46) || // GIF
			(at(0, 0x52, 0x49, 0x46, 0x46) && at(8, 0x57, 0x45, 0x42, 0x50)) || // WEBP
			at(4, 0x66, 0x74, 0x79, 0x70) || // AVIF / HEIF
			at(0, 0xFF, 0x0A) || at(4, 0x4A, 0x58, 0x4C, 0x20) || // JPEG XL
			at(0, 0x42, 0x4D) // BMP
	}

	/** First, middle and last items (or [count] items spread evenly), without repeats. */
	private fun <T> List<T>.spread(count: Int): List<T> = when {
		isEmpty() -> emptyList()
		count <= 1 -> listOf(first())
		else -> (0 until count).map { this[it * (size - 1) / (count - 1)] }.distinct()
	}

	private class Problems {

		private val items = mutableListOf<String>()

		suspend fun check(what: String, block: suspend () -> Unit) {
			try {
				try {
					block()
				} catch (e: Exception) {
					// One retry for the site being momentarily overloaded or unreachable, like a reader would.
					if (e is CancellationException || !e.isTransient()) throw e
					withContext(Dispatchers.Default) { delay(3_000L) }
					block()
				}
			} catch (e: CancellationException) {
				throw e
			} catch (e: Throwable) {
				val message = e.message?.lineSequence()?.firstOrNull()?.take(200).orEmpty()
				items += "$what: ${if (e is IllegalStateException) "" else e.javaClass.simpleName + ": "}$message"
			}
		}

		private fun Throwable.isTransient(): Boolean = when (this) {
			is HttpStatusException -> statusCode >= 500 || statusCode == 429
			is IOException -> true
			else -> false
		}

		/** Every value must not fail, and at least one of them must list titles. */
		suspend fun <T> checkEach(kind: String, values: Collection<T>, list: suspend (T) -> List<Manga>) {
			var anyResults = false
			for (value in values) {
				check("$kind $value") {
					if (list(value).isNotEmpty()) anyResults = true
				}
			}
			if (values.isNotEmpty() && !anyResults) {
				items += "no $kind value lists any titles"
			}
		}

		fun assertNone() {
			if (items.isNotEmpty()) {
				throw AssertionError("${items.size} problem(s): " + items.joinToString(" | "))
			}
		}
	}
}
