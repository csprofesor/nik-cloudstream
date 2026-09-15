package com.keyiflerolsun

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class DiziPalOriginal : MainAPI() {
    override var mainUrl = "https://dizipal1430.com"
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
            document.select("div.episodes-list-grid > a.episode-list-item").mapNotNull { it.toEpisodeSearch() }
        } else {
            document.select("ul.content-grid > li").mapNotNull { it.toSearch() }
        }
        return newHomePageResponse(request.name, items, false)
    }

    private fun Element.toSearch(): SearchResponse? {
        val title = selectFirst("div.card-info h3")?.text()?.trim() ?: return null
        val href = fixUrlNull(selectFirst("a")?.attr("href")) ?: return null
        val poster = fixUrlNull(selectFirst("img")?.attr("data-src") ?: selectFirst("img")?.attr("src"))
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) { posterUrl = poster }
    }

    private fun Element.toEpisodeSearch(): SearchResponse? {
        val name = selectFirst(".ep-title")?.text()?.trim() ?: return null
        val info = selectFirst(".ep-info")?.text()?.trim() ?: ""
        val href = fixUrlNull(attr("href")) ?: return null
        val poster = fixUrlNull(selectFirst("img")?.attr("data-src") ?: selectFirst("img")?.attr("src"))
        val title = "$name ${info.replace(". Sezon ", "x").replace(". Bölüm", "")}"
        val seriesUrl = href.replace(Regex("-\\d+-sezon-\\d+-bolum.*$"), "").replace("/bolum/", "/dizi/")
        return newTvSeriesSearchResponse(title, seriesUrl, TvType.TvSeries) { posterUrl = poster }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/diziler?kelime=${URLEncoder.encode(query, "UTF-8")}&durum=&tur=&type=&siralama="
        return app.get(url).document.select("ul.content-grid > li").mapNotNull { it.toSearch() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        if (url.contains("/bolum/")) {
            val seriesUrl = url.replace("/bolum/", "/dizi/").replace(Regex("-\\d+-sezon.*"), "")
            return load(seriesUrl)
        }

        val document = app.get(url).document
        val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        val year = document.selectFirst("div.info-row:contains(Yıl) span.info-value")?.text()?.trim()?.toIntOrNull()
        val plot = document.selectFirst("p.series-description")?.text()?.trim()
        val tags = document.select("div.info-row:contains(Kategoriler) span.info-value.categories a").map { it.text().trim() }

        if (url.contains("/dizi/")) {
            val title = document.selectFirst("h1.series-title")?.text()?.trim() ?: return null
            val episodes = document.select("div.detail-episode-item-wrap").mapNotNull { wrap ->
                val a = wrap.selectFirst("a.detail-episode-item") ?: return@mapNotNull null
                val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
                val name = a.selectFirst("div.detail-episode-title")?.text()?.trim() ?: return@mapNotNull null
                val subtitle = a.selectFirst("div.detail-episode-subtitle")?.text()?.trim() ?: ""
                val match = Regex("""(\\d+)\\.\\s*[Ss]ezon\\s*(\\d+)\\.\\s*[Bb]ölüm""").find(subtitle)
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
            }
        }

        val title = document.selectFirst("h1.series-title, h1.movie-title")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" izle")?.trim()
            ?: return null
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            posterUrl = poster
            this.year = year
            this.plot = plot
            this.tags = tags
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
        val embedRaw = Regex(""""v"\\s*:\\s*"([^"]+)"""").find(decoded)?.groupValues?.getOrNull(1)?.replace("\\/", "/") ?: return false
        val embedUrl = fixUrl(embedRaw)

        if (embedUrl.contains("imagestoo")) {
            val videoId = embedUrl.trimEnd('/').substringAfterLast("/")
            val api = app.post(
                "https://imagestoo.com/player/index.php?data=$videoId&do=getVideo",
                referer = embedUrl,
                headers = mapOf("User-Agent" to ua, "X-Requested-With" to "XMLHttpRequest", "Accept" to "*/*")
            )
            val cookie = api.cookies["fireplayer_player"]?.let { "fireplayer_player=$it" } ?: ""
            val source = Regex(""""securedLink"\\s*:\\s*"([^"]+)"""").find(api.text)?.groupValues?.getOrNull(1)
            if (source != null) {
                callback(newExtractorLink(this.name, "Dizipal (Imagestoo)", fixUrl(source.replace("\\/", "/")), ExtractorLinkType.M3U8) {
                    referer = embedUrl
                    headers = mapOf("Cookie" to cookie)
                    quality = Qualities.Unknown.value
                })
                return true
            }
        }

        val sourceHtml = app.get(embedUrl, referer = data, headers = mapOf("User-Agent" to ua)).text
        val match = Regex("""sources\\s*:\\s*\\[\\s*\\{\\s*file\\s*:\s*["']([^"']+\\.m3u8.*?)["']""").find(sourceHtml)
            ?: Regex("""v\\s*:\\s*["']([^"']+\\.html.*?)["']""").find(sourceHtml)
        val extracted = match?.groupValues?.getOrNull(1) ?: return false
        val finalUrl = if (extracted.contains(".html")) {
            val id = Regex("""embed-([^.]+)\\.html""").find(extracted)?.groupValues?.getOrNull(1) ?: return false
            "https://s2.superadjacentsoddenly.xyz/hls2/01/00007/${id}_,n,h,.urlset/master.m3u8"
        } else extracted

        callback(newExtractorLink(this.name, "Dizipal (Ana Sunucu)", finalUrl, ExtractorLinkType.M3U8) {
            referer = embedUrl
            quality = Qualities.Unknown.value
        })

        Regex("""tracks\\s*:\\s*\\[(.*?)\\]""", RegexOption.DOT_MATCHES_ALL).find(sourceHtml)?.groupValues?.getOrNull(1)?.let { tracks ->
            Regex("""\\{(.*?)\\}""", RegexOption.DOT_MATCHES_ALL).findAll(tracks).forEach { item ->
                val text = item.groupValues[1]
                val file = Regex("""file\\s*:\\s*["']([^"']+)["']""").find(text)?.groupValues?.getOrNull(1)
                val label = Regex("""label\\s*:\\s*["']([^"']+)["']""").find(text)?.groupValues?.getOrNull(1) ?: "Unknown"
                if (file != null && (file.endsWith(".vtt") || file.endsWith(".srt"))) subtitleCallback(SubtitleFile(label, fixUrl(file)))
            }
        }
        return true
    }
}
