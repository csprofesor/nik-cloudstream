// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.Score
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.util.regex.Pattern

class FullHDFilm : MainAPI() {
    override var mainUrl              = "https://hdfilm.us"
    override var name                 = "FullHDFilm"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries)

    // ! CloudFlare v2
    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor      by lazy { CloudflareInterceptor(cloudflareKiller) }

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller): Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request  = chain.request()
            val response = chain.proceed(request)
            val doc      = Jsoup.parse(response.peekBody(1024 * 1024).string())

            if (doc.html().contains("Just a moment")) {
                return cloudflareKiller.intercept(chain)
            }

            return response
        }
    }

    override val mainPage = mainPageOf(
        "${mainUrl}/tur/turkce-altyazili-film-izle"       to "Altyazılı Filmler",
        "${mainUrl}/tur/netflix-filmleri-izle/"		       to "Netflix",
        "${mainUrl}/tur/yerli-film-izle"		           to "Yerli Film",
        "${mainUrl}/category/aile-filmleri-izle"	       to "Aile",
        "${mainUrl}/category/aksiyon-filmleri-izle"       to "Aksiyon",
        "${mainUrl}/category/animasyon-filmleri-izle"     to "Animasyon",
        "${mainUrl}/category/belgesel-filmleri-izle"      to "Belgesel",
        "${mainUrl}/category/bilim-kurgu-filmleri-izle"   to "Bilim Kurgu",
        "${mainUrl}/category/biyografi-filmleri-izle"     to "Biyografi",
        "${mainUrl}/category/dram-filmleri-izle"          to "Dram",
        "${mainUrl}/category/fantastik-filmler-izle"      to "Fantastik",
        "${mainUrl}/category/gerilim-filmleri-izle"       to "Gerilim",
        "${mainUrl}/category/gizem-filmleri-izle"	       to "Gizem",
        "${mainUrl}/category/kisa"	                       to "Kısa",
        "${mainUrl}/category/komedi-filmleri-izle"	       to "Komedi",
        "${mainUrl}/category/korku-filmleri-izle"	       to "Korku",
        "${mainUrl}/category/macera-filmleri-izle"	       to "Macera",
        "${mainUrl}/category/muzik"	                       to "Müzik",
        "${mainUrl}/category/muzikal-filmleri-izle"	       to "Müzikal",
        "${mainUrl}/category/romantik-filmler-izle"       to "Romantik",
        "${mainUrl}/category/savas-filmleri-izle"         to "Savaş",
        "${mainUrl}/category/spor-filmleri-izle"          to "Spor",
        "${mainUrl}/category/suc-filmleri-izle"           to "Suç",
        "${mainUrl}/category/tarih-filmleri-izle"         to "Tarih",
        "${mainUrl}/category/western-filmleri-izle"       to "Western"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}/page/${page}"
        val document = app.get(url, interceptor = interceptor).document
        val movieBoxes = document.select("div.film-box, div.movie-box, div.movie-poster")
        val home = movieBoxes.mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title     = this.selectFirst("img")?.attr("alt") ?: this.selectFirst("div.name a")?.text() ?: return null
        val href      = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src") ?: this.selectFirst("img")?.attr("data-src"))
        val rating    = this.selectFirst("div.rating span.align-right, div.rating")?.text()?.trim()?.toFloatOrNull()

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.score = rating?.let { Score.from10(it) }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/?s=${query}", interceptor = interceptor).document

        return document.select("div.film-box, div.movie-box, div.movie-poster").mapNotNull { it.toSearchResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, interceptor = interceptor).document
    
        val title       = document.selectFirst("h1")?.text() ?: document.selectFirst("meta[property='og:title']")?.attr("content") ?: return null
        val poster      = fixUrlNull(document.selectFirst("div.poster img")?.attr("src") ?: document.selectFirst("div.poster img")?.attr("data-src") ?: document.selectFirst("meta[property='og:image']")?.attr("content"))
        val description = document.selectFirst("div.description")?.text()?.trim() ?: document.selectFirst("div.film")?.text()?.trim() ?: document.selectFirst("meta[property='og:description']")?.attr("content")?.trim()
        val tags        = document.select("ul.post-categories li a, div.tur.info a").map { it.text() }
        val year        = Regex("""(\d{4})""").find(document.selectFirst("li.release span, div.yayin-tarihi.info, div.category")?.text() ?: "")?.groupValues?.get(1)?.toIntOrNull()
        val rating      = document.selectFirst("div.imdb-count")?.text()?.substringBefore(" ")?.trim()?.toFloatOrNull()
        val actors      = document.select("div.actors.list a, div.cast a").map { Actor(it.text()) }

        val recommendations = document.select("div.related-movies div.movie-box, div.film-box").mapNotNull { el ->
            val recName = el.selectFirst("img")?.attr("alt") ?: el.selectFirst("div.name a")?.text() ?: return@mapNotNull null
            val recHref = fixUrlNull(el.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
            val recPoster = fixUrlNull(el.selectFirst("img")?.attr("src") ?: el.selectFirst("img")?.attr("data-src"))
            val recRating = el.selectFirst("div.rating span.align-right, div.rating")?.text()?.trim()?.toFloatOrNull()
            newMovieSearchResponse(recName, recHref, TvType.Movie) {
                this.posterUrl = recPoster
                this.score = recRating?.let { Score.from10(it) }
            }
        }

        val isSeries = url.lowercase().contains("-dizi") || tags.any { it.lowercase().contains("dizi") }

        if (isSeries) {
            val episodes = document.select("li.psec").mapNotNull { el ->
                val partId = el.attr("id")
                val partName = el.text().trim()
                if (partName.lowercase().contains("fragman")) return@mapNotNull null
                
                // Basit Sezon/Bölüm çıkarımı
                val s = Regex("""(\d+)\.\s*Sezon""").find(partName)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val e = Regex("""(\d+)\.\s*Bölüm""").find(partName)?.groupValues?.get(1)?.toIntOrNull() ?: 1

                newEpisode(url) {
                    this.name = partName
                    this.season = s
                    this.episode = e
                }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = rating?.let { Score.from10(it) }
                this.recommendations = recommendations
                this.actors = actors.map { ActorData(it) }
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.year = year
            this.plot = description
            this.tags = tags
            this.score = rating?.let { Score.from10(it) }
            this.recommendations = recommendations
            this.actors = actors.map { ActorData(it) }
        }
    }

    private fun getIframe(sourceCode: String): String {
        // Base64 kodlu iframe'i içeren script bloğunu yakala
        val base64ScriptRegex = Regex("""<script[^>]*>(PCEtLWJhc2xpazp[^<]*)</script>""")
        val base64Encoded = base64ScriptRegex.find(sourceCode)?.groupValues?.get(1) ?: return ""
    
        return try {
            // Base64 decode
            val decodedHtml = String(Base64.decode(base64Encoded, Base64.DEFAULT), Charsets.UTF_8)
            // Decode edilmiş HTML içinden iframe src'sini bul
            val iframeMatch = Regex("""src=["']([^"']+)["']""").find(decodedHtml)
            iframeMatch?.groupValues?.get(1) ?: ""
        } catch (e: Exception) {
            Log.e("FHDF", "Base64 decode error", e)
            ""
        }
    }

    private fun extractSubtitleUrl(sourceCode: String): String? {
        val patterns = listOf(
            Pattern.compile("var playerjsSubtitle = \"\\[Türkçe\\](https?://[^\\s\"]+?\\.srt)\""),
            Pattern.compile("var playerjsSubtitle = \"(https?://[^\\s\"]+?\\.srt)\""),
            Pattern.compile("subtitle:\\s*\"(https?://[^\\s\"]+?\\.srt)\"")
        )
        for (pattern in patterns) {
            val matcher = pattern.matcher(sourceCode)
            if (matcher.find()) return matcher.group(1)
        }
        return null
    }

    private suspend fun extractSubtitleFromIframe(iframeUrl: String): String? {
        if (iframeUrl.isEmpty()) return null
        return try {
            val headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36")
            val iframeResponse = app.get(iframeUrl, headers=headers)
            extractSubtitleUrl(iframeResponse.text)
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36",
            "Referer" to mainUrl
        )

        val mainDoc = app.get(data, headers=headers, interceptor = interceptor).document
        
        // Dublaj/Altyazı alternatiflerini bul
        val pageLinks = mutableListOf<Pair<String, String>>()
        pageLinks.add("Ana Sunucu" to data) // Mevcut sayfa (genellikle dublaj)

        // Diğer sayfaları (altyazı vb.) bul
        mainDoc.select("div#action-parts a[href]").forEach {
            val href = it.attr("href")
            val linkText = it.text().trim()
            if (href.contains("?page=")) {
                val linkUrl = if (href.startsWith("?")) {
                    "${data.split("?")[0].removeSuffix("/")}$href"
                } else {
                    fixUrlNull(href)
                }
                if (linkUrl != null && !pageLinks.any { p -> p.second == linkUrl }) {
                    pageLinks.add(linkText to linkUrl)
                }
            }
        }

        Log.d("FHDF", "Pages to process: ${pageLinks.map { it.second }}")
        var foundLinks = false

        for ((name, pageUrl) in pageLinks) {
            val sourceName = if (name.isBlank() || name == "Ana Sunucu") "Vidpapi" else "Vidpapi - $name"
            
            try {
                val response = app.get(pageUrl, headers=headers, interceptor = interceptor)
                val sourceCode = response.text

                // Ana sayfadan altyazı URL’sini çek
                var subtitleUrl = extractSubtitleUrl(sourceCode)

                // Iframe’den URL’yi çek
                val iframeSrc = getIframe(sourceCode)
                Log.d("FHDF", "iframeSrc for $pageUrl: $iframeSrc")

                if (subtitleUrl == null && iframeSrc.isNotEmpty()) {
                    subtitleUrl = extractSubtitleFromIframe(iframeSrc)
                }

                // Altyazı bulunduysa ekle
                if (subtitleUrl != null) {
                    try {
                        val subtitleResponse = app.get(subtitleUrl, headers=headers, allowRedirects=true, interceptor = interceptor)
                        if (subtitleResponse.isSuccessful) {
                            @Suppress("DEPRECATION")
                            subtitleCallback(SubtitleFile("Türkçe", subtitleUrl))
                            Log.d("FHDF", "Subtitle added: $subtitleUrl")
                        }
                    } catch (e: Exception) {
                        Log.d("FHDF", "Subtitle URL error: ${e.message}")
                    }
                }

                if (iframeSrc.contains("vidpapi.xyz")) {
                    val videoId = iframeSrc.split("/").lastOrNull() ?: continue
                    val iframeResponse = app.get(iframeSrc, headers=mapOf(
                        "User-Agent" to headers["User-Agent"]!!,
                        "Referer" to mainUrl
                    ), interceptor = interceptor)
                    
                    val fpCookie = iframeResponse.cookies["fireplayer_player"] ?: ""
                    Log.d("FHDF", "Vidpapi cookie: $fpCookie")

                    val apiURL = "https://vidpapi.xyz/player/index.php?data=$videoId&do=getVideo"
                    val apiHeaders = mapOf(
                        "User-Agent" to headers["User-Agent"]!!,
                        "Referer" to iframeSrc,
                        "X-Requested-With" to "XMLHttpRequest",
                        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                        "Cookie" to "fireplayer_player=$fpCookie"
                    )

                    val apiResponse = app.post(apiURL, headers=apiHeaders, data=mapOf("data" to videoId, "do" to "getVideo"))
                    val securedLink = Regex("""securedLink":"([^"]+)""").find(apiResponse.text)?.groupValues?.get(1)?.replace("\\/", "/")
                    
                    if (securedLink != null && securedLink.isNotBlank()) {
                        Log.d("FHDF", "Found M3U8: $securedLink")
                        callback(newExtractorLink(
                            sourceName,
                            sourceName,
                            securedLink,
                            type = ExtractorLinkType.M3U8
                        ) {
                            referer = mainUrl
                        })
                        foundLinks = true
                    }
                } else if (iframeSrc.isNotEmpty()) {
                    // Diğer extractors (vidmoly vb.)
                    if (loadExtractor(iframeSrc, data, subtitleCallback, callback)) {
                        foundLinks = true
                    }
                }
            } catch (e: Exception) {
                Log.e("FHDF", "Error loading links for $pageUrl", e)
            }
        }

        return foundLinks
    }
}
