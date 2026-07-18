package org.koitharu.kotatsu.parsers.site.id

import okhttp3.Headers
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.time.Instant
import java.util.*

internal abstract class BaseDoujinDesuParser(
	context: MangaLoaderContext,
	source: MangaParserSource
) : PagedMangaParser(context, source, pageSize = 18) {

	protected abstract val defaultTypes: String

	protected abstract val availableContentTypes: Set<ContentType>

	override val configKeyDomain: ConfigKey.Domain
		get() = ConfigKey.Domain("doujindesu.tv", "doujindesu.xxx", "doujin.desu.xxx")

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
		keys.add(ConfigKey.InterceptCloudflare(defaultValue = true))
	}

	override val defaultSortOrder: SortOrder
		get() = SortOrder.UPDATED

	override val availableSortOrders: Set<SortOrder>
		get() = EnumSet.of(SortOrder.UPDATED, SortOrder.NEWEST, SortOrder.ALPHABETICAL, SortOrder.POPULARITY)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isMultipleTagsSupported = true,
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isAuthorSearchSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchAvailableTags(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
		availableContentTypes = availableContentTypes,
	)

	override fun getRequestHeaders(): Headers = Headers.Builder()
		.add("X-Requested-With", "XMLHttpRequest")
		.add("Referer", "https://$domain/")
		.add("X-App-Secret", "dfdf72051dbfdc7d76889ebd31324e74")
		.build()

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val limit = pageSize
		val offset = (page - 1) * limit

		val url = urlBuilder().apply {
			addPathSegment("api")
			addPathSegment("manga")

			addQueryParameter("limit", limit.toString())
			addQueryParameter("offset", offset.toString())

			filter.query?.let {
				addQueryParameter("search", it)
			}

			filter.author?.let {
				addQueryParameter("author", it.replace(' ', '-').lowercase())
			}

			filter.tags.forEach {
				addQueryParameter("genres[]", it.key)
			}

			val sortParam = when (order) {
				SortOrder.POPULARITY -> "popular"
				SortOrder.ALPHABETICAL -> "title"
				SortOrder.NEWEST -> "latest"
				SortOrder.UPDATED -> "latest_chapter"
				else -> "latest_chapter"
			}
			addQueryParameter("sort", sortParam)

			val typeParam = filter.types.oneOrThrowIfMany()?.let {
				when (it) {
					ContentType.MANGA -> "manga"
					ContentType.MANHWA -> "manhwa"
					ContentType.DOUJINSHI -> "doujinshi"
					else -> ""
				}
			} ?: defaultTypes

			if (typeParam.isNotEmpty()) {
				addQueryParameter("type", typeParam)
			}

			filter.states.oneOrThrowIfMany()?.let {
				val stateParam = when (it) {
					MangaState.ONGOING -> "ongoing"
					MangaState.FINISHED -> "completed"
					else -> ""
				}
				if (stateParam.isNotEmpty()) {
					addQueryParameter("status", stateParam)
				}
			}
		}.build()

		val jsonResponse = webClient.httpGet(url, extraHeaders = getRequestHeaders()).parseJson()
		val encHex = jsonResponse.getString("_enc_resp_")
		val decrypted = decrypt(encHex)

		val array = JSONArray(decrypted)

		val list = mutableListOf<Manga>()
		for (i in 0 until array.length()) {
			val obj = array.getJSONObject(i)
			val slug = obj.getString("slug")
			val href = "/manga/$slug"
			list.add(
				Manga(
					id = generateUid(href),
					title = obj.getString("title"),
					altTitles = emptySet(),
					url = href,
					publicUrl = href.toAbsoluteUrl(domain),
					rating = obj.optDouble("rating", 0.0).toFloat() / 10f,
					contentRating = ContentRating.ADULT,
					coverUrl = obj.optString("cover_url").takeIf { it.isNotEmpty() }?.toAbsoluteUrl(domain)?.let { cover ->
						if (cover.contains("doujin")) {
							cover.replace(Regex("https?://[^/]+"), "https://$domain")
						} else {
							cover
						}
					},
					tags = emptySet(),
					state = null,
					authors = emptySet(),
					largeCoverUrl = null,
					description = null,
					source = source,
				)
			)
		}
		return list
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val slug = manga.url.removePrefix("/manga/").removeSuffix("/")
		val url = "/api/manga/$slug".toAbsoluteUrl(domain)

		val jsonResponse = webClient.httpGet(url, extraHeaders = getRequestHeaders()).parseJson()
		val encHex = jsonResponse.getString("_enc_resp_")
		val decrypted = decrypt(encHex)
		val obj = JSONObject(decrypted)

		val state = when (obj.optString("status")) {
			"completed" -> MangaState.FINISHED
			"ongoing" -> MangaState.ONGOING
			else -> null
		}

		val author = obj.optString("author").takeIf { it.isNotEmpty() && it != "null" }

		val tags = mutableSetOf<MangaTag>()
		val genresArray = obj.optJSONArray("manga_genres")
		if (genresArray != null) {
			for (i in 0 until genresArray.length()) {
				val genreObj = genresArray.getJSONObject(i).getJSONObject("genres")
				tags.add(
					MangaTag(
						key = genreObj.getString("slug"),
						title = genreObj.getString("name"),
						source = source
					)
				)
			}
		}

		val chaptersArray = obj.getJSONArray("chapters")
		val chapters = mutableListOf<MangaChapter>()
		for (i in 0 until chaptersArray.length()) {
			val chapObj = chaptersArray.getJSONObject(i)
			val chId = chapObj.getString("id")
			val chNum = chapObj.optDouble("chapter_number", 0.0).toFloat()
			val chTitle = chapObj.optString("title").takeIf { it.isNotEmpty() } ?: "Chapter $chNum"
			val chUrl = "/reader/$chId"
			val createdAt = chapObj.optString("created_at")
			val uploadDate = runCatching { Instant.parse(createdAt).toEpochMilli() }.getOrDefault(0L)

			chapters.add(
				MangaChapter(
					id = generateUid(chUrl),
					title = chTitle,
					number = chNum,
					volume = 0,
					url = chUrl,
					scanlator = null,
					uploadDate = uploadDate,
					branch = null,
					source = source
				)
			)
		}

		val rawDesc = obj.optString("description").takeIf { it.isNotEmpty() && it != "null" }
		val cleanDesc = rawDesc?.let { html ->
			org.jsoup.Jsoup.parseBodyFragment(html).text()
				.replace(Regex("^Sinopsis:\\s*", RegexOption.IGNORE_CASE), "")
				.trim()
		}

		val coverUrl = obj.optString("cover_url").takeIf { it.isNotEmpty() }?.toAbsoluteUrl(domain)?.let { cover ->
			if (cover.contains("doujin")) {
				cover.replace(Regex("https?://[^/]+"), "https://$domain")
			} else {
				cover
			}
		}

		return manga.copy(
			authors = setOfNotNull(author),
			description = cleanDesc,
			state = state,
			rating = obj.optDouble("rating", 0.0).toFloat() / 10f,
			tags = tags,
			coverUrl = coverUrl ?: manga.coverUrl,
			chapters = chapters.reversed()
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val chId = chapter.url.removePrefix("/reader/").removeSuffix("/")
		val url = "/api/chapters/$chId".toAbsoluteUrl(domain)

		val jsonResponse = webClient.httpGet(url, extraHeaders = getRequestHeaders()).parseJson()
		val encHex = jsonResponse.getString("_enc_resp_")
		val decrypted = decrypt(encHex)
		val obj = JSONObject(decrypted)

		val pagesList = mutableListOf<MangaPage>()
		val contentUrls = obj.optJSONArray("content_urls")
		if (contentUrls != null && contentUrls.length() > 0) {
			for (i in 0 until contentUrls.length()) {
				val pageUrl = contentUrls.getString(i)
				pagesList.add(
					MangaPage(
						id = generateUid(pageUrl),
						url = pageUrl,
						preview = null,
						source = source
					)
				)
			}
		} else {
			val signedUrlsStr = obj.optString("signed_content_urls")
			if (signedUrlsStr.isNotEmpty()) {
				val signedUrls = JSONArray(signedUrlsStr)
				for (i in 0 until signedUrls.length()) {
					val pageUrl = signedUrls.getString(i)
					pagesList.add(
						MangaPage(
							id = generateUid(pageUrl),
							url = pageUrl,
							preview = null,
							source = source
						)
					)
				}
			}
		}

		return pagesList
	}

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		val url = "/api/terms?taxonomy=genre".toAbsoluteUrl(domain)
		val jsonResponse = webClient.httpGet(url, extraHeaders = getRequestHeaders()).parseJson()
		val encHex = jsonResponse.getString("_enc_resp_")
		val decrypted = decrypt(encHex)
		val array = JSONArray(decrypted)
		val tags = mutableSetOf<MangaTag>()
		for (i in 0 until array.length()) {
			val obj = array.getJSONObject(i)
			val name = obj.getString("name")
			val slug = obj.getString("slug")
			tags.add(MangaTag(key = slug, title = name, source = source))
		}
		return tags
	}

	private fun generateKey(step: Long): String {
		val input = "doujindesu-scrapers-cannot-read-this-super-secret-salt-2026-v2_$step"
		var n = 0
		for (i in 0 until input.length) {
			n = (n shl 5) - n + input[i].code
		}
		var seed = if (n == 0) 123456789L else kotlin.math.abs(n.toLong())
		val keyBuilder = StringBuilder()
		for (i in 0 until 32) {
			seed = (seed * 1664525L + 1013904223L) and 0xFFFFFFFFL
			val charCode = 33 + (seed % 93).toInt()
			keyBuilder.append(charCode.toChar())
		}
		return keyBuilder.toString()
	}

	private fun decrypt(encHex: String): String {
		val timeStep = 3600000L
		val now = System.currentTimeMillis()
		val currentStep = now / timeStep

		var lastError: Exception? = null
		for (offset in intArrayOf(0, -1, 1)) {
			val step = currentStep + offset
			val key = generateKey(step)
			try {
				val encBytes = ByteArray(encHex.length / 2) { i ->
					encHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
				}
				val decBytes = ByteArray(encBytes.size)
				var c = 42
				for (i in encBytes.indices) {
					val p = encBytes[i].toInt() and 0xFF
					val f = key[i % key.length].code
					val k = p xor f xor (i * 13) xor c
					decBytes[i] = (k and 0xFF).toByte()
					c = (c + p) % 256
				}
				val decoded = String(decBytes, Charsets.UTF_8)
				return java.net.URLDecoder.decode(decoded, "UTF-8")
			} catch (e: Exception) {
				lastError = e
			}
		}
		throw lastError ?: RuntimeException("Decryption failed")
	}
}

@MangaSourceParser("DOUJINDESU", "DoujinDesu", "id")
internal class DoujinDesuParser(context: MangaLoaderContext) :
	BaseDoujinDesuParser(context, MangaParserSource.DOUJINDESU) {

	override val defaultTypes: String = "doujinshi,manga"

	override val availableContentTypes: Set<ContentType> = EnumSet.of(
		ContentType.MANGA,
		ContentType.DOUJINSHI,
	)
}
