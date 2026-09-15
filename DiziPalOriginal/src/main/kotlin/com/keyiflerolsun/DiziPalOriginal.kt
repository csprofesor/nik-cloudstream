package com.keyiflerolsun

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document
import java.net.URLEncoder

class DiziPalOriginal : MainAPI() {
    override var mainUrl = "https://dizipal1430.com"
    override var name = "DiziPalOriginal"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)
    override var sequentialMainPage = true

    override val mainPage = mainPageOf(
        "$mainUrl/bolumler" to "Son Bölümler",
        "$mainUrl/diziler" to "Yeni Diziler",
        "$mainUrl/filmler" to "Yeni Filmler",
        "$mainUrl/platform/netflix" to "Netflix",
        "$mainUrl/platform/exxen" to "Exxen",
        "$mainUrl/platform/blutv" to "BluTV",
        "$mainUrl/platform/disney-plus" to "Disney+",
        "$mainUrl/platform/prime-video" to "Amazon Prime",
        "$mainUrl/platform/tabii" to "Tabii",
        "$mainUrl/platform/gain" to "Gain",
        "$mainUrl/platform/max" to "Max"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val items = if (request.data.contains("/bolumler")) {
            document.select("div.episodes-list-grid > a.episode-list-item, .episode-list-item").mapNotNull { it.toEpisodeSearch() }
        } else {
            document.select("ul.content-grid > li, article.type2 ul li").mapNotNull { it.toSearch() }
        }
        return newHomePageResponse(request.name, items, false)
    }

    private fun Element.posterUrl(): String? {
        val img = selectFirst("img") ?: return null
        val raw = listOf("data-src", "data-lazy-src", "data-original", "src")
            .asSequence()
            .map { img.attr(it).trim() }
            .firstOrNull { it.isNotEmpty() }
        return raw?.let { fixUrlNull(it) }
    }

    private fun Element.imdbScore(): Score? {
        val text = text().replace(',', '.')
        val value = Regex("(?i)(?:IMDb|IMDB)\\s*[:\\-]?\\s*([0-9]+(?:\\.[0-9]+)?)").find(text)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
            ?: Regex("(?i)([0-9]+(?:\\.[0-9]+)?)\\s*(?:IMDb|IMDB)").find(text)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        return value?.takeIf { it in 0.0..10.0 }?.let { Score.from10(it) }
    }

    private fun Element.toSearch(): SearchResponse? {
        val title = selectFirst("div.card-info h3")?.text()?.trim()
            ?: selectFirst("h3")?.text()?.trim()
            ?: selectFirst(".title")?.text()?.trim()
            ?: return null
        val href = fixUrlNull(selectFirst("a")?.attr("href")) ?: return null
        val poster = posterUrl()
        val score = imdbScore()
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            posterUrl = poster
            this.score = score
        }
    }

    private fun Element.toEpisodeSearch(): SearchResponse? {
        val name = selectFirst(".ep-title")?.text()?.trim() ?: return null
        val info = selectFirst(".ep-info")?.text()?.trim() ?: ""
        val href = fixUrlNull(attr("href")) ?: return null
        val poster = posterUrl()
        val score = imdbScore()
        val title = "$name ${info.replace(". Sezon ", "x").replace(". Bölüm", "")}"
        val seriesUrl = href.replace(Regex("-\\d+-sezon-\\d+-bolum.*$"), "").replace("/bolum/", "/dizi/")
        return newTvSeriesSearchResponse(title, seriesUrl, TvType.TvSeries) {
            posterUrl = poster
            this.score = score
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/diziler?kelime=${URLEncoder.encode(query, "UTF-8")}&durum=&tur=&type=&siralama="
        return app.get(url).document.select("ul.content-grid > li, article.type2 ul li").mapNotNull { it.toSearch() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    private fun Document.pagePoster(): String? = fixUrlNull(selectFirst("meta[property='og:image']")?.attr("content"))

    private fun Document.pageYear(): Int? {
        val labelled = select("div.info-row").firstOrNull { it.text().contains("Yıl", true) }?.text()
        return Regex("\\b(19|20)\\d{2}\\b").find(labelled ?: text())?.value?.toIntOrNull()
    }

    private fun Document.pageScore(): Score? {
        val text = text().replace(',', '.')
        val value = Regex("(?i)(?:IMDb|IMDB)\\s*[:\\-]?\\s*([0-9]+(?:\\.[0-9]+)?)").find(text)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
            ?: Regex("(?i)([0-9]+(?:\\.[0-9]+)?)\\s*(?:IMDb|IMDB)").find(text)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        return value?.takeIf { it in 0.0..10.0 }?.let { Score.from10(it) }
    }

    private fun Document.pageTags(): List<String> {
        val row = select("div.info-row").firstOrNull { it.text().contains("Kategor", true) }
        return row?.select("a")?.map { it.text().trim() }?.filter { it.isNotEmpty() }?.distinct()
            ?: emptyList()
    }

    private fun Document.pageActors(): List<ActorData> {
        val heading = select("h2, h3, h4, .section-title, .title").firstOrNull {
            it.text().trim().equals("Oyuncular", true)
        }

        val roots = listOfNotNull(heading?.parent(), heading?.parent()?.parent())
        val nodes = roots.asSequence()
            .flatMap { root ->
                root.select(".cast-item, .actor-item, .cast-member, .actor, .cast-list > *, .actors-list > *, .actors > *, .cast > *, li")
                    .asSequence()
            }
            .distinct()
            .toList()

        val fallback = if (nodes.isNotEmpty()) nodes else select(".cast-item, .actor-item, .cast-member, .actor").toList()

        return fallback.mapNotNull { node ->
            val img = node.selectFirst("img")
            val image = img?.let {
                listOf("data-src", "data-lazy-src", "data-original", "src")
                    .asSequence().map { key -> it.attr(key).trim() }.firstOrNull { value -> value.isNotEmpty() }
                    ?.let { value -> fixUrlNull(value) }
            }
            val name = node.selectFirst(".actor-name, .cast-name, .name, h4, h5, strong")?.text()?.trim()
                ?: node.ownText().trim().takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            val fullText = node.text().trim()
            val role = fullText.removePrefix(name).trim().takeIf { it.isNotEmpty() && it.length < 100 }
            ActorData(Actor(name, image), roleString = role)
        }.distinctBy { it.actor.name }.take(30)
    }

    override suspend fun load(url: String): LoadResponse? {
        if (url.contains("/bolum/")) {
            val seriesUrl = url.replace("/bolum/", "/dizi/").replace(Regex("-\\d+-sezon.*"), "")
            return load(seriesUrl)
        }

        val document = app.get(url).document
        val poster = document.pagePoster()
        val year = document.pageYear()
        val plot = document.selectFirst("p.series-description, .series-description, .movie-description")?.text()?.trim()
        val tags = document.pageTags()
        val score = document.pageScore()
        val actors = document.pageActors()

        if (url.contains("/dizi/")) {
            val title = document.selectFirst("h1.series-title, h1")?.text()?.trim() ?: return null
            val episodes = document.select("div.detail-episode-item-wrap, .detail-episode-item-wrap").mapNotNull { wrap ->
                val a = wrap.selectFirst("a.detail-episode-item, a") ?: return@mapNotNull null
                val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
                val name = a.selectFirst("div.detail-episode-title, .detail-episode-title")?.text()?.trim()
                    ?: a.text().trim()
                val subtitle = a.selectFirst("div.detail-episode-subtitle, .detail-episode-subtitle")?.text()?.trim() ?: ""
                val match = Regex("""(\\d+)\\.\\s*[Ss]ezon\\s*(\\d+)\\.\\s*[Bb]ölüm""").find(subtitle)
                newEpisode(href) {
                    this.name = name
                    season = match?.groupValues?.getOrNull(1)?.toIntOrNull()
                    episode = match?.groupValues?.getOrNull(2)?.toIntOrNull()
                }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.score = score
                this.actors = actors
            }
        }

        val title = document.selectFirst("h1.series-title, h1.movie-title, h1")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" izle")?.trim()
            ?: return null
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            posterUrl = poster
            this.year = year
            this.plot = plot
            this.tags = tags
            this.score = score
            this.actors = actors
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
        val response = app.get(data, headers = mapOf("User-Agent" to ua, "Cache-Control" to "no-cache", "Pragma" to "no-cache"))
        val token = response.document.selectFirst("#videoContainer")?.attr("data-cfg")?.trim()
        if (token.isNullOrEmpty()) return false

        val padded = token + "=".repeat((4 - token.length % 4) % 4)
        val decoded = try { String(Base64.decode(padded, Base64.DEFAULT)) } catch (_: Exception) { return false }
        val embedRaw = Regex("""\"v\"\s*:\s*\"([^\"]+)\"""").find(decoded)?.groupValues?.getOrNull(1)?.replace("\\/", "/") ?: return false
        val embedUrl = fixUrl(embedRaw)

        if (embedUrl.contains("imagestoo")) {
            val videoId = embedUrl.trimEnd('/').substringAfterLast("/")
            val api = app.post(
                "https://imagestoo.com/player/index.php?data=$videoId&do=getVideo",
                referer = embedUrl,
                headers = mapOf("User-Agent" to ua, "X-Requested-With" to "XMLHttpRequest", "Accept" to "*/*")
            )
            val cookie = api.cookies["fireplayer_player"]?.let { "fireplayer_player=$it" } ?: ""
            val source = Regex("""\"securedLink\"\s*:\s*\"([^\"]+)\"""").find(api.text)?.groupValues?.getOrNull(1)
            if (source != null) {
                callback(newExtractorLink(this.name, "Dizipal (Imagestoo)", fixUrl(source.replace("\\/", "/")), ExtractorLinkType.M3U8) {
                    referer = embedUrl
                    headers = mapOf("Cookie" to cookie)
                    quality = Qualities.Unknown.value
                })
                return true
            }
        }

        val sourceHtml = app.get(embedUrl, referer = data, headers = mapOf("User-Agent" to ua)).text
        val match = Regex("""sources\\s*:\\s*\\[\\s*\\{\\s*file\\s*:\\s*[\"']([^\"']+\\.m3u8.*?)[\"']""").find(sourceHtml)
            ?: Regex("""v\\s*:\\s*[\"']([^\"']+\\.html.*?)[\"']""").find(sourceHtml)
        val extracted = match?.groupValues?.getOrNull(1) ?: return false
        val finalUrl = if (extracted.contains(".html")) {
            val id = Regex("""embed-([^.]+)\\.html""").find(extracted)?.groupValues?.getOrNull(1) ?: return false
            "https://s2.superadjacentsoddenly.xyz/hls2/01/00007/${id}_,n,h,.urlset/master.m3u8"
        } else extracted

        callback(newExtractorLink(this.name, "Dizipal (Ana Sunucu)", finalUrl, ExtractorLinkType.M3U8) {
            referer = embedUrl
            quality = Qualities.Unknown.value
        })

        Regex("""tracks\\s*:\\s*\\[(.*?)\\]""", RegexOption.DOT_MATCHES_ALL).find(sourceHtml)?.groupValues?.getOrNull(1)?.let { tracks ->
            Regex("""\\{(.*?)\\}""", RegexOption.DOT_MATCHES_ALL).findAll(tracks).forEach { item ->
                val text = item.groupValues[1]
                val file = Regex("""file\\s*:\\s*[\"']([^\"']+)[\"']""").find(text)?.groupValues?.getOrNull(1)
                val label = Regex("""label\\s*:\\s*[\"']([^\"']+)[\"']""").find(text)?.groupValues?.getOrNull(1) ?: "Unknown"
                if (file != null && (file.endsWith(".vtt") || file.endsWith(".srt"))) subtitleCallback(SubtitleFile(label, fixUrl(file)))
            }
        }
        return true
    }
}
