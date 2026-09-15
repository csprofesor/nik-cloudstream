from pathlib import Path

path = Path("HintFilmIzle/src/main/kotlin/com/nikyokki/HintFilmIzlePlugin.kt")
s = path.read_text(encoding="utf-8-sig")

# Current Kinescope HLS URLs are commonly /<video-id>/master.m3u8. The old
# resolver required /hls/ and therefore never recognised the real manifest.
old_manifest = r'''private val kinescopeManifestRegex = Regex(
        "https?://[^\"'\\s<>]*(?:kinescopecdn\\.net|kinescope\\.io)/(?:[^\"'\\s<>]+/)*hls/[^\"'\\s<>]+\\.m3u8(?:\\?[^\"'\\s<>]*)?",
        RegexOption.IGNORE_CASE
    )'''
new_manifest = r'''private val kinescopeManifestRegex = Regex(
        "https?://[^\"'\\s<>]*(?:kinescopecdn\\.net|kinescope\\.io)/[^\"'\\s<>]*\\.m3u8(?:\\?[^\"'\\s<>]*)?",
        RegexOption.IGNORE_CASE
    )'''
s = s.replace(old_manifest, new_manifest, 1)

# Capture the current Kinescope API host as an additional request. API traffic
# must never terminate the WebView; only a real HLS manifest does that.
old_api = r'''private val kinescopeApiRegex = Regex("https?://(?:kinescope\\.io|[^\"'\\s<>]*kinescopecdn\\.net)(?:/[^\"'\\s<>]+)*/api/v1/embed(?:-kp|-serials)?/[^\"'\\s<>]+", RegexOption.IGNORE_CASE)'''
new_api = r'''private val kinescopeApiRegex = Regex("https?://api\\.kinescope\\.io/[^\"'\\s<>]+", RegexOption.IGNORE_CASE)'''
s = s.replace(old_api, new_api, 1)

# Never intercept every HTTP request: that would stop the player on player.js,
# analytics, fonts, etc. before playback starts.
s = s.replace(
    'interceptUrl = Regex("${kinescopeApiRegex.pattern}|${kinescopeManifestRegex.pattern}", RegexOption.IGNORE_CASE),',
    'interceptUrl = Regex(kinescopeManifestRegex.pattern, RegexOption.IGNORE_CASE),',
    1,
)
s = s.replace(
    'additionalUrls = emptyList(), userAgent = ua, useOkhttp = false, timeout = 60_000L, script = script',
    'additionalUrls = listOf(kinescopeApiRegex), userAgent = ua, useOkhttp = false, timeout = 90_000L, script = script',
    1,
)

# Force the hidden WebView player to start. Kinescope supports autoplay/muted
# URL parameters on iframe embeds.
s = s.replace(
    'val target = if (parsedKine?.host.equals("player.hintfilmizle.com", true)) kine else kine',
    'val target = if (kine.contains("autoplay=", true)) kine else if (kine.contains("?")) "$kine&autoplay=1&muted=1" else "$kine?autoplay=1&muted=1"',
    1,
)

# Update the JS scanner to the same current manifest layout.
old_js = r'''var m = u.match(/https?:\\/\\/[^\\s\"']*(?:kinescopecdn\\.net|kinescope\\.io)\\/(?:[^\\s\"']+\\/)*hls\\/[^\\s\"']+\\.m3u8(?:\\?[^\\s\"']*)?/i);'''
new_js = r'''var m = u.match(/https?:\\/\\/[^\\s\"']*(?:kinescopecdn\\.net|kinescope\\.io)\\/[^\\s\"']*\\.m3u8(?:\\?[^\\s\"']*)?/i);'''
s = s.replace(old_js, new_js, 1)

path.write_text(s, encoding="utf-8")
print("HintFilmIzle Kinescope: fixed manifest detection, safe interception, API capture, autoplay")