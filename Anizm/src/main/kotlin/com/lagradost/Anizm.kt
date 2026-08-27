package com.lagradost

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI

class Anizm : MainAPI() {
    override var mainUrl = "https://anizm.net"
    override var name = "Anizm"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    companion object {
        private const val mainServer = "https://anizmplayer.com"
    }

    override val mainPage = mainPageOf(
        "$mainUrl/anime-izle?sayfa=" to "Son Eklenen Animeler",
        "$mainUrl/kategoriler/4" to "Dram",
        "$mainUrl/kategoriler/2" to "Aksiyon",
        "$mainUrl/kategoriler/8" to "Bilim-Kurgu",
        "$mainUrl/kategoriler/20" to "Korku",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (request.data.contains("/kategoriler/")) {
            if (page <= 1) request.data else "${request.data}?page=$page"
        } else request.data + page

        val document = app.get(url).document

        val home = if (request.data.contains("/kategoriler/")) {
            val heading = document.select("h1, h2, h3, h4")
                .firstOrNull { it.text().contains("Kategorisindeki Animeler", ignoreCase = true) }

            val categoryContainer = generateSequence(heading?.parent()) { it.parent() }
                .take(8)
                .firstOrNull { container ->
                    container.select("a[href]").count { anchor ->
                        val href = fixUrl(anchor.attr("href").trim())
                        val text = anchor.text().trim()
                        href.startsWith(mainUrl) &&
                            !href.contains("/kategoriler/") &&
                            !href.contains("/anime-izle") &&
                            text.length > 2 &&
                            !text.equals("İzle", ignoreCase = true)
                    } >= 5
                }

            categoryContainer?.select("a[href]")?.mapNotNull { it.toSearchResult() }
                ?.distinctBy { it.url }.orEmpty()
        } else {
            document.select("div#episodesMiddle div.posterBlock > a")
                .mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        }

        val hasNext = document.selectFirst(
            "div.nextBeforeButtons > div.ui > a.right:not(.disabled), " +
                "div.nextBeforeButtons a.right:not(.disabled), " +
                "a[rel=next]:not(.disabled)"
        ) != null

        return newHomePageResponse(request.name, home, hasNext = hasNext)
    }

    private fun getProperAnimeLink(uri: String): String =
        if (uri.contains("-bolum")) {
            "$mainUrl/${uri.substringAfter("$mainUrl/").replace(Regex("-[0-9]+-bolum.*"), "")}"
        } else uri

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val link = if (tagName() == "a") this else selectFirst("a") ?: return null
        val rawHref = link.attr("href").trim()
        if (rawHref.isBlank()) return null

        val href = getProperAnimeLink(fixUrl(rawHref))
        if (!href.startsWith(mainUrl) || href.contains("/kategoriler/") || href.contains("/anime-izle")) return null

        var card: Element = this
        if (tagName() == "a") {
            var parent = parent()
            var foundCard = false
            repeat(4) {
                if (parent == null) return@repeat
                if (parent.selectFirst("img") != null) {
                    card = parent
                    foundCard = true
                    return@repeat
                }
                parent = parent.parent()
            }
            if (!foundCard) return null
        }

        val title = card.selectFirst("div.title, h5.animeTitle a, .title, h5, h4, h3")?.text()?.trim()
            ?: card.selectFirst("img")?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
            ?: link.attr("title").trim().takeIf { it.isNotBlank() }
            ?: link.text().trim().takeIf { it.isNotBlank() && !it.equals("İzle", ignoreCase = true) }
            ?: return null

        val posterUrl = fixUrlNull(card.selectFirst("img")?.let { image ->
            image.attr("data-src").ifBlank {
                image.attr("data-original").ifBlank {
                    image.attr("data-lazy-src").ifBlank { image.attr("src") }
                }
            }
        })

        val episodeText = card.selectFirst("div.truncateText, div.episodeBlock")?.text() ?: link.text()
        val episode = Regex("""([0-9]+).?\s?Bölüm""", RegexOption.IGNORE_CASE)
            .find(episodeText)?.groupValues?.getOrNull(1)?.toIntOrNull()

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
            .mapNotNull { it.toSearchResult() }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst(
            "h1, h2.anizm_pageTitle a, h2.anizm_pageTitle, .animeTitle, .title"
        )?.text()?.trim().takeIf { !it.isNullOrBlank() }
            ?: url.substringAfterLast("/").replace("-", " ").trim()

