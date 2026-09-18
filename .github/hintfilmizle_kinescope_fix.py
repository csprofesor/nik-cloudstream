from pathlib import Path

path = Path("HintFilmIzle/src/main/kotlin/com/nikyokki/HintFilmIzlePlugin.kt")
s = path.read_text(encoding="utf-8-sig")


def replace_once(old: str, new: str, label: str) -> None:
    global s
    if new in s:
        print(f"Skipping {label}: already patched")
        return
    if old not in s:
        print(f"Warning: Kinescope patch target not found: {label}, skipping")
        return
    s = s.replace(old, new, 1)

# Current resolver is V13. Broaden the manifest matcher: Kinescope does not
# guarantee that the HLS URL contains /hls/ in every CDN layout.
old_manifest = r'''private val kinescopeManifestRegex = Regex(
        "https?://[^\"'\\s<>]*(?:kinescopecdn\\.net|kinescope\\.io)/(?:[^\"'\\s<>]+/)*hls/[^\"'\\s<>]+\\.m3u8(?:\\?[^\"'\\s<>]*)?",
        RegexOption.IGNORE_CASE
    )'''
new_manifest = r'''private val kinescopeManifestRegex = Regex(
        "https?://[^\"'\\s<>]*(?:kinescopecdn\\.net|kinescope\\.io)/[^\"'\\s<>]*\\.m3u8(?:\\?[^\"'\\s<>]*)?",
        RegexOption.IGNORE_CASE
    )'''
replace_once(old_manifest, new_manifest, "manifest regex")

# Keep API discovery broad enough for the current Kinescope player.
old_api = r'''private val kinescopeApiRegex = Regex("https?://(?:kinescope\\.io|[^\"'\\s<>]*kinescopecdn\\.net)(?:/[^\"'\\s<>]+)*/api/v1/embed(?:-kp|-serials)?/[^\"'\\s<>]+", RegexOption.IGNORE_CASE)'''
new_api = r'''private val kinescopeApiRegex = Regex("https?://[^\"'\\s<>]*(?:kinescopecdn\\.net|kinescope\\.io)/[^\"'\\s<>]*/api/[^\"'\\s<>]+", RegexOption.IGNORE_CASE)'''
replace_once(old_api, new_api, "API regex")

# Never let an API/config request terminate WebViewResolver. Only the actual
# HLS manifest should be returned as an ExtractorLink.
replace_once(
    'interceptUrl = Regex("${kinescopeApiRegex.pattern}|${kinescopeManifestRegex.pattern}", RegexOption.IGNORE_CASE),',
    'interceptUrl = Regex(kinescopeManifestRegex.pattern, RegexOption.IGNORE_CASE),',
    "manifest-only interception",
)

replace_once(
    'additionalUrls = emptyList(), userAgent = ua, useOkhttp = false, timeout = 60_000L, script = script',
    'additionalUrls = emptyList(), userAgent = ua, useOkhttp = false, timeout = 90_000L, script = script',
    "resolver timeout",
)

# Inject XHR/fetch response-body capture into the existing V13 page script.
# The Kinescope embed loads playerjs.js but the useful playback URL is created
# dynamically, so it does not necessarily appear as a normal resource early on.
needle = '''                function scanResources() {\n                  try {\n                    cleanAds(document); startPlayer();'''
injection = '''                function captureManifestText(value) {\n                  try {\n                    if (typeof value !== 'string') return;\n                    var m = isManifest(value);\n                    if (!m || window.__csHintManifest === m) return;\n                    window.__csHintManifest = m;\n                    try {\n                      var v = document.createElement('video');\n                      v.muted = true;\n                      v.setAttribute('muted','');\n                      v.setAttribute('playsinline','');\n                      v.preload = 'metadata';\n                      v.src = m;\n                      document.documentElement.appendChild(v);\n                      v.load();\n                    } catch (_) {}\n                  } catch (_) {}\n                }\n                try {\n                  var nativeOpen = XMLHttpRequest.prototype.open;\n                  var nativeSend = XMLHttpRequest.prototype.send;\n                  XMLHttpRequest.prototype.open = function(method, url) {\n                    try { this.__csHintUrl = String(url || ''); } catch (_) { this.__csHintUrl = ''; }\n                    return nativeOpen.apply(this, arguments);\n                  };\n                  XMLHttpRequest.prototype.send = function() {\n                    try {\n                      this.addEventListener('load', function() {\n                        try {\n                          captureManifestText(String(this.responseURL || this.__csHintUrl || ''));\n                          captureManifestText(String(this.responseText || ''));\n                        } catch (_) {}\n                      });\n                    } catch (_) {}\n                    return nativeSend.apply(this, arguments);\n                  };\n                } catch (_) {}\n                try {\n                  var nativeFetch = window.fetch;\n                  window.fetch = function() {\n                    return nativeFetch.apply(this, arguments).then(function(response) {\n                      try {\n                        captureManifestText(String(response.url || ''));\n                        response.clone().text().then(function(text) {\n                          captureManifestText(String(text || ''));\n                        }).catch(function() {});\n                      } catch (_) {}\n                      return response;\n                    });\n                  };\n                } catch (_) {}\n                function scanResources() {\n                  try {\n                    cleanAds(document); startPlayer();'''
replace_once(needle, injection, "XHR/fetch hook")

path.write_text(s, encoding="utf-8")
print("HintFilmIzle Kinescope V14 patch applied")
