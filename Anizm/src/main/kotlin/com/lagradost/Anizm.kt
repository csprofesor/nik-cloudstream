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
    override var mainUrl = "https://anizm.com.tr"
    override var name = "Anizm"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    companion object {
        private const val logTag = "Anizm"
        private const val mainServer = "https://anizmplayer.com"
        private const val browserUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private val browserHeaders = mapOf(
            "User-Agent" to browserUserAgent,
            "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
            "sec-ch-ua" to "\"Chromium\";v=\"124\", \"Google Chrome\";v=\"124\", \"Not-A.Brand\";v=\"99\"",
            "sec-ch-ua-mobile" to "?0",
            "sec-ch-ua-platform" to "\"Windows\""
        )
    }

    override val mainPage = mainPageOf(
        mainUrl to "Son Eklenen Animeler",
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

    private fun normalizeUrl(url: String): String {
        return url.replace("https://anizm.net", mainUrl)
            .replace("http://anizm.net", mainUrl)
            .replace("https://anizm.tv", mainUrl)
            .replace("http://anizm.tv", mainUrl)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val targetUrl = normalizeUrl(if (page <= 1) request.data else "${request.data}?page=$page")
        Log.d(logTag, "getMainPage: targetUrl = $targetUrl")
        val document = app.get(targetUrl, headers = browserHeaders).document
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
                    !href.contains("/liste") &&
                    !href.contains("/uyeol") &&
                    !href.contains("/sasirt-beni") &&
                    !href.contains("/tavsiyeRobotu")
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

        Log.d(logTag, "getMainPage: ${request.name} loaded ${home.size} items")
        return newHomePageResponse(request.name, home, hasNext = hasNext)
    }

    private fun getProperAnimeLink(uri: String): String {
        val normalized = normalizeUrl(uri)
        return if (normalized.contains("-bolum")) {
            val basePart = normalized.substringAfter("$mainUrl/").replace(Regex("-[0-9]+-bolum.*"), "")
            "$mainUrl/$basePart"
        } else normalized
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val link = if (tagName() == "a") this else selectFirst("a") ?: return null
        val href = getProperAnimeLink(fixUrl(link.attr("href")))
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

        val posterElement = card.selectFirst("img") ?: return null

        val posterUrl = fixUrlNull(
            posterElement.let { image ->
                image.attr("data-src").ifBlank {
                    image.attr("data-original").ifBlank {
                        image.attr("data-lazy-src").ifBlank { image.attr("src") }
                    }
                }
            }
        )

        val episodeText = selectFirst("div.truncateText, div.episodeBlock")?.text() ?: link.text()
        val episode = Regex("""([0-9]+).?s?Bölüm""", RegexOption.IGNORE_CASE)
            .find(episodeText)?.groupValues?.getOrNull(1)?.toIntOrNull()

        if (title.equals("Logo", ignoreCase = true)) return null

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addSub(episode)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        Log.d(logTag, "search: query = $query")
        val searchJsonUrl = "$mainUrl/searchAnime?query=$query&page=1"
        try {
            val response = app.get(
                searchJsonUrl,
                headers = browserHeaders + mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to "$mainUrl/"
                )
            ).parsedSafe<SearchAnimeResponse>()

            val searchItems = response?.data
            if (!searchItems.isNullOrEmpty()) {
                val results = searchItems.mapNotNull { item: SearchAnimeItem ->
                    val title = item.title?.trim() ?: return@mapNotNull null
                    val slug = item.slug?.trim() ?: return@mapNotNull null
                    val href = fixUrl("$mainUrl/$slug")
                    val posterUrl = item.poster?.let { fixUrl("$mainUrl/storage/pcovers/$it") }

                    newAnimeSearchResponse(title, href, TvType.Anime) {
                        this.posterUrl = posterUrl
                    }
                }
                if (results.isNotEmpty()) {
                    Log.d(logTag, "search: JSON API returned ${results.size} results")
                    return results.distinctBy { it.url }
                }
            }
        } catch (e: Throwable) {
            Log.e(logTag, "search: JSON API failed: ${e.message}")
        }

        val document = app.get(
            "$mainUrl/fullViewSearch?search=$query&skip=0",
            headers = browserHeaders + mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to "$mainUrl/"
            )
        ).document
        val fallbackResults = document.select("div.searchResultItem, div.posterBlock")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
        Log.d(logTag, "search: Fallback HTML returned ${fallbackResults.size} results")
        return fallbackResults
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
        val normalizedUrl = normalizeUrl(url)
        Log.d(logTag, "load: inputUrl = $url, normalizedUrl = $normalizedUrl")
        val document = app.get(normalizedUrl, headers = browserHeaders).document

        val title = document.selectFirst("h2.anizm_pageTitle a, h2.anizm_pageTitle, h1.anizm_pageTitle, h1")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return newAnimeLoadResponse("Anime", normalizedUrl, TvType.Anime)

        val episodeElements = document.select(
            "div.episodeListTabContent div > a, div.ui.grid div.four.wide a, .info_episodeList a, a[href*='-bolum-izle']"
        )

        val episodes = episodeElements.mapNotNull { element ->
            val link = if (element.tagName() == "a") element else element.selectFirst("a")
            val href = link?.attr("href")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val episodeName = element.selectFirst("div.episodeBlock")?.text()?.trim()
                ?: element.text().trim().takeIf { it.isNotBlank() }
                ?: "Bölüm"

            newEpisode(normalizeUrl(fixUrl(href))) {
                name = episodeName
            }
        }.distinctBy { it.data }

        Log.d(logTag, "load: title = $title, episodesCount = ${episodes.size}")

        val type = if (episodes.size == 1) TvType.Movie else TvType.Anime
        val trailer = document.selectFirst(
            "div.yt-hd-thumbnail-inner-container iframe, iframe[src*='youtube.com'], iframe[src*='youtu.be']"
        )?.attr("src")

        val year = Regex("""\b(19|20)\d{2}\b""").find(
            document.select("div.infoSta li, div.anizm_boxContent li.dataRow")
                .joinToString(" ") { it.text() }
        )?.value?.toIntOrNull()

        val imdbId = extractImdbId(document)

        return newAnimeLoadResponse(title, normalizedUrl, type) {
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
        sourceName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        sourceCallback: (ExtractorLink) -> Unit
    ) {
        val normalizedUrl = normalizeUrl(url)
        Log.d(logTag, "invokeLokalSource: fetching player page = $normalizedUrl ($sourceName - $translator)")
        val playerDocument = app.get(
            normalizedUrl,
            referer = "$mainUrl/",
            headers = browserHeaders + mapOf("Referer" to "$mainUrl/")
        ).document

        val playerHtml = playerDocument.html()

        playerDocument.selectFirst("iframe")?.attr("src")?.takeIf { it.isNotBlank() }?.let { innerIframe ->
            val fixedInner = fixUrl(innerIframe)
            if (!fixedInner.contains("anizmplayer.com") && !fixedInner.contains("anizm.com.tr") && !fixedInner.contains("anizm.net") && !fixedInner.contains("anizm.tv")) {
                Log.d(logTag, "invokeLokalSource: loading external extractor = $fixedInner")
                loadExtractor(fixedInner, normalizedUrl, subtitleCallback, sourceCallback)
                return
            }
        }

        val packedScript = playerDocument.select("script").firstOrNull { script ->
            script.data().contains("eval(function(p,a,c,k,e,d)")
        }

        if (packedScript != null) {
            val unpacked = getAndUnpack(packedScript.data())
            val key = unpacked.substringAfter("FirePlayer(\"").substringBefore("\",")
            if (key.isNotBlank() && !unpacked.startsWith(key)) {
                val referer = "$mainServer/video/$key"
                val link = "$mainServer/player/index.php?data=$key&do=getVideo"
                Log.d(logTag, "invokeLokalSource: resolved FirePlayer key = $key, requesting $link")
                val response = app.post(
                    link,
                    data = mapOf("hash" to key, "r" to "$mainUrl/"),
                    referer = referer,
                    headers = mapOf(
                        "Accept" to "*/*",
                        "Origin" to mainServer,
                        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                        "X-Requested-With" to "XMLHttpRequest",
                        "User-Agent" to browserUserAgent
                    )
                ).parsedSafe<Source>()

                val videoSource = response?.videoSource ?: response?.securedLink
                if (!videoSource.isNullOrBlank()) {
                    Log.d(logTag, "invokeLokalSource: found FirePlayer stream = $videoSource")
                    val displayName = if (sourceName.isNotBlank() && sourceName != translator) {
                        "${this.name} - $sourceName ($translator)"
                    } else {
                        "${this.name} ($translator)"
                    }
                    M3u8Helper.generateM3u8(
                        displayName,
                        videoSource,
                        referer
                    ).forEach(sourceCallback)
                    return
                }
            }
        }

        val directUrlMatch = Regex("""(?:const|var|let)\s+url\s*=\s*[\"']([^\"']+)[\"']""")
            .find(playerHtml)?.groupValues?.getOrNull(1)?.replace("\\/", "/")
        val fileMatch = Regex("""file:\s*[\"']([^\"']+)[\"']""")
            .find(playerHtml)?.groupValues?.getOrNull(1)?.replace("\\/", "/")
        val mediaUrl = directUrlMatch ?: fileMatch

        if (!mediaUrl.isNullOrBlank() && mediaUrl.startsWith("http")) {
            val isMedia = mediaUrl.contains(".m3u8") ||
                mediaUrl.contains(".mp4") ||
                mediaUrl.contains("/storage/uploads/") ||
                mediaUrl.contains("/cdn/hls/")
            val isStaticAsset = mediaUrl.endsWith(".js") ||
                mediaUrl.endsWith(".css") ||
                mediaUrl.endsWith(".png") ||
                mediaUrl.endsWith(".jpg") ||
                mediaUrl.endsWith(".jpeg") ||
                mediaUrl.endsWith(".webp") ||
                mediaUrl.endsWith(".gif") ||
                mediaUrl.endsWith(".svg")

            if (isMedia && !isStaticAsset) {
                Log.d(logTag, "invokeLokalSource: found direct media URL = $mediaUrl")
                val displayName = if (sourceName.isNotBlank() && sourceName != translator) {
                    "${this.name} - $sourceName ($translator)"
                } else {
                    "${this.name} ($translator)"
                }

                if (mediaUrl.contains(".m3u8")) {
                    M3u8Helper.generateM3u8(
                        displayName,
                        mediaUrl,
                        normalizedUrl
                    ).forEach(sourceCallback)
                } else {
                    sourceCallback(
                        newExtractorLink(
                            source = this.name,
                            name = displayName,
                            url = mediaUrl,
                            type = INFER_TYPE
                        ) {
                            this.referer = normalizedUrl
                            this.quality = Qualities.P1080.value
                        }
                    )
                }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val normalizedData = normalizeUrl(data)
        Log.d(logTag, "loadLinks: episodeUrl = $normalizedData")
        val document = app.get(
            normalizedData,
            referer = "$mainUrl/",
            headers = browserHeaders + mapOf("Referer" to "$mainUrl/")
        ).document

        val translatorItems = document.select(
            "div.episodeTranslators div#fansec, div.episodeTranslators [translator], a[translator], [data-translatorclick]"
        ).distinctBy {
            it.selectFirst("a[translator]")?.attr("translator")
                ?: it.attr("translator")
                ?: it.attr("href")
        }

        Log.d(logTag, "loadLinks: found ${translatorItems.size} translators")

        translatorItems.forEach { item ->
            safeApiCall {
                val translatorAnchor = item.selectFirst("a[translator]") ?: item.takeIf { it.hasAttr("translator") }
                val translatorUrlRaw = translatorAnchor?.attr("translator")?.let(::fixUrl).orEmpty()
                    .ifBlank { translatorAnchor?.attr("href")?.let(::fixUrl).orEmpty() }
                val translatorUrl = normalizeUrl(translatorUrlRaw)
                val translator = item.selectFirst("div.title, span")?.text()?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: translatorAnchor?.text()?.trim().orEmpty()

                if (translatorUrl.isBlank() || translatorUrl == "#") {
                    return@safeApiCall
                }

                Log.d(logTag, "loadLinks: translator = $translator, url = $translatorUrl")

                val translatorResponse = app.get(
                    translatorUrl,
                    referer = normalizedData,
                    headers = browserHeaders + mapOf(
                        "Accept" to "application/json, text/javascript, */*; q=0.01",
                        "X-Requested-With" to "XMLHttpRequest",
                        "Referer" to normalizedData,
                        "sec-fetch-dest" to "empty",
                        "sec-fetch-mode" to "cors",
                        "sec-fetch-site" to "same-origin"
                    )
                ).parsedSafe<Translators>()

                val translatorHtml = translatorResponse?.data
                if (translatorHtml.isNullOrBlank()) {
                    return@safeApiCall
                }

                val videoLinks = Jsoup.parse(translatorHtml).select("a[video]")
                Log.d(logTag, "loadLinks: translator $translator has ${videoLinks.size} video buttons")

                videoLinks.forEach { video ->
                    val videoUrlRaw = video.attr("video").let(::fixUrl)
                    if (videoUrlRaw.isBlank()) return@forEach
                    val videoUrl = normalizeUrl(videoUrlRaw)
                    val videoSourceName = video.attr("data-video-name").ifBlank { video.text().trim() }

                    safeApiCall {
                        Log.d(logTag, "loadLinks: requesting video button = $videoUrl ($videoSourceName)")
                        val player = app.get(
                            videoUrl,
                            referer = normalizedData,
                            headers = browserHeaders + mapOf(
                                "Accept" to "application/json, text/javascript, */*; q=0.01",
                                "X-Requested-With" to "XMLHttpRequest",
                                "Referer" to normalizedData,
                                "sec-fetch-dest" to "empty",
                                "sec-fetch-mode" to "cors",
                                "sec-fetch-site" to "same-origin"
                            )
                        ).parsedSafe<Videos>()?.player

                        if (player.isNullOrBlank()) {
                            return@safeApiCall
                        }

                        val parsed = Jsoup.parse(player)
                        val iframeElement = parsed.selectFirst("iframe, embed")
                        val link = (
                            iframeElement?.attr("src")
                                ?: iframeElement?.attr("data-src")
                                ?: iframeElement?.attr("data-lazy-src")
                        )?.trim()?.takeIf { it.isNotBlank() }
                            ?: player.trim().takeIf { it.startsWith("http") }

                        if (link.isNullOrBlank()) {
                            return@safeApiCall
                        }

                        val fixedLink = normalizeUrl(fixUrl(link))
                        Log.d(logTag, "loadLinks: resolved player link = $fixedLink ($videoSourceName)")

                        if (fixedLink.contains("anizmplayer.com") || fixedLink.contains("anizm.com.tr/player/") || fixedLink.contains("anizm.net/player/") || fixedLink.contains("anizm.tv/player/")) {
                            invokeLokalSource(fixedLink, translator, videoSourceName, subtitleCallback, callback)
                        } else {
                            loadExtractor(fixedLink, normalizedData, subtitleCallback, callback)
                        }
                    }
                }
            }
        }

        return true
    }

    data class SearchAnimeResponse(
        @JsonProperty("data") val data: List<SearchAnimeItem>? = null
    )

    data class SearchAnimeItem(
        @JsonProperty("info_title") val title: String? = null,
        @JsonProperty("info_slug") val slug: String? = null,
        @JsonProperty("info_poster") val poster: String? = null,
        @JsonProperty("info_year") val year: String? = null
    )

    data class Source(
        @JsonProperty("videoSource") val videoSource: String? = null,
        @JsonProperty("securedLink") val securedLink: String? = null
    )

    data class Videos(
        @JsonProperty("player") val player: String? = null
    )

    data class Translators(
        @JsonProperty("data") val data: String? = null
    )
}
