from pathlib import Path

path = Path("HintFilmIzle/src/main/kotlin/com/nikyokki/HintFilmIzlePlugin.kt")
s = path.read_text(encoding="utf-8-sig")

# Keep the real Kinescope CDN embed URL and let its own embed.js/playerjs.js
# perform the signed handshake. The resolver watches both the API request and HLS.
s = s.replace(
    'val resolver = WebViewResolver(interceptUrl=intercept,additionalUrls=emptyList(),userAgent=ua,useOkhttp=false,timeout=25_000L,script=script)',
    'val resolver = WebViewResolver(interceptUrl=Regex("${kinescopeApiRegex.pattern}|${kinescopeManifestRegex.pattern}", RegexOption.IGNORE_CASE),additionalUrls=emptyList(),userAgent=ua,useOkhttp=false,timeout=60_000L,script=script)'
)

# The previous patch narrowed the intercept to HLS only. Restore API interception.
s = s.replace(
    'interceptUrl = kinescopeManifestRegex,',
    'interceptUrl = Regex("${kinescopeApiRegex.pattern}|${kinescopeManifestRegex.pattern}", RegexOption.IGNORE_CASE),'
)
s = s.replace('additionalUrls = listOf(kinescopeApiRegex),', 'additionalUrls = emptyList(),')
s = s.replace('timeout = 25_000L,', 'timeout = 60_000L,')

# Capture the signed /api/v1/embed/... URL from Chromium's resource timing.
# Kinescope may issue this request from code that does not use fetch/XHR hooks.
marker = '''                function scanResources() {'''
if marker in s and 'function captureKinescopeApi()' not in s:
    inject = '''                function captureKinescopeApi() {
                  try {
                    var es=performance.getEntriesByType('resource')||[];
                    for(var i=es.length-1;i>=0;i--){
                      var u=String(es[i].name||'');
                      if(/\\/api\\/v1\\/embed(?:-kp|-serials)?\\//i.test(u)){
                        window.__csApiUrl=u;
                        if(!window.__csApiSent){
                          window.__csApiSent=true;
                          window.location.href=u;
                        }
                        return;
                      }
                    }
                  } catch (_) {}
                }
'''
    s = s.replace(marker, inject + marker, 1)
    s = s.replace(
        '                    cleanAds(document); startPlayer();\n                    var es=performance.getEntriesByType',
        '                    cleanAds(document); startPlayer(); captureKinescopeApi();\n                    var es=performance.getEntriesByType',
        1
    )
    s = s.replace(
        "                scanResources();\n                [100,300,700,1500,3000,5000,10000].forEach(function(ms){setTimeout(scanResources,ms);});",
        "                scanResources(); captureKinescopeApi();\n                [100,300,700,1500,3000,5000,10000,20000].forEach(function(ms){setTimeout(function(){scanResources();captureKinescopeApi();},ms);});",
        1
    )
    s = s.replace(
        "                var scanTimer=setInterval(function(){if(window.__csHintManifest){clearInterval(scanTimer);return;}scanResources();},1000);",
        "                var scanTimer=setInterval(function(){if(window.__csHintManifest || window.__csApiUrl){clearInterval(scanTimer);return;}scanResources();captureKinescopeApi();},1000);",
        1
    )

# Keep the publisher id supplied by HintFilmIzle; do not replace it with a guessed CDN publisher.
s = s.replace(
    'add("https://player.hintfilmizle.com/embed/$id?design=3&lang=tr")',
    'add("https://river-3-329.kinescopecdn.net/$pub/embed/$id?design=3&lang=tr")'
)

# Keep fallback compilation safe.
s = s.replace(
    '''            response = runCatching { app.get(fallbackUrl, referer = "$mainUrl/", headers = headers()) }.getOrNull()\n            if (response != null) { doc = response.document; r = results(doc, slug) }''',
    '''            val fallbackResponse = runCatching { app.get(fallbackUrl, referer = "$mainUrl/", headers = headers()) }.getOrNull()\n            if (fallbackResponse != null) { doc = fallbackResponse.document; r = results(doc, slug) }''',
    1,
)

path.write_text(s, encoding="utf-8")
print("HintFilmIzle Kinescope: restore legacy CDN flow and capture signed API URL")
