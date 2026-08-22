package com.nikyokki

import android.util.Log
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
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class FilmKovasi : MainAPI() {
    override var mainUrl = "https://filmkovasi.co"
    override var name = "FilmKovası"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "${mainUrl}/filmizle/aile/" to "Aile",
        "${mainUrl}/filmizle/aksiyon-hd/" to "Aksiyon",
        "${mainUrl}/filmizle/animasyon/" to "Animasyon",
        "${mainUrl}/filmizle/belgesel-hd/" to "Belgesel",
        "${mainUrl}/filmizle/bilim-kurgu/" to "Bilim Kurgu",
        "${mainUrl}/filmizle/dram-hd/" to "Dram",
        "${mainUrl}/filmizle/fantastik-hd/" to "Fantastik",
        "${mainUrl}/filmizle/gerilim/" to "Gerilim",
        "${mainUrl}/filmizle/gizem/" to "Gizem",
        "${mainUrl}/filmizle/komedi-hd/" to "Komedi",
        "${mainUrl}/filmizle/korku/" to "Korku",
        "${mainUrl}/filmizle/macera-hd/" to "Macera",
        "${mainUrl}/filmizle/romantik-hd/" to "Romantik",
        "${mainUrl}/filmizle/savas-hd/" to "Savaş",
        "${mainUrl}/filmizle/suc-hd/" to "Suç",
        "${mainUrl}/filmizle/tarih/" to "Tarih",
        "${mainUrl}/filmizle/vahsi-bati-hd/" to "Vahşi Batı",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}page/$page").document
        val home = document.select("div.movie-box").mapNotNull { it.toMainPageResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.imageUrl(): String? {
        val attrs = listOf("data-src", "data-lazy-src", "data-original", "data-image", "src")
        for (attr in attrs) {
            val value = attr(attr).trim()
            if (value.isNotBlank() && !value.startsWith("data:image")) return fixUrlNull(value)
        }
        val srcset = attr("srcset").substringBefore(',').trim().split(' ').firstOrNull()
        return srcset?.takeIf { it.isNotBlank() }?.let { fixUrlNull(it) }
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val title = this.selectFirst("div.film-ismi a, .film-ismi a, a")?.text()?.replace(" izle", "")?.trim().orEmpty()
        val href = fixUrlNull(this.selectFirst("div.film-ismi a, .film-ismi a, a")?.attr("href")) ?: return null
        val posterUrl = this.selectFirst("div.poster img, .poster img, img")?.imageUrl()
        if (title.isBlank()) return null
        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/?s=${query}").document
        return document.select("div.movie-box, article, .movie-item").mapNotNull { it.toMainPageResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1.title-border, h1, .title-border")?.text()?.replace(" izle", "")?.trim() ?: return null
        val poster = document.selectFirst("div.film-afis img, .film-afis img, .poster img, .film-poster img")?.imageUrl()
        val description = document.selectFirst("div#film-aciklama, #film-aciklama, .film-aciklama")?.text()?.trim()
        var year = document.selectFirst("div.release a")?.text()?.trim()?.toIntOrNull()
        val tags = document.select("div#listelements a, #listelements a").map { it.text() }
        var rating = document.selectFirst("div.imdb")?.text()?.replace("IMDb Puanı:", "")?.split("/")?.first()?.trim()
        var actors = document.select("div.actor a").map { it.text() }
        val trailer = document.selectFirst("div.film-afis iframe")?.let { iframe -> fixUrlNull(iframe.attr("src").ifBlank { iframe.attr("data-src") }) }
        val listItems = document.select("div.list-item")
        for (item in listItems) {
            if (item.selectFirst("a")?.attr("href")?.contains("/yil/") == true) year = item.selectFirst("a")?.text()?.toIntOrNull()
            if (item.selectFirst("a")?.attr("href")?.contains("/oyuncu/") == true) actors = item.select("a").map { it.text() }
        }
        document.select("div#listelements div, #listelements div").forEach {
            if (it.text().contains("IMDb:")) rating = it.text().trim().split(" ").last()
        }
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = description
            this.year = year
            this.tags = tags
            this.score = Score.from10(rating)
            addActors(actors)
            addTrailer(trailer)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val candidates = linkedSetOf<String>()

        fun addCandidate(value: String?) {
            val url = value?.trim()?.let { fixUrlNull(it) } ?: return
            if (url.isBlank() || url.startsWith("javascript:") || url.startsWith("#")) return
            val lower = url.lowercase()
            if (lower.contains("filmkovasi.co") && !lower.contains("/embed") && !lower.contains("/player") && !lower.contains("/watch")) return
            if (lower.contains("vidsrc") || lower.contains("vidplay") || lower.contains("filemoon") || lower.contains("streamtape") ||
                lower.contains("streamwish") || lower.contains("dood") || lower.contains("mixdrop") || lower.contains("voe") ||
                lower.contains("vk.com") || lower.contains("vkvideo") || lower.contains("ok.ru") || lower.contains("mail.ru") ||
                lower.contains("2embed") || lower.contains("autoembed") || lower.contains("embed") || lower.contains("player") ||
                lower.contains("iframe") || lower.contains("m3u8") || lower.endsWith(".mp4")
            ) candidates += url
        }

        document.select("iframe[src], iframe[data-src], video source[src], source[src], a[href], [data-src], [data-url], [data-embed], [data-link], [data-p]").forEach { element ->
            addCandidate(element.attr("src"))
            addCandidate(element.attr("data-src"))
            addCandidate(element.attr("data-url"))
            addCandidate(element.attr("data-embed"))
            addCandidate(element.attr("data-link"))
            addCandidate(element.attr("href"))
        }

        // Some providers are injected into JavaScript instead of a normal iframe element.
        val html = document.html()
        Regex("https?://[^\\\"'\\s<>]+", RegexOption.IGNORE_CASE).findAll(html).forEach { match -> addCandidate(match.value) }

        Log.d("FKV", "video candidates = ${candidates.size}")
        var loaded = false
        candidates.forEachIndexed { index, candidate ->
            try {
                if (candidate.contains(".m3u8") || candidate.endsWith(".mp4")) {
                    callback.invoke(
                        ExtractorLink(
                            source = "FilmKovası",
                            name = "FilmKovası ${index + 1}",
                            url = candidate,
                            referer = data,
                            quality = Qualities.Unknown.value,
                            type = if (candidate.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        )
                    )
                    loaded = true
                } else {
                    loadExtractor(candidate, data, subtitleCallback, callback)
                    loaded = true
                }
            } catch (_: Throwable) {
                // Try the next provider; one dead provider must not stop the others.
            }
        }

        return loaded
    }
}
