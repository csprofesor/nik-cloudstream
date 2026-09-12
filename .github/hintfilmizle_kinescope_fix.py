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

# Make the nullable fallback assignment valid even when the secondary build patch is skipped.
s = s.replace(
    '''            response = runCatching { app.get(fallbackUrl, referer = "$mainUrl/", headers = headers()) }.getOrNull()\n            if (response != null) { doc = response.document; r = results(doc, slug) }''',
    '''            val fallbackResponse = runCatching { app.get(fallbackUrl, referer = "$mainUrl/", headers = headers()) }.getOrNull()\n            if (fallbackResponse != null) { doc = fallbackResponse.document; r = results(doc, slug) }''',
    1,
)

path.write_text(s, encoding="utf-8")
print("HintFilmIzle Kinescope network interception patch applied")
