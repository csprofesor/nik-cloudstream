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
        "$mainUrl/dizi-izle/" to "Aile",
        "$mainUrl/dizi-izle/" to "Aksiyon",
        "$mainUrl/dizi-izle/" to "Animasyon",
        "$mainUrl/dizi-izle/" to "Belgesel",
        "$mainUrl/dizi-izle/" to "Bilim Kurgu",
        "$mainUrl/dizi-izle/" to "Dram",
        "$mainUrl/dizi-izle/" to "Fantastik",
        "$mainUrl/dizi-izle/" to "Gerilim",
        "$mainUrl/dizi-izle/" to "Komedi",
        "$mainUrl/dizi-izle/" to "Korku",
        "$mainUrl/dizi-izle/" to "Macera",
        "$mainUrl/dizi-izle/" to "Polisiye",
        "$mainUrl/dizi-izle/" to "Romantik",
        "$mainUrl/dizi-izle/" to "Savaş",
        "$mainUrl/dizi-izle/" to "Suç",
        "$mainUrl/dizi-izle/" to "Tarih"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(
            if (page == 1) "$mainUrl/dizi-izle/" else "$mainUrl/dizi-izle/page/$page/",
            referer = "$mainUrl/"
        ).document

        val wantedGenre = request.name.trim()
        val results = document.select("div.single-item, article, .item, .post").mapNotNull { it.toResult() }
            .distinctBy { it.url }
            .filter { result ->
                if (wantedGenre.isBlank()) true
                else {
                    val container = document.select("a[href='${result.url}']").firstOrNull()?.parent()?.parent()?.text().orEmpty()
                    container.contains(wantedGenre, ignoreCase = true) ||
                        result.name.contains(wantedGenre, ignoreCase = true)
                }
            }

        return newHomePageResponse(request.name, results)
    }

    private fun Element.toResult(): SearchResponse? {
        val link = selectFirst("a[href*='/diziler/'], a[href*='/dizi/'], div.categorytitle a, div.serie-name a") ?: return null
        val title = link.text().trim().ifBlank { link.attr("title").trim() }.ifBlank { return null }
        val href = fixUrlNull(link.attr("href")) ?: return null
        val img = selectFirst("img")
        val poster = fixUrlNull(img?.attr("data-src").orEmpty().ifBlank { img?.attr("src").orEmpty() })
        val text = text()
        val rating = Regex("(?:IMDb|IMDB)\\s*:\\s*([0-9]+(?:[.,][0-9]+)?)", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.getOrNull(1)

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            posterUrl = poster
            score = Score.from10(rating)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=${query.trim().replace(" ", "+")}").document
        return document.select("div.single-item").mapNotNull { element ->
            val link = element.selectFirst("div.categorytitle a, a[href*='/diziler/']") ?: return@mapNotNull null
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
        val poster = fixUrlNull(document.selectFirst("div.seriePoster")?.attr("style")?.substringAfter("background-image:url(")?.substringBefore(")")?.trim(' ', '\'', '"'))
        val description = document.selectFirst("div.serieDescription p")?.text()?.trim()
        val year = document.selectFirst("div.airDateYear a")?.text()?.trim()?.toIntOrNull()
        val tags = document.select("div.genreList a").map { it.text().trim() }.filter { it.isNotEmpty() }
        val rating = document.selectFirst("div.score")?.text()?.trim()
        val duration = document.select("div.serieMetaInformation div.totalSession").lastOrNull()?.text()?.substringBefore(" ")?.toIntOrNull()
        val actors = document.select("div.owl-stage a").mapNotNull { a ->
            val actor = a.text().trim()
            if (actor.isBlank()) null else Actor(actor, fixUrlNull(a.selectFirst("img")?.attr("src")))
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

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data, referer = "$mainUrl/").document
        Log.d("DiziGom", "Episode: $data")
        val iframeUrls = document.select("iframe[src], iframe[data-src], div#content iframe, .player iframe")
            .mapNotNull { fixUrlNull(it.attr("src").ifBlank { it.attr("data-src") }) }
            .filter { it.isNotBlank() }.distinct()
        var matched = false
        for (iframe in iframeUrls) {
            if (iframe.contains(".m3u8", true)) {
                callback(newExtractorLink(name, "$name HLS", iframe, ExtractorLinkType.M3U8) { referer = "$mainUrl/" })
                matched = true
            } else if (iframe.contains(".mp4", true)) {
                callback(newExtractorLink(name, "$name MP4", iframe, ExtractorLinkType.VIDEO) { referer = "$mainUrl/" })
                matched = true
            } else {
                matched = runCatching { loadExtractor(iframe, "$mainUrl/", subtitleCallback, callback) }.getOrDefault(false) || matched
            }
        }
        if (!matched) {
            Regex("""[\"']contentUrl[\"']\s*:\s*[\"']([^\"']+)[\"']""").findAll(document.html()).map { it.groupValues[1] }.mapNotNull { fixUrlNull(it) }.distinct().forEach { url ->
                matched = runCatching { loadExtractor(url, "$mainUrl/", subtitleCallback, callback) }.getOrDefault(false) || matched
            }
        }
        return matched
    }
}
