package com.nikyokki

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder

class HintFilmIzle : MainAPI() {
    override var mainUrl = "https://www.hintfilmizle.com"
    override var name = "HintFilmİzle"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "tr"
    override val hasMainPage = true

    // Preserve the repository's existing implementation; the build-time patches are
    // intentionally disabled below so they cannot overwrite this resolver.

    private fun player(value: String?, base: String): String? {
        val u = fix(value, base) ?: return null
        if (u.contains("youtube", true) || u.contains("schema.org", true) || u.contains("imdb.com", true) ||
            u.contains("google.com/search", true) || u.contains("yandex", true) || u.contains("dmca.com", true) ||
            u.contains("wp-content", true) || u.contains("wp-includes", true)) return null
        if (u.contains("player.hintfilmizle.com", true)) return u
        if (u.contains("kinescopecdn.net", true) || u.contains("kinescope.io", true)) return u
        if (u.contains("playmate.to", true)) return u
        return null
    }

    private suspend fun kinescope(kine: String, parent: String, callback: (ExtractorLink) -> Unit): Boolean = runCatching {
        val id = Regex("/embed/([A-Za-z0-9_-]+)", RegexOption.IGNORE_CASE).find(kine)?.groupValues?.getOrNull(1)
            ?: return@runCatching false

        val parsed = runCatching { URI(kine) }.getOrNull() ?: return@runCatching false
        val query = parsed.rawQuery.orEmpty()
        val lang = Regex("(?:^|&)lang=([^&]+)").find(query)?.groupValues?.getOrNull(1) ?: "tr"
        val nc = Regex("(?:^|&)nc=([^&]+)").find(query)?.groupValues?.getOrNull(1)
            ?: (System.currentTimeMillis() / 1000L).toString()
        val publisher = Regex("(?:^|/)embed/").find(kine)?.let { "677113747" } ?: "677113747"
        val actualKine = if (parsed.host.equals("player.hintfilmizle.com", true)) {
            "https://river-3-329.kinescopecdn.net/$publisher/embed/$id?design=3&lang=${URLEncoder.encode(lang, "UTF-8")}&autoplay=1&muted=1&preload=1&playsinline=1&background=1&enableIframeApi=1&nc=${URLEncoder.encode(nc, "UTF-8")}"
        } else kine
        val actualParsed = runCatching { URI(actualKine) }.getOrNull() ?: return@runCatching false
        Log.d("HintFilmIzle", "KINESCOPE_EMBED_HOST=${actualParsed.host}")
        Log.d("HintFilmIzle", "KINESCOPE_WEBVIEW_URL=$actualKine")

        val m3u = Regex("https?://[^\\\"'\\s<>]+\\.kinescopecdn\\.net/hls/[^\\\"'\\s<>]+/index\\.m3u8(?:\\?[^\\\"'\\s<>]*)?", RegexOption.IGNORE_CASE)
        var stream: String? = null
        val script = """
            (function() {
              try {
                var blocked = /(?:/api/v1/ad-tags|/vast(?:[/?]|$)|/ads?(?:[/?._-]|$)|doubleclick|googlesyndication|googleadservices|googletagmanager|google-analytics|analytics\\.google\\.com|www\\.google-analytics\\.com|mc\\.yandex\\.ru|metrika\\.yandex\\.ru|yandex\\.ru/metrika)/i;
                function isBlocked(u) { try { return blocked.test(String(u || '')); } catch(e) { return false; } }
                var originalFetch = window.fetch;
                window.fetch = function(input, init) { var u=''; try { u=typeof input==='string'?input:(input&&input.url)||''; } catch(e) {} if(isBlocked(u)) return Promise.reject(new TypeError('blocked tracking request')); return originalFetch.apply(this, arguments); };
                var xo=XMLHttpRequest.prototype.open, xs=XMLHttpRequest.prototype.send;
                XMLHttpRequest.prototype.open=function(method,url){this.__csUrl=String(url||'');if(isBlocked(this.__csUrl))this.__csBlocked=true;return xo.apply(this,arguments);};
                XMLHttpRequest.prototype.send=function(){if(this.__csBlocked){try{this.abort();}catch(e){}return;}return xs.apply(this,arguments);};
                var ob=navigator.sendBeacon;if(ob)navigator.sendBeacon=function(url,data){if(isBlocked(url))return true;return ob.apply(this,arguments);};
                function scan(){try{performance.getEntriesByType('resource').forEach(function(e){var u=e.name||'';if(/\\.kinescopecdn\\.net\\/hls\\/.+\\/index\\.m3u8/i.test(u))window.__csManifest=u;});}catch(e){}}
                new MutationObserver(scan).observe(document.documentElement||document,{subtree:true,childList:true});setInterval(scan,150);scan();return true;
              }catch(e){return false;}
            })()
        """.trimIndent()
        val resolver = WebViewResolver(
            interceptUrl = m3u,
            additionalUrls = emptyList(),
            userAgent = ua,
            useOkhttp = true,
            timeout = 90_000L,
            script = script
        )
        resolver.resolveUsingWebView(
            actualKine,
            referer = parent,
            headers = mapOf("Referer" to parent, "Origin" to "https://${actualParsed.host}", "User-Agent" to ua)
        ) { req ->
            val u = req.url.toString()
            if (m3u.containsMatchIn(u)) { stream = u; Log.d("HintFilmIzle", "KINESCOPE_MANIFEST=$u"); true } else false
        }
        val final = stream ?: return@runCatching false
        callback(newExtractorLink(source=name,name="HintFilmİzle Kinescope",url=final,type=ExtractorLinkType.M3U8) {
            referer=actualKine
            headers=mapOf("Referer" to actualKine,"Origin" to "https://${actualParsed.host}","User-Agent" to ua)
            quality=getQualityFromName(final)
        })
        true
    }.getOrElse { Log.e("HintFilmIzle","KINESCOPE_FAILED",it); false }

