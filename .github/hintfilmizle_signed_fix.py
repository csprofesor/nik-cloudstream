from pathlib import Path
path = Path("HintFilmIzle/src/main/kotlin/com/nikyokki/HintFilmIzlePlugin.kt")
s = path.read_text(encoding="utf-8-sig")
s = s.replace('''            response = runCatching { app.get(fallbackUrl, referer = "$mainUrl/", headers = headers()) }.getOrNull()
            if (response != null) { doc = response.document; r = results(doc, slug) }
''','''            val fallbackResponse = runCatching { app.get(fallbackUrl, referer = "$mainUrl/", headers = headers()) }.getOrNull()
            if (fallbackResponse != null) { doc = fallbackResponse.document; r = results(doc, slug) }
''',1)
if 'import java.security.SecureRandom' not in s:
    s=s.replace('import java.net.URLEncoder\n','import java.net.URLEncoder\nimport java.security.SecureRandom\n',1)
start=s.index('    private suspend fun kinescope(')
end=s.index('\n    override suspend fun loadLinks',start)
new_kinescope="""    private fun kinescopeHash(value: String): String {
        var hash = 0
        for (ch in value) hash = ((hash shl 5) - hash) + ch.code
        val out = StringBuilder(hash.toLong().let { kotlin.math.abs(it) }.toString(16).padStart(8, '0'))
        var i = 0
        while (i < value.length) {
            val end = minOf(i + 3, value.length)
            var sum = 0
            for (j in i until end) sum += value[j].code
            out.append(sum.toString(16))
            i += 3
        }
        return out.toString()
    }

    private fun kinescopeSignature(material: String): String {
        val secret = "RTAJTmjFegZfxynQ95EwdoqYrQ2T5ZJE"
        val padded = secret.padEnd(64, Char(0)).take(64)
        val first = buildString(padded.length) { padded.forEach { append((it.code xor 54).toChar()) } }
        val second = buildString(padded.length) { padded.forEach { append((it.code xor 92).toChar()) } }
        return kinescopeHash(second + kinescopeHash(first + material))
    }

    private fun kinescopeNonce(): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        val random = SecureRandom()
        return buildString(11) { repeat(11) { append(chars[random.nextInt(chars.length)]) } }
    }

    private fun findKinescopeUrl(value: Any?, base: String): String? {
        when (value) {
            is JSONObject -> {
                val preferred = listOf("playlist", "m3u8", "url", "file", "src")
                for (key in preferred) {
                    if (!value.has(key) || value.isNull(key)) continue
                    findKinescopeUrl(value.get(key), base)?.let { return it }
                }
                val keys = value.keys()
                while (keys.hasNext()) findKinescopeUrl(value.get(keys.next()), base)?.let { return it }
            }
            is JSONArray -> for (i in 0 until value.length()) findKinescopeUrl(value.get(i), base)?.let { return it }
            is String -> if (value.trim().contains(".m3u8", true)) return fix(value.trim(), base)
        }
        return null
    }

    private suspend fun kinescope(kine: String, parent: String, callback: (ExtractorLink) -> Unit): Boolean = runCatching {
        val id = Regex("/embed/([A-Za-z0-9_-]+)", RegexOption.IGNORE_CASE).find(kine)?.groupValues?.getOrNull(1)
            ?: return@runCatching false

        val target = "https://river-3-329.kinescopecdn.net/677113747/embed/$id?design=3&lang=tr&autoplay=1&muted=1&preload=1&playsinline=1&background=1&enableIframeApi=1&nc=${System.currentTimeMillis() / 1000L}"
        val host = "river-3-329.kinescopecdn.net"
        val parentDomain = runCatching { URI(parent).host?.removePrefix("www.") }.getOrNull() ?: "hintfilmizle.com"
        val timestamp = System.currentTimeMillis() / 1000L
        val nonce = kinescopeNonce()
        val material = listOf(parentDomain, target, timestamp.toString(), nonce, "").joinToString("|")
        val signature = kinescopeSignature(material)

        val commonQuery = buildString {
            append("?domain=").append(URLEncoder.encode(parentDomain, "UTF-8"))
            append("&iframe_url=").append(URLEncoder.encode(target, "UTF-8"))
            append("&sig=").append(URLEncoder.encode(signature, "UTF-8"))
            append("&ts=").append(timestamp)
            append("&nonce=").append(URLEncoder.encode(nonce, "UTF-8"))
            append("&meta=")
            append("&a=0")
        }

        // fetchPlaylist() in the supplied Kinescope embed.js identifies these exact API resource names:
        // /api/v1/embed-kp/{id} for KP/IMDB and /api/v1/embed-serials/{id} for serials.
        val endpointPaths = listOf("/api/v1/embed-kp/$id", "/api/v1/embed-serials/$id")
        for (endpointPath in endpointPaths) {
            val signedUrl = "https://$host$endpointPath$commonQuery"
            Log.d("HintFilmIzle", "KINESCOPE_SIGNED_TARGET=$target")
            Log.d("HintFilmIzle", "KINESCOPE_ENDPOINT=$endpointPath")
            Log.d("HintFilmIzle", "KINESCOPE_SIGNED_URL=${signedUrl.substringBefore("&sig=")}...sig=<redacted>")

            val response = runCatching {
                app.get(
                    signedUrl,
                    referer = target,
                    headers = headers() + mapOf(
                        "Referer" to target,
                        "Origin" to "https://$parentDomain"
                    )
                )
            }.getOrNull() ?: continue

            Log.d("HintFilmIzle", "KINESCOPE_SIGNED_CODE=${response.code}")
            Log.d("HintFilmIzle", "KINESCOPE_SIGNED_LEN=${response.text.length}")
            val encoded = runCatching { JSONObject(response.text).optString("p", "") }.getOrNull().orEmpty()
            if (encoded.isBlank()) continue
            val decoded = runCatching {
                val raw = Base64.decode(encoded.reversed(), Base64.DEFAULT)
                val key = "RySdvcyu5iTUxn97vn4HwoniwgxaCynA".toByteArray(Charsets.UTF_8)
                val out = ByteArray(raw.size)
                for (i in raw.indices) out[i] = (raw[i].toInt() xor key[i % key.size].toInt()).toByte()
                String(out, Charsets.UTF_8)
            }.getOrNull() ?: continue
            Log.d("HintFilmIzle", "KINESCOPE_SIGNED_DECODED=$decoded")
            val json = runCatching { JSONTokener(decoded).nextValue() }.getOrNull() ?: continue
            val manifest = findKinescopeUrl(json, "https://$host/") ?: continue
            Log.d("HintFilmIzle", "KINESCOPE_MANIFEST=$manifest")
            callback(newExtractorLink(source = name, name = "HintFilmİzle Kinescope", url = manifest, type = ExtractorLinkType.M3U8) {
                referer = target
                this.headers = mapOf("Referer" to target, "Origin" to "https://$parentDomain", "User-Agent" to ua)
                quality = getQualityFromName(manifest)
            })
            return@runCatching true
        }
        false
    }.getOrElse {
        Log.e("HintFilmIzle", "KINESCOPE_SIGNED_FAILED", it)
        false
    }
"""
s=s[:start]+new_kinescope+s[end:]
path.write_text(s,encoding="utf-8")
