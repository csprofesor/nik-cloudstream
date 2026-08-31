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
        debugFilmKovasi("LOAD_URL", url)
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

    private fun debugFilmKovasi(tag: String, value: String) {
        Log.d("FilmKovasiDebug", "$tag = $value")
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        debugFilmKovasi("LOADLINKS_DATA", data)
        val document = app.get(data).document
        var found = false

        suspend fun tryExtractor(rawUrl: String?, referer: String = data) {
            val candidate = rawUrl?.trim()
                ?.trim('"', '(', ')', ';', ',')
                ?: return

            debugFilmKovasi("SOURCE_RAW", candidate)

            // Never send labels/identifiers such as "filmkova" to the HTTP client.
            val url = if (candidate.startsWith("http://", true) || candidate.startsWith("https://", true)) {
                candidate
            } else {
                fixUrlNull(candidate) ?: return
            }

            if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return

            if (url.isBlank() ||
                url.startsWith("javascript:", true) ||
                url == data
            ) return

            try {
                debugFilmKovasi("EXTRACTOR_URL", url)
                if (loadExtractor(url, referer, subtitleCallback) { link ->
                        callback(link)
                    }) {
                    found = true
                }
            } catch (_: Throwable) {
                // Try the next source.
            }
        }

        // The site can expose the player either directly or through source/mirror pages.
        val sourceElements = document.select(
            "div.sources a[href], " +
            "a[data-url], a[data-src], a[data-link], a[data-embed], " +
            "a[href*='embed'], a[href*='player'], " +
            "button[data-url], button[data-src]"
        )

        // First try the URLs exposed directly by source buttons.
        for (element in sourceElements) {
            val referer = fixUrlNull(element.attr("href"))?.takeUnless { it.isBlank() } ?: data

            val attributes = listOf(
                element.attr("href"),
                element.attr("data-url"),
                element.attr("data-src"),
                element.attr("data-link"),
                element.attr("data-embed"),
                element.attr("data-video")
            )

            for (raw in attributes) {
                val candidates = Regex("""https?://[^"'\\s<>]+|/[^"'\\s<>]+""")
                    .findAll(raw)
                    .map { it.value }
                    .toList()

                if (candidates.isEmpty() && raw.isNotBlank()) {
                    tryExtractor(raw, data)
                } else {
                    candidates.forEach { tryExtractor(it, data) }
                }
            }

            // Some source buttons open a FilmKovası page containing the real iframe.
            val href = fixUrlNull(element.attr("href"))
            if (!href.isNullOrBlank() && href.contains("filmkovasi.co", true)) {
                try {
                    val sourceDoc = app.get(href, referer = data).document
                    sourceDoc.select("iframe[src], iframe[data-src]").forEach { iframe ->
                        val iframeUrl = iframe.attr("src").ifBlank { iframe.attr("data-src") }
                        tryExtractor(iframeUrl, href)
                    }

                    sourceDoc.select("video source[src], video[src]").forEach { video ->
                        tryExtractor(video.attr("src").ifBlank { video.attr("data-src") }, href)
                    }
                } catch (_: Throwable) {
                    // Continue with other sources.
                }
            }
        }

        // Also inspect any iframe already present on the film page.
        document.select("iframe[src], iframe[data-src]").forEach { iframe ->
            val iframeUrl = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            tryExtractor(iframeUrl, data)
        }

        // Finally accept a direct media URL if the site exposes one in the HTML.
        document.select("video source[src], video[src]").forEach { video ->
            val media = fixUrlNull(video.attr("src").ifBlank { video.attr("data-src") })
                ?: return@forEach

            if (media.startsWith("http", true)) {
                callback(
                    ExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = media,
                        referer = data,
                        quality = Qualities.Unknown.value,
                        type = if (media.contains(".m3u8", true)) {
                            ExtractorLinkType.M3U8
                        } else {
                            ExtractorLinkType.VIDEO
                        }
                    )
                )
                found = true
            }
        }

        return found
    }
}
