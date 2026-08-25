package com.nikyokki

import Video
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class HDFilmIzle : MainAPI() {
    override var mainUrl = "https://www.hdfilmizle.vip"
    override var name = "HDFilmİzle"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "${mainUrl}/tur/aile-1/" to "Aile Filmleri",
        "${mainUrl}/tur/aksiyon-1/" to "Aksiyon Filmleri",
        "${mainUrl}/tur/animasyon-1/" to "Animasyon Filmleri",
        "${mainUrl}/tur/belgesel-1/" to "Belgesel Filmleri",
        "${mainUrl}/tur/bilim-kurgu-1/" to "Bilim Kurgu Filmleri",
        "${mainUrl}/tur/dram-1/" to "Dram Filmleri",
        "${mainUrl}/tur/fantastik-1/" to "Fantastik Filmleri",
        "${mainUrl}/tur/gerilim-1/" to "Gerilim Filmleri",
        "${mainUrl}/tur/gizem-1/" to "Gizem Filmleri",
        "${mainUrl}/tur/komedi-1/" to "Komedi Filmleri",
        "${mainUrl}/tur/korku-1/" to "Korku Filmleri",
        "${mainUrl}/tur/macera-1/" to "Macera Filmleri",
        "${mainUrl}/tur/muzik-1/" to "Müzik Filmleri",
        "${mainUrl}/tur/romantik-1/" to "Romantik Filmler",
        "${mainUrl}/tur/savas-1/" to "Savaş Filmleri",
        "${mainUrl}/tur/suc-1/" to "Suç Filmleri",
        "${mainUrl}/tur/tarih-1/" to "Tarih Filmleri",
        "${mainUrl}/tur/vahsi-bati-1/" to "Vahşi Batı Filmleri",
        "${mainUrl}/tur/yerli-film-izle-1/" to "Yerli Filmler",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = if (page <= 1) request.data else request.data.replace(Regex("-1/?$"), "-$page/")
        val document = app.get(pageUrl).document
        val home = document.select("div#moviesListResult a.poster, a.poster").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse {
        val title = this.selectFirst("h2.title")?.text()?.trim()
            ?: this.selectFirst("h2")?.text()?.trim()
            ?: this.selectFirst("img")?.attr("alt")?.trim() ?: ""
        val href = fixUrlNull(this.attr("href")) ?: return newMovieSearchResponse(title, mainUrl, TvType.Movie)
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src"))
            ?: fixUrlNull(this.selectFirst("img")?.attr("src"))
        val score = this.selectFirst(".poster-imdb")?.text()?.trim()
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.score = Score.from10(score)
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.post(
            "$mainUrl/search/",
            headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
            referer = mainUrl,
            data = mapOf("query" to query)
        ).document
        val searchResults = mutableListOf<SearchResponse>()
        val objectMapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
        try {
            val videos: List<Video> = objectMapper.readValue(response.body().text())
            videos.forEach { video ->
                val href = fixUrlNull(video.slug) ?: return@forEach
                val posterUrl = fixUrlNull(video.thumbUrl) ?: fixUrlNull(video.thumbWebp)
                searchResults.add(newMovieSearchResponse(video.name, href, TvType.Movie) {
                    this.posterUrl = posterUrl
                })
            }
        } catch (e: Exception) {
            Log.e("HDF", "Search parse error: ${e.message}")
        }
        return searchResults
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val orgTitle = document.selectFirst("div.page-title h1")?.text() ?: ""
        val altTitle = document.selectFirst("div.page-title small.text-muted.alt-name")?.text() ?: ""
        val title = if (altTitle.isNotEmpty() && orgTitle != altTitle) "$orgTitle - $altTitle" else orgTitle
        val poster = fixUrlNull(document.selectFirst("picture.poster-auto img")?.attr("data-src"))
            ?: fixUrlNull(document.selectFirst("picture.poster-auto img")?.attr("src"))
        val tags = document.select("div.pb-2.genres a").map { it.text() }
        val year = document.selectFirst("div.page-title small.text-muted")?.text()
            ?.replace("(", "")?.replace(")", "")?.toIntOrNull()
        val description = document.selectFirst("article.text-white > p")?.text()?.trim()
        val rating = document.selectFirst("div.rate.mb-2 span")?.text()
        val actors = document.select("div.stories-wrapper a").map {
            Actor(it.selectFirst("div.story-item-title")?.text() ?: "", fixUrlNull(it.select("img").attr("data-src")))
        }
        val recommendations = document.select("div#swiper-wrapper-benzer").mapNotNull {
            val recName = it.selectFirst("a")?.attr("title") ?: return@mapNotNull null
            val recHref = fixUrlNull(it.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
            val recPosterUrl = fixUrlNull(it.selectFirst("img")?.attr("data-src"))
                ?: fixUrlNull(it.selectFirst("img")?.attr("src"))
            newMovieSearchResponse(recName, recHref, TvType.Movie) { this.posterUrl = recPosterUrl }
        }
        val trailer = document.selectFirst("div.nav-link")?.attr("data-trailer")
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.year = year
            this.plot = description
            this.tags = tags
            this.score = Score.from10(rating)
            this.recommendations = recommendations
            addActors(actors)
            addTrailer(trailer)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (com.lagradost.cloudstream3.utils.ExtractorLink) -> Unit
    ): Boolean {
        Log.d("HDF", "data » $data")
        val document = app.get(data).document

        // Yeni sitede VidRame player iframe olarak sayfaya ekleniyor.
        // URL'nin içinde "vidrame" geçmesini şart koşmuyoruz.
        val iframe = document.select("iframe").mapNotNull { element ->
            element.attr("data-src").ifBlank { element.attr("src") }
        }.firstOrNull { it.isNotBlank() } ?: ""

        Log.d("HDF", "iframe » $iframe")
        if (iframe.isNotBlank()) {
            loadExtractor(iframe, data, subtitleCallback, callback)
            return true
        }

        // Bazı sürümlerde player URL'si düğmenin data alanında bulunabiliyor.
        val playerUrl = document.select("a,button,div").firstOrNull {
            it.text().trim().equals("Vidrame", ignoreCase = true)
        }?.let { element ->
            sequenceOf("href", "data-src", "data-url", "data-player", "data-embed", "data-iframe")
                .map { element.attr(it) }
                .firstOrNull { it.isNotBlank() }
        } ?: ""

        Log.d("HDF", "VidRame/player » $playerUrl")
        if (playerUrl.isNotBlank()) {
            loadExtractor(playerUrl, data, subtitleCallback, callback)
        }

        return true
    }

    private data class SubSource(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("kind") val kind: String? = null
    )

    data class Results(
        @JsonProperty("results") val results: List<String> = arrayListOf()
    )
}
