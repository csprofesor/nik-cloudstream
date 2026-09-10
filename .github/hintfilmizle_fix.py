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
        val apiRegex = Regex(
            "https?://[^\"'\\s<>]+/api/v1/embed/[A-Za-z0-9_-]+(?:\\?[^\"'\\s<>]*)?",
            RegexOption.IGNORE_CASE
        )
        var stream: String? = null
        var apiUrl: String? = null
        var streamHeaders: Map<String, String> = emptyMap()

        val script = """
            (function() {
              try {
                if (window.__csHintKineV6) return true;
                window.__csHintKineV6 = true;
                var KEY = 'RySdvcyu5iTUxn97vn4HwoniwgxaCynA';
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
                      for (var i = 0; i < value.length; i++) { var a = findManifest(value[i], seen); if (a) return a; }
                    } else {
                      for (var k in value) { try { var b = findManifest(value[k], seen); if (b) return b; } catch (_) {} }
                    }
                  } catch (_) {}
                  return null;
                }
                function trigger(url) {
                  try {
                    if (!url || window.__csHintManifest === url) return;
                    window.__csHintManifest = url;
                    var v = document.createElement('video');
                    v.muted = true;
                    v.setAttribute('muted', '');
                    v.setAttribute('playsinline', '');
                    v.preload = 'metadata';
                    v.src = url;
                    (document.documentElement || document.body).appendChild(v);
                    v.load();
                  } catch (_) {}
                }
                function inspect(text) {
                  try {
                    var direct = findManifest(text, []);
                    if (direct) { trigger(direct); return; }
                    var parsed = JSON.parse(text);
                    if (!parsed || typeof parsed.p !== 'string') return;
                    var encoded = parsed.p.split('').reverse().join('');
                    var binary = atob(encoded);
                    var out = new Uint8Array(binary.length);
                    for (var i = 0; i < binary.length; i++) out[i] = binary.charCodeAt(i) ^ KEY.charCodeAt(i % KEY.length);
                    var decoded = new TextDecoder('utf-8').decode(out);
                    var manifest = findManifest(JSON.parse(decoded), []);
                    if (manifest) trigger(manifest);
                  } catch (_) {}
                }
                var ofetch = window.fetch;
                if (ofetch) {
                  window.fetch = function() {
                    var args = arguments;
                    try {
                      var u = typeof args[0] === 'string' ? args[0] : (args[0] && args[0].url);
                      if (/\\.m3u8(?:\\?|$)/i.test(String(u || ''))) trigger(String(u));
                    } catch (_) {}
                    return ofetch.apply(this, args).then(function(r) {
                      try { r.clone().text().then(inspect).catch(function(){}); } catch (_) {}
                      return r;
                    });
                  };
                }
                var oopen = XMLHttpRequest.prototype.open;
                var osend = XMLHttpRequest.prototype.send;
                XMLHttpRequest.prototype.open = function(method, url) {
                  this.__csUrl = String(url || '');
                  return oopen.apply(this, arguments);
                };
                XMLHttpRequest.prototype.send = function() {
                  try {
                    this.addEventListener('load', function() {
                      var u = String(this.__csUrl || '');
                      if (/\\.m3u8(?:\\?|$)/i.test(u)) trigger(u);
                      if (u.indexOf('/api/v1/embed/') >= 0) inspect(this.responseText || '');
                    });
                  } catch (_) {}
                  return osend.apply(this, arguments);
                };
                return true;
              } catch (_) { return false; }
            })()
        """.trimIndent()

        val resolver = WebViewResolver(
            interceptUrl = Regex("(?:m3u8|/api/v1/embed/)", RegexOption.IGNORE_CASE),
            additionalUrls = emptyList(),
            userAgent = ua,
            useOkhttp = true,
            timeout = 45_000L,
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
            val u = req.url.toString()
            when {
                manifestRegex.containsMatchIn(u) -> {
                    stream = u
                    streamHeaders = req.headers.toMap()
                    Log.d("HintFilmIzle", "KINESCOPE_MANIFEST=" + u)
                    true
                }
                apiRegex.containsMatchIn(u) -> {
                    apiUrl = u
                    Log.d("HintFilmIzle", "KINESCOPE_API=" + u)
                    true
                }
                else -> false
            }
        }

        if (stream.isNullOrBlank() && !apiUrl.isNullOrBlank()) {
            val apiBody = runCatching {
                app.get(
                    apiUrl!!,
                    referer = target,
                    headers = mapOf(
                        "Referer" to target,
                        "Origin" to "https://river-3-329.kinescopecdn.net",
                        "User-Agent" to ua,
                        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
                    )
                ).text
            }.getOrNull()
            stream = apiBody?.let(::decodeKinescopeApi)
            if (!stream.isNullOrBlank()) Log.d("HintFilmIzle", "KINESCOPE_API_DECODED=" + stream)
        }

        val final = stream ?: return false
        val finalHeaders = linkedMapOf(
            "Referer" to (streamHeaders["Referer"] ?: target),
            "User-Agent" to (streamHeaders["User-Agent"] ?: ua),
            "Accept" to (streamHeaders["Accept"] ?: "*/*")
        )
        streamHeaders["Origin"]?.takeIf { it.isNotBlank() }?.let { finalHeaders["Origin"] = it }
        streamHeaders["Accept-Language"]?.takeIf { it.isNotBlank() }?.let { finalHeaders["Accept-Language"] = it }

        callback(newExtractorLink(source = name, name = "HintFilmİzle Kinescope", url = final, type = ExtractorLinkType.M3U8) {
            referer = finalHeaders["Referer"] ?: target
            headers = finalHeaders
            quality = getQualityFromName(final)
        })
        true
    }.getOrElse { Log.e("HintFilmIzle", "KINESCOPE_FAILED", it); false }

'''

text = text[:start] + replacement + text[end:]
PATH.write_text(text, encoding='utf-8')
print('HintFilmIzle source patched: category-safe main page + suspend-safe Kinescope API interception/decoding')
