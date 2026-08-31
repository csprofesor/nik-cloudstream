package com.nikyokki.extractors

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink

class FilmKovasiOkRuExtractor : ExtractorApi() {
    override val name = "FilmKovası • OK.ru"
    override val mainUrl = "https://ok.ru"
    override val requiresReferer = false

    private val mapper = ObjectMapper()

    private val headers = mapOf(
        "Accept" to "*/*",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "Origin" to "https://ok.ru",
        "Referer" to "https://ok.ru/",
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Site" to "cross-site",
        "User-Agent" to USER_AGENT,
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val embedUrl = when {
            url.contains("/videoembed/") -> url
            url.contains("/video/") -> url.replace("/video/", "/videoembed/")
            else -> url
        }

        val videoId = Regex("""(?:/videoembed/|/video/)(\d+)""")
            .find(embedUrl)
            ?.groupValues
            ?.getOrNull(1)

        // The current OK.ru web player obtains signed CDN renditions from
        // videoPlayerMetadata. Use that first instead of depending on the
        // WebView's player DOM.
        if (!videoId.isNullOrBlank()) {
            val metadataEndpoints = listOf(
                "https://www.ok.ru/dk?cmd=videoPlayerMetadata",
                "https://ok.ru/dk?cmd=videoPlayerMetadata"
            )

            for (endpoint in metadataEndpoints) {
                val root = runCatching {
                    val response = app.post(
                        endpoint,
                        data = mapOf("mid" to videoId),
                        headers = headers,
                        referer = referer ?: embedUrl
                    )
                    mapper.readTree(response.text)
                }.getOrNull() ?: continue

                if (emitMetadata(root, callback)) return
            }
        }

        val document = try {
            app.get(
                embedUrl,
                headers = headers,
                referer = referer ?: "https://ok.ru/"
            ).document
        } catch (e: Throwable) {
            throw ErrorLoadingException("OK.ru embed alınamadı: " + (e.message ?: "bilinmeyen hata"))
        }

        val options = document.selectFirst("[data-options]")
            ?.attr("data-options")
            ?.replace("&quot;", """)
            ?.replace("&#34;", """)
            ?.replace("&amp;", "&")
            ?.trim()

        if (options.isNullOrBlank()) {
            throw ErrorLoadingException("OK.ru player data-options bulunamadı")
        }

        val player = try {
            mapper.readTree(options)
        } catch (e: Throwable) {
            throw ErrorLoadingException("OK.ru player JSON çözülemedi: " + (e.message ?: "bilinmeyen hata"))
        }

        val flashvars = player.path("flashvars")

        val metadata = flashvars.path("metadata").takeUnless {
            it.isMissingNode || it.isNull || it.asText().isBlank()
        }?.let {
            runCatching { mapper.readTree(it.asText()) }.getOrNull()
        }

        if (metadata != null && emitMetadata(metadata, callback)) return

        val metadataUrl = flashvars.path("metadataUrl").asText("").trim()
        if (metadataUrl.isNotBlank()) {
            val remoteMetadata = runCatching {
                mapper.readTree(app.post(metadataUrl, headers = headers).text)
            }.getOrNull()

            if (remoteMetadata != null && emitMetadata(remoteMetadata, callback)) return
        }

        throw ErrorLoadingException("OK.ru oynatılabilir kaynak döndürmedi")
    }

    private fun emitMetadata(
        metadata: JsonNode,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        var emitted = false
        val videos = metadata.path("videos")

        if (videos.isArray) {
            for (video in videos) {
                val streamUrl = video.path("url").asText("").let {
                    if (it.startsWith("//")) "https:$it" else it
                }
                if (streamUrl.isBlank()) continue

                val qualityName = video.path("name").asText("").uppercase()
                    .replace("MOBILE", "144p")
                    .replace("LOWEST", "240p")
                    .replace("LOW", "360p")
                    .replace("SD", "480p")
                    .replace("HD", "720p")
                    .replace("FULL", "1080p")
                    .replace("QUAD", "1440p")
                    .replace("ULTRA", "2160p")

                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = streamUrl,
                        type = INFER_TYPE
                    ) {
                        this.referer = "https://ok.ru/"
                        this.headers = headers
                        this.quality = getQualityFromName(qualityName)
                    }
                )
                emitted = true
            }
        }

        val hls = sequenceOf(
            metadata.path("hlsMasterPlaylistUrl").asText(""),
            metadata.path("hlsManifestUrl").asText(""),
            metadata.path("ondemandHls").asText("")
        ).firstOrNull { it.isNotBlank() }

        if (!hls.isNullOrBlank()) {
            callback(
                newExtractorLink(
                    source = name,
                    name = "$name HLS",
                    url = hls,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "https://ok.ru/"
                    this.headers = headers
                    this.quality = 1080
                }
            )
            emitted = true
        }

        return emitted
    }
}
