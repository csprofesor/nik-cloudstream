package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject

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
                    "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "application/json, text/javascript, */*; q=0.01",
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

        Log.d("CloudStream_$name", "Response prefix=${body.take(500)}")

        val candidates = LinkedHashSet<String>()

        // PeaceMakerst'in normal cevabı: { videoSources: [{ file: "..." }] }
        try {
            val json = JSONObject(body)
            val sources = json.optJSONArray("videoSources")
            if (sources != null) {
                for (i in 0 until sources.length()) {
                    val source = sources.optJSONObject(i) ?: continue
                    addCandidate(candidates, source.optString("file"))
                }
            }

            // Bazı cevaplarda sourceList de bulunuyor.
            val sourceList = json.optJSONObject("sourceList")
            if (sourceList != null) {
                val keys = sourceList.keys()
                while (keys.hasNext()) {
                    addCandidate(candidates, sourceList.optString(keys.next()))
                }
            }
        } catch (_: Exception) {
            // JSON bozuksa aşağıdaki ham metin taraması devreye girer.
        }

        // JSON yapısı değişirse file/url/source alanlarını doğrudan tara.
        Regex(
            "[\\\"'](?:file|url|source|src|link|hls|stream|video)[\\\"']\\s*[:=]\\s*[\\\"']([^\\\"']+)[\\\"']",
            RegexOption.IGNORE_CASE
        ).findAll(body).forEach {
            addCandidate(candidates, it.groupValues[1])
        }

        // Teve2 yönlendirmesi: resmi extractor ile aynı akışı uygula.
        val teve2Id = Regex(
            "teve2\\.com\\.tr[/\\\\]+embed[/\\\\]+([^\\\"'/?]+)",
            RegexOption.IGNORE_CASE
        ).find(body)?.groupValues?.getOrNull(1)

        if (!teve2Id.isNullOrBlank()) {
            try {
                val teve2Response = app.get(
                    "https://www.teve2.com.tr/action/media/$teve2Id",
                    referer = "https://www.teve2.com.tr/embed/$teve2Id"
                )
                val teve2Json = JSONObject(teve2Response.text)
                val media = teve2Json.optJSONObject("Media")
                    ?: teve2Json.optJSONObject("media")
                val link = media?.optJSONObject("Link")
                    ?: media?.optJSONObject("link")
                if (link != null) {
                    val serviceUrl = link.optString("ServiceUrl").ifBlank { link.optString("serviceUrl") }
                    val securePath = link.optString("SecurePath").ifBlank { link.optString("securePath") }
                    if (serviceUrl.isNotBlank() && securePath.isNotBlank()) {
                        addCandidate(candidates, "$serviceUrl//$securePath")
                    }
                }
            } catch (e: Exception) {
                Log.d("CloudStream_$name", "Teve2 çözümleme hatası: ${e.message}")
            }
        }

        // Son güvenlik ağı: m3u8/mp4 adreslerini ham cevaptan çıkar.
        Regex(
            "https?://[^\\s\\\"'<>\\\\]+(?:\\.m3u8(?:\\?[^\\s\\\"'<>\\\\]*)?|\\.mp4(?:\\?[^\\s\\\"'<>\\\\]*)?)",
            RegexOption.IGNORE_CASE
        ).findAll(body).forEach {
            addCandidate(candidates, it.value)
        }

        if (candidates.isEmpty()) {
            throw ErrorLoadingException("PeaceMakerst videoSources içinden video adresi alınamadı")
        }

        for (candidate in candidates) {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = candidate,
                    type = INFER_TYPE
                ) {
                    this.referer = extRef
                    this.quality = when {
                        candidate.contains("1080", true) -> Qualities.P1080.value
                        candidate.contains("720", true) -> Qualities.P720.value
                        candidate.contains("480", true) -> Qualities.P480.value
                        else -> Qualities.Unknown.value
                    }
                }
            )
        }
    }

    private fun addCandidate(set: LinkedHashSet<String>, value: String?) {
        var result = value?.trim() ?: return
        result = result
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .trim('"', '\'', '`', '”', '“', '’', '‘', ',', ';')

        if (result.startsWith("//")) result = "https:$result"
        if (result.startsWith("http://") || result.startsWith("https://")) {
            set.add(result)
        }
    }

    private companion object {
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }
}
