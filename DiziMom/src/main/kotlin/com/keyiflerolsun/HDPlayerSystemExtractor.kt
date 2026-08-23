package com.keyiflerolsun

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

open class HDPlayerSystem : ExtractorApi() {
    override val name = "HDPlayerSystem"
    // DiziMom exposes this host as hdplayersystem.com/video/... . The backend
    // currently serving the media API is hdplayersystem.live.
    override val mainUrl = "https://hdplayersystem.com"
    private val apiUrl = "https://hdplayersystem.live"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val extRef = referer?.takeIf { it.isNotBlank() } ?: mainUrl
        val vidId = when {
            url.contains("video/") -> url.substringAfter("video/").substringBefore("?")
            url.contains("?data=") -> url.substringAfter("?data=").substringBefore("&")
            else -> throw ErrorLoadingException("HDPlayerSystem video ID bulunamadı")
        }

        val postUrl = "$apiUrl/player/index.php?data=$vidId&do=getVideo"
        val response = app.post(
            postUrl,
            data = mapOf("hash" to vidId, "r" to extRef),
            referer = extRef,
            headers = mapOf(
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "X-Requested-With" to "XMLHttpRequest",
                "Accept" to "application/json, text/javascript, */*; q=0.01",
            )
        )

        val body = response.text.trim().replace("\\/", "/").replace("&amp;", "&")
        if (body.isBlank()) throw ErrorLoadingException("HDPlayerSystem boş yanıt döndürdü")

        val parsed = try { response.parsedSafe<SystemResponse>() } catch (_: Exception) { null }
        val mediaUrl = parsed?.securedLink?.takeIf { it.isNotBlank() }
            ?: parsed?.hls?.takeIf { it.isNotBlank() }
            ?: parsed?.videoSource?.takeIf { it.isNotBlank() }
            ?: Regex("https?://[^\\s\\\"'<>]+(?:\\.m3u8|\\.mp4)(?:\\?[^\\s\\\"'<>]*)?", RegexOption.IGNORE_CASE)
                .find(body)?.value
            ?: throw ErrorLoadingException("HDPlayerSystem video adresi bulunamadı")

        callback.invoke(
            newExtractorLink(source = name, name = name, url = mediaUrl, type = INFER_TYPE) {
                this.referer = extRef
                this.quality = Qualities.Unknown.value
            }
        )
    }

    data class SystemResponse(
        @JsonProperty("hls") val hls: String? = null,
        @JsonProperty("videoImage") val videoImage: String? = null,
        @JsonProperty("videoSource") val videoSource: String? = null,
        @JsonProperty("securedLink") val securedLink: String? = null,
    )
}
