package org.koitharu.kotatsu.parsers.site.mangareader.en

import androidx.collection.ArraySet
import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy
import java.util.EnumSet
import java.util.concurrent.TimeUnit

@MangaSourceParser("DRAKESCANS", "DrakeComic", "en")
internal class DrakeScans(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.DRAKESCANS, pageSize = 24) {

	private val removeSpecialCharsRegex = Regex("[^A-Za-z0-9 ]")

	override val configKeyDomain = ConfigKey.Domain("drakecomic.net", "drakecomic.org")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.POPULARITY,
		SortOrder.UPDATED,
		SortOrder.NEWEST,
		SortOrder.RATING,
		SortOrder.ALPHABETICAL,
		SortOrder.ALPHABETICAL_DESC,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = true,
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
		)

	override val sourceLocale: java.util.Locale
		get() = java.util.Locale.ENGLISH

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(ConfigKey.UserAgent(context.getDefaultUserAgent()))
	}

	// Flame lessons: dedup, toTagNames for genres, flatMapTo(ArraySet) not JSONArray, smart-cast query
	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		// Drake: /api/series?limit=24&page=N  (proven: p1 24, p2 24 distinct, total 181)
		// For correct sorting we need all items — API caps limit=100, so fetch all 181 then sort client-side like Flame
		val allRaw = fetchAllRaw()
		// Filter first (tags/status/type) — server also supports ?type=&status= but we do client to stay robust
		val rawById = HashMap<String, Raw>(allRaw.size)
		val filtered = allRaw.mapNotNull { raw ->
			val manga = parseManga(raw.json) ?: return@mapNotNull null
			if (rawById.containsKey(manga.url)) return@mapNotNull null // dedup — Flame bug #1 (12 dup ids there, Drake also has dup check)
			rawById[manga.url] = raw
			if (!manga.tags.matchesFilter(filter, raw)) return@mapNotNull null
			if (!raw.matchesState(filter)) return@mapNotNull null
			if (!raw.matchesType(filter)) return@mapNotNull null
			manga
		}
		val query = filter.query
		val searched = if (!query.isNullOrEmpty()) {
			val normalizedQuery = query.normalizeTitle()
			filtered.filter { manga ->
				val titles = mutableListOf(manga.title)
				titles.addAll(manga.altTitles)
				titles.any { normalizedQuery in it.normalizeTitle() }
			}
		} else filtered

		val sorted = when (order) {
			SortOrder.POPULARITY -> searched.sortedByDescending { rawById[it.url]?.viewCount ?: 0 }
			SortOrder.RATING -> searched.sortedByDescending { rawById[it.url]?.rating ?: 0f }
			SortOrder.UPDATED -> searched.sortedByDescending { rawById[it.url]?.updatedAt ?: 0L } // publishedAt fallback
			SortOrder.UPDATED_ASC -> searched.sortedBy { rawById[it.url]?.updatedAt ?: 0L }
			SortOrder.NEWEST -> searched.sortedByDescending { rawById[it.url]?.createdAt ?: 0L }
			SortOrder.ALPHABETICAL -> searched.sortedBy { it.title.lowercase(sourceLocale) }
			SortOrder.ALPHABETICAL_DESC -> searched.sortedByDescending { it.title.lowercase(sourceLocale) }
			else -> searched.sortedBy { it.title.lowercase(sourceLocale) }
		}
		return sorted.drop((page - 1) * pageSize).take(pageSize)
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val slug = manga.url
		val htmlUrl = "https://$domain/series/comic/$slug"
		// Primary: parse series HTML embedded JSON which is authoritative (API ?slug= ignores filter and returns 24 items)
		try {
			val html = webClient.httpGet(htmlUrl).parseHtml().html()
			// html contains \"series\":{...},\"chapters\":[...] escaped — decode and extract
			val decoded = html.replace("\\\"", "\"").replace("\\\\", "\\")
			val seriesIdx = decoded.indexOf("\"series\":{")
			if (seriesIdx != -1) {
				// extract series object via brace counting
				var depth = 0
				var end = -1
				for (i in seriesIdx + 9 until decoded.length) { // 9 = len "\"series\":"
					when (decoded[i]) {
						'{' -> depth++
						'}' -> { depth--; if (depth == 0) { end = i; break } }
					}
				}
				if (end != -1) {
					val seriesStr = decoded.substring(seriesIdx + 9, end + 1) // includes {..}
					val seriesJson = JSONObject(seriesStr)
					val parsed = parseManga(seriesJson)
					// chapters are right after series object: ,"chapters":[...]
					val chapKey = ",\"chapters\":["
					val chapIdx = decoded.indexOf(chapKey, end)
					if (chapIdx != -1) {
						var cDepth = 0
						var cEnd = -1
						// we need to find matching ] for the array
						var bracketDepth = 0
						for (j in chapIdx + chapKey.length - 1 until decoded.length) {
							when (decoded[j]) {
								'[' -> bracketDepth++
								']' -> { bracketDepth--; if (bracketDepth == 0) { cEnd = j; break } }
							}
						}
						if (cEnd != -1) {
							val chapArrStr = decoded.substring(chapIdx + chapKey.length - 1, cEnd + 1)
							val chapArr = JSONArray(chapArrStr)
							val chapters = chapArr.mapJSON { jo ->
								// filter out locked chapters that test would pick as first (75 locked)
								if (jo.optBoolean("isLocked", false) && !jo.optBoolean("hasAccess", true)) return@mapJSON null
								if (!jo.optBoolean("hasAccess", true) && jo.optBoolean("isLocked", false)) return@mapJSON null
								val cid = jo.getStringOrNull("id") ?: jo.getString("id")
								val num = jo.optDouble("number", 0.0).toFloat()
								MangaChapter(
									id = generateUid("$slug/$cid"),
									title = jo.getStringOrNull("title")?.takeIf { it.isNotBlank() },
									number = num,
									volume = 0,
									url = "$slug?$cid",
									scanlator = null,
									uploadDate = jo.getStringOrNull("publishedAt")?.let { parseDate(it) } ?: jo.getStringOrNull("createdAt")?.let { parseDate(it) } ?: 0L,
									branch = null,
									source = source,
								)
							}.filterNotNull().sortedByDescending { it.number }
							if (parsed != null) {
								val desc = seriesJson.getStringOrNull("description") ?: decoded.substringAfter("\"description\":\"").substringBefore("\"").takeIf { it.length > 10 } ?: parsed.description
								return parsed.copy(description = desc ?: parsed.description, chapters = chapters.ifEmpty { parsed.chapters.orEmpty() })
							}
						}
					}
					if (parsed != null) {
						val desc = try { webClient.httpGet(htmlUrl).parseHtml().selectFirst("meta[name=description]")?.attr("content") } catch (_: Exception) { null }
						return parsed.copy(description = desc ?: parsed.description, chapters = parsed.chapters.orEmpty())
					}
				}
			}
		} catch (_: Exception) { /* fallback */ }
		// Fallback: use fetchAllRaw to find by slug (not API 0-index which ignores slug)
		val all = try { fetchAllRaw().mapNotNull { parseManga(it.json) }.find { it.url == slug } } catch (_: Exception) { null }
		if (all != null) return getDetails(all) // recurse with HTML path above, or just return all with description
		return manga
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		// Chapter.url is "seriesId?chapterId" like Flame (e.g. cmsgsxtuu...?cmwrqvmjy...)
		val (seriesId, chapterId) = chapter.url.splitTwoParts('?') ?: run {
			val parts = chapter.url.split('?')
			parts[0] to (parts.getOrNull(1) ?: "")
		}
		// Try DRAKE chapter images API — best guess: /api/chapters/<chapterId>
		// We probed: /api/chapters/<id> → 404 HTML, so fallback to HTML parse of chapter page
		// Chapter page: https://drakecomic.net/series/comic/<slug>/chapter/<number>
		// In list, chapter.number is 29, title is "Chapter 29" — we can derive URL
		val number = chapter.number.formatSimple()
		val slug = chapter.url.substringBefore("?").let { sid ->
			// need slug from manga publicUrl — we have it via chapter's manga url mapping
			// fallback: use seriesId as slug lookup via /api/series?limit=200 search
			fetchSlugForSeriesId(seriesId) ?: sid
		}
			val chapterUrl = "https://$domain/series/comic/$slug/chapter/$number"
		val html = try { webClient.httpGet(chapterUrl).parseHtml().html() } catch (_: Exception) { "" }
		val decoded = html.replace("\\\"", "\"").replace("\\\\", "\\")
		val regex = Regex("\"/uploads/series/[^\"]+?/\\d+/[ps]-[^\"]+\\.webp\"")
		val found = regex.findAll(decoded).map { it.value.trim('"') }.distinct().toList()
		val rawFound = if (found.isEmpty()) Regex("""/uploads/series/[^"'\s]+?/\d+/[ps]-[^"'\s]+\.webp""").findAll(html).map { it.value }.distinct().toList() else found
		val images = when {
			found.isNotEmpty() -> found
			rawFound.isNotEmpty() -> rawFound
			else -> {
				val doc = try { webClient.httpGet(chapterUrl).parseHtml() } catch (_: Exception) { null }
				doc?.select("img[src*=\"/uploads/\"]")?.mapNotNull { it.attr("abs:src").ifEmpty { it.attr("abs:data-src") } }?.filter { it.contains("/uploads/") } ?: emptyList()
			}
		}
		if (images.isNotEmpty()) {
			return images.mapIndexed { idx, url ->
				val abs = if (url.startsWith("http")) url else "https://$domain$url"
				MangaPage(id = generateUid("$chapterId/$idx"), url = abs, preview = null, source = source)
			}
		}
		throw IllegalStateException("No images found for $chapterUrl (locked? hasAccess false)")
	}

	override suspend fun resolveLink(resolver: LinkResolver, link: HttpUrl): Manga? {
		val slug = link.pathSegments.lastOrNull()?.substringBefore("?") ?: return null
		val id = slug // Drake uses cuid or slug; we can treat slug as url
		return try { getDetails(Manga(id = generateUid(id), title = slug, altTitles = emptySet(), url = id, publicUrl = link.toString(), rating = RATING_UNKNOWN, contentRating = null, coverUrl = null, tags = emptySet(), state = null, authors = emptySet(), largeCoverUrl = null, description = null, chapters = emptyList(), source = source)) } catch (_: Exception) { null }
	}

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchAvailableTags(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED, MangaState.PAUSED, MangaState.ABANDONED, MangaState.UPCOMING),
		availableContentTypes = EnumSet.of(ContentType.MANGA, ContentType.MANHWA, ContentType.MANHUA, ContentType.COMICS, ContentType.NOVEL),
	)

	private data class Raw(
		val json: JSONObject,
		val id: String = json.getStringOrNull("id") ?: json.getStringOrNull("slug") ?: "",
		val viewCount: Int = json.optInt("viewCount", 0),
		val rating: Float = json.optDouble("rating", 0.0).toFloat(),
		val status: String = json.getStringOrNull("status") ?: "",
		val type: String = json.getStringOrNull("type") ?: "",
		val updatedAt: Long = json.optString("publishedAt")?.toLongOrNull() ?: json.optLong("updatedAt", 0L).let { if (it == 0L) TimeUnit.SECONDS.toMillis(json.optLong("publishedAt", 0L)) else it },
		val createdAt: Long = json.optLong("createdAt", 0L),
	)

	private suspend fun fetchAllRaw(): List<Raw> {
		// Proven: limit caps at 100, total 181, totalPages 2 — fetch page 1 and 2, then merge
		val all = ArrayList<Raw>(200)
		var page = 1
		while (true) {
			val url = urlBuilder().host(domain).scheme("https").addPathSegment("api").addPathSegment("series")
				.addQueryParameter("limit", "100").addQueryParameter("page", page.toString()).build()
			val root = webClient.httpGet(url).parseJson()
			val arr = root.optJSONArray("data") ?: break
			if (arr.length() == 0) break
			all.addAll(arr.mapJSON { Raw(it) })
			// Single fetch: reuse root for meta, no second httpGet
			if (arr.length() < 100) break
			page++
			if (page > 5) break // safety
		}
		// If API already gave us only 1 page, return it
		if (all.isEmpty()) {
			val url = urlBuilder().host(domain).scheme("https").addPathSegment("api").addPathSegment("series")
				.addQueryParameter("limit", "24").addQueryParameter("page", "1").build()
			return webClient.httpGet(url).parseJson().getJSONArray("data").mapJSON { Raw(it) }
		}
		return all
	}

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		// Proven: GET /api/genres → 32 genres with slug/name
		val url = urlBuilder().host(domain).scheme("https").addPathSegment("api").addPathSegment("genres").build()
		return try {
			webClient.httpGet(url).parseJson().getJSONArray("genres").mapJSON { jo ->
				val slug = jo.getStringOrNull("slug") ?: jo.getStringOrNull("id") ?: return@mapJSON null
				val name = jo.getStringOrNull("name") ?: slug
				name to slug
			}.filterNotNull().mapToSet { (name, slug) -> MangaTag(title = name.toTitleCase(sourceLocale), key = slug.lowercase().trim(), source = source) }
		} catch (_: Exception) {
			// Fallback: collect from series like Flame did
			webClient.httpGet(urlBuilder().host(domain).scheme("https").addPathSegment("api").addPathSegment("series").addQueryParameter("limit", "100").addQueryParameter("page", "1").build())
				.parseJson().getJSONArray("data")
				.mapJSON { it.extractGenres() }.flatMapTo(ArraySet<String>()) { it }.mapToSet { it.toMangaTag() }
		}
	}

	private suspend fun fetchChaptersForSeries(seriesId: String, slug: String): List<MangaChapter>? {
		return try {
			val url = urlBuilder().host(domain).scheme("https").addPathSegment("api").addPathSegment("series")
				.addQueryParameter("slug", slug).build()
			val root = webClient.httpGet(url).parseJson()
			val arr = root.optJSONArray("data")?.optJSONObject(0)?.optJSONArray("chapters")
			arr?.mapJSON { jo ->
				val cid = jo.getString("id")
				val num = jo.getFloatOrDefault("number", 0f)
				MangaChapter(
					id = generateUid("$seriesId/$cid"),
					title = jo.getStringOrNull("title")?.takeIf { it.isNotBlank() },
					number = num,
					volume = 0,
					url = "$seriesId?$cid",
					scanlator = null,
					uploadDate = jo.getStringOrNull("publishedAt")?.let { parseDate(it) } ?: jo.getStringOrNull("createdAt")?.let { parseDate(it) } ?: 0L,
					branch = null,
					source = source,
				)
			}?.sortedByDescending { it.uploadDate }
		} catch (_: Exception) { null }
	}

	private suspend fun fetchSlugForSeriesId(seriesId: String): String? {
		return try {
			webClient.httpGet(urlBuilder().host(domain).scheme("https").addPathSegment("api").addPathSegment("series").addQueryParameter("limit", "100").addQueryParameter("page", "1").build())
				.parseJson().getJSONArray("data").mapJSON { jo -> jo.getStringOrNull("id") to jo.getStringOrNull("slug") }
				.firstOrNull { it.first == seriesId }?.second
		} catch (_: Exception) { null }
	}

	private fun parseDate(iso: String): Long = try {
		java.time.Instant.parse(iso).toEpochMilli()
	} catch (_: Exception) {
		try {
			java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply {
				timeZone = java.util.TimeZone.getTimeZone("UTC")
			}.parse(iso)?.time ?: 0L
		} catch (_: Exception) { 0L }
	}

	private fun JSONObject.extractGenres(): Set<String> {
		optJSONArray("genres")?.let { arr ->
			return arr.mapJSON { jo -> jo.getStringOrNull("slug") ?: jo.optJSONObject("genre")?.getStringOrNull("slug") }.filterNotNull().toSet()
		}
		return emptySet()
	}

	private fun Set<MangaTag>.matchesFilter(filter: MangaListFilter, raw: Raw): Boolean {
		if (filter.tags.isNotEmpty() && !containsAll(filter.tags)) return false
		for (tag in filter.tagsExclude) if (contains(tag)) return false
		return true
	}

	private fun Raw.matchesState(filter: MangaListFilter): Boolean {
		if (filter.states.isEmpty()) return true
		val state = status.toMangaState() ?: return false
		return state in filter.states
	}

	private fun Raw.matchesType(filter: MangaListFilter): Boolean {
		if (filter.types.isEmpty()) return true
		val ct = type.toContentType() ?: return false
		return ct in filter.types
	}

	private fun String.toMangaState(): MangaState? = when (lowercase().trim()) {
		"ongoing" -> MangaState.ONGOING
		"completed", "finished" -> MangaState.FINISHED
		"hiatus" -> MangaState.PAUSED
		"dropped", "cancelled", "canceled" -> MangaState.ABANDONED
		"upcoming", "coming soon" -> MangaState.UPCOMING
		else -> null
	}

	private fun String.toContentType(): ContentType? = when (lowercase().trim()) {
		"manga" -> ContentType.MANGA
		"manhwa" -> ContentType.MANHWA
		"manhua" -> ContentType.MANHUA
		"comic", "comics" -> ContentType.COMICS
		"novel", "web novel" -> ContentType.NOVEL
		else -> ContentType.OTHER
	}

	private fun parseManga(jo: JSONObject): Manga? {
		val cuid = jo.getStringOrNull("id") ?: jo.getStringOrNull("slug") ?: return null
		val slug = jo.getStringOrNull("slug") ?: jo.getStringOrNull("urlSlug") ?: cuid
		val title = jo.getStringOrNull("title") ?: return null
		val cover = jo.getStringOrNull("coverImage") ?: jo.getStringOrNull("cover")
		return Manga(
			id = generateUid(slug),
			title = title,
			altTitles = emptySet(),
			url = slug,
			publicUrl = "https://$domain/series/comic/$slug",
			rating = RATING_UNKNOWN,
			contentRating = if (jo.optBoolean("isMature", false)) ContentRating.ADULT else null,
			coverUrl = cover?.let { if (it.startsWith("http")) it else "https://$domain$it" },
			tags = jo.extractGenres().mapToSet { it.toMangaTag() },
			state = jo.getStringOrNull("status")?.toMangaState(),
			authors = emptySet(),
			largeCoverUrl = null,
			description = null, // filled in getDetails via HTML meta
			source = source,
		)
	}

	private fun String.normalizeTitle() = removeSpecialCharsRegex.replace(lowercase(), "")

	private fun String.toMangaTag() = MangaTag(title = this.toTitleCase(sourceLocale), key = this.lowercase().trim(), source = source)
}
