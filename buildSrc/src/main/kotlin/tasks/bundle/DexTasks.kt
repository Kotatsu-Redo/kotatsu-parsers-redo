package tasks.bundle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject

/**
 * Turns the compiled parsers into the form Android runs, with the tool Android applications are built with.
 */
abstract class DexBundleTask @Inject constructor(
	private val exec: ExecOperations,
) : DefaultTask() {

	/** The R8 tools, D8 among them. */
	@get:Classpath
	abstract val tools: ConfigurableFileCollection

	/** A jar of the parsers alone. */
	@get:InputFile
	@get:PathSensitive(PathSensitivity.NONE)
	abstract val classes: RegularFileProperty

	/** What the parsers are compiled against and will find in the application: looked at, never put in. */
	@get:Classpath
	abstract val libraries: ConfigurableFileCollection

	@get:InputFile
	@get:PathSensitive(PathSensitivity.NONE)
	abstract val androidJar: RegularFileProperty

	@get:Input
	abstract val minApi: Property<Int>

	@get:OutputDirectory
	abstract val outputDir: DirectoryProperty

	@TaskAction
	fun run() {
		val output = outputDir.get().asFile
		output.deleteRecursively()
		output.mkdirs()
		val arguments = ArrayList<String>()
		arguments += listOf("--release", "--min-api", minApi.get().toString())
		arguments += listOf("--lib", androidJar.get().asFile.absolutePath)
		for (library in libraries.files) {
			arguments += listOf("--classpath", library.absolutePath)
		}
		arguments += listOf("--output", output.absolutePath)
		arguments += classes.get().asFile.absolutePath
		exec.javaexec {
			classpath = tools
			mainClass.set("com.android.tools.r8.D8")
			args = arguments
		}
		val dexFiles = output.listFiles { file -> file.name.endsWith(".dex") }.orEmpty()
		if (dexFiles.isEmpty()) {
			throw GradleException("D8 produced no dex file in $output")
		}
	}
}

/**
 * Goes over everything the parsers refer to outside of themselves, and fails on anything an application is not known
 * to have, see [checkAllIsThere], [checkAllIsKept] and [checkNothingRewritten].
 *
 * Once loaded on a phone, such a reference is a crash in whichever parser makes it. Here it is a failed build.
 */
