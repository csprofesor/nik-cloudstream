package com.nikyokki

// Kinescope WebView resolver: only the real HLS manifest terminates the resolver.
// Analytics/ad requests are blocked in-page without using interceptUrl.

import android.util.Log
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

class HintFilmIzle : MainAPI() {
    override var mainUrl = "https://www.hintfilmizle.com"
    override var name = "HintFilmİzle"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val ua = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36"
    private fun headers() = mapOf("User-Agent" to ua, "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7")

    override val mainPage = mainPageOf(
        "$mainUrl/tur/aile-filmleri" to "Aile", "$mainUrl/tur/aksiyon-filmleri" to "Aksiyon",
        "$mainUrl/tur/animasyon-filmleri" to "Animasyon", "$mainUrl/tur/bilim-kurgu-filmleri" to "Bilim Kurgu",
        "$mainUrl/tur/dram-filmleri" to "Dram", "$mainUrl/tur/fantastik-filmleri" to "Fantastik",
        "$mainUrl/tur/komedi-filmleri" to "Komedi", "$mainUrl/tur/korku-filmleri" to "Korku",
        "$mainUrl/tur/macera-filmleri" to "Macera", "$mainUrl/tur/romantik-filmleri" to "Romantik",
        "$mainUrl/tur/savas-filmleri" to "Savaş", "$mainUrl/tur/suc-filmleri" to "Suç",
        "$mainUrl/tur/tarih-filmleri" to "Tarih", "$mainUrl/tur/gerilim-filmleri" to "Gerilim",
        "$mainUrl/netflix-izle" to "Netflix"
    )

