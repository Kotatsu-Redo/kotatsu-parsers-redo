package tasks.tests

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.simpleframework.xml.core.Persister
import tasks.model.Failure
import tasks.model.TestCase
import tasks.model.TestSuite
import java.io.File
import java.util.Properties

private const val MAX_MESSAGE_LENGTH = 120
private const val MAX_FAILURES_SHOWN = 8

/**
 * What says a site is gone, in the failure of the test that lists its manga: its name is not known any more, the page
 * of the list is not there, what is there is not what the parser reads, or it lists nothing. Anything else that test
 * fails of says nothing of that: a refusal, a time-out, a picture that does not load, a sign-in asked for.
 */
private val SITE_GONE = Regex(
	"""java\.net\.UnknownHostException|parsers\.exception\.(NotFound|Parse)Exception|Status=(404|410)\b|""" +
		"""Manga list for 'list' is empty""",
)

/** What a test that works on one manga of the list fails of when there was none to take: the list came back empty. */
private const val NO_MANGA_TO_TAKE = "java.util.NoSuchElementException"

/** The tests that only pass with a list of manga in hand: one of them passing says the list came back. */
private val NEED_A_LIST = setOf("details", "pages", "link", "search", "tags", "tags_multiple")

/**
 * Tests whose failure leaves the source working for whoever reads with it: its icon, the address it is reached at,
 * opening a link to it, a filter by language, a filter by two genres at once. They are noted, and are not a reason to
 * keep a change out.
 */
private val MINOR_TESTS = setOf("favicon", "domain", "link", "locale", "tags_multiple")

/**
 * The same, by what a failure says, in any other test: a cover that does not load, pages of the list that overlap
 * because something was published between the two, a genre that is not capitalized, two chapters with the same
 * number. A picture that does not load in `pages` is not one of them: that is reading.
 */
private val MINOR_FAILURES = listOf(
	"Request failed: ",
	"Wrong response mime type: ",
	"Pages are intersected by ",
	"Not all tags are capitalized",
	"Chapters are not distinct by branch/volume/number",
)

/**
 * Reads how the live tests of the sources selectParserTests chose went, and says whether a person has to look at it
 * before the change is merged.
 *
 * It never fails because tests failed: that is what it reports. A failure caused by Cloudflare is told apart because
 * it says something about the site and about where the tests run, and nothing about the code. Small failures, see
 * [MINOR_TESTS] and [MINOR_FAILURES], are shown and let through.
 *
 * A source that a pull request removes cannot be tested in it, so it is judged by a run made as the base commit has it
 * (removedResultsDir): its removal is vouched for only when that run shows its site to be gone. A source that works,
 * and one the tests could not reach, are for a person to look at.
 *
 * So is any change outside the parser sources (the tests, the build, the workflows, the core): no result vouches for
 * it, however good.
 */
abstract class SummarizeParserTestsTask : DefaultTask() {

	/** Where selectParserTests wrote, and where this writes. */
	@get:Internal
	abstract val testsDir: DirectoryProperty

	/** The JUnit XML of the test task. */
	@get:Internal
	abstract val resultsDir: DirectoryProperty

	/** The JUnit XML of the run for the removed sources, made in a checkout of the base commit. It may not exist. */
	@get:Internal
	abstract val removedResultsDir: DirectoryProperty

	@get:Internal
	abstract val outputDir: DirectoryProperty

	init {
		outputs.upToDateWhen { false }
	}

	private enum class Result(val title: String) {
		PASSED("passed"),
		NOTED("passed, with small failures"),
		CLOUDFLARE("Cloudflare"),
		FAILED("failed"),
		NOT_RUN("not run"),
	}

	private enum class Removal(val title: String) {
		GONE("no longer works"),
		WORKS("still works"),
		LISTS("still lists manga"),
		CLOUDFLARE("Cloudflare"),
		UNCLEAR("cannot be told"),
		NOT_RUN("not run"),
	}