abstract class CheckBundleLinksTask @Inject constructor(
	private val exec: ExecOperations,
) : DefaultTask() {

	@get:Classpath
	abstract val tools: ConfigurableFileCollection

	@get:InputDirectory
	@get:PathSensitive(PathSensitivity.NONE)
	abstract val dexDir: DirectoryProperty

	/** A jar of the contract. */
	@get:InputFile
	@get:PathSensitive(PathSensitivity.NONE)
	abstract val contract: RegularFileProperty

	/** The libraries, at the versions applications of this version of the contract are known to have. */
	@get:Classpath
	abstract val libraries: ConfigurableFileCollection

	@get:InputFile
	@get:PathSensitive(PathSensitivity.NONE)
	abstract val androidJar: RegularFileProperty

	/** A jar of the parsers alone, the classes the dex was made from. */
	@get:InputFile
	@get:PathSensitive(PathSensitivity.NONE)
	abstract val bundleClasses: RegularFileProperty

	/** The oldest version of Android the parsers are meant to run on. */
	@get:Input
	abstract val minApi: Property<Int>

	/** The SDK's `api-versions.xml`, which says when each part of Android came. Absent if the SDK has none. */
	@get:InputFile
	@get:Optional
	@get:PathSensitive(PathSensitivity.NONE)
	abstract val apiVersions: RegularFileProperty

	@get:OutputFile
	abstract val report: RegularFileProperty

	/** Everything outside of themselves the parsers make use of, written out to be read by a person. */
	@get:OutputFile
	abstract val uses: RegularFileProperty

	/** Everything of Android the parsers make use of, written out to be read by a person. */
	@get:OutputFile
	abstract val platformUses: RegularFileProperty

	/** Every part of Android the parsers use that is newer than [minApi]. */
	@get:OutputFile
	abstract val platformReport: RegularFileProperty

	@TaskAction
	fun run() {
		val dexFiles = dexDir.get().asFile.listFiles { file -> file.name.endsWith(".dex") }.orEmpty().sorted()
		checkAllIsThere(dexFiles)
		val used = listUses(dexFiles)
		checkAllIsKept(used)
		checkNothingRewritten(used)
		BundleClasses(
			bundleClasses.get().asFile,
			contract.get().asFile,
			libraries.files,
			androidJar.get().asFile,
		).use { classes ->
			checkAllIsOurs(classes)
			checkNoBorrowedDefaults(classes)
		}
		checkPlatformLevel(dexFiles)
	}

	/**
	 * Only classes under `org.koitharu.kotatsu.parsers` are taken from what an application downloads, and those of
	 * the contract are never taken: it has them from the day it was built.
	 */
	private fun checkAllIsOurs(classes: BundleClasses) {
		val prefix = BundleSurface.CONTRACT_PACKAGE.replace('.', '/')
		val strangers = classes.bundleNames.filterNot { it.startsWith(prefix) }.sorted()
		val shadowing = classes.bundleNames.filter { it in classes.contractNames }.sorted()
		if (strangers.isNotEmpty() || shadowing.isNotEmpty()) {
			throw GradleException(
				"The parsers hold classes an application would not take from them:\n" +
					(strangers.map { "  $it (outside of org.koitharu.kotatsu.parsers)" } +
						shadowing.map { "  $it (also in the contract)" })
						.take(40).joinToString("\n") +
					(if (strangers.size + shadowing.size > 40) "\n  ... and more" else "") +
					"\n\nAn application loads from the download only what is in org.koitharu.kotatsu.parsers, and " +
					"takes any other class from itself, as it was on the day the application was built, older and " +
					"shrunk. Code using such a class would meet the old one, or none. Move it under " +
					"org.koitharu.kotatsu.parsers, in a package of the parsers' own, and never give a class the name " +
					"of one in src/contract.",
			)
		}
	}

	/**
	 * An application is built for Android 6 and up, and its shrinker turns what is a `default` or `static` method of
	 * an interface into abstract methods and helper classes. Classes made inside the application get matching
	 * methods; the downloaded ones, made for Android 8 and up, do not. A downloaded class that leans on such a
	 * method of the application's interface finds nothing to run.
	 */
	private fun checkNoBorrowedDefaults(classes: BundleClasses) {
		val problems = classes.findBorrowedDefaults().distinct()
		if (problems.isNotEmpty()) {
			throw GradleException(
				"The parsers lean on default or static methods of interfaces that an application holds:\n" +
					problems.take(40).joinToString("\n") { "  $it" } +
					(if (problems.size > 40) "\n  ... and ${problems.size - 40} more" else "") +
					"\n\nIn an application these methods are abstract, their code having moved to a helper class " +
					"that only classes made with the application know to call. Here it would be a crash where " +
					"the method is used. Declare the method in the class itself, make the interface's method " +
					"abstract (in src/contract), write the lambda as an object, and copy the static helper " +
					"into the parsers instead of calling the application's.",
			)
		}
	}

	/**
	 * Android has not always had everything that the newest SDK has. The dex is looked at rather than the classes,
	 * as the tool that makes it writes in the older methods that it can stand in for.
	 */
	private fun checkPlatformLevel(dexFiles: List<File>) {
		val xml = apiVersions.orNull?.asFile
			?: throw GradleException(
				"The Android SDK of ${androidJar.get().asFile} has no data/api-versions.xml next to its android.jar, " +
					"which is needed to tell what version of Android each part came with. Install the platform " +
					"completely, or give -PandroidJar=<an android.jar of a complete platform>.",
			)
		val platformFile = platformUses.get().asFile
		platformFile.parentFile.mkdirs()
		val arguments = ArrayList<String>()
		arguments += "--keep-rules"
		arguments += listOf("--lib", contract.get().asFile.absolutePath)
		for (file in libraries.files) {
			arguments += listOf("--lib", file.absolutePath)
		}
		arguments += listOf("--lib", androidJar.get().asFile.absolutePath)
		arguments += listOf("--target", androidJar.get().asFile.absolutePath)
		for (file in dexFiles) {
			arguments += listOf("--source", file.absolutePath)
		}
		arguments += listOf("--output", platformFile.absolutePath)
		exec.javaexec {
			classpath = tools
			mainClass.set("com.android.tools.r8.tracereferences.TraceReferences")
			args = arguments
		}
		val levels = PlatformLevels(xml)
		val limit = minApi.get()
		val used = LinkedHashMap<String, ArrayList<String>>()
		for (line in platformFile.readLines()) {
			val declaration = CLASS_RULE.find(line)
			if (declaration != null) {
				used[declaration.groupValues[1].replace('.', '/')] = ArrayList()
			} else if (line.trim().endsWith(";")) {
				used.entries.lastOrNull()?.value?.add(line.trim())
			}
		}
		// The tool also lists, with nothing in them, the classes above the ones the parsers use, as the SDK it looks
		// at has them: java.util.List has a SequencedCollection above it there, which is no use of it by the
		// parsers. A class listed on its own is therefore passed over when another one listed has it above it.
		val implied = used.keys.flatMapTo(HashSet()) { levels.ancestors(it) }
		val tooNew = ArrayList<String>()
		// Members the SDK's list does not have, usually for being declared on a class it hides
		val unlisted = ArrayList<String>()
		for ((owner, members) in used) {
			val level = levels.classLevel(owner)
			if (level != null && level > limit && (members.isNotEmpty() || owner !in implied)) {
				tooNew += "${owner.replace('/', '.')} (class, Android ${PlatformLevels.describe(level)})"
			}
			for (line in members) {
				val member = PlatformLevels.memberOf(line) ?: continue
				val memberLevel = levels.memberLevel(owner, member)
				if (memberLevel == null) {
					unlisted += "${owner.replace('/', '.')}: $line"
				} else if (memberLevel > limit) {
					tooNew += "${owner.replace('/', '.')}: $line (Android ${PlatformLevels.describe(memberLevel)})"
				}
			}
		}
		val reportFile = platformReport.get().asFile
		reportFile.parentFile.mkdirs()
		reportFile.writeText(
			(if (tooNew.isEmpty()) "Everything of Android is there.\n" else tooNew.joinToString("\n", postfix = "\n")) +
				if (unlisted.isEmpty()) {
					""
				} else {
					"\nNot in the SDK's list of versions, and taken on trust:\n" + unlisted.joinToString("\n", postfix = "\n")
				},
		)
		if (tooNew.isNotEmpty()) {
			throw GradleException(
				"The parsers use what Android has only from a version newer than $limit:\n" +
					tooNew.take(40).joinToString("\n") { "  $it" } +
					(if (tooNew.size > 40) "\n  ... and ${tooNew.size - 40} more, see $reportFile" else "") +
					"\n\nOn a phone with Android $limit this is a crash where it is used. Use something Android " +
					"$limit has: for a method of the JDK, look for one that is older, or write the few lines " +
					"in the parsers. Some members of a class are newer than the class itself.",
			)
		}
	}

	/**
	 * A method of a library newer than the applications have, a class of the contract that was not there when they
	 * were made, something of the JDK or of `org.json` that Android lacks.
	 */
	private fun checkAllIsThere(dexFiles: List<File>) {
		val arguments = ArrayList<String>()
		arguments += "--check"
		arguments += listOf("--lib", androidJar.get().asFile.absolutePath)
		arguments += listOf("--target", contract.get().asFile.absolutePath)
		for (file in libraries.files) {
			arguments += listOf("--target", file.absolutePath)
		}
		for (file in dexFiles) {
			arguments += listOf("--source", file.absolutePath)
		}
		val output = ByteArrayOutputStream()
		val result = exec.javaexec {
			classpath = tools
			mainClass.set("com.android.tools.r8.tracereferences.TraceReferences")
			args = arguments
			standardOutput = output
			errorOutput = output
			isIgnoreExitValue = true
		}
		val text = output.toString(Charsets.UTF_8)
		val missing = text.lineSequence()
			.map { it.removePrefix("Error: ").trim() }
			.filter { it.startsWith("Missing ") }
			.distinct()
			.toList()
		val reportFile = report.get().asFile
		reportFile.parentFile.mkdirs()
		if (result.exitValue == 0 && missing.isEmpty()) {
			reportFile.writeText("Everything the parsers refer to is there.\n")
			return
		}
		reportFile.writeText(text)
		if (missing.isEmpty()) {
			throw GradleException("The check of the parsers' references failed, see $reportFile")
		}
		throw GradleException(
			"The parsers refer to what applications do not have:\n" +
				missing.take(40).joinToString("\n") { "  $it" } +
				(if (missing.size > 40) "\n  ... and ${missing.size - 40} more, see $reportFile" else "") +
				"\n\nAn application loading these parsers would crash where they are used. If it is something of " +
				"the JDK or of org.json, Android does not have it: use something else. If it is something new in " +
				"the contract or in a library, it needs a new ParsersApi.VERSION and a new release of the application.",
		)
	}

	/** @return every class outside of the parsers that they use, with the members of it they use */
	private fun listUses(dexFiles: List<File>): Map<String, List<String>> {
		val usesFile = uses.get().asFile
		usesFile.parentFile.mkdirs()
		val arguments = ArrayList<String>()
		arguments += "--keep-rules"
		arguments += listOf("--lib", androidJar.get().asFile.absolutePath)
		arguments += listOf("--target", contract.get().asFile.absolutePath)
		for (file in libraries.files) {
			arguments += listOf("--target", file.absolutePath)
		}
		for (file in dexFiles) {
			arguments += listOf("--source", file.absolutePath)
		}
		arguments += listOf("--output", usesFile.absolutePath)
		exec.javaexec {
			classpath = tools
			mainClass.set("com.android.tools.r8.tracereferences.TraceReferences")
			args = arguments
		}
		val result = LinkedHashMap<String, ArrayList<String>>()
		var members: ArrayList<String>? = null
		for (line in usesFile.readLines()) {
			val declaration = CLASS_RULE.find(line)
			if (declaration != null) {
				members = result.getOrPut(declaration.groupValues[1]) { ArrayList() }
			} else if (line.trim().endsWith(";")) {
				members?.add(line.trim())
			}
		}
		return result
	}

	/**
	 * The shrinker of an application removes what the application does not use itself, except for what the rules
	 * that come with this library tell it to leave whole, see [BundleSurface]. Whatever of a library the parsers use
	 * has to be inside of that.
	 */
	private fun checkAllIsKept(used: Map<String, List<String>>) {
		val outside = used.keys.filterNot {
			// What is left out on purpose is only ever named by an annotation, and one that names a class that is
			// not there is passed over when the class carrying it is loaded
			it.startsWith(BundleSurface.CONTRACT_PACKAGE) || BundleSurface.keeps(it) || it in BundleSurface.EXCLUDED
		}
		if (outside.isNotEmpty()) {
			throw GradleException(
				"The parsers use classes that applications do not keep for them:\n" +
					outside.joinToString("\n") { "  $it" } +
					"\n\nAn application may have removed or reshaped these, and would crash where the parsers use " +
					"them. They are internal to their library more often than not: use something else. Adding to " +
					"what applications keep, in BundleSurface, needs a new ParsersApi.VERSION and a new release of " +
					"the application.",
			)
		}
	}

	/**
	 * An application for old versions of Android is built with the newer parts of the JDK rewritten to a copy it
	 * carries along, and its classes of the contract and of the libraries are rewritten with it: a method giving a
	 * `java.time.Instant` gives a `j$.time.Instant` there. The parsers are not rewritten, being for versions of
	 * Android that have these parts, so to them such a method is not there at all.
	 */
	private fun checkNothingRewritten(used: Map<String, List<String>>) {
		val rewritten = used.flatMap { (owner, members) ->
			members.filter { member -> BundleSurface.REWRITTEN.any { it in member } }.map { "$owner: $it" }
		}
		if (rewritten.isNotEmpty()) {
			throw GradleException(
				"The parsers use members that an application built for old versions of Android holds under " +
					"another signature:\n" + rewritten.joinToString("\n") { "  $it" } +
					"\n\nAn application loading these parsers would crash where they are used. Use a member " +
					"without java.time, java.util.function or java.util.stream in what it takes and gives.",
			)
		}
	}

	private companion object {

		val CLASS_RULE = Regex("""^-keep(?:,\w+)* (?:class|interface|enum|@interface) ([\w.$]+)""")
	}
}

