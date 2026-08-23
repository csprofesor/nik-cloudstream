package com.keyiflerolsun

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.nodes.Element
import java.nio.charset.StandardCharsets
import java.util.Base64

class HDFilmCehennemi : MainAPI() {
    override var mainUrl = "https://www.hdfilmcehennemi.nl"
    override var name = "HDFilmCehennemi"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/category/tavsiye-filmler-izle2/page/" to "Tavsiye Filmler Kategorisi",
        "$mainUrl/yabancidiziizle-1/page/" to "Son Eklenen Yabancı Diziler",
        "$mainUrl/imdb-7-puan-uzeri-filmler/page/" to "Imdb 7+ Filmler",
        "$mainUrl/en-cok-yorumlananlar/page/" to "En Çok Yorumlananlar",
        "$mainUrl/en-cok-begenilen-filmleri-izle/page/" to "En Çok Beğenilenler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + page).document
        val home = document.select("div.card-body div.row div.col-6.col-sm-3.poster-container").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst("h2.title")?.text() ?: return null
        val href = fixUrlNull(selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(selectFirst("img")?.attr("data-src"))
        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    private fun Media.toSearchResponse(): SearchResponse? {
        return newMovieSearchResponse(title ?: return null, "$mainUrl/$slugPrefix$slug", TvType.TvSeries) {
            this.posterUrl = "$mainUrl/uploads/poster/$poster"
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        return app.post(
            "$mainUrl/search/",
            data = mapOf("query" to query),
            referer = "$mainUrl/",
            headers = mapOf("Accept" to "application/json, text/javascript, */*; q=0.01", "X-Requested-With" to "XMLHttpRequest")
        ).parsedSafe<Result>()?.result?.mapNotNull { it.toSearchResponse() }
            ?: throw ErrorLoadingException("Invalid Json response")
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("div.card-header > h1, div.card-header > h2")?.text()
            ?.removeSuffix("Filminin Bilgileri")?.trim() ?: return null
        val poster = fixUrlNull(document.select("img.img-fluid").lastOrNull()?.attr("src"))
        val tags = document.select("div.mb-0.lh-lg div:nth-child(5) a").map { it.text() }
        val year = document.selectFirst("div.mb-0.lh-lg div:nth-child(4) a")?.text()?.trim()?.toIntOrNull()
        val tvType = if (document.select("nav#seasonsTabs").isNullOrEmpty()) TvType.Movie else TvType.TvSeries
        val description = document.selectFirst("article.text-white > p")?.text()?.trim()
        val rating = document.selectFirst("div.rating-votes div.rate span")?.text()?.toRatingInt()
        val actors = document.select("div.mb-0.lh-lg div:last-child a.chip").map { Actor(it.text(), it.select("img").attr("src")) }
        val recommendations = document.select("div.swiper-wrapper div.poster.poster-pop").mapNotNull {
            val recName = it.selectFirst("h2.title")?.text() ?: return@mapNotNull null
            val recHref = fixUrlNull(it.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
            val recPosterUrl = fixUrlNull(it.selectFirst("img")?.attr("data-src"))
            newTvSeriesSearchResponse(recName, recHref, TvType.TvSeries) { this.posterUrl = recPosterUrl }
        }

        return if (tvType == TvType.TvSeries) {
            val trailer = document.selectFirst("button.btn.btn-fragman.btn-danger")?.attr("data-trailer")?.let { "https://www.youtube.com/embed/$it" }
            val episodes = document.select("div#seasonsTabs-tabContent div.card-list-item").map {
                val href = it.select("a").attr("href")
                val name = it.select("h3").text().trim()
                val episode = Regex("Sezon\\s?([0-9]+).").find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()
                val season = it.parents()[1].attr("id").substringAfter("-").toIntOrNull()
                Episode(href, name, season, episode)
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster; this.year = year; this.plot = description; this.tags = tags; this.rating = rating
                addActors(actors); this.recommendations = recommendations; addTrailer(trailer)
            }
        } else {
            val trailer = document.selectFirst("nav.nav.card-nav.nav-slider a[data-bs-toggle=\"modal\"]")?.attr("data-trailer")?.let { "https://www.youtube.com/embed/$it" }
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster; this.year = year; this.plot = description; this.tags = tags; this.rating = rating
                addActors(actors); this.recommendations = recommendations; addTrailer(trailer)
            }
        }
    }

    private fun rot13(value: String): String = value.map { c ->
        when {
            c in 'a'..'z' -> ((c.code - 'a'.code + 13) % 26 + 'a'.code).toChar()
            c in 'A'..'Z' -> ((c.code - 'A'.code + 13) % 26 + 'A'.code).toChar()
            else -> c
        }
    }.joinToString("")

    private fun characterUnmix(value: String): String = buildString {
        value.forEachIndexed { index, c ->
            val mixed = (c.code - (399756995L % (index + 5)) + 256) % 256
            append(mixed.toInt().toChar())
        }
    }

    private fun decodeVariant1(value: String): String {
        val decoded = Base64.getDecoder().decode(rot13(value))
        return characterUnmix(String(decoded, StandardCharsets.ISO_8859_1))
    }

    private fun decodeVariant2(value: String): String {
        val decoded = Base64.getDecoder().decode(value)
        return characterUnmix(rot13(String(decoded, StandardCharsets.ISO_8859_1)))
    }

    private fun decodeVariant3(value: String): String {
        var decoded = String(Base64.getDecoder().decode(value), StandardCharsets.ISO_8859_1)
        decoded = decoded.reversed()
        decoded = rot13(decoded)
        return characterUnmix(decoded)
    }

    private fun isValidVideoUrl(url: String?): Boolean {
        if (url.isNullOrBlank() || !url.startsWith("https://")) return false
        return url.contains(".m3u8") || url.contains("/hls/") || url.contains(".mp4")
    }

    private fun decodeVideoUrl(parts: List<String>): String? {
        val value = parts.joinToString("")
        val reversed = value.reversed()

        try { decodeVariant3(value).takeIf { isValidVideoUrl(it) }?.let { return it } } catch (_: Exception) {}
        try { decodeVariant1(reversed).takeIf { isValidVideoUrl(it) }?.let { return it } } catch (_: Exception) {}
        try { decodeVariant2(reversed).takeIf { isValidVideoUrl(it) }?.let { return it } } catch (_: Exception) {}
        return null
    }

    private fun extractVideoUrl(decoded: String): String? {
        val partsMatch = Regex("dc_\\w+\\(\\[([^]]+)]\\)").find(decoded)
        if (partsMatch != null) {
            val parts = Regex("\\\"([^\\\"]+)\\\"").findAll(partsMatch.groupValues[1]).map { it.groupValues[1] }.toList()
            decodeVideoUrl(parts)?.let { return it }
        }

        val fileLink = Regex("file_link=\\\"([^\\\"]+)\\\"").find(decoded)?.groupValues?.getOrNull(1)
        if (!fileLink.isNullOrBlank()) {
            try {
                val oldUrl = base64Decode(fileLink)
                if (isValidVideoUrl(oldUrl)) return oldUrl
            } catch (_: Exception) {}
        }
        return null
    }

    private suspend fun invokeLocalSource(source: String, url: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val script = app.get(url, referer = "$mainUrl/").document.select("script").find { it.data().contains("sources:") }?.data() ?: return
        val decodedScript = try { getAndUnpack(script) } catch (_: Exception) { script }
        val videoUrl = extractVideoUrl(decodedScript) ?: return
        val subData = decodedScript.substringAfter("tracks: [", "").substringBefore("]")

        callback.invoke(ExtractorLink(source, source, videoUrl, "$mainUrl/", Qualities.Unknown.value, true))
        tryParseJson<List<SubSource>>("[${subData}]")?.filter { it.kind == "captions" }?.map {
            subtitleCallback.invoke(SubtitleFile(it.label.toString(), fixUrl(it.file.toString())))
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        app.get(data).document.select("nav.nav.card-nav.nav-slider a.nav-link").map { Pair(it.attr("href"), it.text()) }.apmap { (url, source) ->
            safeApiCall {
                app.get(url).document.select("div.card-video > iframe").attr("data-src").let { iframeUrl ->
                    if (iframeUrl.startsWith(mainUrl)) {
                        invokeLocalSource(source, iframeUrl, subtitleCallback, callback)
                    } else {
                        loadExtractor(iframeUrl, "$mainUrl/", subtitleCallback) { link ->
                            callback.invoke(ExtractorLink(source, source, link.url, link.referer, link.quality, link.type, link.headers, link.extractorData))
                        }
                    }
                }
            }
        }
        return true
    }

    private data class SubSource(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("kind") val kind: String? = null
    )

    data class Result(@JsonProperty("result") val result: ArrayList<Media>? = arrayListOf())
    data class Media(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("slug_prefix") val slugPrefix: String? = null
    )
}