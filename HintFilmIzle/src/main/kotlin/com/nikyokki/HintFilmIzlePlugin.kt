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
        val raw = value?.replace("\\/", "/")?.replace("\\u0026", "&")?.trim()?.takeIf { it.isNotBlank() } ?: return null
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
        fun valid(value: String?): String? = cleanUrl(value)?.takeIf { !it.startsWith("data:image", true) && !it.contains("placeholder", true) && !it.contains("spacer", true) && !it.contains("blank.", true) }
        val attrs = listOf("data-src", "data-lazy-src", "data-lazy", "data-original", "data-original-src", "data-image", "data-poster", "data-poster-url", "data-thumb", "data-thumbnail", "data-fsrc", "data-url", "data-image-url", "data-bg", "data-background-image", "src")
        select("img, picture source").forEach { image ->
            attrs.mapNotNull { valid(image.attr(it)) }.firstOrNull()?.let { return it }
            listOf("data-srcset", "data-lazy-srcset", "srcset").forEach { attr ->
                val candidate = image.attr(attr).split(",").asReversed().map { it.trim().substringBefore(" ").trim() }.mapNotNull { valid(it) }.firstOrNull()
                if (candidate != null) return candidate
            }
        }
        return listOf("data-poster", "data-image", "data-thumb", "data-src").mapNotNull { valid(attr(it)) }.firstOrNull()
    }

    private fun Element.cardTitle(): String? = sequenceOf(selectFirst(".film-title")?.text(), selectFirst(".movie-title")?.text(), selectFirst(".entry-title")?.text(), selectFirst(".card-title")?.text(), selectFirst("h2")?.text(), selectFirst("h3")?.text(), selectFirst(".title")?.text(), selectFirst(".name")?.text(), selectFirst("img")?.attr("alt"), selectFirst("img")?.attr("title"), attr("title")).mapNotNull { it?.trim()?.replace(Regex("\\s+"), " ")?.takeIf { s -> s.isNotBlank() && !s.equals("image", true) } }.firstOrNull()

    private fun Element.toSearchResult(card: Element = this): SearchResponse? {
        val href = cleanUrl(attr("href")) ?: return null
        if (!href.startsWith(mainUrl)) return null
        val path = href.removePrefix(mainUrl).substringBefore("?").trimEnd('/')
        if (!path.startsWith("/film/") && !path.startsWith("/dizi/")) return null
        val title = card.cardTitle()?.replace(Regex("\\s+"), " ")?.trim()?.removeSuffix(" izle")?.trim()?.takeIf { it.isNotBlank() } ?: path.substringAfterLast("/").replace(Regex("[-_]+"), " ").replace(Regex("\\b\\w"), { it.value.uppercase() }).trim()
        if (title.length > 180 || title.equals("film", true) || title.equals("dizi", true) || title.equals("filmler", true)) return null
        val poster = card.posterUrl() ?: posterUrl()
        val rating = cardRating(card)
        return if (path.startsWith("/dizi/")) newTvSeriesSearchResponse(title, href, TvType.TvSeries) { posterUrl = poster; score = Score.from10(rating) } else newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster; score = Score.from10(rating) }
    }

    private fun cardRating(card: Element): String? = Regex("""(?<!\d)(?:10(?:[.,]0+)?|[1-9](?:[.,]\d{1,3})?)(?!\d)""").findAll(card.text()).mapNotNull { it.value.replace(',', '.').toFloatOrNull() }.firstOrNull { it > 0f && it <= 10f }?.toString()

    private fun extractResults(document: org.jsoup.nodes.Document): List<SearchResponse> = document.select("a[href*='/film/'], a[href*='/dizi/']").mapNotNull { anchor ->
        val card = anchor.parents().firstOrNull { it.select("a[href*='/film/'], a[href*='/dizi/']").size <= 4 && it.selectFirst("img, picture source, [style*='background'], [data-poster], [data-image]") != null && it.text().length < 1200 } ?: anchor
        anchor.toSearchResult(card)
    }.distinctBy { it.url }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = if (page <= 1) request.data else request.data.trimEnd('/') + "/page/" + page + "/"
        val document = runCatching { app.get(pageUrl, referer = "$mainUrl/", headers = browserHeaders()).document }.getOrNull() ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val results = extractResults(document)
        return newHomePageResponse(request.name, results, hasNext = results.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().takeIf { it.isNotBlank() } ?: return emptyList()
        val encoded = URLEncoder.encode(q, "UTF-8")
        for (url in listOf("$mainUrl/film?search=$encoded", "$mainUrl/film?s=$encoded", "$mainUrl/?s=$encoded", "$mainUrl/?search=$encoded", "$mainUrl/arama?q=$encoded", "$mainUrl/search?q=$encoded")) {
            val results = runCatching { extractResults(app.get(url, referer = "$mainUrl/", headers = browserHeaders()).document) }.getOrDefault(emptyList())
            if (results.isNotEmpty()) return results
        }
        return emptyList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    private fun browserHeaders() = mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36", "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8", "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7")

    private fun firstText(document: org.jsoup.nodes.Document, vararg selectors: String): String? = selectors.asSequence().mapNotNull { document.selectFirst(it)?.text()?.trim() }.firstOrNull { it.isNotBlank() }
    private fun findNumber(text: String, vararg labels: String): String? = Regex("(?:${labels.joinToString("|") { Regex.escape(it) }})\\s*[:\\-]?\\s*([0-9]+(?:[.,][0-9]+)?)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.getOrNull(1)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, referer = "$mainUrl/", headers = browserHeaders()).document
        val title = firstText(document, "h1", ".entry-title", ".film-title", ".movie-title", ".serieTitle") ?: return null
        val poster = cleanUrl(document.selectFirst("meta[property='og:image']")?.attr("content")) ?: document.selectFirst("article, .movie-detail, .film-detail, .serie-detail")?.posterUrl()
        val bodyText = document.text()
        val description = firstText(document, ".description", ".film-description", ".movie-description", ".serieDescription", ".plot", ".summary", ".synopsis", ".film-summary", ".movie-summary", ".entry-content p", ".entry-content > p")
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
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) { posterUrl = poster; this.year = year; plot = description; this.tags = tags; score = Score.from10(rating); addActors(actors); this.recommendations = recommendations }
        }
        return newMovieLoadResponse(title, url, TvType.Movie, url) { posterUrl = poster; this.year = year; plot = description; this.tags = tags; score = Score.from10(rating); addActors(actors); this.recommendations = recommendations }
    }

    @Serializable
    private data class PlaymateStreamInfo(@SerialName("sx") val sx: String? = null)

    private suspend fun loadPlaymate(playerUrl: String, parentUrl: String, callback: (ExtractorLink) -> Unit): Boolean = runCatching {
        val id = playerUrl.substringAfterLast('/').substringBefore('?').trim()
        if (id.isBlank()) return false
        val response = app.post("https://playmate.to/api/s", json = mapOf("c" to id, "d" to "web"), headers = mapOf("User-Agent" to "Mozilla/5.0 (X11; Linux x86_64; rv:153.0) Gecko/20100101 Firefox/153.0")).parsed<PlaymateStreamInfo>()
        val stream = response.sx?.trim()?.takeIf { it.startsWith("http", true) && it.contains(".m3u8", true) } ?: return false
        callback(newExtractorLink(source = name, name = "HintFilmİzle Playmate", url = stream, type = ExtractorLinkType.M3U8) { referer = parentUrl; headers = mapOf("Referer" to parentUrl); quality = getQualityFromName(stream) })
        true
    }.getOrElse { Log.e("HintFilmIzle", "PLAYMATE_FAILED", it); false }

    private fun playerUrl(value: String?, base: String): String? {
        val url = cleanUrl(value, base) ?: return null

        if (url.contains("player.hintfilmizle.com", true)) {
            val id = runCatching {
                URI(url).path.substringAfterLast("/").trim()
            }.getOrNull()?.takeIf { it.isNotBlank() }

            if (id != null) {
                return "https://kinescope.io/embed/$id?design=3&lang=tr&autoplay=1&muted=1&nc=PLACEHOLDER"
                    .replace("PLACEHOLDER", System.currentTimeMillis().toString())
            }
        }

        return url
    }

    private suspend fun loadKinescope(iframeUrl: String, parentUrl: String, callback: (ExtractorLink) -> Unit): Boolean = runCatching {
        val manifestRegex = Regex(
            """https?://[^"'\\s<>]+(?:kinescopecdn\.net|kinescope\.io)[^"'\\s<>]*\.m3u8[^"'\\s<>]*""",
            RegexOption.IGNORE_CASE
        )

        val resolver = WebViewResolver(
            interceptUrl = manifestRegex,
            additionalUrls = listOf(manifestRegex),
            userAgent = null,
            useOkhttp = false,
            timeout = 75_000L
        )

        val livePlayerUrl = runCatching {
            val separator = if (iframeUrl.contains("?")) "&" else "?"
            iframeUrl + separator + "autoplay=1&muted=1&nc=" + System.currentTimeMillis()
        }.getOrElse { iframeUrl }

        val resolved = resolver.resolveUsingWebView(
            url = livePlayerUrl,
            referer = parentUrl,
            headers = emptyMap()
        )

        val urls = buildList {
            resolved.first?.url?.toString()?.let(::add)
            addAll(resolved.second.map { it.url.toString() })
        }.distinct()

        val manifestUrl = urls.firstOrNull {
            it.contains(".m3u8", true) &&
                (it.contains("kinescopecdn.net", true) || it.contains("kinescope.io", true))
        } ?: return false

        callback(
            newExtractorLink(
                source = name,
                name = "HintFilmİzle Kinescope",
                url = manifestUrl,
                type = ExtractorLinkType.M3U8
            ) {
                referer = livePlayerUrl.substringBefore("?")
                headers = mapOf("Referer" to livePlayerUrl.substringBefore("?"))
                quality = getQualityFromName(manifestUrl)
            }
        )

        true
    }.getOrElse {
        Log.e("HintFilmIzle", "KINESCOPE_FAILED", it)
        false
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = runCatching {
            app.get(
                data,
                referer = "$mainUrl/",
                headers = browserHeaders()
            ).document
        }.getOrNull() ?: return false

        val players = linkedSetOf<String>()

        fun addPlayer(raw: String?) {
            playerUrl(raw, data)?.let { url ->
                if (url.contains("kinescope", true) || url.contains("playmate", true)) {
                    players.add(url)
                }
            }
        }

        // HintFilmIzle stores the actual player in data-frame.
        document.select("[data-frame]").forEach {
            addPlayer(it.attr("data-frame"))
        }

        document.select(
            "iframe[src], iframe[data-src], iframe[data-url], iframe[data-frame], " +
                "video[src], video source[src]"
        ).forEach { element ->
            addPlayer(element.attr("src").ifBlank { element.attr("data-src") })
        }

        document.select("a[href]").forEach {
            addPlayer(it.attr("href"))
        }

        document.select("[data-player], [data-embed], [data-video]").forEach { element ->
            addPlayer(
                element.attr("data-player")
                    .ifBlank { element.attr("data-embed") }
                    .ifBlank { element.attr("data-video") }
            )
        }

        // Some themes build data-frame inside inline JS.
        document.select("script").forEach { script ->
            Regex("""https?://[^"'\\s<>]+""", RegexOption.IGNORE_CASE)
                .findAll(script.data())
                .forEach { addPlayer(it.value) }

            Regex(
                """data-frame\\s*=\\s*["']([^"']+)["']""",
                RegexOption.IGNORE_CASE
            ).findAll(script.data()).forEach {
                addPlayer(it.groupValues.getOrNull(1))
            }
        }

        Regex(
            """data-frame\\s*=\\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).findAll(document.html()).forEach {
            addPlayer(it.groupValues.getOrNull(1))
        }

        var found = false

        for (player in players) {
            when {
                player.contains("kinescope", true) -> {
                    if (loadKinescope(player, data, callback)) found = true
                }

                player.contains("playmate", true) -> {
                    if (loadPlaymate(player, data, callback)) found = true
                }
            }
        }

        return found
    }
}

class HintFilmIzlePlugin : com.lagradost.cloudstream3.plugins.CloudstreamPlugin() {
    override fun load(context: android.content.Context) {
        registerMainAPI(HintFilmIzle())
    }
}