        val poster = fixUrlNull(document.selectFirst("div.infoPosterImg > img")?.let { img ->
            img.attr("src").ifBlank {
                img.attr("data-src").ifBlank {
                    img.attr("data-original").ifBlank { img.attr("data-lazy-src") }
                }
            }
        })

        val episodes = document.select("a[href]").mapNotNull { a ->
            val name = a.text().trim()
            val href = a.attr("href").trim()
            if (href.isBlank() || !href.contains("-bolum", ignoreCase = true)) return@mapNotNull null
            if (!name.contains("Bölüm", ignoreCase = true)) return@mapNotNull null
            Pair(fixUrl(href), name)
        }.distinctBy { it.first }.map { (href, name) ->
            newEpisode(href) { this.name = name }
        }

        val type = if (episodes.isEmpty()) TvType.Movie else TvType.Anime
        val trailer = document.selectFirst("iframe")?.attr("src")

        return newAnimeLoadResponse(title, url, type) {
            posterUrl = poster
            addEpisodes(DubStatus.Subbed, episodes)
            plot = document.selectFirst("div.infoDesc, .infoDesc, .description")?.text()?.trim()
            tags = document.select("span.dataValue span.ui.label, .ui.label").map { it.text() }
            addTrailer(trailer)
        }
    }

    private fun absoluteUrl(value: String, base: String): String? {
        val v = value.trim()
        if (v.isBlank()) return null
        return try { URI(base).resolve(v).toString() } catch (_: Throwable) { null }
    }

    private fun iframeSources(html: String, base: String): List<String> =
        Jsoup.parse(html).select("iframe[src], iframe[data-src], video source[src]")
            .mapNotNull { element ->
                val value = element.attr("src").ifBlank { element.attr("data-src") }
                absoluteUrl(value, base)
            }.distinct()

    private suspend fun invokeLokalSource(
        url: String,
        translator: String,
        sourceCallback: (ExtractorLink) -> Unit
    ) {
        val hash = url.substringAfterLast("/video/").substringBefore("/").takeIf { it.isNotBlank() }
            ?: return

        val referer = if (url.contains("/video/")) url else "$mainServer/video/$hash"
        val apiUrl = "$mainServer/player/index.php?data=$hash&do=getVideo"

        app.post(
            apiUrl,
            data = mapOf("hash" to hash, "r" to "$mainUrl/"),
            referer = referer,
            headers = mapOf(
                "Accept" to "*/*",
                "Origin" to mainServer,
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "X-Requested-With" to "XMLHttpRequest"
            )
        ).parsedSafe<Source>()?.securedLink?.takeIf { it.isNotBlank() }?.let { m3uLink ->
            M3u8Helper.generateM3u8(
                "${this.name} ($translator)",
                m3uLink,
                referer
            ).forEach(sourceCallback)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val translatorItems = document.select("div.episodeTranslators div#fansec")

        translatorItems.forEach { item ->
            val translatorUrl = absoluteUrl(item.select("a").attr("translator"), data) ?: return@forEach
            val translator = item.select("div.title").text().trim()

            safeApiCall {
                app.get(
                    translatorUrl,
                    referer = data,
                    headers = mapOf(
                        "Accept" to "application/json, text/javascript, */*; q=0.01",
                        "X-Requested-With" to "XMLHttpRequest"
                    )
                ).parsedSafe<Translators>()?.data?.let { translatorData ->
                    Jsoup.parse(translatorData)
                        .select("a.videoPlayerButtons, a[video]")
                        .forEach { video ->
                            val rawVideoUrl = video.attr("video").trim()
                            if (rawVideoUrl.isBlank()) return@forEach

                            val playerUrl = absoluteUrl(
                                rawVideoUrl.replace("/video/", "/player/"),
                                translatorUrl
                            ) ?: return@forEach

                            val redirectedUrl = runCatching {
                                app.get(
                                    playerUrl,
                                    referer = data,
                                    allowRedirects = false,
                                    headers = mapOf(
                                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                                        "X-Requested-With" to "XMLHttpRequest"
                                    )
                                ).headers["Location"]
                            }.getOrNull() ?: return@forEach

                            when {
                                redirectedUrl.contains("anizmplayer.com/video/") -> {
                                    invokeLokalSource(redirectedUrl, translator, callback)
                                }

                                redirectedUrl.isNotBlank() -> {
                                    loadExtractor(
                                        redirectedUrl,
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

        return true
    }

    data class Source(
        @JsonProperty("securedLink") val securedLink: String?,
        @JsonProperty("videoSource") val videoSource: String?
    )

    data class Videos(@JsonProperty("player") val player: String?)
    data class Translators(@JsonProperty("data") val data: String?)
}
