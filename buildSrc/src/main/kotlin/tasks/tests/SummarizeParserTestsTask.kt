package tasks.tests

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.simpleframework.xml.core.Persister
import tasks.model.TestCase
import tasks.model.TestSuite
import java.io.File
import java.util.Properties

private const val MAX_MESSAGE_LENGTH = 120
private const val MAX_FAILURES_SHOWN = 8

/**
 * Reads how the live tests of the sources selectParserTests chose went, and says whether a person has to look at it
 * before the change is merged.
 *
 * It never fails because tests failed: that is what it reports. A failure caused by Cloudflare is told apart because
 * it says something about the site and about where the tests run, and nothing about the code.
 */
abstract class SummarizeParserTestsTask : DefaultTask() {

	/** Where selectParserTests wrote, and where this writes. */
	@get:Internal
	abstract val testsDir: DirectoryProperty

	/** The JUnit XML of the test task. */
	@get:Internal
	abstract val resultsDir: DirectoryProperty

	@get:Internal
	abstract val outputDir: DirectoryProperty

	init {
		outputs.upToDateWhen { false }
	}

	private enum class Result(val title: String) {
		PASSED("passed"),
		CLOUDFLARE("Cloudflare"),
		FAILED("failed"),
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
		val plan = planFile.readText().trimEnd()

		val cases = if (selected.isEmpty()) emptyMap() else readCases(resultsDir.get().asFile)
		val rows = selected.map { source -> source to judge(cases[source].orEmpty()) }
		val notPassed = rows.filter { it.second.first != Result.PASSED }
		val verdict = when {
			nothingChanged -> "nothing"
			notPassed.isEmpty() && untested == 0 -> "passed"
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
			appendLine(
				when {
					nothingChanged -> "Nothing that goes into the parsers changed, so there was nothing to test."
					verdict == "passed" && rows.isEmpty() -> "No source needed testing."
					verdict == "passed" && rows.size == 1 -> "The one tested source passed."
					verdict == "passed" -> "All ${rows.size} tested sources passed."
					else -> {
						val reasons = ArrayList<String>()
						if (notPassed.isNotEmpty()) {
							val counts = Result.entries.filter { it != Result.PASSED }
								.mapNotNull { kind -> rows.count { it.second.first == kind }.takeIf { it > 0 }?.let { "$it ${kind.title}" } }
							reasons += "${notPassed.size} of ${rows.size} tested sources did not pass (${counts.joinToString(", ")})"
						}
						if (untested > 0) {
							reasons += if (untested == 1) "1 change could not be tested" else "$untested changes could not be tested"
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
		val isCloudflare = failed.all { case ->
			val failure = checkNotNull(case.failure)
			listOf(failure.type, failure.message, failure.text).any { it.contains("CloudFlareProtectedException") }
		}
		val lines = failed.take(MAX_FAILURES_SHOWN).map { case -> "${case.testName}: ${describe(case)}" }
		val more = failed.size - lines.size
		val text = (lines + listOfNotNull("and $more more".takeIf { more > 0 })).joinToString("<br>")
		return (if (isCloudflare) Result.CLOUDFLARE else Result.FAILED) to text
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
