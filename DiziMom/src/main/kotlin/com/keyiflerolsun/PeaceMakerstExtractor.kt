package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

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
                data = mapOf("hash" to hash, "r" to extRef, "s" to ""),
                referer = extRef,
                headers = mapOf(
                    "Accept" to "text/plain, application/json, text/javascript, */*; q=0.01",
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
            .trim()
            .removePrefix("\uFEFF")
            .replace("\\/", "/")
            .replace("&amp;", "&")

        if (body.isBlank()) {
            throw ErrorLoadingException("PeaceMakerst boş yanıt döndürdü")
        }

        // Servis zaman zaman geçersiz/eksik JSON döndürüyor.
        // response.parsed() kullanmak yerine ham gövdeden medya URL'sini çıkarıyoruz.
        val candidates = LinkedHashSet<String>()

        val quotedUrlRegex = Regex(
            "(?:\\\"(?:file|url|source|src|link|hls|stream|video)\\\"|(?:file|url|source|src|link|hls|stream|video))\\s*[:=]\\s*\\\"([^\\\"]+)\\\"",
            RegexOption.IGNORE_CASE
        )

        quotedUrlRegex.findAll(body)
            .mapNotNull { normalizeUrl(it.groupValues[1]) }
            .forEach(candidates::add)

        // JSON tamamen bozuk olsa bile doğrudan m3u8/mp4 URL'sini yakala.
        Regex(
            "https?://[^\\s\\\"'<>\\\\]+(?:\\.m3u8(?:\\?[^\\s\\\"'<>\\\\]*)?|\\.mp4(?:\\?[^\\s\\\"'<>\\\\]*)?)",
            RegexOption.IGNORE_CASE
        ).findAll(body)
            .mapNotNull { normalizeUrl(it.value) }
            .forEach(candidates::add)

        // Teve2 embed adresini de doğrudan yakala.
        Regex(
            "https?://(?:www\\.)?teve2\\.com\\.tr/(?:embed|video)/[^\\s\\\"'<>]+",
            RegexOption.IGNORE_CASE
        ).findAll(body)
            .mapNotNull { normalizeUrl(it.value) }
            .forEach(candidates::add)

        if (candidates.isEmpty()) {
            Log.d("CloudStream_$name", "No media URL found. Response prefix=${body.take(500)}")
            throw ErrorLoadingException("PeaceMakerst yanıtından video adresi çıkarılamadı")
        }

        for (candidate in candidates) {
            emit(candidate, extRef, callback)
        }
    }

    private fun normalizeUrl(value: String?): String? {
        var result = value?.trim() ?: return null
        result = result
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .trim('"', '\'', '`', '”', '“', '’', '‘', ',', ';')

        if (result.startsWith("//")) result = "https:$result"

        return result.takeIf {
            it.startsWith("http://") || it.startsWith("https://")
        }
    }

    private suspend fun emit(
        url: String,
        referer: String,
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
            }
        )
    }

    private companion object {
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }
}
