package org.koitharu.kotatsu.parsers.site.vi

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.jsoup.HttpStatusException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.koitharu.kotatsu.parsers.exception.AuthRequiredException
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException

internal class GocTruyenDomainResolverTest {

	@Test
	fun scansNextFiveDomainsAndCachesFirstVerifiedReplacement() = runTest {
		val probes = mutableListOf<String>()
		val resolver = GocTruyenDomainResolver({ host(41) }, {
			probes += it
			it == host(43)
		})
		val requests = mutableListOf<String>()
		val result = resolver.withDomain {
			requests += it
			if (it == host(41)) throw UnknownHostException()
			it
		}
		assertEquals(host(43), result)
		assertEquals(listOf(host(42), host(43)), probes)
		assertEquals(listOf(host(41), host(43)), requests)
		assertEquals(host(43), resolver.withDomain { it })
		assertEquals(2, probes.size)
	}

	@Test
	fun concurrentFailuresShareOneDiscovery() = runTest {
		var probes = 0
		val resolver = GocTruyenDomainResolver({ host(41) }, {
			probes++
			delay(10)
			true
		})
		val results = List(8) {
			async {
				resolver.withDomain { domain ->
					if (domain == host(41)) throw ConnectException()
					domain
				}
			}
		}.awaitAll()
		assertTrue(results.all { it == host(42) })
		assertEquals(1, probes)
	}

	@Test
	fun failedScanIsBoundedAndThrottled() = runTest {
		var now = 0L
		val probes = mutableListOf<String>()
		val failure = UnknownHostException("dead")
		val resolver = GocTruyenDomainResolver({ host(41) }, { probes += it; false }, { now })
		repeat(2) {
			assertSame(failure, runCatching { resolver.withDomain<String> { throw failure } }.exceptionOrNull())
		}
		assertEquals((42..46).map(::host), probes)
		now += TimeUnit.MINUTES.toNanos(5)
		runCatching { resolver.withDomain<String> { throw failure } }
		assertEquals(10, probes.size)
	}

	@Test
	fun manualConfigurationOverridesDiscoveryAndCustomHostsAreNotGuessed() = runTest {
		var configured = host(41)
		var probes = 0
		val resolver = GocTruyenDomainResolver({ configured }, { probes++; true })
		assertEquals(host(42), resolver.withDomain {
			if (it == host(41)) throw UnknownHostException()
			it
		})
		configured = "custom.example"
		assertEquals(configured, resolver.withDomain { it })
		runCatching { resolver.withDomain<String> { throw UnknownHostException() } }
		assertEquals(1, probes)
		assertTrue(GocTruyenDomainResolver.candidates("custom.example").isEmpty())
		assertTrue(GocTruyenDomainResolver.candidates("goctruyentranhvui41.com.attacker.example").isEmpty())
	}

	@Test
	fun httpAuthenticationTlsAndGenericIoErrorsDoNotTriggerDiscovery() = runTest {
		var probes = 0
		val resolver = GocTruyenDomainResolver({ host(41) }, { probes++; true })
		for (failure in listOf(
			HttpStatusException("missing", 404, "https://${host(41)}"),
			HttpStatusException("blocked", 403, "https://${host(41)}"),
			HttpStatusException("limited", 429, "https://${host(41)}"),
			HttpStatusException("server", 500, "https://${host(41)}"),
			AuthRequiredException(MangaParserSource.GOCTRUYENTRANHVUI),
			SSLHandshakeException("certificate invalid"),
			IOException("other failure"),
		)) {
			assertSame(failure, runCatching { resolver.withDomain<String> { throw failure } }.exceptionOrNull())
		}
		assertEquals(0, probes)
	}

	@Test
	fun probeConnectionFailureSkipsCandidateButCancellationPropagates() = runTest {
		val resolver = GocTruyenDomainResolver({ host(41) }, {
			if (it == host(42)) throw SocketTimeoutException()
			true
		})
		assertEquals(host(43), resolver.withDomain {
			if (it == host(41)) throw UnknownHostException()
			it
		})
		val cancellation = CancellationException("cancelled")
		val cancelledResolver = GocTruyenDomainResolver({ host(41) }, { throw cancellation })
		assertSame(cancellation, runCatching {
			cancelledResolver.withDomain<String> { throw UnknownHostException() }
		}.exceptionOrNull())
	}

