package org.koitharu.kotatsu.parsers.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaParser
import org.koitharu.kotatsu.parsers.core.AbstractMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy
import java.util.Locale

public class LinkResolver internal constructor(
	private val context: MangaLoaderContext,
	public val link: HttpUrl,
) {

	private val candidates = suspendLazy(Dispatchers.Default, ::resolveCandidates)

	public suspend fun getSource(): MangaParserSource? = candidates.get().firstOrNull()?.source

	public suspend fun getManga(): Manga? {
		val ranked = candidates.get()
		val best = ranked.firstOrNull() ?: return null
		// Several sources may share a domain (locales of one site, legacy parsers).
		// Every equally ranked source gets a chance to claim the link before falling back.
		for (candidate in ranked.takeWhile { it.score == best.score }) {
			context.newParserInstance(candidate.source).resolveLink(this, link)?.let { return it }
		}
		return resolveManga(context.newParserInstance(best.source))
	}

	/**
	 * All sources whose domain matches the link, ranked by:
	 * 1. exact host match over a match of the top private domain only
	 * 2. working sources over broken ones
	 * 3. sources whose locale matches a locale hint of the link (subdomain, first path segment or query)
	 * 4. declaration order
	 */
	private suspend fun resolveCandidates(): List<Candidate> = runInterruptible(Dispatchers.Default) {
		val host = link.host.removePrefix("www.")
		val topDomain = link.topPrivateDomain()
		val localeHints = localeHints()
		val matches = ArrayList<Candidate>()
		for (s in MangaParserSource.entries) {
			val presets = context.newParserInstance(s).configKeyDomain.presetValues
			var score = when {
				presets.any { it.removePrefix("www.") == host } -> 4
				topDomain != null && topDomain in presets -> 0
				else -> continue
			}
			if (!s.isBroken) score += 2
			if (s.locale.isNotEmpty() && s.locale.lowercase(Locale.ROOT) in localeHints) score += 1
			matches += Candidate(s, score)
		}
		matches.sortedByDescending { it.score }
	}

	private fun localeHints(): Set<String> {
		val raw = ArrayList<String>(6)
		val labels = link.host.split('.')
		if (labels.size > 2) raw += labels.first()
		link.pathSegments.firstOrNull()?.let { raw += it }
		for (name in LOCALE_QUERY_PARAMS) {
			link.queryParameter(name)?.let { raw += it }
		}
		return raw.mapNotNullTo(HashSet()) { value ->
			value.substringBefore('-').substringBefore('_').lowercase(Locale.ROOT)
				.takeIf { it in ISO_LANGUAGES }
		}
	}

	private data class Candidate(val source: MangaParserSource, val score: Int)

	internal suspend fun resolveManga(
		parser: MangaParser,
		url: String = link.toString().toRelativeUrl(link.host),
		id: Long = parser.generateUid(url),
		title: String = STUB_TITLE,
	): Manga? = resolveBySeed(
		parser,
		Manga(
			id = id,
			title = title,
			altTitles = emptySet(),
			url = url,
			publicUrl = link.toString(),
			rating = RATING_UNKNOWN,
			contentRating = null,
			coverUrl = "",
			tags = emptySet(),
			state = null,
			authors = emptySet(),
			largeCoverUrl = null,
			description = null,
			chapters = null,
			source = parser.source,
		),
	)

	private suspend fun resolveBySeed(parser: MangaParser, s: Manga): Manga? {
		val seed = parser.getDetails(s)
		if (!parser.filterCapabilities.isSearchSupported) {
			return seed.takeUnless { it.chapters.isNullOrEmpty() }
		}
		val query = when {
			seed.title != STUB_TITLE && seed.title.isNotEmpty() -> seed.title
			seed.altTitles.isNotEmpty() -> seed.altTitles.first()
			seed.authors.isNotEmpty() -> seed.authors.first()
			else -> return seed // unfortunately we do not know a real manga title so unable to find it
		}
		val resolved = runCatchingCancellable {
			val list = parser.getList(0, parser.bestSortOrder(), MangaListFilter(query = query))
			list.singleOrNull { manga -> isSameUrl(manga.publicUrl) }
		}.getOrNull()
		if (resolved == null) {
			return seed
		}
		return runCatchingCancellable {
			parser.getDetails(resolved)
		}.getOrElse {
			resolved.copy(
				chapters = seed.chapters ?: resolved.chapters,
				description = seed.description ?: resolved.description,
				authors = seed.authors.ifEmpty { resolved.authors },
				tags = seed.tags + resolved.tags,
				state = seed.state ?: resolved.state,
				coverUrl = seed.coverUrl ?: resolved.coverUrl,
				largeCoverUrl = seed.largeCoverUrl ?: resolved.largeCoverUrl,
				altTitles = seed.altTitles + resolved.altTitles,
			)
		}
	}

	private fun isSameUrl(publicUrl: String): Boolean {
		if (publicUrl == link.toString()) {
			return true
		}
		val httpUrl = publicUrl.toHttpUrlOrNull() ?: return false
		return link.host == httpUrl.host
			&& link.encodedPath == httpUrl.encodedPath
	}

	private fun MangaParser.bestSortOrder(): SortOrder {
		val supported = availableSortOrders
		if (SortOrder.RELEVANCE in supported) {
			return SortOrder.RELEVANCE
		}
		if (this is AbstractMangaParser) {
			return defaultSortOrder
		}
		return SortOrder.entries.first { it in supported }
	}

	private companion object {

		const val STUB_TITLE = "Unknown manga"

		val LOCALE_QUERY_PARAMS = arrayOf("lang", "language", "locale", "hl")

		val ISO_LANGUAGES: Set<String> = Locale.getISOLanguages().toHashSet()
	}
}
