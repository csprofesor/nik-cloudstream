// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
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

    override val mainPage = mainPageOf(
        "${mainUrl}/filmizle/turkce-altyazili-filmler/"  to "Altyazılı Filmler",
        "${mainUrl}/filmizle/turkce-dublaj-film/"       to "Türkçe Dublaj",
        "${mainUrl}/filmizle/yerli-filmler/"            to "Yerli Film",
        "${mainUrl}/filmizle/vizyon-filmleri/"          to "Vizyon Filmleri",
        "${mainUrl}/filmizle/aile-filmleri/"            to "Aile",
        "${mainUrl}/filmizle/aksiyon-filmleri/"         to "Aksiyon",
        "${mainUrl}/filmizle/animasyon-filmleri/"       to "Animasyon",
        "${mainUrl}/filmizle/belgesel/"                 to "Belgesel",
        "${mainUrl}/filmizle/bilim-kurgu-filmleri/"     to "Bilim Kurgu",
        "${mainUrl}/filmizle/biyografi-filmleri/"       to "Biyografi",
        "${mainUrl}/filmizle/dram-filmleri/"            to "Dram",
        "${mainUrl}/filmizle/fantastik-filmler/"        to "Fantastik",
        "${mainUrl}/filmizle/gerilim-filmleri/"         to "Gerilim",
        "${mainUrl}/filmizle/gizem-filmleri/"           to "Gizem",
        "${mainUrl}/filmizle/komedi-filmleri/"          to "Komedi",
        "${mainUrl}/filmizle/korku-filmleri/"           to "Korku",
        "${mainUrl}/filmizle/macera-filmleri/"          to "Macera",
        "${mainUrl}/filmizle/muzikal-filmler/"          to "Müzikal",
        "${mainUrl}/filmizle/romantik-filmler/"         to "Romantik",
        "${mainUrl}/filmizle/savas-filmleri/"           to "Savaş",
        "${mainUrl}/filmizle/spor-filmleri/"            to "Spor",
        "${mainUrl}/filmizle/suc-filmleri/"             to "Suç",
        "${mainUrl}/filmizle/tarih-filmleri/"           to "Tarih",
        "${mainUrl}/filmizle/genclik-filmleri/"         to "Gençlik",
        "${mainUrl}/filmizle/yesilcam-filmleri/"        to "Yeşilçam"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}/page/${page}"
        val document = app.get(url).document
        val movieBoxes = document.select("div.movie-poster")
        val home = movieBoxes.mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title     = this.selectFirst("img")?.attr("alt") ?: return null
        val href      = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))

        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/?s=${query}").document

        return document.select("div.movie-poster").mapNotNull { it.toSearchResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
    
        val title       = document.selectFirst("h1")?.text() ?: return null
        val poster      = fixUrlNull(document.selectFirst("div.poster img")?.attr("src"))
        val description = document.selectFirst("div.film")?.text()?.trim() ?: document.selectFirst("meta[property='og:description']")?.attr("content")?.trim()
        val tags        = document.select("div.tur.info a").map { it.text() }
        val year        = Regex("""(\d{4})""").find(document.selectFirst("div.yayin-tarihi.info")?.text()?.trim() ?: "")?.groupValues?.get(1)?.toIntOrNull()
        val actors      = document.selectFirst("div.oyuncular")?.ownText()?.split(",")?.map { Actor(it.trim()) } ?: emptyList()

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
                this.actors = actors.map { ActorData(it) }
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.year = year
            this.plot = description
            this.tags = tags
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

        val mainDoc = app.get(data, headers=headers).document
        
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
                val response = app.get(pageUrl, headers=headers)
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
                        val subtitleResponse = app.get(subtitleUrl, headers=headers, allowRedirects=true)
                        if (subtitleResponse.isSuccessful) {
                            @Suppress("DEPRECATION")
                            subtitleCallback(com.lagradost.cloudstream3.SubtitleFile("Türkçe", subtitleUrl))
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
                    ))
                    
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
