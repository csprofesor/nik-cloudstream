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
import com.lagradost.cloudstream3.fixUrlNull
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
import com.lagradost.cloudstream3.utils.M3u8Helper
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

    private fun cleanUrl(value: String?, base: String = mainUrl): String? {
        val raw = value?.replace("\\/", "/")?.replace("\\u0026", "&")?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
        return runCatching {
            when {
                raw.startsWith("//") -> "https:$raw"
                raw.startsWith("http://", true) || raw.startsWith("https://", true) -> raw
                raw.startsWith("/") -> "$mainUrl$raw"
                else -> URI(base).resolve(raw).toString()
            }
        }.getOrNull()?.takeIf { it.startsWith("http", true) }
    }

    private fun Element.posterUrl(): String? {
        fun valid(value: String?): String? {
            val url = cleanUrl(value)
            return url?.takeIf {
                !it.startsWith("data:image", true) && !it.contains("placeholder", true) &&
                    !it.contains("spacer", true) && !it.contains("blank.", true)
            }
        }
        val attrs = listOf(
            "data-src", "data-lazy-src", "data-lazy", "data-original", "data-original-src",
            "data-image", "data-poster", "data-poster-url", "data-thumb", "data-thumbnail",
            "data-fsrc", "data-url", "data-image-url", "data-bg", "data-background-image", "src"
        )
        select("img, picture source").asSequence().forEach { image ->
            attrs.asSequence().mapNotNull { valid(image.attr(it)) }.firstOrNull()?.let { return it }
            listOf("data-srcset", "data-lazy-srcset", "srcset").forEach { attr ->
                val candidate = image.attr(attr).split(",").asReversed().asSequence()
                    .map { it.trim().substringBefore(" ").trim() }.mapNotNull { valid(it) }.firstOrNull()
                if (candidate != null) return candidate
            }
        }
        return listOf("data-poster", "data-image", "data-thumb", "data-src")
            .asSequence().mapNotNull { valid(attr(it)) }.firstOrNull()
    }

    private fun Element.cardTitle(): String? = sequenceOf(
        selectFirst(".film-title")?.text(), selectFirst(".movie-title")?.text(),
        selectFirst(".entry-title")?.text(), selectFirst(".card-title")?.text(),
        selectFirst("h2")?.text(), selectFirst("h3")?.text(), selectFirst(".title")?.text(),
        selectFirst(".name")?.text(), selectFirst("img")?.attr("alt"), selectFirst("img")?.attr("title"), attr("title")
    ).mapNotNull { it?.trim()?.replace(Regex("\\s+"), " ")?.takeIf { s -> s.isNotBlank() && !s.equals("image", true) } }
        .firstOrNull()

    private fun Element.toSearchResult(card: Element = this): SearchResponse? {
        val href = cleanUrl(attr("href")) ?: return null
        if (!href.startsWith(mainUrl)) return null
        val path = href.removePrefix(mainUrl).substringBefore("?").trimEnd('/')
        if (!path.startsWith("/film/") && !path.startsWith("/dizi/")) return null
        val title = card.cardTitle()?.replace(Regex("\\s+"), " ")?.trim()?.removeSuffix(" izle")?.trim()?.takeIf { it.isNotBlank() }
            ?: path.substringAfterLast("/").replace(Regex("[-_]+"), " ").replace(Regex("\\b\\w"), { it.value.uppercase() }).trim()
        if (title.length > 180 || title.equals("film", true) || title.equals("dizi", true) || title.equals("filmler", true)) return null
        val poster = card.posterUrl() ?: posterUrl()
        val rating = cardRating(card)
        return if (path.startsWith("/dizi/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = poster
                score = Score.from10(rating)
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl = poster
                score = Score.from10(rating)
            }
        }
    }

    private fun cardRating(card: Element): String? {
        val text = card.text()
        return Regex("""(?<!\d)(?:10(?:[.,]0+)?|[1-9](?:[.,]\d{1,3})?)(?!\d)""")
            .findAll(text)
            .mapNotNull { it.value.replace(',', '.').toFloatOrNull() }
            .firstOrNull { it > 0f && it <= 10f }
            ?.toString()
    }

    private fun extractResults(document: org.jsoup.nodes.Document): List<SearchResponse> {
        val allAnchors = document.select("a[href*='/film/'], a[href*='/dizi/']")
        val heading = document.selectFirst("main h1") ?: document.selectFirst("h1")
        val startIndex = heading?.let { document.allElements.indexOf(it) } ?: -1
        val anchors = if (startIndex >= 0) allAnchors.filter { document.allElements.indexOf(it) > startIndex } else allAnchors
        return anchors.mapNotNull { anchor ->
            val card = anchor.parents().firstOrNull {
                val links = it.select("a[href*='/film/'], a[href*='/dizi/']")
                val image = it.selectFirst("img, picture source, [style*='background'], [data-poster], [data-image]")
                image != null && links.size <= 4 && it.text().length < 1200
            } ?: anchor
            anchor.toSearchResult(card)
        }.distinctBy { it.url }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = if (page <= 1) request.data else request.data.trimEnd('/') + "/page/" + page + "/"
        val document = runCatching {
            app.get(pageUrl, referer = "$mainUrl/", headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
            )).document
        }.getOrNull() ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val firstPageResults = extractResults(document).toMutableList()
        if (firstPageResults.size < 10) {
            for (nextPage in 2..3) {
                val extraUrl = request.data.trimEnd('/') + "/page/" + nextPage + "/"
                val extra = runCatching {
                    extractResults(app.get(extraUrl, referer = pageUrl, headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
                    )).document)
                }.getOrDefault(emptyList())
                firstPageResults.addAll(extra.filterNot { item -> firstPageResults.any { it.url == item.url } })
                if (firstPageResults.size >= 10 || extra.isEmpty()) break
            }
        }
        val results = firstPageResults.distinctBy { it.url }
        return newHomePageResponse(request.name, results, hasNext = results.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().takeIf { it.isNotBlank() } ?: return emptyList()
        val encoded = URLEncoder.encode(q, "UTF-8")
        val urls = linkedSetOf(
            "$mainUrl/film?search=$encoded", "$mainUrl/film?s=$encoded", "$mainUrl/?s=$encoded",
            "$mainUrl/?search=$encoded", "$mainUrl/arama?q=$encoded", "$mainUrl/search?q=$encoded"
        )
        for (url in urls) {
            val results = runCatching {
                extractResults(app.get(url, referer = "$mainUrl/", headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
                )).document)
            }.getOrDefault(emptyList())
            if (results.isNotEmpty()) return results
        }
        val needle = q.lowercase()
        for (page in 1..6) {
            val url = if (page == 1) "$mainUrl/film" else "$mainUrl/film/page/$page/"
            val results = runCatching { extractResults(app.get(url, referer = "$mainUrl/film", headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
            )).document) }.getOrDefault(emptyList())
            val matched = results.filter { it.name.lowercase().contains(needle) }
            if (matched.isNotEmpty()) return matched
        }
        return emptyList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    private fun firstText(document: org.jsoup.nodes.Document, vararg selectors: String): String? =
        selectors.asSequence().mapNotNull { document.selectFirst(it)?.text()?.trim() }.firstOrNull { it.isNotBlank() }

    private fun findNumber(text: String, vararg labels: String): String? {
        val label = labels.joinToString("|") { Regex.escape(it) }
        return Regex("(?:$label)\\s*[:\\-]?\\s*([0-9]+(?:[.,][0-9]+)?)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.getOrNull(1)
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, referer = "$mainUrl/", headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
        )).document
        val title = firstText(document, "h1", ".entry-title", ".film-title", ".movie-title", ".serieTitle") ?: return null
        val poster = cleanUrl(document.selectFirst("meta[property='og:image']")?.attr("content"))
            ?: document.selectFirst("article, .movie-detail, .film-detail, .serie-detail")?.posterUrl()
        val bodyText = document.text()
        val description = firstText(
            document,
            ".description", ".film-description", ".movie-description", ".serieDescription",
            ".plot", ".summary", ".synopsis", ".film-summary", ".movie-summary",
            ".entry-content p", ".entry-content > p",
            "[class*='description' i]", "[class*='summary' i]", "[class*='synopsis' i]"
        ) ?: document.select("h2, h3, h4").firstOrNull { it.text().contains("Genel Bakış", true) }
            ?.parent()?.select("p")?.joinToString(" ") { it.text().trim() }?.trim()
                ?.takeIf { it.isNotBlank() }
        val year = Regex("\\b(19|20)\\d{2}\\b").find(bodyText)?.value?.toIntOrNull()
        val rating = findNumber(bodyText, "IMDb", "IMDB")
        val tags = document.select(".genres a, .genre a, .genreList a, .categories a, .post-categories a").map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
        val actors = document.select(".actors a, .cast a, .oyuncular a").mapNotNull { it.text().trim().takeIf(String::isNotBlank)?.let(::Actor) }.distinctBy { it.name }
        val recommendations = extractResults(document)
        val isSeries = url.contains("/dizi/", true) || document.selectFirst(".episodes, .episode-list, .seasons") != null

        if (isSeries) {
            val episodes = document.select("a[href*='/dizi/'], a[href*='sezon'], a[href*='bolum'], .episode a, .episodes a, .episode-list a").mapNotNull { link ->
                val href = cleanUrl(link.attr("href")) ?: return@mapNotNull null
                if (href == url) return@mapNotNull null
                val text = link.text() + " " + link.attr("title")
                val season = Regex("(?:s|sezon[\\s._-]*)(\\d+)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
                val episode = Regex("(?:e|bölüm[\\s._-]*)(\\d+)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
                if (season == null || episode == null) return@mapNotNull null
                newEpisode(href) { name = link.text().trim(); this.season = season; this.episode = episode }
            }.distinctBy { it.data }.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster; this.year = year; plot = description; this.tags = tags; score = Score.from10(rating); addActors(actors); this.recommendations = recommendations
            }
        }
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            posterUrl = poster; this.year = year; plot = description; this.tags = tags; score = Score.from10(rating); addActors(actors); this.recommendations = recommendations
        }
    }

    private fun directLinks(html: String): List<String> {
        val regex = Regex("""https?://[^"'\\s<>]+?\.(?:m3u8|mp4)(?:\?[^"'\\s<>]*)?""", RegexOption.IGNORE_CASE)
        return regex.findAll(html).mapNotNull { cleanUrl(it.value.trimEnd('\\', '"', '\'', ')', ']')) }.distinct().toList()
    }

    private fun playerUrl(value: String?, baseUrl: String): String? {
        val raw = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val url = runCatching {
            when {
                raw.startsWith("//") -> "https:$raw"
                raw.startsWith("http://", true) || raw.startsWith("https://", true) -> raw
                else -> URI(baseUrl).resolve(raw).toString()
            }
        }.getOrNull()?.takeIf { it.startsWith("http", true) } ?: return null
        if (url.startsWith("data:", true) || url.startsWith("javascript:", true)) return null
        return url
    }

    private fun isTrailerPlayer(url: String): Boolean = listOf("youtube.com", "youtu.be", "youtube-nocookie.com").any { url.contains(it, true) }

    private fun isIgnoredPlayer(url: String): Boolean {
        val u = url.lowercase()
        val blockedHosts = listOf("video.twimg.com", "twitter.com", "x.com", "t.co/", "youtube.com", "youtu.be", "youtube-nocookie.com", "facebook.com", "fb.watch", "instagram.com", "instagramcdn.com", "tiktok.com", "vimeo.com")
        if (blockedHosts.any { u.contains(it) }) return true
        return u.contains("/ads/") || u.contains("ads.") || u.contains("/advert") || u.contains("doubleclick.net") || u.contains("googlesyndication.com")
    }

    @Serializable
    private data class PlaymateStreamInfo(@SerialName("sx") val sx: String? = null)

    private suspend fun loadPlaymate(playerUrl: String, parentUrl: String, callback: (ExtractorLink) -> Unit): Boolean {
        return runCatching {
            val id = playerUrl.substringAfterLast('/').substringBefore('?').trim()
            if (id.isBlank()) return false
            val response = app.post("https://playmate.to/api/s", json = mapOf("c" to id, "d" to "web"),
                headers = mapOf("User-Agent" to "Mozilla/5.0 (X11; Linux x86_64; rv:153.0) Gecko/20100101 Firefox/153.0")).parsed<PlaymateStreamInfo>()
            val stream = response.sx?.trim()?.takeIf { it.startsWith("http", true) && it.contains(".m3u8", true) } ?: return false
            callback(newExtractorLink(source = name, name = "HintFilmİzle Playmate", url = stream, type = ExtractorLinkType.M3U8) {
                referer = parentUrl
                headers = mapOf("Referer" to parentUrl)
                quality = getQualityFromName(stream)
            })
            true
        }.getOrElse { Log.e("HintFilmIzle", "PLAYMATE_FAILED", it); false }
    }

    private suspend fun loadKinescope(iframeUrl: String, parentUrl: String, callback: (ExtractorLink) -> Unit): Boolean {
        return runCatching {
            val userAgent =
                "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

            val resolver = WebViewResolver(
                interceptUrl = Regex(
                    """https?://[^"'\s]+\.m3u8[^"'\s]*""",
                    RegexOption.IGNORE_CASE
                ),
                additionalUrls = emptyList(),
                userAgent = userAgent,
                useOkhttp = false,
                timeout = 60_000L,
                script = """
                    (function() {
                        try {
                            window.__csKinescopeUrls = window.__csKinescopeUrls || [];

                            function remember(u) {
                                try {
                                    if (!u) return;
                                    u = String(u);
                                    if (/kinescope|kinescopecdn|\/hls\/|\.m3u8/i.test(u) &&
                                        window.__csKinescopeUrls.indexOf(u) < 0) {
                                        window.__csKinescopeUrls.push(u);
                                    }
                                } catch (_) {}
                            }

                            if (!window.__csKinescopeHooksInstalled) {
                                window.__csKinescopeHooksInstalled = true;

                                try {
                                    var oldOpen = XMLHttpRequest.prototype.open;
                                    XMLHttpRequest.prototype.open = function(method, url) {
                                        remember(url);
                                        return oldOpen.apply(this, arguments);
                                    };
                                } catch (_) {}

                                try {
                                    var oldFetch = window.fetch;
                                    window.fetch = function(input) {
                                        try {
                                            remember(typeof input === 'string' ? input : (input && input.url));
                                        } catch (_) {}
                                        return oldFetch.apply(this, arguments);
                                    };
                                } catch (_) {}
                            }

                            function clickPlay() {
                                var selectors = [
                                    'video',
                                    'button[aria-label*="Play" i]',
                                    'button[title*="Play" i]',
                                    '[data-testid*="play" i]',
                                    '[class*="play-button" i]',
                                    '[class*="playButton" i]'
                                ];

                                for (var i = 0; i < selectors.length; i++) {
                                    var nodes = document.querySelectorAll(selectors[i]);
                                    for (var j = 0; j < nodes.length; j++) {
                                        var el = nodes[j];
                                        if (!el) continue;

                                        try {
                                            if (el.tagName && el.tagName.toLowerCase() === 'video') {
                                                el.muted = true;
                                                el.setAttribute('muted', '');
                                                el.autoplay = true;
                                                remember(el.currentSrc);
                                                remember(el.src);
                                                var p = el.play();
                                                if (p && p.catch) p.catch(function(){});
                                            } else if (el.offsetWidth > 0 && el.offsetHeight > 0) {
                                                el.click();
                                            }
                                        } catch (_) {}
                                    }
                                }
                            }

                            function collect() {
                                try {
                                    var resources = performance.getEntriesByType('resource') || [];
                                    resources.forEach(function(e) { remember(e.name); });
                                } catch (_) {}

                                try {
                                    document.querySelectorAll('video, video source').forEach(function(e) {
                                        remember(e.currentSrc);
                                        remember(e.src);
                                    });
                                } catch (_) {}

                                try {
                                    var urls = window.__csKinescopeUrls || [];
                                    return JSON.stringify({
                                        href: location.href,
                                        resources: urls.filter(function(u) {
                                            return /kinescope|kinescopecdn|\/hls\/|\.m3u8/i.test(u);
                                        })
                                    });
                                } catch (e) {
                                    return JSON.stringify({error: String(e), href: location.href});
                                }
                            }

                            clickPlay();
                            collect();

                            var started = Date.now();
                            var timer = setInterval(function() {
                                clickPlay();
                                collect();
                                if (Date.now() - started > 40000) clearInterval(timer);
                            }, 500);

                            setTimeout(function() { clearInterval(timer); }, 42000);

                            return collect();
                        } catch (e) {
                            return JSON.stringify({error: String(e), href: location.href});
                        }
                    })()
                """.trimIndent(),
                scriptCallback = { result ->
                    Log.d("HintFilmIzle", "KINESCOPE_JS_GRAPH=$result")
                }
            )

            // Use the real HintFilmIzle/Rendex embed URL.
            // Do not replace river-*/{publisher}/embed/{video} with
            // kinescope.io/embed/{video}; that loses publisher, voiceover,
            // language and design parameters used by the site.
            // Refresh nc on every playback so the WebView follows the
            // browser flow and does not reuse a cached embed.
            val livePlayerUrl = runCatching {
                val uri = URI(iframeUrl)
                val query = uri.rawQuery.orEmpty()
                val refreshedQuery = if (query.isBlank()) {
                    "autoplay=1&muted=1&nc=" + System.currentTimeMillis()
                } else {
                    val params = query.split("&")
                        .filter { it.isNotBlank() && !it.startsWith("nc=", true) }
                        .toMutableList()
                    params.add("autoplay=1")
                    params.add("muted=1")
                    params.add("nc=" + System.currentTimeMillis())
                    params.joinToString("&")
                }
                URI(uri.scheme, uri.authority, uri.path, refreshedQuery, uri.fragment).toString()
            }.getOrElse { iframeUrl }

            Log.d("HintFilmIzle", "KINESCOPE_LIVE_PLAYER=$livePlayerUrl")

            val resolveHeaders = mapOf(
                "Referer" to parentUrl,
                "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
                "User-Agent" to userAgent
            )

            val resolved = resolver.resolveUsingWebView(
                url = parentUrl,
                referer = "$mainUrl/",
                headers = resolveHeaders
            )

            // Düzeltme: resolved.first bir String? olabilir, doğrudan toString alınmalı
            val resolverLinkUrl = resolved.first?.toString().orEmpty()
            val captured = resolved.second.orEmpty()

            val urls = buildList {
                if (resolverLinkUrl.isNotBlank()) add(resolverLinkUrl)
                addAll(captured.map { it.url.toString() })
            }.distinct()

            Log.d("HintFilmIzle", "KINESCOPE_REQUEST_COUNT=${urls.size}")
            urls.filter {
                it.contains("kinescope", true) ||
                    it.contains("kinescopecdn", true) ||
                    it.contains("/hls/", true)
            }.forEach {
                Log.d("HintFilmIzle", "KINESCOPE_REQUEST=$it")
            }

            val manifestCandidates = urls.filter {
                it.contains(".m3u8", true)
            }.distinct()

            Log.d(
                "HintFilmIzle",
                "KINESCOPE_CAPTURED_MANIFESTS=${manifestCandidates.joinToString(" || ")}"
            )

            if (manifestCandidates.isEmpty()) {
                Log.e("HintFilmIzle", "KINESCOPE_NO_SIGNED_MANIFEST")
                return false
            }

            fun score(url: String): Int {
                var value = 0
                if (url.contains(".m3u8", true)) value += 500
                if (url.contains("/hls/", true)) value += 100
                if (url.contains("kinescopecdn.net", true)) value += 50
                if (url.contains("preview", true) || url.contains("trailer", true)) value -= 500
                if (url.contains("/ad", true) || url.contains("ads", true)) value -= 500
                return value
            }

            val manifestUrl = manifestCandidates
                .mapIndexed { index, url -> Triple(score(url), index, url) }
                .maxWithOrNull(
                    compareBy<Triple<Int, Int, String>> { it.first }.thenBy { it.second }
                )?.third ?: return false

            val request = captured.lastOrNull { it.url.toString() == manifestUrl }

            fun header(name: String): String? =
                request?.headers?.get(name)?.takeIf { it.isNotBlank() }

            val headers = linkedMapOf(
                "Referer" to (header("Referer") ?: livePlayerUrl),
                "User-Agent" to (header("User-Agent") ?: userAgent),
                "Accept" to (header("Accept") ?: "*/*")
            )

            header("Accept-Language")?.let { headers["Accept-Language"] = it }
            header("Origin")?.let { headers["Origin"] = it }

            Log.d("HintFilmIzle", "KINESCOPE_SELECTED_MANIFEST=$manifestUrl")
            Log.d("HintFilmIzle", "KINESCOPE_SELECTED_REFERER=${headers["Referer"]}")
            Log.d("HintFilmIzle", "KINESCOPE_SELECTED_ORIGIN=${headers["Origin"].orEmpty()}")

            callback(
                newExtractorLink(
                    source = name,
                    name = "HintFilmİzle Kinescope",
                    url = manifestUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    referer = headers["Referer"] ?: livePlayerUrl
                    this.headers = headers
                    quality = getQualityFromName(manifestUrl)
                }
            )

            true
        }.getOrElse {
            Log.e("HintFilmIzle", "KINESCOPE_RESOLVER_FAILED", it)
            false
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = runCatching {
            app.get(data, referer = "$mainUrl/", headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
            )).document
        }.getOrNull() ?: return false

        var found = false
        val players = linkedSetOf<String>()
        Log.d("HintFilmIzle", "FILM_DATA=$data")

        fun addUrl(value: String?, base: String = data) {
            if (value.isNullOrBlank()) return
            val cleaned = value.replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&").trim().trim('"', '\'')
            val url = playerUrl(cleaned, base) ?: return
            if (isIgnoredPlayer(url) || isTrailerPlayer(url) || url.startsWith(mainUrl, true)) return

            val host = runCatching { URI(url).host?.lowercase().orEmpty() }.getOrDefault("")
            val path = runCatching { URI(url).path?.lowercase().orEmpty() }.getOrDefault("")
            val supportedHost = host.contains("kinescope") ||
                host.contains("playmate") ||
                path.contains("/embed/") || path.contains("/player/") || path.contains("/video/")
            if (supportedHost) players.add(url)
        }

        document.select(
            "iframe[src], iframe[data-src], iframe[data-url], iframe[data-iframe], iframe[data-frame], frame[src], " +
                "video[src], video[data-src], video[data-url], video source[src], video source[data-src], video source[data-url], " +
                "button[data-url], button[data-embed], button[data-frame], button[data-video], button[data-player], [data-url], [data-embed], [data-frame], [data-video], [data-player]"
        ).forEach { element ->
            Log.d("HintFilmIzle", "ELEMENT tag=${element.tagName()} class=${element.className()} id=${element.id()}")
            listOf(
                element.attr("href"), element.attr("src"), element.attr("data-src"), element.attr("data-url"),
                element.attr("data-embed"), element.attr("data-frame"), element.attr("data-video"), element.attr("data-player"),
                element.attr("data-iframe"), element.attr("onclick")
            ).forEach { addUrl(it) }
        }

        // Rendex kinescope parametreleri
        document.select("[data-publisher-id][data-id]").forEach { element ->
            val publisherId = element.attr("data-publisher-id").trim()
            val videoId = element.attr("data-id").trim()
            val design = element.attr("data-design").trim().ifBlank { "3" }
            val playerLang = lang.ifBlank { "tr" }
            val voiceover = element.attr("data-voiceover").trim()
            if (publisherId.isNotBlank() && videoId.isNotBlank()) {
                val query = buildString {
                    append("?design=").append(design)
                    append("&lang=").append(playerLang)
                    if (voiceover.isNotBlank()) append("&voiceover=").append(URLEncoder.encode(voiceover, "UTF-8"))
                }
                val rendexEmbed = "https://river-3-329.kinescopecdn.net/$publisherId/embed/$videoId$query"
                players.add(rendexEmbed)
                Log.d("HintFilmIzle", "KINESCOPE_RENDEX_EMBED=$rendexEmbed")
            }
        }

        // Oyuncu listesini işle
        for (player in players) {
            Log.d("HintFilmIzle", "PROCESSING_PLAYER=$player")
            if (player.contains("kinescope", true) || player.contains("kinescopecdn", true)) {
                if (loadKinescope(player, data, callback)) {
                    found = true
                }
            } else if (player.contains("playmate.to", true)) {
                if (loadPlaymate(player, data, callback)) {
                    found = true
                }
            } else {
                loadExtractor(player, data, subtitleCallback, callback)
                found = true
            }
        }

        return found
    }
}
