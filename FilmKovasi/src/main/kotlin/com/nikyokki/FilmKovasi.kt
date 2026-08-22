package com.nikyokki

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element

class FilmKovasi : MainAPI() {
    override var mainUrl = "https://filmkovasi.co"
    override var name = "FilmKovası"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "${mainUrl}/filmizle/aile/" to "Aile",
        "${mainUrl}/filmizle/aksiyon/" to "Aksiyon",
        "${mainUrl}/filmizle/animasyon/" to "Animasyon",
        "${mainUrl}/filmizle/belgesel/" to "Belgesel",
        "${mainUrl}/filmizle/bilim-kurgu/" to "Bilim Kurgu",
        "${mainUrl}/filmizle/dram/" to "Dram",
        "${mainUrl}/filmizle/fantastik/" to "Fantastik",
        "${mainUrl}/filmizle/gerilim/" to "Gerilim",
        "${mainUrl}/filmizle/gizem/" to "Gizem",
        "${mainUrl}/filmizle/komedi/" to "Komedi",
        "${mainUrl}/filmizle/korku/" to "Korku",
        "${mainUrl}/filmizle/macera/" to "Macera",
        "${mainUrl}/filmizle/romantik/" to "Romantik",
        "${mainUrl}/filmizle/savas/" to "Savaş",
        "${mainUrl}/filmizle/suc/" to "Suç",
        "${mainUrl}/filmizle/tarih/" to "Tarih",
        "${mainUrl}/filmizle/vahsi-bati/" to "Vahşi Batı",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = if (page <= 1) request.data else "${request.data}page/$page/"
        val document = app.get(pageUrl).document
        val home = document.select("div.movie-box").mapNotNull { it.toMainPageResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val title = selectFirst("div.film-ismi a")?.text()?.replace(" izle", "")?.trim()
            ?: return null
        val href = fixUrlNull(selectFirst("div.film-ismi a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(selectFirst("div.poster img")?.attr("data-src"))
            ?: fixUrlNull(selectFirst("div.poster img")?.attr("data-lazy-src"))
            ?: fixUrlNull(selectFirst("div.poster img")?.attr("src"))

        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/?s=${query}").document
        return document.select("div.movie-box").mapNotNull { it.toMainPageResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1.title-border")?.text()?.replace(" izle", "")?.trim()
            ?: return null
        val poster = fixUrlNull(document.selectFirst("div.film-afis img")?.attr("src"))
            ?: fixUrlNull(document.selectFirst("div.film-afis img")?.attr("data-src"))
            ?: fixUrlNull(document.selectFirst("div.film-afis img")?.attr("data-lazy-src"))
        val description = document.selectFirst("div#film-aciklama")?.text()?.trim()
        var year = document.selectFirst("div.release a")?.text()?.trim()?.toIntOrNull()
        val tags = document.select("div#listelements a").map { it.text() }
        var rating = document.selectFirst("div.imdb")?.text()?.replace("IMDb Puanı:", "")
            ?.split("/")?.first()?.trim()
        var actors = document.select("div.actor a").map { it.text() }
        val trailer = document.selectFirst("div.film-afis iframe")?.let { fixUrlNull(it.attr("src")) }

        document.select("div.list-item").forEach { item ->
            if (item.selectFirst("a")?.attr("href")?.contains("/yil/") == true) {
                year = item.selectFirst("a")?.text()?.toIntOrNull()
            }
            if (item.selectFirst("a")?.attr("href")?.contains("/oyuncu/") == true) {
                actors = item.select("a").map { it.text() }
            }
        }
        document.select("div#listelements div").forEach {
            if (it.text().contains("IMDb:")) rating = it.text().trim().split(" ").last()
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            posterUrl = poster
            plot = description
            this.year = year
            this.tags = tags
            score = Score.from10(rating)
            addActors(actors)
            addTrailer(trailer)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("FKV", "data » $data")
        val document = app.get(data).document
        val iframe = document.selectFirst("iframe")?.attr("src")
        val sourceName = document.selectFirst("div.sources span")?.text() ?: name

        if (!iframe.isNullOrBlank()) {
            loadLinkExtractor(iframe, sourceName, subtitleCallback, callback)
        }

        document.select("div.sources a[href]").forEach { source ->
            val sourceUrl = fixUrlNull(source.attr("href")) ?: return@forEach
            val sourceTitle = source.selectFirst("span")?.text() ?: name
            try {
                val sourceDoc = app.get(sourceUrl, referer = data).document
                val sourceIframe = sourceDoc.selectFirst("iframe")?.attr("src")
                if (!sourceIframe.isNullOrBlank()) {
                    loadLinkExtractor(sourceIframe, sourceTitle, subtitleCallback, callback)
                }
            } catch (e: Throwable) {
                Log.e("FKV", "source failed: $sourceUrl", e)
            }
        }
        return true
    }

    private suspend fun loadLinkExtractor(
        iframe: String,
        sourceName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val ianaLink = iframe.substringBefore("/watch/")
        val idoc = app.get(iframe, referer = iframe).document
        val script = idoc.select("script").firstOrNull { it.data().contains("sources:") }?.data() ?: return
        val vidJson = script.substringAfter("var video = ").substringBefore(";")
        val source = script.substringAfter("sources: [").substringBefore("],")
            .replace("`", "\"").addMarks("file").addMarks("type").addMarks("preload")
        val tracks = script.substringAfter("tracks: [").substringBefore("]")
        if (vidJson.isBlank() || source.isBlank()) return

        try {
            val objectMapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
            objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            val video: FKVSource = objectMapper.readValue(vidJson)
            val file: FileSource = objectMapper.readValue(source)

            if (tracks.isNotBlank()) {
                runCatching {
                    val track: Track = objectMapper.readValue(tracks)
                    track.file?.let { subtitleCallback(SubtitleFile(lang = "Türkçe Altyazı", url = it)) }
                }
            }

            val stream = ianaLink + (file.file ?: "")
                .replace("\${video.uid}", video.uid ?: "")
                .replace("\${video.md5}", video.md5 ?: "")
                .replace("\${video.id}", video.id ?: "")
                .replace("\${video.status}", video.status ?: "")

            if (stream.isNotBlank() && !stream.contains("null")) {
                callback(
                    ExtractorLink(
                        source = name,
                        name = sourceName,
                        url = stream,
                        referer = iframe,
                        quality = Qualities.Unknown.value,
                        type = ExtractorLinkType.M3U8
                    )
                )
            }
        } catch (e: Throwable) {
            Log.e("FKV", "video parse failed", e)
        }
    }

    private data class FKVSource(
        @JsonProperty("uid") val uid: String? = null,
        @JsonProperty("md5") val md5: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("status") val status: String? = null,
    )

    private data class FileSource(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("preload") val preload: String? = null,
    )

    private data class Track(
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("kind") val kind: String? = null,
    )

    private fun String.addMarks(str: String): String = replace(Regex("\\\"?$str\\\"?"), "\\\"$str\\\"")
}
