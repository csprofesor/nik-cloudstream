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
        val document = app.get(url).document
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
        val document = app.get(url).document

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
        val document = app.get(data).document

        document.select("div.video-inside iframe, div.tabs-block__content iframe").forEach { iframe ->
            val src = iframe.attr("data-src").ifEmpty { iframe.attr("src") }
            if (src.isNotEmpty()) {
                var fixUrl = fixUrl(src)
                if (fixUrl.contains("cdn1.sinematv.az")) {
                    fixUrl = fixUrl.replace("cdn1.sinematv.az", "abyss.to")
                }
                loadExtractor(fixUrl, "$mainUrl/", subtitleCallback, callback)
            }
        }

        return true
    }
}