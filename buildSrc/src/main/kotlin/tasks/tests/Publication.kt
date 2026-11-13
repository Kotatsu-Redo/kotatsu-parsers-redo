package tasks.tests

import org.gradle.api.GradleException
import tasks.bundle.readPublicKey
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.URL
import java.security.Signature
import java.util.Base64

private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"

/** What the manifest of a publication says of the parsers it announces. */
internal class Publication(
	val url: String,
	/** The commit they were built from. */
	val revision: String,
	/** The time of that commit, which tells which of two publications is the newer. */
	val version: Long,
)

/**
 * The last publication, or null when there is none. Any other failure to read it is an error: a publication that
 * cannot be read must not be taken for one that does not exist.
 *
 * When [publicKey] is given the signature has to verify, as an application would check it.
 */
internal fun fetchPublication(bundleUrl: String, apiVersion: Int, publicKey: String?): Publication? {
	val url = bundleUrl.trimEnd('/') + "/parsers-v$apiVersion.json"
	val text = try {
		download(URL(url))
	} catch (e: FileNotFoundException) {
		return null
	} catch (e: Exception) {
		throw GradleException("The manifest of the last publication could not be read from $url: $e", e)
	}
	val payloadBase64 = jsonString(text, "payload")
		?: throw GradleException("The manifest at $url has no payload")
	val signatureBase64 = jsonString(text, "signature")
		?: throw GradleException("The manifest at $url has no signature")
	val payload = try {
		Base64.getDecoder().decode(payloadBase64)
	} catch (e: IllegalArgumentException) {
		throw GradleException("The payload of the manifest at $url is not Base64", e)
	}
	publicKey?.takeIf { it.isNotBlank() }?.let { key ->
		val isValid = try {
			Signature.getInstance(SIGNATURE_ALGORITHM).run {
				initVerify(readPublicKey(key))
				update(payload)
				verify(Base64.getDecoder().decode(signatureBase64))
			}
		} catch (e: Exception) {
			false
		}
		if (!isValid) {
			throw GradleException(
				"The manifest at $url is not signed with the key applications trust (parsersPublicKey in " +
					"gradle.properties). It is not used to tell what was published.",
			)
		}
	}
	val payloadText = payload.toString(Charsets.UTF_8)
	val revision = jsonString(payloadText, "revision")?.takeIf { it.isNotBlank() }
		?: throw GradleException("The manifest at $url does not say which revision it was built from")
	val version = Regex("\"version\"\\s*:\\s*(\\d+)").find(payloadText)?.groupValues?.get(1)?.toLongOrNull()
		?: throw GradleException("The manifest at $url does not say which version it is")
	return Publication(url, revision, version)
}

private fun download(url: URL): String {
	val connection = url.openConnection()
	if (connection is HttpURLConnection) {
		connection.connectTimeout = 30_000
		connection.readTimeout = 30_000
		connection.instanceFollowRedirects = true
		if (connection.responseCode == 404) {
			throw FileNotFoundException(url.toString())
		}
		if (connection.responseCode !in 200..299) {
			throw GradleException("HTTP ${connection.responseCode} ${connection.responseMessage}")
		}
	}
	return connection.getInputStream().use { it.readBytes().toString(Charsets.UTF_8) }
}

/** A string field of a JSON object, found without a JSON library: the files read are written by SignBundleTask. */
internal fun jsonString(json: String, key: String): String? {
	val match = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(json) ?: return null
	return match.groupValues[1].replace("\\/", "/").replace("\\\"", "\"").replace("\\\\", "\\")
}
