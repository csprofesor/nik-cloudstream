from pathlib import Path

PATH = Path('HintFilmIzle/src/main/kotlin/com/nikyokki/HintFilmIzlePlugin.kt')
text = PATH.read_text(encoding='utf-8')

old = '''        var response = runCatching { app.get(url, referer = "$mainUrl/", headers = headers()) }.getOrNull()\n            ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)\n'''
new = '''        var response = runCatching { app.get(url, referer = "$mainUrl/", headers = headers()) }.getOrNull()\n        if (response == null) return newHomePageResponse(request.name, emptyList(), hasNext = false)\n'''
if old in text:
    text = text.replace(old, new, 1)

start = text.find('    private suspend fun kinescope(')
end = text.find('    override suspend fun loadLinks(', start)
if start < 0 or end < 0:
    raise SystemExit('HintFilmIzle kinescope boundaries not found')

replacement = r'''    private suspend fun kinescope(kine: String, parent: String, callback: (ExtractorLink) -> Unit): Boolean = runCatching {
        val id = Regex("/embed/([A-Za-z0-9_-]+)", RegexOption.IGNORE_CASE).find(kine)?.groupValues?.getOrNull(1) ?: return false
        val target = if (kine.contains("river-3-329.kinescopecdn.net", true)) kine else
            "https://river-3-329.kinescopecdn.net/677113747/embed/$id?design=3&lang=${URLEncoder.encode(lang.ifBlank { "tr" }, "UTF-8")}&autoplay=1&muted=1&preload=1&playsinline=1&background=1&enableIframeApi=1&nc=${System.currentTimeMillis() / 1000L}"

        val manifestRegex = Regex(
            "https?://[^\"'\\s<>]+\\.kinescopecdn\\.net/hls/[^\"'\\s<>]+/index\\.m3u8(?:\\?[^\"'\\s<>]*)?",
            RegexOption.IGNORE_CASE
        )
        var stream: String? = null
        var streamHeaders: Map<String, String> = emptyMap()

        val script = """
            (function() {
              try {
                if (window.__csHintKineV10) return true;
                window.__csHintKineV10 = true;
                var KEY = 'RySdvcyu5iTUxn97vn4HwoniwgxaCynA';
                function cleanAds() {
                  try {
                    document.querySelectorAll('.belink, .belink.active, [class*="belink"], [id*="belink"]').forEach(function(e) {
                      e.style.setProperty('display', 'none', 'important');
                      e.style.setProperty('visibility', 'hidden', 'important');
                      e.style.setProperty('pointer-events', 'none', 'important');
                    });
                  } catch (_) {}
                }
                cleanAds();
                new MutationObserver(cleanAds).observe(document.documentElement, {subtree:true, childList:true, attributes:true});
                function findManifest(value, seen) {
                  try {
                    if (value == null) return null;
                    if (typeof value === 'string') {
                      var m = value.match(/https?:\\/\\/[^\\s\"']+\\.kinescopecdn\\.net\\/hls\\/[^\\s\"']+\\/index\\.m3u8(?:\\?[^\\s\"']*)?/i);
                      return m ? m[0] : null;
                    }
                    if (typeof value !== 'object') return null;
                    seen = seen || [];
                    if (seen.indexOf(value) >= 0) return null;
                    seen.push(value);
                    if (Array.isArray(value)) {
                      for (var i=0;i<value.length;i++) { var a=findManifest(value[i],seen); if(a)return a; }
                    } else {
                      for (var k in value) { try { var b=findManifest(value[k],seen); if(b)return b; } catch(_){} }
                    }
                  } catch (_) {}
                  return null;
                }
                function forceVideo(url) {
                  try {
                    if (!url || window.__csHintManifest === url) return;
                    window.__csHintManifest = url;
                    var v = document.createElement('video');
                    v.muted = true;
                    v.setAttribute('muted','');
                    v.setAttribute('playsinline','');
                    v.preload = 'metadata';
                    v.src = url;
                    document.documentElement.appendChild(v);
                    v.load();
                  } catch (_) {}
                }
                function inspectResponse(text) {
                  try {
                    var direct=findManifest(text,[]);
                    if(direct){forceVideo(direct);return;}
                    var obj=JSON.parse(text);
                    if(!obj || typeof obj.p!=='string') return;
                    var binary=atob(obj.p.split('').reverse().join(''));
                    var out=new Uint8Array(binary.length);
                    for(var i=0;i<binary.length;i++) out[i]=binary.charCodeAt(i)^KEY.charCodeAt(i%KEY.length);
                    var decoded=new TextDecoder('utf-8').decode(out);
                    var found=findManifest(JSON.parse(decoded),[]);
                    if(found) forceVideo(found);
                  }catch(_){}
                }
                var of=window.fetch;
                if(of){
                  window.fetch=function(){
                    var args=arguments;
                    return of.apply(this,args).then(function(r){
                      try{r.clone().text().then(inspectResponse).catch(function(){});}catch(_){}
                      return r;
                    });
                  };
                }
                var oo=XMLHttpRequest.prototype.open;
                var os=XMLHttpRequest.prototype.send;
                XMLHttpRequest.prototype.open=function(method,url){this.__csHintUrl=String(url||'');return oo.apply(this,arguments);};
                XMLHttpRequest.prototype.send=function(){
                  try{this.addEventListener('load',function(){inspectResponse(this.responseText||'');});}catch(_){}
                  return os.apply(this,arguments);
                };
                function scanResources(){
                  try{
                    var es=performance.getEntriesByType('resource')||[];
                    for(var i=0;i<es.length;i++){
                      var u=String(es[i].name||'');
                      if(/\\/api\\/v1\\/embed\\//i.test(u)){
                        fetch(u,{credentials:'include'}).then(function(r){return r.text();}).then(inspectResponse).catch(function(){});
                      }else if(/\\.m3u8(?:\\?|$)/i.test(u)) forceVideo(u);
                    }
                  }catch(_){}
                }
                setTimeout(scanResources,100);
                setTimeout(scanResources,500);
                setInterval(scanResources,1000);
                return true;
              }catch(_){return false;}
            })()
        """.trimIndent()

        val resolver = WebViewResolver(
            interceptUrl = Regex("m3u8", RegexOption.IGNORE_CASE),
            additionalUrls = emptyList(),
            userAgent = ua,
            useOkhttp = false,
            timeout = 60_000L,
            script = script
        )

        resolver.resolveUsingWebView(
            target,
            referer = parent,
            headers = mapOf(
                "Referer" to parent,
                "Origin" to mainUrl,
                "User-Agent" to ua,
                "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
            )
        ) { req ->
            val u=req.url.toString()
            if(manifestRegex.containsMatchIn(u)){
                stream=u
                streamHeaders=req.headers.toMap()
                Log.d("HintFilmIzle","KINESCOPE_MANIFEST="+u)
                true
            }else false
        }

        val final=stream ?: return false
        val finalHeaders=linkedMapOf(
            "Referer" to (streamHeaders["Referer"] ?: target),
            "User-Agent" to (streamHeaders["User-Agent"] ?: ua),
            "Accept" to (streamHeaders["Accept"] ?: "*/*")
        )
        streamHeaders["Origin"]?.takeIf{it.isNotBlank()}?.let{finalHeaders["Origin"]=it}
        streamHeaders["Accept-Language"]?.takeIf{it.isNotBlank()}?.let{finalHeaders["Accept-Language"]=it}

        callback(newExtractorLink(source=name,name="HintFilmİzle Kinescope",url=final,type=ExtractorLinkType.M3U8){
            referer=finalHeaders["Referer"] ?: target
            headers=finalHeaders
            quality=getQualityFromName(final)
        })
        true
    }.getOrElse { Log.e("HintFilmIzle","KINESCOPE_FAILED",it); false }

'''

