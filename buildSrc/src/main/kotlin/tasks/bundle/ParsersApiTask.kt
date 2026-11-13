package tasks.bundle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Keeps a written record of the contract, one file for each of its versions, and holds the code to it.
 *
 * An application already installed has the contract of the version it was made for and no other. So the record of a
 * version, once written, is never written again: whatever changes what is recorded has to come as a new version.
 */
abstract class ParsersApiTask : DefaultTask() {

	/** The compiled classes of the contract. */
	@get:Classpath
	abstract val classes: ConfigurableFileCollection

	@get:Input
	abstract val apiVersion: Property<Int>

	/** The libraries a bundle is built with, as `group:name:version`: written down along with the classes. */
	@get:Input
	abstract val libraries: ListProperty<String>

	/** `false` to compare with the record, `true` to write it. */
	@get:Input
	abstract val write: Property<Boolean>

	/** To write over the record of a version that no released application has yet. */
	@get:Input
	abstract val overwrite: Property<Boolean>

	@get:Internal
	abstract val recordsDir: DirectoryProperty

	@TaskAction
	fun run() {
		val version = apiVersion.get()
		val record = recordFile(recordsDir.get().asFile, version)
		val classesNow = describeClasses()
		if (write.get()) {
			if (record.exists() && !overwrite.get()) {
				if (classesOf(record.readText()) == classesNow) {
					logger.lifecycle("${record.name} is up to date")
					return
				}
				throw GradleException(
					"The contract of version $version is already written down in ${record.name}, and applications " +
						"may have been released with it.\nRaise ParsersApi.VERSION for the change you made. Only if " +
						"no released application has version $version yet, write it again with -PoverwriteParsersApi",
				)
			}
			record.parentFile.mkdirs()
			record.writeText(
				buildString {
					append("# The contract between an application and the parsers it downloads, version $version.\n")
					append("# Written by ./gradlew dumpParsersApi and compared by ./gradlew checkParsersApi.\n")
					append("# Not to be edited, and not to be written again once an application is released with it.\n")
					for (library in libraries.get().sorted()) {
						append(LIBRARY).append(library).append('\n')
					}
					append(classesNow)
				},
			)
			logger.lifecycle("Wrote ${record.name}")
			return
		}
		if (!record.exists()) {
			throw GradleException(
				"There is no record of the contract of version $version. Write it with ./gradlew dumpParsersApi " +
					"and commit ${record.name}",
			)
		}
		val classesThen = classesOf(record.readText())
		if (classesThen != classesNow) {
			val then = classesThen.lines().toSet()
			val now = classesNow.lines().toSet()
			val difference = (then - now).map { "- $it" } + (now - then).map { "+ $it" }
			throw GradleException(
				"What is under src/contract no longer matches ${record.name}:\n" +
					difference.take(30).joinToString("\n") +
					(if (difference.size > 30) "\n... and ${difference.size - 30} more" else "") +
					"\n\nApplications made for version $version hold these classes as they were and cannot be " +
					"given others. Either undo the change, or raise ParsersApi.VERSION and write the record of the " +
					"new version with ./gradlew dumpParsersApi",
			)
		}
	}

	private fun describeClasses(): String {
		val signatures = classes.asFileTree.files
			.filter { it.name.endsWith(".class") }
			.map { ClassSignature.read(it) }
			.filterNot { it.isNameless }
			.sortedBy { it.name }
		check(signatures.isNotEmpty()) { "No classes of the contract were found" }
		return buildString {
			// What applications keep of the libraries is as much a part of the contract as its own classes
			for (pattern in BundleSurface.LIBRARIES) {
				append("keeps ").append(pattern).append('\n')
			}
			for (signature in signatures) {
				signature.describe(this)
			}
		}
	}

	companion object {

		private const val LIBRARY = "library "

		fun recordFile(dir: File, version: Int): File = File(dir, "parsers-api-v$version.txt")

		/** The libraries applications of this version are known to have, at these versions or later ones. */
		fun librariesOf(record: File): List<String> = record.readLines()
			.filter { it.startsWith(LIBRARY) }
			.map { it.removePrefix(LIBRARY).trim() }

		private fun classesOf(text: String): String = text.lineSequence()
			.filterNot { it.startsWith("#") || it.startsWith(LIBRARY) }
			.joinToString("\n")
			.trimEnd() + "\n"
	}
}
