// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer

class FilmModu : MainAPI() {
    override var mainUrl              = "https://www.filmmodu.one"
    override var name                 = "FilmModu"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "${mainUrl}/hd-film-kategori/4k-film-izle"          to "4K",
        "${mainUrl}/hd-film-kategori/aile-filmleri"         to "Aile",
        "${mainUrl}/hd-film-kategori/aksiyon"               to "Aksiyon",
        "${mainUrl}/hd-film-kategori/animasyon"             to "Animasyon",
        "${mainUrl}/hd-film-kategori/belgeseller"           to "Belgesel",
        "${mainUrl}/hd-film-kategori/bilim-kurgu-filmleri"  to "Bilim-Kurgu",
        "${mainUrl}/hd-film-kategori/dram-filmleri"         to "Dram",
        "${mainUrl}/hd-film-kategori/fantastik-filmler"     to "Fantastik",
        "${mainUrl}/hd-film-kategori/gerilim"               to "Gerilim",
        "${mainUrl}/hd-film-kategori/gizem-filmleri"        to "Gizem",
        "${mainUrl}/hd-film-kategori/hd-hint-filmleri"      to "Hint Filmleri",
        "${mainUrl}/hd-film-kategori/kisa-film"             to "Kısa Film",
        "${mainUrl}/hd-film-kategori/hd-komedi-filmleri"    to "Komedi",
        "${mainUrl}/hd-film-kategori/komedi"                to "Komedi",
        "${mainUrl}/hd-film-kategori/korku-filmleri"        to "Korku",
        "${mainUrl}/hd-film-kategori/kult-filmler-izle"     to "Kült Filmler",
        "${mainUrl}/hd-film-kategori/macera-filmleri"       to "Macera",
        "${mainUrl}/hd-film-kategori/muzik"                 to "Müzik",
        "${mainUrl}/hd-film-kategori/odullu-filmler-izle"   to "Oscar Ödüllü Filmler",
        "${mainUrl}/hd-film-kategori/romantik-filmler"      to "Romantik",
        "${mainUrl}/hd-film-kategori/savas"                 to "Savaş",
        "${mainUrl}/hd-film-kategori/savas-filmleri"        to "Savaş",
        "${mainUrl}/hd-film-kategori/stand-up"              to "Stand Up",
        "${mainUrl}/hd-film-kategori/suc-filmleri"          to "Suç",
        "${mainUrl}/hd-film-kategori/tarih"                 to "Tarih",
        "${mainUrl}/hd-film-kategori/tavsiye-filmler"       to "Tavsiye Filmler",
        "${mainUrl}/hd-film-kategori/tv-film"               to "TV film",
        "${mainUrl}/hd-film-kategori/vahsi-bati-filmleri"   to "Vahşi Batı",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}?page=${page}", referer = "$mainUrl/").document
        val home     = document.select("div.movie, div.movie-large").mapNotNull { it.toMainPageResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val title     = this.selectFirst("a")?.text()?.ifEmpty { null } ?: this.selectFirst("img")?.attr("alt") ?: return null
        val href      = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterImg = this.selectFirst("picture img") ?: this.selectFirst("img")
        val posterUrl = fixUrlNull(posterImg?.attr("data-src")?.ifEmpty { null } ?: posterImg?.attr("src"))

        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/film-ara?term=${query}", referer = "$mainUrl/").document

        return document.select("div.movie").mapNotNull { it.toMainPageResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, referer = "$mainUrl/").document

        val orgTitle    = document.selectFirst("div.titles h1")?.text()?.trim() ?: return null
        val altTitle    = document.selectFirst("div.titles h2")?.text()?.trim() ?: ""
        val title       = if (altTitle.isNotEmpty()) "$orgTitle - $altTitle" else orgTitle
        val poster      = fixUrlNull(document.selectFirst("img.img-responsive")?.attr("src"))
        val description = document.selectFirst("p[itemprop='description']")?.text()?.trim()
        val year        = document.selectFirst("span[itemprop='dateCreated']")?.text()?.trim()?.toIntOrNull()
        val tags        = document.select("div.description a[href*='-kategori/']").map { it.text() }
        val actors      = document.select("div.description a[href*='-oyuncu-']").map { Actor(it.selectFirst("span")!!.text()) }
        val trailer     = document.selectFirst("div.container iframe")?.attr("src")

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot      = description
            this.year      = year
            this.tags      = tags
            addActors(actors)
            addTrailer(trailer)
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("FLMMD", "data » $data")
        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        val mainResp = app.get(data, referer = "$mainUrl/", headers = mapOf("User-Agent" to userAgent))
        val document = mainResp.document

        document.select("div.alternates a").forEach {
            val altLink = fixUrlNull(it.attr("href")) ?: return@forEach
            val altName = it.text()
            if (altName.contains("Fragman", ignoreCase = true) || altName.contains("Trailer", ignoreCase = true)) return@forEach

            val altReq  = app.get(altLink, referer = data, headers = mapOf("User-Agent" to userAgent))
            val altCookies = altReq.headers.values("Set-Cookie").joinToString("; ") { it.substringBefore(";") }

            val vidId   = Regex("""var videoId = '(.*)'""").find(altReq.text)?.groupValues?.get(1) ?: return@forEach
            val vidType = Regex("""var videoType = '(.*)'""").find(altReq.text)?.groupValues?.get(1) ?: return@forEach

            val sourceReq = app.get("${mainUrl}/get-source?movie_id=${vidId}&type=${vidType}", referer = altLink, headers = mapOf("User-Agent" to userAgent, "X-Requested-With" to "XMLHttpRequest"))
            val sourceCookies = sourceReq.headers.values("Set-Cookie").joinToString("; ") { it.substringBefore(";") }
            val cookies = listOf(altCookies, sourceCookies).filter { it.isNotBlank() }.joinToString("; ")

            val vidReq = sourceReq.parsedSafe<GetSource>() ?: return@forEach

            if (vidReq.subtitle != null) {
                subtitleCallback.invoke(
                    SubtitleFile(
                        lang = "Türkçe",
                        url  = fixUrl(vidReq.subtitle)
                    )
                )
            }

            vidReq.sources?.forEach { source ->
                callback.invoke(
                    newExtractorLink(
                        source  = "${this.name} - $altName",
                        name    = "${this.name} - $altName",
                        url     = fixUrl(source.src),
                        type    = ExtractorLinkType.M3U8
                    ) {
                        quality = getQualityFromName(source.label)
                        headers = mapOf(
                            "Referer" to "${mainUrl}/",
                            "Origin" to mainUrl,
                            "User-Agent" to userAgent,
                            if (cookies.isNotBlank()) "Cookie" to cookies else "" to ""
                        ).filterKeys { it.isNotBlank() }
                    }
                )
            }
        }

        return true
    }
}
