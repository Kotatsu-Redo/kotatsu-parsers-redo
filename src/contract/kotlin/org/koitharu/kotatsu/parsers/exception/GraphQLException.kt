package org.koitharu.kotatsu.parsers.exception

import okio.IOException
import org.json.JSONArray
import org.koitharu.kotatsu.parsers.InternalParsersApi

public class GraphQLException @InternalParsersApi constructor(errors: JSONArray) : IOException() {

	public val messages: List<String> = List(errors.length()) { i ->
		errors.getJSONObject(i).getString("message")
	}

	override val message: String
		get() = messages.joinToString("\n")
}
