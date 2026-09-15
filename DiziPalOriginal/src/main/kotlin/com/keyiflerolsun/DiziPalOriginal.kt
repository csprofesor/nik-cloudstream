// ! Bu dosyanın mevcut çalışma mantığı korunarak ana alan adı güncellendi.
// Kaynak DiziPalOriginal implementasyonu: feroxx/Kekik-cloudstream ve güncel DiziPal varyantları.

package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class DiziPalOriginal : MainAPI() {
    override var mainUrl              = "https://dizipal2132.com"
    override var name                 = "DiziPalOriginal"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.TvSeries, TvType.Movie)

    // ! CloudFlare bypass
    override var sequentialMainPage = true

    override val mainPage = mainPageOf(
        "${mainUrl}/bolumler" to "Son Bölümler",
        "${mainUrl}/diziler" to "Yeni Diziler",
        "${mainUrl}/filmler" to "Yeni Filmler",
        "${mainUrl}/platform/netflix" to "Netflix",
        "${mainUrl}/platform/exxen" to "Exxen",
        "${mainUrl}/platform/blutv" to "BluTV",
        "${mainUrl}/platform/disney-plus" to "Disney+",
        "${mainUrl}/platform/prime-video" to "Amazon Prime",
        "${mainUrl}/platform/tabii" to "Tabii",
        "${mainUrl}/platform/gain" to "Gain",
        "${mainUrl}/platform/max" to "Max",
        "${mainUrl}/kategori/bilim-kurgu" to "Bilimkurgu Filmleri",
        "${mainUrl}/kategori/komedi" to "Komedi Filmleri",
        "${mainUrl}/kategori/belgesel" to "Belgesel Filmleri"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val home = if (request.data.contains("/bolumler")) {
            document.select("div.episodes-list-grid > a.episode-list-item").mapNotNull { it.sonBolumler() }
        } else {
            document.select("ul.content-grid > li").mapNotNull { it.diziler() }
        }
        return newHomePageResponse(request.name, home, hasNext = false)
    }

    private fun Element.sonBolumler(): SearchResponse? {
        val name = this.selectFirst(".ep-title")?.text() ?: return null
        val episode = this.selectFirst(".ep-info")?.text()?.trim()
            ?.replace(". Sezon ", "x")?.replace(". Bölüm", "") ?: return null
        val title = "$name $episode"
        val href = fixUrlNull(this.attr("href")) ?: return null
        val imgElement = this.selectFirst("img")
        val posterUrl = fixUrlNull(imgElement?.attr("data-src")?.ifEmpty { imgElement.attr("src") })
        val seriesUrl = href
            .replace(Regex("-\\d+-sezon-\\d+-bolum.*$"), "")
            .replace("/bolum/", "/dizi/")
        return newTvSeriesSearchResponse(title, seriesUrl, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    private fun Element.diziler(): SearchResponse? {
        val title = this.selectFirst("div.card-info h3")?.text() ?: return null
        val href = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src"))
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/ajax-search?q=$query"
        val responseRaw = app.get(
            searchUrl,
            headers = mapOf(
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "X-Requested-With" to "XMLHttpRequest"
            ),
            referer = "$mainUrl/"
        )
        val jsonResponse = AppUtils.parseJson<DizipalSearchData>(responseRaw.text)
        return jsonResponse.results?.mapNotNull { item ->
            val title = item.title ?: return@mapNotNull null
            val url = item.url ?: return@mapNotNull null
            if (item.type == "Dizi") {
                newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                    this.posterUrl = item.poster
                    this.year = item.year
                }
            } else {
                newMovieSearchResponse(title, url, TvType.Movie) {
                    this.posterUrl = item.poster
                    this.year = item.year
                }
            }
        } ?: emptyList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        if (url.contains("/bolum/")) {
            val seriesUrl = url.replace("/bolum/", "/dizi/")
                .replace(Regex("-\\d+-sezon.*"), "")
            return load(seriesUrl)
        }

        val document = app.get(url).document
        val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        val year = document.selectFirst("div.info-row:contains(Yıl) span.info-value")?.text()?.trim()?.toIntOrNull()
        val description = document.selectFirst("p.series-description")?.text()?.trim()
        val tags = document.select("div.info-row:contains(Kategoriler) span.info-value.categories a")
            .map { it.text().trim() }

        if (url.contains("/dizi/")) {
            val title = document.selectFirst("h1.series-title")?.text()?.trim() ?: return null
            val episodes = document.select("div.detail-episode-item-wrap").mapNotNull { wrap ->
                val anchor = wrap.selectFirst("a.detail-episode-item") ?: return@mapNotNull null
                val epHref = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
                val epName = anchor.selectFirst("div.detail-episode-title")?.text()?.trim()
                    ?: return@mapNotNull null
                val subtitle = anchor.selectFirst("div.detail-episode-subtitle")?.text()?.trim() ?: ""
                val match = Regex("""(\d+)\.\s*[Ss]ezon\s*(\d+)\.\s*[Bb]ölüm""").find(subtitle)
                newEpisode(epHref) {
                    this.name = epName
                    this.episode = match?.groupValues?.getOrNull(2)?.toIntOrNull()
                    this.season = match?.groupValues?.getOrNull(1)?.toIntOrNull()
                }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
            }
        }

        val title = document.selectFirst("h1.series-title, h1.movie-title")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" izle")?.trim()
            ?: return null

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.year = year
            this.plot = description
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("DZP", "Oynatılacak Bölüm Linki » $data")

        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
        val getResponse = app.get(
            url = data,
            headers = mapOf(
                "User-Agent" to userAgent,
                "Cache-Control" to "no-cache",
                "Pragma" to "no-cache"
            )
        )

        val document = getResponse.document
        val configToken = document.selectFirst("#videoContainer")?.attr("data-cfg")?.trim()
        if (configToken.isNullOrEmpty()) {
            Log.e("DZP", "Sayfadan video config token'ı (data-cfg) alınamadı!")
            return false
        }

        val cookies = getResponse.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        val paddedToken = configToken + "=".repeat((4 - configToken.length % 4) % 4)
        val decodedToken = String(android.util.Base64.decode(paddedToken, android.util.Base64.DEFAULT))
        val embedUrlRaw = Regex(""""v"\s*:\s*"([^"]+)"""").find(decodedToken)?.groupValues?.getOrNull(1)
            ?.replace("\\/", "/")

        if (embedUrlRaw.isNullOrEmpty()) {
            Log.e("DZP", "Decoded token içinden embed URL alınamadı!")
            return false
        }

        val embedResponse = app.get(
            embedUrlRaw,
            headers = mapOf(
                "User-Agent" to userAgent,
                "Referer" to data,
                "Cookie" to cookies
            )
        )
        val embedDocument = embedResponse.document
        val iframeSrc = embedDocument.selectFirst("iframe")?.attr("src")?.takeIf { it.isNotBlank() }
        val playerUrl = iframeSrc ?: embedUrlRaw

        // Sayfadaki standart m3u8 bağlantısını çıkar.
        val pageText = embedResponse.text
        val m3u8 = Regex("https?://[^\\\"'\\s]+\\.m3u8[^\\\"'\\s]*")
            .find(pageText)?.value

        if (!m3u8.isNullOrEmpty()) {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "DiziPalOriginal",
                    url = m3u8,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = playerUrl
                    this.headers = mapOf("User-Agent" to userAgent)
                }
            )
            return true
        }

        // Son aşamada CloudStream extractor'larını kullan.
        loadExtractor(playerUrl, data, subtitleCallback, callback)
        return true
    }
}