	@TaskAction
	fun run() {
		val dir = testsDir.get().asFile
		val sourcesFile = requireFile(dir, "sources.txt")
		val planFile = requireFile(dir, "plan.md")
		val selectionFile = requireFile(dir, "selection.properties")
		val selected = sourcesFile.readText().split(',').map { it.trim() }.filter { it.isNotEmpty() }
		val selectionProperties = Properties().apply { selectionFile.reader().use(::load) }
		val nothingChanged = selectionProperties.getProperty("nothingChanged")?.trim() == "true"
		val untested = selectionProperties.getProperty("untested")?.trim()?.toIntOrNull() ?: 0
		// Written by every selection since it was added, and a selection without it is not trusted with a green check
		val outsideSources = selectionProperties.getProperty("protected")?.trim()?.toIntOrNull()
			?: throw GradleException("$selectionFile does not say what changed outside the sources: run selectParserTests again")
		val plan = planFile.readText().trimEnd()
		// Outputs of older versions have no such file
		val removed = File(dir, "removed.txt").takeIf { it.isFile }?.readText().orEmpty()
			.split(',').map { it.trim() }.filter { it.isNotEmpty() }

		val cases = if (selected.isEmpty()) emptyMap() else readCases(resultsDir.get().asFile)
		val rows = selected.map { source -> source to judge(cases[source].orEmpty()) }
		val notPassed = rows.filter { it.second.first != Result.PASSED && it.second.first != Result.NOTED }
		val noted = rows.count { it.second.first == Result.NOTED }
		val removedCases = if (removed.isEmpty()) emptyMap() else readCases(removedResultsDir.get().asFile)
		val removedRows = removed.map { source -> source to judgeRemoved(removedCases[source].orEmpty()) }
		val notGone = removedRows.filter { it.second.first != Removal.GONE }
		val verdict = when {
			outsideSources > 0 -> "review"
			nothingChanged -> "nothing"
			notPassed.isEmpty() && untested == 0 && notGone.isEmpty() -> "passed"
			else -> "review"
		}

		val summary = buildString {
			appendLine("# Parser tests")
			appendLine()
			appendLine(plan)
			appendLine()
			if (rows.isNotEmpty()) {
				appendLine("## Results")
				appendLine()
				appendLine("| Source | Result | What failed |")
				appendLine("| --- | --- | --- |")
				for ((source, result) in rows) {
					appendLine("| $source | ${result.first.title} | ${result.second} |")
				}
				appendLine()
			}
			if (removedRows.isNotEmpty()) {
				appendLine("## Removed sources")
				appendLine()
				appendLine("| Source | As it was | What that says |")
				appendLine("| --- | --- | --- |")
				for ((source, result) in removedRows) {
					appendLine("| $source | ${result.first.title} | ${result.second} |")
				}
				appendLine()
			}
			val removedWork = if (removedRows.size == 1) "the one removed source no longer works" else
				"the ${removedRows.size} removed sources no longer work"
			val passedSmall = when {
				noted == 0 -> ""
				rows.size == 1 -> ", with small failures"
				else -> " ($noted with small failures)"
			}
			appendLine(
				when {
					verdict == "nothing" -> "Nothing that goes into the parsers changed, so there was nothing to test."
					verdict == "passed" && rows.isEmpty() ->
						if (removedRows.isEmpty()) "No source needed testing." else "No source needed testing, and $removedWork."
					verdict == "passed" && rows.size == 1 ->
						if (removedRows.isEmpty()) "The one tested source passed$passedSmall." else
							"The one tested source passed$passedSmall, and $removedWork."
					verdict == "passed" ->
						if (removedRows.isEmpty()) "All ${rows.size} tested sources passed$passedSmall." else
							"All ${rows.size} tested sources passed$passedSmall, and $removedWork."
					else -> {
						val reasons = ArrayList<String>()
						if (outsideSources > 0) {
							reasons += if (outsideSources == 1) "1 file outside the parser sources changed" else
								"$outsideSources files outside the parser sources changed"
						}
						if (notPassed.isNotEmpty()) {
							val counts = Result.entries.filter { it != Result.PASSED && it != Result.NOTED }
								.mapNotNull { kind -> rows.count { it.second.first == kind }.takeIf { it > 0 }?.let { "$it ${kind.title}" } }
							reasons += "${notPassed.size} of ${rows.size} tested sources did not pass (${counts.joinToString(", ")})"
						}
						if (untested > 0) {
							reasons += if (untested == 1) "1 change could not be tested" else "$untested changes could not be tested"
						}
						if (notGone.isNotEmpty()) {
							reasons += "${notGone.size} of ${removedRows.size} removed " +
								"${if (removedRows.size == 1) "source" else "sources"} " +
								"${if (notGone.size == 1) "still works" else "still work"} or could not be judged"
						}
						"${reasons.joinToString(" and ")}, so a person has to look at it."
					}
				},
			)
		}
		val out = outputDir.get().asFile
		out.mkdirs()
		File(out, "verdict.txt").writeText(verdict)
		File(out, "summary.md").writeText(summary)
		logger.lifecycle("Verdict: $verdict")
	}

