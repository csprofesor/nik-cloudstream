@file:Suppress("DEPRECATION")

package com.lagradost

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
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

    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    companion object { private const val mainServer = "https://anizmplayer.com" }

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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else request.data + "?page=" + page
        val document = app.get(url).document
        val home = document.select("a[href]")
            .filter { link ->
                val href = link.attr("href")
                href.isNotBlank() &&
                    href != mainUrl &&
                    href != "$mainUrl/" &&
                    !href.contains("/kategoriler/") &&
                    !href.contains("/anime-izle") &&
                    !href.contains("/fullViewSearch") &&
                    !href.contains("/takvim") &&
                    !href.contains("/giris") &&
                    !href.contains("/kayit") &&
                    !href.contains("/favori") &&
                    !href.contains("/liste")
            }
            .mapNotNull { it.toSearchResult() }
            .filter { it.url.startsWith(mainUrl) }
            .filterNot { it.name.equals("Logo", ignoreCase = true) }
            .distinctBy { it.url }
        val hasNext = document.selectFirst(
            "div.nextBeforeButtons > div.ui > a.right:not(.disabled), " +
                "div.nextBeforeButtons a.right:not(.disabled), " +
                "a[rel=next]:not(.disabled)"
        ) != null

        return newHomePageResponse(request.name, home, hasNext = hasNext)
    }

    private fun getProperAnimeLink(uri: String): String = if (uri.contains("-bolum")) {
        "$mainUrl/${uri.substringAfter("$mainUrl/").replace(Regex("-[0-9]+-bolum.*"), "")}"
    } else uri

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val link = if (tagName() == "a") this else selectFirst("a") ?: return null
        val href = getProperAnimeLink(link.attr("href"))
        if (href.isBlank()) return null

        var card: Element = this
        if (tagName() == "a") {
            var parent = parent()
            repeat(5) {
                if (parent == null) return@repeat
                if (parent.selectFirst("img") != null) {
                    card = parent
                    return@repeat
                }
                parent = parent.parent()
            }
        }

        val title = card.selectFirst("div.title, h5.animeTitle a, .title, h5, h4, h3")?.text()?.trim()
            ?: card.selectFirst("img")?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
            ?: link.attr("title").trim().takeIf { it.isNotBlank() }
            ?: link.text().trim().takeIf { it.isNotBlank() }
            ?: return null

        val posterUrl = fixUrlNull(
            card.selectFirst("img")?.let { image ->
                image.attr("data-src").ifBlank {
                    image.attr("data-original").ifBlank {
                        image.attr("data-lazy-src").ifBlank { image.attr("src") }
                    }
                }
            }
        )

        val episodeText = selectFirst("div.truncateText, div.episodeBlock")?.text() ?: link.text()
        val episode = Regex("""([0-9]+)\.?\s?Bölüm""", RegexOption.IGNORE_CASE)
            .find(episodeText)?.groupValues?.getOrNull(1)?.toIntOrNull()

        if (title.equals("Logo", ignoreCase = true)) return null

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
        return document.select("div.searchResultItem, div.posterBlock")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    private fun extractImdbId(document: org.jsoup.nodes.Document): String? {
        val candidates = sequenceOf(
            document.selectFirst("a[href*='imdb.com/title/']")?.attr("href"),
            document.selectFirst("[data-imdb-id]")?.attr("data-imdb-id"),
            document.selectFirst("[data-imdb]")?.attr("data-imdb"),
            document.select("span.dataValue").joinToString(" ") { it.text() }
        )

        return candidates.filterNotNull().mapNotNull {
            Regex("""tt[0-9]{7,9}""").find(it)?.value
        }.firstOrNull()
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h2.anizm_pageTitle a, h2.anizm_pageTitle, h1.anizm_pageTitle, h1")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return newAnimeLoadResponse("Anime", url, TvType.Anime)

        val episodeElements = document.select(
            "div.episodeListTabContent div > a, div.ui.grid div.four.wide"
        )

        val episodes = episodeElements.mapNotNull { element ->
            val link = if (element.tagName() == "a") element else element.selectFirst("a")
            val href = link?.attr("href")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val episodeName = element.selectFirst("div.episodeBlock")?.text()?.trim()
                ?: element.text().trim().takeIf { it.isNotBlank() }
                ?: "Bölüm"

            newEpisode(fixUrl(href)) {
                name = episodeName
            }
        }.distinctBy { it.data }

        val type = if (episodes.size == 1) TvType.Movie else TvType.Anime
        val trailer = document.selectFirst(
            "div.yt-hd-thumbnail-inner-container iframe, iframe[src*='youtube.com'], iframe[src*='youtu.be']"
        )?.attr("src")

        val year = Regex("""\b(19|20)\d{2}\b""").find(
            document.select("div.infoSta li, div.anizm_boxContent li.dataRow")
                .joinToString(" ") { it.text() }
        )?.value?.toIntOrNull()

        val imdbId = extractImdbId(document)

        return newAnimeLoadResponse(title, url, type) {
            posterUrl = fixUrlNull(document.selectFirst("div.infoPosterImg > img, div.infoPosterImg img")?.let {
                it.attr("data-src").ifBlank {
                    it.attr("data-original").ifBlank { it.attr("src") }
                }
            })
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            plot = document.selectFirst("div.infoDesc")?.text()?.trim()
            tags = document.select(
                "span.dataValue > span.tag > span.label, span.dataValue span.ui.label"
            ).map { it.text() }.distinct()
            imdbId?.let { addImdbId(it) }
            trailer?.let { addTrailer(it) }
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

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("Anizm", "loadLinks data=$data")
        val document = app.get(data, referer = "$mainUrl/").document

        val translatorItems = document.select(
            "div.episodeTranslators div#fansec, div.episodeTranslators [translator], a[translator]"
        ).distinctBy { it.selectFirst("a[translator]")?.attr("translator") ?: it.attr("translator") }

        Log.d("Anizm", "translator items=${translatorItems.size}")

        translatorItems.forEach { item ->
            safeApiCall {
                val translatorAnchor = item.selectFirst("a[translator]") ?: item.takeIf { it.hasAttr("translator") }
                val translatorUrl = translatorAnchor?.attr("translator")?.let(::fixUrl).orEmpty()
                    .ifBlank { translatorAnchor?.attr("href")?.let(::fixUrl).orEmpty() }
                val translator = item.selectFirst("div.title")?.text()?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: translatorAnchor?.text()?.trim().orEmpty()

                if (translatorUrl.isBlank()) {
                    Log.d("Anizm", "translator URL missing: $translator")
                    return@safeApiCall
                }

                Log.d("Anizm", "translator=$translator url=$translatorUrl")

                val translatorResponse = app.get(
                    translatorUrl,
                    referer = data,
                    headers = mapOf(
                        "Accept" to "application/json, text/javascript, */*; q=0.01",
                        "X-Requested-With" to "XMLHttpRequest"
                    )
                ).parsedSafe<Translators>()

                val translatorHtml = translatorResponse?.data
                if (translatorHtml.isNullOrBlank()) {
                    Log.d("Anizm", "translator response/data empty")
                    return@safeApiCall
                }

                val videoLinks = Jsoup.parse(translatorHtml).select("a[video]")
                Log.d("Anizm", "video links=${videoLinks.size}")

                videoLinks.forEach { video ->
                    val videoUrl = video.attr("video").let(::fixUrl)
                    if (videoUrl.isBlank()) return@forEach

                    Log.d("Anizm", "video url=$videoUrl")

                    safeApiCall {
                        val player = app.get(
                            videoUrl,
                            referer = data,
                            headers = mapOf(
                                "Accept" to "application/json, text/javascript, */*; q=0.01",
                                "X-Requested-With" to "XMLHttpRequest"
                            )
                        ).parsedSafe<Videos>()?.player

                        if (player.isNullOrBlank()) {
                            Log.d("Anizm", "player response empty")
                            return@safeApiCall
                        }

                        val parsed = Jsoup.parse(player)
                        val iframeElement = parsed.selectFirst("iframe")
                        val link = (
                            iframeElement?.attr("src")
                                ?: iframeElement?.attr("data-src")
                                ?: iframeElement?.attr("data-lazy-src")
                        )?.trim()?.takeIf { it.isNotBlank() }
                            ?: player.trim().takeIf { it.startsWith("http") }

                        if (link.isNullOrBlank()) {
                            Log.d("Anizm", "iframe/player URL missing")
                            return@safeApiCall
                        }

                        val fixedLink = fixUrl(link)
                        Log.d("Anizm", "resolved link=$fixedLink")

                        if (fixedLink.startsWith(mainServer)) {
                            invokeLokalSource(fixedLink, translator, callback)
                        } else {
                            loadExtractor(fixedLink, data, subtitleCallback, callback)
                        }
                    }
                }
            }
        }

        return true
    }
    data class Source(@JsonProperty("videoSource") val videoSource: String?)
    data class Videos(@JsonProperty("player") val player: String?)
    data class Translators(@JsonProperty("data") val data: String?)
}
