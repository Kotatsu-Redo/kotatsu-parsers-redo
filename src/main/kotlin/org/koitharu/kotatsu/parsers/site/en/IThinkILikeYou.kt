package org.koitharu.kotatsu.parsers.site.en

import okhttp3.Cookie
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaParserAuthProvider
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.SinglePageMangaParser
import org.koitharu.kotatsu.parsers.exception.AuthRequiredException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("ITHINKILIKEYOU", "IThinkILikeYou", "en", ContentType.COMICS)
internal class IThinkILikeYou(context: MangaLoaderContext) :
	SinglePageMangaParser(context, MangaParserSource.ITHINKILIKEYOU), MangaParserAuthProvider {

	override val configKeyDomain = ConfigKey.Domain("ithinkilikeyou.net")

	override val availableSortOrders: Set<SortOrder> = Collections.singleton(SortOrder.NEWEST)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities()

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableLocales = setOf(Locale.ENGLISH, Locale("es")),
	)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	private val chapterDateFormat = SimpleDateFormat("MMM d, yyyy", Locale.ENGLISH)

	/**
	 * Standalone "story" collections, each surfaced as its own comic. They are WordPress pages
	 * holding a list of posts; every post becomes a chapter and its body images become the pages.
	 * The spicy/short-story collections are Patreon-gated and require a logged-in session.
	 */
	private enum class StoryCollection(
		val title: String,
		val path: String,
		val gated: Boolean,
		val adult: Boolean,
	) {
		FREE_STORIES("Free Stories", "/free-stories/", gated = false, adult = false),
		SHORT_STORIES("Short Stories", "/stories/", gated = true, adult = true),
		SPICY("Spicy", "/spicy/", gated = true, adult = true),
		EXTRA_SPICY("Extra Spicy", "/extra-spicy/", gated = true, adult = true),
	}

	// region authorization

	override val authUrl: String
		get() = "https://$domain/login/"

	override suspend fun isAuthorized(): Boolean {
		mirrorAuthCookies()
		return authCookies().any { it.name.contains("wordpress_logged_in") }
	}

	private fun authCookies(): List<Cookie> =
		context.cookieJar.getCookies(domain) + context.cookieJar.getCookies("www.$domain")

	/**
	 * The in-app login goes through Patreon, which lands back on the `www` host, so the WordPress
	 * session cookies may be captured there while the canonical site (and our requests) use the
	 * bare domain. Re-issue those cookies as domain cookies on the bare host so they cover both.
	 */
	private fun mirrorAuthCookies() {
		val wpCookies = authCookies().filter { it.name.startsWith("wordpress") || it.name.startsWith("wp-") }
		val present = context.cookieJar.getCookies(domain).mapTo(HashSet()) { it.name }
		for (cookie in wpCookies) {
			if (cookie.name in present) continue
			context.cookieJar.insertCookie(
				domain,
				Cookie.Builder()
					.name(cookie.name)
					.value(cookie.value)
					.domain(domain)
					.path("/")
					.secure()
					.build(),
			)
		}
	}

	override suspend fun getUsername(): String {
		if (!isAuthorized()) {
			throw AuthRequiredException(source)
		}
		val body = webClient.httpGet("https://$domain/").parseHtml().body()
		return body.selectFirst(".um-user-name, .um-name, a[href*=/user/]")?.textOrNull()
			?: body.parseFailed("Cannot find username")
	}

	// endregion

	override suspend fun getList(order: SortOrder, filter: MangaListFilter): List<Manga> {
		val doc = webClient.httpGet("https://$domain/").parseHtml()
		// Each /series/ entry is a separate comic. The homepage grid carries titles + covers,
		// but a few series (e.g. the Spanish editions) only appear as plain menu links, so we
		// gather every distinct /series/ slug and enrich it from the grid where possible.
		val result = LinkedHashMap<String, Manga>()
		doc.select("#series-grid span.series-thumbnail-wrapper").forEach { item ->
			val href = item.selectFirst("a[href*=/series/]")?.attrAsRelativeUrlOrNull("href") ?: return@forEach
			val slug = href.seriesSlug() ?: return@forEach
			result[slug] = buildSeries(
				slug = slug,
				title = item.selectFirst(".series-rollover h3")?.textOrNull(),
				cover = item.selectFirst("img")?.src(),
			)
		}
		doc.select("a[href*=/series/]").forEach { a ->
			val slug = a.attrAsRelativeUrlOrNull("href")?.seriesSlug() ?: return@forEach
			if (slug !in result) {
				result[slug] = buildSeries(slug = slug, title = a.textOrNull(), cover = null)
			}
		}
		// The standalone story collections are English-only.
		val all = result.values + StoryCollection.entries.map { buildCollection(it) }
		return when (filter.locale?.language) {
			"es" -> all.filter { it.url.isSpanishSeries() }
			Locale.ENGLISH.language -> all.filterNot { it.url.isSpanishSeries() }
			else -> all
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val collection = StoryCollection.entries.find { it.path == manga.url }
		return if (collection != null) {
			getCollectionDetails(manga, collection)
		} else {
			getSeriesDetails(manga)
		}
	}

	private suspend fun getSeriesDetails(manga: Manga): Manga {
		// The chapter branch is what the app turns into the "Translation" language flag.
		val branch = if (manga.url.isSpanishSeries()) LANG_SPANISH else LANG_ENGLISH
		val firstPage = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val lastPage = firstPage.select("nav.pagination a.page-numbers")
			.mapNotNull { it.text().trim().toIntOrNull() }
			.maxOrNull() ?: 1
		val chapters = ArrayList<MangaChapter>()
		var firstCover: String? = null
		for (page in 1..lastPage) {
			val doc = if (page == 1) {
				firstPage
			} else {
				webClient.httpGet("${manga.url.toAbsoluteUrl(domain)}?comics_paged=$page").parseHtml()
			}
			doc.select("ul#comic-list > li.comic").forEach { li ->
				val href = li.selectFirst("a[href*=/comic/]")?.attrAsRelativeUrlOrNull("href") ?: return@forEach
				if (firstCover == null) {
					firstCover = li.selectFirst(".thmb img")?.src()
				}
				chapters.add(
					MangaChapter(
						id = generateUid(href),
						title = li.selectFirst(".comic-title")?.textOrNull(),
						number = 0f,
						volume = 0,
						url = href,
						scanlator = null,
						branch = branch,
						uploadDate = chapterDateFormat.parseSafe(li.selectFirst(".comic-post-date")?.textOrNull()),
						source = source,
					),
				)
			}
		}
		// Pages are listed newest-first; reverse so the first chapter is the oldest episode.
		chapters.reverse()
		return manga.copy(
			coverUrl = manga.coverUrl ?: firstCover,
			chapters = chapters.toNumbered(),
		)
	}

	private suspend fun getCollectionDetails(manga: Manga, collection: StoryCollection): Manga {
		if (collection.gated && !isAuthorized()) {
			throw AuthRequiredException(source)
		}
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		var firstCover: String? = manga.coverUrl
		val chapters = doc.select("ul.wp-block-latest-posts__list > li").mapNotNull { li ->
			val link = li.selectFirst("a.wp-block-latest-posts__post-title")
				?: li.selectFirst(".wp-block-latest-posts__featured-image a")
				?: return@mapNotNull null
			val href = link.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
			if (firstCover == null) {
				firstCover = li.selectFirst(".wp-block-latest-posts__featured-image img")?.src()
			}
			MangaChapter(
				id = generateUid(href),
				title = link.textOrNull() ?: li.selectFirst(".wp-block-latest-posts__post-title")?.textOrNull(),
				number = 0f,
				volume = 0,
				url = href,
				scanlator = null,
				branch = LANG_ENGLISH,
				uploadDate = chapterDateFormat.parseSafe(
					li.selectFirst(".wp-block-latest-posts__post-date")?.textOrNull(),
				),
				source = source,
			)
		}
		// A gated collection with no readable entries means the session is missing or expired.
		if (chapters.isEmpty() && collection.gated && !isAuthorized()) {
			throw AuthRequiredException(source)
		}
		return manga.copy(
			coverUrl = firstCover,
			chapters = chapters.reversed().toNumbered(),
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		mirrorAuthCookies()
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
		// Patron-only / early-access episodes drop the comic and show a "For Patrons Only" lock
		// block (plus a support placeholder image), so detect that before reading any images.
		if (doc.getElementById("patreon-login") != null) {
			throw AuthRequiredException(source)
		}
		// Comic episodes keep their pages in #spliced-comic; story posts keep them in .entry-content.
		val container = doc.selectFirst("#spliced-comic .default-lang")
			?: doc.selectFirst("#one-comic-option .default-lang")
			?: doc.selectFirst("article .entry-content")
			?: doc.parseFailed("No comic content found")
		return container.select("img")
			.mapNotNull { it.src()?.fixImageHost() }
			.filter { it.isContentImage() }
			.distinct()
			.map { url ->
				MangaPage(
					id = generateUid(url),
					url = url,
					preview = null,
					source = source,
				)
			}
	}

	private fun buildSeries(slug: String, title: String?, cover: String?): Manga {
		val url = "/series/$slug/"
		return baseManga(url, title?.takeUnless { it.isBlank() } ?: slug.toTitle(), cover, contentRating = null)
	}

	private fun buildCollection(collection: StoryCollection): Manga = baseManga(
		url = collection.path,
		title = collection.title,
		cover = null,
		// Only the spicy collections are adult; the regular comics and (short/free) stories are not.
		contentRating = if (collection.adult) ContentRating.ADULT else null,
	)

	private fun baseManga(url: String, title: String, cover: String?, contentRating: ContentRating?): Manga = Manga(
		id = generateUid(url),
		title = title,
		altTitles = emptySet(),
		url = url,
		publicUrl = url.toAbsoluteUrl(domain),
		rating = RATING_UNKNOWN,
		contentRating = contentRating,
		coverUrl = cover,
		tags = emptySet(),
		state = null,
		authors = emptySet(),
		source = source,
	)

	private fun List<MangaChapter>.toNumbered(): List<MangaChapter> =
		mapIndexed { i, chapter -> chapter.copy(number = i + 1f) }

	// Spanish editions use an "-espanol" slug; the main comic's translation is "creo-que-te-quiero".
	private fun String.isSpanishSeries(): Boolean {
		val slug = seriesSlug() ?: return false
		return slug.endsWith("-espanol") || slug == "creo-que-te-quiero"
	}

	private fun String.seriesSlug(): String? = substringAfter("/series/", "")
		.substringBefore('?')
		.substringBefore('#')
		.trim('/')
		.nullIfEmpty()

	private fun String.toTitle(): String = replace('-', ' ')
		.split(' ')
		.joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

	// Strips WordPress resized variants like "image-300x150.png", keeping only full-size art.
	private fun String.isResizedThumbnail(): Boolean = RESIZED_THUMBNAIL_REGEX.containsMatchIn(this)

	/**
	 * A real comic/story page image. Gated entries serve signed "?svw=1" URLs (not upload paths),
	 * while open comics and free stories use plain /wp-content/uploads/ files. UltimateMember
	 * profile photos and resized thumbnails are page furniture, not content.
	 */
	private fun String.isContentImage(): Boolean = when {
		contains("/ultimatemember/") -> false
		contains("svw=1") -> true
		contains("/wp-content/uploads/") -> !isResizedThumbnail()
		else -> false
	}

	// Story posts still embed images on the site's dead former domain (http://www.itily.net);
	// the same paths are served by the current domain over https.
	private fun String.fixImageHost(): String = replace(OLD_DOMAIN_REGEX, "https://$domain")

	private companion object {
		private val RESIZED_THUMBNAIL_REGEX = Regex("""-\d+x\d+\.\w+(\?.*)?$""")
		private val OLD_DOMAIN_REGEX = Regex("""^https?://(?:www\.)?itily\.net""")

		// Branch labels the app maps to a "Translation" language flag.
		private const val LANG_ENGLISH = "English"
		private const val LANG_SPANISH = "Español"
	}
}
