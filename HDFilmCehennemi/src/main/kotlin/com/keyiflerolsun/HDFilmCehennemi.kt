package com.keyiflerolsun

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.nodes.Element
import java.util.Base64

@Suppress("DEPRECATION")
class HDFilmCehennemi : MainAPI() {
    override var mainUrl = "https://www.hdfilmcehennemi.nl"
    override var name = "HDFilmCehennemi"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/category/tavsiye-filmler-izle3/page/" to "Tavsiye Filmler Kategorisi",
        "$mainUrl/yabancidiziizle-5/page/" to "Son Eklenen Yabancı Diziler",
        "$mainUrl/imdb-7-puan-uzeri-filmler-2/page/" to "Imdb 7+ Filmler",
        "$mainUrl/en-cok-yorumlananlar-2/page/" to "En Çok Yorumlananlar",
        "$mainUrl/en-cok-begenilen-filmleri-izle-4/page/" to "En Çok Beğenilenler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = if (page == 1) request.data.removeSuffix("page/") else request.data + page
        val document = app.get(pageUrl).document
        val cards = document.select("div.poster-container, div.poster, article.poster, a[href]:has(img)")
        val home = cards.mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = if (tagName() == "a") this else selectFirst("a[href]") ?: return null
        val href = fixUrlNull(anchor.attr("href")) ?: return null
        val title = (
            selectFirst("div.poster-title h2, h2.title, h3.title, .title")?.text()
                ?: anchor.selectFirst("h2, h3, .title")?.text()
                ?: anchor.text()
        ).replace(" izle", "").trim().takeIf { it.isNotBlank() } ?: return null
        val image = selectFirst("img") ?: anchor.selectFirst("img")
        val poster = fixUrlNull(
            image?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: image?.attr("data-lazy-src")?.takeIf { it.isNotBlank() }
                ?: image?.attr("data-original")?.takeIf { it.isNotBlank() }
                ?: image?.attr("src")
        )
        val score = selectFirst("span.bg-warning, span.rating, .rating")?.text()?.trim()
        return if (href.contains("/dizi/", true) || text().contains("Yabancı Dizi", true)) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = poster
                this.score = Score.from10(score)
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl = poster
                this.score = Score.from10(score)
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        return app.post("$mainUrl/search/", data = mapOf("query" to query), referer = "$mainUrl/", headers = mapOf(
            "Accept" to "application/json, text/javascript, */*; q=0.01",
            "X-Requested-With" to "XMLHttpRequest"
        )).parsedSafe<Result>()?.result?.mapNotNull { media ->
            val title = media.title ?: return@mapNotNull null
            val slug = media.slug ?: return@mapNotNull null
            val prefix = media.slugPrefix ?: ""
            val url = fixUrl("$mainUrl/$prefix$slug")
            if (prefix.contains("dizi", true)) {
                newTvSeriesSearchResponse(title, url, TvType.TvSeries) { posterUrl = media.poster?.let { fixUrl("$mainUrl/uploads/poster/$it") } }
            } else {
                newMovieSearchResponse(title, url, TvType.Movie) { posterUrl = media.poster?.let { fixUrl("$mainUrl/uploads/poster/$it") } }
            }
        } ?: throw ErrorLoadingException("Invalid Json response")
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("div.card-header > h1, div.card-header > h2")?.text()?.removeSuffix("Filminin Bilgileri")?.trim() ?: return null
        val poster = fixUrlNull(document.selectFirst("picture.poster-auto img")?.attr("data-src") ?: document.select("img.img-fluid").lastOrNull()?.attr("src"))
        val description = document.selectFirst("article.text-white > p")?.text()?.trim()
        val tags = document.select("div.mb-0.lh-lg div:nth-child(5) a, div#listelements a").map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
        val year = document.selectFirst("div.mb-0.lh-lg div:nth-child(4) a, div.release a")?.text()?.trim()?.toIntOrNull()
        val rating = document.selectFirst("div.rating-votes div.rate span, div.rate")?.text()
        val actors = document.select("div.mb-0.lh-lg div:last-child a.chip, .story-item").mapNotNull {
            val actorName = it.selectFirst("a.chip")?.text()?.trim() ?: it.selectFirst("div.story-item-title")?.text()?.trim() ?: return@mapNotNull null
            Actor(actorName, fixUrlNull(it.selectFirst("img")?.attr("data-src") ?: it.selectFirst("img")?.attr("src")))
        }.distinctBy { it.name }
        val recommendations = document.select("div.swiper-wrapper div.poster.poster-pop, div.glide__slide.poster-container").mapNotNull {
            val href = fixUrlNull(it.selectFirst("a[href]")?.attr("href")) ?: return@mapNotNull null
            val recTitle = it.selectFirst("h2.title, h2")?.text()?.trim() ?: it.selectFirst("a")?.text()?.trim() ?: return@mapNotNull null
            val recPoster = fixUrlNull(it.selectFirst("img")?.attr("data-src") ?: it.selectFirst("img")?.attr("src"))
            if (href.contains("/dizi/", true)) newTvSeriesSearchResponse(recTitle, href, TvType.TvSeries) { posterUrl = recPoster }
            else newMovieSearchResponse(recTitle, href, TvType.Movie) { posterUrl = recPoster }
        }.distinctBy { it.url }
        val isSeries = document.select("nav#seasonsTabs").isNotEmpty() || url.contains("/dizi/", true)
        return if (isSeries) {
            val episodes = document.select("div#seasonsTabs-tabContent div.card-list-item, div.seasonsTabs-tabContent div.card-list-item").mapNotNull { item ->
                val href = fixUrlNull(item.selectFirst("a[href]")?.attr("href")) ?: return@mapNotNull null
                val episodeName = item.selectFirst("h3")?.text()?.trim() ?: "Bölüm"
                val season = item.parents().firstOrNull { it.id().startsWith("seasons-") }?.id()?.substringAfter("seasons-")?.toIntOrNull()
                val episode = Regex("(?:Sezon\\s*\\d+\\s*)?([0-9]+)\\.?\\s*Bölüm", RegexOption.IGNORE_CASE).find(episodeName)?.groupValues?.getOrNull(1)?.toIntOrNull()
                newEpisode(href) { name = episodeName; this.season = season ?: 1; this.episode = episode }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster; this.year = year; plot = description; this.tags = tags; score = Score.from10(rating); addActors(actors); this.recommendations = recommendations
                addTrailer(document.selectFirst("button.btn-fragman.btn-danger")?.attr("data-trailer")?.let { "https://www.youtube.com/embed/$it" })
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster; this.year = year; plot = description; this.tags = tags; score = Score.from10(rating); addActors(actors); this.recommendations = recommendations
                addTrailer(document.selectFirst("[data-trailer]")?.attr("data-trailer")?.let { "https://www.youtube.com/embed/$it" })
            }
        }
    }

    private suspend fun addDirectSource(sourceName: String, rawUrl: String?, referer: String, callback: (ExtractorLink) -> Unit) {
        val url = rawUrl?.trim()?.replace("\\/", "/") ?: return
        if (!url.startsWith("http")) return
        if (url.contains(".m3u8", true)) M3u8Helper.generateM3u8(sourceName, url, referer).forEach(callback)
    }

    private suspend fun extractLocalPlayer(sourceName: String, iframeUrl: String, referer: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val response = app.get(iframeUrl, referer = referer)
        val document = response.document
        document.select("video source[src], source[src]").forEach { addDirectSource(sourceName, it.attr("src"), iframeUrl, callback) }
        document.select("video[src]").forEach { addDirectSource(sourceName, it.attr("src"), iframeUrl, callback) }
        val scripts = document.select("script").joinToString("\n") { it.data() }
        Regex("https?://[^\\\"'\\s]+\\.m3u8(?:\\?[^\\\"'\\s]+)?", RegexOption.IGNORE_CASE).findAll(scripts).map { it.value }.distinct().forEach { addDirectSource(sourceName, it, iframeUrl, callback) }
        val packed = Regex("\\[\\{file:\\\"([^\"\\]+)").find(scripts)?.groupValues?.getOrNull(1).orEmpty()
        if (packed.isNotBlank()) addDirectSource(sourceName, packed, iframeUrl, callback)
        val subtitleRegex = Regex("(?:src|file):\\s*['\\\"]([^'\\\"]+).*?(?:label|name):\\s*['\\\"]([^'\\\"]+)", RegexOption.DOT_MATCHES_ALL)
        subtitleRegex.findAll(scripts).forEach { match ->
            val subUrl = fixUrlNull(match.groupValues[1]) ?: return@forEach
            subtitleCallback(SubtitleFile(match.groupValues[2], subUrl))
        }
        Regex("file_link\\s*=\\s*['\\\"]([^'\\\"]+)['\\\"]").findAll(scripts).forEach { match ->
            try { addDirectSource(sourceName, String(Base64.getDecoder().decode(match.groupValues[1])), iframeUrl, callback) } catch (_: Exception) { }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val page = app.get(data).document
        val sourcePages = page.select("nav.nav.card-nav.nav-slider a.nav-link[href]").map { it.attr("href") to it.text().trim() }.filter { it.first.isNotBlank() }.distinctBy { it.first }
        for ((sourcePage, sourceName) in sourcePages) {
            try {
                val sourceDocument = app.get(sourcePage, referer = data).document
                val iframe = fixUrlNull(sourceDocument.selectFirst("div.card-video > iframe")?.attr("data-src") ?: sourceDocument.selectFirst("div.card-video iframe")?.attr("src") ?: sourceDocument.selectFirst("iframe")?.attr("data-src") ?: sourceDocument.selectFirst("iframe")?.attr("src")) ?: continue
                loadExtractor(iframe, sourcePage, subtitleCallback) { link -> callback(link) }
                extractLocalPlayer(sourceName.ifBlank { name }, iframe, sourcePage, subtitleCallback, callback)
            } catch (_: Exception) { }
        }
        return true
    }

    data class Result(@JsonProperty("result") val result: ArrayList<Media>? = arrayListOf())
    data class Media(@JsonProperty("title") val title: String? = null, @JsonProperty("poster") val poster: String? = null, @JsonProperty("slug") val slug: String? = null, @JsonProperty("slug_prefix") val slugPrefix: String? = null)
}
