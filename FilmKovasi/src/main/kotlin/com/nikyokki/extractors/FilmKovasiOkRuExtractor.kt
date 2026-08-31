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

        val document = try {
            app.get(
                embedUrl,
                headers = headers,
                referer = referer ?: "https://ok.ru/"
            ).document
        } catch (e: Throwable) {
            throw ErrorLoadingException("OK.ru embed alınamadı: \${e.message}")
        }

        val options = document.selectFirst("[data-options]")
            ?.attr("data-options")
            ?.replace("&quot;", "\"")
            ?.replace("&#34;", "\"")
            ?.replace("&amp;", "&")
            ?.trim()

        if (options.isNullOrBlank()) {
            throw ErrorLoadingException("OK.ru player data-options bulunamadı")
        }

        val player = try {
            mapper.readTree(options)
        } catch (e: Throwable) {
            throw ErrorLoadingException("OK.ru player JSON çözülemedi: \${e.message}")
        }

        val flashvars = player.path("flashvars")
        var metadata: JsonNode? = null

        val inlineMetadata = flashvars.path("metadata")
        if (!inlineMetadata.isMissingNode && !inlineMetadata.isNull && inlineMetadata.asText().isNotBlank()) {
            metadata = runCatching {
                mapper.readTree(inlineMetadata.asText())
            }.getOrNull()
        }

        if (metadata == null) {
            val metadataUrl = flashvars.path("metadataUrl").asText("").trim()
            if (metadataUrl.isNotBlank()) {
                metadata = runCatching {
                    mapper.readTree(
                        app.post(metadataUrl, headers = headers).text
                    )
                }.getOrNull()
            }
        }

        if (metadata == null) {
            throw ErrorLoadingException("OK.ru metadata bulunamadı")
        }

        val videos = metadata.path("videos")
        var emitted = false

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

        if (!emitted) {
            throw ErrorLoadingException("OK.ru oynatılabilir kaynak döndürmedi")
        }
    }
}
