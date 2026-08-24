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

    // DiziGom'un kendi filtre sistemi ?tur=... kullanıyor.
    // Kategorileri bu URL'lere doğrudan bağlamak, tüm katalogu indirip
    // uygulama tarafında filtrelemekten daha doğru ve çok daha hızlıdır.
    override val mainPage = mainPageOf(
        "$mainUrl/dizi-izle/?tur=Aile" to "Aile",
        "$mainUrl/dizi-izle/?tur=Aksiyon" to "Aksiyon",
        "$mainUrl/dizi-izle/?tur=Animasyon" to "Animasyon",
        "$mainUrl/dizi-izle/?tur=Belgesel" to "Belgesel",
        "$mainUrl/dizi-izle/?tur=Bilim%20Kurgu" to "Bilim Kurgu",
        "$mainUrl/dizi-izle/?tur=Dram" to "Dram",
        "$mainUrl/dizi-izle/?tur=Fantastik" to "Fantastik",
        "$mainUrl/dizi-izle/?tur=Gerilim" to "Gerilim",
        "$mainUrl/dizi-izle/?tur=Komedi" to "Komedi",
        "$mainUrl/dizi-izle/?tur=Korku" to "Korku",
        "$mainUrl/dizi-izle/?tur=Macera" to "Macera",
        "$mainUrl/dizi-izle/?tur=Polisiye" to "Polisiye",
        "$mainUrl/dizi-izle/?tur=Romantik" to "Romantik",
        "$mainUrl/dizi-izle/?tur=Savaş" to "Savaş",
        "$mainUrl/dizi-izle/?tur=Suç" to "Suç",
        "$mainUrl/dizi-izle/?tur=Tarih" to "Tarih"
    )

    private fun pageUrl(base: String, page: Int): String {
        if (page <= 1) return base
        val queryIndex = base.indexOf('?')
        return if (queryIndex >= 0) {
            base.substring(0, queryIndex).trimEnd('/') + "/page/$page/" + base.substring(queryIndex)
        } else {
            base.trimEnd('/') + "/page/$page/"
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = pageUrl(request.data, page)
        val document = app.get(url, referer = "$mainUrl/").document

        // Filtre sonucu zaten site tarafından hazırlanıyor; burada tekrar
        // tür adına göre filtreleme yapmıyoruz. Böylece her kategori kendi
        // gerçek içerik listesini eksiksiz gösterebilir.
        val results = document
            .select("div.single-item, article, .item, .post")
            .mapNotNull { it.toResult() }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, results)
    }

    private fun Element.toResult(): SearchResponse? {
        val link = selectFirst(
            "a[href*='/diziler/'], a[href*='/dizi/'], div.categorytitle a, div.serie-name a"
        ) ?: return null
        val title = link.text().trim()
            .ifBlank { link.attr("title").trim() }
            .ifBlank { return null }
        val href = fixUrlNull(link.attr("href")) ?: return null
        val img = selectFirst("img")
        val poster = fixUrlNull(
            img?.attr("data-src").orEmpty().ifBlank { img?.attr("src").orEmpty() }
        )
        val rating = Regex(
            "(?:IMDb|IMDB)\\s*:\\s*([0-9]+(?:[.,][0-9]+)?)",
            RegexOption.IGNORE_CASE
        ).find(text())?.groupValues?.getOrNull(1)

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            posterUrl = poster
            score = Score.from10(rating)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get(
            "$mainUrl/?s=${query.trim().replace(" ", "+")}"
        ).document

        return document.select("div.single-item, article, .item, .post")
            .mapNotNull { element ->
                val link = element.selectFirst(
                    "div.categorytitle a, a[href*='/diziler/'], a[href*='/dizi/']"
                ) ?: return@mapNotNull null
                val title = link.text().trim()
                val href = fixUrlNull(link.attr("href")) ?: return@mapNotNull null
                val img = element.selectFirst("img")
                val poster = fixUrlNull(
                    img?.attr("data-src").orEmpty().ifBlank { img?.attr("src").orEmpty() }
                )
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    posterUrl = poster
                }
            }
            .distinctBy { it.url }
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
