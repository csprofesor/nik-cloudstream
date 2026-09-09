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
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
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
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/film?order=DESC&orderby=date" to "Yeni Filmler",
        "$mainUrl/tur/aile-filmleri" to "Aile",
        "$mainUrl/tur/aksiyon-filmleri" to "Aksiyon",
        "$mainUrl/tur/animasyon-filmleri" to "Animasyon",
        "$mainUrl/tur/bilim-kurgu-filmleri" to "Bilim Kurgu",
        "$mainUrl/tur/dram-filmleri" to "Dram",
        "$mainUrl/tur/fantastik-filmleri" to "Fantastik",
        "$mainUrl/tur/komedi-filmleri" to "Komedi",
        "$mainUrl/tur/macera-filmleri" to "Macera",
        "$mainUrl/tur/romantik-filmleri" to "Romantik",
        "$mainUrl/tur/savas-filmleri" to "Savaş",
        "$mainUrl/tur/suc-filmleri" to "Suç",
        "$mainUrl/tur/tarih-filmleri" to "Tarih",
        "$mainUrl/tur/gerilim-filmleri" to "Gerilim",
        "$mainUrl/netflix-izle" to "Netflix"
    )

    private val desktopUa = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36"

    private fun browserHeaders() = mapOf(
        "User-Agent" to desktopUa,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    private fun cleanUrl(value: String?, base: String = mainUrl): String? {
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

    private fun Element.posterUrl(): String? {
        val attrs = listOf("data-src", "data-lazy-src", "data-original", "data-image", "data-poster", "data-thumb", "src")
        select("img, picture source").forEach { image ->
            attrs.firstNotNullOfOrNull { cleanUrl(image.attr(it))?.takeIf { u -> !u.startsWith("data:") && !u.contains("placeholder", true) } }?.let { return it }
            image.attr("srcset").split(",").asReversed().map { it.trim().substringBefore(" ") }.firstNotNullOfOrNull { cleanUrl(it) }?.let { return it }
        }
        return null
    }

    private fun Element.cardTitle(): String? = sequenceOf(
        selectFirst(".film-title")?.text(), selectFirst(".movie-title")?.text(),
        selectFirst(".entry-title")?.text(), selectFirst(".card-title")?.text(),
        selectFirst("h2")?.text(), selectFirst("h3")?.text(), selectFirst(".title")?.text(),
        selectFirst(".name")?.text(), selectFirst("img")?.attr("alt"), attr("title")
    ).mapNotNull { it?.trim()?.replace(Regex("\\s+"), " ")?.takeIf { s -> s.isNotBlank() } }.firstOrNull()

    private fun cardRating(card: Element): String? = Regex("""(?<!\d)(?:10(?:[.,]0+)?|[1-9](?:[.,]\d{1,3})?)(?!\d)""")
        .findAll(card.text()).mapNotNull { it.value.replace(',', '.').toFloatOrNull() }.firstOrNull { it in 0.0f..10.0f }?.toString()

    private fun Element.toSearchResult(card: Element = this): SearchResponse? {
        val href = cleanUrl(attr("href")) ?: return null
        if (!href.startsWith(mainUrl)) return null
        val path = href.removePrefix(mainUrl).substringBefore("?").trimEnd('/')
        if (!path.startsWith("/film/") && !path.startsWith("/dizi/")) return null
        val title = card.cardTitle()?.replace(Regex("\\s+"), " ")?.trim()?.removeSuffix(" izle")?.trim()
            ?: path.substringAfterLast('/').replace(Regex("[-_]+"), " ")
        if (title.isBlank() || title.length > 180) return null
        val poster = card.posterUrl()
        return newMovieSearchResponse(title, href, TvType.Movie) {
            posterUrl = poster
            score = Score.from10(cardRating(card))
        }
    }

    private fun extractResults(document: org.jsoup.nodes.Document): List<SearchResponse> = document
        .select("a[href*='/film/'], a[href*='/dizi/']")
        .mapNotNull { a ->
            val card = a.parents().firstOrNull { p -> p.select("img, picture source").isNotEmpty() && p.select("a[href*='/film/'],a[href*='/dizi/']").size <= 4 } ?: a
            a.toSearchResult(card)
        }.distinctBy { it.url }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else request.data.trimEnd('/') + "/page/$page/"
        val results = runCatching { extractResults(app.get(url, referer = "$mainUrl/", headers = browserHeaders()).document) }.getOrDefault(emptyList())
        return newHomePageResponse(request.name, results, hasNext = results.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().takeIf { it.isNotBlank() } ?: return emptyList()
        val e = URLEncoder.encode(q, "UTF-8")
        val urls = listOf("$mainUrl/film?search=$e", "$mainUrl/film?s=$e", "$mainUrl/?s=$e", "$mainUrl/search?q=$e")
        for (url in urls) {
            val r = runCatching { extractResults(app.get(url, referer = "$mainUrl/", headers = browserHeaders()).document) }.getOrDefault(emptyList())
            if (r.isNotEmpty()) return r
        }
        return emptyList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    private fun firstText(document: org.jsoup.nodes.Document, vararg selectors: String): String? = selectors.asSequence().mapNotNull { document.selectFirst(it)?.text()?.trim() }.firstOrNull { it.isNotBlank() }
    private fun findNumber(text: String, vararg labels: String): String? = Regex("(?:${labels.joinToString("|") { Regex.escape(it) }})\\s*[:\\-]?\\s*([0-9]+(?:[.,][0-9]+)?)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.getOrNull(1)

    override suspend fun load(url: String): LoadResponse? {
        val document = runCatching { app.get(url, referer = "$mainUrl/", headers = browserHeaders()).document }.getOrNull() ?: return null
        val title = firstText(document, "h1", ".entry-title", ".film-title", ".movie-title", ".serieTitle") ?: return null
        val poster = cleanUrl(document.selectFirst("meta[property='og:image']")?.attr("content")) ?: document.selectFirst("article, .movie-detail, .film-detail, .serie-detail")?.posterUrl()
        val body = document.text()
        val description = firstText(document, ".description", ".film-description", ".movie-description", ".serieDescription", ".plot", ".summary", ".synopsis", ".entry-content p")
        val year = Regex("\\b(19|20)\\d{2}\\b").find(body)?.value?.toIntOrNull()
        val rating = findNumber(body, "IMDb", "IMDB")
        val tags = document.select(".genres a, .genre a, .genreList a, .categories a, .post-categories a").map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
        val actors = document.select(".actors a, .cast a, .oyuncular a").mapNotNull { it.text().trim().takeIf(String::isNotBlank)?.let(::Actor) }.distinctBy { it.name }
        val recommendations = extractResults(document)
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            posterUrl = poster; this.year = year; plot = description; this.tags = tags; score = Score.from10(rating); addActors(actors); this.recommendations = recommendations
        }
    }

    private fun isTrailerPlayer(url: String) = listOf("youtube.com", "youtu.be", "youtube-nocookie.com").any { url.contains(it, true) }
    private fun isIgnoredPlayer(url: String): Boolean {
        val u = url.lowercase()
        return listOf("twitter.com", "x.com", "facebook.com", "instagram.com", "tiktok.com", "vimeo.com", "youtube.com", "youtu.be").any { u.contains(it) } ||
            u.contains("/ads/") || u.contains("doubleclick") || u.contains("googlesyndication")
    }

    private fun playerUrl(value: String?, base: String): String? {
        val url = cleanUrl(value, base) ?: return null
        if (url.contains("player.hintfilmizle.com", true)) {
            val id = Regex("""/embed/([A-Za-z0-9_-]+)""").find(url)?.groupValues?.getOrNull(1)
            if (!id.isNullOrBlank()) return "https://river-3-329.kinescopecdn.net/677113747/embed/$id?voiceover=487&design=3&lang=${URLEncoder.encode(lang.ifBlank { "tr" }, "UTF-8")}&nc=${System.currentTimeMillis()/1000L}"
        }
        return url
    }

    private suspend fun loadKinescope(iframeUrl: String, parentUrl: String, callback: (ExtractorLink) -> Unit): Boolean = runCatching {
        val videoId = Regex("""/embed/([A-Za-z0-9_-]+)""").find(iframeUrl)?.groupValues?.getOrNull(1) ?: return false
        val voiceover = Regex("""[?&]voiceover=([^&]+)""").find(iframeUrl)?.groupValues?.getOrNull(1) ?: "487"
        val playerUrl = "https://river-3-329.kinescopecdn.net/677113747/embed/$videoId?voiceover=$voiceover&design=3&lang=${URLEncoder.encode(lang.ifBlank { "tr" }, "UTF-8")}&nc=${System.currentTimeMillis()/1000L}"
        val manifestRegex = Regex("""https?://[^\"'\\s]+\.kinescopecdn\.net/hls/[^\"'\\s]+/index\.m3u8(?:\?[^\"'\\s]*)?""", RegexOption.IGNORE_CASE)
        val apiRegex = Regex("""https?://[^\"'\\s]+/api/v1/embed/[^\"'\\s]+""", RegexOption.IGNORE_CASE)
        var manifest: String? = null
        var manifestHeaders: Map<String,String> = emptyMap()
        var api: String? = null
        var apiHeaders: Map<String,String> = emptyMap()
        val script = """
            (function(){
                try {
                    var target = ${org.json.JSONObject.quote(playerUrl)};
                    function fix(){
                        var found = false;
                        document.querySelectorAll('iframe').forEach(function(f){
                            var src = f.getAttribute('src') || f.getAttribute('data-src') || '';
                            if (/kinescope|kinescopecdn/i.test(src) || src.indexOf('/embed/$videoId') >= 0){
                                f.setAttribute('allow','autoplay; fullscreen; picture-in-picture; encrypted-media; gyroscope; accelerometer; clipboard-write; screen-wake-lock;');
                                f.setAttribute('allowfullscreen','');
                                if (src.indexOf('/embed/$videoId') >= 0 && src !== target) f.src = target;
                                found = true;
                            }
                        });
                        if (!found){
                            var nodes = document.querySelectorAll('[data-frame],[data-src],[data-url],[data-iframe]');
                            for(var i=0;i<nodes.length;i++){
                                var s=nodes[i].getAttribute('data-frame')||nodes[i].getAttribute('data-src')||nodes[i].getAttribute('data-url')||nodes[i].getAttribute('data-iframe')||'';
                                if(s.indexOf('/embed/$videoId')>=0 || /kinescope|kinescopecdn/i.test(s)){
                                    var f=document.createElement('iframe'); f.src=target; f.setAttribute('allow','autoplay; fullscreen; picture-in-picture; encrypted-media; gyroscope; accelerometer; clipboard-write; screen-wake-lock;'); f.setAttribute('allowfullscreen',''); f.style.width='100%'; f.style.height='100%'; f.style.minHeight='360px'; f.style.border='0'; nodes[i].appendChild(f); found=true; break;
                                }
                            }
                        }
                    }
                    fix();
                    new MutationObserver(fix).observe(document.documentElement,{childList:true,subtree:true});
                    setInterval(fix,1000);
                } catch(e) {}
            })();
        """.trimIndent()
        val resolver = WebViewResolver(interceptUrl=manifestRegex, additionalUrls=listOf(apiRegex), userAgent=desktopUa, useOkhttp=false, timeout=60_000L, script=script)
        resolver.resolveUsingWebView(url=parentUrl, referer="$mainUrl/", headers=browserHeaders()) { request ->
            val u=request.url.toString()
            Log.d("HintFilmIzle","KINESCOPE_REQUEST=$u")
            when {
                manifestRegex.containsMatchIn(u) -> { manifest=u; manifestHeaders=request.headers.toMap(); true }
                apiRegex.containsMatchIn(u) -> { api=u; apiHeaders=request.headers.toMap(); true }
                else -> false
            }
        }
        manifest?.let { stream ->
            val h=linkedMapOf("Referer" to (manifestHeaders["Referer"] ?: playerUrl), "User-Agent" to (manifestHeaders["User-Agent"] ?: desktopUa))
            callback(newExtractorLink("HintFilmİzle Kinescope","Kinescope",stream,ExtractorLinkType.M3U8) { headers=h; quality=getQualityFromName("1080p") })
            return true
        }
        api?.let { apiUrl ->
            val response=app.get(apiUrl, headers=apiHeaders + mapOf("User-Agent" to desktopUa, "Referer" to playerUrl))
            val p=response.parsedSafe<org.json.JSONObject>()?.optString("p")?.takeIf { it.isNotBlank() }
            if (!p.isNullOrBlank()) {
                val decoded=runCatching {
                    val rev=String(android.util.Base64.decode(p, android.util.Base64.DEFAULT).reversed().toByteArray(), Charsets.UTF_8)
                    val key="RySdvcyu5iTUxn97v4HwoniwgxaCynA".toByteArray(Charsets.UTF_8)
                    rev.toByteArray(Charsets.UTF_8).mapIndexed { i,b -> (b.toInt() xor key[i%key.size].toInt()).toByte() }.toByteArray().toString(Charsets.UTF_8)
                }.getOrNull()
                val stream=decoded?.let { Regex("""https?://[^\"'\\s]+\.m3u8(?:\?[^\"'\\s]*)?""", RegexOption.IGNORE_CASE).find(it)?.value }
                if (!stream.isNullOrBlank()) {
                    callback(newExtractorLink("HintFilmİzle Kinescope","Kinescope",stream,ExtractorLinkType.M3U8) { headers=mapOf("Referer" to playerUrl,"User-Agent" to desktopUa); quality=getQualityFromName("1080p") })
                    return true
                }
            }
        }
        false
    }.getOrDefault(false)

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val players = Regex("https?://[^\"'\\s<>]+", RegexOption.IGNORE_CASE).findAll(data).mapNotNull { cleanUrl(it.value) }.filterNot { isIgnoredPlayer(it) }.distinct().toList()
        var found = false
        players.forEach { player ->
            if (player.contains("kinescope", true) || player.contains("player.hintfilmizle.com", true)) {
                if (loadKinescope(player, data, callback)) found = true
            } else if (!isTrailerPlayer(player)) {
                runCatching { loadExtractor(player, "$mainUrl/", subtitleCallback, callback) }
            }
        }
        return found
    }
}
