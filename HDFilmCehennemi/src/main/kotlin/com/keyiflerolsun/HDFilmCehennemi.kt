// ! https://github.com/hexated/cloudstream-extensions-hexated/blob/master/Hdfilmcehennemi/src/main/kotlin/com/hexated/Hdfilmcehennemi.kt

package com.keyiflerolsun

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.Actor
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
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.base64DecodeArray
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.lang.Math.floorMod

class HDFilmCehennemi : MainAPI() {
    override var mainUrl = "https://www.hdfilmcehennemi.nl"
    override var name = "HDFilmCehennemi"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 200L
    override var sequentialMainPageScrollDelay = 200L

    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor by lazy { CloudflareInterceptor(cloudflareKiller) }

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val response = chain.proceed(chain.request())
            val doc = Jsoup.parse(response.peekBody(1024 * 1024).string())
            if (doc.select("title").text() == "Just a moment..." || doc.select("title").text() == "Bir dakika lütfen...") {
                return cloudflareKiller.intercept(chain)
            }
            return response
        }
    }

    override val mainPage = mainPageOf(
        "${mainUrl}/load/page/sayfano/home/" to "Yeni Eklenen Filmler",
        "${mainUrl}/load/page/sayfano/categories/nette-ilk-filmler/" to "Nette İlk Filmler",
        "${mainUrl}/load/page/sayfano/home-series/" to "Yeni Eklenen Diziler",
        "${mainUrl}/load/page/sayfano/categories/tavsiye-filmler-izle2/" to "Tavsiye Filmler",
        "${mainUrl}/load/page/sayfano/imdb7/" to "IMDB 7+ Filmler",
        "${mainUrl}/load/page/sayfano/mostCommented/" to "En Çok Yorumlananlar",
        "${mainUrl}/load/page/sayfano/mostLiked/" to "En Çok Beğenilenler",
        "${mainUrl}/load/page/sayfano/genres/aile-filmleri-izleyin-6/" to "Aile Filmleri",
        "${mainUrl}/load/page/sayfano/genres/aksiyon-filmleri-izleyin-5/" to "Aksiyon Filmleri",
        "${mainUrl}/load/page/sayfano/genres/animasyon-filmlerini-izleyin-5/" to "Animasyon Filmleri",
        "${mainUrl}/load/page/sayfano/genres/belgesel-filmlerini-izle-1/" to "Belgesel Filmleri",
        "${mainUrl}/load/page/sayfano/genres/bilim-kurgu-filmlerini-izleyin-3/" to "Bilim Kurgu Filmleri",
        "${mainUrl}/load/page/sayfano/genres/komedi-filmlerini-izleyin-1/" to "Komedi Filmleri",
        "${mainUrl}/load/page/sayfano/genres/korku-filmlerini-izle-4/" to "Korku Filmleri",
        "${mainUrl}/load/page/sayfano/genres/romantik-filmleri-izle-2/" to "Romantik Filmleri",
        "${mainUrl}/load/page/sayfano/genres/suc-filmleri-izle-3/" to "Suç Filmleri",
        "${mainUrl}/load/page/sayfano/genres/tarih-filmleri-izle-4/" to "Tarih Filmleri"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val mapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        val url = request.data.replace("sayfano", page.toString())
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:137.0) Gecko/20100101 Firefox/137.0",
            "Accept" to "*/*",
            "X-Requested-With" to "fetch"
        )
        val doc = app.get(url, headers = headers, referer = mainUrl, interceptor = interceptor)
        if (doc.toString().contains("Sayfa Bulunamadı")) return newHomePageResponse(request.name, emptyList())
        val aa: HDFC = mapper.readValue(doc.toString())
        return newHomePageResponse(request.name, Jsoup.parse(aa.html).select("a").mapNotNull { it.toSearchResult() })
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = attr("title")
        val href = fixUrlNull(attr("href")) ?: return null
        val posterUrl = fixUrlNull(selectFirst("img")?.attr("data-src"))
        val score = selectFirst("span.imdb")?.text()?.trim()
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.score = Score.from10(score)
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.get("${mainUrl}/search?q=${query}", headers = mapOf("X-Requested-With" to "fetch")).parsedSafe<Results>() ?: return emptyList()
        return response.results.mapNotNull { html ->
            val document = Jsoup.parse(html)
            val title = document.selectFirst("h4.title")?.text() ?: return@mapNotNull null
            val href = fixUrlNull(document.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
            val posterUrl = fixUrlNull(document.selectFirst("img")?.attr("src")) ?: fixUrlNull(document.selectFirst("img")?.attr("data-src"))
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl?.replace("/thumb/", "/list/") }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, interceptor = interceptor).document
        val title = document.selectFirst("h1.section-title")?.text()?.substringBefore(" izle") ?: return null
        val poster = fixUrlNull(document.select("aside.post-info-poster img.lazyload").lastOrNull()?.attr("data-src"))
        val tags = document.select("div.post-info-genres a").map { it.text() }
        val year = document.selectFirst("div.post-info-year-country a")?.text()?.trim()?.toIntOrNull()
        val tvType = if (document.select("div.seasons").isEmpty()) TvType.Movie else TvType.TvSeries
        val description = document.selectFirst("article.post-info-content > p")?.text()?.trim()
        val rating = document.selectFirst("div.post-info-imdb-rating span")?.text()?.substringBefore("(")?.trim()
        val actors = document.select("div.post-info-cast a").map { Actor(it.selectFirst("strong")!!.text(), it.select("img").attr("data-src")) }
        val recommendations = document.select("div.section-slider-container div.slider-slide").mapNotNull {
            val n = it.selectFirst("a")?.attr("title") ?: return@mapNotNull null
            val h = fixUrlNull(it.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
            val p = fixUrlNull(it.selectFirst("img")?.attr("data-src")) ?: fixUrlNull(it.selectFirst("img")?.attr("src"))
            newTvSeriesSearchResponse(n, h, TvType.TvSeries) { this.posterUrl = p }
        }
        return if (tvType == TvType.TvSeries) {
            val episodes = document.select("div.seasons-tab-content a").mapNotNull {
                val epName = it.selectFirst("h4")?.text()?.trim() ?: return@mapNotNull null
                val epHref = fixUrlNull(it.attr("href")) ?: return@mapNotNull null
                val epEpisode = Regex("""(\d+)\. ?Bölüm""").find(epName)?.groupValues?.get(1)?.toIntOrNull()
                val epSeason = Regex("""(\d+)\. ?Sezon""").find(epName)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                newEpisode(epHref) { name = epName; season = epSeason; episode = epEpisode }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster; this.year = year; this.plot = description; this.tags = tags; this.score = Score.from10(rating); this.recommendations = recommendations; addActors(actors)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster; this.year = year; this.plot = description; this.tags = tags; this.score = Score.from10(rating); this.recommendations = recommendations; addActors(actors)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("HDCH", "loadLinks data=$data")
        val document = app.get(data, interceptor = interceptor).document
        val buttons = document.select("div.alternative-links button.alternative-link")
        var found = false

        buttons.forEach { button ->
            val videoID = button.attr("data-video").trim()
            if (videoID.isEmpty()) return@forEach

            val apiUrl = "${mainUrl}/video/$videoID/"
            val apiGet = app.get(
                apiUrl,
                interceptor = interceptor,
                headers = mapOf("Accept" to "*/*", "X-Requested-With" to "fetch"),
                referer = data
            ).text

            Log.d("HDCH", "videoID=$videoID responseLength=${apiGet.length}")
            val candidates = linkedSetOf<String>()

            Regex("""data-src\\?=\\?[\"']([^\"']+)""").findAll(apiGet).forEach { candidates += it.groupValues[1] }
            Regex("""<iframe[^>]+(?:src|data-src)=[\"']([^\"']+)[\"']""", RegexOption.IGNORE_CASE).findAll(apiGet).forEach { candidates += it.groupValues[1] }
            Regex("""(?:https?:)?//[^\"'<>\\s]+""").findAll(apiGet).forEach { candidates += it.value }

            Jsoup.parse(apiGet).select("iframe[src], iframe[data-src], video[src], source[src]").forEach { el ->
                el.attr("src").takeIf { it.isNotBlank() }?.let { candidates += it }
                el.attr("data-src").takeIf { it.isNotBlank() }?.let { candidates += it }
            }

            candidates.map { it.replace("\\\\", "").replace("&amp;", "&").trim() }
                .filter { it.isNotBlank() }
                .forEach { raw ->
                    val link = when {
                        raw.startsWith("//") -> "https:$raw"
                        raw.startsWith("/") -> mainUrl + raw
                        else -> raw
                    }
                    Log.d("HDCH", "candidate=$link")
                    found = true
                    if (link.contains(".m3u8", ignoreCase = true)) {
                        callback(newExtractorLink(this.name, "HDFilmCehennemi", link, type = com.lagradost.cloudstream3.utils.ExtractorLinkType.M3U8) {
                            referer = data
                            quality = com.lagradost.cloudstream3.utils.Qualities.Unknown.value
                        })
                    } else if (link.contains(".mp4", ignoreCase = true)) {
                        callback(newExtractorLink(this.name, "HDFilmCehennemi", link) {
                            referer = data
                            quality = com.lagradost.cloudstream3.utils.Qualities.Unknown.value
                        })
                    } else {
                        loadExtractor(link, data, subtitleCallback, callback)
                    }
                }
        }

        Log.d("HDCH", "linksFound=$found buttons=${buttons.size}")
        return found
    }

    private fun dcHello(base64Input: String): String {
        val decodedOnce = base64Decode(base64Input)
        val reversedString = decodedOnce.reversed()
        val decodedTwice = base64Decode(reversedString)
        return when {
            decodedTwice.contains("+") -> decodedTwice.substringAfterLast("+")
            decodedTwice.contains(" ") -> decodedTwice.substringAfterLast(" ")
            decodedTwice.contains("|") -> decodedTwice.substringAfterLast("|")
            else -> decodedTwice
        }
    }

    fun dcNew(parts: List<String>): String {
        val decodedBytes = base64DecodeArray(parts.joinToString(""))
        val rot13Bytes = decodedBytes.map { byte ->
            val c = byte.toInt()
            when (c) {
                in 'a'.code..'z'.code -> (((c - 'a'.code + 13) % 26) + 'a'.code).toByte()
                in 'A'.code..'Z'.code -> (((c - 'A'.code + 13) % 26) + 'A'.code).toByte()
                else -> byte
            }
        }.toByteArray()
        val reversedBytes = rot13Bytes.reversedArray()
        val unmixedBytes = ByteArray(reversedBytes.size)
        for (i in reversedBytes.indices) {
            val charCode = reversedBytes[i].toInt() and 0xFF
            unmixedBytes[i] = floorMod(charCode - (399756995 % (i + 5)), 256).toByte()
        }
        return String(unmixedBytes, Charsets.ISO_8859_1)
    }

    private data class SubSource(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("language") val language: String? = null,
        @JsonProperty("kind") val kind: String? = null
    )

    data class Results(@JsonProperty("results") val results: List<String> = arrayListOf())
    data class HDFC(@JsonProperty("html") val html: String, @JsonProperty("meta") val meta: Meta)
    data class Meta(
        @JsonProperty("title") val title: String,
        @JsonProperty("canonical") val canonical: Any? = null,
        @JsonProperty("keywords") val keywords: Any? = null
    )
}