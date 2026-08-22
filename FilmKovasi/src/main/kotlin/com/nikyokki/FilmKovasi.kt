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
        var found = false
        val sourceNames = setOf(
            "harici kaynak 1", "harici kaynak 2",
            "vidsrc me", "vidsrc xyz", "vidsrc to", "vidsrc pro", "vidsrc icu", "vidsrc cc",
            "2embed", "autoembed", "smashystream", "multiembed", "moviesapi"
        )

        suspend fun resolveCandidate(raw: String, sourceName: String) {
            val candidate = fixUrlNull(raw.trim().trim('"', '\'', '(', ')', ';', ',')) ?: return
            if (candidate.isBlank() || candidate.startsWith("javascript:") || candidate == data) return
            try {
                if (candidate.startsWith("http") && !candidate.contains("filmkovasi.co", true)) {
                    if (loadExtractor(candidate, data, subtitleCallback) { link ->
                        callback(link)
                    }) found = true
                    return
                }

                val sourceDoc = app.get(candidate, referer = data).document
                for (iframe in sourceDoc.select("iframe[src], iframe[data-src]")) {
                    val iframeUrl = fixUrlNull(iframe.attr("src").ifBlank { iframe.attr("data-src") }) ?: continue
                    if (loadExtractor(iframeUrl, candidate, subtitleCallback) { link ->
                        callback(link)
                    }) found = true
                }
            } catch (_: Throwable) {
                // Try the next visible source; FilmKovası exposes multiple mirrors.
            }
        }

        for (element in document.select("a, button, [role=button]")) {
            val label = element.text().replace(Regex("\\s+"), " ").trim().lowercase()
            if (label !in sourceNames) continue
            val sourceName = element.text().replace(Regex("\\s+"), " ").trim()
            val attrs = listOf(
                element.attr("href"), element.attr("src"), element.attr("data-url"),
                element.attr("data-src"), element.attr("data-link"), element.attr("data-embed"),
                element.attr("data-video"), element.attr("onclick")
            )
            for (raw in attrs.filter { it.isNotBlank() }) {
                for (candidate in Regex("https?://[^\\\"'\\s<>]+|/[^\\\"'\\s<>]+")
                    .findAll(raw).map { it.value }) {
                    resolveCandidate(candidate, sourceName)
                }
            }

            for (iframe in element.select("iframe[src], iframe[data-src]")) {
                val iframeUrl = iframe.attr("src").ifBlank { iframe.attr("data-src") }
                if (iframeUrl.isBlank()) continue
                if (loadExtractor(iframeUrl, data, subtitleCallback) { link ->
                    callback(link)
                }) found = true
            }
        }

        for (source in document.select("div.sources a[href]")) {
            val sourceName = source.selectFirst("span")?.text()?.trim().takeUnless { it.isNullOrBlank() } ?: name
            val href = fixUrlNull(source.attr("href")) ?: continue
            try {
                val sourceDoc = app.get(href, referer = data).document
                for (iframe in sourceDoc.select("iframe[src], iframe[data-src]")) {
                    val iframeUrl = fixUrlNull(iframe.attr("src").ifBlank { iframe.attr("data-src") }) ?: continue
                    if (loadExtractor(iframeUrl, href, subtitleCallback) { link ->
                        callback(link)
                    }) found = true
                }
            } catch (_: Throwable) {
                // Continue with other source buttons.
            }
        }

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
