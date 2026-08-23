package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.util.Base64

open class HCCloseLoadExtractor : ExtractorApi() {
    override val name = "CloseLoad"
    override val mainUrl = "https://hdfilmcehennemi.mobi"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val pageReferer = referer ?: "https://www.hdfilmcehennemi.nl/"
        val document = app.get(url, referer = pageReferer).document
        val script = document.select("script")
            .firstOrNull { it.data().contains("eval") && it.data().contains("PlayerInit") }
            ?.data()
            ?: document.select("script").firstOrNull { it.data().contains("eval") }?.data()
            ?: return

        document.select("track[src]").forEach {
            subtitleCallback.invoke(
                SubtitleFile(
                    it.attr("label").ifBlank { it.attr("srclang") },
                    it.absUrl("src")
                )
            )
        }

        val unpacked = try { getAndUnpack(script) } catch (_: Exception) { script }
        val partsMatch = Regex("""[\\w_]+\\s*=\\s*[\\w_]+\\(\\[(.*?)]\\)""").find(unpacked)
            ?: return
        val parts = partsMatch.groupValues[1].split(",")
        val playlistUrl = decodeCurrent(parts) ?: return

        val hostUrl = "https://" + java.net.URI(url).host
        val videoHeaders = mapOf(
            "Referer" to url,
            "Origin" to hostUrl
        )

        // The current HDFilmCehennemi player expects this hash request before
        // the returned HLS playlist is accepted by the video server.
        runCatching {
            val hash = Regex("""hash:\s*\"([^\"]+)\"""").find(unpacked)?.groupValues?.get(1)
            val ajaxPath = Regex("""url:\s*\"([^\"]+)\"""").find(unpacked)?.groupValues?.get(1)
            if (!hash.isNullOrBlank() && !ajaxPath.isNullOrBlank()) {
                val ajaxUrl = if (ajaxPath.startsWith("http")) ajaxPath
                else hostUrl + "/" + ajaxPath.trimStart('/')
                app.post(
                    ajaxUrl,
                    data = mapOf("hash" to hash),
                    referer = url,
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                )
            }
        }

        Log.d("Kekik_${this.name}", "playlist -> $playlistUrl")
        callback.invoke(
            newExtractorLink(name, name, playlistUrl, ExtractorLinkType.M3U8) {
                this.referer = url
                this.headers = videoHeaders
                this.quality = Qualities.Unknown.value
            }
        )
    }

    private fun decodeCurrent(parts: List<String>): String? {
        return try {
            val value = parts.joinToString("") { it.trim().trim('"') }
            var decoded = Base64.getDecoder().decode(value)

            // Current HDFilmCehennemi order:
            // Base64 -> ROT13 on decoded bytes -> reverse -> unmix.
            decoded = decoded.map { byte ->
                val c = (byte.toInt() and 0xFF).toChar()
                when {
                    c in 'a'..'z' -> (('a'.code + (c.code - 'a'.code + 13) % 26)).toByte()
                    c in 'A'..'Z' -> (('A'.code + (c.code - 'A'.code + 13) % 26)).toByte()
                    else -> byte
                }
            }.reversed().toByteArray()

            buildString {
                decoded.forEachIndexed { index, byte ->
                    val mixed = ((byte.toInt() and 0xFF) - (399756995L % (index + 5)) + 256) % 256
                    append(mixed.toInt().toChar())
                }
            }.takeIf { it.startsWith("https://") && (it.contains(".m3u8") || it.contains("/hls/") || it.contains(".mp4")) }
        } catch (_: Exception) {
            null
        }
    }
}
