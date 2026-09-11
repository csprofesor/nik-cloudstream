from pathlib import Path

path = Path("HintFilmIzle/src/main/kotlin/com/nikyokki/HintFilmIzlePlugin.kt")
s = path.read_text(encoding="utf-8-sig")

# The API response contains several labelled HLS URLs in one decrypted string.
# Pick the highest labelled quality and stop the URL at the comma separator.
lines = s.splitlines()
for i, line in enumerate(lines):
    if line.strip().startswith('return Regex("https?://') and 'kinescopecdn.net/hls' in line and 'index\\\\.m3u8' in line:
        lines[i:i + 1] = [
            '        val labelled = Regex("\\\\[(\\\\d{3,4})p\\\\]\\\\{[^}]*\\\\}(https?://[^\\\"\\\'\\\\s<>]+\\\\.kinescopecdn\\\\.net/hls/[^\\\"\\\',\\\\s<>]+/index\\\\.m3u8(?:\\\\?[^\\\"\\\',\\\\s<>]*)?)", RegexOption.IGNORE_CASE)',
            '            .findAll(decoded)',
            '            .mapNotNull { m -> m.groupValues.getOrNull(1)?.toIntOrNull()?.let { it to m.groupValues[2] } }',
            '            .maxByOrNull { it.first }?.second',
            '        val fallback = Regex("https?://[^\\\"\\\'\\\\s<>]+\\\\.kinescopecdn\\\\.net/hls/[^\\\"\\\',\\\\s<>]+/index\\\\.m3u8(?:\\\\?[^\\\"\\\',\\\\s<>]*)?", RegexOption.IGNORE_CASE).find(decoded)?.value',
            '        return (labelled ?: fallback)?.replace("\\\\/", "/")?.replace("\\\\u0026", "&")',
        ]
        break
else:
    raise SystemExit("Kinescope decoder return line not found")

s = "\\n".join(lines) + ("\\n" if s.endswith("\\n") else "")

start = s.find("        val intercept = Regex(")
end_marker = "        val final = stream ?: return false"
end = s.find(end_marker, start)
if start < 0 or end < 0:
    raise SystemExit("Kinescope WebView resolver block not found")

replacement = '''        // Kinescope requests /api/v1/embed/{id} before exposing the HLS URL.
        // Capture the already-signed API URL from WebView, then fetch and decode it.
        val apiIntercept = Regex(
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
            headers = mapOf("Referer" to parent, "Origin" to mainUrl, "User-Agent" to ua)
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
s = s[:start] + replacement + s[end:]
path.write_text(s, encoding="utf-8")
print("HintFilmIzle Kinescope API patch applied")
