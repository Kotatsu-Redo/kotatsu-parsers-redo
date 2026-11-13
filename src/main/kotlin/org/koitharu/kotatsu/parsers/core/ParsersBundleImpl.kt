package org.koitharu.kotatsu.parsers.core

import okhttp3.HttpUrl
import org.koitharu.kotatsu.parsers.DirectParsersAccess
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaParser
import org.koitharu.kotatsu.parsers.ParsersApi
import org.koitharu.kotatsu.parsers.ParsersBundle
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.ParserMangaSource
import org.koitharu.kotatsu.parsers.newParser
import org.koitharu.kotatsu.parsers.util.LinkResolver

/**
 * The parsers of this very build of the library.
 *
 * An application uses the one it was compiled with to start from. In a bundle it has downloaded, this is the class it
 * looks for by name, [CLASS_NAME], to get to everything else: neither may change.
 */
@DirectParsersAccess
public object ParsersBundleImpl : ParsersBundle {

	public const val CLASS_NAME: String = "org.koitharu.kotatsu.parsers.core.ParsersBundleImpl"

	override val apiVersion: Int
		get() = ParsersApi.VERSION

	override val version: Long
		get() = ParsersBuildInfo.VERSION

	override val revision: String
		get() = ParsersBuildInfo.REVISION

	override val sources: List<ParserMangaSource>
		get() = MangaParserSource.entries

	override fun findSource(name: String): ParserMangaSource? = MangaParserSource.find(name)

	override fun newParser(source: ParserMangaSource, context: MangaLoaderContext): MangaParser {
		// By its name: the source may well be an object of another bundle, the one this is taking over from
		val own = requireNotNull(MangaParserSource.find(source.name)) {
			"Manga source ${source.name} is not known to the parsers of $revision"
		}
		return own.newParser(context)
	}

	override suspend fun resolveLink(context: MangaLoaderContext, link: HttpUrl): Manga? {
		return LinkResolver(context, link).getManga()
	}
}
