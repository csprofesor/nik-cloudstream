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
import com.lagradost.cloudstream3.utils.INFER_TYPE
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
                        Log.d("SinemaTvAzWebView", "BRIDGE_FOUND: $reqUrl, body: $body")
                        val urls = Regex("https?://[^\"'\\s<>]+(?:\\.m3u8(?:\\?[^\"',\\s<>]*)?|\\.mp4(?:\\?[^\"',\\s<>]*)?)", RegexOption.IGNORE_CASE)
                            .findAll("$body $reqUrl")
                            .map { it.value }
                            .distinct()
                            .toList()

                        for (stream in urls) {
                            var fixStream = stream
                            if (fixStream.contains("cdn1.sinematv.az")) {
                                fixStream = fixStream.replace("cdn1.sinematv.az", "abyss.to")
                            }
                            if (!foundStream.getAndSet(true)) {
                                CoroutineScope(Dispatchers.IO).launch {
                                    callback.invoke(
                                        newExtractorLink(
                                            source = "SinemaTvAzWebView",
                                            name = "SinemaTvAz",
                                            url = fixStream,
                                            type = if (fixStream.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                                        ) {
                                            this.quality = Qualities.Unknown.value
                                            this.headers = mapOf("Referer" to mainUrl)
                                        }
                                    )
                                }
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
                                        if (text.includes('m3u8') || text.includes('mp4') || text.includes('playlist') || text.includes('balancer')) {
                                            window.AndroidBridge.onStreamFound(text, response.url);
                                        }
                                    } catch(e) {}
                                    return response;
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

                        if (reqUrl.contains(".mp4") || reqUrl.contains(".m3u8") || reqUrl.contains("playlist")) {
                            var fixUrl = reqUrl
                            if (fixUrl.contains("cdn1.sinematv.az")) {
                                fixUrl = fixUrl.replace("cdn1.sinematv.az", "abyss.to")
                            }
                            val isM3u8 = fixUrl.contains(".m3u8") || fixUrl.contains("playlist")
                            val type = if (isM3u8) INFER_TYPE else ExtractorLinkType.VIDEO
                            
                            if (!foundStream.getAndSet(true)) {
                                CoroutineScope(Dispatchers.IO).launch {
                                    callback.invoke(
                                        newExtractorLink(
                                            source = "SinemaTvAzWebView",
                                            name = "SinemaTvAz",
                                            url = fixUrl,
                                            type = type,
                                        ) {
                                            this.quality = Qualities.Unknown.value
                                            this.headers = mapOf("Referer" to mainUrl)
                                        }
                                    )
                                }
                            }
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
