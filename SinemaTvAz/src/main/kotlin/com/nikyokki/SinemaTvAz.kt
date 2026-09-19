package com.nikyokki

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import org.jsoup.nodes.Element

class SinemaTvAz : MainAPI() {
    override var mainUrl              = "https://sinematv.az"
    override var name                 = "SinemaTvAz"
    override val hasMainPage          = true
    override var lang                 = "az"
    override val hasQuickSearch       = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport   = true
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/film/" to "Filmlər",
        "$mainUrl/serial/" to "Seriallar",
        "$mainUrl/mult/" to "Cizgi filmləri",
        "$mainUrl/anime/" to "Anime",
        "$mainUrl/hind-filmleri/" to "Hind filmləri",
        "$mainUrl/xarici-filmler/" to "Xarici filmlər",
        "$mainUrl/rus-filmleri/" to "Rus filmləri",
        "$mainUrl/turkce-filmler/" to "Türkçe filmler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val document = app.get(url, headers = browserHeaders, referer = "$mainUrl/").document
        val home = document.select("a.poster-item").mapNotNull { it.toMainPageResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val title     = this.selectFirst("div.poster-item__title")?.text() ?: this.attr("title") ?: return null
        val href      = fixUrlNull(this.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src") ?: this.selectFirst("img")?.attr("src"))
        
        return newMovieSearchResponse(title, href, TvType.Movie) { 
            this.posterUrl = posterUrl 
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.post(
            "$mainUrl/",
            headers = browserHeaders,
            data = mapOf(
                "do" to "search",
                "subaction" to "search",
                "story" to query
            )
        ).document

        return document.select("a.poster-item").mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title     = this.selectFirst("div.poster-item__title")?.text() ?: this.attr("title") ?: return null
        val href      = fixUrlNull(this.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src") ?: this.selectFirst("img")?.attr("src"))

        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = browserHeaders, referer = "$mainUrl/").document

        val title           = document.selectFirst("h1")?.text()?.trim() ?: return null
        val poster          = fixUrlNull(document.selectFirst("div.page__poster img")?.attr("data-src") ?: document.selectFirst("div.page__poster img")?.attr("src"))
        val description     = document.selectFirst("div.page__text")?.text()?.trim()
        val year            = document.selectFirst("div.page__year")?.text()?.trim()?.toIntOrNull()
        val tags            = document.selectFirst("span.page__meta-item--genres")?.text()?.split(",")?.map { it.trim() }
        val actorsText      = document.selectFirst("div.line-clamp:contains(В ролях:)")?.ownText() ?: ""
        val actors          = actorsText.split(",").map { Actor(it.trim()) }.filter { it.name.isNotEmpty() }
        val trailer         = fixUrlNull(document.selectFirst("div.page__trailer iframe")?.attr("data-src") ?: document.selectFirst("div.page__trailer iframe")?.attr("src"))
        val recommendations = document.select("a.poster-item").mapNotNull { it.toRecommendationResult() }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl       = poster
            this.plot            = description
            this.year            = year
            this.tags            = tags
            this.recommendations = recommendations
            addActors(actors)
            addTrailer(trailer)
        }
    }

    private fun Element.toRecommendationResult(): SearchResponse? {
        val title     = this.selectFirst("div.poster-item__title")?.text() ?: this.attr("title") ?: return null
        val href      = fixUrlNull(this.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src") ?: this.selectFirst("img")?.attr("src"))

        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data, headers = browserHeaders, referer = "$mainUrl/").document

        document.select("div.video-inside iframe, div.tabs-block__content iframe").forEach { iframe ->
            val src = iframe.attr("data-src").ifEmpty { iframe.attr("src") }
            if (src.isNotEmpty()) {
                val playerUrl = fixUrl(src) ?: return@forEach
                val playerHtml = runCatching { app.get(playerUrl, headers = browserHeaders, referer = data).text }.getOrNull().orEmpty()

                val streams = Regex(
                    "https?://[^\",'\\s<>]+(?:\\.m3u8(?:\\?[^\"',\\s<>]*)?|\\.mp4(?:\\?[^\"',\\s<>]*)?)",
                    RegexOption.IGNORE_CASE
                ).findAll(playerHtml).map { it.value }.distinct().toList()

                for (stream in streams) {
                    var fixStream = stream
                    if (fixStream.contains("cdn1.sinematv.az")) {
                        fixStream = fixStream.replace("cdn1.sinematv.az", "abyss.to")
                    }
                    callback(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = fixStream,
                            type = if (fixStream.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = playerUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }

                if (streams.isEmpty()) {
                    var fixUrl = playerUrl
                    if (fixUrl.contains("cdn1.sinematv.az")) {
                        fixUrl = fixUrl.replace("cdn1.sinematv.az", "abyss.to")
                    }
                    loadExtractor(fixUrl, data, subtitleCallback, callback)
                }
            }
        }

        return true
    }
}
