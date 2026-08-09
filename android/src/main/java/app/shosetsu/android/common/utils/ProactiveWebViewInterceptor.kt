package app.shosetsu.android.common.utils

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
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
	private val cfHosts = setOf("fictionzone.net", "www.fictionzone.net")

	// For fetch()-based POST requests
	private val results = ConcurrentHashMap<Long, PostResult>()
	private val latches = ConcurrentHashMap<Long, CountDownLatch>()
	private val nextId = AtomicLong(0)

	private class PostResult {
		var code = 0
		var body = ""
		var headers = mutableMapOf<String, String>()
		var error: String? = null
	}

	private class GetResult {
		var html: String? = null
		var error: String? = null
	}

	inner class Bridge {
		@JavascriptInterface
		fun onResult(id: Long, code: Int, body: String, hdrJson: String) {
			val r = PostResult().apply { this.code = code; this.body = body }
			try {
				val j = org.json.JSONObject(hdrJson)
				j.keys().forEach { k -> r.headers[k] = j.getString(k) }
			} catch (_: Exception) {}
			results[id] = r
			latches[id]?.countDown()
		}
		@JavascriptInterface
		fun onError(id: Long, msg: String) {
			results[id] = PostResult().apply { error = msg }
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
					@Volatile var solved = false
					override fun onPageFinished(view: WebView, url: String) {
						if (solved) return
						val c = CookieManager.getInstance().getCookie(url)
						if (c != null && c.contains("cf_clearance")) {
							solved = true; bootstrapped = true; latch.countDown()
						}
					}
					override fun onReceivedErrorCompat(v: WebView, c: Int, d: String?, u: String, m: Boolean) {}
				}
				wv.loadUrl("https://fictionzone.net/")
				webView = wv
			}
			latch.await(60, TimeUnit.SECONDS)
			bootstrapped = true
		}
	}

	override fun intercept(chain: Interceptor.Chain): Response {
		val req = chain.request()
		if (req.url.host !in cfHosts) return chain.proceed(req)

		val path = req.url.encodedPath.lowercase()
		if (path.let { it.endsWith(".jpg") || it.endsWith(".png") || it.endsWith(".webp") ||
			it.endsWith(".gif") || it.endsWith(".ico") || it.endsWith(".css") ||
			it.endsWith(".js") || it.endsWith(".woff") })
			return chain.proceed(req)

		ensureBootstrapped()
		val wv = webView ?: return chain.proceed(req)

		return if (req.method == "GET") loadPageGet(wv, req) else fetchPost(wv, req)
	}

	private fun loadPageGet(wv: WebView, req: Request): Response {
		val latch = CountDownLatch(1)
		val result = GetResult()

		executor.execute {
			val url = req.url.toString()
			val extras = mutableMapOf<String, String>()
			for (i in 0 until req.headers.size) {
				val n = req.headers.name(i).lowercase()
				if (n == "host" || n == "content-length" || n == "connection" || n == "transfer-encoding") continue
				extras[req.headers.name(i)] = req.headers.value(i)
			}

			wv.webViewClient = object : WebViewClientCompat() {
				var done = false; var loads = 0
				override fun onPageFinished(view: WebView, pageUrl: String) {
					if (done) return; loads++
					if (!pageUrl.contains("fictionzone.net")) return
					view.evaluateJavascript(
						"(function(){return '<html>'+document.documentElement.outerHTML+'</html>';})();"
					) { h ->
						if (!done && h != null && h != "null" && h.length > 200) {
							done = true
							result.html = h.removeSurrounding("\"").replace("\\\"","\"").replace("\\n","\n")
								.replace("\\t","\t").replace("\\/","/").replace("\\\\","\\").replace("\\u003C","<")
							latch.countDown()
						} else if (loads > 12 && !done) { done = true; result.error = "CF loop"; latch.countDown() }
					}
				}
				override fun onReceivedErrorCompat(v: WebView, c: Int, d: String?, u: String, m: Boolean) {
					if (m && loads > 18) { done = true; result.error = "CF fail"; latch.countDown() }
				}
			}
			wv.loadUrl(url, extras)
		}

		if (!latch.await(45, TimeUnit.SECONDS)) throw IOException("GET timeout ${req.url}")
		if (result.error != null) throw IOException("GET: ${result.error}")
		val h = result.html ?: throw IOException("GET: empty")
		return Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
			.body(h.toResponseBody("text/html".toMediaTypeOrNull())).build()
	}

	private fun fetchPost(wv: WebView, req: Request): Response {
		val id = nextId.incrementAndGet()
		val latch = CountDownLatch(1)
		latches[id] = latch

		val method = req.method
		val safeUrl = req.url.toString().replace("\\","\\\\").replace("'","\\'")
		val bodyStr = req.body?.let { val b = Buffer(); it.writeTo(b); b.readUtf8() }
		val safeBody = (bodyStr ?: "").replace("\\","\\\\").replace("'","\\'").replace("\n","\\n")

		val hdrs = buildString {
			append("{")
			var fi = true
			for (i in 0 until req.headers.size) {
				val n = req.headers.name(i).lowercase()
				if (n in setOf("host","content-length","connection","transfer-encoding")) continue
				if (!fi) append(","); fi = false
				val name_ = req.headers.name(i).replace("'","\\'")
				val value_ = req.headers.value(i).replace("'","\\'").replace("\n","\\n")
				append("'$name_':'$value_'")
			}
			append("}")
		}
		val bodyClause = if (bodyStr != null) ",body:'$safeBody'" else ""

		val js = """fetch('$safeUrl',{method:'$method',headers:$hdrs$bodyClause}).then(function(r){var h={};r.headers.forEach(function(v,k){h[k]=v});return r.text().then(function(t){window.__ss.onResult($id,r.status,t,JSON.stringify(h))})}).catch(function(e){window.__ss.onError($id,'fetch:'+e.message)})"""

		executor.execute { wv.evaluateJavascript(js, null) }

		if (!latch.await(30, TimeUnit.SECONDS)) throw IOException("POST timeout ${req.url}")
		val r = results.remove(id); latches.remove(id)
		if (r == null) throw IOException("POST: null result")
		if (r.error != null) throw IOException("POST: ${r.error}")

		val hb = okhttp3.Headers.Builder()
		r.headers.forEach { (k, v) -> hb.add(k, v) }
		return Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(r.code).message("OK")
			.headers(hb.build()).body(r.body.toResponseBody("text/html".toMediaTypeOrNull())).build()
	}
}
