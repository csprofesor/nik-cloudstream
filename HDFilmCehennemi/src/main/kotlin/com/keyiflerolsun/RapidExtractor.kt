package com.keyiflerolsun

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

open class RapidExtractor : ExtractorApi() {
    override val mainUrl = "https://rapid.filmmakinesi.to"
    override val name = "Rapid"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d(name, "getUrl çağrıldı, url: $url")

        val response = app.get(url, referer = referer ?: mainUrl)
        val rawHtml = response.text
        val cookies = response.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        Log.d(name, "Raw HTML uzunluğu: ${rawHtml.length}")

        var videoUrl: String? = null
        val unpackedJs = unpackPackerJs(rawHtml)
        if (unpackedJs != null) {
            val varPattern = Regex(
                """(?:var|let|const)\s+(\w+)\s*=\s*(\w+)\s*\(\s*\[(.*?)\]\s*\)""",
                RegexOption.DOT_MATCHES_ALL
            )
            val varMatch = varPattern.find(unpackedJs)

            if (varMatch != null) {
                val varName = varMatch.groupValues[1]
                val funcName = varMatch.groupValues[2]
                val partsStr = varMatch.groupValues[3]
                val parts = Regex(""""([^"]*)"""").findAll(partsStr).map {
                    it.groupValues[1].replace("\\/", "/").replace("\\\"", "\"")
                }.toList()

                val funcBody = extractFuncBody(unpackedJs, funcName)
                if (funcBody != null) {
                    videoUrl = parseAndExecuteJs(funcBody, parts)
                }
            }
        }
        if (videoUrl.isNullOrBlank()) {
            val jsonLdMatch = Regex(""""contentUrl"\s*:\s*"([^"]+)"""").find(rawHtml)
            videoUrl = jsonLdMatch?.groupValues?.get(1)
        }
        if (videoUrl.isNullOrBlank()) {
            val directMatch = Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)""").find(rawHtml)
            videoUrl = directMatch?.groupValues?.get(1)?.replace("\\/", "/")
        }

        if (videoUrl.isNullOrBlank()) {
            val atobMatch = Regex("""aHR0[0-9a-zA-Z+\/=]+""").find(rawHtml)
            if (atobMatch != null) {
                var atob = atobMatch.value
                val padding = atob.length % 4
                if (padding != 0) {
                    atob += "=".repeat(4 - padding)
                }
                videoUrl = String(Base64.decode(atob, Base64.DEFAULT), Charsets.ISO_8859_1)
            }
        }

        if (videoUrl.isNullOrBlank()) {
            Log.e(name, "Video URL bulunamadı!")
            return
        }

        val ajaxMatch = Regex("""url\s*:\s*["']([^"']+ah/)["'].*?data\s*:\s*\{\s*hash\s*:\s*["']([^"']+)["']""").find(unpackedJs ?: "")
        if (ajaxMatch != null) {
            val ajaxUrl = ajaxMatch.groupValues[1]
            val ajaxHash = ajaxMatch.groupValues[2]
            val fullAjaxUrl = "$mainUrl$ajaxUrl"
            try {
                app.post(
                    url = fullAjaxUrl,
                    data = mapOf("hash" to ajaxHash),
                    headers = mapOf(
                        "Referer" to url,
                        "Origin" to mainUrl,
                        "X-Requested-With" to "XMLHttpRequest",
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                        if (cookies.isNotBlank()) "Cookie" to cookies else "" to ""
                    ).filter { it.key.isNotBlank() }
                )
            } catch (e: Exception) {
                Log.w(name, "AJAX POST hatası: ${e.message}")
            }
        }

        parseSubtitles(rawHtml, subtitleCallback)

        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = videoUrl,
                type = if (videoUrl.contains(".txt") || videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = referer ?: mainUrl
                this.quality = Qualities.Unknown.value
                this.headers = mapOf(
                    "Accept" to "*/*",
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                    "Referer" to (referer ?: mainUrl),
                    "Origin" to mainUrl,
                    if (cookies.isNotBlank()) "Cookie" to cookies else "" to ""
                ).filter { it.key.isNotBlank() }
            }
        )
    }

    private fun unpackPackerJs(rawHtml: String): String? {
        return try {
            val startMarker = "eval(function(p,a,c,k,e,d){"
            val endMarker = ",0,{}))"
            val startIdx = rawHtml.indexOf(startMarker)
            if (startIdx == -1) return null
            val endIdx = rawHtml.indexOf(endMarker, startIdx + startMarker.length)
            if (endIdx == -1) return null
            val block = rawHtml.substring(startIdx, endIdx + endMarker.length)
            val packedStart = block.indexOf("}('") + 3
            val packedEnd = block.indexOf("',", packedStart)
            if (packedStart == -1 || packedEnd == -1) return null
            val packed = block.substring(packedStart, packedEnd)
            val afterPacked = block.substring(packedEnd + 2)
            val baseEnd = afterPacked.indexOf(",")
            if (baseEnd == -1) return null
            val base = afterPacked.substring(0, baseEnd).toInt()
            val afterBase = afterPacked.substring(baseEnd + 1)
            val countEnd = afterBase.indexOf(",")
            if (countEnd == -1) return null
            val count = afterBase.substring(0, countEnd).toInt()
            val dictQuoteStart = afterBase.indexOf("'") + 1
            val dictQuoteEnd = afterBase.indexOf("'.split", dictQuoteStart)
            if (dictQuoteStart == -1 || dictQuoteEnd == -1) return null
            val dictStr = afterBase.substring(dictQuoteStart, dictQuoteEnd)

            val dictionary = dictStr.split('|')
            val lookup = mutableMapOf<String, String>()
            var c = count - 1
            while (c >= 0) {
                val key = packerEncode(c, base)
                lookup[key] = if (c < dictionary.size && dictionary[c].isNotEmpty()) dictionary[c] else key
                c--
            }
            var result = packed
            val sortedKeys = lookup.keys.sortedByDescending { it.length }
            for (key in sortedKeys) {
                result = result.replace(Regex("\\b${Regex.escape(key)}\\b"), lookup[key]!!)
            }
            result
        } catch (e: Exception) {
            null
        }
    }

    private fun packerEncode(num: Int, base: Int): String {
        val digits = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        if (num == 0) return "0"
        var n = num
        val sb = StringBuilder()
        while (n > 0) {
            sb.insert(0, digits[n % base])
            n /= base
        }
        return sb.toString()
    }

    private fun parseAndExecuteJs(funcBody: String, parts: List<String>): String? {
        return try {
            val seedMatch = Regex("""var\s+(\w+)\s*=\s*"([^"]+)"\s*;\s*var\s+(\w+)\s*=\s*"([^"]+)"""").find(funcBody) ?: return null
            val seedStr = seedMatch.groupValues[2]
            val opsStr = seedMatch.groupValues[4]

            var u3e = parts.joinToString("")
            var gzx1 = 0
            var mff = 0
            for (i in seedStr.indices) {
                val ngm = seedStr[i].code
                gzx1 = (gzx1 * 31 + ngm) % 251
                mff = (mff xor (ngm + i)) and 255
            }
            val ghihx = (gzx1 + mff) % 256
            val cv1 = (gzx1 % 13) + 3
            var pzvvv = ((gzx1 * 256 + mff) % 65521) + 1

            for (i in opsStr.length - 1 downTo 0) {
                val ch = opsStr[i]
                u3e = when (ch) {
                    'b' -> atob(u3e)
                    'v' -> u3e.reversed()
                    else -> {
                        val oufo = (26 - ((ch.code - 64) % 26)) % 26
                        caesarShift(u3e, oufo)
                    }
                }
            }
            val imm = u3e.length
            if (imm > 1) {
                val irdt = IntArray(imm)
                for (sm7 in imm - 1 downTo 1) {
                    pzvvv = (pzvvv * 75 + 74) % 65537
                    irdt[sm7] = pzvvv % (sm7 + 1)
                }
                val arr = u3e.toCharArray()
                for (sm7 in 1 until imm) {
                    val j = irdt[sm7]
                    val tmp = arr[sm7]
                    arr[sm7] = arr[j]
                    arr[j] = tmp
                }
                u3e = String(arr)
            }
            val sb = StringBuilder(imm)
            var to4 = ghihx
            for (c in u3e) {
                val ngm = c.code
                to4 = (to4 + cv1) % 256
                sb.append((ngm xor to4).toChar())
                to4 = (to4 + ngm) % 256
            }
            sb.toString().trim().takeIf { it.startsWith("http") }
        } catch (e: Exception) {
            null
        }
    }

    private fun atob(s: String): String {
        var str = s.trim()
        val padding = 4 - str.length % 4
        if (padding != 4) str += "=".repeat(padding)
        return Base64.decode(str, Base64.DEFAULT).toString(Charsets.ISO_8859_1)
    }

    private fun caesarShift(text: String, shift: Int): String {
        return text.map { c ->
            when {
                c in 'A'..'Z' -> ((c.code - 'A'.code + shift) % 26 + 'A'.code).toChar()
                c in 'a'..'z' -> ((c.code - 'a'.code + shift) % 26 + 'a'.code).toChar()
                else -> c
            }
        }.joinToString("")
    }

    private fun extractFuncBody(jsCode: String, funcName: String): String? {
        val startIdx = jsCode.indexOf("function $funcName")
        if (startIdx == -1) return null
        val braceIdx = jsCode.indexOf('{', startIdx)
        if (braceIdx == -1) return null
        var braceCount = 1
        var i = braceIdx + 1
        while (braceCount > 0 && i < jsCode.length) {
            when (jsCode[i]) {
                '{' -> braceCount++
                '}' -> braceCount--
            }
            i++
        }
        return if (braceCount == 0) jsCode.substring(braceIdx + 1, i - 1) else null
    }

    private suspend fun parseSubtitles(
        rawHtml: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val tracksMatch = Regex("""tracks:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL).find(rawHtml)
        tracksMatch?.groupValues?.get(1)?.let { tracksStr ->
            val subMatches = Regex(
                """"file"\s*:\s*"([^"]+)".*?"label"\s*:\s*"([^"]+)".*?"language"\s*:\s*"([^"]+)"""",
                RegexOption.DOT_MATCHES_ALL
            ).findAll(tracksStr).toList()

            subMatches.forEach { match ->
                var subUrl = match.groupValues[1].replace("\\/", "/").replace("\\\"", "\"")
                val subLabel = match.groupValues[2]
                val langCode = match.groupValues[3]
                if (!subUrl.startsWith("http")) {
                    subUrl = mainUrl.trimEnd('/') + (if (subUrl.startsWith("/")) "" else "/") + subUrl
                }
                val lang = when {
                    langCode == "forced" || subLabel.contains("Forced", ignoreCase = true) -> "Forced"
                    langCode == "tr" || subLabel.contains("Turkish", ignoreCase = true) -> "Türkçe"
                    langCode == "en" || subLabel.contains("English", ignoreCase = true) -> "İngilizce"
                    else -> return@forEach
                }
                subtitleCallback.invoke(newSubtitleFile(lang, subUrl))
            }
        }
    }
}
