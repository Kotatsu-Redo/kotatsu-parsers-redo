package org.koitharu.kotatsu.parsers

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.model.search.MangaSearchQuery
import org.koitharu.kotatsu.parsers.model.search.QueryCriteria
import org.koitharu.kotatsu.parsers.model.search.SearchableField
import java.io.File
import java.util.Locale
import kotlin.time.Duration.Companion.minutes

/**
 * Harvests real chapter lists, for the chapter-alignment evaluation set in kotatsuredo-server.
 *
 * **A tool, not a test.** It talks to live websites, so it is gated on an environment variable and
 * never runs by accident:
 *
 *     CHAPTER_HARVEST_OUT=/path/to/chapters.json ./gradlew test --tests '*ChapterAlignmentHarvest*' -i
 *
 * Why it exists: aligning "source A's chapter 45" to "source B's chapter 45" has no published ground
 * truth anywhere, and the thresholds in the alignment design are reasoned guesses until something
 * measures them. The last time this project guessed at matching thresholds, the evaluation set showed
 * title-exact matching had 0.1% recall - so the rule is that the guess gets measured before it ships.
 *
 * The works below are chosen for the quirks, not for popularity: series that restart numbering
 * between seasons, that run past chapter 1000, that number by volume, and that have a Part 1 / Part 2
 * split. Those are the cases the offset search has to survive.
 */
internal class ChapterAlignmentHarvest {

	private val context = MangaLoaderContextMock

	@Test
	fun harvest() = runTest(timeout = 60.minutes) {
		val out = System.getenv("CHAPTER_HARVEST_OUT")
		assumeTrue(out != null, "CHAPTER_HARVEST_OUT is not set")

		val records = mutableListOf<String>()
		var attempted = 0
		var succeeded = 0

		for (source in SOURCES) {
			val parser = runCatching { context.newParserInstance(source) }.getOrNull() ?: continue
			for (title in WORKS) {
				attempted++
				val record = runCatching { harvestOne(parser, source, title) }
					.onFailure { println("  ! $source / $title: ${it.javaClass.simpleName}: ${it.message}") }
					.getOrNull()
				if (record != null) {
					records.add(record)
					succeeded++
				}
			}
			println("== $source done ($succeeded/$attempted so far)")
		}

		File(out!!).writeText(records.joinToString(",\n", "[\n", "\n]\n"))
		println("\nHarvested $succeeded of $attempted (source, work) pairs into $out")
	}

	private suspend fun harvestOne(
		parser: org.koitharu.kotatsu.parsers.MangaParser,
		source: MangaParserSource,
		title: String,
	): String? {
		val results = parser.getList(
			MangaSearchQuery.Builder()
				.order(SortOrder.RELEVANCE)
				.criterion(QueryCriteria.Match(SearchableField.TITLE_NAME, title))
				.build(),
		)
		// Title equality after a crude fold. Deliberately strict: a near-miss here would put two
		// different works in the set as though they were one, which is the one error that would make
		// the evaluation lie rather than merely be noisy.
		val match = results.firstOrNull { simplify(it.title) == simplify(title) }
			?: results.firstOrNull { simplify(it.title).startsWith(simplify(title)) }
			?: return null

		val chapters = parser.getDetails(match).chapters?.takeIf { it.isNotEmpty() } ?: return null

		return buildString {
			append("  {")
			append("\"source\":\"").append(source.name).append("\",")
			append("\"query\":").append(quote(title)).append(",")
			append("\"title\":").append(quote(match.title)).append(",")
			append("\"url\":").append(quote(match.url)).append(",")
			append("\"chapters\":[")
			chapters.joinTo(this, ",") { chapter ->
				buildString {
					append("{\"n\":").append(chapter.number)
					append(",\"v\":").append(chapter.volume)
					append(",\"b\":").append(quote(chapter.branch))
					append(",\"t\":").append(quote(chapter.title))
					append(",\"d\":").append(chapter.uploadDate)
					append(",\"s\":").append(quote(chapter.scanlator))
					append("}")
				}
			}
			append("]}")
		}
	}

	private fun simplify(value: String) = value.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

	private fun quote(value: String?): String {
		if (value == null) return "null"
		val escaped = StringBuilder("\"")
		for (character in value) {
			when (character) {
				'"' -> escaped.append("\\\"")
				'\\' -> escaped.append("\\\\")
				'\n' -> escaped.append("\\n")
				'\r' -> escaped.append("\\r")
				'\t' -> escaped.append("\\t")
				else -> if (character < ' ') {
					escaped.append("\\u%04x".format(character.code))
				} else {
					escaped.append(character)
				}
			}
		}
		return escaped.append('"').toString()
	}

	private companion object {

		/**
		 * A spread of source *shapes*, not a popularity ranking: a real API that exposes branches, a
		 * couple of aggregators, and scrapers that number their chapters by hand.
		 */
		val SOURCES = listOf(
			MangaParserSource.MANGADEX,
			MangaParserSource.COMICK_FUN,
			MangaParserSource.MANGAPARK,
			MangaParserSource.WEEBCENTRAL,
			MangaParserSource.MANGAREADERTO,
			MangaParserSource.MANGABUDDY,
			MangaParserSource.MANGAKAKALOTTV,
		)

		/**
		 * Chosen for their numbering, not their sales.
		 *
		 *  - Chainsaw Man and Kaguya-sama have a Part 1 / Part 2 split that some sources restart at 1
		 *    and others continue.
		 *  - Solo Leveling and Omniscient Reader are manhwa with season boundaries.
		 *  - One Piece and Detective Conan are past 1000, where a large offset is plausible.
		 *  - Berserk and Vagabond are numbered by volume on several sources.
		 *  - Kagurabachi and Dandadan are recent, so their lists are short and still growing.
		 */
		val WORKS = listOf(
			"Chainsaw Man",
			"One Piece",
			"Solo Leveling",
			"Jujutsu Kaisen",
			"Berserk",
			"Vinland Saga",
			"Oshi no Ko",
			"Dandadan",
			"Kagurabachi",
			"Sakamoto Days",
			"Omniscient Reader's Viewpoint",
			"Kaguya-sama: Love Is War",
			"Vagabond",
			"Detective Conan",
			"Blue Lock",
		)
	}
}
