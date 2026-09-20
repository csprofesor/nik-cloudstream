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
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class SinemaTvAzWebViewExtractor(private val context: Context) : ExtractorApi() {
    override val name = "SinemaTvAz Özel"
    override val mainUrl = "https://sinematv.az"
    override val requiresReferer = true

    private var webView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val foundStream = AtomicBoolean(false)

        fun emitStream(streamUrl: String) {
            var fixStream = streamUrl
            if (fixStream.contains("cdn1.sinematv.az")) {
                fixStream = fixStream.replace("cdn1.sinematv.az", "abyss.to")
            }
            if (fixStream.startsWith("//")) {
                fixStream = "https:$fixStream"
            }
            if ((fixStream.startsWith("http://") || fixStream.startsWith("https://")) && !foundStream.getAndSet(true)) {
                Log.d("SinemaTvAzWebView", "EMITTING_STREAM=$fixStream")
                CoroutineScope(Dispatchers.IO).launch {
                    if (fixStream.contains("parsed.json") || fixStream.contains("catalog-api/") || fixStream.contains("proxy/playlists")) {
                        try {
                            val jsonStr = app.get(fixStream, referer = mainUrl).text
                            
                            // Extract any and all m3u8 or mp4 URLs found in the JSON response
                            val urls = Regex("https?://[^\"'\\s<>]+(?:\\.m3u8(?:\\?[^\"',\\s<>]*)?|\\.mp4(?:\\?[^\"',\\s<>]*)?)", RegexOption.IGNORE_CASE)
                                .findAll(jsonStr)
                                .map { it.value }
                                .distinct()
                                .toList()

                            if (urls.isNotEmpty()) {
                                for (u in urls) {
                                    callback.invoke(
                                        newExtractorLink(
                                            source = "SinemaTvAzWebView",
                                            name = if (u.contains("1080")) "SinemaTvAz 1080p" else "SinemaTvAz",
                                            url = u,
                                            type = if (u.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                                        ) {
                                            this.quality = if (u.contains("1080")) Qualities.P1080.value else Qualities.Unknown.value
                                            this.headers = mapOf("Referer" to mainUrl)
                                        }
                                    )
                                }
                                return@launch
                            }
                        } catch (e: Exception) {
                            Log.e("SinemaTvAzWebView", "JSON content deep extraction failed", e)
                        }
                    }

                    callback.invoke(
                        newExtractorLink(
                            source = "SinemaTvAzWebView",
                            name = "SinemaTvAz",
                            url = fixStream,
                            type = if (fixStream.contains(".m3u8", true) || fixStream.contains("playlist", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                        ) {
                            this.quality = Qualities.Unknown.value
                            this.headers = mapOf("Referer" to mainUrl)
                        }
                    )
                }
            }
        }

        withContext(Dispatchers.Main) {
            webView = WebView(context).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36"
                }

                addJavascriptInterface(object : Any() {
                    @JavascriptInterface
                    fun onStreamFound(body: String, reqUrl: String) {
                        Log.d("SinemaTvAzWebView", "BRIDGE_FOUND: $reqUrl")
                        val urls = Regex("https?://[^\"'\\s<>]+(?:\\.m3u8(?:\\?[^\"',\\s<>]*)?|\\.mp4(?:\\?[^\"',\\s<>]*)?|parsed\\.json(?:\\?[^\"',\\s<>]*)?|catalog-api[a-zA-Z0-9_/-]*(?:\\?[^\"',\\s<>]*)?|proxy/playlists(?:\\?[^\"',\\s<>]*)?)", RegexOption.IGNORE_CASE)
                            .findAll("$body $reqUrl")
                            .map { it.value }
                            .distinct()
                            .toList()

                        for (stream in urls) {
                            emitStream(stream)
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
                                        if (text.includes('m3u8') || text.includes('mp4') || text.includes('catalog-api') || text.includes('proxy/playlists') || response.url.includes('catalog-api') || response.url.includes('proxy/playlists')) {
                                            window.AndroidBridge.onStreamFound(text, response.url);
                                        }
                                    } catch(e) {}
                                    return response;
                                };

                                const originalXHR = window.XMLHttpRequest.prototype.open;
                                window.XMLHttpRequest.prototype.open = function(method, url, ...args) {
                                    this.addEventListener('load', function() {
                                        try {
                                            if (this.responseText && (this.responseText.includes('m3u8') || this.responseText.includes('mp4') || this.responseText.includes('catalog-api') || this.responseText.includes('proxy/playlists') || url.includes('catalog-api') || url.includes('proxy/playlists'))) {
                                                window.AndroidBridge.onStreamFound(this.responseText, url);
                                            }
                                        } catch(e) {}
                                    });
                                    return originalXHR.apply(this, [method, url, ...args]);
                                };
                            })();
                        """.trimIndent()
                        evaluateJavascript(js, null)
                    }

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): WebResourceResponse? {
                        val reqUrl = request?.url?.toString() ?: ""

                        if (reqUrl.contains(".mp4") || reqUrl.contains(".m3u8") || reqUrl.contains("catalog-api") || reqUrl.contains("proxy/playlists") || reqUrl.contains("parsed.json")) {
                            emitStream(reqUrl)
                        }

                        return super.shouldInterceptRequest(view, request)
                    }
                }

                loadUrl(url)
            }
        }

        delay(15_000L)

        withContext(Dispatchers.Main) {
            try {
                webView?.destroy()
                webView = null
            } catch (_: Exception) {
                // Ignore
            }
        }
    }
}
