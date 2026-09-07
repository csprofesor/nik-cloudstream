package com.nikyokki

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class VidmixiExtractor : ExtractorApi() {
    override val name = "Vidmixi"
    override val mainUrl = "https://vidmixi.com"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val response = app.get(url, referer = referer ?: mainUrl)
        val html = response.text
        val m3u8 = Regex("(?:file|source|src)\\s*[:=]\\s*[\\\"'](https?://(?:www\\.)?vidmixi\\.com/list/[^\\\"']+)")
            .find(html)?.groupValues?.getOrNull(1)
            ?: Regex("https?://(?:www\\.)?vidmixi\\.com/list/[^\\\"'\\s<>]+")
                .find(html)?.value
            ?: return
        callback.invoke(newExtractorLink(source = name, name = name, url = m3u8, type = ExtractorLinkType.M3U8) {
            this.referer = url
            this.quality = Qualities.Unknown.value
        })
        Regex("https?://(?:www\\.)?vidmixi\\.com/[^\\\"'\\s<>]+\\.vtt(?:\\?[^\\\"'\\s<>]*)?")
            .findAll(html).map { it.value }.distinct().forEach { subtitleCallback.invoke(SubtitleFile("Altyazı", it)) }
    }
}
