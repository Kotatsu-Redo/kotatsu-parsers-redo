package org.koitharu.kotatsu.parsers.site.vi

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** In-memory rollover for this site's numbered domains, not a general domain discovery service. */
internal class GocTruyenDomainResolver(
	private val configuredDomain: () -> String,
	private val probe: suspend (String) -> Boolean,
	private val nanoTime: () -> Long = System::nanoTime,
	private val advertisedDomain: suspend (String) -> String? = { null },
) {
	private val mutex = Mutex()
	private var configured: String? = null
	private var selected: String? = null
	private var lastProbe: Long? = null
	private var lastNoticeCheck: Long? = null

	suspend fun <T> withDomain(block: suspend (String) -> T): T {
		val initial = mutex.withLock { preferAdvertisedDomain(currentDomain()) }
		return try {
			block(initial)
		} catch (e: IOException) {
			currentCoroutineContext().ensureActive()
			if (!isConnectionFailure(e)) throw e
			val replacement = mutex.withLock {
				val current = currentDomain()
				if (current != initial) return@withLock current
				val previous = lastProbe
				if (previous != null && nanoTime() - previous < RETRY_INTERVAL) throw e
				val candidates = candidates(current)
				if (candidates.isEmpty()) throw e
				lastProbe = nanoTime()
				for (candidate in candidates) {
					val valid = try {
						probe(candidate)
					} catch (_: IOException) {
						currentCoroutineContext().ensureActive()
						false
					}
					if (valid) {
						selected = candidate
						lastNoticeCheck = null
						return@withLock preferAdvertisedDomain(candidate)
					}
				}
				throw e
			}
			// One retry only; a failure on the verified replacement must not start another scan.
			block(replacement)
		}
	}

	// Called under the mutex. Only a verified, newer domain advertised by the site can replace a working host.
	private suspend fun preferAdvertisedDomain(current: String): String {
		val currentNumber = domainNumber(current) ?: return current
		val previous = lastNoticeCheck
		if (previous != null && nanoTime() - previous < NOTICE_INTERVAL) return current
		lastNoticeCheck = nanoTime()
		try {
			val advertised = advertisedDomain(current) ?: return current
			val number = domainNumber(advertised) ?: return current
			if (number <= currentNumber || !probe(advertised)) return current
			selected = advertised
			return advertised
		} catch (_: IOException) {
			currentCoroutineContext().ensureActive()
			// A failed notice fetch/validation must not prevent normal access or bounded rollover.
			return current
		}
	}

	// Called under the mutex. Configuration changes reset discovery; custom hosts are never guessed.
	private fun currentDomain(): String {
		val value = configuredDomain()
		if (configured != value) {
			configured = value
			selected = value
			lastProbe = null
			lastNoticeCheck = null
		}
		return requireNotNull(selected)
	}

	companion object {
		private val NUMBERED_DOMAIN = Regex("goctruyentranhvui([0-9]{1,6})\\.com", RegexOption.IGNORE_CASE)
		private val RETRY_INTERVAL = TimeUnit.MINUTES.toNanos(5)
		private val NOTICE_INTERVAL = TimeUnit.MINUTES.toNanos(15)
		private const val MAX_CANDIDATES = 5

		fun candidates(domain: String): List<String> {
			val number = domainNumber(domain) ?: return emptyList()
			return (1..MAX_CANDIDATES).map { "goctruyentranhvui${number + it}.com" }
		}

		fun domainNumber(domain: String): Int? =
			NUMBERED_DOMAIN.matchEntire(domain)?.groupValues?.get(1)?.toIntOrNull()

		fun isConnectionFailure(error: IOException): Boolean =
			error is UnknownHostException || error is SocketException || error is SocketTimeoutException

		/** Update saved URLs on the site's old numbered hosts, but leave external image CDNs alone. */
		fun rebaseUrl(value: String, domain: String): String {
			val origin = "https://$domain/".toHttpUrl()
			val url = requireNotNull(origin.resolve(value)) { "Invalid source URL" }
			return if (url.host == origin.host || NUMBERED_DOMAIN.matches(url.host)) {
				url.newBuilder().scheme("https").host(origin.host).port(origin.port)
					.username("").password("").build().toString()
			} else {
				url.toString()
			}
		}
	}
}
