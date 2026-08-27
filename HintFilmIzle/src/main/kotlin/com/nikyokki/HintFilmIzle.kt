package com.nikyokki

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

class HintFilmIzle : MainAPI() {
    override var mainUrl = "https://www.hintfilmizle.com"
    override var name = "HintFilmİzle"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/film" to "Son Filmler",
        "$mainUrl/film?order=DESC&orderby=date" to "Yeni Eklenenler",
        "$mainUrl/trendler" to "Trendler",
        "$mainUrl/tur/aksiyon-filmleri" to "Aksiyon",
        "$mainUrl/tur/dram-filmleri" to "Dram",
        "$mainUrl/tur/komedi-filmleri" to "Komedi",
        "$mainUrl/tur/korku-filmleri" to "Korku",
        "$mainUrl/tur/macera-filmleri" to "Macera",
        "$mainUrl/tur/romantik-filmleri" to "Romantik",
        "$mainUrl/tur/savas-filmleri" to "Savaş",
        "$mainUrl/tur/suc-filmleri" to "Suç",
        "$mainUrl/tur/tarih-filmleri" to "Tarih",
        "$mainUrl/netflix-izle" to "Netflix"
    )

    private fun cleanUrl(value: String?): String? = value
        ?.replace("\\\\/", "/")
        ?.replace("\\\\u0026", "&")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let { if (it.startsWith("//")) "https:$it" else it }
        ?.let { fixUrlNull(it) }

    private fun Element.posterUrl(): String? {
        val attrs = listOf("data-src", "data-lazy-src", "data-original", "data-image", "data-poster", "data-thumb", "src")
        return select("img").asSequence()
            .flatMap { img -> attrs.asSequence().map { img.attr(it) } }
            .mapNotNull { cleanUrl(it) }
            .firstOrNull { !it.startsWith("data:image", true) && !it.contains("placeholder", true) }
    }

    private fun Element.cardTitle(): String? =
        sequenceOf(
            selectFirst("h2")?.text(), selectFirst("h3")?.text(),
            selectFirst(".title")?.text(), selectFirst(".name")?.text(),
            selectFirst("img")?.attr("alt"), selectFirst("img")?.attr("title"),
            attr("title"), text()
        ).mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
            .firstOrNull()

    private fun Element.toSearchResult(): SearchResponse? {
        val href = cleanUrl(attr("href")) ?: return null
        if (!href.startsWith(mainUrl)) return null
        val path = href.removePrefix(mainUrl).substringBefore("?").trimEnd('/')
        if (!path.startsWith("/film/") && !path.startsWith("/dizi/")) return null

        val title = cardTitle()?.replace(Regex("\\s+"), " ")?.trim() ?: return null
        if (title.length > 180 || title.equals("film", true) || title.equals("dizi", true)) return null

        val poster = posterUrl()
        return if (path.startsWith("/dizi/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { posterUrl = poster }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }
        }
    }

    private fun extractResults(document: org.jsoup.nodes.Document): List<SearchResponse> {
        return document.select("a[href*='/film/'], a[href*='/dizi/']").mapNotNull { anchor ->
            val parent = anchor.parents().firstOrNull {
                it.selectFirst("img") != null && it.text().length < 500
            } ?: anchor
            (if (parent !== anchor) parent else anchor).let { card ->
                val link = card.selectFirst("a[href*='/film/'], a[href*='/dizi/']") ?: anchor
                link.toSearchResult()
            }
        }.distinctBy { it.url }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = if (page <= 1) request.data else request.data.trimEnd('/') + "/page/" + page + "/"
        val document = runCatching { app.get(pageUrl, referer = "$mainUrl/").document }.getOrNull()
            ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val results = extractResults(document)
        return newHomePageResponse(request.name, results, hasNext = results.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = query.trim().replace(" ", "+")
        val urls = listOf("$mainUrl/?s=$encoded", "$mainUrl/film?search=$encoded")
        for (url in urls) {
            val results = runCatching { extractResults(app.get(url, referer = "$mainUrl/").document) }
                .getOrDefault(emptyList())
            if (results.isNotEmpty()) return results
        }
        return emptyList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    private fun firstText(document: org.jsoup.nodes.Document, vararg selectors: String): String? =
        selectors.asSequence().mapNotNull { document.selectFirst(it)?.text()?.trim() }
            .firstOrNull { it.isNotBlank() }

    private fun findNumber(text: String, vararg labels: String): String? {
        val label = labels.joinToString("|") { Regex.escape(it) }
        return Regex("(?:$label)\\s*[:\\-]?\\s*([0-9]+(?:[.,][0-9]+)?)", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.getOrNull(1)
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, referer = "$mainUrl/").document
        val title = firstText(document, "h1", ".entry-title", ".film-title", ".movie-title", ".serieTitle")
            ?: return null

        val poster = cleanUrl(document.selectFirst("meta[property='og:image']")?.attr("content"))
            ?: document.selectFirst("article, .movie-detail, .film-detail, .serie-detail")?.posterUrl()

        val bodyText = document.text()
        val description = firstText(
            document, ".description", ".film-description", ".movie-description",
            ".serieDescription", ".plot", ".entry-content p"
        )
        val year = Regex("\\b(19|20)\\d{2}\\b").find(bodyText)?.value?.toIntOrNull()
        val rating = findNumber(bodyText, "IMDb", "IMDB")
        val tags = document.select(".genres a, .genre a, .genreList a, .categories a, .post-categories a")
            .map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
        val actors = document.select(".actors a, .cast a, .oyuncular a").mapNotNull {
            it.text().trim().takeIf(String::isNotBlank)?.let { name -> Actor(name) }
        }.distinctBy { it.name }
        val recommendations = extractResults(document)

        val isSeries = url.contains("/dizi/", true) ||
            document.selectFirst(".episodes, .episode-list, .seasons") != null

        if (isSeries) {
            val episodes = document.select(
                "a[href*='/dizi/'], a[href*='sezon'], a[href*='bolum'], .episode a, .episodes a, .episode-list a"
            ).mapNotNull { link ->
                val href = cleanUrl(link.attr("href")) ?: return@mapNotNull null
                if (href == url) return@mapNotNull null
                val text = link.text() + " " + link.attr("title")
                val season = Regex("(?:s|sezon[\\s._-]*)(\\d+)", RegexOption.IGNORE_CASE)
                    .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
                val episode = Regex("(?:e|bölüm[\\s._-]*)(\\d+)", RegexOption.IGNORE_CASE)
                    .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
                if (season == null || episode == null) return@mapNotNull null
                newEpisode(href) {
                    name = link.text().trim()
                    this.season = season
                    this.episode = episode
                }
            }.distinctBy { it.data }.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster
                this.year = year
                plot = description
                this.tags = tags
                score = Score.from10(rating)
                addActors(actors)
                this.recommendations = recommendations
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            posterUrl = poster
            this.year = year
            plot = description
            this.tags = tags
            score = Score.from10(rating)
            addActors(actors)
            this.recommendations = recommendations
        }
    }

    private fun directLinks(html: String): List<String> =
        Regex("""https?://[^"'\\s<>]+(?:\\.m3u8(?:\\?[^"'\\s<>]*)?|\\.mp4(?:\\?[^"'\\s<>]*)?)""", RegexOption.IGNORE_CASE)
            .findAll(html).mapNotNull { cleanUrl(it.value) }.distinct().toList()

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = runCatching { app.get(data, referer = "$mainUrl/").document }.getOrNull()
            ?: return false

        var found = false
        val frames = document.select("iframe[src], frame[src]").mapNotNull { cleanUrl(it.attr("src")) }.distinct()

        for (frame in frames) {
            val nested = runCatching { app.get(frame, referer = data).text }.getOrDefault("")
            for (stream in directLinks(nested)) {
                found = true
                callback(newExtractorLink(
                    source = name, name = "HintFilmİzle", url = stream,
                    type = if (stream.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    referer = frame
                    quality = getQualityFromName(stream)
                })
            }
            if (runCatching { loadExtractor(frame, data, subtitleCallback, callback) }.isSuccess) found = true
        }

        for (stream in directLinks(document.html())) {
            found = true
            callback(newExtractorLink(
                source = name, name = "HintFilmİzle Direct", url = stream,
                type = if (stream.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                referer = data
                quality = getQualityFromName(stream)
            })
        }
        return found
    }
}
