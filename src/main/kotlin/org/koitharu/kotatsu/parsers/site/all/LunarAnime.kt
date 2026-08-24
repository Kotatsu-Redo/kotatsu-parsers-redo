package org.koitharu.kotatsu.parsers.site.all

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.HttpStatusException
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.ContentRating
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
import org.koitharu.kotatsu.parsers.util.await
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.parseRaw
import org.koitharu.kotatsu.parsers.util.parseSafe
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPrivateKeySpec
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.EnumSet
import java.util.LinkedHashSet
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

@MangaSourceParser("LUNARANIME", "Lunar Manga")
internal class LunarAnime(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.LUNARANIME, pageSize = 30, searchPageSize = SEARCH_PAGE_SIZE) {

	override val configKeyDomain = org.koitharu.kotatsu.parsers.config.ConfigKey.Domain("lunarx.to")

	override val defaultSortOrder: SortOrder = SortOrder.UPDATED

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.RELEVANCE,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isMultipleTagsSupported = true,
			isYearSupported = true,
		)

	override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
		.add("Referer", "https://$domain/")
		.build()

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		return if (request.url.host.equals(CDN_HOST, ignoreCase = true)) {
			chain.proceed(
				request.newBuilder()
					.header("Referer", "https://$domain/")
					.header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
					.build(),
			)
		} else {
			chain.proceed(request)
		}
	}

	private val filterOptions = suspendLazy(initializer = ::fetchFilterOptions)

	override suspend fun getFilterOptions(): MangaListFilterOptions = filterOptions.get()

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		return if (
			order == SortOrder.UPDATED &&
			filter.query.isNullOrBlank() &&
			filter.tags.isEmpty() &&
			filter.states.isEmpty() &&
			filter.year <= 0 &&
			filter.locale == null
		) {
			fetchRecent(page)
		} else {
			search(page, filter)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val slug = manga.url.substringAfterLast('/')
		val detailsUrl = "$apiBaseUrl/api/manga/title/$slug"
		val details = apiGetJson(detailsUrl)
		val info = details.optJSONObject("manga") ?: return manga
		val passwordInfo = runCatching {
			val passwordUrl = "$apiBaseUrl/api/manga/password/info/$slug"
			apiGetJson(passwordUrl)
		}.getOrNull()
		val chaptersUrl = "$apiBaseUrl/api/manga/$slug"
		val chaptersRoot = apiGetJson(chaptersUrl)

		return parseManga(info).copy(
			id = manga.id,
			url = manga.url,
			publicUrl = manga.publicUrl,
			chapters = parseChapters(
				slug = slug,
				chapters = chaptersRoot.optJSONArray("data"),
				passwordInfo = passwordInfo,
			),
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val chapterUrl = chapter.url.toAbsoluteUrl(domain).toHttpUrl()
		val slug = chapterUrl.pathSegments.getOrNull(1).orEmpty()
		val chapterId = chapterUrl.pathSegments.getOrNull(2).orEmpty()
		val language = chapterUrl.queryParameter("lang") ?: "en"
		if (slug.isEmpty() || chapterId.isEmpty() || language.isEmpty()) {
			return emptyList()
		}

		val imageUrls = decryptChapterImages(chapterUrl.toString(), slug, chapterId, language)

		return imageUrls.mapIndexed { index, imageUrl ->
			MangaPage(
				id = generateUid("${chapter.url}#$index"),
				url = imageUrl,
				preview = null,
				source = source,
			)
		}
	}

	private suspend fun fetchRecent(page: Int): List<Manga> {
		val url = "$apiBaseUrl/api/manga/recent?page=$page&limit=$pageSize"
		val root = apiGetJson(url)
		val mangas = root.optJSONArray("our_mangas") ?: root.optJSONArray("mangas")
		return List(mangas?.length() ?: 0) { index ->
			parseManga(mangas!!.getJSONObject(index))
		}
	}

	private suspend fun search(page: Int, filter: MangaListFilter): List<Manga> {
		val limit = if (filter.query.isNullOrBlank()) pageSize else SEARCH_PAGE_SIZE
		val url = "$apiBaseUrl/api/manga/search".toHttpUrl().newBuilder()
			.addQueryParameter("page", page.toString())
			.addQueryParameter("limit", limit.toString())

		filter.query?.takeIf { it.isNotBlank() }?.let {
			url.addQueryParameter("query", it)
		}

		if (filter.tags.isNotEmpty()) {
			url.addQueryParameter("genres", filter.tags.joinToString(",") { it.key })
		}

		filter.states.firstOrNull()?.let { state ->
			url.addQueryParameter(
				"status",
				when (state) {
					MangaState.ONGOING -> "ongoing"
					MangaState.FINISHED -> "completed"
					MangaState.PAUSED -> "hiatus"
					MangaState.ABANDONED -> "cancelled"
					else -> return@let
				},
			)
		}

		if (filter.year > 0) {
			url.addQueryParameter("year", filter.year.toString())
		}

		filter.locale?.language
			?.takeIf { it.isNotBlank() }
			?.let { url.addQueryParameter("language", normalizeLanguageCode(it)) }

		url.addQueryParameter("sort", "relevance")

		val builtUrl = url.build()
		val root = apiGetJson(builtUrl.toString())
		val mangas = root.optJSONArray("manga") ?: JSONArray()
		return List(mangas.length()) { index ->
			parseManga(mangas.getJSONObject(index))
		}
	}

	private fun parseManga(json: JSONObject): Manga {
		val slug = json.optString("slug")
		val url = "/manga/$slug"
		val tags = LinkedHashSet<MangaTag>()
		parseStringArray(json.optString("genres")).forEach { genre ->
			tags += MangaTag(
				key = genre,
				title = formatTagTitle(genre),
				source = source,
			)
		}
		parseStringArray(json.optString("themes")).forEach { theme ->
			tags += MangaTag(
				key = theme,
				title = formatTagTitle(theme),
				source = source,
			)
		}
		json.optString("demographic").nullIfEmpty()?.let { demographic ->
			tags += MangaTag(
				key = demographic,
				title = formatTagTitle(demographic),
				source = source,
			)
		}

		val authors = LinkedHashSet<String>()
		authors += splitPeople(json.optString("author"))
		authors += splitPeople(json.optString("artist"))

		return Manga(
			id = generateUid(url),
			title = json.optString("title").ifBlank { slug },
			altTitles = parseStringArray(json.optString("alternative_titles")).toSet(),
			url = url,
			publicUrl = url.toAbsoluteUrl(domain),
			rating = RATING_UNKNOWN,
			contentRating = parseContentRating(json.optString("rating")),
			coverUrl = json.optString("cover_url").nullIfEmpty(),
			tags = tags,
			state = parseState(json.optString("publication_status")),
			authors = authors,
			largeCoverUrl = json.optString("banner_url").nullIfEmpty(),
			description = json.optString("description").nullIfEmpty(),
			source = source,
		)
	}

	private fun parseChapters(
		slug: String,
		chapters: JSONArray?,
		passwordInfo: JSONObject?,
	): List<MangaChapter> {
		if (chapters == null) return emptyList()
		val hasSeriesPassword = passwordInfo?.optBoolean("has_series_password") == true
		val chapterPasswords = passwordInfo?.optJSONArray("chapter_passwords")
		return List(chapters.length()) { index ->
			val chapter = chapters.getJSONObject(index)
			val chapterId = chapter.optString("chapter").ifBlank {
				chapter.optString("chapter_number")
			}
			val language = chapter.optString("language").ifBlank { "en" }
			val locked = hasSeriesPassword || isChapterLocked(chapterPasswords, chapterId, language)
			val rawTitle = chapter.optString("chapter_title").nullIfEmpty()
			val chapterNumber = chapter.optDouble("chapter_number", 0.0).toFloat()
			val displayTitle = buildString {
				if (chapterNumber > 0f) {
					append("Chapter ")
					append(formatChapterNumber(chapterNumber))
				}
				if (!rawTitle.isNullOrBlank()) {
					if (isNotEmpty()) append(" - ")
					append(rawTitle)
				}
				if (isEmpty()) {
					append("Chapter ")
					append(chapterId)
				}
				if (locked) {
					append(" [Locked]")
				}
			}
			MangaChapter(
				id = generateUid("/$slug/$chapterId/$language"),
				title = displayTitle,
				number = chapterNumber,
				volume = 0,
				url = "/manga/$slug/$chapterId?lang=$language",
				scanlator = chapter.optJSONObject("uploader_profile")?.optString("username")?.nullIfEmpty(),
				uploadDate = parseDate(chapter.optString("uploaded_at")),
				branch = languageToTitle(language),
				source = source,
			)
		}.distinctBy { chapter ->
			Triple(chapter.branch, chapter.volume, chapter.number)
		}
	}

	private fun isChapterLocked(passwords: JSONArray?, chapterId: String, language: String): Boolean {
		if (passwords == null) return false
		for (i in 0 until passwords.length()) {
			val item = passwords.optJSONObject(i) ?: continue
			val passwordChapter = item.opt("chapter_number")?.toString().orEmpty()
			val passwordLanguage = item.optString("language").nullIfEmpty()
			if (passwordChapter == chapterId && (passwordLanguage == null || passwordLanguage == language)) {
				return true
			}
		}
		return false
	}

	private suspend fun fetchFilterOptions(): MangaListFilterOptions {
		val languages = fetchLanguages()
		val tags = fetchTags()
		return MangaListFilterOptions(
			availableTags = tags,
			availableStates = EnumSet.of(
				MangaState.ONGOING,
				MangaState.FINISHED,
				MangaState.PAUSED,
				MangaState.ABANDONED,
			),
			availableLocales = languages,
		)
	}

	private suspend fun fetchLanguages(): Set<Locale> {
		val url = "$apiBaseUrl/api/manga/recent?page=1&limit=1"
		val root = apiGetJson(url)
		return parseStringArray(root.optJSONArray("available_languages"))
			.mapTo(LinkedHashSet()) { Locale(normalizeLanguageCode(it)) }
	}

	private suspend fun fetchTags(): Set<MangaTag> {
		val tags = LinkedHashSet<MangaTag>()
		for (page in 1..3) {
			val url = "$apiBaseUrl/api/manga/search?page=$page&limit=100"
			val root = apiGetJson(url)
			val mangas = root.optJSONArray("manga") ?: break
			for (i in 0 until mangas.length()) {
				val genres = parseStringArray(mangas.getJSONObject(i).optString("genres"))
				genres.forEach { genre ->
					tags += MangaTag(
						key = genre,
						title = formatTagTitle(genre),
						source = source,
					)
				}
			}
			if (mangas.length() < 100) {
				break
			}
		}
		return tags
			.groupBy { it.title }
			.values
			.map { variants -> variants.firstOrNull { it.key == it.title } ?: variants.first() }
			.sortedBy { it.title }
			.toCollection(LinkedHashSet())
	}

	private suspend fun apiGetJson(url: String): JSONObject {
		val request = Request.Builder()
			.get()
			.url(url)
			.headers(apiHeaders("GET", url))
			.build()
		return context.httpClient.newCall(request).await().use { response ->
			val body = response.body.string()
			if (response.code == 403 && body.isDeviceValidationResponse()) {
				requestDeviceValidation()
			}
			if (!response.isSuccessful) {
				throw HttpStatusException(response.message, response.code, response.request.url.toString())
			}
			JSONObject(body)
		}
	}

	private suspend fun apiHeaders(method: String, url: String): Headers {
		val dpop = signUrl(method, url.substringBefore('?'))
		return getRequestHeaders().newBuilder().apply {
			if (dpop.isNotEmpty()) {
				add("dpop", dpop)
			}
		}.build()
	}

	private fun requestDeviceValidation(): Nothing {
		keyPairJson = null
		dpopPrivateKey = null
		val validationUrl = "https://$domain/validate?redirect=/"
		try {
			context.requestBrowserAction(this, validationUrl)
		} catch (e: UnsupportedOperationException) {
			throw ParseException(
				"Device validation required. Open Lunar Manga in WebView and retry.",
				validationUrl,
				e,
			)
		}
	}

	private suspend fun signUrl(method: String, url: String): String {
		if (keyPairJson == null) {
			val raw = exportDeviceKeys() ?: return ""
			val parsed = runCatching { JSONObject(raw) }.getOrNull() ?: return ""
			val privateKey = runCatching {
				buildPrivateKey(parsed.getJSONObject("privateJwk"))
			}.getOrNull() ?: return ""
			keyPairJson = parsed
			dpopPrivateKey = privateKey
		}
		val keyPair = keyPairJson ?: return ""
		val privateKey = dpopPrivateKey ?: return ""
		return runCatching {
			buildDpop(method, url, privateKey, keyPair.getJSONObject("publicJwk"))
		}.getOrDefault("")
	}

	private suspend fun exportDeviceKeys(): String? {
		val raw = runCatching {
			context.evaluateJs("https://$domain/", EXPORT_KEYS_JS, 5000L)
		}.getOrNull()?.decodeWebViewString()
		return raw?.takeUnless {
			it.isBlank() || it == "null" || it == "{}"
		}
	}

	private val curveSpec: java.security.spec.ECParameterSpec by lazy {
		AlgorithmParameters.getInstance("EC").apply {
			init(ECGenParameterSpec("secp256r1"))
		}.getParameterSpec(java.security.spec.ECParameterSpec::class.java)
	}

	private fun buildDpop(method: String, url: String, privateKey: PrivateKey, publicJwk: JSONObject): String {
		val headerEncoded = base64UrlEncode(
			JSONObject()
				.put("typ", "dpop+jwt")
				.put("alg", "ES256")
				.put("jwk", publicJwk),
		)
		val payloadEncoded = base64UrlEncode(
			JSONObject()
				.put("htm", method.uppercase(Locale.ROOT))
				.put("htu", url)
				.put("iat", System.currentTimeMillis() / 1000)
				.put("jti", base64UrlEncode(ByteArray(16).apply { SecureRandom().nextBytes(this) })),
		)
		val signingInput = "$headerEncoded.$payloadEncoded"
		val derSignature = Signature.getInstance("SHA256withECDSA").apply {
			initSign(privateKey)
			update(signingInput.toByteArray(Charsets.UTF_8))
		}.sign()
		return "$signingInput.${base64UrlEncode(derToP1363(derSignature))}"
	}

	private fun buildPrivateKey(jwk: JSONObject): PrivateKey {
		val privateValue = BigInteger(1, Base64.getUrlDecoder().decode(jwk.getString("d").padBase64()))
		return KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(privateValue, curveSpec))
	}

	private fun derToP1363(der: ByteArray): ByteArray {
		val out = ByteArray(64)
		val rLen = der[3].toInt() and 0xFF
		val rOffset = 4
		val rOctets = der.copyOfRange(rOffset, rOffset + rLen)
		val sLenOffset = rOffset + rLen + 1
		val sLen = der[sLenOffset].toInt() and 0xFF
		val sOffset = sLenOffset + 1
		val sOctets = der.copyOfRange(sOffset, sOffset + sLen)
		val rBig = BigInteger(1, rOctets).toByteArray().takeLast(32).toByteArray()
		val sBig = BigInteger(1, sOctets).toByteArray().takeLast(32).toByteArray()
		System.arraycopy(rBig, 0, out, 32 - rBig.size, rBig.size)
		System.arraycopy(sBig, 0, out, 64 - sBig.size, sBig.size)
		return out
	}

	private suspend fun decryptChapterImages(chapterUrl: String, slug: String, chapterNum: String, lang: String): List<String> {
		val (rctx0, rctx1) = getReaderContext(chapterUrl)
		val (token, nonce) = generateToken(rctx0, rctx1, slug, chapterNum)
		val sessionData = fetchSessionData(token, lang)
		return decryptSessionImages(sessionData, rctx0, nonce)
	}

	private suspend fun getReaderContext(url: String): Pair<String, String> {
		val html = webClient.httpGet(url, getRequestHeaders()).parseRaw()
		for (match in nextFPushRegex.findAll(html)) {
			val segment = match.groupValues[1]
			val decoded = segment.replace("\\\\", "\\").replace("\\\"", "\"")
			for (dictMatch in dictRegex.findAll(decoded)) {
				val map = runCatching {
					dictMatch.value.toStringMap()
				}.getOrNull() ?: continue
				decodeReaderContext(map)?.let { return it }
			}
		}
		error("Failed to find LunarX reader context")
	}

	private suspend fun fetchSessionData(token: String, lang: String): String {
		val url = "$apiBaseUrl/api/manga/r/$token?language=$lang"
		val root = apiGetJson(url)
		return root.optJSONObject("data")
			?.optString("session_data")
			?.nullIfEmpty()
			?: root.optString("session_data").nullIfEmpty()
			?: error("session_data is empty")
	}

	private fun decodeReaderContext(values: Map<String, String>): Pair<String, String>? {
		for ((key, encoded) in values) {
			val envelope = runCatching {
				Base64.getDecoder().decode(encoded.reversed().padBase64())
			}.getOrNull() ?: continue
			var keyHash = 0
			key.forEach { char ->
				keyHash = (31 * keyHash + char.code) and 0xFF
			}
			val descriptorBytes = ByteArray(envelope.size) { index ->
				((envelope[index].toInt() and 0xFF) xor ((keyHash + 37 * index) and 0xFF)).toByte()
			}
			val fields = String(descriptorBytes, Charsets.ISO_8859_1).split('|')
			if (fields.size != 6 || fields[0] != "3") continue
			val seed = fields[1].toIntOrNull(16) ?: continue
			val multiplier = fields[2].toIntOrNull(16) ?: continue
			val increment = fields[3].toIntOrNull(16) ?: continue
			val encodedProgram = fields[4]
			if (encodedProgram.isEmpty() || encodedProgram.length % 3 != 0) continue
			val program = mutableListOf<Pair<Int, Int>>()
			var validProgram = true
			for (instruction in encodedProgram.chunked(3)) {
				val operation = instruction[0].digitToIntOrNull(16)
				val argument = instruction.substring(1).toIntOrNull(16)
				if (operation == null || argument == null || operation > 7) {
					validProgram = false
					break
				}
				program += operation to argument
			}
			if (!validProgram) continue
			val names = fields[5].split('.').filter { it.isNotEmpty() }
			if (names.isEmpty()) continue
			val hex = names.joinToString("") { values[it].orEmpty() }
			if (hex.length < 2 || hex.length % 2 != 0) continue

			val decoded = ByteArray(hex.length / 2)
			var state = seed and 0xFF
			var previous = 0
			var validHex = true
			for (index in decoded.indices) {
				val input = hex.substring(index * 2, index * 2 + 2).toIntOrNull(16)
				if (input == null) {
					validHex = false
					break
				}
				state = (state * multiplier + increment) and 0xFF
				decoded[index] = reverseReaderByte(input xor previous, index, state, program).toByte()
				previous = input
			}
			if (!validHex) continue
			if (decoded.size < 7 ||
				(decoded[0].toInt() and 0xFF) != 167 ||
				(decoded[1].toInt() and 0xFF) != 62 ||
				(decoded[2].toInt() and 0xFF) != 145
			) {
				continue
			}
			val firstLength = ((decoded[3].toInt() and 0xFF) shl 8) or (decoded[4].toInt() and 0xFF)
			val secondLength = ((decoded[5].toInt() and 0xFF) shl 8) or (decoded[6].toInt() and 0xFF)
			if (firstLength <= 0 || secondLength <= 0 || 7 + firstLength + secondLength > decoded.size) continue
			val first = String(decoded, 7, firstLength, Charsets.ISO_8859_1)
			val second = String(decoded, 7 + firstLength, secondLength, Charsets.ISO_8859_1)
			return first to second
		}
		return null
	}

	private fun reverseReaderByte(
		input: Int,
		index: Int,
		state: Int,
		program: List<Pair<Int, Int>>,
	): Int {
		var value = input and 0xFF
		for ((operation, argument) in program.asReversed()) {
			value = when (operation) {
				0 -> value xor argument
				1 -> value - argument
				2 -> {
					val shift = (argument and 7).takeUnless { it == 0 } ?: 1
					(value ushr shift) or (value shl (8 - shift))
				}
				3 -> ((value and 0x0F) shl 4) or ((value and 0xFF) ushr 4)
				4 -> value xor state
				5 -> value xor ((index * (argument or 1) + argument) and 0xFF)
				6 -> value.inv()
				else -> argument - value
			}
			value = value and 0xFF
		}
		return value
	}

	private fun generateToken(
		rctx0: String,
		rctx1: String,
		slug: String,
		index: String,
	): Pair<String, String> {
		require(rctx0.isNotEmpty() && rctx1.isNotEmpty()) { "LunarX reader context is empty" }
		val digest = "$rctx0\u0001$rctx1".sha256()
		val key = ByteArray(maxOf(rctx0.length, rctx1.length)) { position ->
			(
				rctx0[position % rctx0.length].code xor
					rctx1[position % rctx1.length].code xor
					(digest[position % digest.size].toInt() and 0xFF) xor
					((83 * position + 29) and 0xFF)
			).toByte()
		}
		val nonce = randomString(12)
		val timestamp = (System.currentTimeMillis() / 1000).toString(16)
		val suffix = randomString(6)
		val payload = "$timestamp|$nonce|$slug|$index|$suffix"
		val offset = Random.nextInt(256)
		val encrypted = ByteArray(payload.length + 1)
		encrypted[0] = offset.toByte()
		for (position in payload.indices) {
			encrypted[position + 1] = (
				payload[position].code xor
					(key[(position + offset) % key.size].toInt() and 0xFF) xor
					((offset + 83 * position) and 0xFF)
			).toByte()
		}
		return base64UrlEncode(encrypted) to nonce
	}

	private fun decryptSessionImages(sessionData: String, rctx0: String, nonce: String): List<String> {
		val ciphertext = runCatching {
			Base64.getDecoder().decode(sessionData.padBase64())
		}.recoverCatching {
			Base64.getUrlDecoder().decode(sessionData.padBase64())
		}.getOrThrow()
		val thumbprint = deviceKeyThumbprint()
		val keyInputs = buildList {
			if (!thumbprint.isNullOrEmpty()) {
				add("$rctx0\u0001$nonce\u0002$thumbprint")
			}
			add("$rctx0\u0001$nonce")
		}
		val decrypted = keyInputs.firstNotNullOfOrNull { keyInput ->
			runCatching {
				Cipher.getInstance("AES/CBC/PKCS5Padding").run {
					init(Cipher.DECRYPT_MODE, SecretKeySpec(keyInput.sha256(), "AES"), IvParameterSpec(ByteArray(16)))
					String(doFinal(ciphertext), Charsets.UTF_8)
				}
			}.getOrNull()?.takeIf { it.startsWith('{') || it.startsWith('[') }
		}?.replace("\\/", "/") ?: error("Failed to decrypt LunarX chapter data")
		val payload = parseDecryptedPayload(decrypted) ?: return emptyList()
		return jsonArrayToStrings(
			payload.optJSONObject("data")?.optJSONArray("images")
				?: payload.optJSONArray("images")
				?: payload.optJSONArray("chapter_images"),
		)
	}

	private fun deviceKeyThumbprint(): String? {
		val jwk = keyPairJson?.optJSONObject("publicJwk") ?: return null
		val x = jwk.optString("x").nullIfEmpty() ?: return null
		val y = jwk.optString("y").nullIfEmpty() ?: return null
		val canonical = """{"crv":"P-256","kty":"EC","x":"$x","y":"$y"}"""
		return base64UrlEncode(canonical.sha256())
	}

	private fun randomString(length: Int): String =
		(1..length).joinToString("") { randAlphabet[Random.nextInt(randAlphabet.length)].toString() }

	private fun String.toStringMap(): Map<String, String> {
		val json = JSONObject(this)
		return json.keys().asSequence().associateWith { key -> json.optString(key) }
	}

	private fun String.padBase64(): String = padEnd((length + 3) / 4 * 4, '=')

	private fun base64UrlEncode(data: JSONObject): String = base64UrlEncode(data.toString())

	private fun base64UrlEncode(data: String): String = base64UrlEncode(data.toByteArray(Charsets.UTF_8))

	private fun base64UrlEncode(data: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(data)

	private fun String.decodeWebViewString(): String {
		if (length < 2 || first() != '"' || last() != '"') {
			return this
		}
		return substring(1, lastIndex)
			.replace("\\\"", "\"")
			.replace("\\\\", "\\")
			.replace("\\/", "/")
			.replace("\\n", "\n")
			.replace("\\r", "\r")
			.replace("\\t", "\t")
			.replace(unicodeEscapeRegex) { match ->
				match.groupValues[1].toInt(16).toChar().toString()
			}
	}

	private fun String.isDeviceValidationResponse(): Boolean {
		return contains("requires_device_binding", ignoreCase = true) ||
			contains("requires_validation", ignoreCase = true) ||
			contains("Device not validated", ignoreCase = true) ||
			contains("validate", ignoreCase = true)
	}

	private fun parseDecryptedPayload(payload: String): JSONObject? {
		runCatching { JSONObject(payload) }.getOrNull()?.let { return it }
		if (payload.startsWith("\"") && payload.endsWith("\"")) {
			val unwrapped = runCatching {
				JSONArray("[${payload}]").getString(0)
			}.getOrNull()?.replace("\\/", "/")
			if (!unwrapped.isNullOrBlank()) {
				runCatching { JSONObject(unwrapped) }.getOrNull()?.let { return it }
			}
		}
		return null
	}

	private fun parseStringArray(raw: String?): List<String> {
		if (raw.isNullOrBlank()) return emptyList()
		return runCatching {
			val array = JSONArray(raw)
			List(array.length()) { index ->
				array.optString(index).trim()
			}.filter { it.isNotEmpty() }
		}.getOrDefault(emptyList())
	}

	private fun parseStringArray(array: JSONArray?): List<String> {
		if (array == null) return emptyList()
		return List(array.length()) { index ->
			array.optString(index).trim()
		}.filter { it.isNotEmpty() }
	}

	private fun jsonArrayToStrings(array: JSONArray?): List<String> {
		if (array == null) return emptyList()
		return List(array.length()) { index ->
			array.optString(index).trim()
		}.filter { it.isNotEmpty() }
	}

	private fun splitPeople(raw: String?): List<String> {
		return raw.orEmpty()
			.split(',', '&', '/', ';')
			.mapNotNull { it.trim().nullIfEmpty() }
	}

	private fun parseContentRating(raw: String?): ContentRating? {
		return when (raw?.trim()?.uppercase(Locale.ROOT)) {
			"G", "PG", "SAFE" -> ContentRating.SAFE
			"PG-13", "R", "R-15" -> ContentRating.SUGGESTIVE
			"R-18", "NSFW", "ADULT" -> ContentRating.ADULT
			else -> null
		}
	}

	private fun parseState(raw: String?): MangaState? {
		return when (raw?.trim()?.lowercase(Locale.ROOT)) {
			"ongoing" -> MangaState.ONGOING
			"completed", "finished" -> MangaState.FINISHED
			"hiatus", "paused" -> MangaState.PAUSED
			"cancelled", "canceled", "dropped", "abandoned" -> MangaState.ABANDONED
			else -> null
		}
	}

	private fun parseDate(raw: String?): Long {
		if (raw.isNullOrBlank()) return 0L
		return synchronized(dateFormats) {
			dateFormats.firstNotNullOfOrNull { format ->
				runCatching { format.parseSafe(raw) }.getOrNull()?.takeIf { it != 0L }
			} ?: 0L
		}
	}

	private fun formatChapterNumber(number: Float): String {
		return if (number % 1f == 0f) {
			number.toInt().toString()
		} else {
			number.toString()
		}
	}

	private fun formatTagTitle(value: String): String =
		value.replaceFirstChar { char -> char.titlecase(Locale.ROOT) }

	private fun languageToTitle(code: String): String {
		return when (normalizeLanguageCode(code)) {
			"bg" -> "Bulgarian"
			"en" -> "English"
			"fr" -> "French"
			"id" -> "Indonesian"
			"ja" -> "Japanese"
			"ko" -> "Korean"
			else -> code.uppercase(Locale.ROOT)
		}
	}

	private fun normalizeLanguageCode(code: String): String {
		return when (code.lowercase(Locale.ROOT)) {
			"in" -> "id"
			else -> code.lowercase(Locale.ROOT)
		}
	}

	private fun String.sha256(): ByteArray = MessageDigest.getInstance("SHA-256").digest(toByteArray())

	private var keyPairJson: JSONObject? = null
	private var dpopPrivateKey: PrivateKey? = null

	private companion object {
		private const val apiBaseUrl = "https://api.lunarx.to"
		private const val CDN_HOST = "vault.lunarx.to"
		private const val SEARCH_PAGE_SIZE = 100
		private const val EXPORT_KEYS_JS = """
			(function() {
				try {
					var stored = localStorage.getItem("lunar-device-key-jwk");
					if (stored) return JSON.parse(stored);
				} catch(e) {}
				return null;
			})();
		"""
		private val nextFPushRegex = Regex("""self\.__next_f\.push\(\[1,"(.*?)"\]\)""", RegexOption.DOT_MATCHES_ALL)
		private val dictRegex = Regex("""\{[^{}]*\}""")
		private val randAlphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
		private val unicodeEscapeRegex = Regex("""\\u([0-9A-Fa-f]{4})""")
		private val dateFormats = listOf(
			SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US),
			SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US),
		).onEach {
			it.timeZone = TimeZone.getTimeZone("UTC")
		}
	}
}
