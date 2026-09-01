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
        if (playerUrl.contains("cloudorchestranova.com", true)) {
            if (resolveCloudOrchestra(playerUrl, referer, " [CloudOrchestra]", callback)) {
                return true
            }
        }

        debugFilmKovasi("WEBVIEW_PLAYER", playerUrl)
        var captured: okhttp3.Request? = null
        val mediaRegex = Regex("(?i).*(?:\\.m3u8(?:[?#].*)?|\\.mp4(?:[?#].*)?).*$")
        val resolver = WebViewResolver(
            interceptUrl = mediaRegex,
            additionalUrls = listOf(mediaRegex),
            userAgent = USER_AGENT,
            useOkhttp = false,
            timeout = 45_000L
        )
        val result = runCatching {
            resolver.resolveUsingWebView(playerUrl, referer = referer) { request ->
                if (mediaRegex.matches(request.url.toString())) {
                    captured = request
                    true
                } else false
            }
        }.getOrNull() ?: return false
        val request = captured ?: result.first ?: result.second.firstOrNull() ?: return false
        val mediaUrl = request.url.toString()
        if (!mediaRegex.matches(mediaUrl)) return false
        val mediaReferer = request.header("Referer") ?: referer
        val mediaUa = request.header("User-Agent") ?: USER_AGENT
        val type = if (Regex("(?i)\\.m3u8(?:[?#]|$)").containsMatchIn(mediaUrl)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        callback(newExtractorLink("FilmKovası", "FilmKovası", mediaUrl, type) {
            this.referer = mediaReferer
            this.headers = mapOf("User-Agent" to mediaUa)
        })
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
                    if (candidate == data || candidate.startsWith(mainUrl, true)) return@forEach
                    if (candidate.contains("google.com", true) || candidate.contains("doubleclick", true)) return@forEach
                    playerUrls.putIfAbsent(candidate, sourceUrl)
                }
            }

            document.select("script").forEach { script ->
                val text = script.data().ifBlank { script.html() }
                Regex("""atob\(\s*[\"']([^\"']+)[\"']\s*\)""", RegexOption.IGNORE_CASE)
                    .findAll(text)
                    .forEach { match ->
                        runCatching {
                            Base64.decode(match.groupValues[1], Base64.DEFAULT).toString(Charsets.UTF_8)
                        }.getOrNull()?.let { decoded ->
                            Regex("https?://[^\"'\\s<>]+", RegexOption.IGNORE_CASE)
                                .findAll(decoded)
                                .forEach { matchUrl ->
                                    val candidate = normalize(matchUrl.value, sourceUrl) ?: return@forEach
                                    if (!candidate.startsWith(mainUrl, true)) playerUrls.putIfAbsent(candidate, sourceUrl)
                                }
                        }
                    }
            }
        }

        debugFilmKovasi("PLAYER_URLS", playerUrls.entries.joinToString(" || ") { it.key + " <- " + it.value })
        for ((playerUrl, referer) in playerUrls) {
            debugFilmKovasi("PLAYER_TRY", playerUrl + " REF=" + referer)
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
