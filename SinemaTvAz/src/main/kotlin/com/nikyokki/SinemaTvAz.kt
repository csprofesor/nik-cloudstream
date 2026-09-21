package com.nikyokki

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import org.jsoup.Jsoup
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
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "https://sinematv.az/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/film/" to "Filmlər",
        "$mainUrl/serial/" to "Seriallar",
        "$mainUrl/mult/" to "Cizgi filmləri",
        "$mainUrl/anime/" to "Anime",
        "$mainUrl/dokumentalnyj/" to "Belgesel",
        "$mainUrl/thriller/" to "Gerilim",
        "$mainUrl/4k-filmy-i-serialy/" to "4K",
        "$mainUrl/crime/" to "Suç",
        "$mainUrl/biografiya/" to "Biyografi",
        "$mainUrl/adventures/" to "Maceralar",
        "$mainUrl/fantastic/" to "Bilimkurgu",
        "$mainUrl/hind-filmleri/" to "Hind filmləri",
        "$mainUrl/xarici-filmler/" to "Xarici filmlər",
        "$mainUrl/rus-filmleri/" to "Rus filmləri",
        "$mainUrl/turkce-filmler/" to "Türkçe filmler"
    )

    private fun Element.getImgUrl(): String? {
        val dataSrc = this.attr("data-src").takeIf { it.isNotBlank() }
        val dataOrig = this.attr("data-original").takeIf { it.isNotBlank() }
        val src = this.attr("src").takeIf { it.isNotBlank() }
        return dataSrc ?: dataOrig ?: src
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val html = app.get(url, headers = browserHeaders, referer = "$mainUrl/").text
        
        val tvType = when {
            request.data.contains("/serial") -> TvType.TvSeries
            request.data.contains("/mult") -> TvType.TvSeries
            request.data.contains("/anime") -> TvType.Anime
            else -> TvType.Movie
        }

        // Only parse the items within the main content block, ignoring the popular sidebar
        val contentBlock = Regex("""id="dle-content"[^>]*>(.*?)(?:<div class="pagination|<!-- dle_content -->)""", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1) ?: html
        val document = Jsoup.parse(contentBlock)
        
        val home = document.select("a.poster-item").mapNotNull { it.toMainPageResult(tvType) }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toMainPageResult(type: TvType = TvType.Movie): SearchResponse? {
        val title     = this.selectFirst("div.poster-item__title")?.text() ?: this.attr("title") ?: return null
        val href      = fixUrlNull(this.attr("href")) ?: return null
        val img       = this.selectFirst("img")
        val posterUrl = fixUrlNull(img?.getImgUrl())
        
        return if (type == TvType.Movie) {
            newMovieSearchResponse(title, href, type) { 
                this.posterUrl = posterUrl 
                this.posterHeaders = browserHeaders
            }
        } else {
            newTvSeriesSearchResponse(title, href, type) { 
                this.posterUrl = posterUrl 
                this.posterHeaders = browserHeaders
            }
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

        return document.select("div#dle-content a.poster-item, div.search-results a.poster-item, a.poster-item").mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title     = this.selectFirst("div.poster-item__title")?.text() ?: this.attr("title") ?: return null
        val href      = fixUrlNull(this.attr("href")) ?: return null
        val img       = this.selectFirst("img")
        val posterUrl = fixUrlNull(img?.getImgUrl())

        val tvType = if (href.contains("/serial") || href.contains("/mult")) TvType.TvSeries else TvType.Movie

        return if (tvType == TvType.Movie) {
            newMovieSearchResponse(title, href, tvType) { 
                this.posterUrl = posterUrl 
                this.posterHeaders = browserHeaders
            }
        } else {
            newTvSeriesSearchResponse(title, href, tvType) { 
                this.posterUrl = posterUrl 
                this.posterHeaders = browserHeaders
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = browserHeaders, referer = "$mainUrl/").document

        val title           = document.selectFirst("h1")?.text()?.trim() ?: return null
        val posterImg       = document.selectFirst("div.page__poster img")
        val poster          = fixUrlNull(posterImg?.getImgUrl())
        val description     = document.selectFirst("div.page__text")?.text()?.trim()
        val year            = document.selectFirst("div.page__year")?.text()?.trim()?.toIntOrNull()
        val tags            = document.selectFirst("span.page__meta-item--genres")?.text()?.split(",")?.map { it.trim() }
        val actorsText      = document.selectFirst("div.line-clamp:contains(В ролях:)")?.ownText() ?: ""
        val actors          = actorsText.split(",").map { Actor(it.trim()) }.filter { it.name.isNotEmpty() }
        val trailer         = fixUrlNull(document.selectFirst("div.page__trailer iframe")?.attr("data-src")?.takeIf { it.isNotBlank() } ?: document.selectFirst("div.page__trailer iframe")?.attr("src"))
        val recommendations = document.select("div#owl-related a.poster-item, div.sect__content a.poster-item").mapNotNull { it.toRecommendationResult() }

        val isSeries        = url.contains("/serial/") || document.select("select#season").isNotEmpty() || document.select("div.serial-tabs").isNotEmpty()

        return if (isSeries) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, emptyList()) {
                this.posterUrl       = poster
                this.posterHeaders   = browserHeaders
                this.plot            = description
                this.year            = year
                this.tags            = tags
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl       = poster
                this.posterHeaders   = browserHeaders
                this.plot            = description
                this.year            = year
                this.tags            = tags
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
            }
        }
    }

    private fun Element.toRecommendationResult(): SearchResponse? {
        val title     = this.selectFirst("div.poster-item__title")?.text() ?: this.attr("title") ?: return null
        val href      = fixUrlNull(this.attr("href")) ?: return null
        val img       = this.selectFirst("img")
        val posterUrl = fixUrlNull(img?.getImgUrl())

        val tvType = if (href.contains("/serial") || href.contains("/mult")) TvType.TvSeries else TvType.Movie

        return if (tvType == TvType.Movie) {
            newMovieSearchResponse(title, href, tvType) { 
                this.posterUrl = posterUrl 
                this.posterHeaders = browserHeaders
            }
        } else {
            newTvSeriesSearchResponse(title, href, tvType) { 
                this.posterUrl = posterUrl 
                this.posterHeaders = browserHeaders
            }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data, headers = browserHeaders, referer = "$mainUrl/").document

        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("data-src").ifEmpty { iframe.attr("src") }
            val title = iframe.attr("title")

            if (title.contains("Трейлер", ignoreCase = true) || title.contains("Trailer", ignoreCase = true)) {
                return@forEach
            }

            if (src.isEmpty() || src.contains("googletagmanager") || src.contains("yandex") || src.contains("facebook")) {
                return@forEach
            }

            val playerUrl = fixUrl(src) ?: return@forEach
            val context = SinemaTvAzPlugin.pluginContext
            if (context != null) {
                SinemaTvAzWebViewExtractor(context).getUrl(playerUrl, data, subtitleCallback, callback)
            } else {
                loadExtractor(playerUrl, data, subtitleCallback, callback)
            }
        }

        return true
    }
}
