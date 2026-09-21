package com.keyiflerolsun

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

open class CloseLoadExtractor : ExtractorApi() {
    override val mainUrl = "https://closeload.filmmakinesi.to"
    override val name = "CloseLoad"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d(name, "getUrl çağrıldı, url: $url")

        val response = app.get(url, referer = referer ?: "")
        val rawHtml = response.text
        val cookies = response.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        Log.d(name, "Raw HTML uzunluğu: ${rawHtml.length}")

        var videoUrl: String? = null
        val hasPacker = rawHtml.contains("eval(function(p,a,c,k,e,d){")
        Log.d(name, "Packed JS marker var mı: $hasPacker")
        val dcMatches = Regex("""(dc_\w+)""").findAll(rawHtml).map { it.value }.distinct().toList()
        Log.d(name, "dc_ fonksiyon adları: $dcMatches")
        val sMatches = Regex("""var\s+(s_\w+)""").findAll(rawHtml).map { it.groupValues[1] }.distinct().toList()
        Log.d(name, "s_ değişkenleri: $sMatches")
        Regex("""\[\s*"[^"]*"\s*,\s*"[^"]*"""").findAll(rawHtml).take(5).forEachIndexed { i, m ->
            Log.d(name, "Parts benzeri dizi #$i @${m.range.first}: ${m.value.take(80)}")
        }
        val evalIdx = rawHtml.indexOf("eval(")
        if (evalIdx != -1) {
            Log.d(name, "eval( konumu: $evalIdx, çevresi: ${rawHtml.substring(evalIdx, (evalIdx + 200).coerceAtMost(rawHtml.length))}")
        }

        val varPattern = Regex("""var\s+(\w+)\s*=\s*(\w+)\s*\(\s*\[(.*?)\]\s*\)""", RegexOption.DOT_MATCHES_ALL)
        val varMatch = varPattern.find(rawHtml)

        if (varMatch != null) {
            val varName = varMatch.groupValues[1]
            val funcName = varMatch.groupValues[2]
            val partsStr = varMatch.groupValues[3]
            val parts = Regex(""""([^"]*)"""").findAll(partsStr).map {
                it.groupValues[1].replace("\\/", "/").replace("\\\"", "\"")
            }.toList()

            Log.d(name, "Dinamik bulundu: var=$varName, func=$funcName, parts=${parts.size}")
            val funcBody = extractFuncBody(rawHtml, funcName)
            if (funcBody != null) {
                Log.d(name, "Fonksiyon body bulundu, uzunluk: ${funcBody.length}")
                Log.d(name, "FUNC_BODY: $funcBody")
                videoUrl = parseAndExecuteJs(funcBody, parts)
                Log.d(name, "Dinamik çözülen URL: $videoUrl")
            } else {
                Log.w(name, "Fonksiyon body bulunamadı, bilinen decryptor'ları deniyorum")
                videoUrl = tryAllDecryptors(parts)
            }
        } else {
            Log.w(name, "var s_XXX = dc_YYY([...]) pattern bulunamadı")
        }

