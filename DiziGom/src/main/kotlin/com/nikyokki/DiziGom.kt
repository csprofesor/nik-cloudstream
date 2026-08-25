package com.nikyokki

import android.util.Log
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
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
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import java.net.URLEncoder

class DiziGom : MainAPI() {
    override var mainUrl = "https://www.dizigom.love"
    override var name = "DiziGom"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries)

    private val genres = listOf(
        "Aksiyon", "Animasyon", "Belgesel", "Bilim Kurgu", "Biyografi", "Dram",
        "Fantastik", "Gençlik", "Gerilim", "Gizem", "Komedi", "Korku", "Macera",
        "Polisiye", "Romantik", "Savaş", "Suç", "Tarih"
    )

    override val mainPage = mainPageOf(
        *genres.map { "$mainUrl/dizi-izle/?tur=${URLEncoder.encode(it, "UTF-8")}" to it }.toTypedArray()
    )

    private fun genreUrl(genre: String, page: Int): String {
        val encoded = URLEncoder.encode(genre, "UTF-8")
        return if (page <= 1) {
            "$mainUrl/dizi-izle/?tur=$encoded"
        } else {
            "$mainUrl/dizi-izle/page/$page/?tur=$encoded"
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(genreUrl(request.name, page), referer = "$mainUrl/").document
        val results = document
            .select("a[href*='/diziler/']")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, results)
    }

    private fun Element.findSeriesCard(): Element? {
        return generateSequence(this as Element?) { it.parent() }
            .take(10)
            .firstOrNull { element ->
                val seriesLinks = element
                    .select("a[href*='/diziler/']")
                    .mapNotNull { fixUrlNull(it.attr("href")) }
                    .distinct()
                seriesLinks.size == 1 && element.selectFirst("img") != null
            }
    }

    private fun Element.extractPoster(): String? {
        val image = selectFirst("img") ?: return null
        val raw = listOf(
            image.attr("data-src"),
            image.attr("data-lazy-src"),
            image.attr("data-original"),
            image.attr("data-image"),
            image.attr("data-fallback-src"),
            image.attr("src"),
            image.attr("data-srcset"),
            image.attr("srcset")
        )
            .firstOrNull { it.isNotBlank() }
            ?.substringBefore(',')
            ?.trim()

        val styleUrl = Regex("url\\((?:\\\"|')?([^\\\"')]+)", RegexOption.IGNORE_CASE)
            .find(image.attr("style"))
            ?.groupValues
            ?.getOrNull(1)

        return fixUrlNull(raw ?: styleUrl.orEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(attr("href")) ?: return null
        val card = findSeriesCard() ?: return null
        val title = sequenceOf(
            selectFirst("img")?.attr("alt"),
            text(),
            attr("title")
        )
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
            ?: return null

        val cardText = card.text().replace(Regex("\\s+"), " ").trim()
        val rating = Regex(
            "(?:IMDb|IMDB)\\s*:?\\s*([0-9]+(?:[.,][0-9]+)?)",
            RegexOption.IGNORE_CASE
        ).find(cardText)?.groupValues?.getOrNull(1)

        val poster = extractPoster() ?: card.extractPoster()

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            posterUrl = poster
            score = Score.from10(rating)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = query.trim().replace(" ", "+")
        val document = app.get(
            "$mainUrl/?s=$encoded",
            referer = "$mainUrl/"
        ).document

        return document
            .select("a[href*='/diziler/']")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    private fun Element.extractBackgroundUrl(): String? {
        return Regex("url\\((?:\\\"|')?([^\\\"')]+)", RegexOption.IGNORE_CASE)
            .find(attr("style"))
            ?.groupValues
            ?.getOrNull(1)
            ?.let { fixUrlNull(it) }
    }

    private fun Element.firstText(vararg selectors: String): String? =
        selectors.asSequence()
            .mapNotNull { selectFirst(it)?.text()?.trim() }
            .firstOrNull { it.isNotBlank() }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, referer = "$mainUrl/").document

        val title = document.firstText(
            "div.serieTitle h1",
            ".serieTitle h1",
            "h1.entry-title",
            "article h1",
            "h1"
        ) ?: return null

        val poster = document.selectFirst("div.seriePoster")?.extractBackgroundUrl()
            ?: document.selectFirst("div.seriePoster img")?.extractPoster()
            ?: document.selectFirst("meta[property='og:image']")?.attr("content")?.let { fixUrlNull(it) }
            ?: document.selectFirst("article img")?.extractPoster()

        val description = document.firstText(
            "div.serieDescription p",
            ".serieDescription p",
            ".description p",
            ".entry-content p"
        )

        val year = Regex("(?:Yapım Yılı|Yapim Yili)\\s*:?\\s*(\\d{4})", RegexOption.IGNORE_CASE)
            .find(document.text())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()

        val rating = Regex(
            "(?:IMDB|IMDb)\\s*:?\\s*([0-9]+(?:[.,][0-9]+)?)",
            RegexOption.IGNORE_CASE
        ).find(document.text())?.groupValues?.getOrNull(1)

        val tags = document.select("div.genreList a, .genreList a, a[href*='/tur/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }

        val actors = document.select("div.owl-stage a, .cast a, .actors a")
            .mapNotNull { actorLink ->
                val actor = actorLink.text().trim()
                if (actor.isBlank()) return@mapNotNull null
                Actor(actor, actorLink.selectFirst("img")?.extractPoster())
            }
            .distinctBy { it.name }

        val episodes = document
            .select("a[href*='-sezon-'][href*='-bolum']")
            .mapNotNull { episodeLink ->
                val href = fixUrlNull(episodeLink.attr("href")) ?: return@mapNotNull null
                val label = episodeLink.text().trim()
                val source = "$label ${episodeLink.attr("title")}".trim()

                val season = Regex("(\\d+)\\s*\\.?\\s*Sezon", RegexOption.IGNORE_CASE)
                    .find(source)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: Regex("-(\\d+)-sezon-", RegexOption.IGNORE_CASE)
                        .find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()

                val episode = Regex("(\\d+)\\s*\\.?\\s*Bölüm", RegexOption.IGNORE_CASE)
                    .find(source)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: Regex("-(\\d+)-bolum", RegexOption.IGNORE_CASE)
                        .find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()

                if (season == null || episode == null) return@mapNotNull null

                newEpisode(href) {
                    name = label
                        .replace(Regex("^İzledim\\s*", RegexOption.IGNORE_CASE), "")
                        .trim()
                    this.season = season
                    this.episode = episode
                }
            }
            .distinctBy { it.data }
            .sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            posterUrl = poster
            this.year = year
            plot = description
            this.tags = tags
            score = Score.from10(rating)
            addActors(actors)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, referer = "$mainUrl/").document
        Log.d("DiziGom", "Episode: $data")

        val iframeUrls = document
            .select("iframe[src], iframe[data-src], iframe[data-lazy-src], .player iframe, div#content iframe")
            .mapNotNull { iframe ->
                fixUrlNull(
                    iframe.attr("src")
                        .ifBlank { iframe.attr("data-src") }
                        .ifBlank { iframe.attr("data-lazy-src") }
                )
            }
            .filter { it.isNotBlank() }
            .distinct()

        var matched = false

        for (iframe in iframeUrls) {
            if (iframe.contains(".m3u8", true)) {
                callback(newExtractorLink(name, "$name HLS", iframe, ExtractorLinkType.M3U8) {
                    referer = "$mainUrl/"
                })
                matched = true
            } else if (iframe.contains(".mp4", true)) {
                callback(newExtractorLink(name, "$name MP4", iframe, ExtractorLinkType.VIDEO) {
                    referer = "$mainUrl/"
                })
                matched = true
            } else {
                matched = runCatching {
                    loadExtractor(iframe, "$mainUrl/", subtitleCallback, callback)
                }.getOrDefault(false) || matched
            }
        }

        if (!matched) {
            val contentUrls = Regex(
                "[\\\"']contentUrl[\\\"']\\s*:\\s*[\\\"']([^\\\"']+)[\\\"']",
                RegexOption.IGNORE_CASE
            )
                .findAll(document.html())
                .map { it.groupValues[1] }
                .mapNotNull { fixUrlNull(it) }
                .distinct()

            for (videoUrl in contentUrls) {
                matched = runCatching {
                    loadExtractor(videoUrl, "$mainUrl/", subtitleCallback, callback)
                }.getOrDefault(false) || matched
            }
        }

        return matched
    }
}