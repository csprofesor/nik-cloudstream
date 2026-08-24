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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.nodes.Element

class DiziGom : MainAPI() {
    override var mainUrl = "https://www.dizigom.love"
    override var name = "DiziGom"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries)

    // These are the categories currently exposed by DiziGom's archive.
    private val genres = listOf(
        "Aksiyon", "Animasyon", "Belgesel", "Bilim Kurgu", "Biyografi", "Dram",
        "Fantastik", "Gençlik", "Gerilim", "Gizem", "Komedi", "Korku", "Macera",
        "Polisiye", "Romantik", "Savaş", "Suç", "Tarih"
    )

    override val mainPage = mainPageOf(
        *genres.map { "$mainUrl/dizi-izle/" to it }.toTypedArray()
    )

    private val archiveMutex = Mutex()
    private var archiveCache: List<SearchResponse>? = null
    private val genreCache = mutableMapOf<String, Set<String>>()

    private suspend fun getArchive(): List<SearchResponse> {
        archiveCache?.let { return it }

        return archiveMutex.withLock {
            archiveCache?.let { return@withLock it }

            val allResults = mutableListOf<SearchResponse>()

            // DiziGom currently exposes 27 archive pages. Stop automatically
            // when a future page contains no real series cards.
            for (page in 1..100) {
                val url = if (page == 1) {
                    "$mainUrl/dizi-izle/"
                } else {
                    "$mainUrl/dizi-izle/page/$page/"
                }

                val document = runCatching {
                    app.get(url, referer = "$mainUrl/").document
                }.getOrNull() ?: break

                val pageResults = document
                    .select("a[href*='/diziler/']")
                    .mapNotNull { it.toArchiveResult() }
                    .distinctBy { it.url }

                if (pageResults.isEmpty()) break
                allResults += pageResults
            }

            archiveCache = allResults.distinctBy { it.url }
            archiveCache.orEmpty()
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val wantedGenre = normalizeGenre(request.name)
        val archive = getArchive()

        val filtered = archive.filter { result ->
            genreCache[result.url].orEmpty().any { normalizeGenre(it) == wantedGenre }
        }

        // Keep CloudStream pagination useful instead of dumping the whole
        // archive into one row.
        val pageSize = 20
        val pageResults = filtered
            .drop((page - 1).coerceAtLeast(0) * pageSize)
            .take(pageSize)

        return newHomePageResponse(request.name, pageResults)
    }

    private fun Element.findSeriesCard(href: String): Element? {
        return generateSequence(this as Element?) { it.parent() }
            .take(10)
            .firstOrNull { element ->
                val seriesLinks = element
                    .select("a[href*='/diziler/']")
                    .mapNotNull { fixUrlNull(it.attr("href")) }
                    .distinct()

                // A real card contains exactly one series URL and an image.
                // This prevents the site's alphabet/sidebar containers from
                // supplying a poster belonging to another series.
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

    private fun Element.toArchiveResult(): SearchResponse? {
        val href = fixUrlNull(attr("href")) ?: return null
        val card = findSeriesCard(href) ?: return null

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

        val typeText = Regex(
            "Tür\\s*:\\s*(.*?)(?=\\s+Favorilere Ekle\\b|$)",
            RegexOption.IGNORE_CASE
        ).find(cardText)?.groupValues?.getOrNull(1).orEmpty()

        val detectedGenres = typeText
            .split(",", "|", "/")
            .map { it.trim() }
            .filter { value ->
                genres.any { normalizeGenre(it) == normalizeGenre(value) }
            }
            .distinctBy(::normalizeGenre)

        genreCache[href] = detectedGenres.toSet()

        // Prefer the poster inside this exact link. Otherwise use the image
        // inside the exact single-series card.
        val poster = extractPoster() ?: card.extractPoster()

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            posterUrl = poster
            score = Score.from10(rating)
        }
    }

    private fun normalizeGenre(value: String): String = value
        .trim()
        .lowercase()
        .replace("ı", "i")
        .replace("ş", "s")
        .replace("ğ", "g")
        .replace("ü", "u")
        .replace("ö", "o")
        .replace("ç", "c")
        .replace("fantazi", "fantastik")
        .replace(Regex("\\s+"), " ")

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = query.trim().replace(" ", "+")
        val document = app.get(
            "$mainUrl/?s=$encoded",
            referer = "$mainUrl/"
        ).document

        return document
            .select("a[href*='/diziler/']")
            .mapNotNull { it.toArchiveResult() }
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
            .distinctBy(::normalizeGenre)

        val actors = document.select("div.owl-stage a, .cast a, .actors a")
            .mapNotNull { actorLink ->
                val actor = actorLink.text().trim()
                if (actor.isBlank()) return@mapNotNull null
                Actor(
                    actor,
                    actorLink.selectFirst("img")?.extractPoster()
                )
            }
            .distinctBy { it.name }

        val episodes = document
            .select("a[href*='-sezon-'][href*='-bolum']")
            .mapNotNull { episodeLink ->
                val href = fixUrlNull(episodeLink.attr("href")) ?: return@mapNotNull null
                val label = episodeLink.text().trim()
                val source = "$label ${episodeLink.attr("title")}".trim()

                val season = Regex("(\\d+)\\s*\\.?\\s*Sezon", RegexOption.IGNORE_CASE)
                    .find(source)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                    ?: Regex("-(\\d+)-sezon-", RegexOption.IGNORE_CASE)
                        .find(href)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()

                val episode = Regex("(\\d+)\\s*\\.?\\s*Bölüm", RegexOption.IGNORE_CASE)
                    .find(source)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                    ?: Regex("-(\\d+)-bolum", RegexOption.IGNORE_CASE)
                        .find(href)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()

                if (season == null || episode == null) return@mapNotNull null

                newEpisode(href) {
                    name = label
                        .replace(Regex("^İzledim\\s*", RegexOption.IGNORE_CASE), "")
                        .trim()
                    this.season = season
                    this.episode = episode
                }
            }
            .distinctBy { it.url }
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
