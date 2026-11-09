package org.koitharu.kotatsu.parsers.site.vi

import okhttp3.Headers
import okhttp3.HttpUrl
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
import org.koitharu.kotatsu.parsers.util.LinkResolver
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.json.mapJSON
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.toTitleCase
import java.time.OffsetDateTime
import java.util.EnumSet

@MangaSourceParser("CUUTRUYEN", "Cứu Truyện", "vi")
internal class CuuTruyenParser(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.CUUTRUYEN, 24) {

	override val configKeyDomain = ConfigKey.Domain("cuutruyen.net")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.UPDATED, SortOrder.POPULARITY)

	override val filterCapabilities = MangaListFilterCapabilities(
		isSearchSupported = true,
		isSearchWithFiltersSupported = true,
		isMultipleTagsSupported = true,
	)

	override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
		.add("Cuutruyen-Client", "OfficialWebApp-20250805")
		.add("Referer", "https://$domain/")
		.build()

	private val availableTags = suspendLazy(soft = true) {
		val data = webClient.httpGet(api("tags/popular"), getRequestHeaders()).parseJson().getJSONObject("data")
		listOf("common_tags", "warning_tags", "normal_tags").flatMap { group ->
			data.getJSONArray(group).mapJSON { it.toTag() }
		}.toSet()
	}

	override suspend fun getFilterOptions() = MangaListFilterOptions(availableTags = availableTags.get())

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val searching = !filter.query.isNullOrBlank() || filter.tags.isNotEmpty()
		val endpoint = when {
			searching -> "mangas/search"
			order == SortOrder.POPULARITY -> "mangas/top"
			else -> "mangas/recently_updated"
		}
		val url = api(endpoint).toHttpUrl().newBuilder()
			.addQueryParameter("page", page.toString())
			.addQueryParameter("per_page", "24")
			.apply {
				if (searching) {
					addQueryParameter("q", filter.query.orEmpty().trim())
					addQueryParameter("tags", filter.tags.joinToString(" AND ") { "\"${it.key}\"" })
				} else if (order == SortOrder.POPULARITY) {
					addQueryParameter("duration", "all")
				}
			}.build()
		return webClient.httpGet(url, getRequestHeaders()).parseJson().getJSONArray("data").mapJSON { it.toManga() }
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val path = resolveMangaPath(manga)
		val data = webClient.httpGet(path.toAbsoluteUrl(domain), getRequestHeaders()).parseJson().getJSONObject("data")
		val tags = data.getJSONArray("tags").mapJSON { it.toTag() }.toSet()
		val scanlator = data.optJSONObject("team")?.getStringOrNull("name")
		val chapters = webClient.httpGet("${path.toAbsoluteUrl(domain)}/chapters", getRequestHeaders()).parseJson()
			.getJSONArray("data").mapJSON { it }.sortedBy { it.getInt("order") }.map { chapter ->
				val chapterId = chapter.getLong("id")
				val chapterPath = "/api/v2/chapters/$chapterId"
				val number = chapter.getStringOrNull("number")
				MangaChapter(
					// Numeric ids, as the original API parser made them, keep saved history valid.
					id = generateUid(chapterId),
					url = chapterPath,
					title = listOfNotNull(
						number?.takeIf { it.toFloatOrNull() == null },
						chapter.getStringOrNull("name"),
					).joinToString(" - ").ifEmpty { null },
					number = number?.toFloatOrNull() ?: 0f,
					volume = 0,
					uploadDate = chapter.getStringOrNull("created_at")?.let {
						OffsetDateTime.parse(it).toInstant().toEpochMilli()
					} ?: 0L,
					scanlator = scanlator,
					branch = null,
					source = source,
				)
			}
		return manga.copy(
			title = data.getString("name"),
			publicUrl = "/mangas/${data.getLong("id")}".toAbsoluteUrl(domain),
			coverUrl = (data.getStringOrNull("cover_mobile_url") ?: data.getStringOrNull("cover_url"))?.storageUrl(),
			largeCoverUrl = data.getStringOrNull("cover_url")?.storageUrl(),
			altTitles = data.getJSONArray("titles").mapJSON { it.getString("name") }
				.filterNot { it == data.getString("name") }.toSet(),
			authors = setOfNotNull(data.optJSONObject("author")?.getStringOrNull("name")),
			description = data.getStringOrNull("full_description") ?: data.getStringOrNull("description"),
			tags = tags,
			state = when {
				tags.any { it.key == "đã hoàn thành" } -> MangaState.FINISHED
				tags.any { it.key == "tạm ngưng" } -> MangaState.PAUSED
				tags.any { it.key == "đang tiến hành" } -> MangaState.ONGOING
				else -> null
			},
			contentRating = if (data.optBoolean("is_nsfw")) ContentRating.ADULT else null,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val path = chapter.url.toApiPath("chapters")
		val data = webClient.httpGet(path.toAbsoluteUrl(domain), getRequestHeaders()).parseJson().getJSONObject("data")
		val pages = data.getJSONArray("pages").mapJSON { it }.sortedBy { it.getInt("order") }
		if (pages.isEmpty()) throw ParseException("Chapter has no readable pages", chapter.url)
		return pages.map { page ->
			val image = page.getString("image_url").storageUrl().toHttpUrl().newBuilder()
			page.getStringOrNull("drm_data")?.takeIf { it.isNotBlank() }?.let {
				// Persist the map with the page URL, without sending it to the image server.
				image.fragment("cuutruyen=" + CuuTruyenDrm.decode(context.decodeBase64(it.filterNot(Char::isWhitespace))).joinToString("|") { strip ->
					"${strip.first}-${strip.second}"
				})
			}
			MangaPage(
				id = generateUid(page.getLong("id")),
				url = image.build().toString(),
				preview = null,
				source = source,
			)
		}
	}

	override fun intercept(chain: Interceptor.Chain): Response {
		val original = chain.request()
		val request = STORAGE_HOSTS[original.url.host]?.let { host ->
			original.newBuilder().url(original.url.newBuilder().host(host).build()).build()
		} ?: original
		val response = chain.proceed(request)
		val map = request.url.fragment?.takeIf { it.startsWith("cuutruyen=") } ?: return response
		if (!response.isSuccessful) return response
		return context.redrawImageResponse(response) { bitmap ->
			CuuTruyenDrm.redraw(bitmap, CuuTruyenDrm.parse(map.removePrefix("cuutruyen=")), context::createBitmap)
		}
	}

	override suspend fun getRelatedManga(seed: Manga): List<Manga> =
		webClient.httpGet("${resolveMangaPath(seed).toAbsoluteUrl(domain)}/similar", getRequestHeaders()).parseJson()
			.getJSONArray("data").mapJSON { it.toManga() }

	override suspend fun resolveLink(resolver: LinkResolver, link: HttpUrl): Manga? {
		if (link.host != domain) return null
		val match = MANGA_PATH.matchEntire(link.encodedPath.trimEnd('/')) ?: return null
		return getDetails(JSONObject().put("id", match.groupValues[1].toLong()).put("name", "").toManga())
	}

	private fun JSONObject.toTag() = MangaTag(
		key = getString("name"),
		title = getString("name").toTitleCase(sourceLocale),
		source = source,
	)

	private fun JSONObject.toManga(): Manga {
		val mangaId = getLong("id")
		val path = "/api/v2/mangas/$mangaId"
		return Manga(
			// Numeric ids, as the original API parser made them, keep saved favourites valid.
			id = generateUid(mangaId),
			url = path,
			publicUrl = "/mangas/${getLong("id")}".toAbsoluteUrl(domain),
			title = getString("name"),
			altTitles = emptySet(),
			coverUrl = (getStringOrNull("cover_mobile_url") ?: getStringOrNull("cover_url"))?.storageUrl(),
			largeCoverUrl = getStringOrNull("cover_url")?.storageUrl(),
			authors = setOfNotNull(getStringOrNull("author_name")),
			tags = emptySet(),
			state = null,
			description = null,
			contentRating = null,
			source = source,
			rating = RATING_UNKNOWN,
		)
	}

	private suspend fun resolveMangaPath(manga: Manga): String {
		if (!manga.url.startsWith("/truyen/")) return manga.url.toApiPath("mangas")
		// Entries from the unrelated .moe catalogue have no shared IDs. Only migrate an unambiguous exact title.
		val matches = getListPage(1, SortOrder.UPDATED, MangaListFilter(query = manga.title))
			.filter { it.title.trim().equals(manga.title.trim(), ignoreCase = true) }
		return matches.singleOrNull()?.url
			?: throw ParseException("Cannot uniquely match saved manga on CuuTruyen.net", manga.publicUrl)
	}

	private fun String.toApiPath(resource: String): String {
		val path = if (startsWith("http")) toHttpUrl().encodedPath else this
		val id = path.trimEnd('/').substringAfterLast('/').toLongOrNull()
			?: throw ParseException("Invalid CuuTruyen $resource URL", this)
		return "/api/v2/$resource/$id"
	}

	private fun api(path: String) = "https://$domain/api/v2/$path"

	private fun String.storageUrl(): String {
		val url = toHttpUrl()
		val host = STORAGE_HOSTS[url.host] ?: return this
		return url.newBuilder().host(host).build().toString()
	}

	private companion object {
		// The official client rewrites these retired API storage hosts before loading images.
		val STORAGE_HOSTS = mapOf(
			"storage-ct.lrclib.net" to "storage-bravo.cuutruyen.net",
			"storage-ct-riften.site" to "storage-charlie.cuutruyen.net",
		)
		val MANGA_PATH = Regex("/(?:api/v2/)?mangas/(\\d+)(?:/chapters/\\d+)?")
	}
}