        if (videoUrl.isNullOrBlank()) {
            val jsonLdMatch = Regex(""""contentUrl"\s*:\s*"([^"]+)"""").find(rawHtml)
            videoUrl = jsonLdMatch?.groupValues?.get(1)
            Log.d(name, "Fallback JSON-LD contentUrl: $videoUrl")
        }

        if (videoUrl.isNullOrBlank()) {
            val atobMatch = Regex("""aHR0[0-9a-zA-Z+\/=]+""").find(rawHtml)
            if (atobMatch != null) {
                var atob = atobMatch.value
                val padding = atob.length % 4
                if (padding != 0) {
                    atob += "=".repeat(4 - padding)
                }
                videoUrl = String(Base64.decode(atob, Base64.DEFAULT), Charsets.UTF_8)
                Log.d(name, "Fallback atob m3u8: $videoUrl")
            }
        }

        if (videoUrl.isNullOrBlank()) {
            Log.e(name, "Video URL bulunamadı!")
            return
        }

        
        val unpackedJs = unpackPackerJs(rawHtml)
        val ajaxMatch = Regex("""url\s*:\s*["']([^"']+ah/)["'].*?data\s*:\s*\{\s*hash\s*:\s*["']([^"']+)["']""").find(unpackedJs ?: "")
        if (ajaxMatch != null) {
            val ajaxUrl = ajaxMatch.groupValues[1]
            val ajaxHash = ajaxMatch.groupValues[2]
            val fullAjaxUrl = "$mainUrl$ajaxUrl"
            Log.d(name, "AJAX POST yapılıyor: $fullAjaxUrl hash=$ajaxHash")
            try {
                app.post(
                    url = fullAjaxUrl,
                    data = mapOf("hash" to ajaxHash),
                    headers = mapOf(
                        "Referer" to url,
                        "Origin" to mainUrl,
                        "X-Requested-With" to "XMLHttpRequest",
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36 Norton/124.0.0.0",
                        if (cookies.isNotBlank()) "Cookie" to cookies else "" to ""
                    ).filter { it.key.isNotBlank() }
                )
            } catch (e: Exception) {
                Log.w(name, "AJAX POST hatası: ${e.message}")
            }
        } else {
            Log.w(name, "AJAX hash bulunamadı!")
        }

        val tracksMatch = Regex("""tracks:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL).find(rawHtml)
        val tracksStr = tracksMatch?.groupValues?.get(1)

        tracksStr?.let { str ->
            val matches = Regex(""""file"\s*:\s*"([^"]+)".*?"label"\s*:\s*"([^"]+)"""", RegexOption.DOT_MATCHES_ALL)
                .findAll(str).toList()
            Log.d(name, "Bulunan altyazı sayısı: ${matches.size}")

            matches.forEachIndexed { index, match ->
                val subUrl = match.groupValues[1].replace("\\/", "/")
                val subLabel = match.groupValues[2]
                val lang = when {
                    subLabel.contains("Turkish", ignoreCase = true) -> "Türkçe"
                    subLabel.contains("Forced", ignoreCase = true) -> "Forced"
                    subLabel.contains("English", ignoreCase = true) -> "İngilizce"
                    else -> return@forEachIndexed
                }
                Log.d(name, "Altyazı #$index - lang: '$lang', label: '$subLabel'")
                subtitleCallback.invoke(SubtitleFile(lang, subUrl))
            }
        }

        

        
        
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
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36 Norton/124.0.0.0",
                    "Referer" to (referer ?: mainUrl),
                    "Origin" to mainUrl,
                    if (cookies.isNotBlank()) "Cookie" to cookies else "" to ""
                ).filter { it.key.isNotBlank() }
            }
        )
        Log.d(name, "ExtractorLink eklendi: $videoUrl")


        Log.d(name, "ExtractorLink eklendi: $videoUrl")
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
                lookup[key] = if (c < dictionary.size && dictionary[c].isNotEmpty()) {
                    dictionary[c]
                } else {
                    key
                }
                c--
            }

            var result = packed
            val sortedKeys = lookup.keys.sortedByDescending { it.length }
            for (key in sortedKeys) {
                val value = lookup[key]!!
                result = result.replace(Regex("\\b${Regex.escape(key)}\\b"), value)
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

    private fun extractFuncBody(rawHtml: String, funcName: String): String? {
        val startIdx = rawHtml.indexOf("function $funcName")
        if (startIdx == -1) return null

        val braceIdx = rawHtml.indexOf('{', startIdx)
        if (braceIdx == -1) return null

        var braceCount = 1
        var i = braceIdx + 1
        while (braceCount > 0 && i < rawHtml.length) {
            when (rawHtml[i]) {
                '{' -> braceCount++
                '}' -> braceCount--
            }
            i++
        }
        return if (braceCount == 0) rawHtml.substring(braceIdx + 1, i - 1) else null
    }
    private fun parseAndExecuteJs(funcBody: String, parts: List<String>): String? {
        return try {
            val seedMatch = Regex(
                """var\s+(\w+)\s*=\s*"([^"]+)"\s*;\s*var\s+(\w+)\s*=\s*"([^"]+)""""
            ).find(funcBody) ?: run {
                Log.w(name, "Seed/ops string'leri bulunamadı")
                return null
            }
            val seedStr = seedMatch.groupValues[2]
            val opsStr = seedMatch.groupValues[4]
            Log.d(name, "Seed: '$seedStr', Ops: '$opsStr'")

            var la8q = parts.joinToString("")
            var m1l = 0
            var rfdgf = 0
            for (i in seedStr.indices) {
                val ioz = seedStr[i].code
                m1l = (m1l * 31 + ioz) % 251
                rfdgf = (rfdgf xor (ioz + i)) and 255
            }
            val ucv = (m1l + rfdgf) % 256
            val h52gx = (m1l % 13) + 3
            var ws7g = ((m1l * 256 + rfdgf) % 65521) + 1
            Log.d(name, "ucv=$ucv, h52gx=$h52gx, ws7g=$ws7g")
            for (i in opsStr.length - 1 downTo 0) {
                val ch = opsStr[i]
                la8q = when (ch) {
                    'b' -> atob(la8q)
                    'v' -> la8q.reversed()
                    else -> {
                        val tcxa = (26 - ((ch.code - 64) % 26)) % 26
                        caesarShift(la8q, tcxa)
                    }
                }
            }
            if (opsStr.length > 4096) la8q = la8q.reversed()

            Log.d(name, "Operasyonlar sonrası uzunluk: ${la8q.length}")
            val tmzq = la8q.length
            if (tmzq > 1) {
                val gtwld = IntArray(tmzq)
                for (xfm8 in tmzq - 1 downTo 1) {
                    ws7g = (ws7g * 75 + 74) % 65537
                    gtwld[xfm8] = ws7g % (xfm8 + 1)
                }
                val onw = la8q.toCharArray()
                for (xfm8 in 1 until tmzq) {
                    val j = gtwld[xfm8]
                    val tmp = onw[xfm8]
                    onw[xfm8] = onw[j]
                    onw[j] = tmp
                }
                la8q = String(onw)
            }
            val sb = StringBuilder(tmzq)
            var ew0 = ucv
            for (c in la8q) {
                val ioz = c.code
                ew0 = (ew0 + h52gx) % 256
                sb.append((ioz xor ew0).toChar())
                ew0 = (ew0 + ioz) % 256
            }

            val result = sb.toString()
            Log.d(name, "Çözülen değer: ${result.take(200)}")
            result.trim().takeIf { it.startsWith("http") }
        } catch (e: Exception) {
            Log.e(name, "JS Parser hatası: ${e.message}")
            null
        }
    }

    private fun atob(s: String): String {
        var str = s.trim()
        val padding = 4 - str.length % 4
        if (padding != 4) str += "=".repeat(padding)
        return Base64.decode(str, Base64.DEFAULT).toString(Charsets.ISO_8859_1)
    }

    private fun btoa(s: String): String {
        return Base64.encodeToString(s.toByteArray(Charsets.ISO_8859_1), Base64.DEFAULT).trim()
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

    private fun xorUnmix(text: String, accStart: Int, increment: Int): String {
        var acc = accStart
        val unmix = StringBuilder()
        for (i in text.indices) {
            val b = text[i].code
            acc = (acc + increment) % 256
            val plain = b xor acc
            acc = (acc + b) % 256
            unmix.append(plain.toChar())
        }
        return unmix.toString()
    }
    private fun tryAllDecryptors(parts: List<String>): String? {
        val decryptors = listOf(::decryptV1, ::decryptV2, ::decryptV3, ::decryptV4)
        for ((index, decryptor) in decryptors.withIndex()) {
            try {
                val result = decryptor(parts)
                if (!result.isNullOrBlank() && result.contains("http")) {
                    Log.d(name, "Fallback decryptor v${index + 1} başarılı!")
                    return result
                }
            } catch (e: Exception) {
                Log.d(name, "Fallback decryptor v${index + 1} başarısız: ${e.message}")
            }
        }
        return null
    }

    private fun decryptV1(valueParts: List<String>): String? {
        var value = valueParts.joinToString("")
        value = caesarShift(value, 9); value = caesarShift(value, 16)
        value = value.reversed()
        var decoded = atob(value); decoded = atob(decoded)
        return xorUnmix(decoded, 241, 11)
    }

    private fun decryptV2(valueParts: List<String>): String? {
        var value = valueParts.joinToString("")
        value = value.reversed(); value = caesarShift(value, 15)
        var decoded = atob(value); decoded = decoded.reversed(); decoded = atob(decoded)
        return xorUnmix(decoded, 185, 12)
    }

    private fun decryptV3(valueParts: List<String>): String? {
        var value = valueParts.joinToString("")
        var decoded = atob(value); decoded = atob(decoded)
        decoded = decoded.reversed(); decoded = caesarShift(decoded, 25); decoded = atob(decoded)
        return xorUnmix(decoded, 77, 9)
    }

    private fun decryptV4(valueParts: List<String>): String? {
        var value = valueParts.joinToString("")
        var decoded = atob(value); decoded = decoded.reversed(); decoded = atob(decoded)
        return xorUnmix(decoded, 130, 10)
    }
}

class CloseLoadTo : CloseLoadExtractor() {
    override val mainUrl = "https://closeload.filmmakinesi.to"
}

class CloseLoadFilm : CloseLoadExtractor() {
    override val mainUrl = "https://closeload.filmmakinesi.film"
}

class CloseLoadDe : CloseLoadExtractor() {
    override val mainUrl = "https://closeload.filmmakinesi.de"
}

class CloseLoadTv : CloseLoadExtractor() {
    override val mainUrl = "https://closeload.filmmakinesi.tv"
}

class CloseLoadSh : CloseLoadExtractor() {
    override val mainUrl = "https://closeload.filmmakinesi.sh"
}
