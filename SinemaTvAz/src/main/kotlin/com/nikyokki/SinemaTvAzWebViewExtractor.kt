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
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
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

    data class ParsedJson(
        val sources: List<ParsedSource>?
    )
    data class ParsedSource(
        val link: String?,
        val links: List<ParsedLink>?
    )
    data class ParsedLink(
        val quality: String?,
        val src: String?
    )

    data class CatalogEpisode(
        val m3u8MasterFilePath: String?,
        val episodeVariants: List<CatalogVariant>?
    )
    data class CatalogVariant(
        val filepath: String?
    )

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

            // Ignore metadata/content endpoints that are not actual video streams
            if (fixStream.contains("/contents/") || fixStream.contains("/user-stats/") || fixStream.contains("player-metrics")) {
                return
            }

            if ((fixStream.startsWith("http://") || fixStream.startsWith("https://")) && !foundStream.getAndSet(true)) {
                Log.d("SinemaTvAzWebView", "EMITTING_STREAM=$fixStream")
                CoroutineScope(Dispatchers.IO).launch {
                    if (fixStream.contains("parsed.json") || fixStream.contains("catalog-api/episodes")) {
                        try {
                            val jsonStr = app.get(fixStream, referer = mainUrl).text
                            if (fixStream.contains("parsed.json")) {
                                val parsed = parseJson<ParsedJson>(jsonStr)
                                parsed.sources?.forEach { src ->
                                    src.link?.let { link ->
                                        callback.invoke(
                                            newExtractorLink(
                                                source = "SinemaTvAzWebView",
                                                name = "SinemaTvAz Auto",
                                                url = link,
                                                type = ExtractorLinkType.M3U8,
                                            ) {
                                                this.quality = Qualities.Unknown.value
                                                this.headers = mapOf("Referer" to mainUrl)
                                            }
                                        )
                                    }
                                    src.links?.forEach { link ->
                                        link.src?.let { srcLink ->
                                            val qualityValue = when(link.quality) {
                                                "1080" -> Qualities.P1080.value
                                                "720" -> Qualities.P720.value
                                                "480" -> Qualities.P480.value
                                                "360" -> Qualities.P360.value
                                                else -> Qualities.Unknown.value
                                            }
                                            callback.invoke(
                                                newExtractorLink(
                                                    source = "SinemaTvAzWebView",
                                                    name = "SinemaTvAz ${link.quality}p",
                                                    url = srcLink,
                                                    type = if (srcLink.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                                                ) {
                                                    this.quality = qualityValue
                                                    this.headers = mapOf("Referer" to mainUrl)
                                                }
                                            )
                                        }
                                    }
                                }
                            } else {
                                val episodes = parseJson<List<CatalogEpisode>>(jsonStr)
                                episodes.firstOrNull()?.let { ep ->
                                    val masterPath = ep.m3u8MasterFilePath ?: ep.episodeVariants?.firstOrNull()?.filepath
                                    if (masterPath != null) {
                                        callback.invoke(
                                            newExtractorLink(
                                                source = "SinemaTvAzWebView",
                                                name = "SinemaTvAz",
                                                url = masterPath,
                                                type = if (masterPath.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                                            ) {
                                                this.quality = Qualities.Unknown.value
                                                this.headers = mapOf("Referer" to mainUrl)
                                            }
                                        )
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("SinemaTvAzWebView", "JSON parsing failed", e)
                        }
                    } else if (fixStream.contains(".m3u8") || fixStream.contains(".mp4") || fixStream.contains("playlist")) {
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
        }

        val targetUrl = if (url.contains("cdn1.sinematv.az")) {
            url.replace("cdn1.sinematv.az", "abyss.to")
        } else {
            url
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
                        val urls = Regex("https?://[^\"'\\s<>]+(?:\\.m3u8(?:\\?[^\"',\\s<>]*)?|\\.mp4(?:\\?[^\"',\\s<>]*)?|parsed\\.json(?:\\?[^\"',\\s<>]*)?|catalog-api/episodes(?:\\?[^\"',\\s<>]*)?)", RegexOption.IGNORE_CASE)
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
                                // Poll JWPlayer instance if present
                                setInterval(function() {
                                    try {
                                        if (typeof jwplayer !== 'undefined' && jwplayer().getPlaylistItem) {
                                            const item = jwplayer().getPlaylistItem();
                                            if (item && item.file) {
                                                window.AndroidBridge.onStreamFound(item.file, item.file);
                                            }
                                        }
                                    } catch(e) {}
                                }, 500);

                                const originalFetch = window.fetch;
                                window.fetch = async function(...args) {
                                    const response = await originalFetch.apply(this, args);
                                    try {
                                        const clone = response.clone();
                                        const text = await clone.text();
                                        if (text.includes('m3u8') || text.includes('mp4') || text.includes('parsed.json') || response.url.includes('parsed.json') || response.url.includes('catalog-api/episodes')) {
                                            window.AndroidBridge.onStreamFound(text, response.url);
                                        }
                                    } catch(e) {}
                                    return response;
                                };

                                const originalXHR = window.XMLHttpRequest.prototype.open;
                                window.XMLHttpRequest.prototype.open = function(method, url, ...args) {
                                    this.addEventListener('load', function() {
                                        try {
                                            if (this.responseText && (this.responseText.includes('m3u8') || this.responseText.includes('mp4') || this.responseText.includes('parsed.json') || url.includes('parsed.json') || url.includes('catalog-api/episodes'))) {
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

                        if (reqUrl.contains(".mp4") || reqUrl.contains(".m3u8") || reqUrl.contains("parsed.json") || reqUrl.contains("catalog-api/episodes")) {
                            emitStream(reqUrl)
                        }

                        return super.shouldInterceptRequest(view, request)
                    }
                }

                loadUrl(targetUrl)
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
