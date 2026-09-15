from pathlib import Path

path = Path("HintFilmIzle/src/main/kotlin/com/nikyokki/HintFilmIzlePlugin.kt")
s = path.read_text(encoding="utf-8-sig")

# Kinescope currently exposes HLS as /<video-id>/master.m3u8 (and sometimes
# through longer edge-CDN paths). The old resolver required /hls/ and missed it.
s = s.replace(
    'private val kinescopeManifestRegex = Regex(\n        "https?://[^\\\"\'\\\\s<>]*(?:kinescopecdn\\\\.net|kinescope\\\\.io)/(?:[^\\\"\'\\\\s<>]+/)*hls/[^\\\"\'\\\\s<>]+\\\\.m3u8(?:\\\\?[^\\\"\'\\\\s<>]*)?",\n        RegexOption.IGNORE_CASE\n    )',
    'private val kinescopeManifestRegex = Regex(\n        "https?://[^\\\"\\\'\\\\s<>]*(?:kinescopecdn\\\\.net|kinescope\\\\.io)/[^\\\"\\\'\\\\s<>]*\\\\.m3u8(?:\\\\?[^\\\"\\\'\\\\s<>]*)?",\n        RegexOption.IGNORE_CASE\n    )',
    1,
)

# Capture the current Kinescope API host as an additional request. It is not
# the terminating URL; only the actual HLS manifest is allowed to terminate.
s = s.replace(
    'private val kinescopeApiRegex = Regex("https?://(?:kinescope\\\\.io|[^\\\"\\\'\\\\s<>]*kinescopecdn\\\\.net)(?:/[^\\\"\\\'\\\\s<>]+)*/api/v1/embed(?:-kp|-serials)?/[^\\\"\\\'\\\\s<>]+", RegexOption.IGNORE_CASE)',
    'private val kinescopeApiRegex = Regex("https?://api\\\\.kinescope\\\\.io/[^\\\"\\\'\\\\s<>]+", RegexOption.IGNORE_CASE)',
    1,
)

# Never intercept every HTTP request: that kills the WebView on player.js,
# analytics, fonts, etc. before Kinescope can initialise playback.
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

# Start the hidden player without waiting for a user gesture. Kinescope
# documents autoplay/muted URL parameters for iframe embeds.
s = s.replace(
    'val target = if (parsedKine?.host.equals("player.hintfilmizle.com", true)) kine else kine',
    'val target = if (kine.contains("autoplay=", true)) kine else if (kine.contains("?")) "$kine&autoplay=1&muted=1" else "$kine?autoplay=1&muted=1"',
    1,
)

# The JS scanner had the same obsolete /hls/ assumption.
s = s.replace(
    'var m = u.match(/https?:\\\\/\\\\/[^\\\\s\\\"\']*(?:kinescopecdn\\\\.net|kinescope\\\\.io)\\\\/(?:[^\\\\s\\\"\']+\\\\/)*hls\\\\/[^\\\\s\\\"\']+\\\\.m3u8(?:\\\\?[^\\\\s\\\"\']*)?/i);',
    'var m = u.match(/https?:\\\\/\\\\/[^\\\\s\\\"\']*(?:kinescopecdn\\\\.net|kinescope\\\\.io)\\\\/[^\\\\s\\\"\']*\\\\.m3u8(?:\\\\?[^\\\\s\\\"\']*)?/i);',
    1,
)

path.write_text(s, encoding="utf-8")
print("HintFilmIzle Kinescope: fixed master.m3u8 detection, safe interception, API capture, autoplay")