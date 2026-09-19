package com.nikyokki

import android.annotation.SuppressLint
import android.content.Context
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
        withContext(Dispatchers.Main) {
            webView = WebView(context).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
                }

                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val reqUrl = request?.url?.toString() ?: ""

                        if (reqUrl.contains(".m3u8", true) || (reqUrl.contains(".mp4", true) && !reqUrl.contains("ads", true))) {
                            val isM3u8 = reqUrl.contains(".m3u8", true)
                            val type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            
                            GlobalScope.launch(Dispatchers.IO) {
                                callback.invoke(
                                    newExtractorLink(
                                        source = pluginName,
                                        name = pluginName,
                                        url = reqUrl,
                                        type = type
                                    ) {
                                        this.quality = Qualities.P1080.value
                                        this.headers = mapOf("Referer" to mainUrl)
                                    }
                                )
                            }
                        }

                        if (reqUrl.contains("yandex.ru") || reqUrl.contains("googletagmanager") || reqUrl.contains("ads") || reqUrl.contains("analytics")) {
                            return WebResourceResponse("text/plain", "UTF-8", null)
                        }

                        return super.shouldInterceptRequest(view, request)
                    }
                }

                loadUrl(url)
            }
        }

        delay(15_000)

        withContext(Dispatchers.Main) {
            try {
                webView?.destroy()
                webView = null
            } catch (e: Exception) {
                // Ignore
            }
        }
    }
}
