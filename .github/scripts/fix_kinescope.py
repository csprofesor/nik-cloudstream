from pathlib import Path

p = Path('HintFilmIzle/src/main/kotlin/com/nikyokki/HintFilmIzlePlugin.kt')
s = p.read_text()
start = s.index('    private suspend fun loadKinescope(')
end = s.index('    override suspend fun loadLinks(', start)

new = r'''    private suspend fun loadKinescope(
        iframeUrl: String,
        parentUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean = runCatching {
        val userAgent = browserHeaders()["User-Agent"].orEmpty()
        val videoId = Regex("""/embed/([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE)
            .find(iframeUrl)?.groupValues?.getOrNull(1) ?: return false

        val livePlayerUrl = buildString {
            append("https://river-3-329.kinescopecdn.net/677113747/embed/")
            append(videoId)
            append("?design=3&lang=")
            append(URLEncoder.encode(lang.ifBlank { "tr" }, "UTF-8"))
            append("&autoplay=1&muted=1&preload=1&playsinline=1&background=1&enableIframeApi=1&nc=")
            append(System.currentTimeMillis() / 1000L)
        }

        val manifestRegex = Regex(
            """https?://[^\"'\\s]+\.kinescopecdn\.net/hls/[^\"'\\s]+/index\.m3u8(?:\?[^\"'\\s]*)?""",
            RegexOption.IGNORE_CASE
        )

        var capturedManifest: String? = null
        var capturedHeaders: Map<String, String> = emptyMap()

        val script = """
            (function() {
                try {
                    if (window.__csKinescopeFixV3) return true;
                    window.__csKinescopeFixV3 = true;
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
                            if (!url || window.__csKinescopeManifest === url) return;
                            window.__csKinescopeManifest = url;
                            var video = document.createElement('video');
                            video.muted = true;
                            video.preload = 'auto';
                            video.setAttribute('playsinline', '');
                            video.src = url;
                            document.documentElement.appendChild(video);
                            video.load();
                        } catch (_) {}
                    }
                    function inspectText(text) {
                        try {
                            var direct = findManifest(text, []);
                            if (direct) trigger(direct);
                            var parsed = JSON.parse(text);
                            var nested = findManifest(parsed, []);
                            if (nested) trigger(nested);
                        } catch (_) {}
                    }
                    var nativeFetch = window.fetch;
                    if (nativeFetch) {
                        window.fetch = function() {
                            return nativeFetch.apply(this, arguments).then(function(response) {
                                try { response.clone().text().then(inspectText).catch(function(){}); } catch (_) {}
                                return response;
                            });
                        };
                    }
                    var nativeOpen = XMLHttpRequest.prototype.open;
                    var nativeSend = XMLHttpRequest.prototype.send;
                    XMLHttpRequest.prototype.open = function(method, url) {
                        this.__csUrl = url;
                        return nativeOpen.apply(this, arguments);
                    };
                    XMLHttpRequest.prototype.send = function() {
                        try { this.addEventListener('load', function() { if (String(this.__csUrl || '').indexOf('/api/v1/embed/') >= 0) inspectText(this.responseText || ''); }); } catch (_) {}
                        return nativeSend.apply(this, arguments);
                    };
                    function play(root) {
                        try {
                            if (!root || !root.querySelectorAll) return;
                            root.querySelectorAll('video,button,[role="button"],[aria-label],[title]').forEach(function(el) {
                                try {
                                    if (el.tagName && el.tagName.toLowerCase() === 'video') {
                                        el.muted = true; el.setAttribute('muted',''); el.setAttribute('playsinline','');
                                        var p = el.play(); if (p && p.catch) p.catch(function(){});
                                    }
                                    var t = ((el.getAttribute('aria-label') || '') + ' ' + (el.getAttribute('title') || '') + ' ' + (typeof el.className === 'string' ? el.className : '') + ' ' + (el.id || '')).toLowerCase();
                                    if (t.indexOf('play') >= 0 || t.indexOf('oynat') >= 0) el.click();
                                } catch (_) {}
                            });
                            root.querySelectorAll('*').forEach(function(el) { if (el.shadowRoot) play(el.shadowRoot); });
                        } catch (_) {}
                    }
                    play(document);
                    var observer = new MutationObserver(function(){ play(document); });
                    observer.observe(document.documentElement || document, {childList:true,subtree:true});
                    var timer = setInterval(function(){ play(document); }, 700);
                    setTimeout(function(){ clearInterval(timer); try { observer.disconnect(); } catch (_) {} }, 70000);
                    return true;
                } catch (_) { return false; }
            })()
        """.trimIndent()

        val resolver = WebViewResolver(
            interceptUrl = manifestRegex,
            additionalUrls = listOf(Regex("""https?://[^\"'\\s]*kinescopecdn\.net/.*""", RegexOption.IGNORE_CASE)),
            userAgent = userAgent,
            useOkhttp = false,
            timeout = 90_000L,
            script = script
        )

        val requestHeaders = mapOf(
            "Referer" to parentUrl,
            "Origin" to "https://www.hintfilmizle.com",
            "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
            "User-Agent" to userAgent
        )

        resolver.resolveUsingWebView(url = livePlayerUrl, referer = parentUrl, headers = requestHeaders) { request ->
            val requestUrl = request.url.toString()
            Log.d("HintFilmIzle", "KINESCOPE_REQUEST=" + requestUrl)
            if (manifestRegex.containsMatchIn(requestUrl)) {
                capturedManifest = requestUrl
                capturedHeaders = request.headers.toMap()
                true
            } else false
        }

        val manifestUrl = capturedManifest ?: return false
        val finalHeaders = linkedMapOf(
            "Referer" to (capturedHeaders["Referer"] ?: livePlayerUrl),
            "User-Agent" to (capturedHeaders["User-Agent"] ?: userAgent),
            "Accept" to (capturedHeaders["Accept"] ?: "*/*")
        )
        capturedHeaders["Origin"]?.takeIf { it.isNotBlank() }?.let { finalHeaders["Origin"] = it }
        capturedHeaders["Accept-Language"]?.takeIf { it.isNotBlank() }?.let { finalHeaders["Accept-Language"] = it }

        callback(newExtractorLink(source = name, name = "HintFilmİzle Kinescope", url = manifestUrl, type = ExtractorLinkType.M3U8) {
            referer = finalHeaders["Referer"] ?: livePlayerUrl
            headers = finalHeaders
            quality = getQualityFromName(manifestUrl)
        })
        true
    }.getOrElse {
        Log.e("HintFilmIzle", "KINESCOPE_FAILED", it)
        false
    }

'''
s = s[:start] + new + s[end:]
p.write_text(s)
