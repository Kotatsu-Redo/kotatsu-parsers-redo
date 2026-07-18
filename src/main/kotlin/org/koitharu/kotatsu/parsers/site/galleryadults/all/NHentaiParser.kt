package org.koitharu.kotatsu.parsers.site.galleryadults.all

import org.json.JSONObject
import org.jsoup.internal.StringUtil
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.galleryadults.GalleryAdultsParser
import org.koitharu.kotatsu.parsers.util.*
import java.util.*

@MangaSourceParser("NHENTAI", "NHentai.net", type = ContentType.HENTAI)
internal class NHentaiParser(context: MangaLoaderContext) :
	GalleryAdultsParser(context, MangaParserSource.NHENTAI, "nhentai.net", 25) {
	override val selectGallery = ""
	override val selectGalleryLink = ""
	override val selectGalleryTitle = ""
	override val pathTagUrl = ""
	override val selectTags = ""
	override val selectTag = ""
	override val selectAuthor = ""
	override val selectLanguageChapter = ""
	override val idImg = ""

	override val availableSortOrders: Set<SortOrder> =
		EnumSet.of(SortOrder.UPDATED, SortOrder.POPULARITY, SortOrder.POPULARITY_TODAY, SortOrder.POPULARITY_WEEK)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = super.filterCapabilities.copy(
			isMultipleTagsSupported = true,
			isOriginalLocaleSupported = true,
		)

	@Suppress("SpellCheckingInspection")
	private val popularTags = "ahegao,anal,angel,apron,bandages,bbw,bdsm,beauty mark,big areolae,big ass,big breasts,big clit,big lips," +
		"big nipples,bikini,blackmail,bloomers,blowjob,bodysuit,bondage,breast expansion,bukkake,bunny girl,business suit," +
		"catgirl,centaur,cheating,chinese dress,christmas,collar,corset,cosplaying,cowgirl,crossdressing,cunnilingus," +
		"dark skin,daughter,deepthroat,defloration,demon girl,double penetration,dougi,dragon,drunk,elf,exhibitionism,farting," +
		"females only,femdom,filming,fingering,fishnets,footjob,fox girl,furry,futanari,garter belt,ghost,giantess," +
		"glasses,gloves,goblin,gothic lolita,growth,guro,gyaru,hair buns,hairy,hairy armpits,handjob,harem,hidden sex," +
		"horns,huge breasts,humiliation,impregnation,incest,inverted nipples,kemonomimi,kimono,kissing,lactation," +
		"latex,leg lock,leotard,lingerie,lizard girl,maid,masked face,masturbation,midget,miko,milf,mind break," +
		"mind control,monster girl,mother,muscle,nakadashi,netorare,nose hook,nun,nurse,oil,paizuri,panda girl," +
		"pantyhose,piercing,pixie cut,policewoman,ponytail,pregnant,rape,rimjob,robot,scat,lolicon,schoolgirl uniform," +
		"sex toys,shemale,sister,small breasts,smell,sole dickgirl,sole female,squirting,stockings,sundress,sweating," +
		"swimsuit,swinging,tail,tall girl,teacher,tentacles,thigh high boots,tomboy,transformation,twins,twintails," +
		"unusual pupils,urination,vore,vtuber,widow,wings,witch,wolf girl,x-ray,yuri,zombie,sole male,males only,yaoi," +
		"tomgirl,tall man,oni,shotacon,prostate massage,policeman,huge penis,fox boy,feminization,dog boy,dickgirl on male,big penis"

	private fun mapTags(): Set<MangaTag> {
		val tagElements = popularTags.split(",")
		val result = LinkedHashSet<MangaTag>(tagElements.size)
		for (tag in tagElements) {
			val el = tag.trim()
			if (el.isEmpty()) continue
			result += MangaTag(
				title = el.toTitleCase(),
				key = el,
				source = source,
			)
		}
		return result
	}

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableLocales = setOf(Locale.ENGLISH, Locale.JAPANESE, Locale.CHINESE),
		availableTags = mapTags()
	)

	private var imageServers: List<String> = listOf("https://i1.nhentai.net", "https://i2.nhentai.net", "https://i3.nhentai.net")
	private var thumbServers: List<String> = listOf("https://t1.nhentai.net", "https://t2.nhentai.net", "https://t3.nhentai.net")
	private var isConfigFetched = false

	private suspend fun ensureConfigFetched() {
		if (isConfigFetched) return
		try {
			val res = webClient.httpGet("https://$domain/api/v2/config").body?.string() ?: return
			val json = JSONObject(res)
			val imgArray = json.optJSONArray("imageServers")
			if (imgArray != null && imgArray.length() > 0) {
				imageServers = (0 until imgArray.length()).map { imgArray.getString(it) }
			}
			val thumbArray = json.optJSONArray("thumbServers")
			if (thumbArray != null && thumbArray.length() > 0) {
				thumbServers = (0 until thumbArray.length()).map { thumbArray.getString(it) }
			}
			isConfigFetched = true
		} catch (e: Exception) {
			e.printStackTrace()
			isConfigFetched = true
		}
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		ensureConfigFetched()
		val apiUrl = "https://$domain/api/v2"
		val queryUrl = buildString {
			if (!filter.query.isNullOrEmpty()) {
				val numericQuery = filter.query.trim()
				if (numericQuery.matches("\\d+".toRegex())) {
					append("$apiUrl/galleries/$numericQuery")
				} else {
					append("$apiUrl/search?query=")
					append(numericQuery.urlEncoded())
					append("&page=$page")
				}
			} else {
				append("$apiUrl/search?query=")
				val advQuery = buildQuery(filter.tags, filter.locale)
				append(if (advQuery.isEmpty()) "\"\"" else advQuery.urlEncoded())
				when (order) {
					SortOrder.POPULARITY -> append("&sort=popular")
					SortOrder.POPULARITY_TODAY -> append("&sort=popular-today")
					SortOrder.POPULARITY_WEEK -> append("&sort=popular-week")
					SortOrder.UPDATED -> append("&sort=date")
					else -> append("&sort=date")
				}
				append("&page=$page")
			}
		}

		val jsonString = webClient.httpGet(queryUrl).body?.string() ?: return emptyList()
		val json = JSONObject(jsonString)

		if (json.has("id")) {
			return listOf(parseGalleryItem(json))
		}

		val result = json.optJSONArray("result") ?: return emptyList()
		val mangas = mutableListOf<Manga>()
		for (i in 0 until result.length()) {
			mangas.add(parseGalleryItem(result.getJSONObject(i)))
		}
		return mangas
	}

	private fun parseGalleryItem(item: JSONObject): Manga {
		val id = item.getInt("id")

		val titleObj = item.optJSONObject("title")
		val englishTitle = titleObj?.optString("english")?.nullIfEmpty() ?: item.optString("english_title").nullIfEmpty()
		val japaneseTitle = titleObj?.optString("japanese")?.nullIfEmpty() ?: item.optString("japanese_title").nullIfEmpty()
		val prettyTitle = titleObj?.optString("pretty")?.nullIfEmpty()

		val title = (englishTitle ?: japaneseTitle ?: prettyTitle ?: "Unknown").trim()

		val thumbObj = item.optJSONObject("thumbnail")
		val thumbStr = thumbObj?.optString("path")?.nullIfEmpty() ?: item.optString("thumbnail").nullIfEmpty() ?: ""
		val coverUrl = if (thumbStr.isNotEmpty()) "${thumbServers.random()}/$thumbStr" else ""

		val tagsArr = item.optJSONArray("tags")
		val authors = mutableSetOf<String>()
		val tags = mutableSetOf<MangaTag>()
		if (tagsArr != null) {
			for (i in 0 until tagsArr.length()) {
				val tagObj = tagsArr.getJSONObject(i)
				val type = tagObj.optString("type")
				val name = tagObj.optString("name")
				if (type == "artist" || type == "group") {
					authors.add(name.toTitleCase())
				} else if (type == "tag" || type == "category" || type == "language") {
					tags.add(MangaTag(key = name, title = name.toTitleCase(), source = source))
				}
			}
		}

		return Manga(
			id = generateUid(id.toString()),
			title = title,
			altTitles = emptySet(),
			url = "/g/$id/",
			publicUrl = "https://$domain/g/$id/",
			rating = RATING_UNKNOWN,
			contentRating = if (isNsfwSource) ContentRating.ADULT else null,
			coverUrl = coverUrl,
			tags = tags,
			state = MangaState.FINISHED,
			authors = authors,
			source = source,
		)
	}

	override suspend fun getDetails(manga: Manga): Manga {
		ensureConfigFetched()
		val id = manga.url.removeSurrounding("/g/", "/").trimEnd('/')
		val jsonString = webClient.httpGet("https://$domain/api/v2/galleries/$id").body?.string() ?: return manga
		val item = JSONObject(jsonString)

		val titleObj = item.optJSONObject("title")
		val englishTitle = titleObj?.optString("english")?.nullIfEmpty()
		val japaneseTitle = titleObj?.optString("japanese")?.nullIfEmpty()
		val prettyTitle = titleObj?.optString("pretty")?.nullIfEmpty()
		
		val fullTitle = englishTitle ?: japaneseTitle ?: prettyTitle ?: manga.title

		val tagsArr = item.optJSONArray("tags")
		val authors = mutableSetOf<String>()
		val tags = mutableSetOf<MangaTag>()
		var branch: String? = null

		if (tagsArr != null) {
			for (i in 0 until tagsArr.length()) {
				val tagObj = tagsArr.getJSONObject(i)
				val type = tagObj.optString("type")
				val name = tagObj.optString("name")
				if (type == "artist" || type == "group") {
					authors.add(name.toTitleCase())
				} else if (type == "tag" || type == "category") {
					tags.add(MangaTag(key = name, title = name.toTitleCase(), source = source))
				} else if (type == "language") {
					if (branch == null && name != "translated") {
						branch = name.toTitleCase()
					}
					tags.add(MangaTag(key = name, title = name.toTitleCase(), source = source))
				}
			}
		}

		
		val thumbObj = item.optJSONObject("thumbnail")
		val thumbStr = thumbObj?.optString("path")?.nullIfEmpty() ?: item.optString("thumbnail").nullIfEmpty() ?: ""
		val coverUrl = if (thumbStr.isNotEmpty()) "${thumbServers.random()}/$thumbStr" else manga.coverUrl

		val chapter = MangaChapter(
			id = generateUid(id),
			title = "Chapter",
			number = 0f,
			volume = 0,
			url = manga.url,
			scanlator = null,
			uploadDate = item.optLong("upload_date", 0L) * 1000L,
			branch = branch,
			source = source,
		)

		return manga.copy(
			title = fullTitle,
			description = fullTitle,
			tags = tags,
			authors = authors,
			coverUrl = coverUrl,
			chapters = listOf(chapter)
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		ensureConfigFetched()
		val id = chapter.url.removeSurrounding("/g/", "/").trimEnd('/')
		val jsonString = webClient.httpGet("https://$domain/api/v2/galleries/$id").body?.string() ?: return emptyList()
		val item = JSONObject(jsonString)
		val pagesArr = item.optJSONArray("pages") ?: return emptyList()
		val result = mutableListOf<MangaPage>()
		for (i in 0 until pagesArr.length()) {
			val pageObj = pagesArr.getJSONObject(i)
			val path = pageObj.optString("path")
			result.add(
				MangaPage(
					id = generateUid(path),
					url = "${imageServers.random()}/$path",
					preview = null,
					source = source
				)
			)
		}
		return result
	}

	override suspend fun getPageUrl(page: MangaPage): String = page.url

	override suspend fun resolveLink(resolver: LinkResolver, link: okhttp3.HttpUrl): Manga? {
		val id = link.pathSegments.lastOrNull { it.isNotBlank() } ?: return null
		if (!id.matches("\\d+".toRegex())) return null
		return getDetails(
			Manga(
				id = generateUid(id),
				title = "",
				altTitles = emptySet(),
				url = "/g/$id/",
				publicUrl = "https://$domain/g/$id/",
				rating = RATING_UNKNOWN,
				contentRating = if (isNsfwSource) ContentRating.ADULT else null,
				coverUrl = "",
				tags = emptySet(),
				state = MangaState.FINISHED,
				authors = emptySet(),
				source = source,
			)
		)
	}

	override fun parseMangaList(doc: Document): List<Manga> = emptyList()
	override fun Element.parseTags(): Set<MangaTag> = emptySet()

	private fun buildQuery(tags: Collection<MangaTag>, language: Locale?): String {
		val joiner = StringUtil.StringJoiner(" ")
		tags.forEach { tag ->
			joiner.add("tag:\"")
			joiner.append(tag.key)
			joiner.append("\"")
		}
		language?.let { lc ->
			joiner.add("language:\"")
			joiner.append(lc.toLanguagePath())
			joiner.append("\"")
		}
		return joiner.complete()
	}
}
