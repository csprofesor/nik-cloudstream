package com.nikyokki

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class HintFilmIzleWebViewExtractor(private val context: Context, private val pluginName: String) : ExtractorApi() {
    override val name = "HintFilmİzle WebView"
    override val mainUrl = "https://www.hintfilmizle.com"
    override val requiresReferer = true

    private var webView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d("HintFilmIzleWebView", "WEBVIEW_EXTRACTOR_START=$url")
        val foundStream = AtomicBoolean(false)
        val targetUrl = if (url.contains("?")) "$url&autoplay=1&muted=1&playsinline=1" else "$url?autoplay=1&muted=1&playsinline=1"

        withContext(Dispatchers.Main) {
            webView = WebView(context).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
                }

                addJavascriptInterface(object : Any() {
                    @JavascriptInterface
                    fun onStreamFound(body: String, reqUrl: String) {
                        Log.d("HintFilmIzleWebView", "BRIDGE_FOUND: $reqUrl")
                        val m3u8 = Regex("https?://[^\"'\\s<>]+(?:\\.m3u8|playlist|manifest|hls)[^\"'\\s<>]*", RegexOption.IGNORE_CASE).find(body)?.value 
                            ?: Regex("https?://[^\"'\\s<>]+(?:\\.m3u8|playlist|manifest|hls)[^\"'\\s<>]*", RegexOption.IGNORE_CASE).find(reqUrl)?.value
                            ?: reqUrl

                        if ((m3u8.contains("m3u8", true) || m3u8.contains("playlist", true) || m3u8.contains("manifest", true) || m3u8.contains("hls", true)) && !foundStream.getAndSet(true)) {
                            Log.d("HintFilmIzleWebView", "EMITTING_STREAM=$m3u8")
                            GlobalScope.launch(Dispatchers.IO) {
                                callback.invoke(
                                    newExtractorLink(
                                        source = pluginName,
                                        name = pluginName,
                                        url = m3u8,
                                        type = ExtractorLinkType.M3U8
                                    ) {
                                        this.quality = Qualities.P1080.value
                                        this.headers = mapOf("Referer" to "https://kinescope.io/", "Origin" to "https://kinescope.io")
                                    }
                                )
                            }
                        }
                    }
                }, "AndroidBridge")

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        val js = """
                            (function() {
                                setInterval(() => {
                                    try {
                                        const v = document.querySelector('video');
                                        if (v && v.paused) { v.muted = true; v.play(); }
                                        const btn = document.querySelector('[role="button"], .kinescope-player button, .play-button');
                                        if (btn) btn.click();
                                    } catch(e) {}
                                }, 300);

                                const originalFetch = window.fetch;
                                window.fetch = async function(...args) {
                                    const response = await originalFetch.apply(this, args);
                                    try {
                                        const clone = response.clone();
                                        const text = await clone.text();
                                        if (text.includes('m3u8') || text.includes('playlist') || text.includes('manifest') || text.includes('hls')) {
                                            window.AndroidBridge.onStreamFound(text, response.url);
                                        }
                                    } catch(e) {}
                                    return response;
                                };
                                
                                const originalXHR = window.XMLHttpRequest.prototype.open;
                                window.XMLHttpRequest.prototype.open = function(method, url, ...args) {
                                    this.addEventListener('load', function() {
                                        try {
                                            if (this.responseText && (this.responseText.includes('m3u8') || this.responseText.includes('playlist') || this.responseText.includes('manifest'))) {
                                                window.AndroidBridge.onStreamFound(this.responseText, url);
                                            }
                                        } catch(e) {}
                                    });
                                    return originalXHR.apply(this, [method, url, ...args]);
                                };

                                function scanRes() {
                                    try {
                                        const entries = performance.getEntriesByType('resource');
                                        for (let e of entries) {
                                            if (e.name && (e.name.includes('.m3u8') || e.name.includes('playlist') || e.name.includes('manifest'))) {
                                                window.AndroidBridge.onStreamFound(e.name, e.name);
                                            }
                                        }
                                    } catch(e) {}
                                }
                                setInterval(scanRes, 1000);
                            })();
                        """.trimIndent()
                        view?.evaluateJavascript(js, null)
                    }

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val reqUrl = request?.url?.toString() ?: ""
                        if (reqUrl.contains("ads", true) || reqUrl.contains("analytics", true) || reqUrl.contains("vast", true) || reqUrl.contains("banner", true) || reqUrl.contains("popunder", true) || reqUrl.contains("tracker", true) || reqUrl.contains("pixel", true) || reqUrl.contains("yandex.ru", true)) {
                            return WebResourceResponse("text/plain", "UTF-8", null)
                        }

                        if (reqUrl.contains(".m3u8", true) || reqUrl.contains("playlist", true) || reqUrl.contains("manifest", true) || reqUrl.contains("hls", true)) {
                            Log.d("HintFilmIzleWebView", "INTERCEPTED_REQ=$reqUrl")
                            if (!foundStream.getAndSet(true)) {
                                GlobalScope.launch(Dispatchers.IO) {
                                    callback.invoke(
                                        newExtractorLink(
                                            source = pluginName,
                                            name = pluginName,
                                            url = reqUrl,
                                            type = ExtractorLinkType.M3U8
                                        ) {
                                            this.quality = Qualities.P1080.value
                                            this.headers = mapOf("Referer" to "https://kinescope.io/", "Origin" to "https://kinescope.io")
                                        }
                                    )
                                }
                            }
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                }

                loadUrl(targetUrl, mapOf("Referer" to "$mainUrl/"))
            }
        }

        var elapsed = 0L
        while (!foundStream.get() && elapsed < 8000L) {
            delay(200L)
            elapsed += 200L
        }

        withContext(Dispatchers.Main) {
            try {
                webView?.destroy()
                webView = null
                Log.d("HintFilmIzleWebView", "WEBVIEW_EXTRACTOR_DESTROYED")
            } catch (e: Exception) {
                Log.e("HintFilmIzleWebView", "WEBVIEW_DESTROY_ERROR", e)
            }
        }
    }
}
