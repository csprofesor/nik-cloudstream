package com.nikyokki

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
        "${mainUrl}/filmizle/aksiyon/" to "Aksiyon",
        "${mainUrl}/filmizle/animasyon/" to "Animasyon",
        "${mainUrl}/filmizle/belgesel/" to "Belgesel",
        "${mainUrl}/filmizle/bilim-kurgu/" to "Bilim Kurgu",
        "${mainUrl}/filmizle/dram/" to "Dram",
        "${mainUrl}/filmizle/fantastik/" to "Fantastik",
        "${mainUrl}/filmizle/gerilim/" to "Gerilim",
        "${mainUrl}/filmizle/gizem/" to "Gizem",
        "${mainUrl}/filmizle/komedi/" to "Komedi",
        "${mainUrl}/filmizle/korku/" to "Korku",
        "${mainUrl}/filmizle/macera/" to "Macera",
        "${mainUrl}/filmizle/romantik/" to "Romantik",
        "${mainUrl}/filmizle/savas/" to "Savaş",
        "${mainUrl}/filmizle/suc/" to "Suç",
        "${mainUrl}/filmizle/tarih/" to "Tarih",
        "${mainUrl}/filmizle/vahsi-bati/" to "Vahşi Batı",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = if (page <= 1) request.data else "${request.data}page/$page/"
        val document = app.get(pageUrl).document
        return newHomePageResponse(
            request.name,
            document.select("div.movie-box").mapNotNull { it.toMainPageResult() }.distinctBy { it.url }
        )
    }

    private fun Element.posterUrl(): String? {
        val image = if (tagName() == "img") this else selectFirst("img") ?: return null
        listOf("data-src", "data-lazy-src", "data-original", "data-image", "src").forEach { attr ->
            val value = image.attr(attr).trim()
            if (value.isNotBlank() && !value.startsWith("data:image")) return fixUrlNull(value)
        }
        return image.attr("srcset").substringBefore(',').trim().split(" ").firstOrNull()?.let { fixUrlNull(it) }
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val link = selectFirst("div.film-ismi a[href]") ?: return null
        val href = fixUrlNull(link.attr("href")) ?: return null
        val title = link.text().replace(Regex("\\s+"), " ")
            .replace(Regex("(?i)\\s+izle$"), "").trim()
        if (title.length < 2) return null
        val poster = selectFirst("div.poster img")?.posterUrl() ?: selectFirst("img")?.posterUrl()
        return newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }
    }

    override suspend fun search(query: String): List<SearchResponse> =
        app.get("${mainUrl}/?s=${query}").document.select("div.movie-box")
            .mapNotNull { it.toMainPageResult() }.distinctBy { it.url }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1.title-border, h1, .title-border")?.text()
            ?.replace(Regex("(?i)\\s+izle$"), "")?.trim() ?: return null
        val poster = document.selectFirst("meta[property='og:image']")?.attr("content")?.let { fixUrlNull(it) }
            ?: document.selectFirst("div.film-afis img, .film-afis img, .poster img, .film-poster img")?.posterUrl()
        val description = document.selectFirst("div#film-aciklama, #film-aciklama, .film-aciklama")?.text()?.trim()
        var year = document.selectFirst("div.release a")?.text()?.trim()?.toIntOrNull()
        val tags = document.select("div#listelements a, #listelements a").map { it.text() }
        val rating = document.selectFirst("div.imdb")?.text()?.replace("IMDb Puanı:", "")?.split("/")?.first()?.trim()
        var actors = document.select("div.actor a").map { it.text() }
        val trailer = document.selectFirst("div.film-afis iframe")?.let {
            fixUrlNull(it.attr("src").ifBlank { it.attr("data-src") })
        }
        document.select("div.list-item").forEach { item ->
            if (item.selectFirst("a")?.attr("href")?.contains("/yil/") == true) {
                year = item.selectFirst("a")?.text()?.toIntOrNull()
            }
            if (item.selectFirst("a")?.attr("href")?.contains("/oyuncu/") == true) {
                actors = item.select("a").map { it.text() }
            }
        }
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            posterUrl = poster
            plot = description
            this.year = year
            this.tags = tags
            score = Score.from10(rating)
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
        val html = document.html()
        var found = false

        // FilmKovası now exposes the providers as buttons/links. Extract their
        // actual URLs instead of relying only on the old div.sources layout.
        val providerPattern = Regex(
            "(?i)https?://[^\\\"'\\s<>]+(?:vidsrc|2embed|autoembed|smashy|multiembed|moviesapi)[^\\\"'\\s<>]*"
        )
        val providerUrls = linkedSetOf<String>()

        document.select("a[href], iframe[src], button, [onclick], [data-url], [data-src], [data-link], [data-embed], [data-video]")
            .forEach { element ->
                val text = element.text().lowercase()
                val interesting = text.contains("vidsrc") || text.contains("embed") ||
                    text.contains("kaynak") || text.contains("smashy") || text.contains("movies")
                val attrs = listOf(
                    element.attr("href"), element.attr("src"), element.attr("onclick"),
                    element.attr("data-url"), element.attr("data-src"), element.attr("data-link"),
                    element.attr("data-embed"), element.attr("data-video")
                )
                attrs.forEach { raw ->
                    if (interesting || raw.contains("vidsrc", true) || raw.contains("embed", true) || raw.contains("moviesapi", true)) {
                        providerUrls.addAll(providerPattern.findAll(raw).map { it.value.trimEnd(')', ';', ',') })
                    }
                }
            }

        // Some provider buttons are generated from JavaScript, so also inspect scripts.
        providerUrls.addAll(providerPattern.findAll(html).map { it.value.trimEnd(')', ';', ',') })

        // If the page exposes an IMDb id, construct the same provider URLs used by
        // the site's visible source buttons. This bypasses the site's JavaScript UI.
        val imdbId = Regex("\\btt\\d{7,10}\\b").find(html)?.value
        if (imdbId != null) {
            providerUrls += "https://vidsrc.me/embed/movie/$imdbId"
            providerUrls += "https://vidsrc.xyz/embed/movie?imdb=$imdbId"
            providerUrls += "https://vidsrc.to/embed/movie/$imdbId"
            providerUrls += "https://vidsrc.pro/embed/movie/$imdbId"
            providerUrls += "https://vidsrc.icu/embed/movie/$imdbId"
            providerUrls += "https://vidsrc.cc/v2/embed/movie/$imdbId"
            providerUrls += "https://www.2embed.cc/embed/$imdbId"
            providerUrls += "https://player.autoembed.cc/embed/movie/$imdbId"
            providerUrls += "https://player.smashy.stream/movie/$imdbId"
            providerUrls += "https://multiembed.mov/?video_id=$imdbId"
            providerUrls += "https://moviesapi.club/movie/$imdbId"
        }

        // Keep compatibility with the previous FilmKovası source-page implementation.
        document.select("div.sources a[href]").forEach { source ->
            val href = fixUrlNull(source.attr("href"))
            if (href != null) {
                runCatching {
                    val sourceDoc = app.get(href).document
                    sourceDoc.select("iframe[src]").mapNotNull { fixUrlNull(it.attr("src")) }
                        .forEach { providerUrls += it }
                }
            }
        }

        for (provider in providerUrls.distinct()) {
            if (provider.isBlank()) continue
            try {
                val loaded = loadExtractor(provider, data, subtitleCallback) { link ->
                    callback(link.copy(name = link.name.ifBlank { provider.substringAfter("//").substringBefore('/') }))
                }
                found = found || loaded
            } catch (_: Throwable) {
                // Try the next provider; FilmKovası exposes many mirrors.
            }
        }

        // Last fallback: if the page itself contains a playable media URL, expose it.
        document.select("video source[src], video[src]").mapNotNull {
            fixUrlNull(it.attr("src").ifBlank { it.attr("data-src") })
        }.filter { it.startsWith("http") }.forEach { media ->
            callback(
                ExtractorLink(
                    source = this.name,
                    name = "FilmKovası",
                    url = media,
                    referer = data,
                    quality = Qualities.Unknown.value,
                    type = if (media.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                )
            )
            found = true
        }

        return found
    }
}
