package org.koitharu.kotatsu.parsers

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.koitharu.kotatsu.parsers.core.ParsersBundleImpl
import org.koitharu.kotatsu.parsers.exception.AuthRequiredException
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.ParserMangaSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

/**
 * What an application counts on when it puts a newer set of parsers in the place of the one it has: nothing here goes
 * to the network.
 */
internal class ParsersBundleTest {

	private val bundle: ParsersBundle = ParsersBundleImpl

	@Test
	fun `the class an application looks for by name is where it is said to be`() {
		assertEquals(ParsersBundleImpl.CLASS_NAME, ParsersBundleImpl::class.java.name)
		val instance = Class.forName(ParsersBundleImpl.CLASS_NAME).getField("INSTANCE").get(null)
		assertSame(ParsersBundleImpl, instance)
		assertEquals(ParsersApi.VERSION, bundle.apiVersion)
	}

	@Test
	fun `every source has one name, and is found by it`() {
		val sources = bundle.sources
		assertTrue(sources.size > 1000)
		assertEquals(sources.size, sources.mapTo(HashSet()) { it.name }.size)
		for (source in sources) {
			assertSame(source, bundle.findSource(source.name))
			assertSame(source, MangaParserSource.valueOf(source.name))
		}
		assertNull(bundle.findSource("NO_SUCH_SOURCE"))
		assertThrows(IllegalArgumentException::class.java) { MangaParserSource.valueOf("NO_SUCH_SOURCE") }
		assertSame(MangaParserSource.MANGADEX, bundle.findSource("MANGADEX"))
		assertEquals("MANGADEX", MangaParserSource.MANGADEX.toString())
	}

	@Test
	fun `every source has a parser, which says it is of that source`() {
		for (source in bundle.sources) {
			val parser = bundle.newParser(source, MangaLoaderContextMock)
			assertSame(source, parser.source, source.name)
		}
	}

	@Test
	fun `a source of another set of parsers is the same source`() {
		val own: ParserMangaSource = MangaParserSource.MANGADEX
		val foreign: ParserMangaSource = ForeignSource("MANGADEX")
		assertEquals(own, foreign)
		assertEquals(foreign, own)
		assertEquals(own.hashCode(), foreign.hashCode())
		assertNotEquals(own, ForeignSource("MANGAPARK"))
		assertTrue(foreign in bundle.sources)

		// The parser made for it is this bundle's own, and so is the source it carries
		val parser = bundle.newParser(foreign, MangaLoaderContextMock)
		assertSame(MangaParserSource.MANGADEX, parser.source)
		assertNotNull(MangaLoaderContextMock.newParserInstance(foreign))
	}

	@Test
	fun `a source these parsers do not have is refused by name`() {
		val error = assertThrows(IllegalArgumentException::class.java) {
			bundle.newParser(ForeignSource("NO_SUCH_SOURCE"), MangaLoaderContextMock)
		}
		assertTrue(error.message.orEmpty().contains("NO_SUCH_SOURCE"))
	}

	@Test
	fun `a source is written out and read back as the same source, without the class of its parsers`() {
		val source: ParserMangaSource = MangaParserSource.MANGADEX
		val restored = writeAndRead(source)
		assertEquals(source, restored)
		assertEquals(source.hashCode(), restored.hashCode())
		assertEquals(source.title, restored.title)
		assertEquals(source.locale, restored.locale)
		assertEquals(source.contentType, restored.contentType)
		assertEquals(source.isBroken, restored.isBroken)
		// Whoever reads it may hold other parsers, or none: nothing of theirs is named in what was written
		assertNotEquals(MangaParserSource::class.java, restored.javaClass)
		assertEquals(restored, writeAndRead(restored))
		assertSame(MangaParserSource.MANGADEX, bundle.newParser(restored, MangaLoaderContextMock).source)

		// An error carrying a source is what an application keeps to show or to report later
		val error = writeAndRead(AuthRequiredException(source))
		assertEquals(source, error.source)
	}

	private inline fun <reified T : Any> writeAndRead(value: T): T {
		val bytes = ByteArrayOutputStream().also { out -> ObjectOutputStream(out).use { it.writeObject(value) } }.toByteArray()
		assertTrue(!String(bytes, Charsets.ISO_8859_1).contains("MangaParserSource"))
		return ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() } as T
	}

	private class ForeignSource(name: String) : ParserMangaSource(name, name, "en", ContentType.MANGA, false)
}
