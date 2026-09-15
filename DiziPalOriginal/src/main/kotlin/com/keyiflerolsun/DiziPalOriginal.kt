package com.keyiflerolsun

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document
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
        "$mainUrl/platform/netflix" to "Netflix",
        "$mainUrl/platform/exxen" to "Exxen",
        "$mainUrl/platform/blutv" to "BluTV",
        "$mainUrl/platform/disney-plus" to "Disney+",
        "$mainUrl/platform/prime-video" to "Amazon Prime",
        "$mainUrl/platform/tabii" to "Tabii",
        "$mainUrl/platform/gain" to "Gain",
        "$mainUrl/platform/max" to "Max"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val items = if (request.data.contains("/bolumler")) {
            document.select("div.episodes-list-grid > a.episode-list-item, a[href*='/bolum/'], .episode-list-item")
                .mapNotNull { it.toEpisodeSearch() }
                .distinctBy { it.url }
        } else {
            val cards = document.select(
                "ul.content-grid > li, " +
                "article.type2 ul li, " +
                "li, article, " +
                ".card, [class*='card-'], [class*='-card']"
            )

            cards.mapNotNull { it.toSearch() }
                .plus(
                    document.select("a[href*='/dizi/'], a[href*='/film/']")
                        .mapNotNull { it.toSearch() }
                )
                .distinctBy { it.url }
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
        val title = selectFirst(
            "div.card-info h3, .card-info h3, .card-info h2, .card-info h4, " +
            ".card-content h3, .card-content h2, .card-body h3, .card-body h2, " +
            ".content-title, .card-title, .movie-title, .series-title, " +
            "h3, h2, h4, h5, .title"
        )?.text()?.trim()
        if (!title.isNullOrEmpty()) return title

        val ownTitle = attr("title").trim().takeIf { it.isNotEmpty() }
            ?: selectFirst("a[title]")?.attr("title")?.trim()?.takeIf { it.isNotEmpty() }
            ?: selectFirst("img[alt]")?.attr("alt")?.trim()?.takeIf { it.isNotEmpty() }
        if (!ownTitle.isNullOrEmpty()) return ownTitle

        var parent = parent()
        repeat(3) {
            if (parent != null) {
                val pTitle = parent.selectFirst(
                    "div.card-info h3, .card-info h3, .card-title, .movie-title, .series-title, h3, h2, h4, .title"
                )?.text()?.trim()
                if (!pTitle.isNullOrEmpty()) return pTitle
                parent = parent?.parent()
            }
        }
        return null
    }

    private fun Element.cardHref(): String? {
        val candidates = mutableListOf<String>()

        val own = attr("href").trim()
        if (own.isNotEmpty()) candidates += own

        select("a[href]").forEach { a ->
            val href = a.attr("href").trim()
            if (href.isNotEmpty()) candidates += href
        }

        // Kartın kendisi linkin içinde olabilir. Bu durumda link, kartın
        // descendant'ı değil ancestor'ıdır; önceki parser bu yapıyı kaçırıyordu.
        var parent = parent()
        repeat(3) {
            if (parent != null) {
                val href = parent.attr("href").trim()
                if (href.isNotEmpty()) candidates += href
                parent = parent?.parent()
            }
        }

        return candidates
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .firstOrNull { href ->
                val normalized = href.lowercase()
                normalized.contains("/dizi/") || normalized.contains("/film/")
            }
    }

    private fun Element.toSearch(): SearchResponse? {
        val href = fixUrlNull(cardHref()) ?: return null
        val title = cardTitle() ?: return null
        val poster = posterUrl()
        val score = imdbScore()

        return if (href.lowercase().contains("/film/")) {
            newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl = poster
                this.score = score
            }
        } else if (href.lowercase().contains("/dizi/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = poster
                this.score = score
            }
        } else {
            null
        }
    }

    private fun Element.toEpisodeSearch(): SearchResponse? {
        val href = fixUrlNull(attr("href").ifBlank { selectFirst("a[href*='/bolum/']")?.attr("href") }) ?: return null
        val rawTitle = selectFirst(".ep-title, .episode-title, h3, h4, .title")?.text()?.trim()
            ?: text().trim().takeIf { it.isNotEmpty() }
            ?: return null
        val info = selectFirst(".ep-info, .episode-info, .ep-subtitle")?.text()?.trim() ?: ""
        val combined = listOf(rawTitle, info).filter { it.isNotBlank() }.joinToString(" ")
        val poster = posterUrl()
        val score = imdbScore()
        val title = combined.replace(Regex("\\s+"), " ").trim()
        val seriesUrl = href
            .replace(Regex("-\\d+-sezon-\\d+-bolum.*$"), "")
            .replace("/bolum/", "/dizi/")

        return newTvSeriesSearchResponse(title, seriesUrl, TvType.TvSeries) {
            posterUrl = poster
            this.score = score
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/diziler?kelime=${URLEncoder.encode(query, "UTF-8")}&durum=&tur=&type=&siralama="
        return app.get(url).document
            .select("ul.content-grid > li, article.type2 ul li, li, article, .card, [class*='card-'], [class*='-card'], a[href*='/dizi/'], a[href*='/film/']")
            .mapNotNull { it.toSearch() }
            .distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    private fun Document.pagePoster(): String? = fixUrlNull(selectFirst("meta[property='og:image']")?.attr("content"))

    private fun Document.pageYear(): Int? {
        val labelled = select("div.info-row").firstOrNull { it.text().contains("Yıl", true) }?.text()
        return Regex("\\b(19|20)\\d{2}\\b").find(labelled ?: text())?.value?.toIntOrNull()
    }

    private fun Document.pageScore(): Score? {
        val text = text().replace(',', '.')
        val value = Regex("(?i)(?:IMDb|IMDB)\\s*[:\\-]?\\s*([0-9]+(?:\\.[0-9]+)?)")
            .find(text)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
            ?: Regex("(?i)([0-9]+(?:\\.[0-9]+)?)\\s*(?:IMDb|IMDB)")
                .find(text)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        return value?.takeIf { it in 0.0..10.0 }?.let { Score.from10(it) }
    }

    private fun Document.pageTags(): List<String> {
        val row = select("div.info-row").firstOrNull { it.text().contains("Kategor", true) }
        return row?.select("a")?.map { it.text().trim() }?.filter { it.isNotEmpty() }?.distinct() ?: emptyList()
    }

    private fun Document.pageActors(): List<ActorData> {
        val heading = select("h2, h3, h4, .section-title, .title").firstOrNull {
            it.text().trim().equals("Oyuncular", true) || it.text().trim().equals("Oyuncu Kadrosu", true)
        }
        val roots = listOfNotNull(heading?.parent(), heading?.parent()?.parent())
        val nodes = roots.asSequence().flatMap { root ->
            root.select(".cast-item, .actor-item, .cast-member, .actor, .cast-list > *, .actors-list > *, .actors > *, .cast > *, li").asSequence()
        }.distinct().toList()
        val fallback = if (nodes.isNotEmpty()) nodes else select(".cast-item, .actor-item, .cast-member, .actor").toList()

        return fallback.mapNotNull { node ->
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
            val fullText = node.text().trim()
            val role = fullText.removePrefix(name).trim().takeIf { it.isNotEmpty() && it.length < 100 }
            ActorData(Actor(name, image), roleString = role)
        }.distinctBy { it.actor.name }.take(30)
    }

    override suspend fun load(url: String): LoadResponse? {
        if (url.contains("/bolum/")) {
            val seriesUrl = url.replace("/bolum/", "/dizi/").replace(Regex("-\\d+-sezon.*"), "")
            return load(seriesUrl)
        }

        val document = app.get(url).document
        val poster = document.pagePoster()
        val year = document.pageYear()
        val plot = document.selectFirst("p.series-description, .series-description, .movie-description")?.text()?.trim()
        val tags = document.pageTags()
        val score = document.pageScore()
        val actors = document.pageActors()

        if (url.contains("/dizi/")) {
            val title = document.selectFirst("h1.series-title, h1")?.text()?.trim() ?: return null
            val episodes = document.select("div.detail-episode-item-wrap, .detail-episode-item-wrap")
                .mapNotNull { wrap ->
                    val a = wrap.selectFirst("a.detail-episode-item, a") ?: return@mapNotNull null
                    val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
                    val name = a.selectFirst("div.detail-episode-title, .detail-episode-title")?.text()?.trim()
                        ?: a.text().trim()
                    val subtitle = a.selectFirst("div.detail-episode-subtitle, .detail-episode-subtitle")?.text()?.trim() ?: ""
                    val match = Regex("""(\\d+)\\.\\s*[Ss]ezon\\s*(\\d+)\\.\\s*[Bb]ölüm""").find(subtitle.ifBlank { a.text() })
                    newEpisode(href) {
                        this.name = name
                        season = match?.groupValues?.getOrNull(1)?.toIntOrNull()
                        episode = match?.groupValues?.getOrNull(2)?.toIntOrNull()
                    }
                }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.score = score
                this.actors = actors
            }
        }

        val title = document.selectFirst("h1.series-title, h1.movie-title, h1")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" izle")?.trim()
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
        val embedRaw = Regex("""\"v\"\s*:\s*\"([^\"]+)\"""").find(decoded)?.groupValues?.getOrNull(1)?.replace("\\/", "/") ?: return false
        val embedUrl = fixUrl(embedRaw)

        if (embedUrl.contains("imagestoo")) {
            val videoId = embedUrl.trimEnd('/').substringAfterLast("/")
            val streamUrl = "https://imagestoo.com/video/$videoId"
            loadExtractor(streamUrl, data, subtitleCallback, callback)
        } else {
            loadExtractor(embedUrl, data, subtitleCallback, callback)
        }
        return true
    }
}