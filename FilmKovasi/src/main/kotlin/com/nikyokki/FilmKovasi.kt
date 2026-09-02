package com.nikyokki

// Trigger CloudStream build after artifact publishing auth fix.

import android.util.Base64
import android.util.Log
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
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import java.net.URI

class FilmKovasi : MainAPI() {
    override var mainUrl = "https://filmkovasi.co"
    override var name = "FilmKovası"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "${mainUrl}/filmizle/aile/" to "Aile",
        "${mainUrl}/filmizle/aksiyon/" to "Aksiyon",
        "${mainUrl}/filmizle/animasyon/" to "Animasyon",
        "${mainUrl}/filmizle/belgesel/" to "Belgesel",
        "${mainUrl}/filmizle/bilim-kurgu/" to "Bilim Kurgu",
        "${mainUrl}/filmizle/dram/" to "Dram",
        "${mainUrl}/filmizle/fantastik/" to "Fantastik",
        "${mainUrl}/filmizle/gerilim/" to "Gerilim",
        "${mainUrl}/filmizle/gizem/" to "Gizem",
        "${mainUrl}/filmizle/komedi/" to "Komedi",
        "${mainUrl}/filmizle/korku/" to "Korku",
        "${mainUrl}/filmizle/macera/" to "Macera",
        "${mainUrl}/filmizle/romantik/" to "Romantik",
        "${mainUrl}/filmizle/savas/" to "Savaş",
        "${mainUrl}/filmizle/suc/" to "Suç",
        "${mainUrl}/filmizle/tarih/" to "Tarih",
        "${mainUrl}/filmizle/vahsi-bati/" to "Vahşi Batı"
    )

    private fun browserHeaders() = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = if (page <= 1) request.data else "${request.data}page/$page/"
        val document = app.get(pageUrl, headers = browserHeaders()).document
        return newHomePageResponse(
            request.name,
            document.select("div.movie-box").mapNotNull { it.toMainPageResult() }.distinctBy { it.url }
        )
    }

    private fun Element.posterUrl(): String? {
        val image = if (tagName() == "img") this else selectFirst("img") ?: return null
        for (attr in listOf("data-src", "data-lazy-src", "data-original", "data-image", "src")) {
            val value = image.attr(attr).trim()
            if (value.isNotBlank() && !value.startsWith("data:image")) return fixUrlNull(value)
        }
        return image.attr("srcset")
            .substringBefore(',')
            .trim()
            .split(" ")
            .firstOrNull()
            ?.let { fixUrlNull(it) }
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val link = selectFirst("div.film-ismi a[href]") ?: return null
        val href = fixUrlNull(link.attr("href")) ?: return null
        val title = link.text().replace(Regex("\\s+"), " ")
            .replace(Regex("(?i)\\s+izle$"), "")
            .trim()
        if (title.length < 2) return null
        val poster = selectFirst("div.poster img")?.posterUrl() ?: selectFirst("img")?.posterUrl()
        return newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }
    }

    override suspend fun search(query: String): List<SearchResponse> =
        app.get("${mainUrl}/?s=${query}", headers = browserHeaders())
            .document.select("div.movie-box")
            .mapNotNull { it.toMainPageResult() }
            .distinctBy { it.url }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        debugFilmKovasi("LOAD_URL", url)
        val document = app.get(url, headers = browserHeaders()).document
        val title = document.selectFirst("h1.title-border, h1, .title-border")?.text()
            ?.replace(Regex("(?i)\\s+izle$"), "")
            ?.trim() ?: return null
        val poster = document.selectFirst("meta[property='og:image']")?.attr("content")?.let { fixUrlNull(it) }
            ?: document.selectFirst("div.film-afis img, .film-afis img, .poster img, .film-poster img")?.posterUrl()
        val description = document.selectFirst("div#film-aciklama, #film-aciklama, .film-aciklama")?.text()?.trim()
        var year = document.selectFirst("div.release a")?.text()?.trim()?.toIntOrNull()
        val tags = document.select("div#listelements a, #listelements a").map { it.text() }
        val rating = document.selectFirst("div.imdb")?.text()
            ?.replace("IMDb Puanı:", "")
            ?.split("/")
            ?.firstOrNull()
            ?.trim()
        var actors = document.select("div.actor a").map { it.text() }
        val trailer = document.selectFirst("div.film-afis iframe")?.let {
            fixUrlNull(it.attr("src").ifBlank { it.attr("data-src") })
        }
        document.select("div.list-item").forEach { item ->
            val first = item.selectFirst("a")
            if (first?.attr("href")?.contains("/yil/") == true) year = first.text().toIntOrNull()
            if (first?.attr("href")?.contains("/oyuncu/") == true) actors = item.select("a").map { it.text() }
        }
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            posterUrl = poster
            plot = description
            this.year = year
            this.tags = tags
            score = Score.from10(rating)
            addActors(actors)
            addTrailer(trailer)
        }
    }

    private fun debugFilmKovasi(tag: String, value: String) {
        Log.d("FilmKovasiDebug", "$tag = $value")
    }

    private fun normalize(raw: String?, base: String): String? {
        val value = raw?.trim()
            ?.replace("\\/", "/")
            ?.replace("\\u0026", "&")
            ?.replace("&amp;", "&")
            ?.trim('"', '\'', '(', ')', '[', ']', '{', '}', ';', ',')
            ?.takeIf { it.isNotBlank() } ?: return null
        if (value.equals("about:blank", true)) return null
        return runCatching {
            when {
                value.startsWith("http://", true) || value.startsWith("https://", true) -> value
                value.startsWith("//") -> "https:$value"
                else -> URI(base).resolve(value).toString()
            }
        }.getOrNull()?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
    }

    private suspend fun emitM3u8(
        nameSuffix: String,
        masterUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val links = runCatching {
            generateM3u8(
                "FilmKovası$nameSuffix",
                masterUrl,
                referer,
                headers = mapOf(
                    "Referer" to referer,
                    "Origin" to URI(referer).let { "${it.scheme}://${it.host}" }
                )
            )
        }.getOrDefault(emptyList())
        if (links.isEmpty()) {
            callback(
                newExtractorLink(
                    "FilmKovası",
                    "FilmKovası$nameSuffix",
                    masterUrl,
                    ExtractorLinkType.M3U8
                ) {
                    this.referer = referer
                    this.headers = mapOf(
                        "Referer" to referer,
                        "Origin" to URI(referer).let { "${it.scheme}://${it.host}" }
                    )
                }
            )
        } else {
            links.forEach(callback)
        }
        return true
    }

    private suspend fun resolveCloudOrchestra(
        playerUrl: String,
        referer: String,
        suffix: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        debugFilmKovasi("CLOUD_ORCHESTRA", playerUrl)
        val baseOrigin = runCatching {
            URI(playerUrl).let { "${it.scheme}://${it.host}" }
        }.getOrNull() ?: return false

        val player = runCatching {
            app.get(
                playerUrl,
                referer = referer,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                )
            )
        }.getOrNull() ?: return false
        val html = player.text
        debugFilmKovasi("CLOUD_HTML", "${html.length} bytes")

        fun absoluteFrom(raw: String, base: String = playerUrl): String? = normalize(raw, base)

        var rcpUrl = Regex(
            """(?:src|url)\\s*[:=]\\s*[\\\"']((?:https?:)?//[^\\\"']+/rcp/[^\\\"']+|/rcp/[^\\\"']+)""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.getOrNull(1)?.let { absoluteFrom(it) }

        if (rcpUrl == null) {
            rcpUrl = Regex(
                """((?:https?:)?//[^\\\"'\\s<>]+/rcp/[^\\\"'\\s<>]+)""",
                RegexOption.IGNORE_CASE
            ).find(html)?.groupValues?.getOrNull(1)?.let { absoluteFrom(it) }
        }

        var rcpHostReferer = baseOrigin + "/"
        var prorcpUrl: String? = Regex(
            """src\\s*:\\s*['\"](/prorcp/[^'\"]+)['\"]""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.getOrNull(1)?.let { absoluteFrom(it) }

        if (rcpUrl != null) {
            rcpHostReferer = runCatching {
                URI(rcpUrl).let { "${it.scheme}://${it.host}/" }
            }.getOrDefault(rcpHostReferer)
            val rcpHtml = runCatching {
                app.get(
                    rcpUrl,
                    referer = referer,
                    headers = mapOf("User-Agent" to USER_AGENT, "Accept" to "text/html,*/*")
                ).text
            }.getOrNull() ?: return false
            debugFilmKovasi("RCP_HTML", "${rcpHtml.length} bytes")
            prorcpUrl = Regex(
                """src\\s*:\\s*['\"](/prorcp/[^'\"]+)['\"]""",
                RegexOption.IGNORE_CASE
            ).find(rcpHtml)?.groupValues?.getOrNull(1)?.let { absoluteFrom(it, rcpUrl) }
        }

        if (prorcpUrl == null && playerUrl.contains("/prorcp/", true)) {
            prorcpUrl = playerUrl
        }

        if (prorcpUrl == null) {
            val masterInline = Regex(
                """master_urls\\s*=\\s*['\"]([^'\"]+)['\"]""",
                RegexOption.IGNORE_CASE
            ).find(html)?.groupValues?.getOrNull(1)
            if (masterInline != null) {
                return finishCloudMaster(masterInline, rcpHostReferer, suffix, callback)
            }
            return false
        }

        val prorcpHtml = runCatching {
            app.get(
                prorcpUrl,
                referer = rcpHostReferer,
                headers = mapOf("User-Agent" to USER_AGENT, "Accept" to "text/html,*/*")
            ).text
        }.getOrNull() ?: return false
        debugFilmKovasi("PRORCP_HTML", "${prorcpHtml.length} bytes")

        val master = Regex(
            """master_urls\\s*=\\s*['\"]([^'\"]+)['\"]""",
            RegexOption.IGNORE_CASE
        ).find(prorcpHtml)?.groupValues?.getOrNull(1) ?: return false
        return finishCloudMaster(master, rcpHostReferer, suffix, callback)
    }

    private suspend fun finishCloudMaster(
        rawMaster: String,
        referer: String,
        suffix: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val master = rawMaster
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .split(" or ")
            .map { it.trim() }
            .firstOrNull { it.startsWith("http://", true) || it.startsWith("https://", true) }
            ?: return false

        if (!master.contains("__TOKEN__")) {
            return emitM3u8(suffix, master, referer, callback)
        }

        val cdnHost = runCatching { URI(master).host }.getOrNull() ?: return false
        val token = runCatching {
            app.get(
                "https://$cdnHost/generate.php",
                referer = referer,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "*/*"
                )
            ).text.trim()
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return false

        val finalUrl = master.replace("__TOKEN__", token)
        debugFilmKovasi("FINAL_M3U8", finalUrl)
        return emitM3u8(suffix, finalUrl, "https://$cdnHost/", callback)
    }

    private suspend fun resolveRuntimePlayer(
        playerUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // The browser trace shows the working chain as:
        // FilmKovası -> vsembed/vidsrc -> cloudorchestranova -> scintillatingsycophant -> HLS.
        // CloudOrchestra is not always present in FilmKovası's static HTML, so when
        // a TMDB-backed Vidsrc player is available, follow that browser chain first.
        if (playerUrl.contains("vsembed.ru/embed/movie/", true) ||
            playerUrl.contains("vidsrc.to/embed/movie/", true)) {
            val cloudEmbedRegex = Regex(
                """(?i)^https?://cloudorchestranova\\.com/embed/(?:movie|player)/[^?#]+(?:[?#].*)?$"""
            )
            val bridge = runCatching {
                WebViewResolver(
                    interceptUrl = cloudEmbedRegex,
                    additionalUrls = listOf(cloudEmbedRegex),
                    userAgent = USER_AGENT,
                    useOkhttp = false,
                    timeout = 20_000L
                ).resolveUsingWebView(
                    playerUrl,
                    referer = referer
                )
            }.getOrNull()

            val cloudRequest = bridge?.first ?: bridge?.second?.firstOrNull()
            val cloudUrl = cloudRequest?.url?.toString()
            if (!cloudUrl.isNullOrBlank()) {
                debugFilmKovasi("VIDSRC_CLOUD", cloudUrl)
                if (resolveRuntimePlayer(cloudUrl, playerUrl, subtitleCallback, callback)) {
                    return true
                }
            }
            debugFilmKovasi("VIDSRC_CLOUD", "CloudOrchestra HLS zinciri başarısız")
        }

        if (playerUrl.contains("cloudorchestranova.com", true)) {
            debugFilmKovasi("CLOUD_WEBVIEW", playerUrl)

            // Match the exact CDN endpoints seen in the browser trace.
            // generate.php only returns a token; the playable source is the
            // subsequent master.m3u8 request.
            val providerRegex = Regex(
                """(?i)^https?://scintillatingsycophant\\.space/.+/(?:master|index)\\.m3u8(?:[?#].*)?$"""
            )

            val result = runCatching {
                WebViewResolver(
                    interceptUrl = providerRegex,
                    additionalUrls = listOf(providerRegex),
                    userAgent = USER_AGENT,
                    useOkhttp = false,
                    timeout = 20_000L
                ).resolveUsingWebView(
                    playerUrl,
                    referer = referer
                )
            }.getOrNull()

            val request = result?.first ?: result?.second?.firstOrNull()

            if (request != null) {
                val mediaUrl = request.url.toString()
                debugFilmKovasi("CLOUD_WEBVIEW_INTERCEPT", mediaUrl)

                if (mediaUrl.contains(".m3u8", true)) {
                    val mediaReferer = request.header("Referer") ?: referer
                    val mediaUa = request.header("User-Agent") ?: USER_AGENT
                    callback(
                        newExtractorLink(
                            "FilmKovası",
                            "FilmKovası [CloudOrchestra]",
                            mediaUrl,
                            ExtractorLinkType.M3U8
                        ) {
                            this.referer = mediaReferer
                            this.headers = mapOf(
                                "User-Agent" to mediaUa,
                                "Accept" to "*/*"
                            )
                        }
                    )
                    debugFilmKovasi("CLOUD_WEBVIEW_MEDIA", mediaUrl)
                    return true
                }
            }

            debugFilmKovasi("CLOUD_WEBVIEW", "HLS yakalanamadı, HTML çözümleyici deneniyor")
        }

        if (playerUrl.contains("cloudorchestranova.com", true)) {
            if (resolveCloudOrchestra(playerUrl, referer, " [CloudOrchestra]", callback)) {
                return true
            }
        }

        debugFilmKovasi("WEBVIEW_PLAYER", playerUrl)

        val mediaRegex = Regex("""(?i)https?://[^\s"'<>]+\.m3u8(?:[?#].*)?$""")

        val result = runCatching {
            WebViewResolver(
                interceptUrl = mediaRegex,
                additionalUrls = listOf(mediaRegex),
                userAgent = null,
                useOkhttp = false,
                timeout = 45_000L
            ).resolveUsingWebView(
                playerUrl,
                referer = referer
            )
        }.getOrNull() ?: return false

        val request = result.first ?: result.second.firstOrNull() ?: return false
        val mediaUrl = request.url.toString()

        if (!mediaRegex.matches(mediaUrl)) return false

        val mediaReferer = request.header("Referer") ?: referer
        val mediaUa = request.header("User-Agent") ?: USER_AGENT

        callback(
            newExtractorLink(
                "FilmKovası",
                "FilmKovası",
                mediaUrl,
                ExtractorLinkType.M3U8
            ) {
                this.referer = mediaReferer
                this.headers = mapOf("User-Agent" to mediaUa)
            }
        )

        debugFilmKovasi("WEBVIEW_MEDIA", mediaUrl)
        return true
    }

    private suspend fun resolveDataApi(
        sourceUrl: String,
        apiPath: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val apiUrl = normalize(apiPath, sourceUrl) ?: return false
        debugFilmKovasi("DATA_API", apiUrl)
        val response = runCatching {
            app.get(
                apiUrl,
                referer = sourceUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "application/json,text/plain,*/*"
                )
            )
        }.getOrNull() ?: return false
        debugFilmKovasi("DATA_API_STATUS", response.code.toString())
        val src = Regex("""\"src\"\\s*:\\s*\"([^\"]+)\"""")
            .find(response.text)
            ?.groupValues
            ?.getOrNull(1)
            ?.replace("\\/", "/")
            ?.replace("\\u0026", "&")
            ?: return false
        val player = normalize(src, apiUrl) ?: return false
        debugFilmKovasi("DATA_API_SRC", player)
        return resolveRuntimePlayer(player, apiUrl, subtitleCallback, callback)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        debugFilmKovasi("LOADLINKS_DATA", data)
        val firstDocument = runCatching {
            app.get(data, referer = mainUrl + "/", headers = browserHeaders()).document
        }.getOrNull() ?: return false
        debugFilmKovasi("FIRST_HTML", firstDocument.html().take(12000))
        debugFilmKovasi("FIRST_MEDIA", firstDocument.select("iframe,embed,video,source").joinToString(" || ") { it.outerHtml() }.take(12000))
        debugFilmKovasi("FIRST_DATA", firstDocument.select("[data-url],[data-src],[data-api],[data-embed],[data-player],[data-video]").joinToString(" || ") { it.outerHtml() }.take(12000))
        val sourcePages = firstDocument.select("a[href]").mapNotNull { element ->
            val href = normalize(element.attr("href"), data) ?: return@mapNotNull null
            if (!href.startsWith(mainUrl, true) || href == data) return@mapNotNull null
            val number = Regex("/(\\d+)/?$").find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: return@mapNotNull null
            if (number < 2) return@mapNotNull null
            href
        }.distinct()
        debugFilmKovasi("SOURCE_PAGES", sourcePages.joinToString(" || "))
        var found = false
        val playerUrls = linkedMapOf<String, String>()
        for (sourceUrl in (listOf(data) + sourcePages).distinct()) {
            val document = runCatching {
                app.get(
                    sourceUrl,
                    referer = if (sourceUrl == data) mainUrl + "/" else data,
                    headers = browserHeaders()
                ).document
            }.getOrNull() ?: continue

            debugFilmKovasi("SOURCE_HTML", sourceUrl + " :: " + document.html().take(12000))
            debugFilmKovasi("SOURCE_MEDIA", sourceUrl + " :: " + document.select("iframe,embed,video,source").joinToString(" || ") { it.outerHtml() }.take(12000))

            val dataApis = document.select("iframe[data-api]")
                .map { it.attr("data-api") }
                .filter { it.isNotBlank() }
                .distinct()
            debugFilmKovasi("DATA_APIS", sourceUrl + " :: " + dataApis.joinToString(" || "))
            for (apiPath in dataApis) {
                if (resolveDataApi(sourceUrl, apiPath, subtitleCallback, callback)) found = true
            }

            document.select(
                "iframe[src],iframe[data-src],iframe[data-url],iframe[data-embed],iframe[data-player]," +
                    "embed[src],object[data],video[src],video[data-src],video source[src],video source[data-src]," +
                    "[data-url],[data-embed],[data-player],[data-video]"
            ).forEach { element ->
                listOf(
                    element.attr("src"),
                    element.attr("data-src"),
                    element.attr("data-url"),
                    element.attr("data-embed"),
                    element.attr("data-player"),
                    element.attr("data-video"),
                    element.attr("data")
                ).forEach { raw ->
                    val candidate = normalize(raw, sourceUrl) ?: return@forEach
                    val uri = runCatching { java.net.URI(candidate) }.getOrNull()
                        ?: return@forEach

                    // Only accept complete absolute web URLs. This prevents
                    // fragments such as "https://movie", "https://vid" or
                    // "https://player." from becoming WebView targets.
                    if (!uri.scheme.equals("http", true) && !uri.scheme.equals("https", true)) return@forEach
                    val host = uri.host?.trim()?.lowercase() ?: return@forEach
                    if (host.isBlank() || !host.contains(".")) return@forEach
                    if (candidate == data || candidate.startsWith(mainUrl, true)) return@forEach

                    if (candidate.contains("youtube.com", true) ||
                        candidate.contains("youtu.be", true) ||
                        candidate.contains("youtube-nocookie.com", true) ||
                        candidate.contains("googlevideo.com", true) ||
                        candidate.contains("google.com", true) ||
                        candidate.contains("doubleclick", true)) return@forEach

                    playerUrls.putIfAbsent(candidate, sourceUrl)
                }
            }

            // The site uses lazy-loading: the visible iframe is often about:blank,
            // while the real provider URL is present in data-litespeed-src or inline HTML.
            // Read those attributes and only accept known provider hosts.
            document.select("[data-litespeed-src],[data-fku],[data-src],[data-url],[data-embed],[data-player]").forEach { element ->
                listOf(
                    element.attr("data-litespeed-src"),
                    element.attr("data-src"),
                    element.attr("data-url"),
                    element.attr("data-embed"),
                    element.attr("data-player")
                ).forEach { raw ->
                    val candidate = normalize(raw, sourceUrl) ?: return@forEach
                    val host = runCatching { URI(candidate).host?.lowercase() }.getOrNull() ?: return@forEach
                    val known = host == "multiembed.mov" ||
                        host == "player.autoembed.cc" ||
                        host == "www.2embed.cc" ||
                        host == "2embed.cc" ||
                        host == "filmizle1.com" ||
                        host == "vsembed.ru" ||
                        host == "vidsrc.to" ||
                        host == "cloudorchestranova.com" ||
                        host == "scintillatingsycophant.space"
                    if (known && !candidate.contains("youtube", true)) {
                        playerUrls.putIfAbsent(candidate, sourceUrl)
                    }
                }
            }

            // Also recover provider URLs embedded directly in the page source.
            val rawHtml = document.html()
            Regex(
                """https?://(?:www\\.)?(?:multiembed\\.mov|player\\.autoembed\\.cc|(?:www\\.)?2embed\\.cc|filmizle1\\.com|vsembed\\.ru|vidsrc\\.to|cloudorchestranova\\.com)(?:/[^"'<>\\s]*)?""",
                RegexOption.IGNORE_CASE
            ).findAll(rawHtml).forEach { match ->
                val candidate = normalize(match.value, sourceUrl) ?: return@forEach
                playerUrls.putIfAbsent(candidate, sourceUrl)
            }

            document.select("script").forEach { script ->
                val text = script.data().ifBlank { script.html() }
                Regex("""atob\(\s*[\"']([^\"']+)[\"']\s*\)""", RegexOption.IGNORE_CASE)
                    .findAll(text)
                    .forEach { match ->
                        runCatching {
                            Base64.decode(match.groupValues[1], Base64.DEFAULT).toString(Charsets.UTF_8)
                        }.getOrNull()?.let { decoded ->
                            val cleanDecoded = decoded
                                .replace("\\/", "/")
                                .replace("\\u0026", "&")
                                .replace("\\\"", "\"")

                            Regex("""https?://[^"'\\s<>]+""", RegexOption.IGNORE_CASE)
                                .findAll(cleanDecoded)
                                .forEach { matchUrl ->
                                    val candidate = normalize(matchUrl.value, sourceUrl) ?: return@forEach
                                    if (candidate.contains("youtube.com", true) ||
                                        candidate.contains("youtu.be", true) ||
                                        candidate.contains("youtube-nocookie.com", true) ||
                                        candidate.contains("googlevideo.com", true) ||
                                        candidate.contains("google.com", true) ||
                                        candidate.contains("doubleclick", true)) return@forEach
                                    if (!candidate.startsWith(mainUrl, true)) playerUrls.putIfAbsent(candidate, sourceUrl)
                                }
                        }
                    }
            }
        }

        // Some FilmKovası pages expose only a provider ID (e.g. video_id=...)
        // while the working browser path creates a VSEmbed/VidSrc iframe dynamically.
        // Reconstruct those bridge URLs so the same chain can be followed in WebView.
        val discoveredTmdbIds = playerUrls.keys.flatMap { url ->
            listOfNotNull(
                Regex("""(?i)[?&]video_id=(\d+)""").find(url)?.groupValues?.getOrNull(1),
                Regex("""(?i)/embed/movie/(\d+)""").find(url)?.groupValues?.getOrNull(1)
            )
        }.distinct()
        discoveredTmdbIds.forEach { id ->
            playerUrls.putIfAbsent("https://vsembed.ru/embed/movie/$id/", data)
            playerUrls.putIfAbsent("https://vidsrc.to/embed/movie/$id", data)
        }
        debugFilmKovasi("TMDB_BRIDGES", discoveredTmdbIds.joinToString(" || "))

        // Try every discovered provider, but never use YouTube/trailer URLs.
        // CloudOrchestra/Vidsrc is tried first because it is the verified movie
        // path; if it fails, continue through the remaining providers.
        val orderedPlayers = playerUrls.entries
            .filterNot {
                it.key.contains("youtube.com", true) ||
                    it.key.contains("youtu.be", true) ||
                    it.key.contains("youtube-nocookie.com", true) ||
                    it.key.contains("googlevideo.com", true) ||
                    it.key.contains("google.com", true) ||
                    it.key.contains("doubleclick", true)
            }
            .sortedWith(
                compareByDescending<Map.Entry<String, String>> {
                    it.key.contains("cloudorchestranova.com", true) ||
                        it.key.contains("vidsrc", true)
                }.thenBy { it.key }
            )
        // Drop malformed fragments such as "movie" before opening WebView.
        // Keep every valid provider so a failed one can fall through to the next.
        val validPlayers = orderedPlayers.filter { entry ->
            val url = entry.key.trim()
            runCatching {
                val uri = java.net.URI(url)
                (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) &&
                    !uri.host.isNullOrBlank() &&
                    !uri.host.equals("movie", true) &&
                    !uri.host.equals("localhost", true) &&
                    !uri.host.equals("127.0.0.1", true)
            }.getOrDefault(false)
        }
        debugFilmKovasi("PLAYER_URLS", validPlayers.joinToString(" || ") { it.key + " <- " + it.value })
        for ((playerUrl, referer) in validPlayers) {
            debugFilmKovasi("PLAYER_TRY", playerUrl + " REF=" + referer)

            // Final HLS URLs should be emitted directly; opening an already
            // resolved .m3u8 in WebView can stall Android Chromium.
            if (Regex("""(?i)\.m3u8(?:[?#].*)?$""").matches(playerUrl)) {
                val direct = emitM3u8(" [Direct HLS]", playerUrl, referer, callback)
                debugFilmKovasi("DIRECT_HLS", playerUrl + " :: " + direct)
                if (direct) found = true
                continue
            }

            if (resolveRuntimePlayer(playerUrl, referer, subtitleCallback, callback)) {
                found = true
                continue
            }
            val extracted = runCatching {
                loadExtractor(
                    playerUrl,
                    referer = referer,
                    subtitleCallback = subtitleCallback
                ) { link -> callback(link) }
            }.getOrDefault(false)
            debugFilmKovasi("EXTRACTOR_RESULT", playerUrl + " :: " + extracted)
            if (extracted) found = true
        }
        debugFilmKovasi("LOADLINKS_RESULT", found.toString())
        return found
    }
}
