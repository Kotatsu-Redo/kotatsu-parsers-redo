package tasks.bundle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
private const val PEM_HEADER = "-----BEGIN PRIVATE KEY-----"
private const val PEM_FOOTER = "-----END PRIVATE KEY-----"

/**
 * Writes the small file an application reads to learn there are newer parsers: which file they are in, what it must
 * hash to, and a signature over all of that.
 *
 * The signature is the only thing that stands between the phones of everyone using the application and whoever can
 * put a file where it looks. An application takes parsers signed with the one key it was built to trust and no others.
 */
abstract class SignBundleTask : DefaultTask() {

	@get:InputFile
	@get:PathSensitive(PathSensitivity.NAME_ONLY)
	abstract val archive: RegularFileProperty

	@get:InputDirectory
	@get:PathSensitive(PathSensitivity.RELATIVE)
	abstract val dexDir: DirectoryProperty

	@get:Input
	abstract val apiVersion: Property<Int>

	@get:Input
	abstract val bundleVersion: Property<Long>

	@get:Input
	abstract val revision: Property<String>

	@get:Input
	abstract val minSdk: Property<Int>

	/** The private key, in PEM. Kept out of what Gradle remembers of a task. */
	@get:Internal
	abstract val privateKey: Property<String>

	/** The public half, as applications hold it: when given, the signature is checked with it before anything is written. */
	@get:Input
	@get:Optional
	abstract val publicKey: Property<String>

	@get:OutputFile
	abstract val manifest: RegularFileProperty

	@TaskAction
	fun run() {
		val key = privateKey.orNull?.takeIf { it.isNotBlank() } ?: throw GradleException(
			"No key to sign the parsers with. Put the private key, in PEM, in the PARSERS_SIGNING_KEY environment " +
				"variable, or the path of its file in -PparsersSigningKeyFile. A pair is made by " +
				"./gradlew generateParsersSigningKey -PkeyFile=<path>",
		)
		val version = bundleVersion.get()
		if (version <= 0) {
			throw GradleException(
				"These parsers have no version: the time of their commit could not be read from git. " +
					"Build from a git checkout, or pass -PparsersVersion=<seconds since the epoch>",
			)
		}
		val archiveFile = archive.get().asFile
		val dexFiles = dexDir.get().asFile.listFiles { file -> file.name.endsWith(".dex") }.orEmpty().sortedBy { it.name }
		val revisionText = revision.get()
		require(SAFE.matches(revisionText)) { "Unexpected revision: $revisionText" }
		require(SAFE.matches(archiveFile.name)) { "Unexpected file name: ${archiveFile.name}" }
		val payload = buildString {
			append("{\"format\":1")
			append(",\"api\":").append(apiVersion.get())
			append(",\"version\":").append(version)
			append(",\"revision\":\"").append(revisionText).append('"')
			append(",\"minSdk\":").append(minSdk.get())
			append(",\"file\":\"").append(archiveFile.name).append('"')
			append(",\"size\":").append(archiveFile.length())
			append(",\"sha256\":\"").append(archiveFile.sha256()).append('"')
			append(",\"dex\":[")
			dexFiles.forEachIndexed { index, file ->
				if (index > 0) append(',')
				append("{\"name\":\"").append(file.name).append('"')
				append(",\"size\":").append(file.length())
				append(",\"sha256\":\"").append(file.sha256()).append("\"}")
			}
			append("]}")
		}.toByteArray(Charsets.UTF_8)

		val signature = Signature.getInstance(SIGNATURE_ALGORITHM).run {
			initSign(readPrivateKey(key))
			update(payload)
			sign()
		}
		publicKey.orNull?.takeIf { it.isNotBlank() }?.let { expected ->
			val isValid = Signature.getInstance(SIGNATURE_ALGORITHM).run {
				initVerify(readPublicKey(expected))
				update(payload)
				verify(signature)
			}
			if (!isValid) {
				throw GradleException(
					"The key the parsers were signed with is not the one applications trust (parsersPublicKey in " +
						"gradle.properties). Nothing was written: no application would have taken these parsers.",
				)
			}
		}
		val encoder = Base64.getEncoder()
		val output = manifest.get().asFile
		output.parentFile.mkdirs()
		// What is signed is kept byte for byte, inside an envelope: no two programs write the same JSON the same way
		output.writeText(
			"{\"payload\":\"${encoder.encodeToString(payload)}\",\"signature\":\"${encoder.encodeToString(signature)}\"}\n",
		)
		logger.lifecycle("Parsers $revisionText, version $version: ${archiveFile.name} and ${output.name}")
	}

	private fun File.sha256(): String {
		val digest = MessageDigest.getInstance("SHA-256")
		inputStream().use { input ->
			val buffer = ByteArray(64 * 1024)
			while (true) {
				val read = input.read(buffer)
				if (read < 0) break
				digest.update(buffer, 0, read)
			}
		}
		return digest.digest().joinToString("") { "%02x".format(it) }
	}

	private companion object {

		val SAFE = Regex("[A-Za-z0-9._-]+")
	}
}

/**
 * Makes the pair of keys: the private one to sign parsers with, written to a file that is to be kept secret, and the
 * public one, which goes into the application and can be shown to anyone.
 */
abstract class GenerateSigningKeyTask : DefaultTask() {

	@get:Input
	@get:Optional
	abstract val keyFile: Property<String>

	@TaskAction
	fun run() {
		val path = keyFile.orNull ?: throw GradleException("Say where to write the private key: -PkeyFile=<path>")
		val file = File(path)
		if (file.exists()) {
			throw GradleException("$file is already there. A key in use must not be lost: choose another path")
		}
		val pair = KeyPairGenerator.getInstance("EC").run {
			initialize(ECGenParameterSpec("secp256r1"))
			generateKeyPair()
		}
		val encoder = Base64.getMimeEncoder(64, "\n".toByteArray())
		file.absoluteFile.parentFile.mkdirs()
		file.writeText("$PEM_HEADER\n${encoder.encodeToString(pair.private.encoded)}\n$PEM_FOOTER\n")
		logger.lifecycle("Private key, to be kept secret and nowhere in the repository:\n  ${file.absolutePath}")
		logger.lifecycle("Public key, for the application and for parsersPublicKey in gradle.properties:")
		logger.lifecycle("  " + Base64.getEncoder().encodeToString(pair.public.encoded))
	}
}

internal fun readPrivateKey(pem: String): PrivateKey {
	val text = pem.replace("\r", "").trim()
	if (!text.startsWith(PEM_HEADER)) {
		throw GradleException(
			"The signing key is not a PKCS#8 private key in PEM (\"$PEM_HEADER\"). A key written by openssl as " +
				"\"BEGIN EC PRIVATE KEY\" is converted with: openssl pkcs8 -topk8 -nocrypt -in <key> -out <key>.pem",
		)
	}
	val body = text.removePrefix(PEM_HEADER).substringBefore(PEM_FOOTER).filterNot { it.isWhitespace() }
	return KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)))
}

internal fun readPublicKey(base64: String): PublicKey {
	return KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(base64.trim())))
}
