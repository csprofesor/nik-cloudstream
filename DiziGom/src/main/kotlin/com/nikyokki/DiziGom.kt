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

class DiziGom : MainAPI() {
    override var mainUrl = "https://www.dizigom.love"
    override var name = "DiziGom"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries)

    // DiziGom categories use /tur/{slug}/. This is the site's current
    // category structure and avoids the old ?tur=... filtering.
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
        val document = app.get("${request.data}#p=$page", referer = "$mainUrl/").document

        if (page > 1) {
            val form = document.selectFirst("form.dizigom_advenced_search")
            val tax = form?.selectFirst("input[name]")?.attr("name")
            val value = form?.selectFirst("input[name]")?.attr("value")
            val nonce = form?.selectFirst("input[name=_wpnonce]")?.attr("value")
                ?: document.selectFirst("input[name=_wpnonce]")?.attr("value")

            if (!tax.isNullOrBlank() && !value.isNullOrBlank() && !nonce.isNullOrBlank()) {
                val pagedoc = app.post(
                    "$mainUrl/wp-admin/admin-ajax.php",
                    cookies = mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "Referer" to request.data
                    ),
                    data = mapOf(
                        "action" to "dizigom_search_action",
                        "formData" to "$tax=$value",
                        "paged" to page.toString(),
                        "_wpnonce" to nonce
                    )
                ).document

                val home = pagedoc
                    .select("div.episode-box")
                    .mapNotNull { it.toMainPageResult() }
                    .distinctBy { it.url }

                return newHomePageResponse(request.name, home)
            }
        }

        val home = document
            .select("div.episode-box")
            .mapNotNull { it.toMainPageResult() }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val title = selectFirst("div.serie-name a")?.text()?.trim()
            ?.ifBlank { null } ?: return null
        val href = fixUrlNull(selectFirst("a")?.attr("href")) ?: return null
        val poster = fixUrlNull(
            selectFirst("img")?.attr("data-src")
                ?.ifBlank { selectFirst("img")?.attr("src").orEmpty() }
        )
        val rating = selectFirst("div.episode-date")?.text()
            ?.replace("IMDb:", "", ignoreCase = true)
            ?.trim()

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            posterUrl = poster
            score = Score.from10(rating)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get(
            "$mainUrl/?s=${query.trim().replace(" ", "+")}"
        ).document

        return document.select("div.single-item")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = selectFirst("div.categorytitle a") ?: return null
        val title = link.text().trim()
        if (title.isBlank()) return null
        val href = fixUrlNull(link.attr("href")) ?: return null
        val poster = fixUrlNull(
            selectFirst("img")?.attr("data-src")
                ?.ifBlank { selectFirst("img")?.attr("src").orEmpty() }
        )

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            posterUrl = poster
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
        val tags = document.select("div.genreList a")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
        val rating = document.selectFirst("div.score")?.text()?.trim()
        val duration = document.select("div.serieMetaInformation div.totalSession")
            .lastOrNull()?.text()?.substringBefore(" ")?.toIntOrNull()
        val actors = document.select("div.owl-stage a").mapNotNull { a ->
            val actor = a.text().trim()
            if (actor.isBlank()) null
            else Actor(actor, fixUrlNull(a.selectFirst("img")?.attr("src")))
        }
        val episodes = document.select("div.bolumust").mapNotNull { e ->
            val href = fixUrlNull(e.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
            val parts = e.selectFirst("div.baslik")?.text()?.trim()?.split(" ") ?: emptyList()
            newEpisode(href) {
                name = e.selectFirst("div.bolum-ismi")?.text()?.trim()
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

        val iframeUrls = document
            .select("iframe[src], iframe[data-src], div#content iframe, .player iframe")
            .mapNotNull { fixUrlNull(it.attr("src").ifBlank { it.attr("data-src") }) }
            .filter { it.isNotBlank() }
            .distinct()

        var matched = false
        for (iframe in iframeUrls) {
            if (iframe.contains(".m3u8", true)) {
                callback(
                    newExtractorLink(
                        name,
                        "$name HLS",
                        iframe,
                        ExtractorLinkType.M3U8
                    ) { referer = "$mainUrl/" }
                )
                matched = true
            } else if (iframe.contains(".mp4", true)) {
                callback(
                    newExtractorLink(
                        name,
                        "$name MP4",
                        iframe,
                        ExtractorLinkType.VIDEO
                    ) { referer = "$mainUrl/" }
                )
                matched = true
            } else {
                matched = runCatching {
                    loadExtractor(iframe, "$mainUrl/", subtitleCallback, callback)
                }.getOrDefault(false) || matched
            }
        }

        if (!matched) {
            Regex("""[\"']contentUrl[\"']\s*:\s*[\"']([^\"']+)[\"']""")
                .findAll(document.html())
                .map { it.groupValues[1] }
                .mapNotNull { fixUrlNull(it) }
                .distinct()
                .forEach { url ->
                    matched = runCatching {
                        loadExtractor(url, "$mainUrl/", subtitleCallback, callback)
                    }.getOrDefault(false) || matched
                }
        }

        return matched
    }
}
