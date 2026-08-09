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
						if (!bootstrapped && loads >= 2 && url.contains("fictionzone.net")) {
							bootstrapped = true
							latch.countDown()
						}
					}
					override fun onReceivedErrorCompat(v: WebView, c: Int, d: String?, u: String, m: Boolean) {
						loads++
					}
				}
				wv.loadUrl("https://fictionzone.net/")
				webView = wv
			}
			latch.await(30, TimeUnit.SECONDS)
			bootstrapped = true
		}
	}

	override fun intercept(chain: Interceptor.Chain): Response {
		val req = chain.request()
		val host = req.url.host
		if (host !in cfHosts) return chain.proceed(req)

		// Let static assets pass through OkHttp
		val path = req.url.encodedPath.lowercase()
		if (path.let { it.endsWith(".jpg") || it.endsWith(".png") || it.endsWith(".webp") ||
			it.endsWith(".gif") || it.endsWith(".ico") || it.endsWith(".css") ||
			it.endsWith(".js") || it.endsWith(".woff") })
			return chain.proceed(req)

		ensureBootstrapped()
		val wv = webView ?: return chain.proceed(req)

		return bridgeFetch(wv, req)
	}

	private fun bridgeFetch(wv: WebView, req: Request): Response {
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
				val safeName = req.headers.name(i).replace("'", "\\'")
				val safeValue = req.headers.value(i).replace("'", "\\'").replace("\n", "\\n")
				append("'$safeName':'$safeValue'")
			}
			append("}")
		}
		val bodyPart = bodyStr?.let {
			",body:'${it.replace("\\","\\\\").replace("'","\\'").replace("\n","\\n")}'"
		} ?: ""

		val js = """fetch('$safeUrl',{method:'$method',headers:$hdrs$bodyPart}).then(function(r){var h={};r.headers.forEach(function(v,k){h[k]=v});return r.text().then(function(t){try{window.__ss.onResult($id,r.status,t,JSON.stringify(h))}catch(e){window.__ss.onError($id,'bridge-post-error')}})}).catch(function(e){window.__ss.onError($id,'fetch-error:'+e.message)})"""

		executor.execute { wv.evaluateJavascript(js, null) }

		val ok = latch.await(30, TimeUnit.SECONDS)
		val result = results.remove(id)
		latches.remove(id)

		if (result == null) throw IOException("WV: timeout")
		if (result.error != null) throw IOException("WV: ${result.error}")
		if (result.body.isEmpty()) throw IOException("WV: empty body")

		val hb = okhttp3.Headers.Builder()
		result.headers.forEach { (k, v) -> hb.add(k, v) }

		return Response.Builder()
			.request(req).protocol(Protocol.HTTP_1_1)
			.code(result.code).message("OK").headers(hb.build())
			.body(result.body.toResponseBody(result.headers["content-type"]?.toMediaTypeOrNull() ?: "text/html".toMediaTypeOrNull()))
			.build()
	}
}
