package com.nikyokki

import com.nikyokki.extractors.FilmKovasiBundledExtractors

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

// FilmKovasi source-page resolver: source pages (/2/, /3/, ...) are parsed for real player URLs.
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

        val document = try {
            app.get(data).document
        } catch (_: Throwable) {
            return false
        }

        var found = false
        val tried = mutableSetOf<String>()

        suspend fun tryExtractor(rawUrl: String?, referer: String) {
            val url = rawUrl?.trim()
                ?.trim('"', '\'', '(', ')', ';', ',')
                ?.let { fixUrlNull(it) }
                ?: return

            if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return
            if (url == data || !tried.add(url)) return

            debugFilmKovasi("EXTRACTOR_URL", url)

            try {
                // First use the extractors bundled specifically for FilmKovası.
                // These cover the external provider buttons that are not part of
                // CloudStream's built-in extractorApis list.
                val bundled = FilmKovasiBundledExtractors.tryExtract(
                    url = url,
                    referer = referer,
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )
                if (bundled) {
                    found = true
                    return
                }

                // Then hand everything else to the current CloudStream extractor registry.
                loadExtractor(url, referer, subtitleCallback) { link ->
                    found = true
                    callback(link)
                }
            } catch (_: Throwable) {
                // One broken source must not prevent the remaining sources from being tried.
            }
        }

        // FilmKovası lists the providers as numbered source pages (/2/, /3/, ...).
        // Discover them from every href, not only from a specific CSS class.
        val sourcePages = mutableListOf<Pair<String, String>>()

        fun addSourcePage(rawHref: String, rawLabel: String = "") {
            val href = rawHref.trim()
            val url = fixUrlNull(href) ?: return
            if (!url.startsWith(mainUrl, true) || url == data) return

            val match = Regex("/(\\d+)/?$").find(url) ?: return
            val pageNo = match.groupValues[1].toIntOrNull() ?: return
            if (pageNo < 2) return

            val label = rawLabel.trim().ifBlank { "Kaynak $pageNo" }
            sourcePages.add(url to label)
        }

        document.select("a[href]").forEach { element ->
            addSourcePage(
                element.attr("href"),
                element.selectFirst(".dil")?.text()?.trim()
                    ?: element.text().replace(Regex("\\s+"), " ").trim()
            )
        }

        // Some WordPress/cache variants can leave the source anchors only in raw HTML.
        if (sourcePages.isEmpty()) {
            val html = document.html()
            Regex("""https?://[^"'\s<>]+/\d+/?""").findAll(html).forEach { match ->
                addSourcePage(match.value)
            }
            Regex("""href=["']([^"']+/\d+/?)["']""", RegexOption.IGNORE_CASE)
                .findAll(html).forEach { match ->
                    addSourcePage(match.groupValues[1])
                }
        }

        val orderedSourcePages = sourcePages
            .distinctBy { it.first }
            .sortedBy { Regex("/(\\d+)/?$").find(it.first)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE }

        debugFilmKovasi("SOURCE_PAGE_COUNT", orderedSourcePages.size.toString())
        debugFilmKovasi("SOURCE_PAGE_ORDER", orderedSourcePages.joinToString(" | ") { "${it.second}=${it.first}" })

        // Try sources strictly in page-number order. If one source works, stop:
        // there is no reason to wait for the remaining dead providers.
        for ((sourceUrl, label) in orderedSourcePages) {
            debugFilmKovasi("SOURCE_PAGE_START", "${label} = ${sourceUrl}")

            val sourceDocument = try {
                app.get(sourceUrl, referer = data).document
            } catch (e: Throwable) {
                debugFilmKovasi("SOURCE_PAGE_FAIL", "${label} = ${e.javaClass.simpleName}: ${e.message ?: ""}")
                continue
            }

            debugFilmKovasi("SOURCE_PAGE_OK", "${label} = ${sourceUrl}")

            sourceDocument.select(
                "iframe[src], iframe[data-src], " +
                "embed[src], object[data], " +
                "video source[src], video source[data-src], video[src], video[data-src]"
            ).forEach { element ->
                val raw = when (element.tagName()) {
                    "iframe", "embed" -> element.attr("src").ifBlank { element.attr("data-src") }
                    "object" -> element.attr("data")
                    else -> element.attr("src").ifBlank { element.attr("data-src") }
                }

                if (raw.isNotBlank() && !raw.equals("about:blank", true)) {
                    debugFilmKovasi("PLAYER", "${label} -> ${raw}")
                    tryExtractor(raw, sourceUrl)
                }
            }

            // CloudStream does not execute the source page JavaScript.
            // Decode URLs that are present directly or inside simple atob(...) payloads.
            val scriptUrls = mutableListOf<String>()
            val atobRegex = Regex("""atob\(\s*["']([^"']+)["']\s*\)""", RegexOption.IGNORE_CASE)
            val absoluteUrlRegex = Regex("""https?://[^"'\s<>]+""", RegexOption.IGNORE_CASE)

            fun addScriptUrl(raw: String) {
                val cleaned = raw
                    .replace("\\/", "/")
                    .replace("\u0026", "&")
                    .replace("&amp;", "&")
                    .trim()
                    .trim('"', '\'', '(', ')', '[', ']', '{', '}', ';', ',', '.')

                val url = fixUrlNull(cleaned) ?: return
                if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return
                if (url == data || url == sourceUrl || url.contains("filmkovasi.co", true)) return

                val lower = url.lowercase()
                val looksLikePlayer = listOf(
                    "embed", "player", "stream", "video", "watch", "play/",
                    "moviesapi", "vidsrc", "2embed", "autoembed", "smashystream",
                    "multiembed", "youtube.com/embed", "youtu.be/"
                ).any { lower.contains(it) }

                val looksLikeAsset = listOf(
                    ".js", ".css", ".png", ".jpg", ".jpeg", ".webp", ".gif",
                    "googletagmanager", "google-analytics", "recaptcha"
                ).any { lower.contains(it) }

                if (looksLikePlayer && !looksLikeAsset) scriptUrls.add(url)
            }

            sourceDocument.select("script").forEach { script ->
                val text = script.data().ifBlank { script.html() }
                if (text.isBlank()) return@forEach

                atobRegex.findAll(text).forEach { match ->
                    try {
                        val decoded = Base64.decode(match.groupValues[1], Base64.DEFAULT).toString(Charsets.UTF_8)
                        absoluteUrlRegex.findAll(decoded).forEach { addScriptUrl(it.value) }
                    } catch (_: Throwable) {
                    }
                }

                absoluteUrlRegex.findAll(text).forEach { addScriptUrl(it.value) }
            }

            scriptUrls.distinct().forEach { playerUrl ->
                debugFilmKovasi("SCRIPT_PLAYER", "${label} -> ${playerUrl}")
                tryExtractor(playerUrl, sourceUrl)
            }

            sourceDocument.select(
                "[data-embed], [data-player], [data-video], [data-src], [data-url]"
            ).forEach { element ->
                val raw = sequenceOf(
                    element.attr("data-embed"),
                    element.attr("data-player"),
                    element.attr("data-video"),
                    element.attr("data-src"),
                    element.attr("data-url")
                ).firstOrNull { it.isNotBlank() }

                if (!raw.isNullOrBlank() && !raw.equals("about:blank", true)) {
                    debugFilmKovasi("PLAYER_DATA", "${label} -> ${raw}")
                    tryExtractor(raw, sourceUrl)
                }
            }

            if (found) {
                debugFilmKovasi("SOURCE_SUCCESS", "${label}")
                break
            }
        }

        // Fallback: if a site version exposes a direct iframe on the film page,
        // still support it.
        document.select("iframe[src], iframe[data-src]").forEach { iframe ->
            val raw = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            tryExtractor(raw, data)
        }

        return found
    }

}
