package org.koitharu.kotatsu.parsers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs [MangaLoaderContext.evaluateJs] in a real WebView of the app on an emulator, over Chrome DevTools, for
 * parsers whose scripts need a browser. Behaves like the app's WebViewExecutor: loads the page, then runs the
 * script repeatedly until it returns something, and hands back the value JSON-quoted.
 *
 * Off unless `KOTATSU_WEBVIEW=1`. Needs the debug app running with a browser screen open, as a WebView only shows
 * up in DevTools while it exists. `ANDROID_SERIAL` picks the device when several are connected.
 */
internal object EmulatorWebView {

	val isEnabled: Boolean = System.getenv("KOTATSU_WEBVIEW") == "1"

	private const val APP_PACKAGE = "io.github.kotatsuredo.kotatsu.debug"
	private const val PORT = 9334
	private const val POLL_INTERVAL_MS = 200L
	private const val CALL_TIMEOUT_SECONDS = 40L
	private const val OLD_PAGE_MARK = "__kotatsuOldPage"

	private val mutex = Mutex()
	private val client = OkHttpClient()
	private val nextId = AtomicInteger()
	private val pending = ConcurrentHashMap<Int, CompletableFuture<JSONObject>>()
	private var socket: WebSocket? = null

	suspend fun evaluate(baseUrl: String, script: String, timeoutMs: Long): String? = mutex.withLock {
		withContext(Dispatchers.IO) {
			// Marks the current page, so the script can't run on it before the navigation has replaced it
			call("Runtime.evaluate", JSONObject().put("expression", "window.$OLD_PAGE_MARK = true"))
			call("Page.navigate", JSONObject().put("url", baseUrl))
			val expression = "(window.$OLD_PAGE_MARK ? null : (${script.trim()}))"
			val deadline = System.currentTimeMillis() + timeoutMs
			while (System.currentTimeMillis() < deadline) {
				val value = call(
					"Runtime.evaluate",
					JSONObject().put("expression", expression).put("returnByValue", true),
				).optJSONObject("result")?.optJSONObject("result")?.opt("value")
				when (value) {
					null, JSONObject.NULL -> Unit
					is String -> if (value.isNotBlank()) return@withContext JSONObject.quote(value)
					else -> return@withContext value.toString()
				}
				Thread.sleep(POLL_INTERVAL_MS)
			}
			null
		}
	}

	private fun call(method: String, params: JSONObject): JSONObject {
		val id = nextId.incrementAndGet()
		val answer = CompletableFuture<JSONObject>()
		pending[id] = answer
		val message = JSONObject().put("id", id).put("method", method).put("params", params)
		check(connection().send(message.toString())) { "The WebView connection is closed" }
		try {
			return answer.get(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
		} finally {
			pending.remove(id)
		}
	}

	private fun connection(): WebSocket = socket ?: connect().also { socket = it }

	private fun connect(): WebSocket {
		val pid = adb("shell", "pidof", APP_PACKAGE).trim()
		check(pid.isNotEmpty()) { "$APP_PACKAGE is not running" }
		adb("forward", "tcp:$PORT", "localabstract:webview_devtools_remote_$pid")
		val targets = client.newCall(Request.Builder().url("http://127.0.0.1:$PORT/json/list").build())
			.execute().use { JSONArray(it.body.string()) }
		val target = (0 until targets.length()).map { targets.getJSONObject(it) }
			.firstOrNull { it.optString("type") == "page" && it.has("webSocketDebuggerUrl") }
			?: error("No WebView in the app: open a browser screen, such as a source's settings > Open in browser")
		val url = target.getString("webSocketDebuggerUrl")
		return client.newWebSocket(
			Request.Builder().url(url).build(),
			object : WebSocketListener() {
				override fun onMessage(webSocket: WebSocket, text: String) {
					val message = JSONObject(text)
					pending[message.optInt("id", -1)]?.complete(message)
				}

				override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
					socket = null
					pending.values.forEach { it.completeExceptionally(t) }
				}
			},
		)
	}

	private fun adb(vararg args: String): String {
		val adb = System.getenv("ADB")
			?: File(System.getProperty("user.home"), "AppData/Local/Android/Sdk/platform-tools/adb.exe").path
		val process = ProcessBuilder(adb, *args).redirectErrorStream(true).start()
		val output = process.inputStream.bufferedReader().readText()
		check(process.waitFor() == 0) { "adb ${args.joinToString(" ")} failed: $output" }
		return output
	}
}
