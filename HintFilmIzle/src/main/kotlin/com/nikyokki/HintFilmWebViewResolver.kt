package com.nikyokki

import android.annotation.SuppressLint
import android.content.Context
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.api.getContext
import com.lagradost.nicehttp.requestCreator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * HintFilmIzle-specific WebView resolver.
 *
 * This resolver owns the WebViewClient so Kinescope analytics requests are
 * rejected in shouldInterceptRequest before they reach the network stack.
 */
class HintFilmWebViewResolver(
    private val interceptUrl: Regex,
    private val userAgent: String?,
    private val script: String?,
    private val timeout: Long = 60_000L,
    private val blockedDomains: Set<String> = setOf(
        "mc.yandex.ru",
        "mc.yandex.com",
        "googletagmanager.com",
        "google-analytics.com"
    ),
    private val allowedHosts: Set<String> = setOf(
        "hintfilmizle.com",
        "kinescopecdn.net"
    )
) {
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun resolveUsingWebView(
        url: String,
        referer: String? = null,
        headers: Map<String, String> = emptyMap(),
        requestCallBack: (Request) -> Boolean
    ): Request? {
        val fixedRequest = AtomicReference<Request?>(null)
        val shouldExit = AtomicBoolean(false)
        var webView: WebView? = null

        suspend fun destroy() {
            if (!shouldExit.compareAndSet(false, true)) return
            withContext(Dispatchers.Main) {
                webView?.stopLoading()
                webView?.destroy()
                webView = null
            }
        }

        withContext(Dispatchers.Main) {
            webView = WebView(
                (getContext() as? Context)
                    ?: throw RuntimeException("No base context in HintFilmWebViewResolver")
            ).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                if (userAgent != null) settings.userAgentString = userAgent
            }

            webView!!.webViewClient = object : WebViewClient() {
                private fun hostOf(value: String): String? = runCatching {
                    android.net.Uri.parse(value).host?.lowercase()
                }.getOrNull()

                private fun isDomain(host: String?, domain: String): Boolean =
                    host == domain || host?.endsWith(".$domain") == true

                private fun blocked(value: String): Boolean {
                    val host = hostOf(value) ?: return false
                    return blockedDomains.any { isDomain(host, it) }
                }

                private fun allowed(value: String): Boolean {
                    val uri = runCatching { android.net.Uri.parse(value) }.getOrNull() ?: return false
                    val scheme = uri.scheme?.lowercase()
                    if (scheme != "http" && scheme != "https") return true
                    val host = uri.host?.lowercase() ?: return false
                    return allowedHosts.any { isDomain(host, it) }
                }

                private fun emptyResponse() = WebResourceResponse(
                    "text/plain",
                    "UTF-8",
                    ByteArrayInputStream(ByteArray(0))
                )

                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? = runBlocking {
                    val requestUrl = request.url.toString()

                    if (blocked(requestUrl)) {
                        android.util.Log.i("HintFilmWebView", "BLOCKED=$requestUrl")
                        return@runBlocking emptyResponse()
                    }

                    if (!allowed(requestUrl)) {
                        android.util.Log.i("HintFilmWebView", "BLOCKED_EXTERNAL=$requestUrl")
                        return@runBlocking emptyResponse()
                    }

                    if (script != null) {
                        view.post {
                            runCatching { view.evaluateJavascript(script, null) }
                        }
                    }

                    if (interceptUrl.containsMatchIn(requestUrl)) {
                        val req = runCatching {
                            requestCreator(
                                request.method,
                                requestUrl,
                                headers = request.requestHeaders
                            )
                        }.getOrNull()
                        if (req != null && requestCallBack(req)) {
                            fixedRequest.set(req)
                            android.util.Log.i("HintFilmWebView", "MANIFEST=$requestUrl")
                        }
                        destroy()
                        return@runBlocking null
                    }

                    // Null keeps the native Android WebView networking stack in control.
                    super.shouldInterceptRequest(view, request)
                }

                @SuppressLint("WebViewClientOnReceivedSslError")
                override fun onReceivedSslError(
                    view: WebView?,
                    handler: SslErrorHandler?,
                    error: SslError?
                ) {
                    handler?.proceed()
                }
            }

            webView!!.loadUrl(url, headers)
        }

        var elapsed = 0L
        while (!shouldExit.get() && elapsed < timeout) {
            fixedRequest.get()?.let { return it }
            delay(100L)
            elapsed += 100L
        }

        destroy()
        return fixedRequest.get()
    }
}