    private fun fix(value: String?, base: String = mainUrl): String? {
        val raw = value?.replace("\\/", "/")?.replace("\\u0026", "&")?.replace("&amp;", "&")?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            when {
                raw.startsWith("//") -> "https:$raw"
                raw.startsWith("http://", true) || raw.startsWith("https://", true) -> raw
                raw.startsWith("/") -> mainUrl + raw
                else -> URI(base).resolve(raw).toString()
            }
        }.getOrNull()?.takeIf { it.startsWith("http", true) }
    }

    private fun Element.poster(): String? {
        val attrs = listOf("data-src", "data-lazy-src", "data-original", "data-image", "data-poster", "data-thumb", "src")
        select("img,picture source").forEach { img ->
            attrs.firstNotNullOfOrNull {
                fix(img.attr(it))?.takeIf { u -> !u.startsWith("data:") && !u.contains("placeholder", true) }
            }?.let { return it }
        }
        return null
    }

    private fun cleanTitle(value: String?): String? = value?.trim()
        ?.replace(Regex("\\s+"), " ")
        ?.replace(Regex("\\s+(Türkçe\\s+(Altyazı|Dublaj)|izle)\\s*$", RegexOption.IGNORE_CASE), "")
        ?.trim()
        ?.takeIf { it.isNotBlank() && !it.equals("Giriş yap", true) && !it.equals("Oturum Aç", true) }

    private fun titleOf(card: Element): String? = sequenceOf(
        card.selectFirst("h1")?.text(), card.selectFirst(".film-title")?.text(), card.selectFirst(".movie-title")?.text(),
        card.selectFirst(".entry-title")?.text(), card.selectFirst(".single-title")?.text(), card.selectFirst(".post-title")?.text(),
        card.selectFirst(".card-title")?.text(), card.selectFirst("h2")?.text(), card.selectFirst("h3")?.text(),
        card.selectFirst(".title")?.text(), card.selectFirst(".name")?.text(), card.selectFirst("img")?.attr("alt"), card.attr("title")
    ).mapNotNull(::cleanTitle).firstOrNull()

    private fun detailTitle(doc: org.jsoup.nodes.Document, url: String): String? {
        val target = url.substringBefore("?").trimEnd('/')
        val exactLink = doc.select("a[href]").firstOrNull { a ->
            val href = fix(a.attr("href"), url)?.substringBefore("?")?.trimEnd('/')
            href.equals(target, true) && cleanTitle(a.text()) != null
        }
        return sequenceOf(exactLink?.text(), doc.selectFirst("main h1")?.text(), doc.selectFirst("article h1")?.text(), doc.selectFirst("h1")?.text(), doc.selectFirst(".film-title")?.text(), doc.selectFirst(".movie-title")?.text(), doc.selectFirst(".entry-title")?.text()).mapNotNull(::cleanTitle).firstOrNull()
            ?: url.substringBefore("?").substringAfterLast('/').replace(Regex("[-_]+"), " ").replaceFirstChar { it.uppercase() }
    }

    private fun rating(card: Element): String? = Regex("(?<!\\d)(?:10(?:[.,]0+)?|[1-9](?:[.,]\\d{1,3})?)(?!\\d)").findAll(card.text()).mapNotNull { it.value.replace(',', '.').toFloatOrNull() }.firstOrNull { it in 0f..10f }?.toString()

    private fun Element.toResult(card: Element = this): SearchResponse? {
        val href = fix(attr("href")) ?: return null
        val path = href.removePrefix(mainUrl).substringBefore("?").trimEnd('/')
        if (!href.startsWith(mainUrl, true) || (!path.startsWith("/film/") && !path.startsWith("/dizi/"))) return null
        val title = titleOf(card) ?: path.substringAfterLast('/').replace(Regex("[-_]+"), " ")
        return if (path.startsWith("/dizi/")) newTvSeriesSearchResponse(title, href, TvType.TvSeries) { posterUrl = card.poster(); score = Score.from10(rating(card)) }
        else newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = card.poster(); score = Score.from10(rating(card)) }
    }

    private fun cardFor(anchor: Element): Element = anchor.parents().firstOrNull { p -> p.select("img").isNotEmpty() && p.select("a[href*='/film/'],a[href*='/dizi/']").size <= 4 } ?: anchor
    private fun categorySlug(data: String): String? = data.substringBefore("?").trimEnd('/').substringAfter("/tur/", "").takeIf { it.isNotBlank() }
    private fun categoryMatches(card: Element, slug: String): Boolean = card.select("a[href*='/tur/']").any { a -> val href = fix(a.attr("href")) ?: return@any false; href.substringBefore("?").trimEnd('/').equals("$mainUrl/tur/$slug", ignoreCase = true) }
    private fun results(doc: org.jsoup.nodes.Document, slug: String? = null): List<SearchResponse> = doc.select("a[href*='/film/'],a[href*='/dizi/']").mapNotNull { a -> val card = cardFor(a); if (slug != null && !categoryMatches(card, slug)) null else a.toResult(card) }.distinctBy { it.url }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = request.data.substringBefore("?").trimEnd('/'); val q = request.data.substringAfter("?", "").takeIf { it.isNotBlank() }
        val url = if (page <= 1) request.data else base + "/page/$page/" + if (q != null) "?$q" else ""
        val slug = categorySlug(request.data)
        var response = runCatching { app.get(url, referer = "$mainUrl/", headers = headers()) }.getOrNull() ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)
        var doc = response.document; var r = results(doc, slug)
        if (slug != null && r.isEmpty()) {
            val fallbackUrl = if (page <= 1) "$mainUrl/film?order=DESC&orderby=date" else "$mainUrl/film/page/$page/?order=DESC&orderby=date"
            val fallbackResponse = runCatching { app.get(fallbackUrl, referer = "$mainUrl/", headers = headers()) }.getOrNull()
            if (fallbackResponse != null) { doc = fallbackResponse.document; r = results(doc, slug) }
        }
        return newHomePageResponse(request.name, r, hasNext = r.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        listOf("$mainUrl/film?search=$q", "$mainUrl/film?s=$q", "$mainUrl/?s=$q", "$mainUrl/?search=$q", "$mainUrl/arama?q=$q").forEach { url -> val r = runCatching { results(app.get(url, referer = "$mainUrl/", headers = headers()).document) }.getOrDefault(emptyList()); if (r.isNotEmpty()) return r }
        return emptyList()
    }
    override suspend fun quickSearch(query: String) = search(query)

    private fun body(doc: org.jsoup.nodes.Document) = doc.body()?.text()?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
    private fun label(text: String, name: String, next: String): String? = Regex("${Regex.escape(name)}\\s*[:\\-]?\\s*(.*?)\\s*(?=${Regex.escape(next)}|$)", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(text)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
    private fun genres(doc: org.jsoup.nodes.Document, text: String): List<String> { val dom = doc.select("a[href*='/tur/'],.genres a,.genre a,.categories a").map { it.text().trim() }.filter { it.isNotBlank() && it.contains("Film", true) }.distinct(); if (dom.isNotEmpty()) return dom; return label(text, "Türü", "Bu Film özeti").orEmpty().split(",").map { it.trim() }.filter { it.isNotBlank() && it.contains("Film", true) } }
    private fun actors(doc: org.jsoup.nodes.Document): List<Actor> { val links = doc.select("a[href*='/oyuncular/'],a[href*='/oyuncu/'],a[href*='/actor/'],a[href*='/cast/']").map { cleanTitle(it.text()) }.filterNotNull().filter { it.length < 100 }.distinct(); if (links.isNotEmpty()) return links.map(::Actor); val heading = doc.select("h1,h2,h3,h4,h5,h6").firstOrNull { it.text().contains("Öne Çıkan Oyuncular", true) }; val container = heading?.parents()?.firstOrNull { p -> val count = p.select("a").size; count in 1..20 && p.text().contains("Yönetmen", true) }; return container?.select("a")?.map { cleanTitle(it.text()) }?.filterNotNull()?.filter { it.length < 100 }?.distinct()?.map(::Actor).orEmpty() }
    private fun plot(doc: org.jsoup.nodes.Document, text: String): String? { val section = Regex("GENEL BAKIŞ\\s+(.*?)(?=BU FİLM ÖZETİ|HATA BİLDİR|FRAGMAN|ÖNE ÇIKAN OYUNCULAR|YÖNETMEN|ÜLKE\\s)", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(text)?.groupValues?.getOrNull(1) ?: return null; val cleaned = section.replace(Regex("^Türü\\s*:\\s*.*?(?=ÇEVİRİ\\s*:)", RegexOption.IGNORE_CASE), "").replace(Regex("^ÇEVİRİ\\s*:\\s*.*?(?=[A-ZÇĞİÖŞÜ][a-zçğıöşü])", RegexOption.IGNORE_CASE), "").replace(Regex("\\s+"), " ").trim(); return cleaned.takeIf { it.length >= 20 } }

    override suspend fun load(url: String): LoadResponse? {
        val doc = runCatching { app.get(url, referer = "$mainUrl/", headers = headers()).document }.getOrNull() ?: return null
        val text = body(doc); val title = detailTitle(doc, url) ?: return null
        val poster = fix(doc.selectFirst("meta[property='og:image'],meta[name='twitter:image']")?.attr("content")) ?: doc.selectFirst("article,.movie-detail,.film-detail")?.poster()
        val year = Regex("YAPIM YILI\\s+(\\d{4})", RegexOption.IGNORE_CASE).find(text)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: Regex("\\b(19|20)\\d{2}\\b").find(title)?.value?.toIntOrNull()
        val imdb = Regex("IMDB\\s+PUANI\\s+([0-9]+(?:[.,][0-9]+)?)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.getOrNull(1)
        val duration = Regex("SÜRE\\s+(\\d+)\\s*dk", RegexOption.IGNORE_CASE).find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val tag = genres(doc, text); val cast = actors(doc); val rec = results(doc); val p = plot(doc, text)
        if (url.contains("/dizi/", true) || doc.selectFirst(".episodes,.episode-list,.seasons") != null) {
            val eps = doc.select("a[href*='/dizi/'],a[href*='sezon'],a[href*='bolum'],.episode a,.episodes a,.episode-list a").mapNotNull { a -> val u = fix(a.attr("href")) ?: return@mapNotNull null; val t = "${a.text()} ${a.attr("title")}"; val ss = Regex("(?:s|sezon[\\s._-]*)(\\d+)", RegexOption.IGNORE_CASE).find(t)?.groupValues?.getOrNull(1)?.toIntOrNull(); val ee = Regex("(?:e|bölüm[\\s._-]*)(\\d+)", RegexOption.IGNORE_CASE).find(t)?.groupValues?.getOrNull(1)?.toIntOrNull(); if (ss == null || ee == null || u == url) null else newEpisode(u) { name = a.text().trim(); season = ss; episode = ee } }.distinctBy { it.data }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, eps) { posterUrl=poster; this.year=year; plot=p; tags=tag; score=Score.from10(imdb); this.duration=duration; addActors(cast); recommendations=rec }
        }
        return newMovieLoadResponse(title, url, TvType.Movie, url) { posterUrl=poster; this.year=year; plot=p; tags=tag; score=Score.from10(imdb); this.duration=duration; addActors(cast); recommendations=rec }
    }

    private fun player(value: String?, base: String): String? {
        val u = fix(value, base) ?: return null
        if (u.contains("youtube", true) || u.contains("schema.org", true) || u.contains("imdb.com", true) || u.contains("google.com/search", true) || u.contains("yandex", true) || u.contains("dmca.com", true) || u.contains("wp-content", true) || u.contains("wp-includes", true)) return null
        if (u.contains("player.hintfilmizle.com", true)) return u
        if (u.contains("kinescopecdn.net", true) || u.contains("kinescope.io", true)) return u
        if (u.contains("playmate.to", true)) return u
        return null
    }

    private suspend fun kinescope(kine: String, parent: String, callback: (ExtractorLink) -> Unit): Boolean = runCatching {
        val id = Regex("/embed/([A-Za-z0-9_-]+)", RegexOption.IGNORE_CASE).find(kine)?.groupValues?.getOrNull(1) ?: return@runCatching false
        val parsed = runCatching { URI(kine) }.getOrNull() ?: return@runCatching false
        val query = parsed.rawQuery.orEmpty()
        val lang = Regex("(?:^|&)lang=([^&]+)").find(query)?.groupValues?.getOrNull(1) ?: "tr"
        val nc = Regex("(?:^|&)nc=([^&]+)").find(query)?.groupValues?.getOrNull(1) ?: (System.currentTimeMillis()/1000L).toString()
        val actualKine = if (parsed.host.equals("player.hintfilmizle.com", true)) "https://river-3-329.kinescopecdn.net/677113747/embed/$id?design=3&lang=${URLEncoder.encode(lang,"UTF-8")}&autoplay=1&muted=1&preload=1&playsinline=1&background=1&enableIframeApi=1&nc=${URLEncoder.encode(nc,"UTF-8")}" else kine
        val actualParsed = runCatching { URI(actualKine) }.getOrNull() ?: return@runCatching false
        Log.d("HintFilmIzle", "KINESCOPE_EMBED_HOST=${actualParsed.host}")
        Log.d("HintFilmIzle", "KINESCOPE_WEBVIEW_URL=$actualKine")
        val m3u = Regex("https?://[^\\\"'\\s<>]+\\.kinescopecdn\\.net/hls/[^\\\"'\\s<>]+\\.m3u8(?:\\?[^\\\"'\\s<>]*)?", RegexOption.IGNORE_CASE)
        var stream: String? = null
        val script = """
            (function(){try{
              var blocked=/(?:\\/api\\/v1\\/ad-tags|\\/vast(?:[/?]|$)|\\/ads?(?:[/?._-]|$)|doubleclick|googlesyndication|googleadservices|googletagmanager|google-analytics|analytics\\.google\\.com|www\\.google-analytics\\.com|mc\\.yandex\\.ru|metrika\\.yandex\\.ru|yandex\\.ru\\/metrika)/i;
              function isBlocked(u){try{return blocked.test(String(u||''));}catch(e){return false;}}
              var f=window.fetch;window.fetch=function(input,init){var u='';try{u=typeof input==='string'?input:(input&&input.url)||'';}catch(e){}if(isBlocked(u))return Promise.reject(new TypeError('blocked tracking request'));return f.apply(this,arguments);};
              var xo=XMLHttpRequest.prototype.open,xs=XMLHttpRequest.prototype.send;XMLHttpRequest.prototype.open=function(method,url){this.__csUrl=String(url||'');if(isBlocked(this.__csUrl))this.__csBlocked=true;return xo.apply(this,arguments);};XMLHttpRequest.prototype.send=function(){if(this.__csBlocked){try{this.abort();}catch(e){}return;}return xs.apply(this,arguments);};
              var ob=navigator.sendBeacon;if(ob)navigator.sendBeacon=function(url,data){if(isBlocked(url))return true;return ob.apply(this,arguments);};
              function scan(){try{performance.getEntriesByType('resource').forEach(function(e){var u=e.name||'';if(/\\.kinescopecdn\\.net\\/hls\\/.+\\/index\\.m3u8/i.test(u))window.__csManifest=u;});}catch(e){}}
              new MutationObserver(scan).observe(document.documentElement||document,{subtree:true,childList:true});setInterval(scan,150);scan();return true;
            }catch(e){return false;}})()
        """.trimIndent()
        val intercept = Regex("\\.kinescopecdn\\.net/hls/.+\\.m3u8(?:\\?.*)?$", RegexOption.IGNORE_CASE)
        val resolver = WebViewResolver(interceptUrl=intercept,additionalUrls=emptyList(),userAgent=ua,useOkhttp=false,timeout=25_000L,script=script)
        resolver.resolveUsingWebView(actualKine,referer=parent,headers=mapOf("Referer" to parent,"Origin" to "https://${actualParsed.host}","User-Agent" to ua)){req -> val u=req.url.toString(); if(m3u.containsMatchIn(u)){stream=u;Log.d("HintFilmIzle","KINESCOPE_MANIFEST="+u);true}else false}
        val final=stream?:return@runCatching false
        callback(newExtractorLink(source=name,name="HintFilmİzle Kinescope",url=final,type=ExtractorLinkType.M3U8){referer=actualKine;headers=mapOf("Referer" to actualKine,"Origin" to "https://${actualParsed.host}","User-Agent" to ua);quality=getQualityFromName(final)})
        true
    }.getOrElse{Log.e("HintFilmIzle","KINESCOPE_FAILED",it);false}

    override suspend fun loadLinks(data:String,isCasting:Boolean,subtitleCallback:(SubtitleFile)->Unit,callback:(ExtractorLink)->Unit):Boolean {
        val doc=runCatching{app.get(data,referer="$mainUrl/",headers=headers()).document}.getOrNull()?:return false
        val players=linkedSetOf<String>();fun add(value:String?){player(value,data)?.let{players.add(it)}}
        documentFrames(doc,data,::add);var found=false
        for(p in players){when{p.contains("player.hintfilmizle.com",true)||p.contains("kinescope",true)->if(kinescope(p,data,callback))found=true;p.contains("playmate.to",true)->{found=true;loadExtractor(p,data,subtitleCallback,callback)}else->{found=true;loadExtractor(p,data,subtitleCallback,callback)}}}
        return found
    }

    private fun documentFrames(doc: org.jsoup.nodes.Document, base:String, add:(String?)->Unit){
        doc.select("[data-frame], iframe[src], iframe[data-src], iframe[data-url], iframe[data-iframe], frame[src], video[src], video[data-src], video[data-url], video source[src], video source[data-src]").forEach{e->listOf(e.attr("data-frame"),e.attr("src"),e.attr("data-src"),e.attr("data-url"),e.attr("data-iframe")).forEach(add)}
        doc.select("[data-publisher-id][data-id]").forEach{e->{val pub=e.attr("data-publisher-id").trim();val id=e.attr("data-id").trim();if(pub.isNotBlank()&&id.isNotBlank())add("https://river-3-329.kinescopecdn.net/$pub/embed/$id?design=3&lang=tr")}}
        doc.select("script").forEach{s->Regex("https?://[^\\\"'\\s<>]+",RegexOption.IGNORE_CASE).findAll(s.data()).forEach{add(it.value)}}
    }
}
