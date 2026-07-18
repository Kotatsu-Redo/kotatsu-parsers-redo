package org.koitharu.kotatsu.parsers.site.id

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.toJSONArrayOrNull
import org.json.JSONObject
import org.json.JSONArray
import java.util.*
import okhttp3.HttpUrl.Companion.toHttpUrl

@MangaSourceParser("YURIBASE", "YuriBase", "id", type = ContentType.HENTAI)
internal class YuriBase(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.YURIBASE, 16) {

	override val configKeyDomain = ConfigKey.Domain("yuribase.id")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.UPDATED)

	override val filterCapabilities = MangaListFilterCapabilities(
		isSearchSupported = true,
		isSearchWithFiltersSupported = true,
		isMultipleTagsSupported = false,
	)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = setOf(
			"Girls Love", "Romance", "School Life", "Erotica", "Anthology",
			"Vampires", "Fantasy", "Oneshot", "Gyaru", "Slice of Life",
			"Drama", "Harem", "Comedy", "Music", "Ghost",
			"Tribadism", "Magic", "Isekai", "Suggestive", "Incest",
			"Maid", "Office Workers"
		).mapToSet { MangaTag(it, it, source) },
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED, MangaState.PAUSED)
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val limit = 16
		val offset = (page - 1) * limit

		val filtersArray = JSONArray()

		if (!filter.query.isNullOrEmpty()) {
			val query = filter.query!!.lowercase().replace(" ", "-")
			filtersArray.put(JSONObject().apply {
				put("fieldFilter", JSONObject().apply {
					put("field", JSONObject().put("fieldPath", "mangaSlug"))
					put("op", "GREATER_THAN_OR_EQUAL")
					put("value", JSONObject().put("stringValue", query))
				})
			})
			filtersArray.put(JSONObject().apply {
				put("fieldFilter", JSONObject().apply {
					put("field", JSONObject().put("fieldPath", "mangaSlug"))
					put("op", "LESS_THAN_OR_EQUAL")
					put("value", JSONObject().put("stringValue", query + "\uf8ff"))
				})
			})
		}

		if (filter.tags.isNotEmpty()) {
			val rawTag = filter.tags.first().key
			val tag = getFilterOptions().availableTags.find { it.key.equals(rawTag, ignoreCase = true) }?.key ?: rawTag
			filtersArray.put(JSONObject().apply {
				put("fieldFilter", JSONObject().apply {
					put("field", JSONObject().put("fieldPath", "genres"))
					put("op", "ARRAY_CONTAINS")
					put("value", JSONObject().put("stringValue", tag))
				})
			})
		}

		if (filter.states.isNotEmpty()) {
			val status = when (filter.states.first()) {
				MangaState.ONGOING -> "Ongoing"
				MangaState.PAUSED -> "Hiatus"
				MangaState.FINISHED -> "Complete"
				else -> null
			}
			if (status != null) {
				filtersArray.put(JSONObject().apply {
					put("fieldFilter", JSONObject().apply {
						put("field", JSONObject().put("fieldPath", "status"))
						put("op", "EQUAL")
						put("value", JSONObject().put("stringValue", status))
					})
				})
			}
		}

		val whereObj = when {
			filtersArray.length() == 0 -> null
			filtersArray.length() == 1 -> filtersArray.getJSONObject(0)
			else -> JSONObject().apply {
				put("compositeFilter", JSONObject().apply {
					put("op", "AND")
					put("filters", filtersArray)
				})
			}
		}

		val payload = JSONObject().apply {
			put("structuredQuery", JSONObject().apply {
				put("from", JSONArray().put(JSONObject().put("collectionId", "mangas")))
				if (whereObj != null) put("where", whereObj)
				when {
					!filter.query.isNullOrEmpty() -> put("orderBy", JSONArray().put(JSONObject().apply {
						put("field", JSONObject().put("fieldPath", "mangaSlug"))
						put("direction", "ASCENDING")
					}))
					filter.tags.isEmpty() && filter.states.isEmpty() -> put("orderBy", JSONArray().put(JSONObject().apply {
						put("field", JSONObject().put("fieldPath", "timePost"))
						put("direction", "DESCENDING")
					}))
				}
				put("offset", offset)
				put("limit", limit)
			})
		}

		val searchUrl = "https://firestore.googleapis.com/v1/projects/ybase2026/databases/(default)/documents:runQuery".toHttpUrl()
		val responseArray = webClient.httpPost(searchUrl, payload).parseJsonArray()
		
		val list = ArrayList<Manga>()
		for (i in 0 until responseArray.length()) {
			val doc = responseArray.optJSONObject(i)?.optJSONObject("document") ?: continue
			val fields = doc.optJSONObject("fields") ?: continue
			val slug = fields.optJSONObject("mangaSlug")?.optString("stringValue") ?: continue
			
			list.add(
				Manga(
					id = generateUid(slug),
					title = fields.optJSONObject("title")?.optString("stringValue") ?: "Unknown",
					altTitles = setOfNotNull(fields.optJSONObject("titleSourceTwo")?.optString("stringValue")?.takeIf { it.isNotEmpty() }),
					url = "/manga/$slug",
					publicUrl = "https://$domain/manga/$slug",
					rating = fields.optJSONObject("likes")?.optString("integerValue")?.toFloatOrNull()?.div(10f) ?: RATING_UNKNOWN,
					contentRating = if (fields.optJSONObject("nsfw")?.optBoolean("booleanValue", false) == true) ContentRating.ADULT else ContentRating.SAFE,
					coverUrl = fields.optJSONObject("bannerImage")?.optString("stringValue")?.takeIf { it.isNotEmpty() },
					largeCoverUrl = fields.optJSONObject("bannerImage")?.optString("stringValue")?.takeIf { it.isNotEmpty() },
					tags = emptySet(),
					description = fields.optJSONObject("description")?.optString("stringValue")?.takeIf { it.isNotEmpty() },
					state = when (fields.optJSONObject("status")?.optString("stringValue")?.lowercase()) {
						"ongoing" -> MangaState.ONGOING
						"complete", "completed" -> MangaState.FINISHED
						"hiatus" -> MangaState.PAUSED
						else -> null
					},
					authors = setOfNotNull(fields.optJSONObject("artist")?.optJSONObject("arrayValue")?.optJSONArray("values")?.optJSONObject(0)?.optString("stringValue")?.takeIf { it.isNotEmpty() }),
					source = source,
				)
			)
		}
		return list
	}

	private fun extractJsonArray(jsonStr: String, key: String): JSONArray? {
		val searchStr = "\"$key\":["
		val startIdx = jsonStr.indexOf(searchStr)
		if (startIdx == -1) return null

		var bracketCount = 0
		var inString = false
		var escapeNext = false
		val arrayStart = startIdx + searchStr.length - 1

		for (i in arrayStart until jsonStr.length) {
			val c = jsonStr[i]
			if (escapeNext) {
				escapeNext = false
				continue
			}
			if (c == '\\') {
				escapeNext = true
				continue
			}
			if (c == '"') {
				inString = !inString
				continue
			}
			if (!inString) {
				if (c == '[') bracketCount++
				else if (c == ']') {
					bracketCount--
					if (bracketCount == 0) {
						val arrayStr = jsonStr.substring(arrayStart, i + 1)
						return arrayStr.toJSONArrayOrNull()
					}
				}
			}
		}
		return null
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.publicUrl).parseHtml()

		val description = doc.select("[class*=group/synopsis] p").first()?.text()
			?: doc.select("p").firstOrNull { it.text().length > 50 }?.text()

		val tags = doc.select("a[href^=/genre/] span").mapNotNullToSet {
			val text = it.text().trim()
			if (text.isEmpty()) null else MangaTag(text, text, source)
		}

		val authors = doc.select("a[href^=/author/] span").mapNotNullToSet {
			it.text().trim().takeIf { t -> t.isNotEmpty() }
		}

		val stateStr = doc.selectFirst("span.text-violet-400")?.text()
		val state = when (stateStr?.lowercase()) {
			"ongoing" -> MangaState.ONGOING
			"complete" -> MangaState.FINISHED
			else -> null
		}

		val scripts = doc.select("script")
		val jsonLines = StringBuilder()
		for (script in scripts) {
			val raw = script.data().substringBetween("self.__next_f.push(", ")", "").trim()
			if (raw.isEmpty()) continue
			val ja = raw.toJSONArrayOrNull() ?: continue
			for (i in 0 until ja.length()) {
				(ja.opt(i) as? String)?.let { jsonLines.append(it) }
			}
		}
		val jsonStr = jsonLines.toString()

		val chaptersArray = extractJsonArray(jsonStr, "chapters")
		val chapters = ArrayList<MangaChapter>()

		if (chaptersArray != null) {
			for (i in 0 until chaptersArray.length()) {
				val obj = chaptersArray.optJSONObject(i) ?: continue
				val chapterId = obj.optString("id")
				val chapterNumber = obj.optDouble("chapterNumber", 0.0).toFloat()
				if (chapterId.isEmpty()) continue

				chapters.add(
					MangaChapter(
						id = generateUid(chapterId),
						title = "Chapter ${chapterNumber.toString().removeSuffix(".0")}",
						number = chapterNumber,
						volume = 0,
						url = "${manga.url}/chapter/$chapterId",
						scanlator = null,
						uploadDate = 0L,
						branch = null,
						source = source,
					)
				)
			}
			
			chapters.sortByDescending { it.number }
		}

		return manga.copy(
			description = description ?: manga.description,
			tags = tags.ifEmpty { manga.tags },
			authors = authors.ifEmpty { manga.authors },
			state = state ?: manga.state,
			chapters = chapters.reversed()
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val chapterId = chapter.url.substringAfterLast("/")
		val payload = JSONObject().apply {
			put("chapterId", chapterId)
			put("isHD", true)
		}

		val response = webClient.httpPost(
			"https://www.yuribase.id/api/chapter/pages".toHttpUrl(),
			payload
		).parseJson()

		val pagesArray = response.optJSONArray("pages") ?: return emptyList()

		val pages = ArrayList<MangaPage>()
		for (i in 0 until pagesArray.length()) {
			val url = pagesArray.optString(i)
			if (url.isEmpty()) continue
			pages.add(MangaPage(id = generateUid(url), url = url, preview = null, source = source))
		}

		return pages
	}
}