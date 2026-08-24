package com.nikyokki

import android.util.Log
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.Episode
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

class DiziGom : MainAPI() {
    override var mainUrl = "https://www.dizigom.love"
    override var name = "DiziGom"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/tur/aile/" to "Aile",
        "$mainUrl/tur/aksiyon/" to "Aksiyon",
        "$mainUrl/tur/animasyon/" to "Animasyon",
        "$mainUrl/tur/belgesel/" to "Belgesel",
        "$mainUrl/tur/bilim-kurgu/" to "Bilim Kurgu",
        "$mainUrl/tur/dram/" to "Dram",
        "$mainUrl/tur/fantastik/" to "Fantastik",
        "$mainUrl/tur/gerilim/" to "Gerilim",
        "$mainUrl/tur/komedi/" to "Komedi",
        "$mainUrl/tur/korku/" to "Korku",
        "$mainUrl/tur/macera/" to "Macera",
        "$mainUrl/tur/polisiye/" to "Polisiye",
        "$mainUrl/tur/romantik/" to "Romantik",
        "$mainUrl/tur/savas/" to "Savaş",
        "$mainUrl/tur/suc/" to "Suç",
        "$mainUrl/tur/tarih/" to "Tarih"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data, referer = "$mainUrl/").document
        return newHomePageResponse(
            request.name,
            document.select("div.episode-box").mapNotNull { it.toResult() }
        )
    }

    private fun Element.toResult(): SearchResponse? {
        val title = selectFirst("div.serie-name a")?.text()?.trim() ?: return null
        val href = fixUrlNull(selectFirst("a")?.attr("href")) ?: return null
        val img = selectFirst("img")
        val poster = fixUrlNull(img?.attr("data-src").orEmpty().ifBlank { img?.attr("src").orEmpty() })
        val score = selectFirst("div.episode-date")?.text()?.substringAfter("IMDb:", "")?.trim()
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            posterUrl = poster
            this.score = Score.from10(score)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=${query.trim().replace(" ", "+")}").document
        return document.select("div.single-item").mapNotNull { element ->
            val link = element.selectFirst("div.categorytitle a") ?: return@mapNotNull null
            val title = link.text().trim()
            val href = fixUrlNull(link.attr("href")) ?: return@mapNotNull null
            val img = element.selectFirst("img")
            val poster = fixUrlNull(img?.attr("data-src").orEmpty().ifBlank { img?.attr("src").orEmpty() })
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { posterUrl = poster }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, referer = "$mainUrl/").document
        val title = document.selectFirst("div.serieTitle h1")?.text()?.trim() ?: return null
        val poster = fixUrlNull(
            document.selectFirst("div.seriePoster")?.attr("style")
                ?.substringAfter("background-image:url(")
                ?.substringBefore(")")
                ?.trim(' ', '\'', '"')
        )
        val description = document.selectFirst("div.serieDescription p")?.text()?.trim()
        val year = document.selectFirst("div.airDateYear a")?.text()?.trim()?.toIntOrNull()
        val tags = document.select("div.genreList a").map { it.text().trim() }.filter { it.isNotEmpty() }
        val rating = document.selectFirst("div.score")?.text()?.trim()
        val duration = document.select("div.serieMetaInformation div.totalSession")
            .lastOrNull()?.text()?.substringBefore(" ")?.toIntOrNull()
        val actors = document.select("div.owl-stage a").mapNotNull {
            val actor = it.text().trim()
            if (actor.isBlank()) null else Actor(actor, fixUrlNull(it.selectFirst("img")?.attr("src")))
        }
        val episodes = document.select("div.bolumust").mapNotNull {
            val href = fixUrlNull(it.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
            val parts = it.selectFirst("div.baslik")?.text()?.trim()?.split(" ") ?: emptyList()
            newEpisode(href) {
                name = it.selectFirst("div.bolum-ismi")?.text()?.trim()
                season = parts.getOrNull(0)?.replace(".", "")?.toIntOrNull()
                episode = parts.getOrNull(2)?.replace(".", "")?.toIntOrNull()
            }
        }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            posterUrl = poster
            this.year = year
            plot = description
            this.tags = tags
            this.duration = duration
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

        // Güncel DiziGom bölüm sayfalarında oynatıcı doğrudan iframe olarak geliyor.
        // Önce iframe'i alıp CloudStream'in güncel extractor sistemine bırakıyoruz.
        val iframeUrls = document.select("iframe[src], iframe[data-src], div#content iframe, .player iframe")
            .mapNotNull { element ->
                val raw = element.attr("src").ifBlank { element.attr("data-src") }
                fixUrlNull(raw)
            }
            .filter { it.isNotBlank() }
            .distinct()

        Log.d("DiziGom", "Iframe sources: $iframeUrls")

        var matched = false
        for (iframe in iframeUrls) {
            if (iframe.contains(".m3u8", ignoreCase = true)) {
                callback(
                    newExtractorLink(
                        source = name,
                        name = "$name HLS",
                        url = iframe,
                        type = ExtractorLinkType.M3U8
                    ) {
                        referer = "$mainUrl/"
                    }
                )
                matched = true
                continue
            }

            if (iframe.contains(".mp4", ignoreCase = true)) {
                callback(
                    newExtractorLink(
                        source = name,
                        name = "$name MP4",
                        url = iframe,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        referer = "$mainUrl/"
                    }
                )
                matched = true
                continue
            }

            val loaded = runCatching {
                loadExtractor(iframe, "$mainUrl/", subtitleCallback, callback)
            }.getOrDefault(false)
            matched = matched || loaded
        }

        // Bazı eski bölümlerde iframe yerine JSON-LD contentUrl bulunabiliyor.
        if (!matched) {
            val html = document.html()
            val urls = Regex("""[\"']contentUrl[\"']\s*:\s*[\"']([^\"']+)[\"']""")
                .findAll(html)
                .map { it.groupValues[1] }
                .mapNotNull { fixUrlNull(it) }
                .distinct()
                .toList()

            for (url in urls) {
                val loaded = runCatching {
                    loadExtractor(url, "$mainUrl/", subtitleCallback, callback)
                }.getOrDefault(false)
                matched = matched || loaded
            }
        }

        Log.d("DiziGom", "Extractor matched: $matched")
        return matched
    }
}
