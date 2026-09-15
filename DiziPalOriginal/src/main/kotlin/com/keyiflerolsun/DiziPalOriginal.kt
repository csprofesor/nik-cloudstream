package com.keyiflerolsun

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Element
import java.net.URLEncoder

class DiziPalOriginal : MainAPI() {
    override var mainUrl = "https://dizipal1581.com"
    override var name = "DiziPalOriginal"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)
    override var sequentialMainPage = true

    override val mainPage = mainPageOf(
        "$mainUrl/bolumler" to "Son Bölümler",
        "$mainUrl/diziler" to "Yeni Diziler",
        "$mainUrl/filmler" to "Yeni Filmler"
    )

    private fun Element.href(): String? = attr("href").trim().takeIf { it.isNotEmpty() }
        ?: selectFirst("a[href]")?.attr("href")?.trim()?.takeIf { it.isNotEmpty() }

    private fun Element.poster(): String? = selectFirst("img")?.let { img ->
        listOf("data-src", "data-lazy-src", "data-original", "src")
            .firstNotNullOfOrNull { key -> img.attr(key).trim().takeIf { it.isNotEmpty() && !it.startsWith("data:image") } }
    }?.let(::fixUrlNull)

    private fun Element.toSearchResponse(): SearchResponse? {
        val href = fixUrlNull(href()) ?: return null
        val title = (attr("title").takeIf { it.isNotBlank() }
            ?: selectFirst("img")?.attr("alt")?.takeIf { it.isNotBlank() }
            ?: selectFirst("h2,h3,h4,.title,.card-title")?.text()?.trim()) ?: return null

        return when {
            href.contains("/movies/", true) -> newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl = poster()
            }
            href.contains("/series/", true) -> newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = poster()
            }
            else -> null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val selector = if (request.data.contains("/bolumler")) {
            "a[href*='/bolum/']"
        } else {
            "a[href*='/series/'], a[href*='/movies/']"
        }
        val items = document.select(selector).mapNotNull { it.toSearchResponse() }.distinctBy { it.url }
        return newHomePageResponse(request.name, items, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return app.get("$mainUrl/diziler?kelime=$encoded&durum=&tur=&type=&siralama=")
            .document
            .select("a[href*='/series/'], a[href*='/movies/']")
            .mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")?.substringBefore(" izle")?.trim()
            ?: return null
        val poster = fixUrlNull(document.selectFirst("meta[property='og:image']")?.attr("content"))

        if (url.contains("/movies/", true)) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster
            }
        }

        val episodes = document.select("a[href*='/bolum/']").mapNotNull { a ->
            val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
            val match = Regex("-(\\d+)x(\\d+)$").find(href) ?: return@mapNotNull null
            newEpisode(href) {
                name = a.text().trim().ifEmpty { "${match.groupValues[1]}. Sezon ${match.groupValues[2]}. Bölüm" }
                season = match.groupValues[1].toIntOrNull()
                episode = match.groupValues[2].toIntOrNull()
            }
        }.distinctBy { it.data }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            posterUrl = poster
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Link extraction intentionally left disabled while the provider integration is repaired.
        return false
    }
}
