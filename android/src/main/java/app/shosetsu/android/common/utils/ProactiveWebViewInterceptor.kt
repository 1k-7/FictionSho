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
	private val cfHosts = setOf("fictionzone.net", "www.fictionzone.net", "cdn.fictionzone.net")

	// Per-request result objects
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
			val r = Result().also { it.code = code; it.body = body }
			try {
				val j = org.json.JSONObject(hdrJson)
				j.keys().forEach { k -> r.headers[k] = j.getString(k) }
			} catch (_: Exception) {}
			results[id] = r
			latches[id]?.countDown()
		}

		@JavascriptInterface
		fun onError(id: Long, msg: String) {
			val r = Result().also { it.error = msg }
			results[id] = r
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
					private var pageLoads = 0
					override fun onPageFinished(view: WebView, url: String) {
						pageLoads++
						if (bootstrapped) return
						// CF challenge usually takes 2 page loads: challenge page → actual page
						if (url.contains("fictionzone.net") && pageLoads >= 2) {
							bootstrapped = true
							latch.countDown()
						}
					}
					override fun onReceivedErrorCompat(v: WebView, code: Int, d: String?, url: String, mf: Boolean) {
						if (mf) pageLoads++ // CF challenge page is an "error"
					}
				}
				wv.loadUrl("https://fictionzone.net/")
				webView = wv
			}
			latch.await(20, TimeUnit.SECONDS)
			bootstrapped = true // Always mark bootstrapped to prevent deadlock
		}
	}

	override fun intercept(chain: Interceptor.Chain): Response {
		val req = chain.request()
		if (req.url.host !in cfHosts) return chain.proceed(req)

		ensureBootstrapped()
		val wv = webView ?: return chain.proceed(req)

		return fetchViaBridge(wv, req)
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

		if (result == null) throw IOException("WV fetch: timed out")
		if (result.error != null) throw IOException("WV fetch: ${result.error}")
		if (result.code == 0) throw IOException("WV fetch: no response")

		val hdrBuilder = okhttp3.Headers.Builder()
		result.headers.forEach { (k, v) -> hdrBuilder.add(k, v) }

		return Response.Builder()
			.request(req).protocol(Protocol.HTTP_1_1)
			.code(result.code).message("OK")
			.headers(hdrBuilder.build())
			.body(result.body.toResponseBody("text/html".toMediaTypeOrNull()))
			.build()
	}
}