	@Test
	fun replacementGetsOnlyOneRetry() = runTest {
		var probes = 0
		var requests = 0
		val failure = ConnectException()
		val resolver = GocTruyenDomainResolver({ host(41) }, { probes++; true })
		assertSame(failure, runCatching {
			resolver.withDomain<String> { requests++; throw failure }
		}.exceptionOrNull())
		assertEquals(1, probes)
		assertEquals(2, requests)
	}

	@Test
	fun rebasesSavedSiteUrlsButPreservesExternalCdnUrls() {
		assertEquals("https://${host(42)}/truyen/example?x=1", GocTruyenDomainResolver.rebaseUrl(
			"https://${host(17)}/truyen/example?x=1", host(42),
		))
		assertEquals("https://${host(42)}/image/example.jpg", GocTruyenDomainResolver.rebaseUrl(
			"/image/example.jpg", host(42),
		))
		val cdn = "https://cdn.example/image/example.jpg"
		assertEquals(cdn, GocTruyenDomainResolver.rebaseUrl(cdn, host(42)))
	}

	@Test
	fun prefersAdvertisedDomainEvenWhenOldHostWorksAndRefreshesPeriodically() = runTest {
		var now = 0L
		val notices = mutableListOf<String>()
		val probes = mutableListOf<String>()
		val resolver = GocTruyenDomainResolver(
			{ host(30) },
			{ probes += it; true },
			{ now },
			advertisedDomain = { domain ->
				notices += domain
				if (domain == host(30)) host(41) else host(50)
			},
		)
		assertEquals(host(41), resolver.withDomain { it })
		assertEquals(host(41), resolver.withDomain { it })
		assertEquals(listOf(host(30)), notices)
		assertEquals(listOf(host(41)), probes)
		now += TimeUnit.MINUTES.toNanos(15)
		assertEquals(host(50), resolver.withDomain { it })
		assertEquals(listOf(host(30), host(41)), notices)
		assertEquals(listOf(host(41), host(50)), probes)
	}

	@Test
	fun ignoresOlderForeignAndUnverifiedAnnouncements() = runTest {
		for (advertised in listOf(host(30), host(41), host(99), "attacker.example")) {
			val resolver = GocTruyenDomainResolver(
				{ host(41) }, { false }, advertisedDomain = { advertised },
			)
			assertEquals(host(41), resolver.withDomain { it })
		}
	}

	@Test
	fun unavailableNoticeDoesNotBreakWorkingDomain() = runTest {
		val resolver = GocTruyenDomainResolver(
			{ host(41) }, { error("No probe expected") },
			advertisedDomain = { throw UnknownHostException() },
		)
		assertEquals(host(41), resolver.withDomain { it })
	}

	@Test
	fun deadDomainFallsBackToNumberedCandidateThenItsMigrationNotice() = runTest {
		val probes = mutableListOf<String>()
		val resolver = GocTruyenDomainResolver(
			{ host(41) }, { probes += it; true },
			advertisedDomain = {
				if (it == host(41)) throw UnknownHostException()
				host(50)
			},
		)
		assertEquals(host(50), resolver.withDomain {
			if (it == host(41)) throw UnknownHostException()
			it
		})
		assertEquals(listOf(host(42), host(50)), probes)
	}

	@Test
	fun concurrentRequestsShareNoticeCheckAndValidation() = runTest {
		var notices = 0
		var probes = 0
		val resolver = GocTruyenDomainResolver(
			{ host(30) }, { probes++; true },
			advertisedDomain = { notices++; delay(10); host(41) },
		)
		val results = List(8) { async { resolver.withDomain { it } } }.awaitAll()
		assertTrue(results.all { it == host(41) })
		assertEquals(1, notices)
		assertEquals(1, probes)
	}

	@Test
	fun customDomainsDoNotReadNoticesAndCancellationIsNotSwallowed() = runTest {
		val custom = GocTruyenDomainResolver(
			{ "custom.example" }, { error("Unexpected probe") },
			advertisedDomain = { error("Unexpected notice lookup") },
		)
		assertEquals("custom.example", custom.withDomain { it })
		val cancellation = CancellationException("cancelled notice")
		val resolver = GocTruyenDomainResolver(
			{ host(41) }, { true }, advertisedDomain = { throw cancellation },
		)
		assertSame(cancellation, runCatching { resolver.withDomain { it } }.exceptionOrNull())
	}

	private fun host(number: Int) = "goctruyentranhvui$number.com"
}
