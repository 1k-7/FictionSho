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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ProactiveWebViewInterceptor(private val context: Context) : Interceptor {

	private val executor = ContextCompat.getMainExecutor(context)
	@Volatile private var webView: WebView? = null
	@Volatile private var bootstrapped = false
	private val cfHosts = setOf("fictionzone.net", "www.fictionzone.net")

	private class PageResult {
		var html: String? = null
		var error: String? = null
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
				}
				wv.webViewClient = object : WebViewClientCompat() {
					private var cfSolved = false
					override fun onPageFinished(view: WebView, url: String) {
						if (cfSolved) return
						val cookies = CookieManager.getInstance().getCookie(url)
						if (cookies != null && cookies.contains("cf_clearance")) {
							cfSolved = true
							bootstrapped = true
							latch.countDown()
						} else {
							// Keep waiting - CF challenge not solved yet
						}
					}
					override fun onReceivedErrorCompat(v: WebView, c: Int, d: String?, u: String, m: Boolean) {
						// CF challenge pages return 403/503 errors - that's expected
					}
				}
				wv.loadUrl("https://fictionzone.net/")
				webView = wv
			}
			val ok = latch.await(60, TimeUnit.SECONDS)
			if (!ok || !bootstrapped) bootstrapped = true // Fallback
		}
	}

	override fun intercept(chain: Interceptor.Chain): Response {
		val req = chain.request()
		val host = req.url.host
		if (host !in cfHosts) return chain.proceed(req)

		val path = req.url.encodedPath.lowercase()
		if (path.let { it.endsWith(".jpg") || it.endsWith(".png") || it.endsWith(".webp") ||
			it.endsWith(".gif") || it.endsWith(".ico") || it.endsWith(".css") ||
			it.endsWith(".js") || it.endsWith(".woff") })
			return chain.proceed(req)

		ensureBootstrapped()
		val wv = webView ?: return chain.proceed(req)

		return loadAndExtract(wv, req)
	}

	private fun loadAndExtract(wv: WebView, req: Request): Response {
		val latch = CountDownLatch(1)
		val result = PageResult()

		executor.execute {
			val url = req.url.toString()
			val headers = mutableMapOf<String, String>()
			for (i in 0 until req.headers.size) {
				val n = req.headers.name(i).lowercase()
				if (n == "host" || n == "content-length" || n == "connection" || n == "transfer-encoding") continue
				headers[req.headers.name(i)] = req.headers.value(i)
			}

			wv.webViewClient = object : WebViewClientCompat() {
				private var done = false
				private var loads = 0

				override fun onPageFinished(view: WebView, pageUrl: String) {
					if (done) return
					loads++
					// After a few page loads (CF challenges), extract HTML
					if (!pageUrl.contains("fictionzone.net")) return
					view.evaluateJavascript(
						"(function(){return '<html>'+document.documentElement.outerHTML+'</html>';})();"
					) { html ->
						if (!done && html != null && html != "null" && html.length > 200) {
							done = true
							result.html = html
								.removeSurrounding("\"")
								.replace("\\\"", "\"")
								.replace("\\n", "\n")
								.replace("\\t", "\t")
								.replace("\\/", "/")
								.replace("\\\\", "\\")
								.replace("\\u003C", "<")
							latch.countDown()
						} else if (loads > 10 && !done) {
							// Give up after too many page loads (CF loop)
							done = true
							result.error = "CF challenge loop"
							latch.countDown()
						}
					}
				}

				override fun onReceivedErrorCompat(v: WebView, code: Int, d: String?, u: String, m: Boolean) {
					if (m && loads > 15) {
						done = true
						result.error = "CF failed after 15 loads"
						latch.countDown()
					}
				}
			}
			wv.loadUrl(url, headers)
		}

		val ok = latch.await(45, TimeUnit.SECONDS)
		if (!ok) throw IOException("WV: timeout loading ${req.url}")
		val err = result.error
		if (err != null) throw IOException("WV: $err")
		val html = result.html
		if (html == null) throw IOException("WV: empty response")

		return Response.Builder()
			.request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
			.body(html.toResponseBody("text/html".toMediaTypeOrNull()))
			.build()
	}
}
