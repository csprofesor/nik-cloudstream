package com.lagradost

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element


class Anizm : MainAPI() {
    override var mainUrl = "https://anizm.net"
    override var name = "Anizm"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {
        private const val mainServer = "https://anizmplayer.com"
    }

    override val mainPage = mainPageOf(
        "$mainUrl/anime-izle?sayfa=" to "Son Eklenen Animeler",
        "$mainUrl/kategoriler/1" to "Macera",
        "$mainUrl/kategoriler/2" to "Aksiyon",
        "$mainUrl/kategoriler/3" to "Komedi",
        "$mainUrl/kategoriler/4" to "Dram",
        "$mainUrl/kategoriler/5" to "Romantizm",
        "$mainUrl/kategoriler/8" to "Bilim-Kurgu",
        "$mainUrl/kategoriler/13" to "Fantastik",
        "$mainUrl/kategoriler/20" to "Korku",
        "$mainUrl/kategoriler/22" to "Filmler",
        "$mainUrl/kategoriler/26" to "Okul",
        "$mainUrl/kategoriler/34" to "Shounen",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val isCategory = request.data.contains("/kategoriler/")
        val url = if (isCategory) {
            if (page <= 1) request.data else request.data + "?page=" + page
        } else {
            request.data + page
        }

        val document = app.get(url).document

        val home = if (isCategory) {
            document.select("a[href]")
                .mapNotNull { it.toSearchResult() }
                .distinctBy { it.url }
        } else {
            document.select("div.restrictedWidth div#episodesMiddle")
                .mapNotNull { it.toSearchResult() }
        }

        return newHomePageResponse(request.name, home)
    }

