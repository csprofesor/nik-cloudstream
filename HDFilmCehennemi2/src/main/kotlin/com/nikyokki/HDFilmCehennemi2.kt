package com.nikyokki

import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.Episode
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
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class HDFilmCehennemi2 : MainAPI() {
    override var mainUrl = "https://www.hdfilmcehennemi2.biz"
    override var name = "HDFilmCehennemi2"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "${mainUrl}/" to "Yeni Eklenenler",
        "${mainUrl}/en-cok-izlenen-filmler/" to "En Çok İzlenenler",
        "${mainUrl}/imdb-7-puan-uzeri-filmler/" to "Imdb 7+ Filmler",
        "${mainUrl}/tur/aile-filmleri/" to "Aile",
        "${mainUrl}/tur/aksiyon-filmleri-izle/" to "Aksiyon",
        "${mainUrl}/tur/animasyon-film-izle/" to "Animasyon",
        "${mainUrl}/tur/bilim-kurgu-filmleri-izle/" to "Bilim Kurgu",
        "${mainUrl}/tur/biyografi-filmleri/" to "Biyografi",
        "${mainUrl}/tur/dram-filmleri/" to "Dram",
        "${mainUrl}/tur/fantastik-filmleri/" to "Fantastik",
        "${mainUrl}/tur/gerilim-filmleri/" to "Gerilim",
        "${mainUrl}/tur/gizem-filmleri/" to "Gizem",
        "${mainUrl}/tur/komedi-filmleri/" to "Komedi",
        "${mainUrl}/tur/korku-filmleri/" to "Korku",
        "${mainUrl}/tur/macera-filmleri/" to "Macera",
        "${mainUrl}/tur/romantik-filmler/" to "Romantik",
        "${mainUrl}/tur/savas-filmleri/" to "Savaş",
        "${mainUrl}/tur/suc-filmleri/" to "Suç",
        "${mainUrl}/tur/tarih-filmleri/" to "Tarih",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}?page=${page}").document
        val home = document.select("a[class*=group/poster]").mapNotNull { it.toMainPageResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val img = this.selectFirst("img") ?: return null
        val title = img.attr("alt").replace(" izle", "").trim()
        val href = fixUrlNull(this.attr("href")) ?: return null
        val posterUrl = fixUrlNull(img.attr("src") ?: img.attr("data-src"))
        val scoreText = this.select("span").find { it.text().matches(Regex("^[0-9.]+$")) }?.text()

        if (href.contains("/dizi/")) {
            return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.score = Score.from10(scoreText)
            }
        } else {
            return newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.score = Score.from10(scoreText)
            }
        }
    }

    private fun toSearchRes(movie: SearchResult): SearchResponse {
        val title = movie.title ?: ""
        val posterUrl = movie.posterUrl
        val href = fixUrlNull("$mainUrl/${movie.slug}") ?: ""
        val score = Score.from10(movie.imdbRating?.toString())
        
        return if (movie.contentableType == "App\\Models\\TVSeries" || href.contains("/dizi/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.score = score
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { 
                this.posterUrl = posterUrl
                this.score = score
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/api/search?q=$query"
        val response = app.get(
            url,
            headers = mapOf("X-Requested-With" to "XMLHttpRequest", "Accept" to "application/json"),
            referer = "$mainUrl/"
        ).text

        val json = AppUtils.tryParseJson<ApiSearchResponse>(response)
        
        return json?.data?.map { toSearchRes(it) } ?: emptyList()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("div.title a")?.text() ?: return null
        val href = fixUrlNull(this.selectFirst("div.title a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))

        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text()?.substringBefore(" izle", "")?.trim() ?: ""
        
        // Extract metadata from JSON-LD schema
        val schemaScript = document.select("script[type=application/ld+json]").firstOrNull()?.data()
        var poster: String? = null
        var description: String? = null
        var year: Int? = null
        val tags = mutableListOf<String>()
        var rating: String? = null
        val actors = mutableListOf<Actor>()
        
        if (schemaScript != null) {
            val schema = AppUtils.tryParseJson<Map<String, Any>>(schemaScript)
            if (schema != null) {
                poster = schema["image"] as? String
                description = schema["description"] as? String
                val dateCreated = schema["dateCreated"] as? String
                year = dateCreated?.substringBefore("-")?.toIntOrNull()
                
                (schema["genre"] as? List<String>)?.let { tags.addAll(it) }
                
                val aggregateRating = schema["aggregateRating"] as? Map<String, Any>
                rating = aggregateRating?.get("ratingValue")?.toString()
                
                val actorList = schema["actor"] as? List<Map<String, Any>>
                actorList?.forEach {
                    val name = it["name"] as? String
                    if (name != null) {
                        actors.add(Actor(name, null))
                    }
                }
            }
        }
        
        if (poster == null) {
            poster = fixUrlNull(document.selectFirst("picture img")?.attr("data-src"))
        }
        
        val duration = 0
        val trailer = document.selectFirst("button[data-modal-target=trailerModal]")?.attr("data-src")?.let {
            if (it.startsWith("//")) "https:$it" else it
        }

        val recommendations = document.select("div.glide__slide a, a[class*=group/poster]").mapNotNull { it.toRecommendationResult() }

        if (!url.contains("/dizi/")) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = tags
                this.score = Score.from10(rating)
                this.duration = duration
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
            }
        } else {
            val episodes = mutableListOf<Episode>()
            document.select("a[href*=/sezon-][href*=/bolum-]").forEach { ep ->
                val epHref = fixUrlNull(ep.attr("href")) ?: return@forEach
                val text = ep.text().trim()
                if (text.isBlank()) return@forEach
                
                val seasonMatch = Regex("/sezon-([0-9]+)/").find(epHref)
                val episodeMatch = Regex("/bolum-([0-9]+)").find(epHref)
                val epSzn = seasonMatch?.groupValues?.get(1)?.toIntOrNull()
                val epnum = episodeMatch?.groupValues?.get(1)?.toIntOrNull()
                
                if (episodes.none { it.data == epHref }) {
                    episodes.add(
                        newEpisode(epHref) {
                            this.name = text
                            this.season = epSzn
                            this.episode = epnum
                        }
                    )
                }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = tags
                this.score = Score.from10(rating)
                this.duration = duration
                addActors(actors)
                addTrailer(trailer)
            }
        }
    }

    private fun Element.toRecommendationResult(): SearchResponse? {
        val img = this.selectFirst("img") ?: return null
        val title = img.attr("alt").replace(" izle", "").trim().ifEmpty { this.selectFirst("h2")?.text() ?: "" }
        val href = fixUrlNull(this.attr("href")) ?: return null
        val posterUrl = fixUrlNull(img.attr("src") ?: img.attr("data-src"))
        if (href.contains("/dizi/")) {
            return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("HDC", "data » $data")
        val responseText = app.get(data).text
        val document = Jsoup.parse(responseText)
        
        val dataRegex = Regex("""videoPlayerData\(JSON\.parse\('([^']+)'\)""")
        val match = dataRegex.find(responseText)
        
        if (match != null) {
            val jsonString = match.groupValues[1].replace("\\u0022", "\"").replace("\\/", "/")
            val videoMap = AppUtils.tryParseJson<Map<String, List<VideoData>>>(jsonString)
            
            videoMap?.forEach { (lang, videos) ->
                val langName = when (lang) {
                    "tr" -> "Türkçe Dublaj"
                    "en", "orjinal" -> "Türkçe Altyazılı"
                    "dual" -> "Dual"
                    else -> lang
                }
                videos.forEach { video ->
                    val link = video.link ?: return@forEach
                    val templateBase64 = video.template ?: return@forEach
                    
                    val templateDecoded = String(Base64.decode(templateBase64, Base64.DEFAULT), Charsets.UTF_8)
                    val iframeDoc = Jsoup.parse(templateDecoded)
                    val iframeSrc = iframeDoc.select("iframe").attr("data-src").ifEmpty { iframeDoc.select("iframe").attr("src") }
                    val finalUrl = iframeSrc.replace("{url}", link).let { if (it.startsWith("//")) "https:$it" else it }
                    
                    val name = "${video.serviceName} $langName"
                    Log.d("HDC", "Extracted video: $name -> $finalUrl")
                    
                    if (finalUrl.contains("vidload")) {
                        vidloadExtract(finalUrl, name, callback, subtitleCallback)
                    } else {
                        loadExtractor(finalUrl, data, subtitleCallback, callback)
                    }
                }
            }
        } else {
            // Fallback for older layouts if any
            val iframe = fixUrlNull(document.selectFirst("iframe")?.attr("data-src")) ?: ""
            if (iframe.isNotEmpty()) {
                if (iframe.contains("vidload")) {
                    vidloadExtract(iframe, "Video", callback, subtitleCallback)
                } else {
                    loadExtractor(iframe, data, subtitleCallback, callback)
                }
            }
        }
        
        return true
    }

    data class VideoData(
        @JsonProperty("link") val link: String? = null,
        @JsonProperty("service_name") val serviceName: String? = null,
        @JsonProperty("template") val template: String? = null
    )

    suspend fun vidloadExtract(
        iframe: String,
        name: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        Log.d("HDC", "vidloadExtract » $iframe")
        if (iframe.contains("vidload")) {
            val res = app.get(
                iframe,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                ), referer = mainUrl,
                allowRedirects = true
            )
            val docHtml = res.text
            val finalUrl = res.url
            val baseUrl = Regex("""(https?://[^/]+)""").find(finalUrl)?.groupValues?.get(1) ?: "https://vidload.top"

            val sourceRegex = Regex("""file:\s*["']([^"']+\.m3u8[^"']*)["']""")
            val sourceMatch = sourceRegex.find(docHtml)
            val sourcePath = sourceMatch?.groupValues?.get(1) ?: Jsoup.parse(docHtml).selectFirst("source")?.attr("src")

            if (sourcePath != null) {
                val source = if (sourcePath.startsWith("http")) sourcePath else baseUrl + sourcePath
                callback.invoke(
                    newExtractorLink(
                        source = "Vidload",
                        name = "Vidload $name",
                        url = source,
                        ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$baseUrl/"
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
            
            val subtitlesRegex = Regex("""subtitleTracks\s*=\s*(\[.*?\])""")
            val subtitlesMatch = subtitlesRegex.find(docHtml)
            if (subtitlesMatch != null) {
                val jsonStr = subtitlesMatch.groupValues[1]
                val tracks = AppUtils.tryParseJson<List<Map<String, Any>>>(jsonStr)
                tracks?.forEach { track ->
                    val file = track["file"] as? String ?: return@forEach
                    val label = track["label"] as? String ?: "Unknown"
                    val subUrl = if (file.startsWith("http")) file else baseUrl + file
                    subtitleCallback.invoke(
                        SubtitleFile(
                            label,
                            subUrl
                        )
                    )
                }
            } else {
                // Fallback for older script logic
                val script = Jsoup.parse(docHtml).select("script").find { it.data().contains("player.addRemoteTextTrack") }?.data() ?: ""
                val regex = Regex("""src:\s*'([^']*)'.*?label:\s*'([^']*)'""", RegexOption.DOT_MATCHES_ALL)
                regex.findAll(script).forEach { match ->
                    val src = match.groupValues[1]
                    val label = match.groupValues[2]
                    val subUrl = if (src.startsWith("http")) src else baseUrl + src
                    subtitleCallback.invoke(SubtitleFile(label, subUrl))
                }
            }
        }
    }

    data class ApiSearchResponse(
        @JsonProperty("data") val data: List<SearchResult>? = null
    )

    data class SearchResult(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("contentableType") val contentableType: String? = null,
        @JsonProperty("posterUrl") val posterUrl: String? = null,
        @JsonProperty("imdbRating") val imdbRating: Double? = null
    )
}