	private fun requireFile(dir: File, name: String): File {
		return File(dir, name).takeIf { it.isFile }
			?: throw GradleException("${File(dir, name)} is missing: run selectParserTests first")
	}

	/** The test cases by source. */
	private fun readCases(dir: File): Map<String, List<TestCase>> {
		val serializer = Persister()
		val files = dir.listFiles { file -> file.isFile && file.name.endsWith(".xml") }.orEmpty().sortedBy { it.name }
		return files.flatMap { serializer.read(TestSuite::class.java, it).testCases }
			.filter { it.isValid() }
			.groupBy { it.source }
	}

	private fun judge(cases: List<TestCase>): Pair<Result, String> {
		if (cases.isEmpty()) {
			return Result.NOT_RUN to ""
		}
		val failed = cases.filter { it.failure != null }
		if (failed.isEmpty()) {
			return Result.PASSED to ""
		}
		val (small, hard) = failed.partition(::isSmall)
		if (hard.isEmpty()) {
			return Result.NOTED to describeAll(small)
		}
		return (if (hard.all(::isCloudflare)) Result.CLOUDFLARE else Result.FAILED) to describeAll(hard + small)
	}

	/** A failure that leaves the source working for whoever reads with it, see [MINOR_TESTS] and [MINOR_FAILURES]. */
	private fun isSmall(case: TestCase): Boolean {
		if (case.testName in MINOR_TESTS) {
			return true
		}
		if (case.testName == "pages") {
			return false
		}
		val failure = checkNotNull(case.failure)
		return MINOR_FAILURES.any { failure.message.contains(it) }
	}

	/**
	 * What a removed source was. The test that lists its manga failing is not enough to say it no longer works: that
	 * test also loads a picture, and fails of a site that refuses the tests or asks to sign in. The removal is
	 * vouched for only on a sign that the site is gone, see [SITE_GONE], and never when a test that needs a list of
	 * manga passed, whatever else failed.
	 */
	private fun judgeRemoved(cases: List<TestCase>): Pair<Removal, String> {
		val list = cases.firstOrNull { it.testName == "list" } ?: return Removal.NOT_RUN to ""
		val failed = cases.filter { it.failure != null }
		if (failed.isEmpty()) {
			return Removal.WORKS to "its tests pass: removing it takes away a source that works"
		}
		val failure = list.failure
		if (failure == null || cases.any { it.testName in NEED_A_LIST && it.failure == null }) {
			return Removal.LISTS to describeAll(failed)
		}
		if (isCloudflare(list)) {
			return Removal.CLOUDFLARE to "cannot be told from here"
		}
		// The tests that take one manga of the list have to say the same: a list that came back whole, with a picture
		// on a host that is gone, fails the first test in the very way a site that is gone does
		val takingOne = cases.filter { it.testName == "details" || it.testName == "link" }
		val isGone = showsSiteGone(failure) && takingOne.isNotEmpty() && takingOne.all { case ->
			case.failure?.let { showsSiteGone(it) || it.type == NO_MANGA_TO_TAKE } == true
		}
		if (isGone) {
			return Removal.GONE to describe(list)
		}
		// Refused, timed out, asked to sign in: the site may well be there for a phone
		return Removal.UNCLEAR to describe(list)
	}

	private fun showsSiteGone(failure: Failure): Boolean {
		return SITE_GONE.containsMatchIn(failure.type) || SITE_GONE.containsMatchIn(failure.message)
	}

	private fun isCloudflare(case: TestCase): Boolean {
		val failure = checkNotNull(case.failure)
		return listOf(failure.type, failure.message, failure.text).any { it.contains("CloudFlareProtectedException") }
	}

	private fun describeAll(failed: List<TestCase>): String {
		val lines = failed.take(MAX_FAILURES_SHOWN).map { case -> "${case.testName}: ${describe(case)}" }
		val more = failed.size - lines.size
		return (lines + listOfNotNull("and $more more".takeIf { more > 0 })).joinToString("<br>")
	}

	/** The first line of what was said of a failure, short and safe to put in a cell of a table. */
	private fun describe(case: TestCase): String {
		val failure = checkNotNull(case.failure)
		val line = failure.message.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
			?: failure.text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
			?: failure.type
		val short = if (line.length > MAX_MESSAGE_LENGTH) line.take(MAX_MESSAGE_LENGTH - 1) + "…" else line
		return short.replace("|", "\\|").replace("<", "&lt;").replace(">", "&gt;")
	}
}
