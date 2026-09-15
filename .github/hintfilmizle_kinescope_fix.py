from pathlib import Path

path = Path("HintFilmIzle/src/main/kotlin/com/nikyokki/HintFilmIzlePlugin.kt")
s = path.read_text(encoding="utf-8-sig")


def replace_once(old: str, new: str, label: str) -> None:
    global s
    if old not in s:
        raise RuntimeError(f"Kinescope patch target not found: {label}")
    s = s.replace(old, new, 1)


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
replace_once(old_manifest, new_manifest, "manifest regex")

# Capture the current Kinescope API host as an additional request. API traffic
# must never terminate the WebView; only a real HLS manifest does that.
old_api = r'''private val kinescopeApiRegex = Regex("https?://(?:kinescope\\.io|[^\"'\\s<>]*kinescopecdn\\.net)(?:/[^\"'\\s<>]+)*/api/v1/embed(?:-kp|-serials)?/[^\"'\\s<>]+", RegexOption.IGNORE_CASE)'''
new_api = r'''private val kinescopeApiRegex = Regex("https?://api\\.kinescope\\.io/[^\"'\\s<>]+", RegexOption.IGNORE_CASE)'''
replace_once(old_api, new_api, "API regex")

# Never intercept every HTTP request: that would stop the player on player.js,
# analytics, fonts, etc. before playback starts.
replace_once(
    'interceptUrl = Regex("${kinescopeApiRegex.pattern}|${kinescopeManifestRegex.pattern}", RegexOption.IGNORE_CASE),',
    'interceptUrl = Regex(kinescopeManifestRegex.pattern, RegexOption.IGNORE_CASE),',
    "safe manifest interception",
)
replace_once(
    'additionalUrls = emptyList(), userAgent = ua, useOkhttp = false, timeout = 60_000L, script = script',
    'additionalUrls = listOf(kinescopeApiRegex), userAgent = ua, useOkhttp = false, timeout = 90_000L, script = script',
    "API capture and timeout",
)

# Force the hidden WebView player to start. Kinescope supports autoplay/muted
# URL parameters on iframe embeds.
replace_once(
    'val target = if (parsedKine?.host.equals("player.hintfilmizle.com", true)) kine else kine',
    'val target = if (kine.contains("autoplay=", true)) kine else if (kine.contains("?")) "$kine&autoplay=1&muted=1" else "$kine?autoplay=1&muted=1"',
    "autoplay target",
)

# Update the JS scanner to the same current manifest layout.
old_js = r'''var m = u.match(/https?:\\/\\/[^\\s\"']*(?:kinescopecdn\\.net|kinescope\\.io)\\/(?:[^\\s\"']+\\/)*hls\\/[^\\s\"']+\\.m3u8(?:\\?[^\\s\"']*)?/i);'''
new_js = r'''var m = u.match(/https?:\\/\\/[^\\s\"']*(?:kinescopecdn\\.net|kinescope\\.io)\\/[^\\s\"']*\\.m3u8(?:\\?[^\\s\"']*)?/i);'''
replace_once(old_js, new_js, "JS manifest regex")

path.write_text(s, encoding="utf-8")
print("HintFilmIzle Kinescope: fixed manifest detection, safe interception, API capture, autoplay")