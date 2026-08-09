package app.shosetsu.android.common.utils

import android.annotation.SuppressLint
import android.content.Context
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

class ProactiveWebViewInterceptor(private val context: Context) : Interceptor {

	private val executor = ContextCompat.getMainExecutor(context)
	@Volatile private var webView: WebView? = null
	@Volatile private var bootstrapped = false
	private val cfHosts = setOf("fictionzone.net", "www.fictionzone.net", "cdn.fictionzone.net")

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
				}
				wv.webViewClient = object : WebViewClientCompat() {
					override fun onPageFinished(view: WebView, url: String) {
						if (!bootstrapped && (url.contains("fictionzone.net") || url == "about:blank")) {
							bootstrapped = true
							latch.countDown()
						}
					}
					override fun onReceivedErrorCompat(v: WebView, code: Int, d: String?, url: String, mf: Boolean) {
						if (mf && !bootstrapped) { bootstrapped = true; latch.countDown() }
					}
				}
				wv.loadUrl("https://fictionzone.net/")
				webView = wv
			}
			latch.await(25, TimeUnit.SECONDS)
		}
	}

	override fun intercept(chain: Interceptor.Chain): Response {
		val req = chain.request()
		if (req.url.host !in cfHosts) return chain.proceed(req)

		ensureBootstrapped()
		val wv = webView ?: return chain.proceed(req)

		val method = req.method
		val url = req.url.toString()
		val bodyStr = req.body?.let { val b = Buffer(); it.writeTo(b); b.readUtf8() }

		val jsBody = if (bodyStr != null) {
			val safe = bodyStr.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
			""","body":"$safe""""
		} else ""

		val safeUrl = url.replace("\\", "\\\\").replace("\"", "\\\"")
		val hdrs = buildString {
			append("{")
			var fi = true
			for (i in 0 until req.headers.size) {
				val n = req.headers.name(i).lowercase()
				if (n in setOf("host", "content-length", "connection", "transfer-encoding")) continue
				if (!fi) append(",")
				fi = false
				val v = req.headers.value(i).replace("\\", "\\\\").replace("\"", "\\\"")
				append("\"${req.headers.name(i).replace("\"", "\\\"")}\":\"$v\"")
			}
			append("}")
		}

		val js = """fetch("$safeUrl",{method:"$method",headers:$hdrs$jsBody}).then(function(r){var h={};r.headers.forEach(function(v,k){h[k]=v;});return r.text().then(function(b){return JSON.stringify({c:r.status,b:b,h:h});});}).catch(function(e){return JSON.stringify({e:String(e)});})"""

		val latch = CountDownLatch(1)
		var code = 200
		var body = ""
		var rHeaders = emptyMap<String, String>()
		var error: String? = null

		executor.execute {
			wv.evaluateJavascript(js) { result ->
				if (result == "null") { error = "null"; latch.countDown(); return@evaluateJavascript }
				val r = result.removeSurrounding("\"").replace("\\\"", "\"").replace("\\n", "\n")
				try {
					val j = org.json.JSONObject(r)
					if (j.has("e")) { error = j.getString("e") }
					else {
						code = j.getInt("c")
						body = j.getString("b")
						rHeaders = mutableMapOf<String,String>().apply {
							val h = j.getJSONObject("h")
							for (k in h.keys()) put(k, h.getString(k))
						}
					}
				} catch (ex: Exception) { error = ex.message }
				latch.countDown()
			}
		}

		latch.await(30, TimeUnit.SECONDS)
		if (error != null) throw IOException("WV fetch: $error")

		return Response.Builder()
			.request(req).protocol(Protocol.HTTP_1_1).code(code).message("OK")
			.headers(okhttp3.Headers.of(rHeaders))
			.body(body.toResponseBody(toMediaTypeOrNull("text/html"))).build()
	}
}
