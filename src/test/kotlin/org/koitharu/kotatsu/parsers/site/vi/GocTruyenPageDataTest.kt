package org.koitharu.kotatsu.parsers.site.vi

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class GocTruyenPageDataTest {
	@Test
	fun extractsComicIdWithWhitespaceAndAllSiteQuoteStyles() {
		for (quote in listOf("`", "\"", "'")) {
			val html = "<script>const comic = {\n nameEn: ${quote}example$quote,\n id: ${quote}0001285725$quote,\n currentNumber: ${quote}24$quote\n};</script>"
			assertEquals("0001285725", GocTruyenPageData.comicId(html))
		}
	}

	@Test
	fun doesNotMistakeOtherObjectsOrMarkupForComicMetadata() {
		assertNull(GocTruyenPageData.comicId("<script>const other = { id: '123' };</script>"))
		assertNull(GocTruyenPageData.comicId("<p>comic = {id: '123'}</p>"))
		assertNull(GocTruyenPageData.comicId("<script>const comic = { id: 'not-a-comic-id' };</script>"))
	}

	@Test
	fun recognizesSessionExpiredEnvelopeEvenWithHttp200() {
		assertTrue(GocTruyenPageData.requiresAuthorization(JSONObject(
			"""{"status":false,"errorCode":400,"messages":["Phiên làm việc đã hết hạn, vui lòng tải lại."]}""",
		)))
		for (code in listOf("01", "02", "03")) {
			assertTrue(GocTruyenPageData.requiresAuthorization(JSONObject(
				"""{"status":true,"result":{"state":true,"codeState":"$code"}}""",
			)))
		}
		assertFalse(GocTruyenPageData.requiresAuthorization(JSONObject("""{"status":true,"result":{"data":[]}}""")))
		assertFalse(GocTruyenPageData.requiresAuthorization(JSONObject("""{"status":false,"errorCode":400,"messages":["Invalid input"]}""")))
	}
}
