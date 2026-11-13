package tasks.bundle

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Writes the rules that go with the library into an application, telling its shrinker what to leave whole.
 *
 * A shrinker removes and reshapes whatever the application itself makes no use of. Parsers downloaded later are not
 * there for it to see, and they use the contract and the libraries as they were when compiled: every class of the
 * contract, and what [BundleSurface] lists of the libraries, has to still be there, under its own name, when they
 * arrive.
 */
abstract class BundleKeepRulesTask : DefaultTask() {

	/** The compiled classes of the contract. */
	@get:Classpath
	abstract val classes: ConfigurableFileCollection

	@get:OutputDirectory
	abstract val outputDir: DirectoryProperty

	@TaskAction
	fun run() {
		val names = ArrayList<String>()
		for (root in classes.files) {
			if (!root.isDirectory) continue
			root.walkTopDown()
				.filter { it.isFile && it.name.endsWith(".class") }
				.mapTo(names) { it.relativeTo(root).path.removeSuffix(".class").replace(File.separatorChar, '.') }
		}
		names.sort()
		check(names.isNotEmpty()) { "No classes of the contract were found" }
		val file = File(outputDir.get().asFile, "META-INF/proguard/kotatsu-parsers-bundle.pro")
		file.parentFile.mkdirs()
		file.writeText(
			buildString {
				append("# Written by the build of kotatsu-parsers. Parsers an application downloads after it is built\n")
				append("# use these as they were compiled against them, so none may be removed, renamed or reshaped.\n")
				append("\n# The contract\n")
				for (name in names) {
					append("-keep class ").append(name).append(" { *; }\n")
				}
				append("\n# The libraries the parsers are built with, as far as parsers may use them\n")
				for (rule in BundleSurface.keepRules()) {
					append(rule).append('\n')
				}
			},
		)
	}
}
