from pathlib import Path

PATH = Path('HintFilmIzle/src/main/kotlin/com/nikyokki/HintFilmIzlePlugin.kt')
text = PATH.read_text(encoding='utf-8')

old = '''        var response = runCatching { app.get(url, referer = "$mainUrl/", headers = headers()) }.getOrNull()
            ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)
'''
new = '''        var response = runCatching { app.get(url, referer = "$mainUrl/", headers = headers()) }.getOrNull()
        if (response == null) return newHomePageResponse(request.name, emptyList(), hasNext = false)
'''
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

        // Kinescope must be allowed to complete both the signed ad-tags request and
        // the signed embed API request. Cancelling either request breaks the player.
        val script = """
            (function() {
              try {
                if (window.__csHintKineV10) return true;
                window.__csHintKineV10 = true;
                var KEY = 'RySdvcyu5iTUxn97vn4HwoniwgxaCynA';

                // CloudStream has no real pointer interaction. Remove the site's
                // transparent ad/click layers instead of trying to click through them.
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

                // Observe responses without blocking/cancelling them.
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

                // Recover API/M3U8 URLs if the player requested them before our hooks ran.
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

        // CRITICAL: API is deliberately NOT in interceptUrl. WebViewResolver cancels
        // intercepted requests; Kinescope needs the API request to finish normally.
        val resolver = WebViewResolver(
            interceptUrl = Regex("m3u8", RegexOption.IGNORE_CASE),
            additionalUrls = emptyList(),
            userAgent = ua,
            useOkhttp = true,
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
print('HintFilmIzle source patched: V10 - Kinescope API is never intercepted/cancelled; only final M3U8 is intercepted')