text = text[:start] + replacement + text[end:]
PATH.write_text(text, encoding='utf-8')

WEBVIEW = Path('library/src/androidMain/kotlin/com/lagradost/cloudstream3/network/WebViewResolver.android.kt')
if WEBVIEW.exists():
    wv = WEBVIEW.read_text(encoding='utf-8')
    marker = '                    webView?.webViewClient = object : WebViewClient() {'
    if marker in wv and 'HINTFILMIZLE_BLOCKLIST_V14' not in wv:
        inject = '''                    // HINTFILMIZLE_BLOCKLIST_V14\n                    // Only enable this filter for a Kinescope embed opened by the\n                    // HintFilmIzle resolver. Other WebViewResolver users are untouched.\n                    val hintFilmKinescopeMode = url.contains("kinescopecdn.net", ignoreCase = true) &&\n                        url.contains("/embed/", ignoreCase = true)\n\n                    fun hintFilmAllowed(url: String): Boolean {\n                        val host = runCatching { android.net.Uri.parse(url).host?.lowercase() }.getOrNull() ?: return false\n                        return host == "hintfilmizle.com" ||\n                            host.endsWith(".hintfilmizle.com") ||\n                            host == "kinescopecdn.net" ||\n                            host.endsWith(".kinescopecdn.net")\n                    }\n\n                    fun hintFilmBlocked(url: String): Boolean {\n                        val host = runCatching { android.net.Uri.parse(url).host?.lowercase() }.getOrNull() ?: return false\n                        return host == "googletagmanager.com" || host.endsWith(".googletagmanager.com") ||\n                            host == "google-analytics.com" || host.endsWith(".google-analytics.com") ||\n                            host == "mc.yandex.ru" || host.endsWith(".mc.yandex.ru") ||\n                            host == "mc.yandex.com" || host.endsWith(".mc.yandex.com")\n                    }\n\n                    fun hintFilmEmptyResponse(): WebResourceResponse =\n                        WebResourceResponse("text/plain", "UTF-8", java.io.ByteArrayInputStream(ByteArray(0)))\n\n'''
        wv = wv.replace(marker, inject + marker, 1)
        needle = '''                        val webViewUrl = request.url.toString()\n                        Log.i(TAG, "Loading WebView URL: $webViewUrl")\n'''
        repl = '''                        val webViewUrl = request.url.toString()\n                        Log.i(TAG, "Loading WebView URL: $webViewUrl")\n\n                        if (hintFilmKinescopeMode && hintFilmBlocked(webViewUrl)) {\n                            Log.i(TAG, "HINTFILMIZLE_BLOCKED=$webViewUrl")\n                            return@runBlocking hintFilmEmptyResponse()\n                        }\n\n                        val scheme = runCatching { android.net.Uri.parse(webViewUrl).scheme?.lowercase() }.getOrNull()\n                        if (hintFilmKinescopeMode && (scheme == "http" || scheme == "https") && !hintFilmAllowed(webViewUrl)) {\n                            Log.i(TAG, "HINTFILMIZLE_BLOCKED_EXTERNAL=$webViewUrl")\n                            return@runBlocking hintFilmEmptyResponse()\n                        }\n'''
        if needle in wv:
            wv = wv.replace(needle, repl, 1)
        WEBVIEW.write_text(wv, encoding='utf-8')
        print('HintFilmIzle V14: native WebView + Kinescope-only allowlist + analytics blocklist')
    else:
        print('HintFilmIzle V14 WebView patch already present or marker not found')
else:
    print('CloudStream WebViewResolver.android.kt not found; skipping V14 network filter')
