from pathlib import Path

path = Path("HintFilmIzle/src/main/kotlin/com/nikyokki/HintFilmIzlePlugin.kt")
s = path.read_text(encoding="utf-8-sig")

old = '''            response = runCatching { app.get(fallbackUrl, referer = "$mainUrl/", headers = headers()) }.getOrNull()
            if (response != null) { doc = response.document; r = results(doc, slug) }
'''
new = '''            val fallbackResponse = runCatching { app.get(fallbackUrl, referer = "$mainUrl/", headers = headers()) }.getOrNull()
            if (fallbackResponse != null) { doc = fallbackResponse.document; r = results(doc, slug) }
'''
if old in s:
    s = s.replace(old, new, 1)

old_import = 'import android.util.Log\n'
if old_import in s and 'import android.util.Base64' not in s:
    s = s.replace(old_import, old_import + 'import android.util.Base64\n', 1)

start = s.index('    private suspend fun kinescope(')
end = s.index('\n    override suspend fun loadLinks', start)

new_kinescope = r'''    private suspend fun kinescope(kine: String, parent: String, callback: (ExtractorLink) -> Unit): Boolean = runCatching {
        val id = Regex("/embed/([A-Za-z0-9_-]+)", RegexOption.IGNORE_CASE).find(kine)?.groupValues?.getOrNull(1) ?: return@runCatching false
        val parsed = runCatching { URI(kine) }.getOrNull() ?: return@runCatching false
        val query = parsed.rawQuery.orEmpty()
        val lang = Regex("(?:^|&)lang=([^&]+)").find(query)?.groupValues?.getOrNull(1) ?: "tr"
        val nc = Regex("(?:^|&)nc=([^&]+)").find(query)?.groupValues?.getOrNull(1) ?: (System.currentTimeMillis() / 1000L).toString()
        val actualKine = if (parsed.host.equals("player.hintfilmizle.com", true)) {
            "https://river-3-329.kinescopecdn.net/677113747/embed/$id?design=3&lang=${URLEncoder.encode(lang, "UTF-8")}&autoplay=1&muted=1&preload=1&playsinline=1&background=1&enableIframeApi=1&nc=${URLEncoder.encode(nc, "UTF-8")}"
        } else kine
        val actualParsed = runCatching { URI(actualKine) }.getOrNull() ?: return@runCatching false
        Log.d("HintFilmIzle", "KINESCOPE_EMBED_HOST=${actualParsed.host}")
        Log.d("HintFilmIzle", "KINESCOPE_WEBVIEW_URL=$actualKine")

        // Kinescope embed.js APIDecoder v1: reverse -> Base64 decode -> XOR with the decoder key -> UTF-8.
        // This is the native equivalent of the decoder used by the player JS.
        fun decodeApiPayload(body: String): String? {
            val encoded = Regex("\\\"p\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").find(body)?.groupValues?.getOrNull(1) ?: return null
            return runCatching {
                val reversed = encoded.reversed()
                val raw = Base64.decode(reversed, Base64.DEFAULT)
                val key = "RySdvcyu5iTUxn97vn4HwoniwgxaCynA".toByteArray(Charsets.UTF_8)
                val out = ByteArray(raw.size)
                for (i in raw.indices) out[i] = (raw[i].toInt() xor key[i % key.size].toInt()).toByte()
                String(out, Charsets.UTF_8)
            }.getOrNull()
        }

        val apiUrl = "${actualParsed.scheme}://${actualParsed.host}/api/v1/embed/$id?design=3&lang=${URLEncoder.encode(lang, "UTF-8")}&autoplay=1&muted=1&preload=1&playsinline=1&background=1&enableIframeApi=1&nc=${URLEncoder.encode(nc, "UTF-8")}"
        Log.d("HintFilmIzle", "KINESCOPE_API_URL=$apiUrl")
        val apiBody = runCatching { app.get(apiUrl, referer = actualKine, headers = headers()).text }.getOrNull()
        val decoded = apiBody?.let(::decodeApiPayload)
        val decodedUrl = decoded?.let {
            Regex("https?://[^\\\"'\\s<>]+\\.kinescopecdn\\.net/hls/[^\\\"'\\s<>]+\\.m3u8(?:\\?[^\\\"'\\s<>]*)?", RegexOption.IGNORE_CASE).find(it)?.value
        }
        if (decodedUrl != null) {
            Log.d("HintFilmIzle", "KINESCOPE_API_MANIFEST=$decodedUrl")
            callback(newExtractorLink(source = name, name = "HintFilmİzle Kinescope", url = decodedUrl, type = ExtractorLinkType.M3U8) {
                referer = actualKine
                headers = mapOf("Referer" to actualKine, "Origin" to "https://${actualParsed.host}", "User-Agent" to ua)
                quality = getQualityFromName(decodedUrl)
            })
            return@runCatching true
        }
        Log.d("HintFilmIzle", "KINESCOPE_API_DECODE_FAILED")

        // Keep the WebView path as a fallback for future Kinescope API changes.
        val m3u = Regex("https?://[^\\\"'\\s<>]+\\.kinescopecdn\\.net/hls/[^\\\"'\\s<>]+\\.m3u8(?:\\?[^\\\"'\\s<>]*)?", RegexOption.IGNORE_CASE)
        var stream: String? = null
        val script = """
            (function(){try{
              var blocked=/(?:\\/api\\/v1\\/ad-tags|\\/vast(?:[/?]|$)|\\/ads?(?:[/?._-]|$)|doubleclick|googlesyndication|googleadservices|googletagmanager|google-analytics|analytics\\.google\\.com|www\\.google-analytics\\.com|mc\\.yandex\\.ru|metrika\\.yandex\\.ru|yandex\\.ru\\/metrika)/i;
              function isBlocked(u){try{return blocked.test(String(u||''));}catch(e){return false;}}
              var f=window.fetch;window.fetch=function(input,init){var u='';try{u=typeof input==='string'?input:(input&&input.url)||'';}catch(e){}if(isBlocked(u))return Promise.reject(new TypeError('blocked tracking request'));return f.apply(this,arguments);};
              var xo=XMLHttpRequest.prototype.open,xs=XMLHttpRequest.prototype.send;XMLHttpRequest.prototype.open=function(method,url){this.__csUrl=String(url||'');if(isBlocked(this.__csUrl))this.__csBlocked=true;return xo.apply(this,arguments);};XMLHttpRequest.prototype.send=function(){if(this.__csBlocked){try{this.abort();}catch(e){}return;}return xs.apply(this,arguments);};
              var ob=navigator.sendBeacon;if(ob)navigator.sendBeacon=function(url,data){if(isBlocked(url))return true;return ob.apply(this,arguments);};
              function scan(){try{performance.getEntriesByType('resource').forEach(function(e){var u=e.name||'';if(/\\.kinescopecdn\\.net\\/hls\\/.+\\.m3u8/i.test(u))window.__csManifest=u;});}catch(e){}}
              new MutationObserver(scan).observe(document.documentElement||document,{subtree:true,childList:true});setInterval(scan,150);scan();return true;
            }catch(e){return false;}})()
        """.trimIndent()
        val intercept = Regex("\\.kinescopecdn\\.net/hls/.+\\.m3u8(?:\\?.*)?$", RegexOption.IGNORE_CASE)
        val resolver = WebViewResolver(interceptUrl = intercept, additionalUrls = emptyList(), userAgent = ua, useOkhttp = false, timeout = 25_000L, script = script)
        resolver.resolveUsingWebView(actualKine, referer = parent, headers = mapOf("Referer" to parent, "Origin" to "https://${actualParsed.host}", "User-Agent" to ua)) { req ->
            val u = req.url.toString()
            if (m3u.containsMatchIn(u)) {
                stream = u
                Log.d("HintFilmIzle", "KINESCOPE_MANIFEST=$u")
                true
            } else false
        }
        val final = stream ?: return@runCatching false
        callback(newExtractorLink(source = name, name = "HintFilmİzle Kinescope", url = final, type = ExtractorLinkType.M3U8) {
            referer = actualKine
            headers = mapOf("Referer" to actualKine, "Origin" to "https://${actualParsed.host}", "User-Agent" to ua)
            quality = getQualityFromName(final)
        })
        true
    }.getOrElse {
        Log.e("HintFilmIzle", "KINESCOPE_FAILED", it)
        false
    }
'''
s = s[:start] + new_kinescope + s[end:]
path.write_text(s, encoding="utf-8")
print("HintFilmIzle Kinescope native API decoder patch applied")