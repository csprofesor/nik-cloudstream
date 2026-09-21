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
import com.lagradost.cloudstream3.*
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

            if (fixStream.startsWith("http://") || fixStream.startsWith("https://")) {
                Log.d("SinemaTvAzWebView", "EMITTING_STREAM=$fixStream")
                CoroutineScope(Dispatchers.IO).launch {
                    if (fixStream.contains("parsed.json") || fixStream.contains("catalog-api/episodes")) {
                        try {
                            if (fixStream.contains("catalog-api/episodes")) {
                                val jsonStr = app.get(fixStream, referer = mainUrl).text
                                val episodes = parseJson<List<CatalogEpisode>>(jsonStr)
                                episodes.firstOrNull()?.let { ep ->
                                    val masterPath = ep.m3u8MasterFilePath ?: ep.episodeVariants?.firstOrNull()?.filepath
                                    if (masterPath != null) {
                                        // Fetch masterPath inside WebView to inherit session/cookies and avoid 403 Forbidden
                                        withContext(Dispatchers.Main) {
                                            webView?.evaluateJavascript("fetch('$masterPath').then(r => r.text()).then(txt => window.AndroidBridge.onStreamFound(txt, '$masterPath'));", null)
                                        }
                                    }
                                }
                            } else {
                                val jsonStr = app.get(fixStream, referer = mainUrl).text
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

        val finalUrl = withContext(Dispatchers.IO) {
            try {
                val doc = app.get(targetUrl, referer = referer ?: mainUrl).document
                val iframeSrc = doc.selectFirst("iframe[src*=\"player.abyssplayer.com\"], iframe")?.attr("src")?.takeIf { it.isNotBlank() }
                when {
                    iframeSrc?.startsWith("//") == true -> "https:$iframeSrc"
                    iframeSrc?.startsWith("http") == true -> iframeSrc
                    iframeSrc != null -> "${mainUrl}${if (iframeSrc.startsWith("/")) "" else "/"}$iframeSrc"
                    else -> targetUrl
                }
            } catch (_: Exception) {
                targetUrl
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
                                // Mock top/self for domain checks
                                try {
                                    Object.defineProperty(window, 'top', { get: function() { return window; } });
                                } catch(e) {}

                                // Poll players and DOM thoroughly
                                setInterval(function() {
                                    try {
                                        if (typeof jwplayer !== 'undefined') {
                                            const player = jwplayer();
                                            if (player && player.getConfig) {
                                                const cfg = player.getConfig();
                                                if (cfg) {
                                                    if (cfg.file) window.AndroidBridge.onStreamFound(cfg.file, cfg.file);
                                                    if (cfg.playlist && cfg.playlist[0] && cfg.playlist[0].file) {
                                                        window.AndroidBridge.onStreamFound(cfg.playlist[0].file, cfg.playlist[0].file);
                                                    }
                                                    if (cfg.sources) {
                                                        cfg.sources.forEach(s => {
                                                            if (s.file) window.AndroidBridge.onStreamFound(s.file, s.file);
                                                        });
                                                    }
                                                }
                                            }
                                            if (player && player.getPlaylistItem) {
                                                const item = player.getPlaylistItem();
                                                if (item && item.file) {
                                                    window.AndroidBridge.onStreamFound(item.file, item.file);
                                                }
                                            }
                                        }

                                        if (typeof player !== 'undefined' && player.config) {
                                            if (player.config.url) window.AndroidBridge.onStreamFound(player.config.url, player.config.url);
                                            if (player.config.manifestUrl) window.AndroidBridge.onStreamFound(player.config.manifestUrl, player.config.manifestUrl);
                                        }

                                        if (typeof hls !== 'undefined' && hls.url) {
                                            window.AndroidBridge.onStreamFound(hls.url, hls.url);
                                        }

                                        // Scan body HTML for any m3u8/mp4 links
                                        const html = document.documentElement.innerHTML;
                                        const matches = html.match(/https?:\/\/[^\"'\s<>]+?\.(?:m3u8|mp4)(?:\?[^\"'\s<>]*)?/gi);
                                        if (matches) {
                                            matches.forEach(m => window.AndroidBridge.onStreamFound(m, m));
                                        }
                                    } catch(e) {}
                                }, 300);

                                const originalFetch = window.fetch;
                                window.fetch = async function(...args) {
                                    const response = await originalFetch.apply(this, args);
                                    try {
                                        const clone = response.clone();
                                        const text = await clone.text();
                                        if (text.includes('m3u8') || text.includes('mp4') || text.includes('json') || response.url.includes('api') || response.url.includes('playlist')) {
                                            window.AndroidBridge.onStreamFound(text, response.url);
                                        }
                                    } catch(e) {}
                                    return response;
                                };

                                const originalXHR = window.XMLHttpRequest.prototype.open;
                                window.XMLHttpRequest.prototype.open = function(method, url, ...args) {
                                    this.addEventListener('load', function() {
                                        try {
                                            if (this.responseText && (this.responseText.includes('m3u8') || this.responseText.includes('mp4') || this.responseText.includes('json') || url.includes('api') || url.includes('playlist'))) {
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

                        if (reqUrl.contains(".mp4") || reqUrl.contains(".m3u8") || reqUrl.contains("m3u") || reqUrl.contains("manifest") || reqUrl.contains("playlist") || reqUrl.contains("master") || reqUrl.contains("json") || reqUrl.contains("api") || reqUrl.contains("proxy") || reqUrl.contains("token")) {
                            emitStream(reqUrl)
                        }

                        return super.shouldInterceptRequest(view, request)
                    }
                }

                val needsIframeWrapper = finalUrl.contains("cdn2") || finalUrl.contains("token_movie") || finalUrl.contains("stloadi") || finalUrl.contains("allarknow") || finalUrl.contains("vv-player.php")
                if (needsIframeWrapper) {
                    val htmlWrapper = """
                        <!DOCTYPE html>
                        <html>
                        <head>
                            <meta charset="utf-8">
                            <style>
                                body, html { margin: 0; padding: 0; width: 100%; height: 100%; background: #000; overflow: hidden; }
                                iframe { width: 100%; height: 100%; border: none; }
                            </style>
                        </head>
                        <body>
                            <iframe src="$finalUrl" allowfullscreen></iframe>
                        </body>
                        </html>
                    """.trimIndent()
                    loadDataWithBaseURL(referer ?: mainUrl, htmlWrapper, "text/html", "UTF-8", null)
                } else {
                    loadUrl(finalUrl)
                }
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
