package com.nikyokki

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import java.util.LinkedHashSet
import java.util.regex.Pattern

class DiziMag : MainAPI() {
    override var mainUrl = "https://www.dizimag.life"
    override var name = "DiziMag"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    override val mainPage = listOf(
        MainPageRequest(mainUrl, "Dizimag")
    )

    private fun poster(element: Element): String? {
        val img = element.selectFirst("img") ?: return null
        return fixUrlNull(img.attr("src"))
            ?: fixUrlNull(img.attr("data-src"))
            ?: fixUrlNull(img.attr("data-lazy-src"))
            ?: fixUrlNull(img.attr("data-original"))
            ?: fixUrlNull(img.attr("srcset")?.substringBefore(","))
    }

    private fun titleFor(element: Element): String? {
        return element.selectFirst("h2, h3, h4, h5")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: element.selectFirst("img")?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
            ?: element.attr("title").trim().takeIf { it.isNotBlank() }
    }

    private fun toSearch(element: Element): SearchResponse? {
        val href = fixUrlNull(element.attr("href")) ?: return null
        if (!href.contains("/dizi/") && !href.contains("/film/")) return null
        val title = titleFor(element) ?: return null
        val p = poster(element)
        return if (href.contains("/film/")) {
            newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = p }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { posterUrl = p }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val seen = LinkedHashSet<String>()
        val items = document.select("a[href]").mapNotNull { a ->
            val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
            if (!href.contains("/dizi/") && !href.contains("/film/")) return@mapNotNull null
            if (!seen.add(href)) return@mapNotNull null
            toSearch(a)
        }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val urls = listOf(
            "${mainUrl}/arama?q=${java.net.URLEncoder.encode(query, "UTF-8")}",
            "${mainUrl}/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}",
            "${mainUrl}/?s=${java.net.URLEncoder.encode(query, "UTF-8")}"
        )
        for (url in urls) {
            try {
                val document = app.get(url).document
                val seen = LinkedHashSet<String>()
                val results = document.select("a[href]").mapNotNull { a ->
                    val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
                    if (!href.contains("/dizi/") && !href.contains("/film/")) return@mapNotNull null
                    if (!seen.add(href)) return@mapNotNull null
                    toSearch(a)
                }
                if (results.isNotEmpty()) return results
            } catch (_: Exception) { }
        }
        return emptyList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val poster = poster(document.selectFirst("main, article, body") ?: document.body())
        val description = document.selectFirst("meta[name=description]")?.attr("content")
            ?: document.selectFirst("main p, article p")?.text()?.trim()
        val year = Regex("(?:Yapım Yılı|Yıl)\\s*([0-9]{4})").find(document.text())?.groupValues?.getOrNull(1)?.toIntOrNull()
        val rating = Regex("IMDB Puanı\\s*([0-9]+(?:[.,][0-9]+)?)").find(document.text())?.groupValues?.getOrNull(1)?.replace(',', '.')?.toDoubleOrNull()
        val actors = document.select("a[href*='/oyuncu/']").map { Actor(it.text().trim()) }.filter { it.name.isNotBlank() }

        val episodes = document.select("a[href]").mapNotNull { a ->
            val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
            val match = Regex("/dizi/[^/]+/sezon-([0-9]+)/bolum-([0-9]+)").find(href) ?: return@mapNotNull null
            val season = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val episode = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
            val epName = a.text().trim().ifBlank { "$episode. Bölüm" }
            newEpisode(href) { name = epName; this.season = season; this.episode = episode }
        }.distinctBy { "${it.season}-${it.episode}-${it.data}" }

        return if (url.contains("/film/")) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster
                this.year = year
                plot = description
                if (rating != null) this.score = com.lagradost.cloudstream3.Score.from10(rating)
                addActors(actors)
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster
                this.year = year
                plot = description
                if (rating != null) this.score = com.lagradost.cloudstream3.Score.from10(rating)
                addActors(actors)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, referer = mainUrl).document
        var found = false

        document.select("video source[src], video[src]").forEach { element ->
            val src = fixUrlNull(element.attr("src")) ?: return@forEach
            callback.invoke(newExtractorLink(name, name, src, ExtractorLinkType.M3U8) {
                referer = data
                quality = Qualities.Unknown.value
            })
            found = true
        }

        document.select("iframe[src], iframe[data-src]").forEach { iframe ->
            val src = fixUrlNull(iframe.attr("src").ifBlank { iframe.attr("data-src") }) ?: return@forEach
            try {
                loadExtractor(src, data, subtitleCallback, callback)
                found = true
            } catch (_: Exception) { }
        }

        document.select("a[href]").forEach { a ->
            val href = fixUrlNull(a.attr("href")) ?: return@forEach
            val lower = href.lowercase()
            if (lower.contains("embed") || lower.contains("player") || lower.contains("stream") || lower.contains("video")) {
                try {
                    loadExtractor(href, data, subtitleCallback, callback)
                    found = true
                } catch (_: Exception) { }
            }
        }

        val html = document.html()
        val patterns = listOf(
            Regex("https?://[^\\\"'<> ]+\\.m3u8(?:\\?[^\\\"'<> ]*)?"),
            Regex("https?://[^\\\"'<> ]+\\.mp4(?:\\?[^\\\"'<> ]*)?")
        )
        patterns.forEach { pattern ->
            pattern.findAll(html).forEach { match ->
                val src = match.value.replace("\\/", "/")
                callback.invoke(newExtractorLink(name, name, src, ExtractorLinkType.M3U8) {
                    referer = data
                    quality = Qualities.Unknown.value
                })
                found = true
            }
        }

        return found
    }
}
