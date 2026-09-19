// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.Score
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class FullHDFilm : MainAPI() {
    override var mainUrl              = "https://fullfilmizle.fit"
    override var name                 = "FullHDFilm"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries)

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    // ! CloudFlare v2
    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor      by lazy { CloudflareInterceptor(cloudflareKiller) }

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller): Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request  = chain.request()
            val response = chain.proceed(request)
            val doc      = Jsoup.parse(response.peekBody(1024 * 1024).string())

            if (doc.html().contains("Just a moment")) {
                return cloudflareKiller.intercept(chain)
            }

            return response
        }
    }

    override val mainPage = mainPageOf(
        "${mainUrl}/filmizle/turkce-altyazili-filmler/"  to "Altyazılı Filmler",
        "${mainUrl}/filmizle/turkce-dublaj-film/"       to "Türkçe Dublaj",
        "${mainUrl}/filmizle/yerli-filmler/"            to "Yerli Film",
        "${mainUrl}/filmizle/vizyon-filmleri/"          to "Vizyon Filmleri",
        "${mainUrl}/filmizle/aile-filmleri/"            to "Aile",
        "${mainUrl}/filmizle/aksiyon-filmleri/"         to "Aksiyon",
        "${mainUrl}/filmizle/animasyon-filmleri/"       to "Animasyon",
        "${mainUrl}/filmizle/belgesel/"                 to "Belgesel",
        "${mainUrl}/filmizle/bilim-kurgu-filmleri/"     to "Bilim Kurgu",
        "${mainUrl}/filmizle/biyografi-filmleri/"       to "Biyografi",
        "${mainUrl}/filmizle/dram-filmleri/"            to "Dram",
        "${mainUrl}/filmizle/fantastik-filmler/"        to "Fantastik",
        "${mainUrl}/filmizle/gerilim-filmleri/"         to "Gerilim",
        "${mainUrl}/filmizle/gizem-filmleri/"           to "Gizem",
        "${mainUrl}/filmizle/komedi-filmleri/"          to "Komedi",
        "${mainUrl}/filmizle/korku-filmleri/"           to "Korku",
        "${mainUrl}/filmizle/macera-filmleri/"          to "Macera",
        "${mainUrl}/filmizle/muzikal-filmler/"          to "Müzikal",
        "${mainUrl}/filmizle/romantik-filmler/"         to "Romantik",
        "${mainUrl}/filmizle/savas-filmleri/"           to "Savaş",
        "${mainUrl}/filmizle/spor-filmleri/"            to "Spor",
        "${mainUrl}/filmizle/suc-filmleri/"             to "Suç",
        "${mainUrl}/filmizle/tarih-filmleri/"           to "Tarih",
        "${mainUrl}/filmizle/genclik-filmleri/"         to "Gençlik",
        "${mainUrl}/filmizle/yesilcam-filmleri/"        to "Yeşilçam"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}/page/${page}"
        val document = app.get(url, headers = headers, interceptor = interceptor).document
        val movieBoxes = document.select("div.film-box")
        val home = movieBoxes.mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title     = this.selectFirst("img")?.attr("alt") ?: this.selectFirst("div.name a")?.text() ?: return null
        val href      = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src") ?: this.selectFirst("img")?.attr("data-src"))
        val rating    = this.selectFirst("div.rating span.align-right, div.rating")?.text()?.trim()?.toFloatOrNull()

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.score = rating?.let { Score.from10(it) }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/?s=${query}", headers = headers, interceptor = interceptor).document

        return document.select("div.film-box").mapNotNull { it.toSearchResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = headers, interceptor = interceptor).document
    
        val title       = document.selectFirst("h1")?.text() ?: document.selectFirst("meta[property='og:title']")?.attr("content") ?: return null
        val poster      = fixUrlNull(document.selectFirst("div.poster img")?.attr("src") ?: document.selectFirst("div.poster img")?.attr("data-src") ?: document.selectFirst("meta[property='og:image']")?.attr("content"))
        val description = document.selectFirst("div.description")?.text()?.trim() ?: document.selectFirst("div.film")?.text()?.trim() ?: document.selectFirst("meta[property='og:description']")?.attr("content")?.trim()
        val tags        = document.select("ul.post-categories li a, div.tur.info a").map { it.text() }
        val year        = Regex("""(\d{4})""").find(document.selectFirst("li.release span, div.yayin-tarihi.info, div.category")?.text() ?: "")?.groupValues?.get(1)?.toIntOrNull()
        val rating      = document.selectFirst("div.imdb-count")?.text()?.substringBefore(" ")?.trim()?.toFloatOrNull()
        val actors      = document.select("div.actors.list a, div.cast a").map { Actor(it.text()) }

        val recommendations = document.select("div.related-movies div.movie-box").mapNotNull { el ->
            val recName = el.selectFirst("img")?.attr("alt") ?: el.selectFirst("div.name a")?.text() ?: return@mapNotNull null
            val recHref = fixUrlNull(el.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
            val recPoster = fixUrlNull(el.selectFirst("img")?.attr("src") ?: el.selectFirst("img")?.attr("data-src"))
            val recRating = el.selectFirst("div.rating span.align-right, div.rating")?.text()?.trim()?.toFloatOrNull()
            newMovieSearchResponse(recName, recHref, TvType.Movie) {
                this.posterUrl = recPoster
                this.score = recRating?.let { Score.from10(it) }
            }
        }

        val isSeries = url.lowercase().contains("-dizi") || tags.any { it.lowercase().contains("dizi") }

        if (isSeries) {
            val episodes = document.select("li.psec").mapNotNull { el ->
                val partId = el.attr("id")
                val partName = el.text().trim()
                if (partName.lowercase().contains("fragman")) return@mapNotNull null
                
                // Basit Sezon/Bölüm çıkarımı
                val s = Regex("""(\d+)\.\s*Sezon""").find(partName)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val e = Regex("""(\d+)\.\s*Bölüm""").find(partName)?.groupValues?.get(1)?.toIntOrNull() ?: 1

                newEpisode(url) {
                    this.name = partName
                    this.season = s
                    this.episode = e
                }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = rating?.let { Score.from10(it) }
                this.recommendations = recommendations
                this.actors = actors.map { ActorData(it) }
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.year = year
            this.plot = description
            this.tags = tags
            this.score = rating?.let { Score.from10(it) }
            this.recommendations = recommendations
            this.actors = actors.map { ActorData(it) }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val mainDoc = app.get(data, headers = headers, interceptor = interceptor).document
        
        val pageLinks = mutableListOf<Pair<String, String>>()
        val firstPartName = mainDoc.selectFirst("div.keremiya_part span")?.text()?.trim() ?: "Tek Part"
        pageLinks.add(firstPartName to data)

        mainDoc.select("div.keremiya_part a[href], .post-page-numbers[href]").forEach {
            val href = fixUrlNull(it.attr("href")) ?: return@forEach
            val partName = it.text().trim()
            if (!pageLinks.any { p -> p.second == href }) {
                pageLinks.add((if (partName.isNotBlank()) partName else "Alternatif") to href)
            }
        }

        Log.d("FullHDFilm", "Pages/Parts to process: ${pageLinks.map { it.second }}")
        var foundLinks = false

        for ((name, pageUrl) in pageLinks) {
            try {
                val doc = if (pageUrl == data) mainDoc else app.get(pageUrl, headers = headers, interceptor = interceptor).document
                val iframeSrc = fixUrlNull(doc.selectFirst("div.video-content iframe, iframe")?.attr("src")) ?: continue

                Log.d("FullHDFilm", "Found iframe for $name: $iframeSrc")

                if (loadExtractor(iframeSrc, data, subtitleCallback, callback)) {
                    foundLinks = true
                }
            } catch (e: Exception) {
                Log.e("FullHDFilm", "Error loading links for $pageUrl", e)
            }
        }

        return foundLinks
    }
}
