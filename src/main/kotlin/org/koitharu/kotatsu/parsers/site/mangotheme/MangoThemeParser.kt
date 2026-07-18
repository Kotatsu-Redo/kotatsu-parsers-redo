package org.koitharu.kotatsu.parsers.site.mangotheme

import okhttp3.Headers
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.parseSafe
import org.koitharu.kotatsu.parsers.util.urlEncoded
import org.koitharu.kotatsu.parsers.util.json.getIntOrDefault
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.json.mapJSONNotNull
import org.koitharu.kotatsu.parsers.util.json.mapJSONNotNullToSet
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.EnumSet
import java.util.Locale

internal abstract class MangoThemeParser(
	context: MangaLoaderContext,
	source: MangaParserSource,
	domain: String,
	private val cdnUrl: String,
	private val encryptionKey: String,
	private val apiBaseUrl: String? = null,
	private val webMangaPathSegment: String = "obra",
	private val latestPageSize: Int = 24,
	private val searchPageSizeValue: Int = 20,
	private val availableTagsSet: Set<MangaTag>,
	private val statusIdsByState: Map<MangaState, List<String>>,
	private val formatIdsByType: Map<ContentType, List<String>>,
	private val adultFormatIds: Set<String> = emptySet(),
) : PagedMangaParser(context, source, pageSize = latestPageSize, searchPageSize = searchPageSizeValue) {

	override val configKeyDomain = ConfigKey.Domain(domain)

	override val defaultSortOrder: SortOrder
		get() = SortOrder.UPDATED

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.POPULARITY,
		SortOrder.UPDATED,
		SortOrder.RELEVANCE,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isMultipleTagsSupported = true,
		)

	override fun getRequestHeaders() = super.getRequestHeaders().newBuilder()
		.add("Referer", "https://$domain/")
		.add("Accept-Language", "pt-BR, pt;q=0.9, en-US;q=0.8, en;q=0.7")
		.build()

	protected open fun getApiRequestHeaders(): Headers = getRequestHeaders()

	override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions(
		availableTags = availableTagsSet,
		availableStates = statusIdsByState.keys,
		availableContentTypes = formatIdsByType.keys,
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		return if (filter.isEmpty()) {
			when (order) {
				SortOrder.POPULARITY -> getPopularPage(page)
				else -> getLatestPage(page)
			}
		} else {
			search(page, filter)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val mangaId = manga.url.extractMangaId()
		val response = requestJson(apiUrl("obras/$mangaId"))
		val item = response.optJSONObject("obra")
			?: response.optJSONObject("data")
			?: response.optJSONObject("dados")
			?: response
		val parsed = parseManga(item) ?: manga

		return manga.copy(
			title = parsed.title.ifBlank { manga.title },
			url = parsed.url,
			publicUrl = parsed.publicUrl,
			coverUrl = parsed.coverUrl ?: manga.coverUrl,
			description = parsed.description ?: manga.description,
			tags = if (parsed.tags.isNotEmpty()) parsed.tags else manga.tags,
			state = parsed.state ?: manga.state,
			contentRating = parsed.contentRating ?: manga.contentRating,
			chapters = parseChapters(item, parsed.url.extractStoredSlug()).sortedBy { it.number },
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val response = requestJson(
			apiUrl("obras/${chapter.url.extractMangaId()}/capitulos/${chapter.url.extractChapterNumber().urlEncoded()}"),
		)
		val item = response.optJSONObject("capitulo")
			?: response.optJSONObject("data")
			?: response.optJSONObject("dados")
			?: response
		val pages = item.optJSONArray("paginas") ?: return emptyList()
		return pages.mapJSONNotNull { page ->
			val rawUrl = page.getStringOrNull("cdn_id")
				?: page.getStringOrNull("imagem")
				?: page.getStringOrNull("image")
				?: page.getStringOrNull("src")
				?: page.getStringOrNull("link")
				?: page.getStringOrNull("path")
				?: page.getStringOrNull("arquivo")
				?: return@mapJSONNotNull null
			val imageUrl = rawUrl.toAbsoluteCdnUrl()
			MangaPage(
				id = generateUid(imageUrl),
				url = imageUrl,
				preview = null,
				source = source,
			)
		}.sortedBy { it.url.substringAfterLast("/pagina_").substringBefore('.').toIntOrNull() ?: Int.MAX_VALUE }
	}

	private suspend fun getPopularPage(page: Int): List<Manga> {
		if (page > 1) return emptyList()
		val response = requestJson(apiUrl("obras/top10/views?periodo=total"))
		return response.optJSONArray("obras")
			?.mapJSONNotNull(::parseTopManga)
			.orEmpty()
	}

	private suspend fun getLatestPage(page: Int): List<Manga> {
		val response = requestJson(apiUrl("capitulos/recentes?pagina=$page&limite=$latestPageSize"))
		return response.optJSONArray("obras")
			?.mapJSONNotNull(::parseManga)
			?.distinctBy { it.url }
			.orEmpty()
	}

	private suspend fun search(page: Int, filter: MangaListFilter): List<Manga> {
		val limit = if (filter.query.isNullOrEmpty()) latestPageSize else searchPageSizeValue
		val url = buildString {
			append(apiUrl("obras?pagina=$page"))
			append("&limite=").append(limit)
			filter.query?.trim()?.takeIf { it.isNotEmpty() }?.let {
				append("&busca=").append(it.urlEncoded())
			}
			filter.tags.takeIf { it.isNotEmpty() }?.let {
				append("&tag_ids=").append(it.joinToString(",") { tag -> tag.key.urlEncoded() })
			}
			filter.states.takeIf { it.isNotEmpty() }?.firstOrNull()?.let { state ->
				statusIdsByState[state]?.takeIf { ids -> ids.isNotEmpty() }?.let { ids ->
					append("&status_id=").append(ids.joinToString(","))
				}
			}
			filter.types.takeIf { it.isNotEmpty() }?.firstOrNull()?.let { type ->
				formatIdsByType[type]?.takeIf { ids -> ids.isNotEmpty() }?.let { ids ->
					append("&formato_id=").append(ids.joinToString(","))
				}
			}
		}
		val response = requestJson(url)
		return response.optJSONArray("obras")
			?.mapJSONNotNull(::parseManga)
			.orEmpty()
	}

	private fun parseTopManga(json: JSONObject): Manga? {
		val id = json.getIntOrDefault("id", 0).takeIf { it > 0 } ?: return null
		val title = json.getStringOrNull("title") ?: json.getStringOrNull("nome") ?: return null
		val relativeUrl = buildInternalMangaUrl(id.toString(), null)
		return Manga(
			id = generateUid(id.toLong()),
			title = title,
			altTitles = emptySet(),
			url = relativeUrl,
			publicUrl = "https://$domain/$webMangaPathSegment/$id",
			rating = RATING_UNKNOWN,
			contentRating = ContentRating.SAFE,
			coverUrl = (json.getStringOrNull("coverImage") ?: json.getStringOrNull("imagem"))?.toAbsoluteCdnUrl(),
			tags = emptySet(),
			state = null,
			authors = emptySet(),
			description = null,
			source = source,
		)
	}

	private fun parseManga(json: JSONObject): Manga? {
		val id = json.getIntOrDefault("id", 0).takeIf { it > 0 } ?: return null
		val title = json.getStringOrNull("nome") ?: json.getStringOrNull("title") ?: return null
		val slug = json.getStringOrNull("slug")
		val tags = json.optJSONArray("tags").parseTags()
		val formatId = json.getStringOrNull("formato_id") ?: json.opt("formato_id")?.toString()
		return Manga(
			id = generateUid(id.toLong()),
			title = title,
			altTitles = emptySet(),
			url = buildInternalMangaUrl(id.toString(), slug),
			publicUrl = "https://$domain/$webMangaPathSegment/${slug ?: id}",
			rating = RATING_UNKNOWN,
			contentRating = if (formatId != null && formatId in adultFormatIds) ContentRating.ADULT else ContentRating.SAFE,
			coverUrl = (json.getStringOrNull("imagem") ?: json.getStringOrNull("coverImage"))?.toAbsoluteCdnUrl(),
			tags = tags,
			state = parseState(json),
			authors = emptySet(),
			description = json.getStringOrNull("descricao"),
			source = source,
		)
	}

	private fun parseChapters(json: JSONObject, slug: String): List<MangaChapter> {
		val mangaId = json.getIntOrDefault("id", 0).takeIf { it > 0 }?.toString() ?: return emptyList()
		return json.optJSONArray("capitulos")
			?.mapJSONNotNull { chapter ->
				val numberRaw = chapter.getStringOrNull("numero") ?: return@mapJSONNotNull null
				val numberFormatted = numberRaw.formatChapterNumber()
				MangaChapter(
					id = generateUid("${mangaId}_$numberFormatted"),
					title = chapter.getStringOrNull("nome")?.takeUnless { it.equals("Cap. $numberFormatted", ignoreCase = true) },
					number = numberRaw.toFloatOrNull() ?: 0f,
					volume = 0,
					url = buildInternalChapterUrl(mangaId, numberFormatted, slug),
					scanlator = null,
					uploadDate = parseApiDate(chapter.getStringOrNull("criado_em") ?: chapter.getStringOrNull("atualizado_em")),
					branch = null,
					source = source,
				)
			}
			.orEmpty()
	}

	private fun JSONArray?.parseTags(): Set<MangaTag> {
		if (this == null) return emptySet()
		return mapJSONNotNullToSet { tag ->
			val id = tag.getIntOrDefault("id", 0).takeIf { it > 0 } ?: return@mapJSONNotNullToSet null
			val title = tag.getStringOrNull("nome") ?: tag.getStringOrNull("name") ?: return@mapJSONNotNullToSet null
			MangaTag(
				key = id.toString(),
				title = title,
				source = source,
			)
		}
	}

	private fun parseState(json: JSONObject): MangaState? {
		val statusId = json.getIntOrDefault("status_id", -1)
		statusIdsByState.entries.firstOrNull { (_, ids) -> statusId.toString() in ids }?.let { return it.key }
		return when (json.getStringOrNull("status_nome")?.trim()?.lowercase(Locale.ROOT)) {
			"ativo", "em andamento" -> MangaState.ONGOING
			"concluido", "conclu\u00eddo" -> MangaState.FINISHED
			"hiato", "pausado" -> MangaState.PAUSED
			"cancelado" -> MangaState.ABANDONED
			else -> null
		}
	}

	private suspend fun requestJson(url: String): JSONObject {
		return webClient.httpGet(url, getApiRequestHeaders()).use { response ->
			val body = response.body.string()
			val parsedBody = if (response.headers["x-encrypted"].toBoolean()) {
				MangoThemeDecrypt.decrypt(body, encryptionKey)
			} else {
				body
			}
			JSONObject(parsedBody)
		}
	}

	private fun apiUrl(path: String): String = buildString {
		append(apiBaseUrl ?: "https://$domain/api")
		append('/')
		append(path.removePrefix("/"))
	}

	private fun String.toAbsoluteCdnUrl(): String = when {
		startsWith("http://") || startsWith("https://") -> this
		else -> "${cdnUrl.trimEnd('/')}/${removePrefix("/")}"
	}

	private fun buildInternalMangaUrl(mangaId: String, slug: String?): String = buildString {
		append("/obra/").append(mangaId)
		slug?.takeIf { it.isNotBlank() }?.let { append("?slug=").append(it) }
	}

	private fun buildInternalChapterUrl(mangaId: String, chapterNumber: String, slug: String?): String = buildString {
		append("/obra/").append(mangaId).append("/capitulo/").append(chapterNumber)
		slug?.takeIf { it.isNotBlank() }?.let { append("?slug=").append(it) }
	}

	private fun String.extractMangaId(): String = substringAfter("/obra/").substringBefore("/").substringBefore("?")

	private fun String.extractStoredSlug(): String = substringAfter("?slug=", "")
		.substringBefore('&')
		.nullIfEmpty()
		.orEmpty()

	private fun String.extractChapterNumber(): String = substringAfterLast('/').substringBefore('?')

	private fun String.formatChapterNumber(): String = toFloatOrNull()
		?.toString()
		?.removeSuffix(".0")
		?: this

	private companion object {
		private val apiDateFormatters = listOf(
			SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT),
			SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT),
		)
		private val dateOnlyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
		private val midnightOverflowRegex =
			Regex("""^(\d{4}-\d{2}-\d{2})T24:(\d{2}:\d{2}(?:\.\d{1,3})?)(.*)$""")

		private fun parseApiDate(dateString: String?): Long {
			val normalized = dateString?.normalizeMidnightOverflow() ?: return 0L
			return apiDateFormatters.firstNotNullOfOrNull { format ->
				format.parseSafe(normalized).takeIf { it != 0L }
			} ?: 0L
		}

		private fun String.normalizeMidnightOverflow(): String {
			val match = midnightOverflowRegex.matchEntire(this) ?: return this
			val parsedDate = dateOnlyFormat.parseSafe(match.groupValues[1]).takeIf { it != 0L } ?: return this
			val nextDay = Calendar.getInstance().apply {
				timeInMillis = parsedDate
				add(Calendar.DAY_OF_MONTH, 1)
			}
			return buildString {
				append(dateOnlyFormat.format(nextDay.time))
				append('T')
				append("00:")
				append(match.groupValues[2])
				append(match.groupValues[3])
			}
		}
	}
}
