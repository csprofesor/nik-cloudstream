package com.nikyokki

import CryptoJS
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.api.Log
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.toRatingInt
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.util.regex.Pattern

class DiziMag : MainAPI() {
    override var mainUrl = "https://dizimag.one"
    override var name = "DiziMag"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 250L
    override var sequentialMainPageScrollDelay = 250L

    override val mainPage = mainPageOf(
        "${mainUrl}/dizi/tur/aile" to "Aile",
        "${mainUrl}/dizi/tur/aksiyon-macera" to "Aksiyon-Macera",
        "${mainUrl}/dizi/tur/animasyon" to "Animasyon",
        "${mainUrl}/dizi/tur/belgesel" to "Belgesel",
        "${mainUrl}/dizi/tur/bilim-kurgu-fantazi" to "Bilim Kurgu",
        "${mainUrl}/dizi/tur/dram" to "Dram",
        "${mainUrl}/dizi/tur/gizem" to "Gizem",
        "${mainUrl}/dizi/tur/komedi" to "Komedi",
        "${mainUrl}/dizi/tur/savas-politik" to "Savaş Politik",
        "${mainUrl}/dizi/tur/suc" to "Suç",
        "${mainUrl}/film/tur/aile" to "Aile Film",
        "${mainUrl}/film/tur/animasyon" to "Animasyon Film",
        "${mainUrl}/film/tur/bilim-kurgu" to "Bilim-Kurgu Film",
        "${mainUrl}/film/tur/dram" to "Dram Film",
        "${mainUrl}/film/tur/fantastik" to "Fantastik Film",
        "${mainUrl}/film/tur/gerilim" to "Gerilim Film",
        "${mainUrl}/film/tur/gizem" to "Gizem Film",
        "${mainUrl}/film/tur/komedi" to "Komedi Film",
        "${mainUrl}/film/tur/korku" to "Korku Film",
        "${mainUrl}/film/tur/macera" to "Macera Film",
        "${mainUrl}/film/tur/romantik" to "Romantik Film",
        "${mainUrl}/film/tur/savas" to "Savaş Film",
        "${mainUrl}/film/tur/suc" to "Suç Film",
        "${mainUrl}/film/tur/tarih" to "Tarih Film",
        "${mainUrl}/film/tur/vahsi-bati" to "Vahşi Batı Film"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val mainReq = app.get("${request.data}/${page}")
        val document = Jsoup.parse(mainReq.body.string())
        val home = document.select("div.poster-long").mapNotNull { it.diziler() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.diziler(): SearchResponse? {
        val title = selectFirst("div.poster-long-subject h2")?.text() ?: return null
        val href = fixUrlNull(selectFirst("div.poster-long-subject a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(selectFirst("div.poster-long-image img")?.attr("data-src"))
        return if (href.contains("/dizi/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        }
    }

    private fun Element.toPostSearchResult(): SearchResponse? {
        val title = selectFirst("span")?.text()?.trim() ?: return null
        val href = fixUrlNull(selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(selectFirst("img")?.attr("data-src"))
        return if (href.contains("/dizi/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchReq = app.post("${mainUrl}/search", data = mapOf("query" to query), headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:135.0) Gecko/20100101 Firefox/135.0",
            "Accept" to "application/json, text/javascript, */*; q=0.01",
            "X-Requested-With" to "XMLHttpRequest",
            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
            "Accept-Language" to "en-US,en;q=0.5"
        ), referer = "${mainUrl}/").parsedSafe<SearchResult>()
        if (searchReq?.success != true) throw ErrorLoadingException("Invalid Json response")
        return Jsoup.parse(searchReq.theme.toString()).select("ul li").mapNotNull { item ->
            val href = item.selectFirst("a")?.attr("href")
            if (href != null && (href.contains("/dizi/") || href.contains("/film/"))) item.toPostSearchResult() else null
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, referer = mainUrl).document
        val title = document.selectFirst("div.page-title h1")?.selectFirst("a")?.text() ?: return null
        val orgtitle = document.selectFirst("div.page-title p")?.text() ?: ""
        val poster = fixUrlNull(document.selectFirst("div.series-profile-image img")?.attr("src"))
        val year = document.selectFirst("h1 span")?.text()?.substringAfter("(")?.substringBefore(")")?.toIntOrNull()
        val rating = document.selectFirst("span.color-imdb")?.text()?.trim()?.toRatingInt()
        val duration = document.selectXpath("//span[text()='Süre']//following-sibling::p").text().trim().split(" ").first().toIntOrNull()
        val description = document.selectFirst("div.series-profile-summary p")?.text()?.trim()
        val tags = document.selectFirst("div.series-profile-type")?.select("a")?.map { it.text().trim() }
        val trailer = document.selectFirst("div.series-profile-trailer")?.attr("data-yt")
        val actors = document.select("div.series-profile-cast li").mapNotNull {
            val name = it.selectFirst("h5.truncate")?.text()?.trim() ?: return@mapNotNull null
            Actor(name, fixUrlNull(it.selectFirst("img")?.attr("data-src")))
        }
        if (url.contains("/dizi/")) {
            val episodes = mutableListOf<Episode>()
            var season = 1
            for (sezon in document.select("div.series-profile-episode-list")) {
                var episode = 1
                for (bolum in sezon.select("li")) {
                    val epName = bolum.selectFirst("h6.truncate a")?.text() ?: continue
                    val epHref = fixUrlNull(bolum.selectFirst("h6.truncate a")?.attr("href")) ?: continue
                    episodes.add(newEpisode(epHref) { name = epName; this.season = season; this.episode = episode++ })
                }
                season++
            }
            return newTvSeriesLoadResponse("$title - $orgtitle", url, TvType.TvSeries, episodes) {
                posterUrl = poster; this.year = year; plot = description; this.tags = tags; this.rating = rating; addActors(actors)
                if (!trailer.isNullOrBlank()) addTrailer("https://www.youtube.com/embed/$trailer")
            }
        }
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            posterUrl = poster; this.year = year; plot = description; this.tags = tags; this.rating = rating; this.duration = duration; addActors(actors)
            if (!trailer.isNullOrBlank()) addTrailer("https://www.youtube.com/embed/$trailer")
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:135.0) Gecko/20100101 Firefox/135.0",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Referer" to "$mainUrl/"
        )
        val aa = app.get(mainUrl, headers = headers)
        val ciSession = aa.cookies["ci_session"]
        val document = app.get(data, headers = headers, cookies = ciSession?.let { mapOf("ci_session" to it) } ?: emptyMap()).document
        var found = false
        document.select("video source, video[src], source[src]").forEach { el ->
            val src = fixUrlNull(el.attr("src")) ?: return@forEach
            if (src.contains(".m3u8") || src.contains(".mp4")) {
                callback(newExtractorLink(name, name, src, if (src.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) { this.headers = headers; referer = mainUrl; quality = Qualities.Unknown.value })
                found = true
            }
        }
        document.select("iframe[src], iframe[data-src], [data-player], [data-embed]").forEach { el ->
            val src = fixUrlNull(el.attr("src").ifBlank { el.attr("data-src") }.ifBlank { el.attr("data-player") }.ifBlank { el.attr("data-embed") }) ?: return@forEach
            if (src.startsWith("http")) {
                loadExtractor(src, mainUrl, subtitleCallback, callback)
                found = true
            }
        }
        document.select("a[href]").forEach { el ->
            val href = fixUrlNull(el.attr("href")) ?: return@forEach
            if (href.contains(".m3u8") || href.contains(".mp4")) {
                callback(newExtractorLink(name, name, href, if (href.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) { this.headers = headers; referer = mainUrl; quality = Qualities.Unknown.value })
                found = true
            }
        }
        return found
    }
}
