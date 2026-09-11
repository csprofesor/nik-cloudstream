from pathlib import Path

path = Path("HintFilmIzle/src/main/kotlin/com/nikyokki/HintFilmIzlePlugin.kt")
s = path.read_text(encoding="utf-8-sig")

decoder_start = s.find("    private fun decodeKinescopeApi(body: String): String?")
decoder_end = s.find("    private suspend fun kinescope(", decoder_start)
if decoder_start < 0 or decoder_end < 0:
    raise SystemExit("Kinescope decoder function not found")

decoder = '''    private fun decodeKinescopeApi(body: String): String? {
        val encoded = Regex("\\\"p\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
            .find(body)?.groupValues?.getOrNull(1) ?: return null
        val encrypted = runCatching {
            android.util.Base64.decode(encoded.reversed(), android.util.Base64.DEFAULT)
        }.getOrNull() ?: return null
        val key = "RySdvcyu5iTUxn97vn4HwoniwgxaCynA".toByteArray(Charsets.UTF_8)
        val plain = ByteArray(encrypted.size) { i ->
            (encrypted[i].toInt() xor key[i % key.size].toInt()).toByte()
        }
        val decoded = runCatching { String(plain, Charsets.UTF_8) }.getOrNull() ?: return null

        val labelled = Regex(
            "\\\\[(\\\\d{3,4})p\\\\]\\\\{[^}]*\\\\}(https?://[^\\\"'\\\\s<>]+\\\\.kinescopecdn\\\\.net/hls/[^\\\"',\\\\s<>]+/index\\\\.m3u8(?:\\\\?[^\\\"',\\\\s<>]*)?)",
            RegexOption.IGNORE_CASE
        ).findAll(decoded)
            .mapNotNull { m -> m.groupValues.getOrNull(1)?.toIntOrNull()?.let { it to m.groupValues[2] } }
            .maxByOrNull { it.first }?.second

        val fallback = Regex(
            "https?://[^\\\"'\\\\s<>]+\\\\.kinescopecdn\\\\.net/hls/[^\\\"',\\\\s<>]+/index\\\\.m3u8(?:\\\\?[^\\\"',\\\\s<>]*)?",
            RegexOption.IGNORE_CASE
        ).find(decoded)?.value

        return (labelled ?: fallback)
            ?.replace("\\\\/", "/")
            ?.replace("\\\\u0026", "&")
    }

'''
s = s[:decoder_start] + decoder + s[decoder_end:]

kine_start = s.find("    private suspend fun kinescope(")
resolver_start = s.find("        val intercept = Regex(", kine_start)
end_marker = "        val final = stream ?: return false"
resolver_end = s.find(end_marker, resolver_start)
if kine_start < 0 or resolver_start < 0 or resolver_end < 0:
    raise SystemExit("Kinescope WebView resolver block not found")

replacement = '''        val apiIntercept = Regex(
            "\\\\.kinescopecdn\\\\.net/api/v1/embed/[A-Za-z0-9_-]+(?:\\\\?.*)?$",
            RegexOption.IGNORE_CASE
        )
        var apiUrl: String? = null
        val resolver = WebViewResolver(
            interceptUrl = apiIntercept,
            additionalUrls = emptyList(),
            userAgent = ua,
            useOkhttp = true,
            timeout = 45_000L,
            script = script
        )

        resolver.resolveUsingWebView(
            kine,
            referer = parent,
            headers = mapOf(
                "Referer" to parent,
                "Origin" to mainUrl,
                "User-Agent" to ua
            )
        ) { req ->
            val u = req.url.toString()
            if (apiIntercept.containsMatchIn(u)) {
                apiUrl = u
                Log.d("HintFilmIzle", "KINESCOPE_API=" + u)
                true
            } else false
        }

        if (stream == null && apiUrl != null) {
            val apiBody = runCatching {
                app.get(
                    apiUrl!!,
                    referer = kine,
                    headers = mapOf(
                        "Referer" to kine,
                        "Origin" to mainUrl,
                        "User-Agent" to ua,
                        "Accept" to "application/json,text/plain,*/*"
                    )
                ).text
            }.getOrNull()
            stream = apiBody?.let { decodeKinescopeApi(it) }
            if (stream != null) Log.d("HintFilmIzle", "KINESCOPE_MANIFEST=" + stream)
        }

'''
s = s[:resolver_start] + replacement + s[resolver_end:]
path.write_text(s, encoding="utf-8")
print("HintFilmIzle Kinescope patch applied")
