package org.koitharu.kotatsu.parsers.site.vi

import org.json.JSONObject
import org.jsoup.Jsoup

internal object GocTruyenPageData {
	// Escape both braces: Android's ICU regex engine rejects a bare closing brace (unlike JDK 17).
	private val COMIC_OBJECT = Regex("""\bcomic\s*=\s*\{([^}]+)\}""", RegexOption.DOT_MATCHES_ALL)
	private val COMIC_ID = Regex("""\bid\s*:\s*([`"'])([0-9]+)\1""")

	fun comicId(html: String): String? = Jsoup.parse(html).select("script:not([src])").firstNotNullOfOrNull { script ->
		COMIC_OBJECT.findAll(script.data()).firstNotNullOfOrNull { comic ->
			COMIC_ID.find(comic.groupValues[1])?.groupValues?.get(2)
		}
	}

	fun requiresAuthorization(json: JSONObject): Boolean {
		val codeState = json.optJSONObject("result")?.optString("codeState")
		if (codeState in setOf("01", "02", "03")) return true
		if (json.optInt("errorCode") in setOf(401, 403)) return true
		val messages = json.optJSONArray("messages") ?: return false
		return json.optInt("errorCode") == 400 && (0 until messages.length()).any {
			messages.optString(it).contains("Phiên làm việc đã hết hạn", ignoreCase = true)
		}
	}
}