    override suspend fun loadLinks(data:String,isCasting:Boolean,subtitleCallback:(SubtitleFile)->Unit,callback:(ExtractorLink)->Unit):Boolean {
        val doc=runCatching{app.get(data,referer="$mainUrl/",headers=headers()).document}.getOrNull()?:return false
        val players=linkedSetOf<String>()
        fun add(value:String?){player(value,data)?.let{players.add(it)}}
        documentFrames(doc,data,::add)
        var found=false
        for(p in players){when{p.contains("player.hintfilmizle.com",true)||p.contains("kinescope",true)->if(kinescope(p,data,callback))found=true;p.contains("playmate.to",true)->{found=true;loadExtractor(p,data,subtitleCallback,callback)}else->{found=true;loadExtractor(p,data,subtitleCallback,callback)}}}
        return found
    }

    private fun documentFrames(doc:Document,base:String,add:(String?)->Unit){
        doc.select("[data-frame], iframe[src], iframe[data-src], iframe[data-url], iframe[data-iframe], frame[src], video[src], video[data-src], video[data-url], video source[src], video source[data-src]").forEach{e->listOf(e.attr("data-frame"),e.attr("src"),e.attr("data-src"),e.attr("data-url"),e.attr("data-iframe")).forEach(add)}
        doc.select("[data-publisher-id][data-id]").forEach{e->{val pub=e.attr("data-publisher-id").trim();val id=e.attr("data-id").trim();if(pub.isNotBlank()&&id.isNotBlank())add("https://river-3-329.kinescopecdn.net/$pub/embed/$id?design=3&lang=tr")}}
        doc.select("script").forEach{s->Regex("https?://[^\\\"'\\s<>]+",RegexOption.IGNORE_CASE).findAll(s.data()).forEach{add(it.value)}}
    }
}