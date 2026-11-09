package org.koitharu.kotatsu.parsers.site.madara.pt

import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
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
import org.koitharu.kotatsu.parsers.util.json.mapJSON
import org.koitharu.kotatsu.parsers.util.json.mapJSONNotNull
import org.koitharu.kotatsu.parsers.util.oneOrThrowIfMany
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.parseSafe
import org.koitharu.kotatsu.parsers.util.toTitleCase
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale
import java.util.TimeZone

@MangaSourceParser("MAIDSCAN", "MaidScan", "pt")
internal class MaidScan(context: MangaLoaderContext) : PagedMangaParser(
	context,
	source = MangaParserSource.MAIDSCAN,
	pageSize = 24,
	searchPageSize = 15,
) {
	// empreguetes.xyz no longer completes a TLS handshake; the site is served
	// from verdinha.wtf, which is also where the CDN moved to — both
	// cdn.sussytoons.site and cdn.sussytoons.wtf now answer with a redirect to
	// HTML instead of the image.
	override val configKeyDomain = ConfigKey.Domain("verdinha.wtf", "empreguetes.xyz")
	private val apiUrl = "https://api.verdinha.wtf"
	private val cdnUrl = "https://cdn.verdinha.wtf"

	// Maid Scan's own scan id. The catalog it lists also carries works owned by
	// Verdinha (`scan_id` 1), whose covers and pages live under that scan's
	// folder on the CDN, so paths are built from each work's own `scan_id`.
	private val scanId = 3

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.POPULARITY_TODAY,
		SortOrder.POPULARITY_WEEK,
		SortOrder.POPULARITY_MONTH,
		SortOrder.RATING,
		SortOrder.NEWEST,
		SortOrder.ALPHABETICAL,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isMultipleTagsSupported = true,
		)

	override suspend fun getFilterOptions(): MangaListFilterOptions {
		return MangaListFilterOptions(
			availableTags = fetchAvailableTags(),
			availableStates = EnumSet.of(
				MangaState.ONGOING,
				MangaState.FINISHED,
				MangaState.PAUSED,
				MangaState.ABANDONED,
			),
			availableContentTypes = EnumSet.of(
				ContentType.MANGA,
				ContentType.MANHUA,
				ContentType.MANHWA,
				ContentType.HENTAI,
			),
		)
	}

	private val apiHeaders: Headers
		get() = Headers.Builder()
			.add("Referer", "https://$domain/")
			.add("scan-id", scanId.toString())
			.build()

	// `cap_criado_em` is ISO-8601 in UTC, e.g. "2026-07-19T19:57:52.501Z".
	private val chapterDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
		timeZone = TimeZone.getTimeZone("UTC")
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		// Views over a period only come from the ranking endpoint, which ignores
		// the query and filters; with those set, fall back to all-time views.
		val rankingType = when (order) {
			SortOrder.POPULARITY_TODAY -> "visualizacoes_hoje"
			SortOrder.POPULARITY_WEEK -> "visualizacoes_semana"
			SortOrder.POPULARITY_MONTH -> "visualizacoes_mes"
			else -> null
		}
		val url = if (rankingType != null && filter.isEmpty()) {
			"$apiUrl/obras/ranking".toHttpUrl().newBuilder()
				.addQueryParameter("tipo", rankingType)
				.addQueryParameter("pagina", page.toString())
				.addQueryParameter("limite", pageSize.toString())
				.build()
		} else {
			buildSearchUrl(page, filter, order)
		}

		val response = webClient.httpGet(url, apiHeaders).parseJson()
		val results = response.optJSONArray("obras") ?: return emptyList()
		return results.mapJSON { parseMangaFromJson(it) }
	}

	private fun buildSearchUrl(page: Int, filter: MangaListFilter, order: SortOrder): HttpUrl {
		val builder = "$apiUrl/obras/search".toHttpUrl().newBuilder()
			.addQueryParameter("pagina", page.toString())
			.addQueryParameter("limite", pageSize.toString())
			.addQueryParameter("todos_generos", "1")

		// Add search query
		if (!filter.query.isNullOrEmpty()) {
			builder.addQueryParameter("obr_nome", filter.query)
		}

		// Add sorting. The API ignores unknown `orderBy` values (the old
		// "media_rating" silently gave newest first) and then sorts by creation.
		val (orderBy, direction) = when (order) {
			SortOrder.POPULARITY, SortOrder.POPULARITY_TODAY,
			SortOrder.POPULARITY_WEEK, SortOrder.POPULARITY_MONTH -> "visualizacoes_geral" to "DESC"
			SortOrder.RATING -> "rating" to "DESC"
			SortOrder.NEWEST -> "criacao" to "DESC"
			SortOrder.ALPHABETICAL -> "nome" to "ASC"
			else -> "ultima_atualizacao" to "DESC"
		}
		builder.addQueryParameter("orderBy", orderBy)
		builder.addQueryParameter("orderDirection", direction)

		// Add tags
		filter.tags.forEach { tag ->
			builder.addQueryParameter("tags[]", tag.key)
		}

		// Add format (content type)
		filter.types.firstOrNull()?.let { contentType ->
			val type = when (contentType) {
				ContentType.MANHWA -> "1"
				ContentType.MANHUA -> "2"
				ContentType.MANGA -> "3"
				ContentType.HENTAI -> "5"
				else -> null
			}
			type?.let { builder.addQueryParameter("formt_id", it) }
		}

		// Add status: Maid Scan's own status ids, as listed by /obras/filtros
		// (1-4 are Verdinha's and only match the works borrowed from it).
		filter.states.firstOrNull()?.let { state ->
			val statusId = when (state) {
				MangaState.ONGOING -> "10"
				MangaState.FINISHED -> "12"
				MangaState.PAUSED -> "11"
				MangaState.ABANDONED -> "13"
				else -> null
			}
			statusId?.let { builder.addQueryParameter("stt_id", it) }
		}

		return builder.build()
	}

	private fun parseMangaFromJson(json: JSONObject): Manga {
		val id = json.getInt("obr_id")
		val name = json.getString("obr_nome")
		val slug = json.optString("obr_slug", "").ifEmpty {
			name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
		}
		val coverPath = json.optString("obr_imagem", "").takeIf { it != "null" && it.isNotEmpty() } ?: ""
		val workScanId = json.optInt("scan_id").takeIf { it > 0 } ?: scanId

		val largeCoverUrl = when {
			coverPath.isEmpty() -> null
			coverPath.startsWith("http") -> coverPath
			coverPath.startsWith("wp-content") -> "$cdnUrl/$coverPath"
			else -> "$cdnUrl/scans/$workScanId/obras/$id/$coverPath"
		}
		// The site's lists use the API's resized copy instead of the full-size file.
		val coverUrl = if (largeCoverUrl != null && largeCoverUrl.startsWith("$cdnUrl/scans/")) {
			"$apiUrl/cdn/scans/$workScanId/obras/$id/$coverPath?width=300"
		} else {
			largeCoverUrl
		}

		// Get genre information
		val genero = json.optJSONObject("genero")
		val genreName = genero?.optString("gen_nome", "") ?: ""
		val isNsfw = genreName.equals("hentai", ignoreCase = true)

		val rating = json.optDouble("media_rating", 0.0).let {
			if (it > 0) (it / 5.0).toFloat() else RATING_UNKNOWN
		}

		return Manga(
			id = generateUid(id.toLong()),
			title = name,
			url = "/obras/$slug",
			publicUrl = "https://$domain/obras/$slug",
			coverUrl = coverUrl,
			source = source,
			rating = rating,
			altTitles = emptySet(),
			contentRating = if (isNsfw) ContentRating.ADULT else ContentRating.SAFE,
			tags = emptySet(),
			state = null,
			authors = emptySet(),
			largeCoverUrl = largeCoverUrl,
			description = null,
			chapters = null,
		)
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val mangaSlug = manga.url.substringAfter("/obras/")
		val mangaJson = webClient.httpGet("$apiUrl/obras/$mangaSlug", apiHeaders).parseJson()

		val obraId = mangaJson.getInt("obr_id")

		val description = mangaJson.optString("obr_descricao")
			.replace(Regex("</?strong>"), "")
			.replace("\\/", "/")
			.replace(Regex("\\s+"), " ")
			.trim()

		val status = mangaJson.optJSONObject("status")
			?.optString("stt_nome")
			?.let { parseStatus(it) }

		val tags = mangaJson.optJSONArray("tags")?.mapJSON { tagJson ->
			val tagName = tagJson.optString("tag_nome").ifEmpty {
				tagJson.optString("nome", "")
			}
			val tagId = tagJson.optInt("tag_id").takeIf { it != 0 }
				?: tagJson.optInt("id", 0)
			MangaTag(
				key = tagId.toString(),
				title = tagName.toTitleCase(),
				source = source,
			)
		}?.toSet() ?: emptySet()

		// Some works list a number twice (a re-upload, or an uncensored edition
		// next to the censored one); keep the newest upload of each number.
		val chapters = mangaJson.optJSONArray("capitulos")?.mapJSON { chapterJson ->
			parseChapter(chapterJson, obraId)
		}.orEmpty()
			.groupBy { it.number }
			.map { (_, sameNumber) -> sameNumber.maxBy { it.uploadDate } }

		return manga.copy(
			title = mangaJson.optString("obr_nome", manga.title),
			description = description,
			state = status,
			tags = tags,
			chapters = chapters,
		)
	}

	private fun parseChapter(json: JSONObject, obraId: Int): MangaChapter {
		val chapterId = json.getInt("cap_id")
		val chapterName = json.getString("cap_nome")
		val chapterNumber = json.optDouble("cap_numero").toFloat()
		// Chapters that have not been unlocked yet still show up in the list.
		val isLocked = !json.optBoolean("cap_liberado", true)

		return MangaChapter(
			id = generateUid(chapterId.toLong()),
			title = if (isLocked) "🔒 $chapterName" else chapterName,
			number = chapterNumber,
			url = "/capitulo/$chapterId/$obraId",
			uploadDate = chapterDateFormat.parseSafe(json.optString("cap_criado_em")),
			source = source,
			volume = 0,
			scanlator = null,
			branch = null,
		)
	}

	private fun parseStatus(status: String): MangaState? = when (status.lowercase()) {
		"ativo", "em andamento" -> MangaState.ONGOING
		"completo", "concluído" -> MangaState.FINISHED
		"hiato", "pausado" -> MangaState.PAUSED
		"cancelado", "abandonado" -> MangaState.ABANDONED
		else -> null
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val urlParts = chapter.url.substringAfter("/capitulo/").split("/")
		val chapterId = urlParts[0]

		// Fetch chapter data from API
		val chapterData = webClient.httpGet("$apiUrl/capitulos/$chapterId", apiHeaders).parseJson()

		// The work may belong to another scan (Verdinha's are under "scans/1"),
		// so take its ids from the chapter rather than assuming Maid Scan's.
		val obra = chapterData.optJSONObject("obra")
		val obraId = obra?.optInt("obr_id")?.takeIf { it > 0 }
			?: urlParts.getOrNull(1)?.toIntOrNull() ?: 0
		val workScanId = obra?.optInt("scan_id")?.takeIf { it > 0 } ?: scanId
		// The CDN folder is the chapter number as written: "58", "0.5".
		val capNumero = chapterData.optDouble("cap_numero").takeUnless { it.isNaN() }?.let {
			if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString()
		}

		// Parse pages from the response
		val pagesArray = chapterData.optJSONArray("cap_paginas")
			?: throw Exception("No pages found in chapter")

		return pagesArray.mapJSONNotNull { pageJson ->
			val pageSrc = pageJson.optString("src")

			if (pageSrc.isEmpty()) return@mapJSONNotNull null

			// The page's `path` is not used: on some works it is the full file path,
			// on others only the folder, spelled "38_5" where the CDN has "38.5".
			val imageUrl = when {
				// Already a full URL
				pageSrc.startsWith("http") -> pageSrc
				// WordPress manga path, looks like: "manga_.../hash/001.webp"
				pageSrc.startsWith("manga_") -> "$cdnUrl/wp-content/uploads/WP-manga/data/$pageSrc"
				// WordPress legacy path: "wp-content/uploads/..."
				pageSrc.startsWith("wp-content") -> "$cdnUrl/$pageSrc"
				obraId > 0 && capNumero != null -> "$cdnUrl/scans/$workScanId/obras/$obraId/capitulos/$capNumero/$pageSrc"
				else -> "$cdnUrl/$pageSrc"
			}

			MangaPage(
				id = generateUid(imageUrl),
				url = imageUrl,
				source = source,
				preview = null,
			)
		}
	}

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		// The endpoint pages by 10 by default, which hid most of the tags.
		val url = "$apiUrl/tags".toHttpUrl().newBuilder()
			.addQueryParameter("limite", "200")
			.build()

		val response = webClient.httpGet(url, apiHeaders).parseJson()
		// The payload keys this list "tags"; "resultados" never existed and left
		// the tag filter permanently empty.
		val tagsArray = response.optJSONArray("tags") ?: return emptySet()

		return tagsArray.mapJSONNotNull { tagJson ->
			// Several tags are defined but not used by any work yet.
			if (tagJson.optInt("total_obras", 1) == 0) return@mapJSONNotNull null
			val tagName = tagJson.optString("tag_nome").ifEmpty {
				tagJson.optString("nome", "")
			}
			val tagId = tagJson.optInt("tag_id").takeIf { it != 0 }
				?: tagJson.optInt("id", 0)
			MangaTag(
				key = tagId.toString(),
				title = tagName.toTitleCase(),
				source = source,
			)
		}.toSet()
	}
}
