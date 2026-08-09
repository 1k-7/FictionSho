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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class ProactiveWebViewInterceptor(private val context: Context) : Interceptor {

	private val executor = ContextCompat.getMainExecutor(context)
	@Volatile private var webView: WebView? = null
	@Volatile private var bootstrapped = false
	private val cfHosts = setOf("fictionzone.net", "www.fictionzone.net", "cdn.fictionzone.net")

	private object FetchResult {
		var code = 0
		var body = ""
		var headers = emptyMap<String, String>()
		var error: String? = null
	}

	class Bridge {
		@JavascriptInterface
		fun onResult(code: Int, body: String, hdrJson: String) {
			FetchResult.code = code
			FetchResult.body = body
			FetchResult.error = null
			try {
				val j = org.json.JSONObject(hdrJson)
				FetchResult.headers = mutableMapOf<String, String>().apply {
					for (k in j.keys()) put(k, j.getString(k))
				}
			} catch (_: Exception) {
				FetchResult.headers = emptyMap()
			}
		}

		@JavascriptInterface
		fun onError(msg: String) {
			FetchResult.error = msg
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
					override fun onPageFinished(view: WebView, url: String) {
						if (bootstrapped) return
						// Check if CF challenge is solved — the page should load fully
						view.evaluateJavascript(
							"(function(){return document.readyState;})();"
						) { state ->
							if (state == "\"complete\"" && url.contains("fictionzone.net")) {
								bootstrapped = true
								latch.countDown()
							}
						}
					}
					override fun onReceivedErrorCompat(v: WebView, code: Int, d: String?, url: String, mf: Boolean) {
						if (mf && !bootstrapped) {
							// This is the CF challenge page (403/503 error in WebView)
							// Don't fail — keep waiting for the challenge to complete
						}
						if (code == -2 || code == -3) {
							bootstrapped = true
							latch.countDown()
						}
					}
				}
				wv.loadUrl("https://fictionzone.net/")
				webView = wv
			}
			latch.await(20, TimeUnit.SECONDS)
			if (!bootstrapped) {
				// Timed out — mark as bootstrapped anyway and use fallback
				bootstrapped = true
			}
		}
	}

	override fun intercept(chain: Interceptor.Chain): Response {
		val req = chain.request()
		if (req.url.host !in cfHosts) return chain.proceed(req)

		ensureBootstrapped()
		val wv = webView

		if (wv == null) {
			// WebView failed to init — fall back to OkHttp
			return chain.proceed(req)
		}

		return fetchViaBridge(wv, req)
	}

	private fun fetchViaBridge(wv: WebView, req: Request): Response {
		val method = req.method
		val url = req.url.toString()
		val bodyStr = req.body?.let { val b = Buffer(); it.writeTo(b); b.readUtf8() }
		val safeUrl = url.replace("\\", "\\\\").replace("'", "\\'")

		val hdrs = buildString {
			append("{")
			var fi = true
			for (i in 0 until req.headers.size) {
				val n = req.headers.name(i).lowercase()
				if (n in setOf("host", "content-length", "connection", "transfer-encoding")) continue
				if (!fi) append(",")
				fi = false
				val v = req.headers.value(i).replace("\\", "\\\\").replace("'", "\\'")
				append("'${req.headers.name(i).replace("'", "\\'")}':'$v'")
			}
			append("}")
		}

		val bodyPart = if (bodyStr != null) {
			val safe = bodyStr.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")
			",body:'$safe'"
		} else ""

		val js = """
(function(){
	fetch('$safeUrl',{method:'$method',headers:$hdrs$bodyPart})
	.then(function(r){
		var h={};
		r.headers.forEach(function(v,k){h[k]=v;});
		return r.text().then(function(t){
			window.__ss.onResult(r.status,t,JSON.stringify(h));
		});
	})
	.catch(function(e){
		window.__ss.onError(String(e));
	});
})();
		""".trimIndent()

		// Reset result
		FetchResult.code = 0
		FetchResult.body = ""
		FetchResult.headers = emptyMap()
		FetchResult.error = null

		val latch = CountDownLatch(1)
		executor.execute { wv.evaluateJavascript(js) { latch.countDown() } }

		latch.await(30, TimeUnit.SECONDS)

		if (FetchResult.error != null) {
			throw IOException("WV fetch: ${FetchResult.error}")
		}
		if (FetchResult.code == 0) {
			throw IOException("WV fetch: no response")
		}

		val headersBuilder = okhttp3.Headers.Builder()
		FetchResult.headers.forEach { (k, v) -> headersBuilder.add(k, v) }

		return Response.Builder()
			.request(req).protocol(Protocol.HTTP_1_1)
			.code(FetchResult.code).message("OK")
			.headers(headersBuilder.build())
			.body(FetchResult.body.toResponseBody("text/html".toMediaTypeOrNull()))
			.build()
	}
}
