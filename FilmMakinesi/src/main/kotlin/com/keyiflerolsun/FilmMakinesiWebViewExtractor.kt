package com.keyiflerolsun

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class FilmMakinesiWebViewExtractor(private val context: Context) : ExtractorApi() {
    override val name = "FilmMakinesi WebView"
    override val mainUrl = "https://filmmakinesi.to"
    override val requiresReferer = true

    private var webView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d("FLMM_WebView", "WEBVIEW_EXTRACTOR_START=$url")
        val foundStream = AtomicBoolean(false)

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
                        Log.d("FLMM_WebView", "BRIDGE_FOUND: $reqUrl")
                        val m3u8 = Regex("https?://[^\"'\\s<>]+(?:\\.m3u8|\\.txt|playlist|manifest|hls)[^\"'\\s<>]*", RegexOption.IGNORE_CASE).find(body)?.value 
                            ?: Regex("https?://[^\"'\\s<>]+(?:\\.m3u8|\\.txt|playlist|manifest|hls)[^\"'\\s<>]*", RegexOption.IGNORE_CASE).find(reqUrl)?.value
                            ?: reqUrl

                        if ((m3u8.contains("m3u8", true) || m3u8.contains("txt", true) || m3u8.contains("playlist", true) || m3u8.contains("hls", true)) && !foundStream.getAndSet(true)) {
                            Log.d("FLMM_WebView", "EMITTING_STREAM=$m3u8")
                            GlobalScope.launch(Dispatchers.IO) {
                                callback.invoke(
                                    newExtractorLink(
                                        source = "FilmMakinesi",
                                        name = "FilmMakinesi WebView",
                                        url = m3u8,
                                        type = ExtractorLinkType.M3U8
                                    ) {
                                        this.quality = Qualities.P1080.value
                                        this.headers = mapOf(
                                            "Referer" to url,
                                            "Origin" to mainUrl,
                                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                                        )
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
                                const originalFetch = window.fetch;
                                window.fetch = async function(...args) {
                                    const response = await originalFetch.apply(this, args);
                                    try {
                                        const clone = response.clone();
                                        const text = await clone.text();
                                        if (text.includes('m3u8') || text.includes('txt') || text.includes('playlist') || text.includes('hls')) {
                                            window.AndroidBridge.onStreamFound(text, response.url);
                                        }
                                    } catch(e) {}
                                    return response;
                                };
                                
                                const originalXHR = window.XMLHttpRequest.prototype.open;
                                window.XMLHttpRequest.prototype.open = function(method, url, ...args) {
                                    this.addEventListener('load', function() {
                                        try {
                                            if (this.responseText.includes('m3u8') || this.responseText.includes('txt') || this.responseText.includes('playlist')) {
                                                window.AndroidBridge.onStreamFound(this.responseText, url);
                                            }
                                        } catch(e) {}
                                    });
                                    originalXHR.apply(this, [method, url, ...args]);
                                };
                            })();
                        """
                        view?.evaluateJavascript(js, null)
                    }
                }

                loadUrl(url)
            }
        }
    }
}