/**
 * What an application keeps whole for the parsers it will download, of everything that is not the parsers' own: the
 * one definition both of the rules handed to its shrinker and of what the parsers are checked against.
 *
 * It is part of the contract. An application already released keeps what was listed here on the day it was built.
 */
internal object BundleSurface {

	const val CONTRACT_PACKAGE = "org.koitharu.kotatsu.parsers."

	/**
	 * The libraries, as patterns of class names: `*` stands for any name within a package, `**` for anything below.
	 *
	 * OkHttp and Okio are kept as far as they are meant to be used from outside, their internals changing from one
	 * version to the next. The two exceptions are helpers parsers have long been using anyway.
	 */
	val LIBRARIES = listOf(
		"kotlin.**",
		"kotlinx.coroutines.**",
		"okhttp3.*",
		"okhttp3.internal._Util*",
		"okio.*",
		"org.jsoup.**",
		"androidx.collection.*",
	)

	/**
	 * Not kept, whatever the patterns say. The descriptions the Kotlin compiler leaves on every class are megabytes,
	 * are of no use to code already compiled, and stay on every kept class as soon as this one is kept.
	 */
	val EXCLUDED = listOf("kotlin.Metadata")

	/** What core library desugaring moves to `j$`. */
	val REWRITTEN = listOf(
		"java.time.",
		"java.util.function.",
		"java.util.stream.",
		"java.util.Optional",
		"java.util.Spliterator",
		"java.util.PrimitiveIterator",
		"java.util.DoubleSummaryStatistics",
		"java.util.IntSummaryStatistics",
		"java.util.LongSummaryStatistics",
		"java.util.concurrent.ConcurrentHashMap",
		"java.util.concurrent.Flow",
		"java.nio.file.",
	)

	private val matchers = LIBRARIES.map { pattern ->
		Regex(
			pattern.split("**").joinToString(".*") { part ->
				part.split("*").joinToString("[^.]*") { Regex.escape(it) }
			},
		)
	}

	fun keeps(className: String): Boolean = className !in EXCLUDED && matchers.any { it.matches(className) }

	/** The rules a shrinker is given for the libraries. */
	fun keepRules(): List<String> = LIBRARIES.map { pattern ->
		val excluded = EXCLUDED.filter { it.startsWith(pattern.substringBefore('*')) }.joinToString("") { "!$it," }
		"-keep class $excluded$pattern { public protected *; }"
	}
}
