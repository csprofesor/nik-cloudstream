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
        "$mainUrl/trendler" to "Trendler",
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
        return if (path.startsWith("/dizi/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { posterUrl = poster }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }
        }
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
        val results = extractResults(document)
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
        val description = firstText(document, ".description", ".film-description", ".movie-description", ".serieDescription", ".plot", ".entry-content p")
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

    private suspend fun loadKinescope(iframeUrl: String, parentUrl: String, callback: (ExtractorLink) -> Unit): Boolean {
        return runCatching {
            val userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36"
            val resolver = WebViewResolver(
                interceptUrl = Regex(
                    """https?://[^"'\\s<>]*kinescopecdn\.net/[^"'\\s<>]+\.m3u8(?:\?[^"'\\s<>]*)?""",
                    RegexOption.IGNORE_CASE
                ),
                additionalUrls = listOf(
                    Regex("""https?://[^"'\\s<>]*kinescopecdn\.net/[^"'\\s<>]*""", RegexOption.IGNORE_CASE),
                    Regex("""https?://[^"'\\s<>]*kinescope\.io/[^"'\\s<>]*""", RegexOption.IGNORE_CASE),
                    Regex("""https?://[^"'\\s<>]*kinescope[^"'\\s<>]*""", RegexOption.IGNORE_CASE)
                ),
                userAgent = userAgent,
                useOkhttp = false,
                timeout = 45_000L,
                script = """
                    (function() {
                        try {
                            var v = document.querySelector('video');
                            var resources = performance.getEntriesByType('resource')
                                .map(function(e) { return e.name; })
                                .filter(function(u) { return /m3u8|kinescope|kinescopecdn|hls|master/i.test(u); });
                            return JSON.stringify({
                                href: location.href,
                                videoCurrentSrc: v ? (v.currentSrc || v.src || '') : '',
                                videoReadyState: v ? v.readyState : -1,
                                resources: resources
                            });
                        } catch (e) {
                            return JSON.stringify({error: String(e), href: location.href});
                        }
                    })()
                """.trimIndent(),
                scriptCallback = { result -> Log.d("HintFilmIzle", "KINESCOPE_JS_GRAPH=$result") }
            )

            val resolveHeaders = mapOf(
                "Referer" to parentUrl,
                "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
                "User-Agent" to userAgent
            )

            val resolved = resolver.resolveUsingWebView(url = iframeUrl, referer = parentUrl, headers = resolveHeaders)
            val resolverLinkUrl = resolved.first?.url?.toString().orEmpty()
            val captured = resolved.second.orEmpty()
            val urls = buildList {
                if (resolverLinkUrl.isNotBlank()) add(resolverLinkUrl)
                addAll(captured.map { it.url.toString() })
            }.distinct()

            Log.d("HintFilmIzle", "KINESCOPE_REQUEST_COUNT=${urls.size}")
            urls.filter { it.contains("kinescope", true) || it.contains("kinescopecdn", true) }
                .forEach { Log.d("HintFilmIzle", "KINESCOPE_REQUEST=$it") }

            val embedId = Regex("""/embed/([A-Za-z0-9_-]+)(?:[/?#]|$)""", RegexOption.IGNORE_CASE)
                .find(iframeUrl)?.groupValues?.getOrNull(1)

            val directMaster = embedId?.let { "https://kinescope.io/$it/master.m3u8" }

            val capturedManifests = urls.filter {
                it.contains(".m3u8", true) &&
                    (it.contains("kinescopecdn.net", true) || it.contains("kinescope.io", true))
            }.distinct()

            // Prefer the real manifest captured from the Kinescope player. The
            // captured URL can contain the signed/query parameters required by
            // the video's access policy. Use the public master URL only as a
            // fallback when no playable manifest was captured.
            val manifestCandidates = if (capturedManifests.isNotEmpty()) {
                capturedManifests
            } else {
                listOfNotNull(directMaster)
            }

            Log.d("HintFilmIzle", "KINESCOPE_EMBED_ID=${embedId.orEmpty()}")
            Log.d("HintFilmIzle", "KINESCOPE_DIRECT_MASTER=${directMaster.orEmpty()}")
            Log.d("HintFilmIzle", "KINESCOPE_CAPTURED_MANIFESTS=${capturedManifests.joinToString(" || ")}")
            Log.d("HintFilmIzle", "KINESCOPE_MANIFEST_CANDIDATES=${manifestCandidates.joinToString(" || ")}")

            if (manifestCandidates.isEmpty()) {
                Log.e("HintFilmIzle", "KINESCOPE_NO_HLS_CANDIDATE")
                return false
            }

            fun score(url: String): Int {
                var value = 0
                if (embedId != null && url.contains(embedId, true)) value += 1000
                if (url.contains("master.m3u8", true)) value += 500
                if (url.contains("kinescopecdn.net", true)) value += 100
                if (url.contains("/hls/", true)) value += 50
                if (url.contains("preview", true) || url.contains("trailer", true)) value -= 500
                if (url.contains("/ad", true) || url.contains("ads", true)) value -= 500
                return value
            }

            val manifestUrl = manifestCandidates.mapIndexed { index, url -> Triple(score(url), index, url) }
                .maxWithOrNull(compareBy<Triple<Int, Int, String>> { it.first }.thenBy { it.second })?.third ?: return false

            val request = captured.lastOrNull { it.url.toString() == manifestUrl }
            fun header(name: String): String? = request?.headers?.get(name)?.takeIf { it.isNotBlank() }
            val headers = linkedMapOf(
                "Referer" to (header("Referer") ?: parentUrl),
                "User-Agent" to (header("User-Agent") ?: userAgent),
                "Origin" to (header("Origin") ?: mainUrl),
                "Accept" to (header("Accept") ?: "*/*")
            )
            header("Accept-Language")?.let { headers["Accept-Language"] = it }

            Log.d("HintFilmIzle", "KINESCOPE_SELECTED_MANIFEST=$manifestUrl")
            Log.d("HintFilmIzle", "KINESCOPE_SELECTED_REFERER=${headers["Referer"]}")
            Log.d("HintFilmIzle", "KINESCOPE_SELECTED_ORIGIN=${headers["Origin"]}")
            callback(newExtractorLink(
                source = name,
                name = "HintFilmİzle Kinescope",
                url = manifestUrl,
                type = ExtractorLinkType.M3U8
            ) {
                referer = headers["Referer"] ?: parentUrl
                this.headers = headers
                quality = getQualityFromName(manifestUrl)
            })
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
            playerUrl(cleaned, base)?.let { url ->
                if (!isIgnoredPlayer(url) && !isTrailerPlayer(url) && !url.startsWith(mainUrl, true)) players.add(url)
            }
            Regex("""https?://[^"'\\s<>]+""", RegexOption.IGNORE_CASE).findAll(cleaned)
                .map { it.value.trimEnd('\\', '"', '\'', ')', ']', ';') }
                .mapNotNull { playerUrl(it, base) }
                .filter { !isIgnoredPlayer(it) && !isTrailerPlayer(it) && !it.startsWith(mainUrl, true) }
                .forEach(players::add)
        }

        document.select(
            "iframe[src], iframe[data-src], iframe[data-url], iframe[data-iframe], iframe[data-frame], frame[src], " +
                "video[src], video[data-src], video[data-url], video source[src], video source[data-src], video source[data-url], " +
                "a[href], a[data-url], a[data-embed], a[data-frame], a[data-video], a[data-player], button[data-url], " +
                "button[data-embed], button[data-frame], button[data-video], button[data-player], [onclick], [data-url], " +
                "[data-embed], [data-frame], [data-video], [data-player]"
        ).forEach { element ->
            Log.d("HintFilmIzle", "ELEMENT tag=${element.tagName()} class=${element.className()} id=${element.id()}")
            listOf(
                element.attr("href"), element.attr("src"), element.attr("data-src"), element.attr("data-url"),
                element.attr("data-embed"), element.attr("data-frame"), element.attr("data-video"), element.attr("data-player"),
                element.attr("data-iframe"), element.attr("onclick")
            ).forEach { addUrl(it) }
        }

        Regex("""https?:\\?/\\?/[^"'\\s<>]+""", RegexOption.IGNORE_CASE).findAll(document.html())
            .forEach { addUrl(it.value.replace("\\/", "/")) }

        // Rendex creates the actual Kinescope CDN embed dynamically. The legacy
        // player.hintfilmizle.com hostname is NXDOMAIN, so do not resolve it.
        // Browser captures show the generated embed as:
        // https://river-3-329.kinescopecdn.net/<publisher>/embed/<video>?design=3&lang=tr
        // followed by a short-lived signed /hls/.../index.m3u8 request.
        document.select("[data-publisher-id][data-id]").forEach { element ->
            val publisherId = element.attr("data-publisher-id").trim()
            val videoId = element.attr("data-id").trim()
            val design = element.attr("data-design").trim().ifBlank { "3" }
            val playerLang = lang.ifBlank { "tr" }
            val voiceover = element.attr("data-voiceover").trim()
            if (publisherId.isNotBlank() && videoId.isNotBlank()) {
                val query = buildString {
                    append("?design=")
                    append(design)
                    append("&lang=")
                    append(playerLang)
                    if (voiceover.isNotBlank()) {
                        append("&voiceover=")
                        append(URLEncoder.encode(voiceover, "UTF-8"))
                    }
                }
                val rendexEmbed = "https://river-3-329.kinescopecdn.net/$publisherId/embed/$videoId$query"
                players.add(rendexEmbed)
                Log.d("HintFilmIzle", "KINESCOPE_RENDEX_EMBED=$rendexEmbed")
            }
        }

        Log.d("HintFilmIzle", "PLAYER_LIST=${players.joinToString(" || ")}")
        val kinescopePlayers = players.filter { it.contains("kinescope", true) || it.contains("player.hintfilmizle.com", true) }
        Log.d("HintFilmIzle", "KINESCOPE_PLAYERS=${kinescopePlayers.joinToString(" || ")}")
        val otherPlayers = players.filterNot { it.contains("kinescope", true) || it.contains("player.hintfilmizle.com", true) || isIgnoredPlayer(it) }

        for (player in kinescopePlayers) {
            if (loadKinescope(player, data, callback)) found = true
        }

        if (!found && kinescopePlayers.isEmpty()) {
            for (stream in directLinks(document.html())) {
                if (stream.contains("kinescopecdn.net", true) || isIgnoredPlayer(stream) || isTrailerPlayer(stream)) continue
                found = true
                callback(newExtractorLink(
                    source = name,
                    name = "HintFilmİzle Direct",
                    url = stream,
                    type = if (stream.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    referer = data
                    quality = getQualityFromName(stream)
                })
            }
        }

        for (player in otherPlayers) {
            val loaded = runCatching { loadExtractor(player, data, subtitleCallback, callback) }.getOrDefault(false)
            if (loaded) found = true
            val nested = runCatching { app.get(player, referer = data).document }.getOrNull()
            nested?.select("iframe[src], iframe[data-src], iframe[data-frame], iframe[data-url], video[src], video[data-src], video source[src], video source[data-src]")?.forEach { element ->
                val nestedUrl = playerUrl(element.attr("src").ifBlank { element.attr("data-src") }.ifBlank { element.attr("data-frame") }.ifBlank { element.attr("data-url") }, player) ?: return@forEach
                if (nestedUrl == player || nestedUrl.startsWith(mainUrl, true) || isIgnoredPlayer(nestedUrl) || isTrailerPlayer(nestedUrl)) return@forEach
                if (runCatching { loadExtractor(nestedUrl, player, subtitleCallback, callback) }.getOrDefault(false)) found = true
            }
            directLinks(nested?.html().orEmpty()).forEach { stream ->
                if (stream.contains("kinescopecdn.net", true) || isIgnoredPlayer(stream)) return@forEach
                if (stream.contains(".m3u8", true)) {
                    val links = runCatching {
                        M3u8Helper.generateM3u8(
                            source = name, streamUrl = stream, referer = player,
                            headers = mapOf("Referer" to player, "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36", "Accept" to "*/*"),
                            name = "HintFilmİzle"
                        )
                    }.getOrDefault(emptyList())
                    if (links.isNotEmpty()) { links.forEach(callback); found = true }
                } else {
                    callback(newExtractorLink(source = name, name = "HintFilmİzle Direct", url = stream, type = ExtractorLinkType.VIDEO) { referer = player; quality = getQualityFromName(stream) })
                    found = true
                }
            }
        }
        return found
    }
}
