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
        "${mainUrl}/filmizle/aksiyon-hd/" to "Aksiyon",
        "${mainUrl}/filmizle/animasyon/" to "Animasyon",
        "${mainUrl}/filmizle/belgesel-hd/" to "Belgesel",
        "${mainUrl}/filmizle/bilim-kurgu/" to "Bilim Kurgu",
        "${mainUrl}/filmizle/dram-hd/" to "Dram",
        "${mainUrl}/filmizle/fantastik-hd/" to "Fantastik",
        "${mainUrl}/filmizle/gerilim/" to "Gerilim",
        "${mainUrl}/filmizle/gizem/" to "Gizem",
        "${mainUrl}/filmizle/komedi-hd/" to "Komedi",
        "${mainUrl}/filmizle/korku-hd/" to "Korku",
        "${mainUrl}/filmizle/macera-hd/" to "Macera",
        "${mainUrl}/filmizle/romantik-hd/" to "Romantik",
        "${mainUrl}/filmizle/savas-hd/" to "Savaş",
        "${mainUrl}/filmizle/suc-hd/" to "Suç",
        "${mainUrl}/filmizle/tarih/" to "Tarih",
        "${mainUrl}/filmizle/vahsi-bati-hd/" to "Vahşi Batı",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // FilmKovası uses the category URL itself for page 1; /page/2/ starts pagination.
        val pageUrl = if (page <= 1) request.data else "${request.data}page/$page/"
        val document = app.get(pageUrl).document
        val home = document.select("a[href]").mapNotNull { it.toFilmResult() }.distinctBy { it.url }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.imageUrl(): String? {
        val image = if (tagName() == "img") this else selectFirst("img") ?: return null
        val attrs = listOf("data-src", "data-lazy-src", "data-original", "data-image", "src")
        for (attr in attrs) {
            val value = image.attr(attr).trim()
            if (value.isNotBlank() && !value.startsWith("data:image")) return fixUrlNull(value)
        }
        val srcset = image.attr("srcset").substringBefore(',').trim().split(' ').firstOrNull()
        return srcset?.takeIf { it.isNotBlank() }?.let { fixUrlNull(it) }
    }

    private fun Element.toFilmResult(): SearchResponse? {
        val href = fixUrlNull(attr("href")) ?: return null
        val title = text().replace(Regex("\\s+"), " ").trim()
        val lowerHref = href.lowercase()
        // FilmKovası's current cards use links whose visible title ends in "İzle".
        if (title.length < 3 || !title.matches(Regex("(?i).+\\s+izle$"))) return null
        if (!lowerHref.startsWith(mainUrl.lowercase())) return null
        if (lowerHref.contains("/filmizle/") || lowerHref.contains("/yil/") || lowerHref.contains("/oyuncu/") ||
            lowerHref.contains("/film-arsivi/") || lowerHref.contains("/kategori/") || lowerHref.contains("/sayfa/")) return null

        val container = parents().firstOrNull { parent -> parent.select("img").isNotEmpty() && parent.select("a[href]").size <= 10 }
        val poster = container?.imageUrl() ?: imageUrl()
        val cleanTitle = title.replace(Regex("(?i)\\s+izle$"), "").trim()
        return newMovieSearchResponse(cleanTitle, href, TvType.Movie) { posterUrl = poster }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/?s=${query}").document
        return document.select("a[href]").mapNotNull { it.toFilmResult() }.distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1.title-border, h1, .title-border")?.text()?.replace(" izle", "")?.trim() ?: return null
        val poster = document.selectFirst("div.film-afis img, .film-afis img, .poster img, .film-poster img")?.imageUrl()
        val description = document.selectFirst("div#film-aciklama, #film-aciklama, .film-aciklama")?.text()?.trim()
        var year = document.selectFirst("div.release a")?.text()?.trim()?.toIntOrNull()
        val tags = document.select("div#listelements a, #listelements a").map { it.text() }
        var rating = document.selectFirst("div.imdb")?.text()?.replace("IMDb Puanı:", "")?.split("/")?.first()?.trim()
        var actors = document.select("div.actor a").map { it.text() }
        val trailer = document.selectFirst("div.film-afis iframe")?.let { iframe -> fixUrlNull(iframe.attr("src").ifBlank { iframe.attr("data-src") }) }

        document.select("div.list-item").forEach { item ->
            if (item.selectFirst("a")?.attr("href")?.contains("/yil/") == true) year = item.selectFirst("a")?.text()?.toIntOrNull()
            if (item.selectFirst("a")?.attr("href")?.contains("/oyuncu/") == true) actors = item.select("a").map { it.text() }
        }
        document.select("div#listelements div, #listelements div").forEach {
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

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data).document
        val iframe = document.selectFirst("iframe")?.let { fixUrlNull(it.attr("src").ifBlank { it.attr("data-src") }) }
        if (iframe != null) loadLinkExtractor(iframe, document.selectFirst("div.sources span")?.text() ?: name, subtitleCallback, callback)

        document.select("div.sources a[href]").forEach { source ->
            val href = fixUrlNull(source.attr("href")) ?: return@forEach
            val sourceName = source.selectFirst("span")?.text() ?: name
            try {
                val sourceDoc = app.get(href, referer = data).document
                val sourceIframe = sourceDoc.selectFirst("iframe")?.let { fixUrlNull(it.attr("src").ifBlank { it.attr("data-src") }) }
                if (sourceIframe != null) loadLinkExtractor(sourceIframe, sourceName, subtitleCallback, callback)
            } catch (e: Throwable) {
                Log.e("FKV", "source failed: $href", e)
            }
        }
        return true
    }

    private suspend fun loadLinkExtractor(iframe: String, sourceName: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val base = iframe.substringBefore("/watch/")
        val iframeDoc = app.get(iframe, referer = iframe).document
        val script = iframeDoc.select("script").firstOrNull { it.data().contains("sources:") }?.data() ?: return
        val videoJson = script.substringAfter("var video = ").substringBefore(";")
        val fileJson = script.substringAfter("sources: [").substringBefore("],").replace("`", "\"").addMarks("file").addMarks("type").addMarks("preload")
        val trackJson = script.substringAfter("tracks: [").substringBefore("]")
        if (videoJson.isBlank() || fileJson.isBlank()) return

        try {
            val mapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
            mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            val video: FKVSource = mapper.readValue(videoJson)
            val file: FileSource = mapper.readValue(fileJson)
            if (trackJson.isNotBlank()) {
                val track: Track = mapper.readValue(trackJson)
                track.file?.let { subtitleCallback(SubtitleFile(lang = "Türkçe Altyazı", url = it)) }
            }
            val stream = base + (file.file ?: "")
                .replace("\${video.uid}", video.uid ?: "")
                .replace("\${video.md5}", video.md5 ?: "")
                .replace("\${video.id}", video.id ?: "")
                .replace("\${video.status}", video.status ?: "")
            if (!stream.contains("null") && stream.isNotBlank()) {
                callback(ExtractorLink(source = name, name = sourceName, url = stream, referer = iframe, quality = Qualities.Unknown.value, type = ExtractorLinkType.M3U8))
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