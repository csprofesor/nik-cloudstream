package com.keyiflerolsun

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.ExtractorApi
import com.lagradost.cloudstream3.ExtractorLink
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.app
import com.lagradost.cloudstream3.utils.parsedSafe

/**
 * PeaceMakerst video endpoint.
 * The endpoint has returned more than one response shape over time, so both
 * videoSources and sourceList are supported and every usable source is tried.
 */
open class PeaceMakerst : ExtractorApi() {
    override val name = "PeaceMakerst"
    override val mainUrl = "https://peacemakerst.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val extRef = referer?.takeIf { it.isNotBlank() } ?: mainUrl
        val postUrl = if (url.contains("?")) "$url&do=getVideo" else "$url?do=getVideo"
        val hash = url.substringAfter("video/", "").substringBefore("?")

        Log.d("CloudStream_$name", "Requesting $postUrl")

        val response = try {
            app.post(
                postUrl,
                data = mapOf(
                    "hash" to hash,
                    "r" to extRef,
                    "s" to ""
                ),
                referer = extRef,
                headers = mapOf(
                    "Accept" to "application/json, text/javascript, */*; q=0.01",
                    "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                    "Origin" to mainUrl,
                    "X-Requested-With" to "XMLHttpRequest",
                    "User-Agent" to USER_AGENT
                )
            )
        } catch (e: Exception) {
            throw ErrorLoadingException("PeaceMakerst bağlantısı kurulamadı: ${e.message ?: "ağ hatası"}")
        }

        val body = response.text
        if (body.isBlank()) throw ErrorLoadingException("PeaceMakerst boş yanıt döndürdü")

        // Teve2 is returned as an embedded URL by some videos.
        val teve2Id = Regex("teve2\\.com\\.tr(?:\\\\/|/)embed(?:\\\\/|/)([^\\\"'\\s]+)")
            .find(body)?.groupValues?.getOrNull(1)

        if (!teve2Id.isNullOrBlank()) {
            val teve2 = try {
                app.get(
                    "https://www.teve2.com.tr/action/media/$teve2Id",
                    referer = "https://www.teve2.com.tr/embed/$teve2Id",
                    headers = mapOf("User-Agent" to USER_AGENT)
                ).parsedSafe<Teve2ApiResponse>()
            } catch (_: Exception) {
                null
            }

            val link = teve2?.media?.link?.let {
                normalizeUrl(it.serviceUrl + "//" + it.securePath)
            }
            if (!link.isNullOrBlank()) {
                emit(link, extRef, subtitleCallback, callback)
                return
            }
        }

        val parsed = body.trim().removePrefix("\uFEFF").parsedSafe<PeaceResponse>()
        val candidates = LinkedHashSet<String>()

        parsed?.videoSources?.asSequence()
            ?.mapNotNull { normalizeUrl(it.file) }
            ?.forEach(candidates::add)

        parsed?.sourceList?.values?.asSequence()
            ?.mapNotNull { normalizeUrl(it) }
            ?.forEach(candidates::add)

        // Fallback for response variants that Jackson cannot deserialize.
        Regex("(?:\\\"file\\\"|\\\"url\\\"|\\\"source\\\")\\s*:\\s*\\\"([^\\\"]+)\\\"")
            .findAll(body)
            .mapNotNull { normalizeUrl(it.groupValues[1]) }
            .forEach(candidates::add)

        if (candidates.isEmpty()) {
            throw ErrorLoadingException("PeaceMakerst video kaynağı bulunamadı")
        }

        var lastError: Throwable? = null
        for (candidate in candidates) {
            try {
                emit(candidate, extRef, subtitleCallback, callback)
                return
            } catch (e: Exception) {
                lastError = e
            }
        }

        throw ErrorLoadingException("PeaceMakerst video kaynağı açılamadı: ${lastError?.message ?: "bilinmeyen hata"}")
    }

    private fun normalizeUrl(value: String?): String? {
        var result = value?.trim()?.replace("\\/", "/")?.replace("&amp;", "&") ?: return null
        result = result.trim('"', '\'', '`', '”', '“', '’', '‘')
        if (result.startsWith("//")) result = "https:$result"
        return result.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    private suspend fun emit(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = url,
                type = INFER_TYPE
            ) {
                this.referer = referer
                this.quality = when {
                    url.contains("1080", true) -> Qualities.P1080.value
                    url.contains("720", true) -> Qualities.P720.value
                    url.contains("480", true) -> Qualities.P480.value
                    else -> Qualities.Unknown.value
                }
                this.headers = mapOf("User-Agent" to USER_AGENT)
            }
        )
    }

    data class PeaceResponse(
        @JsonProperty("videoImage") val videoImage: String? = null,
        @JsonProperty("videoSources") val videoSources: List<VideoSource> = emptyList(),
        @JsonProperty("sIndex") val sIndex: String? = null,
        @JsonProperty("sourceList") val sourceList: Map<String, String> = emptyMap()
    )

    data class VideoSource(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("type") val type: String? = null
    )

    data class Teve2ApiResponse(
        @JsonProperty("Media") val media: Teve2Media? = null
    )

    data class Teve2Media(
        @JsonProperty("Link") val link: Teve2Link? = null
    )

    data class Teve2Link(
        @JsonProperty("ServiceUrl") val serviceUrl: String? = null,
        @JsonProperty("SecurePath") val securePath: String? = null
    )

    private companion object {
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }
}
