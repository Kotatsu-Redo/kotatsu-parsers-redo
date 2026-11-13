package tasks.tests

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction

/**
 * Refuses to publish parsers that are not newer than the ones published. Applications take the newest by version, so
 * publishing an older build (an old run started again, say) would at best do nothing and at worst hide a newer one.
 */
abstract class CheckNewerThanPublishedTask : DefaultTask() {

	/** The directory holding the manifests of publications. */
	@get:Input
	abstract val bundleUrl: Property<String>

	@get:Input
	abstract val apiVersion: Property<Int>

	@get:Input
	@get:Optional
	abstract val publicKey: Property<String>

	/** The version of this build: the time of its commit. */
	@get:Input
	abstract val buildVersion: Property<Long>

	@get:Input
	abstract val buildRevision: Property<String>

	init {
		outputs.upToDateWhen { false }
	}

	@TaskAction
	fun run() {
		val published = fetchPublication(bundleUrl.get(), apiVersion.get(), publicKey.orNull)
		if (published == null) {
			logger.lifecycle("Nothing is published yet, so these parsers are the newest.")
			return
		}
		if (published.version >= buildVersion.get()) {
			throw GradleException(
				"Published are the parsers of revision ${published.revision}, version ${published.version}. This build " +
					"is revision ${buildRevision.get()}, version ${buildVersion.get()}. A build that is older than " +
					"what is published, or the same, is not published over it.",
			)
		}
		logger.lifecycle("This build (version ${buildVersion.get()}) is newer than the published one (${published.version}).")
	}
}
