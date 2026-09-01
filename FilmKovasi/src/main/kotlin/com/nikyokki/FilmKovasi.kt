package com.nikyokki

import android.util.Base64
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
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import java.net.URI

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
        "${mainUrl}/filmizle/vahsi-bati/" to "Vahşi Batı"
    )

    private fun browserHeaders() = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = if (page <= 1) request.data else "${request.data}page/$page/"
        val document = app.get(pageUrl, headers = browserHeaders()).document
        return newHomePageResponse(
            request.name,
            document.select("div.movie-box").mapNotNull { it.toMainPageResult() }.distinctBy { it.url }
        )
    }

    private fun Element.posterUrl(): String? {
        val image = if (tagName() == "img") this else selectFirst("img") ?: return null
        for (attr in listOf("data-src", "data-lazy-src", "data-original", "data-image", "src")) {
            val value = image.attr(attr).trim()
            if (value.isNotBlank() && !value.startsWith("data:image")) return fixUrlNull(value)
        }
        return null
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val link = selectFirst("a[href]") ?: return null
        val href = normalize(link.attr("href"), mainUrl) ?: return null
        val title = selectFirst(".title, .movie-title, h2, h3")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: link.attr("title").trim().takeIf { it.isNotBlank() }
            ?: link.text().trim().takeIf { it.isNotBlank() }
            ?: return null
        return newMovieSearchResponse(title, href, TvType.Movie) {
            posterUrl = this@toMainPageResult.posterUrl()
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "${mainUrl}/?s=${query.urlEncode()}"
        val document = app.get(url, headers = browserHeaders()).document
        return document.select("div.movie-box, article, .movie, .film").mapNotNull { it.toMainPageResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, referer = mainUrl + "/", headers = browserHeaders()).document
        val title = document.selectFirst("h1, .title, .entry-title")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.title().substringBefore("|").trim().takeIf { it.isNotBlank() }
            ?: return null
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.let(::fixUrlNull)
            ?: document.selectFirst("img")?.posterUrl()
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
            ?: document.selectFirst(".description, .entry-content, .summary")?.text()?.trim()
        val year = Regex("\\b(19|20)\\d{2}\\b").find(document.text())?.value?.toIntOrNull()
        val actors = document.select(".actor, .cast a, [class*=actor] a").map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
        return newMovieLoadResponse(title, url, TvType.Movie, poster) {
            plot = description
            this.year = year
            addActors(actors)
            document.selectFirst("a[href*='youtube.com'],a[href*='youtu.be'],iframe[src*='youtube']")?.let { trailer = it.attr("href").ifBlank { it.attr("src") } }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        debugFilmKovasi("LOADLINKS_DATA", data)
        val firstDocument = runCatching { app.get(data, referer = mainUrl + "/", headers = browserHeaders()).document }
            .getOrNull() ?: return false

        val sourcePages = firstDocument.select("a[href]").mapNotNull { element ->
            val href = normalize(element.attr("href"), data) ?: return@mapNotNull null
            if (!href.startsWith(mainUrl, true) || href == data) return@mapNotNull null
            val number = Regex("/(\\d+)/?$").find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: return@mapNotNull null
            if (number < 2) return@mapNotNull null
            href
        }.distinct()

        var found = false
        val playerUrls = linkedMapOf<String, String>()

        for (sourceUrl in (listOf(data) + sourcePages).distinct()) {
            val document = runCatching {
                app.get(sourceUrl, referer = if (sourceUrl == data) mainUrl + "/" else data, headers = browserHeaders()).document
            }.getOrNull() ?: continue

            val dataApis = document.select("iframe[data-api]")
                .map { it.attr("data-api") }
                .filter { it.isNotBlank() }
                .distinct()

            for (apiPath in dataApis) {
                if (resolveDataApi(sourceUrl, apiPath, subtitleCallback, callback)) found = true
            }

            document.select(
                "iframe[src],iframe[data-src],iframe[data-url],iframe[data-embed],iframe[data-player]," +
                    "embed[src],object[data],video[src],video[data-src],video source[src],video source[data-src]," +
                    "[data-url],[data-embed],[data-player],[data-video]"
            ).forEach { element ->
                listOf(
                    element.attr("src"),
                    element.attr("data-src"),
                    element.attr("data-url"),
                    element.attr("data-embed"),
                    element.attr("data-player"),
                    element.attr("data-video"),
                    element.attr("data")
                ).forEach { raw ->
                    val candidate = normalize(raw, sourceUrl) ?: return@forEach
                    if (candidate == data || candidate.startsWith(mainUrl, true)) return@forEach
                    if (candidate.contains("google.com", true) || candidate.contains("doubleclick", true)) return@forEach
                    playerUrls.putIfAbsent(candidate, sourceUrl)
                }
            }

            document.select("script").forEach { script ->
                val text = script.data().ifBlank { script.html() }
                Regex("""atob\(\s*[\"']([^\"']+)[\"']\s*\)""", RegexOption.IGNORE_CASE)
                    .findAll(text)
                    .forEach { match ->
                        runCatching {
                            Base64.decode(match.groupValues[1], Base64.DEFAULT).toString(Charsets.UTF_8)
                        }.getOrNull()?.let { decoded ->
                            Regex("https?://[^\"'\\s<>]+", RegexOption.IGNORE_CASE)
                                .findAll(decoded)
                                .forEach { matchUrl ->
                                    val candidate = normalize(matchUrl.value, sourceUrl) ?: return@forEach
                                    if (!candidate.startsWith(mainUrl, true)) playerUrls.putIfAbsent(candidate, sourceUrl)
                                }
                        }
                    }
            }
        }

        for ((playerUrl, referer) in playerUrls) {
            if (resolveRuntimePlayer(playerUrl, referer, subtitleCallback, callback)) {
                found = true
                continue
            }
            val extracted = runCatching {
                loadExtractor(
                    playerUrl,
                    referer = referer,
                    subtitleCallback = subtitleCallback
                ) { link -> callback(link) }
            }.getOrDefault(false)
            if (extracted) found = true
        }

        debugFilmKovasi("LOADLINKS_RESULT", found.toString())
        return found
    }

    private suspend fun resolveDataApi(
        sourceUrl: String,
        apiPath: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val apiUrl = normalize(apiPath, sourceUrl) ?: return false
        val response = runCatching { app.get(apiUrl, referer = sourceUrl, headers = browserHeaders()) }.getOrNull() ?: return false
        var found = false
        val body = response.text
        Regex("https?://[^\"'\\s<>]+", RegexOption.IGNORE_CASE).findAll(body).forEach { match ->
            val candidate = normalize(match.value, apiUrl) ?: return@forEach
            if (candidate.contains("google.com", true) || candidate.contains("doubleclick", true)) return@forEach
            if (resolveRuntimePlayer(candidate, sourceUrl, subtitleCallback, callback)) found = true
        }
        return found
    }

    private suspend fun resolveRuntimePlayer(
        playerUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (playerUrl.contains("vidsrc", true)) {
            return resolveWithWebView(playerUrl, referer, subtitleCallback, callback)
        }

        return runCatching {
            var emitted = false
            loadExtractor(playerUrl, referer = referer, subtitleCallback = subtitleCallback) { link ->
                emitted = true
                callback(link)
            }
            emitted
        }.getOrDefault(false)
    }

    private suspend fun resolveWithWebView(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return runCatching {
            var emitted = false
            val resolver = WebViewResolver(
                success = { result ->
                    newExtractorLink(
                        source = "FilmKovası",
                        name = "FilmKovası",
                        url = result.url,
                        referer = referer,
                        type = ExtractorLinkType.VIDEO,
                        quality = result.quality,
                        headers = result.headers
                    ).also {
                        emitted = true
                        callback(it)
                    }
                },
                failure = {}
            )
            resolver.resolve(url, referer, this, callback)
            emitted
        }.getOrDefault(false)
    }

    private fun normalize(raw: String, base: String): String? {
        val value = raw.trim().replace("&amp;", "&")
        if (value.isBlank()) return null
        return runCatching { URI(base).resolve(value).toString() }.getOrNull()
            ?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
    }

    private fun String.urlEncode(): String = java.net.URLEncoder.encode(this, Charsets.UTF_8.name())

    private fun debugFilmKovasi(tag: String, value: String) {
        Log.d("FilmKovasi", "$tag: $value")
    }
}
