package app.shosetsu.android.common.utils

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.core.content.ContextCompat
import app.shosetsu.android.common.utils.webview.WebViewClientCompat
import app.shosetsu.android.common.utils.webview.setDefaultSettings
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class ProactiveWebViewInterceptor(private val context: Context) : Interceptor {

	private val executor = ContextCompat.getMainExecutor(context)
	@Volatile private var webView: WebView? = null
	@Volatile private var bootstrapped = false
	// Only route main site through WebView; CDN images get OkHttp (often CF-lite)
	private val cfHosts = setOf("fictionzone.net", "www.fictionzone.net")

	private val results = ConcurrentHashMap<Long, Result>()
	private val latches = ConcurrentHashMap<Long, CountDownLatch>()
	private val nextId = AtomicLong(0)

	private class Result {
		var code = 0
		var body = ""
		var headers = mutableMapOf<String, String>()
		var error: String? = null
	}

	inner class Bridge {
		@JavascriptInterface
		fun onResult(id: Long, code: Int, body: String, hdrJson: String) {
			val r = Result().apply { this.code = code; this.body = body }
			try {
				val j = org.json.JSONObject(hdrJson)
				j.keys().forEach { k -> r.headers[k] = j.getString(k) }
			} catch (_: Exception) {}
			results[id] = r
			latches[id]?.countDown()
		}
		@JavascriptInterface
		fun onError(id: Long, msg: String) {
			results[id] = Result().apply { error = msg }
			latches[id]?.countDown()
		}
	}

	@SuppressLint("SetJavaScriptEnabled")
	private fun ensureBootstrapped() {
		if (bootstrapped) return
		synchronized(this) {
			if (bootstrapped) return

			val latch = CountDownLatch(1)
			executor.execute {
				val wv = WebView(context).apply {
					setDefaultSettings()
					settings.javaScriptEnabled = true
					settings.domStorageEnabled = true
					addJavascriptInterface(Bridge(), "__ss")
				}
				wv.webViewClient = object : WebViewClientCompat() {
					var loads = 0
					override fun onPageFinished(view: WebView, url: String) {
						loads++
						// Bootstrap is done once we see any content loaded (CF challenge solved)
						if (!bootstrapped && loads >= 2 && url.contains("fictionzone.net")) {
							bootstrapped = true
							latch.countDown()
						}
					}
					override fun onReceivedErrorCompat(v: WebView, c: Int, d: String?, url: String, mf: Boolean) {
						loads++
					}
				}
				wv.loadUrl("https://fictionzone.net/")
				webView = wv
			}
			latch.await(30, TimeUnit.SECONDS)
			bootstrapped = true // Don't deadlock if CF hangs
		}
	}

	override fun intercept(chain: Interceptor.Chain): Response {
		val req = chain.request()
		val host = req.url.host

		// Let non-fictionzone hosts through OkHttp
		if (host !in cfHosts) return chain.proceed(req)

		// For images / static assets on main domain, let OkHttp try
		val path = req.url.encodedPath
		if (path.contains(".jpg") || path.contains(".png") || path.contains(".webp") ||
			path.contains(".gif") || path.contains(".svg") || path.contains(".ico") ||
			path.contains(".css") || path.contains(".js") || path.contains(".woff") ||
			path.contains(".ttf") || path.contains(".json")) {
			return chain.proceed(req)
		}

		ensureBootstrapped()
		val wv = webView ?: return chain.proceed(req)

		// GET requests → load page directly in WebView for full HTML
		if (req.method == "GET") {
			return loadPageInWebView(wv, req)
		}

		// POST/other → use fetch() bridge
		return fetchViaBridge(wv, req)
	}

	@SuppressLint("SetJavaScriptEnabled")
	private fun loadPageInWebView(wv: WebView, req: Request): Response {
		val latch = CountDownLatch(1)
		var html: String? = null
		var errorMsg: String? = null

		executor.execute {
			wv.webViewClient = object : WebViewClientCompat() {
				override fun onPageFinished(view: WebView, url: String) {
					view.evaluateJavascript(
						"(function(){return '<html>'+document.documentElement.outerHTML+'</html>';})();"
					) { result ->
						if (result != null && result != "null") {
							html = result
								.removeSurrounding("\"")
								.replace("\\\"", "\"")
								.replace("\\n", "\n")
								.replace("\\t", "\t")
								.replace("\\/", "/")
								.replace("\\\\", "\\")
								.replace("\\u003C", "<")
								.replace("\\u003E", ">")
						}
						latch.countDown()
					}
				}
				override fun onReceivedErrorCompat(
					v: WebView, code: Int, desc: String?, url: String, mainFrame: Boolean
				) {
					if (mainFrame) { errorMsg = desc; latch.countDown() }
				}
			}
			// Navigate to the target URL — cookies from the first page carry over
			wv.loadUrl(req.url.toString())
		}

		latch.await(25, TimeUnit.SECONDS)

		if (errorMsg != null) throw IOException("WV page load: $errorMsg")
		if (html.isNullOrEmpty()) throw IOException("WV page load: empty body")

		return Response.Builder()
			.request(req).protocol(Protocol.HTTP_1_1)
			.code(200).message("OK")
			.body(html.toResponseBody("text/html".toMediaTypeOrNull()))
			.build()
	}

	private fun fetchViaBridge(wv: WebView, req: Request): Response {
		val id = nextId.incrementAndGet()
		val latch = CountDownLatch(1)
		latches[id] = latch

		val method = req.method
		val safeUrl = req.url.toString().replace("\\", "\\\\").replace("'", "\\'")
		val bodyStr = req.body?.let { val b = Buffer(); it.writeTo(b); b.readUtf8() }

		val hdrs = buildString {
			append("{")
			var fi = true
			for (i in 0 until req.headers.size) {
				val n = req.headers.name(i).lowercase()
				if (n in setOf("host", "content-length", "connection", "transfer-encoding")) continue
				if (!fi) append(","); fi = false
				append("'${req.headers.name(i).replace("'","\\'")}':'${req.headers.value(i).replace("'","\\'").replace("\n","\\n")}'")
			}
			append("}")
		}
		val bodyPart = bodyStr?.let {
			",body:'${it.replace("\\","\\\\").replace("'","\\'").replace("\n","\\n")}'"
		} ?: ""

		val js = """fetch('$safeUrl',{method:'$method',headers:$hdrs$bodyPart}).then(function(r){var h={};r.headers.forEach(function(v,k){h[k]=v});return r.text().then(function(t){window.__ss.onResult($id,r.status,t,JSON.stringify(h))})}).catch(function(e){window.__ss.onError($id,String(e))})"""

		executor.execute { wv.evaluateJavascript(js, null) }

		latch.await(30, TimeUnit.SECONDS)
		val result = results.remove(id)
		latches.remove(id)

		if (result == null) throw IOException("WV POST: timed out")
		if (result.error != null) throw IOException("WV POST: ${result.error}")
		if (result.code == 0) throw IOException("WV POST: no response")

		val hb = okhttp3.Headers.Builder()
		result.headers.forEach { (k, v) -> hb.add(k, v) }

		return Response.Builder()
			.request(req).protocol(Protocol.HTTP_1_1)
			.code(result.code).message("OK").headers(hb.build())
			.body(result.body.toResponseBody("text/html".toMediaTypeOrNull()))
			.build()
	}
}
