from pathlib import Path

path = Path("HintFilmIzle/src/main/kotlin/com/nikyokki/HintFilmIzlePlugin.kt")
s = path.read_text(encoding="utf-8-sig")


def replace_once(old: str, new: str, label: str) -> None:
    global s
    if old not in s:
        raise RuntimeError(f"Kinescope patch target not found: {label}")
    s = s.replace(old, new, 1)


# Kinescope can expose the HLS manifest as /.../*.m3u8 instead of /hls/*.m3u8.
old_manifest = r'''private val kinescopeManifestRegex = Regex(
        "https?://[^\"'\\s<>]*(?:kinescopecdn\\.net|kinescope\\.io)/(?:[^\"'\\s<>]+/)*hls/[^\"'\\s<>]+\\.m3u8(?:\\?[^\"'\\s<>]*)?",
        RegexOption.IGNORE_CASE
    )'''
new_manifest = r'''private val kinescopeManifestRegex = Regex(
        "https?://[^\"'\\s<>]*(?:kinescopecdn\\.net|kinescope\\.io)/[^\"'\\s<>]*\\.m3u8(?:\\?[^\"'\\s<>]*)?",
        RegexOption.IGNORE_CASE
    )'''
replace_once(old_manifest, new_manifest, "manifest regex")

# Keep API capture broad enough for the current Kinescope CDN/API layout.
old_api = r'''private val kinescopeApiRegex = Regex("https?://(?:kinescope\\.io|[^\"'\\s<>]*kinescopecdn\\.net)(?:/[^\"'\\s<>]+)*/api/v1/embed(?:-kp|-serials)?/[^\"'\\s<>]+", RegexOption.IGNORE_CASE)'''
new_api = r'''private val kinescopeApiRegex = Regex("https?://[^\"'\\s<>]*(?:kinescopecdn\\.net|kinescope\\.io)/[^\"'\\s<>]*/api/[^\"'\\s<>]+", RegexOption.IGNORE_CASE)'''
replace_once(old_api, new_api, "API regex")

# Only a real HLS manifest is allowed to terminate the WebView. API requests
# continue normally and are optionally inspected by the native fallback.
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

# Do not leave the embed in Kinescope background mode. Force muted autoplay and
# preserve any existing query parameters so the player actually starts.
replace_once(
    'val target = if (parsedKine?.host.equals("player.hintfilmizle.com", true)) kine else kine',
    'val target = run { val base = if (kine.contains("background=", true)) kine.replace(Regex("([?&]background=)[^&]*", RegexOption.IGNORE_CASE), "${'$'}10") else kine; if (base.contains("autoplay=", true)) base else if (base.contains("?")) "${'$'}base&autoplay=1&muted=1&playsinline=1&preload=1" else "${'$'}base?autoplay=1&muted=1&playsinline=1&preload=1" }',
    "autoplay target",
)

# Update the in-page manifest scanner to the same current URL layout.
old_js = r'''var m = u.match(/https?:\\/\\/[^\\s\"']*(?:kinescopecdn\\.net|kinescope\\.io)\\/(?:[^\\s\"']+\\/)*hls\\/[^\\s\"']+\\.m3u8(?:\\?[^\\s\"']*)?/i);'''
new_js = r'''var m = u.match(/https?:\\/\\/[^\\s\"']*(?:kinescopecdn\\.net|kinescope\\.io)\\/[^\\s\"']*\\.m3u8(?:\\?[^\\s\"']*)?/i);'''
replace_once(old_js, new_js, "JS manifest regex")

# The player currently does not expose the manifest as a normal resource in the
# WebView log. It requests its playback configuration from JavaScript instead.
# Hook XHR/fetch, inspect their response bodies, and navigate to the exact HLS
# URL when one appears. WebViewResolver then sees the real manifest request and
# can return it to CloudStream without depending on the player's internal API.
old_scan = r'''                function scanResources() {'''
new_scan = r'''                function captureManifestText(value) {
                  try {
                    if (typeof value !== 'string') return;
                    var m = isManifest(value);
                    if (m) {
                      window.__csHintManifest = m;
                      if (!window.__csHintNavigated) {
                        window.__csHintNavigated = true;
                        try { window.location.href = m; } catch (_) {}
                      }
                    }
                  } catch (_) {}
                }
                try {
                  var nativeOpen = XMLHttpRequest.prototype.open;
                  var nativeSend = XMLHttpRequest.prototype.send;
                  XMLHttpRequest.prototype.open = function(method, url) {
                    try { this.__csHintUrl = String(url || ''); } catch (_) { this.__csHintUrl = ''; }
                    return nativeOpen.apply(this, arguments);
                  };
                  XMLHttpRequest.prototype.send = function() {
                    try {
                      this.addEventListener('load', function() {
                        try {
                          captureManifestText(String(this.responseText || ''));
                          captureManifestText(String(this.responseURL || this.__csHintUrl || ''));
                        } catch (_) {}
                      });
                    } catch (_) {}
                    return nativeSend.apply(this, arguments);
                  };
                } catch (_) {}
                try {
                  var nativeFetch = window.fetch;
                  window.fetch = function() {
                    return nativeFetch.apply(this, arguments).then(function(response) {
                      try {
                        captureManifestText(String(response.url || ''));
                        var copy = response.clone();
                        copy.text().then(function(text) { captureManifestText(String(text || '')); }).catch(function() {});
                      } catch (_) {}
                      return response;
                    });
                  };
                } catch (_) {}
                function scanResources() {'''
replace_once(old_scan, new_scan, "XHR/fetch manifest hook")

path.write_text(s, encoding="utf-8")
print("HintFilmIzle Kinescope: hooked XHR/fetch response bodies and HLS navigation")
