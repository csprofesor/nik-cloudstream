package com.nikyokki

import android.util.Base64
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document

open class VidRameExtractor : ExtractorApi() {
    override val name = "VidRame"
    override val mainUrl = "https://vidrame.pro"
    override val requiresReferer = true

    private fun decodeXor(data: List<Int>, key: String): String {
        return buildString(data.size) {
            data.forEachIndexed { index, value ->
                val decoded = value xor key[index % key.length].code xor ((index * 17 + 13) and 255)
                append(decoded.toChar())
            }
        }
    }

    private fun parseXorSource(script: String): String? {
        val match = Regex(
            """file:\s*\(function\(d,k\).*?\}\)\(\s*\[([0-9,\s]+)\]\s*,\s*\"([^\"]+)\"\s*\)""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        ).find(script) ?: return null

        val data = match.groupValues[1]
            .split(',')
            .mapNotNull { it.trim().toIntOrNull() }
        val key = match.groupValues[2]
        if (data.isEmpty() || key.isEmpty()) return null

        return decodeXor(data, key)
    }

    private fun decodeOldSource(encoded: String): String? {
        return try {
            var value = encoded.replace("-", "+").replace("_", "/")
            while (value.length % 4 != 0) value += "="
            val decoded = String(Base64.decode(value, Base64.DEFAULT))
            decoded.map { c ->
                when (c) {
                    in 'a'..'z' -> ((c - 'a' + 13) % 26 + 'a'.code).toChar()
                    in 'A'..'Z' -> ((c - 'A' + 13) % 26 + 'A'.code).toChar()
                    else -> c
                }
            }.joinToString("").reversed()
        } catch (_: Exception) {
            null
        }
    }

    private fun extractSubtitles(script: String, subtitleCallback: (SubtitleFile) -> Unit) {
        val tracksMatch = Regex(
            """configs\.tracks\s*=\s*(\[[\s\S]*?]);"""
        ).find(script) ?: return

        val tracks = tracksMatch.groupValues[1]
        val regex = Regex(
            """\"kind\"\s*:\s*\"captions\"\s*,\s*\"label\"\s*:\s*\"([^\"]*)\"\s*,\s*\"language\"\s*:\s*\"([^\"]*)\"\s*,\s*\"fx\"\s*:\s*\{\s*\"d\"\s*:\s*\[([^]]*)\]\s*,\s*\"k\"\s*:\s*\"([^\"]+)\""""
        )

        regex.findAll(tracks).forEach { match ->
            try {
                val label = match.groupValues[1]
                val language = match.groupValues[2]
                val data = match.groupValues[3]
                    .split(',')
                    .mapNotNull { it.trim().toIntOrNull() }
                val key = match.groupValues[4]
                val subtitleUrl = decodeXor(data, key)

                if (subtitleUrl.startsWith("http")) {
                    subtitleCallback(SubtitleFile(label.ifBlank { language }, subtitleUrl))
                }
            } catch (e: Exception) {
                Log.d("VidEx", "Subtitle decode error: ${e.message}")
            }
        }
    }

    private fun extractFromScript(script: String, subtitleCallback: (SubtitleFile) -> Unit): String? {
        extractSubtitles(script, subtitleCallback)

        parseXorSource(script)?.let { url ->
            if (url.startsWith("http")) return url
        }

        // Eski VidRame kodlaması için geriye dönük uyumluluk.
        val oldEncoded = Regex("""file:\s*EE\.dd\(\"([^\"]+)\"\)""").find(script)?.groupValues?.get(1)
        return oldEncoded?.let { decodeOldSource(it) }
    }

    private fun findPlayerScript(document: Document): String? {
        return document.select("script")
            .asSequence()
            .map { it.data() }
            .firstOrNull { it.contains("sources:") && it.contains("file:") }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d("VidEx", "Player URL: $url")

        val playerReferer = url.substringBefore("/vr/").ifBlank { "$mainUrl/" } + "/"
        val document = app.get(
            url,
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/139.0 Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "en-US,en;q=0.8"
            ),
            referer = referer ?: playerReferer
        ).document

        val script = findPlayerScript(document)
            ?: throw Error("VidRame source script bulunamadı")

        val videoUrl = extractFromScript(script, subtitleCallback)
            ?: throw Error("VidRame HLS kaynağı bulunamadı")

        Log.d("VidEx", "M3U8: $videoUrl")

        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = videoUrl,
                type = ExtractorLinkType.M3U8
            ) {
                quality = Qualities.Unknown.value
                this.referer = playerReferer
            }
        )
    }
}
