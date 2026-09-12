from pathlib import Path

path = Path("HintFilmIzle/src/main/kotlin/com/nikyokki/HintFilmIzlePlugin.kt")
s = path.read_text(encoding="utf-8-sig")

# Keep the existing resolver, but make the WebView stop on any real Kinescope HLS manifest.
s = s.replace(
    '''        val intercept = Regex("\\\\.kinescopecdn\\\\.net/hls/.+/index\\\\.m3u8(?:\\\\?.*)?$", RegexOption.IGNORE_CASE)''',
    '''        val intercept = Regex("\\\\.kinescopecdn\\\\.net/hls/.+\\\\.m3u8(?:\\\\?.*)?$", RegexOption.IGNORE_CASE)''',
    1,
)

# The WebView should use its native network stack for Kinescope. OkHttp replacement
# can alter browser-only requests and is unnecessary once the manifest itself is intercepted.
s = s.replace(
    '''        val resolver = WebViewResolver(interceptUrl=intercept,additionalUrls=emptyList(),userAgent=ua,useOkhttp=true,timeout=90_000L,script=script)''',
    '''        val resolver = WebViewResolver(interceptUrl=intercept,additionalUrls=emptyList(),userAgent=ua,useOkhttp=false,timeout=25_000L,script=script)''',
    1,
)

# If the player exposes the manifest through performance entries instead of a normal
# navigational request, force one harmless navigation to that exact signed URL. The
# resolver's interceptUrl then captures it immediately and destroys the WebView.
s = s.replace(
    '''if(/\\\\.kinescopecdn\\\\.net\\\\/hls\\\\/.+\\\\/index\\\\.m3u8/i.test(u))window.__csManifest=u;''',
    '''if(/\\\\.kinescopecdn\\\\.net\\\\/hls\\\\/.+\\\\.m3u8/i.test(u)){window.__csManifest=u;if(!window.__csManifestSent){window.__csManifestSent=true;window.location.href=u;}}''',
    1,
)

# Also fix the known nullable fallback assignment at source level so a standalone
# extension build is valid even if the second workflow patch is bypassed.
s = s.replace(
    '''            response = runCatching { app.get(fallbackUrl, referer = "$mainUrl/", headers = headers()) }.getOrNull()\n            if (response != null) { doc = response.document; r = results(doc, slug) }''',
    '''            val fallbackResponse = runCatching { app.get(fallbackUrl, referer = "$mainUrl/", headers = headers()) }.getOrNull()\n            if (fallbackResponse != null) { doc = fallbackResponse.document; r = results(doc, slug) }''',
    1,
)

path.write_text(s, encoding="utf-8")
print("HintFilmIzle Kinescope network interception patch applied")
