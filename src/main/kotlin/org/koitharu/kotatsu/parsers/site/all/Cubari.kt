package org.koitharu.kotatsu.parsers.site.all

import okhttp3.HttpUrl
import org.json.JSONArray
import org.koitharu.kotatsu.parsers.Broken
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.AbstractMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.network.UserAgents
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import java.util.*

/**
 * Parser for cubari.moe — a manga proxy/reader that hosts arbitrary JSON series.
 *
 * Cubari has no browsable catalog; every manga is accessed via a direct share link such as
 * `https://cubari.moe/read/gist/<base64id>/`. The base64 id uses standard encoding and
 * may contain '/' characters (e.g. `raw/Repo/chapter.json`), so the id spans multiple
 * URL path segments.
 *
 * API pattern: `GET https://cubari.moe/read/api/<type>/series/<id>/`
 * where <type> ∈ {gist, imgur, imgchest, mangadex, …} and <id> may contain '/'.
 *
 * Multiple translation groups per chapter are exposed as separate [MangaChapter] entries
 * sharing the same chapter number but carrying distinct [MangaChapter.branch] values.
 */

@Broken
@MangaSourceParser("CUBARI", "Cubari", type = ContentType.OTHER)
internal class CubariParser(context: MangaLoaderContext) :
	AbstractMangaParser(context, MangaParserSource.CUBARI) {

	override val configKeyDomain = ConfigKey.Domain("cubari.moe")

	// Cubari aggregates covers and images from arbitrary hosts including MangaDex, which
	// rejects browser User-Agent strings without a matching TLS fingerprint (HTTP 400).
	// The Kotatsu UA passes MangaDex's bot check and is accepted by plain file hosts.
	override val userAgentKey = ConfigKey.UserAgent(UserAgents.KOTATSU)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.NEWEST)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(isSearchSupported = false)

	override suspend fun getFilterOptions() = MangaListFilterOptions()

	/** No catalog — cubari is a link-resolution-only source. */
	override suspend fun getList(offset: Int, order: SortOrder, filter: MangaListFilter): List<Manga> = emptyList()

	/**
	 * Parse the share URL directly rather than letting the generic seed fallback trigger a
	 * catalog search (which would fail since [getList] is empty).
	 *
	 * Handles: `https://cubari.moe/read/<type>/<id.../>`
	 * Returns null for any unrecognised path shape so the caller can fall through gracefully.
	 */
	override suspend fun resolveLink(resolver: LinkResolver, link: HttpUrl): Manga? {
		val (type, id) = parseEncodedPath(link.encodedPath) ?: return null
		if (type == "api") return null   // guard against internal API URLs
		val relativeUrl = "/read/$type/$id"
		return getDetails(
			Manga(
				id = generateUid(relativeUrl),
				title = "",
				altTitles = emptySet(),
				url = relativeUrl,
				publicUrl = link.toString(),
				rating = RATING_UNKNOWN,
				contentRating = null,
				coverUrl = "",
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				source = source,
			),
		)
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val (type, id) = parseMangaUrl(manga.url)
		val apiUrl = "https://$domain/read/api/$type/series/$id/"
		val json = webClient.httpGet(apiUrl).parseJson()

		val title = json.getString("title")
		val description = json.getStringOrNull("description")
		val author = json.getStringOrNull("author")
		val artist = json.getStringOrNull("artist")
		val cover = json.getStringOrNull("cover")
		assert(author != null) { "Author is null for $apiUrl" }
		assert(description != null) { "Description is null for $apiUrl" }

		// top-level groups: map of groupId → display name
		val groupNames: Map<String, String> = json.optJSONObject("groups")
			?.entries<String>()
			?.associate { (k, v) -> k to v }
			.orEmpty()

		val chaptersJson = json.getJSONObject("chapters")
		val chapterKeys = chaptersJson.keys().asSequence().toList()
			.sortedBy { it.toFloatOrNull() ?: Float.MAX_VALUE }

		val chapters = ArrayList<MangaChapter>()
		for (chapterKey in chapterKeys) {
			val chapter = chaptersJson.getJSONObject(chapterKey)
			val chapterTitle = chapter.getStringOrNull("title")
			val volume = chapter.getStringOrNull("volume")?.toIntOrNull() ?: 0
			val number = chapterKey.toFloatOrNull() ?: 0f
			val releaseDates = chapter.optJSONObject("release_date")
			val groupsInChapter = chapter.getJSONObject("groups")
			for (groupId in groupsInChapter.keys()) {
				val branchName = groupNames[groupId] ?: groupId
				val uploadDate = releaseDates?.getLongOrDefault(groupId, 0L)?.times(1000L) ?: 0L
				val chapterRelUrl = "/read/$type/$id/$chapterKey/$groupId"
				chapters.add(
					MangaChapter(
						id = generateUid(chapterRelUrl),
						title = chapterTitle,
						number = number,
						volume = volume,
						url = chapterRelUrl,
						scanlator = null,
						uploadDate = uploadDate,
						branch = branchName,
						source = source,
					),
				)
			}
		}

		return manga.copy(
			title = title,
			altTitles = emptySet(),
			description = description,
			coverUrl = cover?.toAbsoluteUrl(domain),
			authors = setOfNotNull(author, artist),
			tags = emptySet(),
			state = null,
			rating = RATING_UNKNOWN,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		// chapter.url = "/read/<type>/<id...>/<chapterKey>/<groupId>"
		// chapterKey and groupId are numeric strings with no '/', so strip from the right.
		val url = chapter.url.trimEnd('/')
		val groupId = url.substringAfterLast('/')
		val beforeGroupId = url.substringBeforeLast('/')
		val chapterKey = beforeGroupId.substringAfterLast('/')
		val mangaRelUrl = beforeGroupId.substringBeforeLast('/')
		val (type, id) = parseMangaUrl(mangaRelUrl)

		val apiUrl = "https://$domain/read/api/$type/series/$id/"
		val groupValue = webClient.httpGet(apiUrl).parseJson()
			.getJSONObject("chapters")
			.getJSONObject(chapterKey)
			.getJSONObject("groups")
			.get(groupId)

		val imageUrls: List<String> = when (groupValue) {
			is JSONArray -> (0 until groupValue.length()).map { groupValue.getString(it) }
			is String -> webClient.httpGet(groupValue.toAbsoluteUrl(domain)).parseJsonArray()
				.asTypedList()
			else -> emptyList()
		}

		return imageUrls.mapIndexed { index, imageUrl ->
			MangaPage(
				id = generateUid("${chapter.url}/$index"),
				url = imageUrl,
				preview = null,
				source = source,
			)
		}
	}

	/**
	 * Extract (type, id) from an encoded URL path like `/read/gist/raw%2Frepo/...` or
	 * `/read/gist/raw/repo/...` (cubari uses unencoded slashes in the base64 id).
	 * Returns null if the path does not match the expected shape.
	 */
	private fun parseEncodedPath(encodedPath: String): Pair<String, String>? {
		val path = encodedPath.trimEnd('/')
		val prefix = "/read/"
		if (!path.startsWith(prefix)) return null
		val afterRead = path.removePrefix(prefix)
		val slashIdx = afterRead.indexOf('/')
		if (slashIdx < 0) return null
		val type = afterRead.substring(0, slashIdx)
		val id = afterRead.substring(slashIdx + 1)
		if (type.isEmpty() || id.isEmpty()) return null
		return type to id
	}

	/**
	 * Extract (type, id) from a stored relative manga URL: `/read/<type>/<id...>`.
	 * The id may contain '/' because cubari uses standard (non-URL-safe) base64.
	 */
	private fun parseMangaUrl(relativeUrl: String): Pair<String, String> {
		val path = relativeUrl.trimEnd('/')
		val prefix = "/read/"
		require(path.startsWith(prefix)) { "Unexpected cubari manga url: $relativeUrl" }
		val afterRead = path.removePrefix(prefix)
		val slashIdx = afterRead.indexOf('/')
		require(slashIdx > 0) { "Cannot extract type from cubari manga url: $relativeUrl" }
		val type = afterRead.substring(0, slashIdx)
		val id = afterRead.substring(slashIdx + 1)
		return type to id
	}
}