    private fun getProperAnimeLink(uri: String): String {
        return if (uri.contains("-bolum")) {
            "$mainUrl/${uri.substringAfter("$mainUrl/").replace(Regex("-[0-9]+-bolum.*"), "")}"
        } else {
            uri
        }
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val link = if (tagName() == "a") this else selectFirst("a") ?: return null
        val href = getProperAnimeLink(link.attr("href"))

        if (href.isBlank() ||
            href.contains("/kategoriler/") ||
            href.contains("/anime-izle") ||
            href.contains("/takvim") ||
            href.contains("/giris") ||
            href.contains("/kayit") ||
            href.contains("/fullViewSearch") ||
            href.contains("javascript:")
        ) return null

        // Site menüsündeki kartlar da img içerdiği için yalnızca anime detay
        // sayfalarına benzeyen tek-segment Anizm URL'lerini kabul et.
        val path = href.removePrefix(mainUrl).trim('/')
        val reservedPaths = setOf(
            "kayit-ol", "kayit", "kategoriler", "sasirt-beni", "tavsiye-robotu",
            "fansublar", "manga", "anime-haber", "discord", "izle", "takvim",
            "giris", "fullViewSearch", "iletisim", "hakkimizda", "sss"
        )
        if (path.isBlank() || path.contains("/") || path in reservedPaths) return null

        var card: Element = link
        var parent = link.parent()
        repeat(6) {
            if (parent == null) return@repeat
            if (parent.selectFirst("img") != null) {
                card = parent
                return@repeat
            }
            parent = parent.parent()
        }

        val image = card.selectFirst("img") ?: return null
        val title = link.text().trim().takeIf { it.isNotBlank() }
            ?: card.selectFirst("div.title, h5.animeTitle a, h5.animeTitle, h5, .title")?.text()?.trim()
            ?: return null

        val posterUrl = fixUrlNull(
            image.attr("src").ifBlank {
                image.attr("data-src").ifBlank {
                    image.attr("data-original").ifBlank { image.attr("data-lazy-src") }
                }
            }
        )

        val episode = card.selectFirst("div.truncateText")?.text()?.let {
            Regex("([0-9]+).?\\s?Bölüm").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addSub(episode)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get(
            "$mainUrl/fullViewSearch?search=$query&skip=0",
            headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        ).document

        return document.select("div.searchResultItem").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst(
            "h1, h2.anizm_pageTitle a, h2.anizm_pageTitle, .animeTitle, .title"
        )?.text()?.trim().takeIf { !it.isNullOrBlank() }
            ?: url.substringAfterLast("/").replace("-", " ").trim()

        val poster = fixUrlNull(
            document.selectFirst(
                "div.infoPosterImg img, .infoPosterImg img, .poster img, img"
            )?.let { img ->
                img.attr("src").ifBlank {
                    img.attr("data-src").ifBlank {
                        img.attr("data-original").ifBlank { img.attr("data-lazy-src") }
                    }
                }
            }
        )

        val episodes = document.select("a[href]").mapNotNull { a ->
            val name = a.text().trim()
            val href = a.attr("href")
            if (name.contains("Bölüm", ignoreCase = true) && href.isNotBlank()) {
                newEpisode(fixUrl(href)) { this.name = name }
            } else null
        }.distinctBy { it.name }

        val type = if (episodes.isEmpty()) TvType.Movie else TvType.Anime
        val trailer = document.selectFirst("iframe")?.attr("src")

        return newAnimeLoadResponse(title, url, type) {
            posterUrl = poster
            addEpisodes(DubStatus.Subbed, episodes)
            plot = document.selectFirst("div.infoDesc, .infoDesc, .description")?.text()?.trim()
            this.tags = document.select("span.dataValue span.ui.label, .ui.label").map { it.text() }
            addTrailer(trailer)
        }
    }

    private suspend fun invokeLokalSource(
        url: String,
        translator: String,
        sourceCallback: (ExtractorLink) -> Unit
    ) {
        app.get(url, referer = "$mainUrl/").document.select("script").find { script ->
            script.data().contains("eval(function(p,a,c,k,e,d)")
        }?.let {
            val key = getAndUnpack(it.data()).substringAfter("FirePlayer(\"").substringBefore("\",")
            val referer = "$mainServer/video/$key"
            val link = "$mainServer/player/index.php?data=$key&do=getVideo"
            Log.i("hexated", link)
            app.post(
                link,
                data = mapOf("hash" to key, "r" to "$mainUrl/"),
                referer = referer,
                headers = mapOf(
                    "Accept" to "*/*",
                    "Origin" to mainServer,
                    "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                    "X-Requested-With" to "XMLHttpRequest"
                )
            ).parsedSafe<Source>()?.videoSource?.let { m3uLink ->
                M3u8Helper.generateM3u8(
                    "${this.name} ($translator)",
                    m3uLink,
                    referer
                ).forEach(sourceCallback)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        document.select("div.episodeTranslators div#fansec").map {
            Pair(it.select("a").attr("translator"), it.select("div.title").text())
        }.amap { (url, translator) ->
            safeApiCall {
                app.get(
                    url,
                    referer = data,
                    headers = mapOf(
                        "Accept" to "application/json, text/javascript, */*; q=0.01",
                        "X-Requested-With" to "XMLHttpRequest"
                    )
                ).parsedSafe<Translators>()?.data?.let {
                    Jsoup.parse(it).select("a").amap { video ->
                        app.get(
                            video.attr("video"),
                            referer = data,
                            headers = mapOf(
                                "Accept" to "application/json, text/javascript, */*; q=0.01",
                                "X-Requested-With" to "XMLHttpRequest"
                            )
                        ).parsedSafe<Videos>()?.player?.let { iframe ->
                            Jsoup.parse(iframe).select("iframe").attr("src").let { link ->
                                when {
                                    link.startsWith(mainServer) -> {
                                        invokeLokalSource(link, translator, callback)
                                    }
                                    else -> {
                                        loadExtractor(
                                            fixUrl(link),
                                            "$mainUrl/",
                                            subtitleCallback,
                                            callback
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return true
    }

    data class Source(
        @JsonProperty("videoSource") val videoSource: String?,
    )

    data class Videos(
        @JsonProperty("player") val player: String?,
    )

    data class Translators(
        @JsonProperty("data") val data: String?,
    )

}