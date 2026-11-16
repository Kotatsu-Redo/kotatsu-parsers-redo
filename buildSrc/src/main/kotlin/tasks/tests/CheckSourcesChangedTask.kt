package tasks.tests

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * The code of the sources: what a publication is made for. The same as what a pull request may change and still be
 * vouched for by its tests (SelectParserTestsTask.isFreeToChange), less the documentation.
 */
private val SOURCE_PATHS = arrayOf(
	":(glob)src/main/kotlin/org/koitharu/kotatsu/parsers/site/**/*.kt",
	":(glob)src/main/java/org/koitharu/kotatsu/parsers/site/**/*.java",
)
private val REVISION = Regex("[0-9a-f]{7,40}")

/**
 * Tells whether the code of the sources changed since the last publication, and writes `true` or `false` to
 * [outputFile]. A publication that leaves the sources as they were, after a commit to the build, the tests or the core
 * alone, would only have every app download the same parsers again, so it is not made. What else changed goes out
 * with the next publication, which is built from all of master.
 *
 * Nothing published yet counts as a change. So does a published revision that cannot be found in the history, which
 * was rewritten since, say: then there is nothing to compare with, and publishing is how it was before this check. A
 * shallow checkout is refused, as every revision would be missing from it.
 */
abstract class CheckSourcesChangedTask : DefaultTask() {

	/** The directory holding the manifests of publications. */
	@get:Input
	abstract val bundleUrl: Property<String>

	@get:Input
	abstract val apiVersion: Property<Int>

	@get:Input
	@get:Optional
	abstract val publicKey: Property<String>

	/** Where git is run: the directory of the checkout, whose HEAD is what would be published. */
	@get:Input
	abstract val gitDir: Property<String>

	@get:OutputFile
	abstract val outputFile: RegularFileProperty

	init {
		// Depends on git and on the network, which Gradle does not look at
		outputs.upToDateWhen { false }
	}

	@TaskAction
	fun run() {
		val changed = hasChanged(File(gitDir.get()))
		outputFile.get().asFile.writeText(changed.toString())
	}

	private fun hasChanged(directory: File): Boolean {
		val published = fetchPublication(bundleUrl.get(), apiVersion.get(), publicKey.orNull)
		if (published == null) {
			logger.lifecycle("Nothing is published yet, so the sources count as changed.")
			return true
		}
		val revision = published.revision
		if (git(directory, "rev-parse", "--is-shallow-repository").second != "false") {
			throw GradleException(
				"The checkout in $directory is shallow, or not a git repository, so the published revision $revision " +
					"cannot be looked for in it: check out with the full history (fetch-depth: 0).",
			)
		}
		val commit = if (REVISION.matches(revision)) {
			git(directory, "rev-parse", "--verify", "--quiet", "$revision^{commit}").takeIf { it.first == 0 }?.second
		} else {
			null
		}
		if (commit == null) {
			logger.warn(
				"The published revision $revision is not a commit of this repository's history, so there is nothing to " +
					"compare the sources with: they count as changed.",
			)
			return true
		}
		return when (git(directory, "diff", "--quiet", commit, "HEAD", "--", *SOURCE_PATHS).first) {
			0 -> {
				logger.lifecycle("The code of the sources is the same as in the published revision $revision.")
				false
			}

			1 -> {
				logger.lifecycle("The code of the sources changed since the published revision $revision.")
				true
			}

			else -> throw GradleException("git could not compare the sources of $commit and HEAD in $directory")
		}
	}

	/** The exit code of git, and its output without the last newline. */
	private fun git(directory: File, vararg args: String): Pair<Int, String> {
		try {
			val process = ProcessBuilder("git", *args)
				.directory(directory)
				.redirectError(ProcessBuilder.Redirect.INHERIT)
				.start()
			val output = process.inputStream.readBytes().toString(Charsets.UTF_8).trimEnd('\n', '\r')
			return process.waitFor() to output
		} catch (e: java.io.IOException) {
			throw GradleException("git could not be run in $directory: $e", e)
		}
	}
}
