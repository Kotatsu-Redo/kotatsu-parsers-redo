package tasks.tests

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.util.Random
import java.util.TreeSet

private const val SITE_DIR = "src/main/kotlin/org/koitharu/kotatsu/parsers/site/"
private const val LIST_LIMIT = 40
private const val TRIGGER_LIMIT = 5
private val COMMIT_NAME = Regex("[0-9A-Za-z._/~^-]{1,200}")
private val DECLARED_SOURCE = Regex("""@MangaSourceParser\(\s*"([A-Z0-9_]+)"""")
private val BROKEN_ANNOTATION = Regex("""^@Broken(?:$|[(\s])""")

/**
 * Decides which sources the live tests are run for: the ones a pull request changes ([comparedWith]) or, when that
 * is not given, the ones whose code is not the code of the last publication. See docs/PULL_REQUESTS.md. Publishing
 * itself tests nothing against the sites.
 *
 * The sites are slow and some of them block automated visitors, so every source is not tested every time. A source
 * that changed itself is always tested. Those that only build on changed code (a base class, a helper, the core) are
 * tested by a sample: if the shared code is broken, a few of them show it as well as all of them would.
 *
 * What goes into the parsers and is not vouched for by any tested source is counted as untested, and a person is asked
 * to look at it: it must never be merged on the strength of tests that did not touch it.
 *
 * A source that is removed is gone from the code that is built, so it cannot be tested there. It is told apart by what
 * it was in the base commit: a source marked broken is let go, and one that was not is named in removed.txt, to be
 * tested as the base commit has it. That goes for a source taken out of a file that stays as well. A source that only
 * moved to another file is not a removal.
 *
 * The tests vouch for sources, with their base classes and helpers, and for nothing else: see [isFreeToChange]. A
 * change anywhere else is named in protected.txt, and a person has to read it whatever the results.
 */
abstract class SelectParserTestsTask : DefaultTask() {

	/** A commit to compare with, which can only reach further back than the last publication. */
	@get:Input
	@get:Optional
	abstract val since: Property<String>

	/**
	 * A commit to compare with and nothing else: no publication is looked for. For a pull request, which is tested
	 * for what it changes in the branch it is to be merged into, whatever was published and when.
	 */
	@get:Input
	@get:Optional
	abstract val comparedWith: Property<String>

	/** The directory holding the manifests of publications: the manifest is `parsers-v<apiVersion>.json` in it. */
	@get:Input
	abstract val bundleUrl: Property<String>

	@get:Input
	abstract val apiVersion: Property<Int>

	/** The key manifests are signed with, as applications hold it. When it is given, the manifest has to verify. */
	@get:Input
	@get:Optional
	abstract val publicKey: Property<String>

	/** Where git is run: the directory of the checkout. */
	@get:Input
	abstract val gitDir: Property<String>

	/** The project, where the files of the sources map are looked for. */
	@get:Input
	abstract val projectPath: Property<String>

	/** How many sources the sample for everything holds. */
	@get:Input
	abstract val maxSampledSources: Property<Int>

	/** How many sources the sample for each shared file holds. */
	@get:Input
	abstract val maxSampledPerFile: Property<Int>

	/** What the KSP processor wrote: one line to a source, see ParserProcessor. */
	@get:Internal
	abstract val sourcesMap: RegularFileProperty

	/**
	 * Not declared as an output: the sources map written by KSP lives in it too, and Gradle would delete a file it did
	 * not see a task write before the first run of this one.
	 */
	@get:Internal
	abstract val outputDir: DirectoryProperty

	init {
		// Depends on git and on the network, which Gradle does not look at
		outputs.upToDateWhen { false }
	}

	private class SourceEntry(
		val name: String,
		val isBroken: Boolean,
		val ownFile: String,
		val superFiles: Set<String>,
	)

	/** The sources tested because of one shared file: some of those it is a base or a helper of. */
	private class SharedGroup(val file: String, val total: Int, val sampled: List<String>, val kind: String)

	private class Selection(
		val own: List<String>,
		val groups: List<SharedGroup>,
		/** Files that put every source in question, and the sample of all sources taken for them. */
		val everythingTriggers: List<String>,
		val everythingTotal: Int,
		val everythingSampled: List<String>,
		/** Files nothing tested vouches for. */
		val untestedChanged: List<String>,
		val untestedDeleted: List<String>,
		val broken: List<String>,
		/** Sources that are gone and were marked broken, and those that are gone and were not. */
		val removedBroken: List<String>,
		val removedLive: List<String>,
	) {
		val tested: List<String> = (own + groups.flatMap { it.sampled } + everythingSampled).distinct().sorted()
		val untested: Int = untestedChanged.size + untestedDeleted.size
	}

	@TaskAction
	fun run() {
		val sources = readSources(checkedMap())
		val directory = File(gitDir.get())
		val head = git(directory, "rev-parse", "--verify", "HEAD^{commit}")
			?: throw GradleException("HEAD is not a commit of the repository in $directory")
		val (base, origin) = chooseBase(directory)

		val changed = diff(directory, base, "d")
		val deleted = diff(directory, base, "D")
		val relevant = changed.filter(::isRelevant)
		val relevantDeleted = deleted.filter(::isRelevant)
		val nothingChanged = relevant.isEmpty() && relevantDeleted.isEmpty()
		val protectedFiles = (changed + deleted).filterNot(::isFreeToChange).distinct().sorted()

		val selection = if (nothingChanged) null else select(sources, relevant, relevantDeleted, head, directory, base)
		val plan = buildString {
			appendLine("## Plan")
			appendLine()
			if (protectedFiles.isNotEmpty()) {
				appendProtected(protectedFiles)
			}
			if (selection == null) {
				appendLine("Nothing that goes into the parsers changed since `$base`, which is $origin.")
				appendLine("There is no source to test.")
			} else {
				appendPlan(base, origin, relevant.size + relevantDeleted.size, selection)
			}
		}
		val out = outputDir.get().asFile
		out.mkdirs()
		val tested = selection?.tested.orEmpty()
		File(out, "sources.txt").writeText(tested.joinToString(","))
		File(out, "plan.md").writeText(plan)
		File(out, "removed.txt").writeText(selection?.removedLive.orEmpty().joinToString(","))
		File(out, "protected.txt").writeText(protectedFiles.joinToString("\n"))
		File(out, "selection.properties").writeText(
			"base=$base\nnothingChanged=$nothingChanged\nuntested=${selection?.untested ?: 0}\n" +
				"protected=${protectedFiles.size}\n",
		)
		logger.lifecycle(
			"Sources to test: ${tested.size}, removed sources to test as they were: ${selection?.removedLive?.size ?: 0}, " +
				"untested changes: ${selection?.untested ?: 0}, changes outside the sources: ${protectedFiles.size} " +
				"(compared with $base)",
		)
	}

	/** The sources map, if it can be trusted: a map that is empty or points at files that are not there is stale. */
	private fun checkedMap(): File {
		val mapFile = sourcesMap.get().asFile
		if (!mapFile.isFile) {
			throw GradleException("$mapFile was not written by KSP: run again with --rerun-tasks")
		}
		val sources = readSources(mapFile)
		val project = File(projectPath.get())
		val missing = sources.filter { it.ownFile.isEmpty() || !File(project, it.ownFile).isFile }
		if (sources.isEmpty() || missing.isNotEmpty()) {
			throw GradleException(
				"The map of sources ($mapFile) looks stale or truncated: " +
					(if (sources.isEmpty()) "it has no sources" else "${missing.size} of its files are not in $project, " +
						"the first being ${missing.first().ownFile}") +
					". Run again with --rerun-tasks",
			)
		}
		return mapFile
	}

	/**
	 * What to compare with, and where that came from. The next publication contains everything since the last one, so
	 * `since` is only allowed to reach back further than it.
	 */
	private fun chooseBase(directory: File): Pair<String, String> {
		val sinceValue = since.orNull?.takeIf { it.isNotBlank() }
		if (sinceValue != null && (sinceValue.startsWith("-") || !COMMIT_NAME.matches(sinceValue))) {
			// It is typed by hand and handed to git: a name of a commit, and never something git reads as an option
			throw GradleException("`$sinceValue` does not look like a commit: give `since` a commit hash, a tag or a branch")
		}
		fun resolve(name: String, what: String) = git(directory, "rev-parse", "--verify", "$name^{commit}")
			?: throw GradleException(
				"$what ($name) is not a commit of the repository in $directory. Either it is from another repository " +
					"or the checkout lacks history: check out with the full history (fetch-depth: 0) and run again.",
			)
		val comparedValue = comparedWith.orNull?.takeIf { it.isNotBlank() }
		if (comparedValue != null) {
			if (sinceValue != null) {
				throw GradleException("`since` and `comparedWith` were both given: it is one or the other")
			}
			if (comparedValue.startsWith("-") || !COMMIT_NAME.matches(comparedValue)) {
				throw GradleException("`$comparedValue` does not look like a commit: give `comparedWith` a commit")
			}
			return resolve(comparedValue, "`comparedWith`") to "what was asked for with `comparedWith`"
		}
		val published = fetchPublication(bundleUrl.get(), apiVersion.get(), publicKey.orNull)
		if (published == null) {
			if (sinceValue == null) {
				throw GradleException(
					"There is no manifest at ${bundleUrl.get().trimEnd('/')}/parsers-v${apiVersion.get()}.json: this " +
						"looks like the first publication of version ${apiVersion.get()}, with nothing to compare " +
						"with. Run again with -Psince=<the commit the parsers inside the released app were built from>",
				)
			}
			return resolve(sinceValue, "`since`") to "given with `since`, as nothing has been published yet"
		}
		val last = git(directory, "rev-parse", "--verify", "${published.revision}^{commit}")
			?: throw GradleException(
				"The published revision ${published.revision} is not in this checkout, so it cannot be compared " +
					"with: check out with the full history (fetch-depth: 0) and run again.",
			)
		if (sinceValue == null) {
			return last to "the last publication (parsers-v${apiVersion.get()}.json)"
		}
		val given = resolve(sinceValue, "`since`")
		if (git(directory, "merge-base", "--is-ancestor", given, last) == null) {
			throw GradleException(
				"`since` ($sinceValue) is newer than the last publication, revision $last. What is published next " +
					"contains everything since that publication, so `since` can only reach further back than it. " +
					"Leave `since` out to compare with the last publication.",
			)
		}
		return given to "given with `since`, which is not after the last publication ($last)"
	}

	/** The files of the diff with the filter given (`d` for all but deleted, `D` for deleted only). */
	private fun diff(directory: File, base: String, filter: String): List<String> {
		return git(
			directory, "-c", "core.quotepath=false", "diff", "--name-only", "--relative", "-z", "--no-renames",
			"--diff-filter=$filter", base, "HEAD",
		)?.split('\u0000')?.filter { it.isNotEmpty() }
			?: throw GradleException("git could not tell what changed between $base and HEAD")
	}

	/**
	 * Whether a pull request may change the file and still be vouched for by the tests: the Kotlin of a source, a base
	 * class or a helper of sources, the documentation, which runs nothing, and .github/summary.yaml, which every build
	 * writes again.
	 *
	 * Everything else is either the check itself (the tests, the build, the workflows), so that the results come from
	 * the pull request's own version of it, or code that every source runs through (core, util, network, the contract).
	 * The check is for fixing sources and taking broken ones out, and cannot speak for either. Any other file in the
	 * site directory is no source either (a .gitattributes there could hide a parser's diff on GitHub).
	 *
	 * The pull request check fails as soon as this task has written protected.txt, before the sources of the pull
	 * request run in the tests: the tests run them, and they could rewrite what the summary reads. .github/CODEOWNERS
	 * draws the same line for GitHub, which reads it from master, out of reach of a pull request that changes this.
	 */
	private fun isFreeToChange(path: String): Boolean {
		return (path.startsWith(SITE_DIR) && path.endsWith(".kt")) || path.startsWith("docs/") ||
			path == "README.md" || path == "CONTRIBUTING.md" || path == ".github/summary.yaml"
	}

	private fun isRelevant(path: String): Boolean {
		return path.startsWith("src/main/") || path.startsWith("src/contract/") ||
			path.startsWith("kotatsu-parsers-ksp/") || path.startsWith("buildSrc/") || path.startsWith("gradle/") ||
			path == "build.gradle.kts" || path == "settings.gradle.kts" || path == "gradle.properties"
	}

	private fun select(
		sources: List<SourceEntry>,
		changed: List<String>,
		deleted: List<String>,
		head: String,
		directory: File,
		base: String,
	): Selection {
		val changedSet = changed.toSet()
		val ownFiles = sources.mapTo(HashSet()) { it.ownFile }
		val superFiles = sources.flatMapTo(HashSet()) { it.superFiles }
		val headSeed = head.take(15).toLong(16)
		val perFile = maxSampledPerFile.get().coerceAtLeast(0)
		val (brokenSources, liveSources) = sources.partition { it.isBroken }

		val ownTested = liveSources.filter { it.ownFile in changedSet }
		val ownNames = ownTested.mapTo(HashSet()) { it.name }
		val others = liveSources.filter { it.name !in ownNames }
		val covered = HashSet<String>()
		val groups = ArrayList<SharedGroup>()
		val triggers = ArrayList<String>()

		fun sample(names: List<String>, limit: Int, seed: Long) =
			names.sorted().shuffled(Random(seed)).take(limit).sorted()

		for (file in changed.sorted()) {
			if (liveSources.any { it.ownFile == file }) {
				covered += file
			}
			if (!file.startsWith(SITE_DIR)) {
				// Core, util, network, contract, the KSP module, the build: every source is built on it
				triggers += file
			}
			if (ownTested.any { file in it.superFiles }) {
				// Sources that changed themselves and build on it are tested
				covered += file
			}
			if (file in superFiles) {
				val group = others.filter { file in it.superFiles }.map { it.name }
				if (group.isNotEmpty()) {
					val sampled = sample(group, perFile, headSeed xor file.hashCode().toLong())
					groups += SharedGroup(file, group.size, sampled, "a base of")
					if (sampled.isNotEmpty()) covered += file
				}
			} else if (file.startsWith(SITE_DIR) && file !in ownFiles) {
				// Code in the site directory that no source is made of, and that sources may use: a helper
				val dir = file.substringBeforeLast('/', "") + "/"
				val near = liveSources.filter { source ->
					source.ownFile.startsWith(dir) || source.superFiles.any { it.startsWith(dir) }
				}
				if (near.isEmpty()) {
					// Nothing says who uses it, so anyone may
					triggers += file
				} else {
					if (near.any { it.name in ownNames }) {
						// The usual case: a parser changed along with the helper next to it, and is tested
						covered += file
					}
					val group = near.filter { it.name !in ownNames }.map { it.name }
					if (group.isNotEmpty()) {
						val sampled = sample(group, perFile, headSeed xor file.hashCode().toLong())
						groups += SharedGroup(file, group.size, sampled, "a helper of")
						if (sampled.isNotEmpty()) covered += file
					}
				}
			}
		}
		// A deleted file outside the sites may have been used by anything, and one inside them is gone with its tests
		triggers += deleted.filter { !it.startsWith(SITE_DIR) }

		val everythingNames = others.map { it.name }
		val everythingSampled = if (triggers.isEmpty()) {
			emptyList()
		} else {
			sample(everythingNames, maxSampledSources.get().coerceAtLeast(0), headSeed)
		}
		if (everythingSampled.isNotEmpty()) {
			covered += triggers
		}
		val untestedChanged = changed.filter { it !in covered }.sorted()
		val removedBroken = TreeSet<String>()
		val removedLive = TreeSet<String>()
		val accountedFor = HashSet<String>()
		val current = sources.associateBy { it.name }
		val deletedSet = deleted.toSet()
		// What each file declared in the base commit. The files that stayed are gone through too: a source can be taken
		// out of a file that holds several
		for (file in deleted + changed) {
			if (!file.startsWith(SITE_DIR) || !file.endsWith(".kt")) continue
			// A file that was added has nothing there
			val text = git(directory, "show", "$base:./$file") ?: continue
			val declared = declaredSources(text)
			if (declared.isEmpty()) continue
			var isAccountedFor = true
			for ((name, wasBroken) in declared) {
				val now = current[name]
				if (now == null) {
					(if (wasBroken) removedBroken else removedLive) += name
				} else if (file in deletedSet && now.isBroken && !wasBroken) {
					// Moved and marked broken in one go, which no test here speaks for: as any deleted file was before
					isAccountedFor = false
				}
				// Otherwise it is still a source of the code being built, and is tested where it is
			}
			if (isAccountedFor) {
				accountedFor += file
			}
		}
		val untestedDeleted = deleted.filter {
			if (it.startsWith(SITE_DIR)) it !in accountedFor else it !in covered
		}.sorted()
		return Selection(
			own = ownTested.map { it.name }.sorted(),
			groups = groups,
			everythingTriggers = triggers.distinct().sorted(),
			everythingTotal = if (triggers.isEmpty()) 0 else everythingNames.size,
			everythingSampled = everythingSampled,
			untestedChanged = untestedChanged,
			untestedDeleted = untestedDeleted,
			// Only the ones that changed themselves are worth naming: a changed base class has broken sources by the
			// hundred among those that build on it, and a list of them buries what the plan is read for
			broken = brokenSources.filter { it.ownFile in changedSet }.map { it.name }.sorted(),
			removedBroken = removedBroken.toList(),
			removedLive = removedLive.toList(),
		)
	}

	/**
	 * The sources a file declares, with whether each is marked broken. Only the annotations standing together with
	 * the one that declares it are looked at, with nothing between them but empty lines and line comments, and when
	 * in doubt it is not broken: then it is tested.
	 */
	private fun declaredSources(text: String): List<Pair<String, Boolean>> {
		val lines = text.split('\n').map { it.trim() }
		fun standsWithAnnotations(line: String) = line.startsWith("@") || line.isEmpty() || line.startsWith("//")
		return DECLARED_SOURCE.findAll(text).map { match ->
			val line = text.substring(0, match.range.first).count { it == '\n' }
			var first = line
			while (first > 0 && standsWithAnnotations(lines[first - 1])) first--
			var last = line
			while (last < lines.size - 1 && standsWithAnnotations(lines[last + 1])) last++
			match.groupValues[1] to (first..last).any { BROKEN_ANNOTATION.containsMatchIn(lines[it]) }
		}.distinct().toList()
	}

	private fun StringBuilder.appendProtected(files: List<String>) {
		val one = files.size == 1
		appendLine(
			"**${files.size} ${if (one) "file" else "files"} outside the parser sources changed.** " +
				"The tests vouch for the Kotlin of sources, their base classes and their helpers, and for nothing " +
				"else. The rest is " +
				"the check itself (the tests, the build, the workflows), whose results below then come from the pull " +
				"request's own version of it, or code that every source runs through. A person has to read " +
				"${if (one) "it" else "them"} before this is merged, whatever the results say:",
		)
		appendLine()
		appendNames(files.map { "`$it`" })
		appendLine()
	}

	private fun StringBuilder.appendPlan(base: String, origin: String, fileCount: Int, selection: Selection) {
		appendLine("The code was compared with `$base`, which is $origin.")
		appendLine("Since then, $fileCount ${if (fileCount == 1) "file" else "files"} changed in the code that goes into the parsers.")
		appendLine()
		if (selection.own.isNotEmpty()) {
			val one = selection.own.size == 1
			appendLine(
				"**${selection.own.size} ${if (one) "source" else "sources"} changed ${if (one) "itself" else "themselves"}**, " +
					"and ${if (one) "it is" else "all are"} tested:",
			)
			appendLine()
			appendNames(selection.own)
			appendLine()
		}
		for (group in selection.groups) {
			val total = group.total
			val sampledCount = group.sampled.size
			appendLine(
				"`${group.file}` is ${group.kind} $total other ${if (total == 1) "source" else "sources"}, and " +
					"${if (sampledCount == total) "all of them are" else "$sampledCount of them are"} tested:",
			)
			appendLine()
			appendNames(group.sampled)
			appendLine()
		}
		if (selection.everythingTriggers.isNotEmpty()) {
			val shown = selection.everythingTriggers.take(TRIGGER_LIMIT).joinToString(", ") { "`$it`" }
			val more = selection.everythingTriggers.size - TRIGGER_LIMIT
			appendLine(
				"Every source is built on ${if (selection.everythingTriggers.size == 1) "this file" else "these files"} " +
					"($shown${if (more > 0) " and $more more" else ""}), so ${selection.everythingSampled.size} of " +
					"${selection.everythingTotal} other sources are tested as a sample:",
			)
			appendLine()
			appendNames(selection.everythingSampled)
			appendLine()
		}
		if (selection.untested > 0) {
			appendLine("**${selection.untested} ${if (selection.untested == 1) "change" else "changes"} could not be tested**, because no source that can be tested is built on ${if (selection.untested == 1) "it" else "them"}:")
			appendLine()
			val items = selection.untestedChanged.map { "`$it` (changed)" } + selection.untestedDeleted.map { "`$it` (deleted)" }
			appendNames(items)
			appendLine()
		}
		if (selection.removedBroken.isNotEmpty()) {
			val one = selection.removedBroken.size == 1
			appendLine(
				"**${selection.removedBroken.size} ${if (one) "source is" else "sources are"} removed that " +
					"${if (one) "was" else "were"} already marked broken**, so nothing that worked is lost:",
			)
			appendLine()
			appendNames(selection.removedBroken)
			appendLine()
		}
		if (selection.removedLive.isNotEmpty()) {
			val one = selection.removedLive.size == 1
			appendLine(
				"**${selection.removedLive.size} ${if (one) "source is" else "sources are"} removed that " +
					"${if (one) "is" else "are"} not marked broken.** ${if (one) "It is" else "Each is"} tested as `$base` has it: " +
					"the removal is vouched for only if its site turns out to be gone.",
			)
			appendLine()
			appendNames(selection.removedLive)
			appendLine()
		}
		if (selection.broken.isNotEmpty()) {
			appendLine("**Changed, and left out for being marked broken:**")
			appendLine()
			appendNames(selection.broken)
			appendLine()
		}
		if (selection.tested.isEmpty() && selection.untested > 0 && selection.removedLive.isEmpty()) {
			appendLine("No source could be tested.")
		}
	}

	private fun StringBuilder.appendNames(names: List<String>) {
		for (name in names.take(LIST_LIMIT)) {
			appendLine("- $name")
		}
		if (names.size > LIST_LIMIT) {
			appendLine("- and ${names.size - LIST_LIMIT} more")
		}
	}

	private fun readSources(file: File): List<SourceEntry> {
		return file.readLines().filter { it.isNotBlank() }.map { line ->
			val parts = line.split('\t')
			SourceEntry(
				name = parts[0],
				isBroken = parts.getOrNull(1) == "1",
				ownFile = parts.getOrElse(2) { "" },
				superFiles = parts.getOrElse(3) { "" }.split(';').filter { it.isNotEmpty() }.toSet(),
			)
		}
	}

	/** The git output without its last newline, or null if git failed. */
	private fun git(directory: File, vararg args: String): String? {
		val errors = File.createTempFile("git", ".err")
		try {
			val process = ProcessBuilder("git", *args)
				.directory(directory)
				.redirectError(errors)
				.start()
			val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
			return if (process.waitFor() == 0) output.trimEnd('\n', '\r') else null
		} catch (e: java.io.IOException) {
			throw GradleException("git could not be run in $directory: $e", e)
		} finally {
			errors.delete()
		}
	}
}
