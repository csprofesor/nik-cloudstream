package com.nikyokki

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
        "$mainUrl/film?order=DESC&orderby=date" to "Yeni Filmler",
        "$mainUrl/tur/aile-filmleri" to "Aile", "$mainUrl/tur/aksiyon-filmleri" to "Aksiyon",
        "$mainUrl/tur/animasyon-filmleri" to "Animasyon", "$mainUrl/tur/bilim-kurgu-filmleri" to "Bilim Kurgu",
        "$mainUrl/tur/dram-filmleri" to "Dram", "$mainUrl/tur/fantastik-filmleri" to "Fantastik",
        "$mainUrl/tur/komedi-filmleri" to "Komedi", "$mainUrl/tur/macera-filmleri" to "Macera",
        "$mainUrl/tur/romantik-filmleri" to "Romantik", "$mainUrl/tur/savas-filmleri" to "Savaş",
        "$mainUrl/tur/suc-filmleri" to "Suç", "$mainUrl/tur/tarih-filmleri" to "Tarih",
        "$mainUrl/tur/gerilim-filmleri" to "Gerilim", "$mainUrl/netflix-izle" to "Netflix"
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
            attrs.firstNotNullOfOrNull { fix(img.attr(it))?.takeIf { u -> !u.startsWith("data:") && !u.contains("placeholder", true) } }?.let { return it }
        }
        return null
    }

    private fun titleOf(card: Element): String? = sequenceOf(
        card.selectFirst(".film-title")?.text(), card.selectFirst(".movie-title")?.text(),
        card.selectFirst(".entry-title")?.text(), card.selectFirst(".card-title")?.text(),
        card.selectFirst("h2")?.text(), card.selectFirst("h3")?.text(),
        card.selectFirst(".title")?.text(), card.selectFirst(".name")?.text(),
        card.selectFirst("img")?.attr("alt"), card.attr("title")
    ).mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }.firstOrNull()
        ?.replace(Regex("\\s+"), " ")
        ?.replace(Regex("\\s+(Türkçe\\s+(Altyazı|Dublaj)|izle)\\s*$", RegexOption.IGNORE_CASE), "")
        ?.trim()

    private fun rating(card: Element): String? = Regex("(?<!\\d)(?:10(?:[.,]0+)?|[1-9](?:[.,]\\d{1,3})?)(?!\\d)")
        .findAll(card.text()).mapNotNull { it.value.replace(',', '.').toFloatOrNull() }.firstOrNull { it in 0f..10f }?.toString()

    private fun Element.toResult(card: Element = this): SearchResponse? {
        val href = fix(attr("href")) ?: return null
        val path = href.removePrefix(mainUrl).substringBefore("?").trimEnd('/')
        if (!href.startsWith(mainUrl, true) || (!path.startsWith("/film/") && !path.startsWith("/dizi/"))) return null
        val title = titleOf(card) ?: path.substringAfterLast('/').replace(Regex("[-_]+"), " ")
        return if (path.startsWith("/dizi/")) newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            posterUrl = card.poster(); score = Score.from10(rating(card))
        } else newMovieSearchResponse(title, href, TvType.Movie) {
            posterUrl = card.poster(); score = Score.from10(rating(card))
        }
    }

    private fun results(doc: org.jsoup.nodes.Document) = doc.select("a[href*='/film/'],a[href*='/dizi/']")
        .mapNotNull { a -> a.toResult(a.parents().firstOrNull { p -> p.select("img").isNotEmpty() && p.select("a[href*='/film/'],a[href*='/dizi/']").size <= 4 } ?: a) }
        .distinctBy { it.url }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = request.data.substringBefore("?").trimEnd('/')
        val q = request.data.substringAfter("?", "").takeIf { it.isNotBlank() }
        val url = if (page <= 1) request.data else base + "/page/$page/" + if (q != null) "?$q" else ""
        val response = runCatching { app.get(url, referer = "$mainUrl/", headers = headers()) }.getOrNull()
            ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val doc = response.document
        if (request.data.contains("/tur/", ignoreCase = true)) {
            val heading = doc.selectFirst("h1")?.text()?.trim().orEmpty()
            val expected = request.name.removeSuffix(" Filmleri").trim()
            if (expected.isNotBlank() && !heading.contains(expected, ignoreCase = true)) {
                return newHomePageResponse(request.name, emptyList(), hasNext = false)
            }
        }
        val r = results(doc)
        return newHomePageResponse(request.name, r, hasNext = r.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        listOf("$mainUrl/film?search=$q", "$mainUrl/film?s=$q", "$mainUrl/?s=$q", "$mainUrl/?search=$q", "$mainUrl/arama?q=$q").forEach { url ->
            val r = runCatching { results(app.get(url, referer = "$mainUrl/", headers = headers()).document) }.getOrDefault(emptyList())
            if (r.isNotEmpty()) return r
        }
        return emptyList()
    }

    override suspend fun quickSearch(query: String) = search(query)

    private fun body(doc: org.jsoup.nodes.Document) = doc.body()?.text()?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
    private fun label(text: String, name: String, next: String): String? = Regex("${Regex.escape(name)}\\s*[:\\-]?\\s*(.*?)\\s*(?=${Regex.escape(next)}|$)", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(text)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }

    private fun genres(doc: org.jsoup.nodes.Document, text: String): List<String> {
        val dom = doc.select("a[href*='/tur/'],.genres a,.genre a,.categories a").map { it.text().trim() }.filter { it.contains("Film", true) }.distinct()
        if (dom.isNotEmpty()) return dom
        return label(text, "Türü", "Bu Film özeti").orEmpty().split(",").map { it.trim() }.filter { it.isNotBlank() && it.contains("Film", true) }
    }

    private fun actors(doc: org.jsoup.nodes.Document): List<Actor> {
        val links = doc.select("a[href*='oyuncu'],a[href*='oyuncular'],a[href*='actor'],a[href*='cast'],.actors a,.cast a,.oyuncular a").map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
        if (links.isNotEmpty()) return links.map(::Actor)
        val h = doc.getElementsContainingOwnText("ÖNE ÇIKAN OYUNCULAR").firstOrNull()
        val p = h?.parents()?.firstOrNull { it.text().contains("YÖNETMEN", true) && it.text().length < 1800 }
        return p?.select("a")?.map { it.text().trim() }?.filter { it.isNotBlank() }?.distinct()?.map(::Actor).orEmpty()
    }

    private fun plot(text: String): String? {
        val s = Regex("GENEL BAKIŞ\\s+(.*?)\\s+HATA BİLDİR", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(text)?.groupValues?.getOrNull(1) ?: return null
        return s.replace(Regex("^Türü\\s*:\\s*.*?\\s+(?=[A-ZÇĞİÖŞÜ])"), "").substringBefore("Bu Film özeti").trim().takeIf { it.length > 20 }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = runCatching { app.get(url, referer = "$mainUrl/", headers = headers()).document }.getOrNull() ?: return null
        val text = body(doc)
        val title = titleOf(doc) ?: return null
        val poster = fix(doc.selectFirst("meta[property='og:image'],meta[name='twitter:image']")?.attr("content")) ?: doc.selectFirst("article,.movie-detail,.film-detail")?.poster()
        val year = Regex("YAPIM YILI\\s+(\\d{4})", RegexOption.IGNORE_CASE).find(text)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: Regex("\\b(19|20)\\d{2}\\b").find(text)?.value?.toIntOrNull()
        val imdb = Regex("IMDB\\s+PUANI\\s+([0-9]+(?:[.,][0-9]+)?)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.getOrNull(1)
        val duration = Regex("SÜRE\\s+(\\d+)\\s*dk", RegexOption.IGNORE_CASE).find(text)?.groupValues?.getOrNull(1)?.let { "$it dk." }
        val tag = genres(doc, text)
        val cast = actors(doc)
        val rec = results(doc)
        val p = plot(text)
        if (url.contains("/dizi/", true) || doc.selectFirst(".episodes,.episode-list,.seasons") != null) {
            val eps = doc.select("a[href*='/dizi/'],a[href*='sezon'],a[href*='bolum'],.episode a,.episodes a,.episode-list a").mapNotNull { a ->
                val u = fix(a.attr("href")) ?: return@mapNotNull null
                val t = "${a.text()} ${a.attr("title")}"
                val ss = Regex("(?:s|sezon[\\s._-]*)(\\d+)", RegexOption.IGNORE_CASE).find(t)?.groupValues?.getOrNull(1)?.toIntOrNull()
                val ee = Regex("(?:e|bölüm[\\s._-]*)(\\d+)", RegexOption.IGNORE_CASE).find(t)?.groupValues?.getOrNull(1)?.toIntOrNull()
                if (ss == null || ee == null || u == url) null else newEpisode(u) { name = a.text().trim(); season = ss; episode = ee }
            }.distinctBy { it.data }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, eps) {
                posterUrl = poster; this.year = year; plot = p; tags = tag; score = Score.from10(imdb); duration = duration; addActors(cast); recommendations = rec
            }
        }
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            posterUrl = poster; this.year = year; plot = p; tags = tag; score = Score.from10(imdb); duration = duration; addActors(cast); recommendations = rec
        }
    }

    private fun player(value: String?, base: String): String? {
        val u = fix(value, base) ?: return null
        if (u.contains("youtube", true) || u.contains("schema.org", true) || u.contains("imdb.com", true) ||
            u.contains("google.com/search", true) || u.contains("yandex", true) || u.contains("dmca.com", true) ||
            u.contains("wp-content", true) || u.contains("wp-includes", true)) return null

        if (u.contains("player.hintfilmizle.com", true)) {
            val id = Regex("/embed/([A-Za-z0-9_-]+)").find(u)?.groupValues?.getOrNull(1) ?: return null
            return "https://river-3-329.kinescopecdn.net/677113747/embed/$id?voiceover=487&design=3&lang=tr&nc=${System.currentTimeMillis() / 1000L}"
        }

        if (u.contains("kinescopecdn.net", true) || u.contains("kinescope.io", true)) return u
        if (u.contains("playmate.to", true)) return u
        return null
    }

    private suspend fun kinescope(kine: String, parent: String, callback: (ExtractorLink) -> Unit): Boolean = runCatching {
        val id = Regex("/embed/([A-Za-z0-9_-]+)").find(kine)?.groupValues?.getOrNull(1) ?: return false
        val target = "https://river-3-329.kinescopecdn.net/677113747/embed/$id?voiceover=487&design=3&lang=tr&autoplay=1&muted=1&preload=1&playsinline=1&enableIframeApi=1&nc=${System.currentTimeMillis() / 1000L}"
        val m3u = Regex("https?://[^\"'\\s<>]+\\.kinescopecdn\\.net/hls/[^\"'\\s<>]+/index\\.m3u8(?:\\?[^\"'\\s<>]*)?", RegexOption.IGNORE_CASE)
        val api = Regex("https?://[^\"'\\s<>]+/api/v1/embed/[^\"'\\s<>]+", RegexOption.IGNORE_CASE)
        var stream: String? = null
        var sh = emptyMap<String, String>()
        var apiUrl: String? = null
        var ah = emptyMap<String, String>()

        val js = """
            (function(){
              try{
                var t=${org.json.JSONObject.quote(target)};
                function f(){
                  var n=document.querySelectorAll('[data-frame],[data-src],[data-url],[data-iframe],iframe');
                  for(var i=0;i<n.length;i++){
                    var x=n[i],s=x.getAttribute('data-frame')||x.getAttribute('data-src')||x.getAttribute('data-url')||x.getAttribute('data-iframe')||x.getAttribute('src')||'';
                    if(s.indexOf('/embed/$id')>=0||/player\\.hintfilmizle\\.com|kinescope/i.test(s)){
                      var q=x.tagName.toLowerCase()==='iframe'?x:document.createElement('iframe');
                      if(q!==x){x.parentNode.insertBefore(q,x.nextSibling);}
                      q.src=t;
                      q.setAttribute('allow','autoplay; fullscreen; picture-in-picture; encrypted-media; gyroscope; accelerometer; clipboard-write; screen-wake-lock;');
                      q.setAttribute('allowfullscreen','');
                      q.style.width='100%';q.style.height='100%';q.style.minHeight='360px';q.style.border='0';
                      return;
                    }
                  }
                }
                f();
                new MutationObserver(f).observe(document.documentElement,{childList:true,subtree:true});
                setInterval(f,1000);
              }catch(e){console.log('HintFilmIzle inject error',e);}
            })();
        """.trimIndent()

        WebViewResolver(
            interceptUrl = m3u,
            additionalUrls = listOf(api),
            userAgent = ua,
            useOkhttp = false,
            timeout = 90000L,
            script = js
        ).resolveUsingWebView(url = parent, referer = "$mainUrl/", headers = headers()) { r ->
            val u = r.url.toString()
            Log.d("HintFilmIzle", "KINESCOPE_REQUEST=$u")
            when {
                m3u.containsMatchIn(u) -> { stream = u; sh = r.headers.toMap(); true }
                api.containsMatchIn(u) -> { apiUrl = u; ah = r.headers.toMap(); true }
                else -> false
            }
        }

        stream?.let { s ->
            callback(newExtractorLink(source = name, name = "HintFilmİzle Kinescope", url = s, type = ExtractorLinkType.M3U8) {
                referer = sh["Referer"] ?: target
                headers = sh + mapOf("User-Agent" to (sh["User-Agent"] ?: ua))
                quality = getQualityFromName(s)
            })
            return true
        }

        apiUrl?.let { a ->
            val r = app.get(a, referer = parent, headers = ah.ifEmpty { mapOf("User-Agent" to ua, "Referer" to target) })
            val p = runCatching { org.json.JSONObject(r.text).optString("p") }.getOrNull().orEmpty()
            if (p.isNotBlank()) {
                runCatching { android.util.Base64.decode(p.reversed(), android.util.Base64.DEFAULT) }.getOrNull()?.let { b ->
                    val k = "RySdvcyu5iTUxn97v4HwoniwgxaCynA"
                    val d = ByteArray(b.size) { i -> (b[i].toInt() xor k[i % k.length].code).toByte() }.toString(Charsets.UTF_8)
                    Regex("https?://[^\"'\\s<>]+\\.kinescopecdn\\.net/hls/[^\"'\\s<>]+/index\\.m3u8(?:\\?[^\"'\\s<>]*)?", RegexOption.IGNORE_CASE)
                        .findAll(d).map { it.value }.distinct().forEach { s ->
                            callback(newExtractorLink(source = name, name = "HintFilmİzle Kinescope", url = s, type = ExtractorLinkType.M3U8) {
                                referer = parent
                                headers = mapOf("Referer" to parent, "User-Agent" to ua)
                                quality = getQualityFromName(s)
                            })
                        }
                    return true
                }
            }
        }
        false
    }.getOrElse { Log.e("HintFilmIzle", "KINESCOPE_FAILED", it); false }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val doc = runCatching { app.get(data, referer = "$mainUrl/", headers = headers()).document }.getOrNull() ?: return false
        val players = linkedSetOf<String>()
        fun add(v: String?) { player(v, data)?.let { u -> players.add(u) } }

        doc.select("[data-frame]").forEach { add(it.attr("data-frame")) }
        doc.select("iframe[src],iframe[data-src],iframe[data-url],[data-player],[data-embed],[data-video],[data-iframe]").forEach { e ->
            listOf(e.attr("src"), e.attr("data-src"), e.attr("data-url"), e.attr("data-player"), e.attr("data-embed"), e.attr("data-video"), e.attr("data-iframe")).forEach(::add)
        }
        doc.select("[data-publisher-id][data-id]").forEach { e ->
            val pub = e.attr("data-publisher-id")
            val id = e.attr("data-id")
            if (pub == "677113747" && id.isNotBlank()) players.add("https://river-3-329.kinescopecdn.net/$pub/embed/$id?voiceover=487&design=3&lang=tr&nc=${System.currentTimeMillis() / 1000L}")
        }
        doc.select("script").forEach { sc ->
            Regex("https?://player\\.hintfilmizle\\.com/embed/[A-Za-z0-9_-]+", RegexOption.IGNORE_CASE).findAll(sc.data()).forEach { add(it.value) }
            Regex("data-frame\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).findAll(sc.data()).forEach { add(it.groupValues[1]) }
        }

        Log.d("HintFilmIzle", "PLAYER_COUNT=${players.size}")
        players.forEach { Log.d("HintFilmIzle", "PLAYER=$it") }

        var found = false
        for (p in players) when {
            p.contains("kinescope", true) || p.contains("kinescopecdn", true) -> if (kinescope(p, data, callback)) found = true
            p.contains("playmate.to", true) -> if (loadPlaymate(p, data, callback)) found = true
            else -> {
                runCatching { loadExtractor(p, data, subtitleCallback, callback) }
                found = true
            }
        }
        return found
    }

    private suspend fun loadPlaymate(playerUrl: String, parentUrl: String, callback: (ExtractorLink) -> Unit): Boolean = runCatching {
        val id = playerUrl.substringAfterLast('/').substringBefore('?').trim()
        if (id.isBlank()) return false
        val r = app.post("https://playmate.to/api/s", json = mapOf("c" to id, "d" to "web"), headers = mapOf("User-Agent" to ua, "Referer" to parentUrl))
        val stream = org.json.JSONObject(r.text).optString("sx").takeIf { it.startsWith("http", true) && it.contains(".m3u8", true) } ?: return false
        callback(newExtractorLink(source = name, name = "HintFilmİzle Playmate", url = stream, type = ExtractorLinkType.M3U8) {
            referer = parentUrl
            headers = mapOf("Referer" to parentUrl, "User-Agent" to ua)
            quality = getQualityFromName(stream)
        })
        true
    }.getOrElse { Log.e("HintFilmIzle", "PLAYMATE_FAILED", it); false }
}
