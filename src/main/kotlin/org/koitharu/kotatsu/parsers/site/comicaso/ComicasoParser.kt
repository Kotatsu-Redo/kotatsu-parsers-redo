package org.koitharu.kotatsu.parsers.site.comicaso

import okhttp3.Headers
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.HttpStatusException
import org.jsoup.Jsoup
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.AuthRequiredException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.network.UserAgents
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import java.util.*

internal abstract class ComicasoParser(
	context: MangaLoaderContext,
	source: MangaParserSource,
	domain: String,
	pageSize: Int = 20,
) : PagedMangaParser(context, source, pageSize, pageSize) {

	override val configKeyDomain = ConfigKey.Domain(domain)

	override val userAgentKey = ConfigKey.UserAgent(UserAgents.CHROME_MOBILE)

	override val sourceLocale: Locale = Locale("id")

	protected abstract val apiSource: String

	protected open val loginRequired: Boolean = false

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.NEWEST,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isMultipleTagsSupported = false,
			isTagsExclusionSupported = false,
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
		)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override fun getRequestHeaders(): Headers = Headers.Builder()
		.add("User-Agent", config[userAgentKey])
		.add("Accept", "application/json, text/plain, */*")
		.add("Referer", "https://$domain/")
		.add("X-Requested-With", "XMLHttpRequest")
		.build()

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchAvailableTags(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
		availableContentTypes = EnumSet.of(
			ContentType.MANGA,
			ContentType.MANHWA,
			ContentType.MANHUA,
		),
	)

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		val url = "https://$domain/api/genres.php?source=$apiSource"
		val json = apiGetJson(url)
		val arr = json.optJSONObject("data")?.optJSONArray(apiSource) ?: return emptySet()
		val result = LinkedHashSet<MangaTag>(arr.length())
		for (i in 0 until arr.length()) {
			val item = arr.getJSONObject(i)
			val genre = item.optString("genre").takeIf { it.isNotBlank() } ?: continue
			val slug = item.optString("genre_slug").ifBlank { genre.lowercase(sourceLocale) }
			result.add(
				MangaTag(
					key = slug,
					title = genre.toTitleCase(sourceLocale),
					source = source,
				),
			)
		}
		return result
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val offset = (page - 1) * pageSize

		val mode = when (order) {
			SortOrder.NEWEST -> "new"
			else -> "update"
		}

		val typeParam = filter.types.oneOrThrowIfMany()?.let { type ->
			when (type) {
				ContentType.MANGA -> "manga"
				ContentType.MANHWA -> "manhwa"
				ContentType.MANHUA -> "manhua"
				else -> "all"
			}
		} ?: "all"

		val genreParam = filter.tags.oneOrThrowIfMany()?.key ?: ""
		val query = filter.query.orEmpty()

		val url = buildString {
			append("https://$domain/api/home.php")
			append("?source=").append(apiSource)
			append("&q=").append(query.urlEncoded())
			append("&mode=").append(mode)
			append("&type=").append(typeParam)
			if (genreParam.isNotEmpty()) append("&genre=").append(genreParam.urlEncoded())
			append("&limit=").append(pageSize)
			append("&offset=").append(offset)
		}

		val json = apiGetJson(url)
		val data = json.optJSONArray("data") ?: return emptyList()

		val stateFilter = filter.states.oneOrThrowIfMany()

		val result = ArrayList<Manga>(data.length())
		for (i in 0 until data.length()) {
			val item = data.getJSONObject(i)

			if (stateFilter != null) {
				val itemStatus = item.optString("status")
				val expectedStatus = when (stateFilter) {
					MangaState.ONGOING -> "on-going"
					MangaState.FINISHED -> "end"
					else -> null
				}
				if (expectedStatus != null && itemStatus != expectedStatus) continue
			}

			val slug = item.getString("slug")
			result.add(
				Manga(
					id = generateUid("/komik/$slug/"),
					url = "/komik/$slug/",
					title = item.getString("title"),
					altTitles = emptySet(),
					publicUrl = "https://$domain/komik/$slug/",
					rating = RATING_UNKNOWN,
					contentRating = if (isNsfwSource) ContentRating.ADULT else null,
					coverUrl = item.optString("thumbnail").ifBlank { "" },
					tags = emptySet(),
					state = when (item.optString("status")) {
						"on-going" -> MangaState.ONGOING
						"end" -> MangaState.FINISHED
						else -> null
					},
					authors = emptySet(),
					source = source,
				),
			)
		}
		return result
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val slug = manga.url.removePrefix("/komik/").removeSuffix("/")
		val url = "https://$domain/api/manga.php?source=$apiSource&slug=${slug.urlEncoded()}&platform=web"
		val json = apiGetJson(url)

		val data = json.getJSONObject("data")

		val title = data.getString("title")
		val synopsis = Jsoup.parse(data.optString("synopsis")).text().takeIf { it.isNotBlank() }
		val alternative = data.optString("alternative").takeIf { it.isNotBlank() }
		val thumbnail = data.optString("thumbnail").takeIf { it.isNotBlank() }
		val author = data.optString("author").takeIf { it.isNotBlank() }
		val artist = data.optString("artist").takeIf { it.isNotBlank() }

		val description = buildString {
			if (synopsis != null) append(synopsis)
			if (alternative != null) {
				if (isNotEmpty()) append("\n\n")
				append("Alternative: ").append(alternative)
			}
		}.trim().takeIf { it.isNotEmpty() }

		val state = when (data.optString("status")) {
			"on-going" -> MangaState.ONGOING
			"end" -> MangaState.FINISHED
			else -> null
		}

		val genresArray = data.optJSONArray("genres")
		val tags: Set<MangaTag> = if (genresArray != null) {
			val tagSet = LinkedHashSet<MangaTag>(genresArray.length())
			for (i in 0 until genresArray.length()) {
				val genre = genresArray.getString(i)
				tagSet.add(
					MangaTag(
						key = genre.lowercase(sourceLocale),
						title = genre.toTitleCase(sourceLocale),
						source = source,
					),
				)
			}
			tagSet
		} else {
			emptySet()
		}

		val authors = setOfNotNull(
			author,
			artist?.takeIf { it != author },
		)

		val chaptersArray = data.optJSONArray("chapters") ?: JSONArray()
		val chapters = ArrayList<MangaChapter>(chaptersArray.length())
		for (i in 0 until chaptersArray.length()) {
			val ch = chaptersArray.getJSONObject(i)
			val chSlug = ch.getString("slug")
			val chTitle = ch.getString("title")
			val chDate = ch.optLong("date").takeIf { it != 0L }?.times(1000L) ?: 0L
			chapters.add(
				MangaChapter(
					id = generateUid("/komik/$slug/$chSlug/"),
					title = chTitle,
					number = extractChapterNumber(chTitle),
					volume = 0,
					url = "/komik/$slug/$chSlug/",
					scanlator = null,
					uploadDate = chDate,
					branch = null,
					source = source,
				),
			)
		}

		chapters.sortBy { it.number }

		return manga.copy(
			title = title,
			description = description,
			coverUrl = thumbnail ?: manga.coverUrl,
			tags = tags,
			state = state,
			authors = authors,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val parts = chapter.url.trim('/').split("/")
		if (parts.size < 3) return emptyList()
		val mangaSlug = parts[1]
		val chapterSlug = parts[2]

		// Re-fetch manga detail to get a fresh, IP-bound chapter token
		val detailUrl = "https://$domain/api/manga.php" +
			"?source=${apiSource.urlEncoded()}" +
			"&slug=${mangaSlug.urlEncoded()}" +
			"&platform=web"
		val detailJson = apiGetJson(detailUrl)

		var chapterToken = ""
		val chaptersArray = detailJson.optJSONObject("data")?.optJSONArray("chapters")
		if (chaptersArray != null) {
			for (i in 0 until chaptersArray.length()) {
				val ch = chaptersArray.getJSONObject(i)
				if (ch.optString("slug") == chapterSlug) {
					chapterToken = ch.optString("chapter_token")
					break
				}
			}
		}

		val url = buildString {
			append("https://$domain/api/chapter.php")
			append("?source=").append(apiSource.urlEncoded())
			append("&manga=").append(mangaSlug.urlEncoded())
			append("&chapter=").append(chapterSlug.urlEncoded())
			append("&platform=web")
			if (chapterToken.isNotBlank()) {
				append("&token=").append(chapterToken.urlEncoded())
			}
		}

		val json = apiGetJson(url)

		val data = json.getJSONObject("data")
		val images = data.optJSONArray("images") ?: return emptyList()

		return (0 until images.length()).map { i ->
			val imgUrl = images.getString(i)
			MangaPage(
				id = generateUid(imgUrl),
				url = imgUrl,
				preview = null,
				source = source,
			)
		}
	}

	private fun extractChapterNumber(title: String): Float {
		return Regex("""\d+(?:[.,]\d+)?""").find(title)
			?.value?.replace(',', '.')?.toFloatOrNull() ?: 0f
	}

	private suspend fun apiGetJson(url: String): JSONObject {
		var solvedChallenge = false
		repeat(2) {
			val json = try {
				webClient.httpGet(url).parseJson()
			} catch (e: HttpStatusException) {
				// ensureSuccess() consumed the body; re-fetch raw to inspect it
				val raw = rawFetchJson(url) ?: throw e
				handleAccessGate(raw, solvedChallenge)?.let {
					solvedChallenge = solvedChallenge || it
					return@repeat
				}
				throw e
			}
			handleAccessGate(json, solvedChallenge)?.let {
				solvedChallenge = solvedChallenge || it
				return@repeat
			}
			return json
		}
		throw IllegalStateException("Gagal memuat data dari $url")
	}

	private suspend fun rawFetchJson(url: String): JSONObject? {
		val request = Request.Builder()
			.url(url)
			.get()
			.headers(getRequestHeaders())
			.build()
		val response = context.httpClient.newCall(request).await()
		val body = response.use { it.body?.string() }
		return if (!body.isNullOrBlank()) JSONObject(body) else null
	}

	private suspend fun handleAccessGate(json: JSONObject, alreadySolved: Boolean): Boolean? {
		if (json.optBoolean("need_challenge")) {
			if (alreadySolved) {
				throw Exception(json.optString("message", "Verifikasi tetap gagal"))
			}
			solveHumanChallenge()
			return true
		}
		if (!json.optBoolean("ok", true) && json.optBoolean("locked", false)) {
			if (loginRequired) {
				throw AuthRequiredException(source)
			}
			throw Exception(json.optString("message", "Konten dikunci"))
		}
		return null
	}

	private suspend fun solveHumanChallenge() {
		var lastMessage: String? = null
		repeat(3) {
			val issue = webClient.httpGet("https://$domain/api/challenge.php").parseJson()
			if (issue.optBoolean("verified")) return
			val ch = issue.optJSONObject("challenge") ?: return@repeat
			val id = ch.optString("id").ifBlank { return@repeat }
			val ticket = ch.optString("ticket").ifBlank { return@repeat }

			// The site JS requires: elapsed_ms >= 650, progress >= 0.97,
			// samples ≤ 79, monotonic t, x∈[0..1]. Emulate 31 samples over 1500 ms.
			val samples = JSONArray()
			val steps = 30
			val durationMs = 1500
			for (i in 0..steps) {
				val frac = i.toDouble() / steps
				val jitter = (Math.random() - 0.5) * 0.005
				val x = if (i == steps) 1.0 else (frac + jitter).coerceIn(0.0, 1.0)
				val t = if (i == steps) durationMs else (frac * durationMs).toInt()
				samples.put(
					JSONObject().apply {
						put("x", (Math.round(x * 10000.0) / 10000.0))
						put("t", t)
					},
				)
			}

			val body = JSONObject().apply {
				put("challenge_id", id)
				put("ticket", ticket)
				put("elapsed_ms", durationMs)
				put("progress", 1)
				put("samples", samples)
			}
			val resp = try {
				webClient.httpPost("https://$domain/api/challenge.php", body).parseJson()
			} catch (e: Exception) {
				lastMessage = e.message
				return@repeat
			}
			if (resp.optBoolean("verified")) return
			lastMessage = resp.optString("message")
		}
		throw Exception(lastMessage ?: "Verifikasi manusia gagal")
	}
}
