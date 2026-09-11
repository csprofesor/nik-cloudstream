from pathlib import Path

PATH = Path('HintFilmIzle/src/main/kotlin/com/nikyokki/HintFilmIzlePlugin.kt')
text = PATH.read_text(encoding='utf-8')

old = '''        var response = runCatching { app.get(url, referer = "$mainUrl/", headers = headers()) }.getOrNull()\n            ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)\n'''
new = '''        var response = runCatching { app.get(url, referer = "$mainUrl/", headers = headers()) }.getOrNull()\n        if (response == null) return newHomePageResponse(request.name, emptyList(), hasNext = false)\n'''
if old in text:
    text = text.replace(old, new, 1)

start = text.find('    private suspend fun kinescope(kine: String, parent: String, callback: (ExtractorLink) -> Unit): Boolean')
end = text.find('    override suspend fun loadLinks(', start)
if start < 0 or end < 0:
    raise SystemExit('HintFilmIzle kinescope boundaries not found')

# Preserve the current Kinescope implementation; this fixer only needs to patch
# the shared WebViewClient network filter below. The source plugin already uses
# native WebView networking (useOkhttp=false) for the Kinescope handshake.

WEBVIEW = Path('library/src/androidMain/kotlin/com/lagradost/cloudstream3/network/WebViewResolver.android.kt')
if WEBVIEW.exists():
    wv = WEBVIEW.read_text(encoding='utf-8')
    old_domain = '                            host == "mc.yandex.ru" || host.endsWith(".mc.yandex.ru")'
    new_domain = '                            host == "mc.yandex.ru" || host.endsWith(".mc.yandex.ru") ||\n                            host == "mc.yandex.com" || host.endsWith(".mc.yandex.com")'
    if old_domain in wv and 'host == "mc.yandex.com"' not in wv:
        wv = wv.replace(old_domain, new_domain, 1)
        WEBVIEW.write_text(wv, encoding='utf-8')
        print('HintFilmIzle V14: added mc.yandex.com to Kinescope-only analytics blocklist')
    elif 'host == "mc.yandex.com"' in wv:
        print('HintFilmIzle V14: mc.yandex.com block already present')
    else:
        print('HintFilmIzle V14: existing blocklist marker not found')
else:
    print('CloudStream WebViewResolver.android.kt not found; skipping V14 domain update')
