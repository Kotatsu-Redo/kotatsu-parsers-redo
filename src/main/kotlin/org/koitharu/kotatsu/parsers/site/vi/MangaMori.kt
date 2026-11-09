package org.koitharu.kotatsu.parsers.site.vi

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.json.mapJSON
import java.time.Instant

@MangaSourceParser("MANGAMORI", "MangaMori", "vi")
internal class MangaMori(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.MANGAMORI, 12) {

	override val configKeyDomain = ConfigKey.Domain("mangamori.dev")
	override val availableSortOrders = setOf(SortOrder.UPDATED)
	override val filterCapabilities = MangaListFilterCapabilities(isSearchSupported = true)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override suspend fun getFilterOptions() = MangaListFilterOptions()

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val query = filter.query
		if (!query.isNullOrBlank()) {
			// Search returns all matches in one response, without pagination.
			if (page > 1) return emptyList()
			return webClient.httpGet("https://$domain/api/search?q=${query.urlEncoded()}")
				.parseJson().getJSONArray("data").mapJSON { it.toManga() }
		}
		val doc = webClient.httpGet("https://$domain/?page=$page").parseHtml()
		val flight = MangaMoriData.flight(doc)
		val pagination = MangaMoriData.props(flight, "currentPage").firstOrNull()
		if (pagination != null && page > pagination.getInt("totalPages")) return emptyList()
		val comics = (if (page == 1) MangaMoriData.objects(flight, "spotlightComic") else emptyList()) +
			MangaMoriData.objects(flight, "comic").filter { it.has("status") }
		if (comics.isEmpty() && page == 1) throw ParseException("Cannot find MangaMori catalogue", doc.location())
		return comics.distinctBy { it.getString("slug") }.map { it.toManga() }
	}

	private fun JSONObject.toManga(): Manga {
		val url = "/truyen/${getString("slug")}"
		return Manga(
			id = generateUid(url),
			url = url,
			publicUrl = url.toAbsoluteUrl(domain),
			title = getString("title"),
			altTitles = emptySet(),
			coverUrl = getStringOrNull("thumbnail")?.toAbsoluteUrl(domain),
			rating = RATING_UNKNOWN,
			contentRating = null,
			tags = emptySet(),
			state = when (optString("status")) {
				"ongoing" -> MangaState.ONGOING
				"completed" -> MangaState.FINISHED
				else -> null
			},
			authors = setOfNotNull(getStringOrNull("author")),
			description = getStringOrNull("description"),
			source = source,
		)
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val metadata = doc.select("script[type=application/ld+json]").asSequence()
			.map { JSONObject(it.data()) }.firstOrNull { it.optString("@type") == "Book" }
			?: throw ParseException("Cannot find MangaMori metadata", doc.location())
		val props = MangaMoriData.props(MangaMoriData.flight(doc), "comicSlug").firstOrNull { it.has("chapters") }
			?: throw ParseException("Cannot find MangaMori chapters", doc.location())
		val uploaders = props.optJSONObject("uploaderNames")
		val chapters = listOf("chapters" to "VietSub", "rawChapters" to "Raw").flatMap { (key, branch) ->
			props.getJSONArray(key).mapJSON { chapter ->
				val number = chapter.get("chapterNumber").toString().removeSuffix(".0")
				val url = "${manga.url}/$number" + if (branch == "Raw") "?mode=raw" else ""
				MangaChapter(
					id = generateUid(url),
					url = url,
					title = chapter.getStringOrNull("title"),
					number = number.toFloat(),
					volume = 0,
					uploadDate = chapter.getStringOrNull("createdAt")?.let { Instant.parse(it.removePrefix("\$D")).toEpochMilli() } ?: 0L,
					scanlator = uploaders?.getStringOrNull(chapter.optString("uploaderId"))
						?: props.getStringOrNull("fallbackUploaderName"),
					branch = branch,
					source = source,
				)
			}
		}.sortedBy { it.number }
		return manga.copy(
			title = metadata.getString("name"),
			coverUrl = metadata.getStringOrNull("image")?.toAbsoluteUrl(domain) ?: manga.coverUrl,
			description = metadata.getStringOrNull("description"),
			authors = setOfNotNull(metadata.optJSONObject("author")?.getStringOrNull("name")),
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
		val props = MangaMoriData.props(MangaMoriData.flight(doc), "comic")
			.firstOrNull { it.optJSONObject("chapter")?.has("pages") == true }
			?: throw ParseException("Cannot find MangaMori reader data", doc.location())
		return props.getJSONObject("chapter").getJSONArray("pages").mapJSON { it }
			.sortedBy { it.getInt("pageOrder") }.map { page ->
				val image = page.getString("imageUrl").toAbsoluteUrl(domain).toHttpUrl().newBuilder()
				if (page.optBoolean("isScrambled")) {
					val rows = page.getInt("scrambleRows")
					val cols = page.getInt("scrambleCols")
					val map = MangaMoriData.decodeMap(props.getString("encryptedMap"), props.getString("mapNonce"), rows, cols)
					// Fragments are not sent to the CDN, but survive page persistence and interceptor requests.
					image.fragment("mangamori=$rows,$cols;${map.joinToString(",")}")
				}
				MangaPage(
					id = generateUid(page.getString("id")),
					url = image.build().toString(),
					preview = null,
					source = source,
				)
			}
	}

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		val response = chain.proceed(request)
		val fragment = request.url.fragment?.takeIf { it.startsWith("mangamori=") } ?: return response
		if (!response.isSuccessful) return response
		val (grid, tiles) = fragment.removePrefix("mangamori=").split(';', limit = 2)
		val (rows, cols) = grid.split(',').map { it.toInt() }
		val map = tiles.split(',').map { it.toInt() }.toIntArray()
		MangaMoriData.validateMap(map, rows, cols)
		return context.redrawImageResponse(response) { bitmap ->
			MangaMoriData.redraw(bitmap, rows, cols, map, context::createBitmap)
		}
	}
}
