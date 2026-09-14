from pathlib import Path

path = Path("HintFilmIzle/src/main/kotlin/com/nikyokki/HintFilmIzlePlugin.kt")
s = path.read_text(encoding="utf-8-sig")

lines = s.splitlines()
for i, line in enumerate(lines):
    if "val intercept = Regex(" in line and "kinescopecdn.net/hls" in line:
        indent = line[: len(line) - len(line.lstrip())]
        lines[i] = indent + 'val intercept = Regex("\\\\.kinescopecdn\\\\.net/hls/.+\\\\.m3u8(?:\\\\?.*)?$", RegexOption.IGNORE_CASE)'
    elif "val resolver = WebViewResolver(" in line and "timeout=90_000L" in line:
        indent = line[: len(line) - len(line.lstrip())]
        lines[i] = indent + 'val resolver = WebViewResolver(interceptUrl=intercept,additionalUrls=emptyList(),userAgent=ua,useOkhttp=false,timeout=25_000L,script=script)'
    elif "window.__csManifest=u;" in line and "kinescopecdn" in line:
        old = "window.__csManifest=u;"
        new = "window.__csManifest=u;if(!window.__csManifestSent){window.__csManifestSent=true;window.location.href=u;}"
        lines[i] = line.replace(old, new, 1)

s = "\n".join(lines) + "\n"

# Let the player complete its signed API handshake; only a real HLS manifest terminates WebView.
s = s.replace(
    'interceptUrl = Regex("${kinescopeApiRegex.pattern}|${kinescopeManifestRegex.pattern}", RegexOption.IGNORE_CASE),',
    'interceptUrl = kinescopeManifestRegex,'
)
s = s.replace(
    'additionalUrls = emptyList(),',
    'additionalUrls = listOf(kinescopeApiRegex),'
)
s = s.replace(
    'timeout = 60_000L,',
    'timeout = 25_000L,'
)

# Capture manifests produced by fetch/XHR while preserving the existing ad blocking.
marker = "                scanResources();"
lines = s.splitlines()
if marker in lines:
    idx = lines.index(marker)
    hook = [
        "                (function() {",
        "                  function captureManifest(value) {",
        "                    try {",
        "                      if (typeof value !== 'string' || !value) return;",
        "                      var m = value.match(/https?:\\/\\/[^\\s\\\"']*(?:kinescopecdn\\.net|kinescope\\.io)[^\\s\\\"']*\\.m3u8(?:\\?[^\\s\\\"']*)?/i);",
        "                      if (!m) m = value.match(/https?:\\/\\/[^\\s\\\"']*\\.m3u8(?:\\?[^\\s\\\"']*)?/i);",
        "                      if (m && !window.__csManifest) {",
        "                        window.__csManifest = m[0];",
        "                        window.__csHintManifest = m[0];",
        "                        if (!window.__csManifestSent) {",
        "                          window.__csManifestSent = true;",
        "                          window.location.href = m[0];",
        "                        }",
        "                      }",
        "                    } catch (_) {}",
        "                  }",
        "                  try {",
        "                    var originalFetch = window.fetch;",
        "                    if (originalFetch && !window.__csFetchHooked) {",
        "                      window.__csFetchHooked = true;",
        "                      window.fetch = function(input, init) {",
        "                        var requestUrl = '';",
        "                        try { requestUrl = typeof input === 'string' ? input : (input && input.url) || ''; } catch (_) {}",
        "                        captureManifest(requestUrl);",
        "                        var promise = originalFetch.call(this, input, init);",
        "                        try {",
        "                          promise.then(function(response) {",
        "                            try { captureManifest(response.url || requestUrl); } catch (_) {}",
        "                            try { response.clone().text().then(captureManifest).catch(function(){}); } catch (_) {}",
        "                          }).catch(function(){});",
        "                        } catch (_) {}",
        "                        return promise;",
        "                      };",
        "                    }",
        "                  } catch (_) {}",
        "                  try {",
        "                    if (!window.__csXHRHooked) {",
        "                      window.__csXHRHooked = true;",
        "                      var originalOpen = XMLHttpRequest.prototype.open;",
        "                      var originalSend = XMLHttpRequest.prototype.send;",
        "                      XMLHttpRequest.prototype.open = function(method, url) {",
        "                        this.__csRequestUrl = url || '';",
        "                        return originalOpen.apply(this, arguments);",
        "                      };",
        "                      XMLHttpRequest.prototype.send = function() {",
        "                        try {",
        "                          var xhr = this;",
        "                          captureManifest(xhr.__csRequestUrl || '');",
        "                          xhr.addEventListener('load', function() {",
        "                            try { captureManifest(xhr.responseURL || xhr.__csRequestUrl || ''); } catch (_) {}",
        "                            try { captureManifest(xhr.responseText || ''); } catch (_) {}",
        "                          });",
        "                        } catch (_) {}",
        "                        return originalSend.apply(this, arguments);",
        "                      };",
        "                    }",
        "                  } catch (_) {}",
        "                })();",
        marker,
    ]
    lines[idx:idx + 1] = hook
    s = "\n".join(lines) + "\n"

# Do not inject a second Kotlin Regex block here: the previous version emitted single backslashes
# into ordinary Kotlin strings (e.g. \s and \"), which Kotlin rejects as unsupported escapes.
# The WebView/fetch/XHR path above remains the fallback for the signed Kinescope manifest.

# Never synthesize a Kinescope CDN URL from a stale publisher/host.
s = s.replace(
    'add("https://river-3-329.kinescopecdn.net/$pub/embed/$id?design=3&lang=tr")',
    'add("https://player.hintfilmizle.com/embed/$id?design=3&lang=tr")'
)

# Make the nullable fallback assignment valid even when the secondary build patch is skipped.
s = s.replace(
    '''            response = runCatching { app.get(fallbackUrl, referer = "$mainUrl/", headers = headers()) }.getOrNull()\n            if (response != null) { doc = response.document; r = results(doc, slug) }''',
    '''            val fallbackResponse = runCatching { app.get(fallbackUrl, referer = "$mainUrl/", headers = headers()) }.getOrNull()\n            if (fallbackResponse != null) { doc = fallbackResponse.document; r = results(doc, slug) }''',
    1,
)

path.write_text(s, encoding="utf-8")
print("HintFilmIzle Kinescope: fixed Kotlin escaping; WebView signed-manifest capture and ad blocking preserved")
