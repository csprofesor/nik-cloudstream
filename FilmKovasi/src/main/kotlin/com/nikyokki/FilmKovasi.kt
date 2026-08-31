package com.nikyokki

import com.nikyokki.extractors.FilmKovasiBundledExtractors

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
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

// FilmKovasi source-page resolver: source pages (/2/, /3/, ...) are parsed for real player URLs.
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
        "${mainUrl}/filmizle/vahsi-bati/" to "Vahşi Batı",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = if (page <= 1) request.data else "${request.data}page/$page/"
        val document = app.get(
            pageUrl,
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
            )
        ).document
        return newHomePageResponse(
            request.name,
            document.select("div.movie-box").mapNotNull { it.toMainPageResult() }.distinctBy { it.url }
        )
    }

    private fun Element.posterUrl(): String? {
        val image = if (tagName() == "img") this else selectFirst("img") ?: return null
        listOf("data-src", "data-lazy-src", "data-original", "data-image", "src").forEach { attr ->
            val value = image.attr(attr).trim()
            if (value.isNotBlank() && !value.startsWith("data:image")) return fixUrlNull(value)
        }
        return image.attr("srcset").substringBefore(',').trim().split(" ").firstOrNull()?.let { fixUrlNull(it) }
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val link = selectFirst("div.film-ismi a[href]") ?: return null
        val href = fixUrlNull(link.attr("href")) ?: return null
        val title = link.text().replace(Regex("\\s+"), " ")
            .replace(Regex("(?i)\\s+izle$"), "").trim()
        if (title.length < 2) return null
        val poster = selectFirst("div.poster img")?.posterUrl() ?: selectFirst("img")?.posterUrl()
        return newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }
    }

    override suspend fun search(query: String): List<SearchResponse> =
        app.get(
            "${mainUrl}/?s=${query}",
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
            )
        ).document.select("div.movie-box")
            .mapNotNull { it.toMainPageResult() }.distinctBy { it.url }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        debugFilmKovasi("LOAD_URL", url)
        val document = app.get(
            url,
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
            )
        ).document
        val title = document.selectFirst("h1.title-border, h1, .title-border")?.text()
            ?.replace(Regex("(?i)\\s+izle$"), "")?.trim() ?: return null
        val poster = document.selectFirst("meta[property='og:image']")?.attr("content")?.let { fixUrlNull(it) }
            ?: document.selectFirst("div.film-afis img, .film-afis img, .poster img, .film-poster img")?.posterUrl()
        val description = document.selectFirst("div#film-aciklama, #film-aciklama, .film-aciklama")?.text()?.trim()
        var year = document.selectFirst("div.release a")?.text()?.trim()?.toIntOrNull()
        val tags = document.select("div#listelements a, #listelements a").map { it.text() }
        val rating = document.selectFirst("div.imdb")?.text()?.replace("IMDb Puanı:", "")?.split("/")?.first()?.trim()
        var actors = document.select("div.actor a").map { it.text() }
        val trailer = document.selectFirst("div.film-afis iframe")?.let {
            fixUrlNull(it.attr("src").ifBlank { it.attr("data-src") })
        }
        document.select("div.list-item").forEach { item ->
            if (item.selectFirst("a")?.attr("href")?.contains("/yil/") == true) {
                year = item.selectFirst("a")?.text()?.toIntOrNull()
            }
            if (item.selectFirst("a")?.attr("href")?.contains("/oyuncu/") == true) {
                actors = item.select("a").map { it.text() }
            }
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        debugFilmKovasi("LOADLINKS_DATA", data)

        val document = runCatching {
            app.get(
                data,
                referer = mainUrl + "/",
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
                )
            ).document
        }.getOrNull() ?: return false

        var found = false
        val visited = mutableSetOf<String>()
        val playerQueue = ArrayDeque<Pair<String, String>>()

        fun normalize(raw: String?, base: String): String? {
            val value = raw?.trim()
                ?.replace("\\/", "/")
                ?.replace("\\u0026", "&")
                ?.replace("&amp;", "&")
                ?.trim('"', '\'', '(', ')', '[', ']', '{', '}', ';', ',')
                ?.takeIf { it.isNotBlank() }
                ?: return null

            val resolved = runCatching {
                when {
                    value.startsWith("//") -> "https:$value"
                    value.startsWith("http://", true) || value.startsWith("https://", true) -> value
                    else -> java.net.URI(base).resolve(value).toString()
                }
            }.getOrNull() ?: return null

            if (!resolved.startsWith("http://", true) && !resolved.startsWith("https://", true)) return null
            if (resolved.equals("about:blank", true)) return null
            return resolved
        }

        fun ignored(url: String): Boolean {
            val u = url.lowercase()
            return listOf(
                "youtube.com", "youtu.be", "youtube-nocookie.com",
                "video.twimg.com", "twitter.com", "x.com", "t.co/",
                "facebook.com", "fb.watch", "instagram.com", "instagramcdn.com",
                "tiktok.com", "vimeo.com", "doubleclick.net",
                "googlesyndication.com", "/ads/", "ads.", "/advert"
            ).any { u.contains(it) }
        }

        fun enqueue(raw: String?, base: String, reason: String) {
            val url = normalize(raw, base) ?: return
            if (url == data || url.startsWith(mainUrl, true) || ignored(url)) return
            if (visited.add(url)) {
                debugFilmKovasi("PLAYER_QUEUE", "${reason} -> ${url}")
                playerQueue.addLast(url to base)
            }
        }

        fun addDirectMedia(html: String, referer: String, sourceName: String): Boolean {
            var added = false
            Regex(
                """https?://[^"'\s<>]+?\\.(?:m3u8|mp4)(?:\\?[^"'\s<>]*)?""",
                RegexOption.IGNORE_CASE
            ).findAll(html).forEach { match ->
                val media = match.value
                    .replace("&amp;", "&")
                    .trimEnd('\\', '"', '\'', ')', ']', ';', ',')
                if (!ignored(media)) {
                    callback(newExtractorLink(
                        source = name,
                        name = "${sourceName} Direct",
                        url = media,
                        type = if (media.contains(".m3u8", true))
                            ExtractorLinkType.M3U8
                        else
                            ExtractorLinkType.VIDEO
                    ) {
                        this.referer = referer
                    })
                    added = true
                }
            }
            return added
        }

        val sourcePages = document.select("a[href]").mapNotNull { element ->
            val href = normalize(element.attr("href"), data) ?: return@mapNotNull null
            if (!href.startsWith(mainUrl, true) || href == data) return@mapNotNull null
            val number = Regex("/(\\d+)/?$").find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: return@mapNotNull null
            if (number < 2) return@mapNotNull null
            href to (element.text().replace(Regex("\\s+"), " ").trim().ifBlank { "Kaynak ${number}" })
        }.distinctBy { it.first }
            .sortedBy { Regex("/(\\d+)/?$").find(it.first)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: Int.MAX_VALUE }

        debugFilmKovasi("SOURCE_PAGE_COUNT", sourcePages.size.toString())

        for ((sourceUrl, label) in sourcePages) {
            debugFilmKovasi("SOURCE_PAGE_START", "${label} = ${sourceUrl}")
            val sourceDocument = runCatching {
                app.get(
                    sourceUrl,
                    referer = data,
                    headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
                    )
                ).document
            }.getOrNull() ?: continue

            val sourceHtml = sourceDocument.html()
            debugFilmKovasi("SOURCE_PAGE_OK", "${label} = ${sourceUrl}")

            sourceDocument.select(
                "iframe[src], iframe[data-src], iframe[data-url], iframe[data-frame], iframe[data-iframe], " +
                "frame[src], embed[src], object[data], video[src], video[data-src], " +
                "video[data-url], video source[src], video source[data-src], video source[data-url], " +
                "a[href], a[data-url], a[data-embed], a[data-frame], a[data-video], a[data-player], " +
                "button[data-url], button[data-embed], button[data-frame], button[data-video], button[data-player], " +
                "[onclick], [data-url], [data-embed], [data-frame], [data-video], [data-player]"
            ).forEach { element ->
                listOf(
                    element.attr("href"),
                    element.attr("src"),
                    element.attr("data-src"),
                    element.attr("data-url"),
                    element.attr("data-embed"),
                    element.attr("data-frame"),
                    element.attr("data-video"),
                    element.attr("data-player"),
                    element.attr("data-iframe"),
                    element.attr("onclick")
                ).forEach { raw -> enqueue(raw, sourceUrl, "${label} ${element.tagName()}") }
            }

            Regex("""https?:\\/\\/[^"'\s<>]+""", RegexOption.IGNORE_CASE)
                .findAll(sourceHtml)
                .forEach { match -> enqueue(match.value.replace("\\/", "/"), sourceUrl, "${label} html") }

            val atobRegex = Regex("""atob\\(\s*["']([^"']+)["']\s*\\)""", RegexOption.IGNORE_CASE)
            val absoluteRegex = Regex("""https?://[^"'\s<>]+""", RegexOption.IGNORE_CASE)
            sourceDocument.select("script").forEach { script ->
                val text = script.data().ifBlank { script.html() }
                atobRegex.findAll(text).forEach { match ->
                    runCatching {
                        val decoded = Base64.decode(match.groupValues[1], Base64.DEFAULT).toString(Charsets.UTF_8)
                        absoluteRegex.findAll(decoded).forEach { url -> enqueue(url.value, sourceUrl, "${label} atob") }
                    }
                }
                absoluteRegex.findAll(text).forEach { url -> enqueue(url.value, sourceUrl, "${label} script") }
            }

            if (addDirectMedia(sourceHtml, sourceUrl, label)) found = true
        }

        var depth = 0
        while (playerQueue.isNotEmpty() && depth < 80) {
            val (playerUrl, referer) = playerQueue.removeFirst()
            depth++
            debugFilmKovasi("PLAYER_START", playerUrl)

            val loaded = runCatching {
                loadExtractor(
                    url = playerUrl,
                    referer = referer,
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )
            }.getOrDefault(false)

            if (loaded) found = true

            val nestedDocument = runCatching {
                app.get(
                    playerUrl,
                    referer = referer,
                    headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
                        "Accept" to "*/*",
                        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
                    )
                ).document
            }.getOrNull() ?: continue

            val nestedHtml = nestedDocument.html()
            if (addDirectMedia(nestedHtml, playerUrl, playerUrl.substringAfter("://").substringBefore('/'))) {
                found = true
            }

            nestedDocument.select(
                "iframe[src], iframe[data-src], iframe[data-url], iframe[data-frame], iframe[data-iframe], " +
                "frame[src], embed[src], object[data], video[src], video[data-src], " +
                "video source[src], video source[data-src], video source[data-url], " +
                "[data-url], [data-embed], [data-frame], [data-video], [data-player]"
            ).forEach { element ->
                listOf(
                    element.attr("src"),
                    element.attr("data-src"),
                    element.attr("data-url"),
                    element.attr("data-embed"),
                    element.attr("data-frame"),
                    element.attr("data-video"),
                    element.attr("data-player"),
                    element.attr("data-iframe"),
                    element.attr("data")
                ).forEach { raw -> enqueue(raw, playerUrl, "nested ${element.tagName()}") }
            }

            Regex("""https?:\\/\\/[^"'\s<>]+""", RegexOption.IGNORE_CASE)
                .findAll(nestedHtml)
                .forEach { match -> enqueue(match.value.replace("\\/", "/"), playerUrl, "nested html") }

            nestedDocument.select("script").forEach { script ->
                val text = script.data().ifBlank { script.html() }
                Regex("""atob\\(\s*["']([^"']+)["']\s*\\)""", RegexOption.IGNORE_CASE)
                    .findAll(text).forEach { match ->
                        runCatching {
                            val decoded = Base64.decode(match.groupValues[1], Base64.DEFAULT).toString(Charsets.UTF_8)
                            Regex("""https?://[^"'\s<>]+""").findAll(decoded)
                                .forEach { url -> enqueue(url.value, playerUrl, "nested atob") }
                        }
                    }
                Regex("""https?://[^"'\s<>]+""").findAll(text)
                    .forEach { url -> enqueue(url.value, playerUrl, "nested script") }
            }
        }

        document.select(
            "iframe[src], iframe[data-src], iframe[data-url], iframe[data-frame], " +
            "embed[src], video[src], video[data-src], video source[src], video source[data-src], " +
            "[data-embed], [data-player], [data-video], [data-url]"
        ).forEach { element ->
            listOf(
                element.attr("src"),
                element.attr("data-src"),
                element.attr("data-url"),
                element.attr("data-embed"),
                element.attr("data-player"),
                element.attr("data-video")
            ).forEach { raw -> enqueue(raw, data, "film-page") }
        }

        var tail = 0
        while (playerQueue.isNotEmpty() && tail < 40) {
            val (playerUrl, referer) = playerQueue.removeFirst()
            tail++
            runCatching {
                if (loadExtractor(playerUrl, referer, subtitleCallback) { link ->
                    found = true
                    callback(link)
                }) {
                    found = true
                }
            }
            val nested = runCatching { app.get(playerUrl, referer = referer).document }.getOrNull() ?: continue
            if (addDirectMedia(nested.html(), playerUrl, playerUrl.substringAfter("://").substringBefore('/'))) found = true
            nested.select("iframe[src],iframe[data-src],video[src],video[data-src],video source[src],video source[data-src],[data-url],[data-embed],[data-player],[data-video]")
                .forEach { element ->
                    listOf(element.attr("src"), element.attr("data-src"), element.attr("data-url"),
                        element.attr("data-embed"), element.attr("data-player"), element.attr("data-video"))
                        .forEach { raw -> enqueue(raw, playerUrl, "tail") }
                }
        }

        return found
    }

}
