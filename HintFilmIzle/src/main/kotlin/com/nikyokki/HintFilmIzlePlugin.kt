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
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
        return if (path.startsWith("/dizi/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = poster
                score = Score.from10(cardRating(card))
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl = poster
                score = Score.from10(cardRating(card))
            }
        }
    }

    private fun extractResults(document: org.jsoup.nodes.Document): List<SearchResponse> = document
        .select("a[href*='/film/'], a[href*='/dizi/']")
        .mapNotNull { a ->
            val card = a.parents().firstOrNull { p ->
                p.select("img, picture source").isNotEmpty() &&
                    p.select("a[href*='/film/'],a[href*='/dizi/']").size <= 4
            } ?: a
            a.toSearchResult(card)
        }.distinctBy { it.url }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = request.data.substringBefore("?").trimEnd('/')
        val query = request.data.substringAfter("?", "").takeIf { it.isNotBlank() }
        val url = if (page <= 1) request.data else {
            base + "/page/$page/" + if (query != null) "?$query" else ""
        }
        val results = runCatching {
            extractResults(app.get(url, referer = "$mainUrl/", headers = browserHeaders()).document)
        }.getOrDefault(emptyList())
        return newHomePageResponse(request.name, results, hasNext = results.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().takeIf { it.isNotBlank() } ?: return emptyList()
        val e = URLEncoder.encode(q, "UTF-8")
        val urls = listOf(
            "$mainUrl/film?search=$e",
            "$mainUrl/film?s=$e",
            "$mainUrl/?s=$e",
            "$mainUrl/?search=$e",
            "$mainUrl/arama?q=$e",
            "$mainUrl/search?q=$e"
        )
        for (url in urls) {
            val r = runCatching {
                extractResults(app.get(url, referer = "$mainUrl/", headers = browserHeaders()).document)
            }.getOrDefault(emptyList())
            if (r.isNotEmpty()) return r
        }
        return emptyList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    private fun firstText(document: org.jsoup.nodes.Document, vararg selectors: String): String? =
        selectors.asSequence().mapNotNull { document.selectFirst(it)?.text()?.trim() }.firstOrNull { it.isNotBlank() }

    private fun findNumber(text: String, vararg labels: String): String? =
        Regex("(?:${labels.joinToString("|") { Regex.escape(it) }})(?:\s+[A-Za-zÇĞİÖŞÜçğıöşü]+){0,3}\s*[:\-]?\s*([0-9]+(?:[.,][0-9]+)?)", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.getOrNull(1)

    private fun sectionText(body: String, start: String, end: String): String? =
        Regex("${Regex.escape(start)}\s+(.*?)\s+${Regex.escape(end)}", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(body)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }

    private fun extractGenreTags(document: org.jsoup.nodes.Document, body: String): List<String> {
        val domTags = document.select(".genres a, .genre a, .genreList a, .categories a, .post-categories a, a[href*='/tur/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() && it.length < 80 && it.contains("Film", true) }
            .distinct()
        if (domTags.isNotEmpty()) return domTags

        val match = Regex(
            "Türü\s*:\s*((?:[A-ZÇĞİÖŞÜ][^,]+?Filmleri(?:\s*,\s*)?)+)",
            RegexOption.IGNORE_CASE
        ).find(body) ?: return emptyList()

        return match.groupValues[1]
            .split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun extractActors(document: org.jsoup.nodes.Document, body: String): List<Actor> {
        val direct = document.select(".actors a, .cast a, .oyuncular a, .cast-list a, .actor-list a")
            .mapNotNull { it.text().trim().takeIf(String::isNotBlank)?.let(::Actor) }
            .distinctBy { it.name }
        if (direct.isNotEmpty()) return direct

        val heading = document.getElementsContainingOwnText("ÖNE ÇIKAN OYUNCULAR").firstOrNull()
        val container = heading?.parents()?.plus(heading)?.firstOrNull { element ->
            val text = element.text()
            val links = element.select("a")
            text.contains("ÖNE ÇIKAN OYUNCULAR", true) &&
                text.contains("YÖNETMEN", true) &&
                links.size in 1..30
        }
        return container?.select("a")
            ?.mapNotNull { it.text().trim().takeIf(String::isNotBlank)?.let(::Actor) }
            ?.distinctBy { it.name }
            .orEmpty()
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = runCatching { app.get(url, referer = "$mainUrl/", headers = browserHeaders()).document }.getOrNull() ?: return null
        val title = firstText(document, "h1", ".entry-title", ".film-title", ".movie-title", ".serieTitle") ?: return null
        val poster = cleanUrl(document.selectFirst("meta[property='og:image']")?.attr("content"))
            ?: document.selectFirst("article, .movie-detail, .film-detail, .serie-detail")?.posterUrl()
        val body = document.text().replace(Regex("\\s+"), " ").trim()

        val overview = sectionText(body, "GENEL BAKIŞ", "HATA BİLDİR")
        val description = firstText(
            document,
            ".description", ".film-description", ".movie-description", ".serieDescription",
            ".plot", ".summary", ".synopsis", ".film-summary", ".movie-summary", ".entry-content p", ".entry-content > p"
        ) ?: overview?.let {
            it.replaceFirst(Regex("^Türü\s*:\s*(?:[A-ZÇĞİÖŞÜ][^,]+?Filmleri(?:\s*,\s*)?)+\s+", RegexOption.IGNORE_CASE), "")
                .substringBefore("Bu Film özeti")
                .trim()
                .takeIf { text -> text.isNotBlank() }
        }

        val year = Regex("\b(19|20)\d{2}\b").find(body)?.value?.toIntOrNull()
        val rating = findNumber(body, "IMDb", "IMDB")
        val tags = extractGenreTags(document, body)
        val actors = extractActors(document, body)
        val recommendations = extractResults(document)
        val isSeries = url.contains("/dizi/", true) || document.selectFirst(".episodes, .episode-list, .seasons") != null
        if (isSeries) {
            val episodes = document.select("a[href*='/dizi/'], a[href*='sezon'], a[href*='bolum'], .episode a, .episodes a, .episode-list a")
                .mapNotNull { link ->
                    val href = cleanUrl(link.attr("href")) ?: return@mapNotNull null
                    if (href == url) return@mapNotNull null
                    val text = link.text() + " " + link.attr("title")
                    val season = Regex("(?:s|sezon[\\s._-]*)(\\d+)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    val episode = Regex("(?:e|bölüm[\\s._-]*)(\\d+)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    if (season == null || episode == null) return@mapNotNull null
                    newEpisode(href) {
                        name = link.text().trim()
                        this.season = season
                        this.episode = episode
                    }
                }.distinctBy { it.data }.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster
                this.year = year
                plot = description
                this.tags = tags
                score = Score.from10(rating)
                addActors(actors)
                this.recommendations = recommendations
            }
        }
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            posterUrl = poster
            this.year = year
            plot = description
            this.tags = tags
            score = Score.from10(rating)
            addActors(actors)
            this.recommendations = recommendations
        }
    }

    private fun isTrailerPlayer(url: String) =
        listOf("youtube.com", "youtu.be", "youtube-nocookie.com").any { url.contains(it, true) }

    private fun isIgnoredPlayer(url: String): Boolean {
        val u = url.lowercase()
        val blockedHosts = listOf(
            "video.twimg.com", "twitter.com", "x.com", "t.co/",
            "youtube.com", "youtu.be", "youtube-nocookie.com",
            "facebook.com", "fb.watch", "instagram.com", "instagramcdn.com",
            "tiktok.com", "vimeo.com"
        )
        if (blockedHosts.any { u.contains(it) }) return true
        return u.contains("/ads/") || u.contains("ads.") || u.contains("/advert") ||
            u.contains("doubleclick.net") || u.contains("googlesyndication.com")
    }

    @Serializable
    private data class PlaymateStreamInfo(@SerialName("sx") val sx: String? = null)

    private suspend fun loadPlaymate(playerUrl: String, parentUrl: String, callback: (ExtractorLink) -> Unit): Boolean = runCatching {
        val id = playerUrl.substringAfterLast('/').substringBefore('?').trim()
        if (id.isBlank()) return false
        val response = app.post(
            "https://playmate.to/api/s",
            json = mapOf("c" to id, "d" to "web"),
            headers = mapOf("User-Agent" to "Mozilla/5.0 (X11; Linux x86_64; rv:153.0) Gecko/20100101 Firefox/153.0")
        ).parsed<PlaymateStreamInfo>()
        val stream = response.sx?.trim()?.takeIf { it.startsWith("http", true) && it.contains(".m3u8", true) } ?: return false
        callback(newExtractorLink(source = name, name = "HintFilmİzle Playmate", url = stream, type = ExtractorLinkType.M3U8) {
            referer = parentUrl
            headers = mapOf("Referer" to parentUrl)
            quality = getQualityFromName(stream)
        })
        true
    }.getOrElse { Log.e("HintFilmIzle", "PLAYMATE_FAILED", it); false }

    private fun playerUrl(value: String?, base: String): String? {
        val url = cleanUrl(value, base) ?: return null
        if (url.contains("player.hintfilmizle.com", true)) {
            val id = Regex("""/embed/([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE).find(url)?.groupValues?.getOrNull(1)
            if (!id.isNullOrBlank()) {
                return "https://river-3-329.kinescopecdn.net/677113747/embed/$id?voiceover=487&design=3&lang=${URLEncoder.encode(lang.ifBlank { "tr" }, "UTF-8")}&nc=${System.currentTimeMillis()/1000L}"
            }
        }
        return url
    }

    private suspend fun loadKinescope(iframeUrl: String, parentUrl: String, callback: (ExtractorLink) -> Unit): Boolean = runCatching {
        val videoId = Regex("""/embed/([A-Za-z0-9_-]+)""").find(iframeUrl)?.groupValues?.getOrNull(1) ?: return false
        val voiceover = Regex("""[?&]voiceover=([^&]+)""").find(iframeUrl)?.groupValues?.getOrNull(1) ?: "487"
        val playerUrl = "https://river-3-329.kinescopecdn.net/677113747/embed/$videoId?voiceover=$voiceover&design=3&lang=${URLEncoder.encode(lang.ifBlank { "tr" }, "UTF-8")}&nc=${System.currentTimeMillis()/1000L}"
        val manifestRegex = Regex("""https?://[^\"'\s]+\.kinescopecdn\.net/hls/[^\"'\s]+/index\.m3u8(?:\?[^\"'\s]*)?""", RegexOption.IGNORE_CASE)
        val apiRegex = Regex("""https?://[^\"'\s]+/api/v1/embed/[^\"'\s]+""", RegexOption.IGNORE_CASE)
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
                                    var f=document.createElement('iframe');
                                    f.src=target;
                                    f.setAttribute('allow','autoplay; fullscreen; picture-in-picture; encrypted-media; gyroscope; accelerometer; clipboard-write; screen-wake-lock;');
                                    f.setAttribute('allowfullscreen','');
                                    f.style.width='100%'; f.style.height='100%'; f.style.minHeight='360px'; f.style.border='0';
                                    nodes[i].appendChild(f); found=true; break;
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

        val resolver = WebViewResolver(
            interceptUrl = manifestRegex,
            additionalUrls = listOf(apiRegex),
            userAgent = desktopUa,
            useOkhttp = false,
            timeout = 60_000L,
            script = script
        )
        resolver.resolveUsingWebView(url = parentUrl, referer = "$mainUrl/", headers = browserHeaders()) { request ->
            val u = request.url.toString()
            Log.d("HintFilmIzle", "KINESCOPE_REQUEST=$u")
            when {
                manifestRegex.containsMatchIn(u) -> { manifest = u; manifestHeaders = request.headers.toMap(); true }
                apiRegex.containsMatchIn(u) -> { api = u; apiHeaders = request.headers.toMap(); true }
                else -> false
            }
        }

        manifest?.let { stream ->
            val h = linkedMapOf(
                "Referer" to (manifestHeaders["Referer"] ?: playerUrl),
                "User-Agent" to (manifestHeaders["User-Agent"] ?: desktopUa)
            )
            manifestHeaders["Origin"]?.let { h["Origin"] = it }
            callback(newExtractorLink(source = name, name = "HintFilmİzle Kinescope", url = stream, type = ExtractorLinkType.M3U8) {
                referer = h["Referer"] ?: playerUrl
                headers = h
                quality = getQualityFromName(stream)
            })
            return true
        }

        api?.let { apiUrl ->
            val response = app.get(
                apiUrl,
                referer = parentUrl,
                headers = apiHeaders.ifEmpty { mapOf("User-Agent" to desktopUa, "Referer" to playerUrl) }
            )
            val p = runCatching { org.json.JSONObject(response.text).optString("p") }.getOrNull()?.takeIf { it.isNotBlank() }
            if (!p.isNullOrBlank()) {
                val decoded = runCatching {
                    val bytes = android.util.Base64.decode(p.reversed(), android.util.Base64.DEFAULT)
                    val key = "RySdvcyu5iTUxn97v4HwoniwgxaCynA"
                    ByteArray(bytes.size) { i -> (bytes[i].toInt() xor key[i % key.length].code).toByte() }.toString(Charsets.UTF_8)
                }.getOrNull()
                val streams = decoded?.let {
                    Regex("""https?://[^\"'\s]+\.kinescopecdn\.net/hls/[^\"'\s]+/index\.m3u8(?:\?[^\"'\s]*)?""", RegexOption.IGNORE_CASE)
                        .findAll(it).map { match -> match.value }.distinct().toList()
                }.orEmpty()
                if (streams.isNotEmpty()) {
                    streams.forEach { stream ->
                        callback(newExtractorLink(source = name, name = "HintFilmİzle Kinescope", url = stream, type = ExtractorLinkType.M3U8) {
                            referer = parentUrl
                            headers = mapOf("Referer" to parentUrl, "User-Agent" to desktopUa)
                            quality = getQualityFromName(stream)
                        })
                    }
                    return true
                }
            }
        }
        false
    }.getOrElse { Log.e("HintFilmIzle", "KINESCOPE_FAILED", it); false }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = runCatching { app.get(data, referer = "$mainUrl/", headers = browserHeaders()).document }.getOrNull() ?: return false
        var found = false
        val players = linkedSetOf<String>()

        fun addUrl(value: String?, base: String = data) {
            if (value.isNullOrBlank()) return
            val cleaned = value.replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&").trim().trim('"', '\'')
            playerUrl(cleaned, base)?.let { url ->
                if (!isIgnoredPlayer(url) && !isTrailerPlayer(url) && !url.startsWith(mainUrl, true)) players.add(url)
            }
            Regex("""https?://[^"'\s<>]+""", RegexOption.IGNORE_CASE).findAll(cleaned)
                .map { it.value.trimEnd('\\', '"', '\'', ')', ']', ';') }
                .mapNotNull { playerUrl(it, base) }
                .filter { !isIgnoredPlayer(it) && !isTrailerPlayer(it) && !it.startsWith(mainUrl, true) }
                .forEach(players::add)
        }

        document.select("[data-frame], iframe[data-frame], a[data-frame], button[data-frame]").forEach { addUrl(it.attr("data-frame")) }
        document.select("[data-publisher-id][data-id]").forEach { element ->
            val publisherId = element.attr("data-publisher-id").trim()
            val videoId = element.attr("data-id").trim()
            val design = element.attr("data-design").trim().ifBlank { "3" }
            val playerLang = lang.ifBlank { "tr" }
            if (publisherId.isNotBlank() && videoId.isNotBlank()) {
                players.add("https://river-3-329.kinescopecdn.net/$publisherId/embed/$videoId?voiceover=487&design=$design&lang=${URLEncoder.encode(playerLang, "UTF-8")}&nc=${System.currentTimeMillis()/1000L}")
            }
        }
        document.select("iframe[src], iframe[data-src], iframe[data-url], iframe[data-iframe], frame[src], video[src], video[data-src], video[data-url], video source[src], video source[data-src], a[href], a[data-url], a[data-embed], a[data-video], a[data-player], button[data-url], button[data-embed], button[data-video], button[data-player], [data-url], [data-embed], [data-video], [data-player]").forEach { element ->
            listOf(element.attr("href"), element.attr("src"), element.attr("data-src"), element.attr("data-url"), element.attr("data-embed"), element.attr("data-frame"), element.attr("data-video"), element.attr("data-player"), element.attr("data-iframe"), element.attr("onclick")).forEach { addUrl(it) }
        }
        document.select("script").forEach { script ->
            Regex("""https?://[^"'\s<>]+""", RegexOption.IGNORE_CASE).findAll(script.data()).forEach { addUrl(it.value) }
            Regex("""data-frame\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).findAll(script.data()).forEach { addUrl(it.groupValues.getOrNull(1)) }
        }
        Regex("""data-frame\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).findAll(document.html()).forEach { addUrl(it.groupValues.getOrNull(1)) }

        Log.d("HintFilmIzle", "PLAYER_COUNT=${players.size}")
        players.forEach { Log.d("HintFilmIzle", "PLAYER=$it") }
        for (player in players) {
            when {
                player.contains("kinescope", true) || player.contains("kinescopecdn", true) -> if (loadKinescope(player, data, callback)) found = true
                player.contains("playmate.to", true) -> if (loadPlaymate(player, data, callback)) found = true
                else -> {
                    runCatching { loadExtractor(player, data, subtitleCallback, callback) }
                    found = true
                }
            }
        }
        return found
    }
}
