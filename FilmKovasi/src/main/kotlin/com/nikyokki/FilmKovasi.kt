package com.nikyokki

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
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
        val home = document.select("div.movie-box").mapNotNull { it.toMainPageResult() }.distinctBy { it.url }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.posterUrl(): String? {
        val image = if (tagName() == "img") this else selectFirst("img") ?: return null
        val attrs = listOf("data-src", "data-lazy-src", "data-original", "data-image", "data-url", "src")
        for (attr in attrs) {
            val value = image.attr(attr).trim()
            if (value.isNotBlank() && !value.startsWith("data:image")) return fixUrlNull(value)
        }
        val absolute = image.absUrl("src").trim()
        if (absolute.isNotBlank()) return absolute
        val srcset = image.attr("srcset").substringBefore(',').trim().split(" ").firstOrNull()
        return srcset?.takeIf { it.isNotBlank() }?.let { fixUrlNull(it) }
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val link = selectFirst("div.film-ismi a[href]") ?: selectFirst("a[href]") ?: return null
        val href = fixUrlNull(link.attr("href")) ?: return null
        val title = link.text().replace(Regex("\\s+"), " ").replace(Regex("(?i)\\s+izle$"), "").trim()
        if (title.length < 2 || !href.startsWith(mainUrl)) return null
        val poster = selectFirst("div.poster img")?.posterUrl()
            ?: selectFirst("img")?.posterUrl()
        return newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/?s=${query}").document
        return document.select("div.movie-box").mapNotNull { it.toMainPageResult() }.distinctBy { it.url }
    }

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
        var rating = document.selectFirst("div.imdb")?.text()?.replace("IMDb Puanı:", "")?.split("/")?.first()?.trim()
        var actors = document.select("div.actor a").map { it.text() }
        val trailer = document.selectFirst("div.film-afis iframe")?.let { fixUrlNull(it.attr("src").ifBlank { it.attr("data-src") }) }

        document.select("div.list-item").forEach { item ->
            if (item.selectFirst("a")?.attr("href")?.contains("/yil/") == true) year = item.selectFirst("a")?.text()?.toIntOrNull()
            if (item.selectFirst("a")?.attr("href")?.contains("/oyuncu/") == true) actors = item.select("a").map { it.text() }
        }
        document.select("div#listelements div, #listelements div").forEach {
            if (it.text().contains("IMDb:")) rating = it.text().trim().split(" ").last()
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            posterUrl = poster
            plot = description
            this.year = year
            tags?.let { this.tags = it }
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
        val document = try { app.get(data).document } catch (e: Throwable) {
            Log.e("FKV", "film page failed", e)
            return false
        }

        val candidates = linkedSetOf<String>()

        // Current site/player markup: collect every iframe and lazy iframe first.
        document.select("iframe[src], iframe[data-src], iframe[data-url], iframe[data-embed]").forEach { iframe ->
            val url = iframe.attr("src").ifBlank { iframe.attr("data-src") }.ifBlank { iframe.attr("data-url") }.ifBlank { iframe.attr("data-embed") }
            fixUrlNull(url)?.let { candidates.add(it) }
        }

        // Source/player buttons and links used by different FilmKovası templates.
        document.select("div.sources a[href], .sources a[href], .player a[href], .video a[href], a[href]").forEach { link ->
            val href = link.attr("href").trim()
            val label = link.text().lowercase()
            val cls = link.className().lowercase()
            if (href.startsWith("http") && (
                link.parents().any { it.className().lowercase().contains("source") || it.className().lowercase().contains("player") || it.className().lowercase().contains("video") } ||
                cls.contains("source") || cls.contains("player") || cls.contains("play") || cls.contains("video") ||
                label.contains("oynat") || label.contains("izle") || label.contains("player")
            )) candidates.add(href)
        }

        // Some versions build the player URL in JavaScript instead of an iframe attribute.
        val html = document.html()
        Regex("https?://[^\\\"'\\s<>]+", RegexOption.IGNORE_CASE).findAll(html).forEach { match ->
            val url = match.value.replace("\\u0026", "&")
            val lower = url.lowercase()
            if (listOf("iframe", "embed", "player", "stream", "video", "watch", "m3u8", "mp4").any { lower.contains(it) }) {
                candidates.add(url)
            }
        }

        val visited = mutableSetOf<String>()
        for (candidate in candidates) {
            resolveVideoCandidate(candidate, data, subtitleCallback, callback, visited, depth = 0)
        }
        return true
    }

    private suspend fun resolveVideoCandidate(
        candidate: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        visited: MutableSet<String>,
        depth: Int
    ) {
        if (depth > 2 || !visited.add(candidate)) return
        val fixed = fixUrlNull(candidate) ?: return
        val lower = fixed.lowercase()
        if (lower.contains("filmkovasi.co") && !lower.contains("/player") && !lower.contains("/embed") && !lower.contains("/watch")) {
            return
        }

        try {
            if (lower.endsWith(".m3u8") || lower.contains(".m3u8?") || lower.endsWith(".mp4") || lower.contains(".mp4?")) {
                callback(ExtractorLink(
                    source = name,
                    name = name,
                    url = fixed,
                    referer = referer,
                    quality = Qualities.Unknown.value,
                    type = if (lower.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ))
                return
            }

            // Let CloudStream's maintained extractors handle external players.
            if (!lower.contains("filmkovasi.co")) {
                loadExtractor(fixed, referer, subtitleCallback, callback)
            }

            // Also inspect an external player page for a nested iframe or direct media URL.
            val doc = app.get(fixed, referer = referer).document
            doc.select("iframe[src], iframe[data-src], iframe[data-url], video source[src], source[src]").forEach { element ->
                val media = element.attr("src").ifBlank { element.attr("data-src") }.ifBlank { element.attr("data-url") }
                fixUrlNull(media)?.let { url ->
                    resolveVideoCandidate(url, fixed, subtitleCallback, callback, visited, depth + 1)
                }
            }
        } catch (e: Throwable) {
            Log.e("FKV", "video candidate failed: $fixed", e)
        }
    }

    private data class FKVSource(
        @JsonProperty("uid") val uid: String? = null,
        @JsonProperty("md5") val md5: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("status") val status: String? = null,
    )

    private data class FileSource(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("preload") val preload: String? = null,
    )

    private data class Track(
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("kind") val kind: String? = null,
    )

    private fun String.addMarks(str: String): String = replace(Regex("\\\"?$str\\\"?"), "\\\"$str\\\"")
}
