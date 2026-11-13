package org.koitharu.kotatsu.parsers

import okhttp3.HttpUrl
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.ParserMangaSource

/**
 * One whole set of parsers: the sources it knows and the way to get a parser for each.
 *
 * An application is built with one inside it, and can load a newer one from a file it has downloaded, without being
 * updated itself. Both are reached through this interface and nothing else, which is what lets one take the place of
 * the other while the application is running.
 *
 * What the two sides share, this interface and everything else under `src/contract`, stays inside the application and
 * is not part of what is downloaded. See [ParsersApi.VERSION].
 */
public interface ParsersBundle {

	/** The [ParsersApi.VERSION] these parsers were built for. */
	public val apiVersion: Int

	/** When the code of these parsers was last changed, in seconds since the epoch. Of two bundles, the newer has the larger. */
	public val version: Long

	/** The commit these parsers were built from, to show to a person. */
	public val revision: String

	/** Every source of this bundle, the broken ones included, always in the same order. */
	public val sources: List<ParserMangaSource>

	public fun findSource(name: String): ParserMangaSource?

	/**
	 * @throws IllegalArgumentException if this bundle has no parser for [source]
	 */
	public fun newParser(source: ParserMangaSource, context: MangaLoaderContext): MangaParser

	/**
	 * Find the manga a web link points at, among all the sources of this bundle
	 */
	public suspend fun resolveLink(context: MangaLoaderContext, link: HttpUrl): Manga?
}

public object ParsersApi {

	/**
	 * The version of everything under `src/contract`: the classes an application and a downloaded bundle have in
	 * common, and the libraries both are built with.
	 *
	 * A bundle is only ever loaded by an application made for the same version. Any change under `src/contract`, and
	 * any change of the version of a library, has to come with a new number here: `./gradlew checkParsersApi` fails
	 * until it does. Applications already installed then stay on the last bundle published for their version, until
	 * they are updated themselves.
	 */
	public const val VERSION: Int = 1
}

/**
 * Marks what belongs to the copy of the parsers the caller was compiled with: the list of its sources as constants,
 * and the bundle itself.
 *
 * An application that can load newer parsers must not reach for these, except to start from: its own copy is no
 * longer the one in use once a newer bundle is loaded, and a source of the copy is not an instance of the same class
 * as that source in the bundle. [MangaLoaderContext.parsers] and [org.koitharu.kotatsu.parsers.model.ParserMangaSource]
 * are what to use instead. Inside this library the marker is accepted for every file.
 */
@Retention(AnnotationRetention.BINARY)
@RequiresOptIn(
	level = RequiresOptIn.Level.ERROR,
	message = "This is the copy of the parsers this code was compiled with, which may not be the one in use. " +
		"Go through MangaLoaderContext.parsers and ParserMangaSource.",
)
@MustBeDocumented
public annotation class DirectParsersAccess
