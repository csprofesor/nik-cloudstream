from pathlib import Path

path = Path("HintFilmIzle/src/main/kotlin/com/nikyokki/HintFilmIzlePlugin.kt")
s = path.read_text(encoding="utf-8-sig")

# The site's player.hintfilmizle.com endpoint is not DNS-resolvable from Android.
# The WebView trace shows that it redirects to this Kinescope CDN embed endpoint.
old = '''            val parsed = runCatching { URI(kine) }.getOrNull() ?: return@runCatching false
            val query = parsed.rawQuery.orEmpty()
            val lang = Regex("(?:^|&)lang=([^&]+)").find(query)?.groupValues?.getOrNull(1) ?: "tr"
            val voiceover = Regex("(?:^|&)voiceover=([^&]+)").find(query)?.groupValues?.getOrNull(1)
            val nc = Regex("(?:^|&)nc=([^&]+)").find(query)?.groupValues?.getOrNull(1)
                ?: (System.currentTimeMillis() / 1000L).toString()

            val apiBase = "https://${parsed.host}/api/v1/embed/$id"
            val kinescopeIframe = kine
'''
new = '''            val parsed = runCatching { URI(kine) }.getOrNull() ?: return@runCatching false
            val query = parsed.rawQuery.orEmpty()
            val lang = Regex("(?:^|&)lang=([^&]+)").find(query)?.groupValues?.getOrNull(1) ?: "tr"
            val voiceover = Regex("(?:^|&)voiceover=([^&]+)").find(query)?.groupValues?.getOrNull(1)
            val nc = Regex("(?:^|&)nc=([^&]+)").find(query)?.groupValues?.getOrNull(1)
                ?: (System.currentTimeMillis() / 1000L).toString()

            val actualKine = if (parsed.host.equals("player.hintfilmizle.com", true)) {
                "https://river-3-329.kinescopecdn.net/677113747/embed/$id?design=3&lang=${URLEncoder.encode(lang, "UTF-8")}&autoplay=1&muted=1&preload=1&playsinline=1&background=1&enableIframeApi=1&nc=${URLEncoder.encode(nc, "UTF-8")}"
            } else kine
            val actualParsed = runCatching { URI(actualKine) }.getOrNull() ?: return@runCatching false
            val apiBase = "https://${actualParsed.host}/api/v1/embed/$id"
            val kinescopeIframe = actualKine
'''
if old not in s:
    raise SystemExit("Kinescope URL normalization block not found")
s = s.replace(old, new, 1)

# Fetch the real CDN embed, not the dead player.hintfilmizle.com hostname.
old = '''            val embedBody = runCatching {
                app.get(kinescopeIframe, referer = parent, headers = headers()).text
            }.getOrNull().orEmpty()
            Log.d("HintFilmIzle", "KINESCOPE_EMBED_HOST=${parsed.host}")
'''
new = '''            val embedBody = runCatching {
                app.get(kinescopeIframe, referer = parent, headers = headers()).text
            }.getOrNull().orEmpty()
            Log.d("HintFilmIzle", "KINESCOPE_EMBED_HOST=${actualParsed.host}")
'''
if old not in s:
    raise SystemExit("Kinescope embed logging block not found")
s = s.replace(old, new, 1)

# The CDN embed must supply the signed API URL; never call the unsigned kinescope.io API.
old = 'val candidates = listOfNotNull(signedApi, "$apiBase?$params").distinct()'
new = 'val candidates = listOfNotNull(signedApi).distinct()'
if old not in s:
    raise SystemExit("Kinescope candidates block not found")
s = s.replace(old, new, 1)

path.write_text(s, encoding="utf-8")
print("HintFilmIzle signed Kinescope CDN host fix applied; retrigger")
