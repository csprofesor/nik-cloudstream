// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.APIHolder.capitalize
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class JetFilmizle : MainAPI() {
    override var mainUrl              = "https://jetfilmizle.now"
    override var name                 = "JetFilmizle"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        mainUrl to "Son Filmler",
        "${mainUrl}/saglayici/netflix"        to "Netflix",
        "${mainUrl}/gunun-kesleri"            to "Editörün Seçimi",
        "${mainUrl}/yerli-filmler"            to "Türk Filmleri",
        "${mainUrl}/diziler"                  to "Diziler",
        "${mainUrl}/nette-ilkler"             to "Nette İlk Filmler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val baseUrl = request.data
        val urlpage = if (page == 1) baseUrl else "$baseUrl/page/$page"
        val document = app.get(urlpage).document
        val home = document.select("div.film-card").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        var title = this.selectFirst(".card-title a")?.text()?.trim() ?: return null
        title = title.substringBeforeLast(" izle").trim()
        val imgElement = this.selectFirst(".film-poster img")
        val posterUrl = fixUrlNull(
            imgElement?.attr("data-src")?.takeIf { it.isNotBlank() } ?: imgElement?.attr("src")
        )
        val isTvSeries = href.contains("/dizi/", ignoreCase = true)
        val tvType = if (isTvSeries) TvType.TvSeries else TvType.Movie
        return newMovieSearchResponse(title, href, tvType) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.post("${mainUrl}/arama?q=", referer = "${mainUrl}/", data = mapOf("s" to query)).document
        return document.select("div.film-card").mapNotNull { it.toSearchResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1.film-title")?.ownText()?.trim()
            ?: document.selectFirst("h1.film-title")?.text()?.substringBefore("(")?.trim() ?: return null
        val imgElement = document.selectFirst("img.film-poster")
        val poster = fixUrlNull(imgElement?.attr("data-src")?.takeIf { it.isNotBlank() } ?: imgElement?.attr("src"))
        val traktUrl = document.selectFirst("a.trakt")?.attr("href")
        var year = traktUrl?.substringAfterLast("-")?.toIntOrNull()
        if (year == null) year = Regex("""\b(19|20)\d{2}\b""").find(document.text())?.value?.toIntOrNull()
        val description = document.selectFirst("div.description-text")?.text()?.trim()
        val tags = document.select("div.catss a, div.film-categories a").map { it.text().trim() }
        val actors = document.select("div.oyuncu, div.cast-item").mapNotNull {
            val name = it.selectFirst("div.name, span.actor-name")?.text()?.trim() ?: return@mapNotNull null
            val actorImg = it.selectFirst("img")
            val actorPoster = fixUrlNull(actorImg?.attr("data-src")?.takeIf { src -> src.isNotBlank() } ?: actorImg?.attr("src"))
            Actor(name, actorPoster)
        }
        val recommendations = document.select("div#benzers article, div.film-card").mapNotNull {
            var recName = it.selectFirst(".card-title a")?.text()?.trim()
                ?: it.selectFirst("h2 a, h3 a")?.text()?.trim() ?: return@mapNotNull null
            recName = recName.substringBeforeLast(" izle").trim()
            val recHref = fixUrlNull(it.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
            val recImg = it.selectFirst("img")
            val recPosterUrl = fixUrlNull(recImg?.attr("data-src")?.takeIf { src -> src.isNotBlank() } ?: recImg?.attr("src"))
            newMovieSearchResponse(recName, recHref, TvType.Movie) { this.posterUrl = recPosterUrl }
        }
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.year = year
            this.plot = description
            this.tags = tags
            this.recommendations = recommendations
            addActors(actors)
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("JTF", "data » $data")
        val document = app.get(data).document
        val iframes = mutableListOf<String>()
        val iframeElement = document.selectFirst("div#active-player iframe, div.player-container iframe")
        val iframeSrc = iframeElement?.attr("data-litespeed-src")?.takeIf { it.isNotBlank() } ?: iframeElement?.attr("src")
        val mainIframe = fixUrlNull(iframeSrc)
        if (mainIframe != null) iframes.add(mainIframe)

        document.select("a.download-btn[href]").forEach { link ->
            val href = link.attr("href")
            if (href.contains("pixeldrain.com")) fixUrlNull(href)?.let { iframes.add(it) }
        }

        val objectMapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

        for (iframeUrl in iframes) {
            if (iframeUrl.contains("d2rs")) {
                val apiUrl = iframeUrl.replace("/?", "/get_video.php?")
                try {
                    val responseText = app.get(apiUrl).text
                    val jsonNode = objectMapper.readTree(responseText)
                    if (jsonNode.path("success").asBoolean()) {
                        val masterUrl = jsonNode.path("masterUrl").asText()
                        val referrerUrl = jsonNode.path("referrerUrl").asText()
                        callback.invoke(newExtractorLink(source = "D2RS", name = "D2RS", url = masterUrl, type = ExtractorLinkType.M3U8) {
                            this.quality = Qualities.Unknown.value
                            this.headers = mapOf("Referer" to referrerUrl)
                        })
                    }
                } catch (e: Exception) {
                    Log.e("JTF", "D2RS JSON Parse veya İstek Hatası: ${e.message}")
                }
            } else if (iframeUrl.contains("jetv.xyz")) {
                val jetvDoc = app.get(iframeUrl).document
                val script = jetvDoc.select("script").find { it.data().contains("\"sources\": [") }?.data() ?: ""
                if (script.isNotBlank()) {
                    val sourceString = script.substringAfter("\"sources\": [").substringBefore("]").addMarks("file").addMarks("type").addMarks("label").replace("\'", "\"")
                    try {
                        val son: Source = objectMapper.readValue(sourceString)
                        callback.invoke(newExtractorLink(source = "Jetv - ${son.label}", name = "Jetv - ${son.label}", url = son.file, type = ExtractorLinkType.M3U8) {
                            this.quality = Qualities.Unknown.value
                        })
                    } catch (e: Exception) {
                        Log.e("JTF", "JSON Parse hatası: ${e.message}")
                    }
                }
            } else {
                loadExtractor(iframeUrl, "$mainUrl/", subtitleCallback, callback)
            }
        }
        return true
    }

    private fun String.addMarks(str: String): String = this.replace(Regex("\"?$str\"?"), "\"$str\"")
}
