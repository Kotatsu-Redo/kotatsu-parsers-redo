package org.koitharu.kotatsu.parsers.site.vi

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.bitmap.Bitmap
import org.koitharu.kotatsu.parsers.bitmap.Rect
import java.util.Base64

/** Source-specific Next.js Flight and image-layout decoding. */
internal object MangaMoriData {

	fun flight(document: Document): String = buildString {
		for (script in document.select("script:not([src])")) {
			val data = script.data().trim()
			if (!data.startsWith("self.__next_f.push(")) continue
			val chunk = JSONArray(data.substringAfter('(').substringBeforeLast(')'))
			if (chunk.optInt(0) == 1) append(chunk.getString(1))
		}
	}

	fun objects(flight: String, key: String): List<JSONObject> =
		Regex("\"${Regex.escape(key)}\":(?=\\{)").findAll(flight).map {
			JSONTokener(flight.substring(it.range.last + 1)).nextValue() as JSONObject
		}.toList()

	/** Component props whose first property is [key]; parse the whole object, including sibling fields. */
	fun props(flight: String, key: String): List<JSONObject> =
		Regex("\\{(?=\"${Regex.escape(key)}\":)").findAll(flight).map {
			JSONTokener(flight.substring(it.range.first)).nextValue() as JSONObject
		}.toList()

	fun decodeMap(encoded: String, nonce: String, rows: Int, cols: Int): IntArray {
		require(rows in 1..64 && cols in 1..64) { "Invalid MangaMori grid" }
		val size = rows * cols
		val data = Base64.getDecoder().decode(encoded)
		require(data.size == 6 + 2 * size) { "Invalid MangaMori map length" }
		// Match the site's Math.imul/FNV seed and signed 32-bit LCG overflow.
		var seed = 0x811c9dc5.toInt()
		intArrayOf(88, 107, 74, 118, 82, 109, 100, 81).forEachIndexed { index, value ->
			seed = (seed xor (value xor ((index shl 2) + 31))) * 0x1000193
		}
		for (char in nonce) seed = (seed xor char.code) * 0x1000193
		val decoded = IntArray(size * 2) { index ->
			seed = seed * 0x41c64e6d + 12345
			(data[index + 6].toInt() and 255) xor ((seed ushr 16) and 255)
		}
		return IntArray(size) { (decoded[it * 2] shl 8) or decoded[it * 2 + 1] }
			.also { validateMap(it, rows, cols) }
	}

	fun validateMap(map: IntArray, rows: Int, cols: Int) {
		require(rows in 1..64 && cols in 1..64 && map.size == rows * cols) { "Invalid MangaMori grid" }
		require(map.all { it in map.indices } && map.toSet().size == map.size) { "Invalid MangaMori permutation" }
	}

	fun redraw(source: Bitmap, rows: Int, cols: Int, map: IntArray, create: (Int, Int) -> Bitmap): Bitmap {
		validateMap(map, rows, cols)
		val width = source.width / cols
		val height = source.height / rows
		require(width > 0 && height > 0) { "MangaMori image is smaller than its grid" }
		val output = create(source.width, source.height)
		// Preserve any pixels outside the grid, including rounding remainders.
		output.drawBitmap(source, Rect(0, 0, source.width, source.height), Rect(0, 0, source.width, source.height))
		for (index in map.indices) {
			val tile = map[index]
			val x = tile % cols * width
			val y = tile / cols * height
			val dx = index % cols * width
			val dy = index / cols * height
			output.drawBitmap(source, Rect(x, y, x + width, y + height), Rect(dx, dy, dx + width, dy + height))
		}
		return output
	}
}
