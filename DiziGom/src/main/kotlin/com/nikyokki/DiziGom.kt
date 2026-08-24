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
        "Aksiyon", "Animasyon", "Belgesel", "Bilim Kurgu", "Dram", "Fantastik",
        "Gerilim", "Komedi", "Korku", "Macera", "Romantik", "Suç", "Tarih"
    )

    private val pageMutex = Mutex()

    override val mainPage = mainPageOf(
        "Diziler" to "$mainUrl/diziler/",
        "Yeni Diziler" to "$mainUrl/yeni-diziler/",
        "Popüler Diziler" to "$mainUrl/populer-diziler/",
        "Aksiyon" to "$mainUrl/tur/aksiyon/",
        "Animasyon" to "$mainUrl/tur/animasyon/",
        "Belgesel" to "$mainUrl/tur/belgesel/",
        "Bilim Kurgu" to "$mainUrl/tur/bilim-kurgu/",
        "Dram" to "$mainUrl/tur/dram/",
        "Fantastik" to "$mainUrl/tur/fantastik/",
        "Gerilim" to "$mainUrl/tur/gerilim/",
        "Komedi" to "$mainUrl/tur/komedi/",
        "Korku" to "$mainUrl/tur/korku/",
        "Macera" to "$mainUrl/tur/macera/",
        "Romantik" to "$mainUrl/tur/romantik/",
        "Suç" to "$mainUrl/tur/suc/",
        "Tarih" to "$mainUrl/tur/tarih/"
    )

    private fun Element.firstText(vararg selectors: String): String? {
        selectors.forEach { selector ->
            val text = selectFirst(selector)?.text()?.trim()
            if (!text.isNullOrBlank()) return text
        }
        return null
    }

    private fun Element.extractPoster(): String? {
        val element = this
        return listOf(
            element.attr("data-src"),
            element.attr("data-lazy-src"),
            element.attr("src")
        ).firstOrNull { it.isNotBlank() }?.let(::fixUrlNull)
    }

    private fun Element.extractBackgroundUrl(): String? {
        val style = attr("style")
        val match = Regex("url\\(['\\\"]?([^'\\\")]+)").find(style)
        return match?.groupValues?.getOrNull(1)?.let(::fixUrlNull)
    }

    private fun normalizeGenre(value: String): String =
        value.lowercase().replace("ı", "i").replace("ş", "s").replace("ğ", "g")
            .replace("ü", "u").replace("ö", "o").replace("ç", "c").trim()

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return pageMutex.withLock {
            val url = if (request.data.contains("?")) {
                "${request.data}&page=$page"
            } else {
                "${request.data.trimEnd('/')}/page/$page/"
            }
            val document = app.get(url, referer = "$mainUrl/").document
            val items = document.select(
                "article, .post-item, .film, .movie-item, .series-item, .item, .filmItem"
            ).mapNotNull { item ->
                val link = item.selectFirst("a[href]") ?: return@mapNotNull null
                val href = fixUrlNull(link.attr("href")) ?: return@mapNotNull null
                val title = item.selectFirst("h2, h3, h4, .title, .filmTitle, .post-title")
                    ?.text()?.trim()?.takeIf { it.isNotBlank() }
                    ?: link.attr("title").trim().takeIf { it.isNotBlank() }
                    ?: link.text().trim().takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val poster = item.selectFirst("img")?.extractPoster()
                newTvSeriesSearchResponse(title, href, poster) {}
            }.distinctBy { it.url }

            newHomePageResponse(items, hasNext = items.isNotEmpty())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(url, referer = "$mainUrl/").document
        return document.select(
            "article, .post-item, .film, .movie-item, .series-item, .item, .filmItem"
        ).mapNotNull { item ->
            val link = item.selectFirst("a[href]") ?: return@mapNotNull null
            val href = fixUrlNull(link.attr("href")) ?: return@mapNotNull null
            val title = item.selectFirst("h2, h3, h4, .title, .filmTitle, .post-title")
                ?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: link.attr("title").trim().takeIf { it.isNotBlank() }
                ?: link.text().trim().takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val poster = item.selectFirst("img")?.extractPoster()
            newTvSeriesSearchResponse(title, href, poster) {}
        }.distinctBy { it.url }
    }

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
            // Episode stores the source URL in `data`; it does not expose a `url` property.
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

        val links = document.select("a[href], source[src], iframe[src]")
            .mapNotNull { element ->
                val href = element.attr("href").ifBlank { element.attr("src") }
                fixUrlNull(href)
            }
            .distinct()

        var found = false
        for (link in links) {
            if (link.contains("dizigom", ignoreCase = true)) continue
            if (link.startsWith("javascript:", ignoreCase = true)) continue
            runCatching {
                val result = loadExtractor(link, data, subtitleCallback, callback)
                if (result) found = true
            }
        }

        if (!found) {
            links.filter { it.contains("m3u8", ignoreCase = true) || it.contains("\.mp4", ignoreCase = true) }
                .forEach { link ->
                    callback(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = link,
                            type = if (link.contains("m3u8", ignoreCase = true)) {
                                ExtractorLinkType.M3U8
                            } else {
                                ExtractorLinkType.VIDEO
                            }
                        ) {
                            referer = data
                        }
                    )
                    found = true
                }
        }
        return found
    }
}
