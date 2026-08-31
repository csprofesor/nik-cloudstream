package com.nikyokki.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Bundled bridge for FilmKovası's external player buttons.
 * CloudStream's built-in loadExtractor() remains the first-class fallback;
 * this router supplies the providers that are not in the current core registry.
 */
object FilmKovasiBundledExtractors {
    private val hosts = setOf(
        "vidsrc.me","vidsrc.xyz","vidsrc.to","vidsrc.pro","vidsrc.icu","vidsrc.cc",
        "2embed.cc","2embed.org","autoembed.cc","player.autoembed.cc","multiembed.mov",
        "smashystream.com","smashy.stream","player.smashy.stream","embed.smashystream.com",
        "moviesapi.club"
    )

    suspend fun tryExtract(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        visited: MutableSet<String> = mutableSetOf(),
        depth: Int = 0
    ): Boolean {
        if (depth > 4) return false
        val u = url.trim().trim('"','\'','(',')',';',',')
        if (!u.startsWith("http://", true) && !u.startsWith("https://", true)) return false
        if (!visited.add(u.substringBefore("#"))) return false
        val host = u.substringAfter("://").substringBefore('/').substringBefore(':').lowercase()
        if (hosts.none { host == it || host.endsWith(".$it") }) return false

        return when {
            host.contains("smashystream") || host.contains("smashy.stream") ->
                smashy(u, referer, subtitleCallback, callback, visited, depth)
            host.contains("moviesapi") ->
                iframeBridge(u, referer, subtitleCallback, callback, visited, depth, "MoviesAPI")
            else ->
                iframeBridge(u, referer, subtitleCallback, callback, visited, depth,
                    if (host.contains("vidsrc")) "VidSrc" else "Embed")
        }
    }

    private suspend fun iframeBridge(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        visited: MutableSet<String>,
        depth: Int,
        label: String
    ): Boolean {
        val response = runCatching {
            app.get(url, headers(referer))
        }.getOrNull() ?: return false

        var found = false
        val candidates = mutableListOf<String>()

        response.document.select(
            "iframe[src],iframe[data-src],embed[src],object[data]," +
            "video[src],video source[src],[data-url],[data-src],[data-video],[data-embed]"
        ).forEach { e ->
            val v = when (e.tagName()) {
                "object" -> e.attr("data")
                "iframe","embed" -> e.attr("src").ifBlank { e.attr("data-src") }
                else -> e.attr("src").ifBlank {
                    listOf(e.attr("data-url"),e.attr("data-video"),e.attr("data-embed"),e.attr("data-src"))
                        .firstOrNull { it.isNotBlank() } ?: ""
                }
            }
            if (v.isNotBlank() && !v.equals("about:blank", true)) candidates += v
        }

        val abs = Regex("""https?://[^"'\s<>]+""")
        response.document.select("script").forEach { s ->
            abs.findAll(s.data().ifBlank { s.html() }).forEach { candidates += it.value }
        }

        val unpacked = runCatching {
            getAndUnpack(response.document.select("script").joinToString("\n") { script -> script.data().ifBlank { script.html() } })
        }.getOrDefault(response.text)

        Regex("""(?:file|src|source)\s*:\s*["']([^"']+\.(?:m3u8|mp4)(?:\?[^"']*)?)["']""")
            .findAll(unpacked).forEach {
                val media = resolve(it.groupValues[1], url) ?: return@forEach
                callback(link(label, media, url))
                found = true
            }

        for (raw in candidates.distinct()) {
            val next = resolve(raw, url) ?: continue
            if (next == url) continue
            if (tryExtract(next, url, subtitleCallback, callback, visited, depth + 1)) {
                found = true
            } else {
                runCatching {
                    loadExtractor(next, url, subtitleCallback) { link ->
                        found = true
                        callback(link)
                    }
                }
            }
        }

        Regex("""https?://[^"'\s<>]+\.(?:m3u8|mp4)(?:\?[^"'\s<>]*)?""")
            .findAll(response.text).forEach {
                callback(link(label, it.value.replace("&amp;","&"), url))
                found = true
            }

        return found
    }

    private suspend fun smashy(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        visited: MutableSet<String>,
        depth: Int
    ): Boolean {
        val response = runCatching { app.get(url, headers(referer)) }.getOrNull()
            ?: return false
        val master = Regex("""MasterJS\s*=\s*'([^']*)'""").find(response.text)?.groupValues?.get(1)
            ?: return iframeBridge(url, referer, subtitleCallback, callback, visited, depth, "SmashyStream")

        return runCatching {
            val blob = String(Base64.getDecoder().decode(master))
            val salt = Regex(""""salt"\s*:\s*"([^"]+)"""").find(blob)?.groupValues?.get(1) ?: return@runCatching false
            val iv = Regex(""""iv"\s*:\s*"([^"]+)"""").find(blob)?.groupValues?.get(1) ?: return@runCatching false
            val iterations = Regex(""""iterations"\s*:\s*(\d+)""").find(blob)?.groupValues?.get(1)?.toIntOrNull()
                ?: return@runCatching false
            val ciphertext = Regex(""""ciphertext"\s*:\s*"([^"]+)"""").find(blob)?.groupValues?.get(1)
                ?: return@runCatching false

            val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512").generateSecret(
                PBEKeySpec("4VqE3#N7zt&HEP^a".toCharArray(), salt.hex(), iterations, 256)
            ).encoded
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv.hex()))
            val decrypted = String(cipher.doFinal(Base64.getDecoder().decode(ciphertext)))
            val sources = Regex("""sources\s*:\s*(\[[^\]]*])""").find(decrypted)?.groupValues?.get(1)
                ?: return@runCatching false
            val file = Regex(""""file"\s*:\s*"([^"]+)"""").find(sources)?.groupValues?.get(1)
                ?: return@runCatching false
            val label = Regex(""""label"\s*:\s*"([^"]+)"""").find(sources)?.groupValues?.get(1) ?: ""
            callback(link("SmashyStream", file, url, getQualityFromName(label)))
            true
        }.getOrDefault(false)
    }

    private suspend fun link(source: String, url: String, referer: String, quality: Int = 0): ExtractorLink =
        newExtractorLink(source, source, url, type = if (url.contains(".m3u8")) ExtractorLinkType.M3U8 else INFER_TYPE) {
            this.referer = referer
            this.headers = headers(referer, false)
            if (quality > 0) this.quality = quality
        }

    private fun headers(referer: String?, browser: Boolean = true): Map<String,String> = mapOf(
        "Accept" to "*/*",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "User-Agent" to USER_AGENT,
        "Referer" to (referer ?: "https://filmkovasi.co/")
    )

    private fun resolve(raw: String, base: String): String? {
        val v = raw.trim().replace("\\/","/").replace("&amp;","&")
        if (v.isBlank() || v == "about:blank") return null
        if (v.startsWith("http://",true) || v.startsWith("https://",true)) return v
        if (v.startsWith("//")) return "https:$v"
        return runCatching { java.net.URI(base).resolve(v).toString() }.getOrNull()
    }

    private fun String.hex(): ByteArray {
        val s = replace(Regex("[^0-9A-Fa-f]"),"")
        return ByteArray(s.length / 2) { i -> s.substring(i*2,i*2+2).toInt(16).toByte() }
    }
}
