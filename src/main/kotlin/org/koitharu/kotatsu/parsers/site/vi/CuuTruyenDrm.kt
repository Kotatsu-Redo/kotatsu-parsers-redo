package org.koitharu.kotatsu.parsers.site.vi

import org.koitharu.kotatsu.parsers.bitmap.Bitmap
import org.koitharu.kotatsu.parsers.bitmap.Rect

internal object CuuTruyenDrm {

	private const val KEY = "3141592653589793"

	fun decode(bytes: ByteArray): List<Pair<Int, Int>> {
		val text = ByteArray(bytes.size) { index ->
			(bytes[index].toInt() xor KEY[index % KEY.length].code).toByte()
		}.toString(Charsets.UTF_8)
		require(text.startsWith("#v4|")) { "Unsupported CuuTruyen image map version" }
		return parse(text.removePrefix("#v4|"))
	}

	fun parse(text: String): List<Pair<Int, Int>> = text.split('|').map { value ->
		val parts = value.split('-')
		require(parts.size == 2) { "Invalid CuuTruyen image strip" }
		val destination = parts[0].toInt()
		val height = parts[1].toInt()
		require(destination >= 0 && height > 0) { "Invalid CuuTruyen image strip bounds" }
		destination to height
	}

	fun redraw(source: Bitmap, strips: List<Pair<Int, Int>>, create: (Int, Int) -> Bitmap): Bitmap {
		// The encoded order describes consecutive source strips, not consecutive destination strips.
		var end = 0L
		for ((destination, height) in strips.sortedBy { it.first }) {
			require(destination.toLong() == end) { "Overlapping or incomplete CuuTruyen image map" }
			end += height
			require(end <= source.height) { "CuuTruyen image strip exceeds image height" }
		}
		require(end == source.height.toLong()) { "Incomplete CuuTruyen image map" }
		val result = create(source.width, source.height)
		var sourceY = 0
		for ((destination, height) in strips) {
			result.drawBitmap(
				source,
				Rect(0, sourceY, source.width, sourceY + height),
				Rect(0, destination, source.width, destination + height),
			)
			sourceY += height
		}
		return result
	}
}
