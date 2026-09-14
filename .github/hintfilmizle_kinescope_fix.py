from pathlib import Path

path = Path("HintFilmIzle/src/main/kotlin/com/nikyokki/HintFilmIzlePlugin.kt")
s = path.read_text(encoding="utf-8-sig")

# Watch every HTTP(S) request from the legacy player. The previous narrow regex
# showed only embed.js/playerjs.js and never exposed the actual media handshake.
s = s.replace(
    'interceptUrl = Regex("${kinescopeApiRegex.pattern}|${kinescopeManifestRegex.pattern}", RegexOption.IGNORE_CASE),',
    'interceptUrl = Regex("https?://.*", RegexOption.IGNORE_CASE),',
    1,
)

# Keep the resolver alive long enough for the legacy player to perform its
# delayed handshake, while still terminating immediately on a real manifest.
s = s.replace('timeout = 60_000L,', 'timeout = 90_000L,', 1)

# Add direct XHR/fetch instrumentation. Some old Kinescope player builds issue
# their signed request through code paths that are awkward for WebViewResolver
# to surface. We record the exact URL and navigate to it after the request is
# observed, which makes the request itself the resolver target.
marker = '''                function cleanAds(root) {'''
if marker in s and 'function hookKinescopeNetwork()' not in s:
    inject = '''                function hookKinescopeNetwork() {
                  try {
                    function capture(u) {
                      try {
                        u=String(u||'');
                        if(!/^https?:\\/\\//i.test(u)) return;
                        if(/\\/api\\/v1\\/embed(?:-kp|-serials)?\\//i.test(u)) {
                          window.__csApiUrl=u;
                          if(!window.__csApiSent){
                            window.__csApiSent=true;
                            setTimeout(function(){try{window.location.href=u;}catch(_){ }},0);
                          }
                        }
                      } catch (_) {}
                    }
                    if(!window.__csFetchHooked && window.fetch){
                      window.__csFetchHooked=true;
                      var origFetch=window.fetch;
                      window.fetch=function(input,init){
                        try { capture(typeof input==='string'?input:(input&&input.url)); } catch(_) {}
                        return origFetch.apply(this,arguments);
                      };
                    }
                    if(!window.__csXhrHooked && window.XMLHttpRequest){
                      window.__csXhrHooked=true;
                      var xo=XMLHttpRequest.prototype.open;
                      XMLHttpRequest.prototype.open=function(method,url){
                        try { capture(url); } catch(_) {}
                        return xo.apply(this,arguments);
                      };
                    }
                  } catch (_) {}
                }
'''
    s = s.replace(marker, inject + marker, 1)
    s = s.replace(
        '                    cleanAds(document); startPlayer();',
        '                    hookKinescopeNetwork(); cleanAds(document); startPlayer();',
        1,
    )
    s = s.replace(
        '                scanResources();\n                [100,300,700,1500,3000,5000,10000,20000].forEach(function(ms){setTimeout(function(){scanResources();captureKinescopeApi();},ms);});',
        '                hookKinescopeNetwork(); scanResources();\n                [100,300,700,1500,3000,5000,10000,20000,40000].forEach(function(ms){setTimeout(function(){hookKinescopeNetwork();scanResources();captureKinescopeApi();},ms);});',
        1,
    )
    s = s.replace(
        'var scanTimer=setInterval(function(){if(window.__csHintManifest || window.__csApiUrl){clearInterval(scanTimer);return;}scanResources();captureKinescopeApi();}',
        'var scanTimer=setInterval(function(){if(window.__csHintManifest || window.__csApiUrl){clearInterval(scanTimer);return;}hookKinescopeNetwork();scanResources();captureKinescopeApi();}',
        1,
    )

# Log every Kinescope request exposed by the broad resolver. Only API and HLS
# requests are consumed; everything else continues loading normally.
s = s.replace(
    'val u=req.url.toString()\n            if(kinescopeApiRegex.containsMatchIn(u))',
    'val u=req.url.toString()\n            if(u.contains("kinescope", true)) Log.d("HintFilmIzle","KINESCOPE_REQUEST=${redactUrlForLog(u)}")\n            if(kinescopeApiRegex.containsMatchIn(u))',
    1,
)

path.write_text(s, encoding="utf-8")
print("HintFilmIzle Kinescope: broad network capture + XHR/fetch hooks")
