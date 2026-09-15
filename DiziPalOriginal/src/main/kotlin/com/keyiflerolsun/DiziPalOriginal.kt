package com.keyiflerolsun

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
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
        "$mainUrl/filmler" to "Yeni Filmler",
        "$mainUrl/kanal/netflix" to "Netflix",
        "$mainUrl/kanal/exxen" to "Exxen",
        "$mainUrl/kanal/blutv" to "BluTV",
        "$mainUrl/kanal/disney" to "Disney+",
        "$mainUrl/kanal/amazon" to "Amazon Prime",
        "$mainUrl/kanal/tabii" to "Tabii",
        "$mainUrl/kanal/gain" to "Gain",
        "$mainUrl/kanal/max" to "Max"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val items = if (request.data.contains("/bolumler")) {
            document.select("a[href*='/bolum/']")
                .mapNotNull { it.toEpisodeSearch() }
                .distinctBy { it.url }
        } else {
            document.select("a[href*='/series/'], a[href*='/movies/']")
                .mapNotNull { it.toSearch() }.distinctBy { it.url }
        }
        return newHomePageResponse(request.name, items, false)
    }

    private fun Element.posterUrl(): String? {
        val img = selectFirst("img") ?: return null
        val raw = listOf("data-src", "data-lazy-src", "data-original", "data-image", "data-lazy", "src")
            .asSequence()
            .map { img.attr(it).trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("data:image") }
        return raw?.let { fixUrlNull(it) }
    }

    private fun Element.imdbScore(): Score? {
        val text = text().replace(',', '.')
        val value = Regex("(?i)(?:IMDb|IMDB)\\s*[:\\-]?\\s*([0-9]+(?:\\.[0-9]+)?)")
            .find(text)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
            ?: Regex("(?i)([0-9]+(?:\\.[0-9]+)?)\\s*(?:IMDb|IMDB)")
                .find(text)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        return value?.takeIf { it in 0.0..10.0 }?.let { Score.from10(it) }
    }

    private fun Element.cardTitle(): String? {
        val imageAlt = selectFirst("img[alt]")?.attr("alt")?.trim()
            ?.removeSuffix(" izle")?.removeSuffix(" İzle")
            ?.takeIf { it.isNotEmpty() }
        if (imageAlt != null) return imageAlt

        val titleAttr = attr("title").trim().removeSuffix(" izle").removeSuffix(" İzle")
        if (titleAttr.isNotEmpty()) return titleAttr

        val linkTitle = selectFirst("a[title]")?.attr("title")?.trim()
            ?.removeSuffix(" izle")?.removeSuffix(" İzle")
        if (!linkTitle.isNullOrEmpty()) return linkTitle

        return selectFirst(
            ".card-info h3, .card-info h2, .card-content h3, .card-content h2, " +
            ".card-title, .movie-title, .series-title, .content-title, h3, h2, h4, h5, .title"
        )?.text()?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun Element.cardHref(): String? {
        val candidates = mutableListOf<String>()
        attr("href").trim().takeIf { it.isNotEmpty() }?.let { candidates += it }
        select("a[href]").forEach { a ->
            a.attr("href").trim().takeIf { it.isNotEmpty() }?.let { candidates += it }
        }
        var parent = parent()
        repeat(3) {
            if (parent != null) {
                parent.attr("href").trim().takeIf { it.isNotEmpty() }?.let { candidates += it }
                parent = parent?.parent()
            }
        }
        return candidates.asSequence()
            .map { it.trim() }
            .firstOrNull {
                val href = it.lowercase()
                href.contains("/series/") || href.contains("/movies/")
            }
    }

    private fun Element.toSearch(): SearchResponse? {
        val href = fixUrlNull(cardHref()) ?: return null
        val title = cardTitle() ?: return null
        val poster = posterUrl()
        val score = imdbScore()
        return when {
            href.contains("/movies/", true) -> newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl = poster
                this.score = score
            }
            href.contains("/series/", true) -> newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = poster
                this.score = score
            }
            else -> null
        }
    }

    private fun Element.toEpisodeSearch(): SearchResponse? {
        val href = fixUrlNull(attr("href").trim()) ?: return null
        if (!href.contains("/bolum/")) return null
        val title = selectFirst("h2, h3, h4, .title")?.text()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: selectFirst("img[alt]")?.attr("alt")?.trim()?.takeIf { it.isNotEmpty() }
            ?: text().trim().takeIf { it.isNotEmpty() }
            ?: return null
        val poster = posterUrl()
        val score = imdbScore()
        val seriesUrl = href.replace("/bolum/", "/series/")
            .replace(Regex("-\\d+x\\d+$"), "")
        return newTvSeriesSearchResponse(title, seriesUrl, TvType.TvSeries) {
            posterUrl = poster
            this.score = score
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/diziler?kelime=${URLEncoder.encode(query, "UTF-8")}&durum=&tur=&type=&siralama="
        return app.get(url).document
            .select("a[href*='/series/'], a[href*='/movies/']")
            .mapNotNull { it.toSearch() }
            .distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    private fun Document.pagePoster(): String? =
        fixUrlNull(selectFirst("meta[property='og:image']")?.attr("content"))

    private fun Document.pageYear(): Int? =
        Regex("\\b(19|20)\\d{2}\\b").find(text())?.value?.toIntOrNull()

    private fun Document.pageScore(): Score? {
        val text = text().replace(',', '.')
        val value = Regex("(?i)(?:IMDb|IMDB)\\s*[:\\-]?\\s*([0-9]+(?:\\.[0-9]+)?)")
            .find(text)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        return value?.takeIf { it in 0.0..10.0 }?.let { Score.from10(it) }
    }

    private fun Document.pageTags(): List<String> {
        val row = select("div.info-row").firstOrNull { it.text().contains("Kategor", true) }
        return row?.select("a")?.map { it.text().trim() }?.filter { it.isNotEmpty() }?.distinct() ?: emptyList()
    }

    private fun Document.pageActors(): List<ActorData> {
        val nodes = select(".cast-item, .actor-item, .cast-member, .actor")
        return nodes.mapNotNull { node ->
            val img = node.selectFirst("img")
            val image = img?.let {
                listOf("data-src", "data-lazy-src", "data-original", "src")
                    .asSequence().map { key -> it.attr(key).trim() }
                    .firstOrNull { value -> value.isNotEmpty() && !value.startsWith("data:image") }
                    ?.let { value -> fixUrlNull(value) }
            }
            val name = node.selectFirst(".actor-name, .cast-name, .name, h4, h5, strong")?.text()?.trim()
                ?: node.ownText().trim().takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            ActorData(Actor(name, image))
        }.distinctBy { it.actor.name }.take(30)
    }

    override suspend fun load(url: String): LoadResponse? {
        if (url.contains("/bolum/")) {
            val seriesUrl = url.replace("/bolum/", "/series/").replace(Regex("-\\d+x\\d+$"), "")
            return load(seriesUrl)
        }

        val document = app.get(url).document
        val poster = document.pagePoster()
        val year = document.pageYear()
        val plot = document.selectFirst("p.series-description, .series-description, .movie-description")?.text()?.trim()
        val tags = document.pageTags()
        val score = document.pageScore()
        val actors = document.pageActors()

        if (url.contains("/series/")) {
            val title = document.selectFirst("h1.series-title, h1")?.text()?.trim()
                ?: document.selectFirst("meta[property='og:title']")?.attr("content")?.substringBefore(" izle")?.trim()
                ?: return null

            val episodes = document.select("a[href*='/bolum/']")
                .mapNotNull { a ->
                    val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
                    val match = Regex("-(\\d+)x(\\d+)$").find(href) ?: return@mapNotNull null
                    val episodeTitle = a.selectFirst("h2, h3, h4, .title")?.text()?.trim()
                        ?: a.text().trim().ifEmpty { "${match.groupValues[1]}. Sezon ${match.groupValues[2]}. Bölüm" }
                    newEpisode(href) {
                        name = episodeTitle
                        season = match.groupValues[1].toIntOrNull()
                        episode = match.groupValues[2].toIntOrNull()
                    }
                }.distinctBy { it.data }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.score = score
                this.actors = actors
            }
        }

        val title = document.selectFirst("h1.movie-title, h1.series-title, h1")?.text()?.trim()
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")?.substringBefore(" izle")?.trim()
            ?: return null
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            posterUrl = poster
            this.year = year
            this.plot = plot
            this.tags = tags
            this.score = score
            this.actors = actors
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
        val response = app.get(data, headers = mapOf("User-Agent" to ua, "Cache-Control" to "no-cache", "Pragma" to "no-cache"))
        val token = response.document.selectFirst("#videoContainer")?.attr("data-cfg")?.trim()
        if (token.isNullOrEmpty()) return false

        val padded = token + "=".repeat((4 - token.length % 4) % 4)
        val decoded = try { String(Base64.decode(padded, Base64.DEFAULT)) } catch (_: Exception) { return false }
        val embedRaw = Regex("\\\"v\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").find(decoded)?.groupValues?.getOrNull(1)?.replace("\\/", "/") ?: return false
        val embedUrl = fixUrl(embedRaw)

        if (embedUrl.contains("imagestoo")) {
            val videoId = embedUrl.trimEnd('/').substringAfterLast("/")
            loadExtractor("https://imagestoo.com/video/$videoId", data, subtitleCallback, callback)
        } else {
            loadExtractor(embedUrl, data, subtitleCallback, callback)
        }
        return true
    }
}
