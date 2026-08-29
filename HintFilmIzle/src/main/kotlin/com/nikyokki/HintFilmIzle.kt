package com.nikyokki

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder

class HintFilmIzle : MainAPI() {
    override var mainUrl = "https://www.hintfilmizle.com"
    override var name = "HintFilmİzle"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/film" to "Son Filmler",
        "$mainUrl/film?order=DESC&orderby=date" to "Yeni Eklenenler",
        "$mainUrl/trendler" to "Trendler",
        "$mainUrl/tur/aksiyon-filmleri" to "Aksiyon",
        "$mainUrl/tur/dram-filmleri" to "Dram",
        "$mainUrl/tur/komedi-filmleri" to "Komedi",
        "$mainUrl/tur/korku-filmleri" to "Korku",
        "$mainUrl/tur/macera-filmleri" to "Macera",
        "$mainUrl/tur/romantik-filmleri" to "Romantik",
        "$mainUrl/tur/savas-filmleri" to "Savaş",
        "$mainUrl/tur/suc-filmleri" to "Suç",
        "$mainUrl/tur/tarih-filmleri" to "Tarih",
        "$mainUrl/netflix-izle" to "Netflix"
    )

    private fun cleanUrl(value: String?, base: String = mainUrl): String? {
        val raw = value
            ?.replace("\\\\/", "/")
            ?.replace("\\\\u0026", "&")
            ?.trim()
            ?.substringBefore(" ")
            ?.takeIf { it.isNotBlank() }
            ?: return null

        return runCatching {
            when {
                raw.startsWith("//") -> "https:$raw"
                raw.startsWith("http://", true) || raw.startsWith("https://", true) -> raw
                raw.startsWith("/") -> "$mainUrl$raw"
                else -> URI(base).resolve(raw).toString()
            }
        }.getOrNull()?.takeIf { it.startsWith("http", true) }
    }

    private fun Element.posterUrl(): String? {
        fun valid(value: String?): String? {
            val url = cleanUrl(value)
            return url?.takeIf {
                !it.startsWith("data:image", true) &&
                !it.contains("placeholder", true) &&
                !it.contains("spacer", true) &&
                !it.contains("blank.", true)
            }
        }

        val attrs = listOf(
            "data-src", "data-lazy-src", "data-lazy", "data-original",
            "data-original-src", "data-image", "data-poster", "data-poster-url",
            "data-thumb", "data-thumbnail", "data-fsrc", "data-url",
            "data-image-url", "data-bg", "data-background-image", "src"
        )

        select("img, picture source").asSequence().forEach { image ->
            attrs.asSequence().mapNotNull { valid(image.attr(it)) }.firstOrNull()?.let { return it }

            listOf("data-srcset", "data-lazy-srcset", "srcset").forEach { attr ->
                val candidate = image.attr(attr)
                    .split(",")
                    .asReversed()
                    .asSequence()
                    .map { it.trim().substringBefore(" ").trim() }
                    .mapNotNull { valid(it) }
                    .firstOrNull()
                if (candidate != null) return candidate
            }
        }

        select("[style]").asSequence()
            .map { it.attr("style") }
            .flatMap { style ->
                Regex("""(?:background-image|background)\s*:[^;]*url\((['"]?)(.*?)\1\)""", RegexOption.IGNORE_CASE)
                    .findAll(style)
                    .map { it.groupValues[2] }
            }
            .mapNotNull { valid(it) }
            .firstOrNull()
            ?.let { return it }

        return listOf("data-poster", "data-image", "data-thumb", "data-src")
            .asSequence()
            .mapNotNull { valid(attr(it)) }
            .firstOrNull()
    }

    private fun Element.cardTitle(): String? =
        sequenceOf(
            selectFirst(".film-title")?.text(),
            selectFirst(".movie-title")?.text(),
            selectFirst(".entry-title")?.text(),
            selectFirst(".card-title")?.text(),
            selectFirst("h2")?.text(),
            selectFirst("h3")?.text(),
            selectFirst(".title")?.text(),
            selectFirst(".name")?.text(),
            selectFirst("img")?.attr("alt"),
            selectFirst("img")?.attr("title"),
            attr("title")
        ).mapNotNull { value ->
            value?.trim()
                ?.replace(Regex("\\s+"), " ")
                ?.takeIf { it.isNotBlank() && !it.equals("image", true) }
        }.firstOrNull()

    // Link ve poster aynı elementte olmayabilir; posteri gerçek kart konteynerinden al.
    private fun Element.toSearchResult(card: Element = this): SearchResponse? {
        val href = cleanUrl(attr("href")) ?: return null
        if (!href.startsWith(mainUrl)) return null

        val path = href.removePrefix(mainUrl).substringBefore("?").trimEnd('/')
        if (!path.startsWith("/film/") && !path.startsWith("/dizi/")) return null

        val title = card.cardTitle()
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.removeSuffix(" izle")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: path.substringAfterLast("/")
                .replace(Regex("[-_]+"), " ")
                .replace(Regex("\\b\\w"), { it.value.uppercase() })
                .trim()
                .takeIf { it.isNotBlank() }
                ?: return null

        if (title.length > 180 ||
            title.equals("film", true) ||
            title.equals("dizi", true) ||
            title.equals("filmler", true)
        ) return null

        val poster = card.posterUrl() ?: posterUrl()

        return if (path.startsWith("/dizi/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl = poster
            }
        }
    }

    private fun extractResults(
        document: org.jsoup.nodes.Document,
        pageUrl: String? = null
    ): List<SearchResponse> {
        // Sayfadaki üst "Günün En İyileri / Beklenen Film-Diziler"
        // kartlarını değil, sayfanın kendi sonuç bölümünü al.
        // HintFilmİzle'nin kategori sayfalarında gerçek sonuç başlığı "Filmler",
        // trend sayfasında ise "Haftanın Trendleri" olarak geliyor.
        val preferredHeadings = when {
            pageUrl?.contains("/trendler", true) == true ->
                listOf("Haftanın Trendleri", "Filmler")
            else ->
                listOf("Filmler", "Netflix Filmleri", "Diziler")
        }

        val heading = document.select("h1, h2, h3, h4, h5, h6")
            .firstOrNull { element ->
                preferredHeadings.any { it.equals(element.text().trim(), true) }
            }
            ?: document.select("h1").firstOrNull()

        val anchors = if (heading != null) {
            // Başlığın bulunduğu içerik konteynerinde sonuç kartlarını ara.
            // Üst carousel'ler genellikle heading'in dışında olduğundan artık
            // bunlar sonuca karışmaz.
            val candidates = listOfNotNull(
                heading.parent(),
                heading.parent()?.parent(),
                heading.parent()?.parent()?.parent()
            )

            candidates.asSequence()
                .map { it.select("a[href*='/film/'], a[href*='/dizi/']") }
                .firstOrNull { it.isNotEmpty() }
                ?: emptyList()
        } else {
            document.select("a[href*='/film/'], a[href*='/dizi/']")
        }


        return anchors.mapNotNull { anchor ->
            val card = anchor.parents().firstOrNull {
                val image = it.selectFirst("img, picture source, [style*='background']")
                val links = it.select("a[href*='/film/'], a[href*='/dizi/']")
                image != null && links.size <= 3 && it.text().length < 800
            } ?: anchor

            anchor.toSearchResult(card)
        }.distinctBy { it.url }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = if (page <= 1) request.data else request.data.trimEnd('/') + "/page/" + page + "/"
        val document = runCatching { app.get(pageUrl, referer = "$mainUrl/").document }.getOrNull()
            ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val results = extractResults(document, pageUrl)
        return newHomePageResponse(request.name, results, hasNext = results.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = query.trim().replace(" ", "+")
        val urls = listOf("$mainUrl/?s=$encoded", "$mainUrl/film?search=$encoded")
        for (url in urls) {
            val results = runCatching { extractResults(app.get(url, referer = "$mainUrl/").document, url) }
                .getOrDefault(emptyList())
            if (results.isNotEmpty()) return results
        }
        return emptyList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    private fun firstText(document: org.jsoup.nodes.Document, vararg selectors: String): String? =
        selectors.asSequence().mapNotNull { document.selectFirst(it)?.text()?.trim() }
            .firstOrNull { it.isNotBlank() }

    private fun findNumber(text: String, vararg labels: String): String? {
        val label = labels.joinToString("|") { Regex.escape(it) }
        return Regex("(?:$label)\\s*[:\\-]?\\s*([0-9]+(?:[.,][0-9]+)?)", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.getOrNull(1)
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, referer = "$mainUrl/").document
        val title = firstText(document, "h1", ".entry-title", ".film-title", ".movie-title", ".serieTitle")
            ?: return null

        val poster = cleanUrl(document.selectFirst("meta[property='og:image']")?.attr("content"))
            ?: document.selectFirst("article, .movie-detail, .film-detail, .serie-detail")?.posterUrl()

        val bodyText = document.text()
        val description = firstText(
            document, ".description", ".film-description", ".movie-description",
            ".serieDescription", ".plot", ".entry-content p"
        )
        val year = Regex("\\b(19|20)\\d{2}\\b").find(bodyText)?.value?.toIntOrNull()
        val rating = findNumber(bodyText, "IMDb", "IMDB")
        val tags = document.select(".genres a, .genre a, .genreList a, .categories a, .post-categories a")
            .map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
        val actors = document.select(".actors a, .cast a, .oyuncular a").mapNotNull {
            it.text().trim().takeIf(String::isNotBlank)?.let { name -> Actor(name) }
        }.distinctBy { it.name }
        val recommendations = extractResults(document)

        val isSeries = url.contains("/dizi/", true) ||
            document.selectFirst(".episodes, .episode-list, .seasons") != null

        if (isSeries) {
            val episodes = document.select(
                "a[href*='/dizi/'], a[href*='sezon'], a[href*='bolum'], .episode a, .episodes a, .episode-list a"
            ).mapNotNull { link ->
                val href = cleanUrl(link.attr("href")) ?: return@mapNotNull null
                if (href == url) return@mapNotNull null
                val text = link.text() + " " + link.attr("title")
                val season = Regex("(?:s|sezon[\\s._-]*)(\\d+)", RegexOption.IGNORE_CASE)
                    .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
                val episode = Regex("(?:e|bölüm[\\s._-]*)(\\d+)", RegexOption.IGNORE_CASE)
                    .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
                if (season == null || episode == null) return@mapNotNull null
                newEpisode(href) {
                    name = link.text().trim()
                    this.season = season
                    this.episode = episode
                }
            }.distinctBy { it.data }.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster
                this.year = year
                plot = description
                this.tags = tags
                score = Score.from10(rating)
                addActors(actors)
                this.recommendations = recommendations
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            posterUrl = poster
            this.year = year
            plot = description
            this.tags = tags
            score = Score.from10(rating)
            addActors(actors)
            this.recommendations = recommendations
        }
    }

    private fun directLinks(html: String): List<String> {
        val regex = Regex(
            """https?://[^"'\\s<>]+(?:\\.(?:m3u8|mp4)(?:\\?[^"'\\s<>]*)?|/manifest(?:\\?[^"'\\s<>]*)?)""",
            RegexOption.IGNORE_CASE
        )
        return regex.findAll(html)
            .mapNotNull { match ->
                cleanUrl(match.value.trimEnd('\\', '"', '\'', ')', ']'))
            }
            .distinct()
            .toList()
    }

    private fun playerUrl(value: String?, baseUrl: String): String? {
        val raw = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val url = cleanUrl(raw) ?: return null
        if (url.startsWith("data:", true) || url.startsWith("javascript:", true)) return null
        return if (url.startsWith("//")) "https:$url"
        else if (url.startsWith("/")) "$mainUrl$url"
        else url
    }

    private fun isTrailerPlayer(url: String): Boolean =
        listOf("youtube.com", "youtu.be", "youtube-nocookie.com")
            .any { url.contains(it, true) }

    private fun isKnownPlayer(url: String): Boolean =
        listOf(
            "vidmoly", "vidhide", "streamtape", "voe.sx", "voe.to",
            "ok.ru", "dood", "filemoon", "mixdrop", "streamwish",
            "filelions", "vidsrc", "embed", "player", "kinescope"
        ).any { url.contains(it, true) }


    private fun extractKinescopeHls(html: String): String? {
        val normalized = html
            .replace("\\u0026", "&")
            .replace("\\/","/")

        val patterns = listOf(
            Regex("""["']hls["']\s*:\s*\{\s*["']src["']\s*:\s*["']([^"']+)""", RegexOption.IGNORE_CASE),
            Regex("""["']shakahls["']\s*:\s*\{\s*["']src["']\s*:\s*["']([^"']+)""", RegexOption.IGNORE_CASE),
            Regex("""["']contentUrl["']\s*:\s*["']([^"']+\.m3u8[^"']*)""", RegexOption.IGNORE_CASE)
        )

        // Kinescope bazen master.m3u8 yerine imzali CDN manifesti verir:
        // /hls/.../<file>.mp4/index.m3u8?expires=...&sign=...
        // Bu nedenle yalnızca master/media aramak yeterli degil.
        val manifestCandidates = Regex(
            """https?://[^"\\s<>]+?\\.m3u8(?:\\?[^"\\s<>]*)?""",
            RegexOption.IGNORE_CASE
        ).findAll(normalized)
            .map { it.value.trimEnd('\\', '"' , '\\'', ')', ']') }
            .map { it.replace("&amp;", "&") }
            .mapNotNull { cleanUrl(it) }
            .distinct()
            .toList()

        val encodedManifestCandidates = Regex(
            """https?%3A%2F%2F[^"\\s<>]+?%2Em3u8(?:%3F[^"\\s<>]*)?""",
            RegexOption.IGNORE_CASE
        ).findAll(normalized)
            .mapNotNull { match ->
                runCatching { URLDecoder.decode(match.value, "UTF-8") }.getOrNull()
            }
            .map { it.replace("&amp;", "&") }
            .mapNotNull { cleanUrl(it) }
            .distinct()
            .toList()

        val allCandidates = (manifestCandidates + encodedManifestCandidates).distinct()

        // Once on the browser Network tab, the working address is the signed CDN
        // manifest. Prefer it over an unsigned fallback URL.
        val signedCdn = allCandidates.firstOrNull {
            it.contains("kinescopecdn.net", true) &&
                it.contains("expires=", true) &&
                it.contains("sign=", true)
        }

        val anyCdnManifest = allCandidates.firstOrNull {
            it.contains("kinescopecdn.net", true)
        }

        return signedCdn
            ?: anyCdnManifest
            ?: allCandidates.firstOrNull()
            ?: patterns.asSequence()
                .mapNotNull { it.find(normalized)?.groupValues?.getOrNull(1) }
                .mapNotNull { cleanUrl(it) }
                .firstOrNull()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = runCatching {
            app.get(data, referer = "$mainUrl/").document
        }.getOrNull() ?: return false

        var found = false
        val playerUrls = linkedSetOf<String>()

        // HintFilmİzle playeri yalnızca iframe olarak değil, video/source ve
        // data-* alanlarında da verebiliyor.
        document.select(
            "iframe[src], iframe[data-src], iframe[data-url], iframe[data-iframe], " +
                "frame[src], video[src], video[data-src], video[data-url], " +
                "video source[src], video source[data-src], video source[data-url]"
        ).forEach { element ->
            listOf(
                element.attr("src"),
                element.attr("data-src"),
                element.attr("data-url"),
                element.attr("data-iframe"),
                element.attr("data-video"),
                element.attr("data-player")
            ).forEach { value ->
                playerUrl(value, data)?.let { playerUrls.add(it) }
            }
        }

        // HintFilmİzle'nin TEKPART düğmeleri bazı içeriklerde iframe'i
        // doğrudan src yerine data-* veya onclick içinde tutabiliyor.
        document.select("a[href], button, [onclick], [data-url], [data-embed], [data-video], [data-player]")
            .forEach { element ->
                listOf(
                    element.attr("href"),
                    element.attr("data-url"),
                    element.attr("data-embed"),
                    element.attr("data-video"),
                    element.attr("data-player"),
                    element.attr("onclick")
                ).forEach { value ->
                    val direct = playerUrl(value, data)
                    if (direct != null &&
                        !direct.startsWith(mainUrl, true) &&
                        !isTrailerPlayer(direct)
                    ) {
                        playerUrls.add(direct)
                    }

                    Regex("""https?://[^"'\\s<>]+""")
                        .findAll(value)
                        .map { it.value.trimEnd('\\', '"', '\'', ')', ']') }
                        .mapNotNull { playerUrl(it, data) }
                        .filter { !it.startsWith(mainUrl, true) && !isTrailerPlayer(it) }
                        .forEach { playerUrls.add(it) }
                }
            }

        // Bilinen sağlayıcılar link olarak gelirse ayrıca koru.
        document.select("a[href]").forEach { link ->
            val href = playerUrl(link.attr("href"), data) ?: return@forEach
            if (!href.startsWith(mainUrl, true) &&
                !isTrailerPlayer(href) &&
                isKnownPlayer(href)
            ) {
                playerUrls.add(href)
            }
        }

        // HTML/JS içinde saklanan doğrudan video adreslerini de yakala.
        directLinks(document.html()).forEach { stream ->
            found = true
            callback(newExtractorLink(
                source = name,
                name = "HintFilmİzle Direct",
                url = stream,
                type = if (stream.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                referer = data
                quality = getQualityFromName(stream)
            })
        }

        for (player in playerUrls) {
            if (player.startsWith("http", true)) {
                // HintFilmİzle'nin TEKPART oynatıcısı Kinescope embed kullanıyor.
                // Kinescope'un imzalı HLS adresi embed HTML içindeki playerOptions
                // nesnesinde veriliyor; imza süreli olduğu için URL'yi sabit yazmıyoruz.
                if (player.contains("kinescope", true)) {
                    val playerHtml = runCatching {
                        app.get(player, referer = data).text
                    }.getOrNull().orEmpty()

                    // Kinescope embed format is /embed/{VIDEO_ID}; the video ID
                    // may be numeric or an opaque alphanumeric value.
                    // Kinescope CDN embeds used by HintFilmIzle can contain a
                    // project/player segment before the actual video embed:
                    // /embed/{project}/embed/{videoId}. Always take the LAST /embed/.
                    val kinescopeVideoId = Regex(
                        """/embed/([^/?#]+)/embed/([^/?#]+)""",
                        RegexOption.IGNORE_CASE
                    ).find(player)?.groupValues?.getOrNull(2)
                        ?: Regex(
                            """/embed/([^/?#]+)""",
                            RegexOption.IGNORE_CASE
                        ).findAll(player).lastOrNull()?.groupValues?.getOrNull(1)
                    // Kinescope player.js may generate the signed CDN manifest only
                    // after the player starts. Prefer a manifest exposed in the embed
                    // HTML, then fall back to Kinescope's documented direct HLS endpoint.
                    val kinescopeStream = extractKinescopeHls(playerHtml)
                        ?: directLinks(
                            playerHtml
                                .replace("\\u0026", "&")
                                .replace("\\/","/")
                        ).firstOrNull { it.contains(".m3u8", true) }
                        ?: kinescopeVideoId?.let {
                            "https://kinescope.io/$it/master.m3u8"
                        }

                    if (kinescopeStream != null) {
                        found = true
                        val playerOrigin = runCatching {
                            URI(player).let { "${it.scheme}://${it.host}" }
                        }.getOrDefault("https://kinescope.io")

                        // Kinescope CDN validates the signed manifest together with
                        // the embed Origin/Referer. Match the browser request exactly
                        // and let the same headers flow into HLS segment requests.
                        val playerReferer = "$playerOrigin/"

                        callback(newExtractorLink(
                            source = name,
                            name = "HintFilmİzle Kinescope",
                            url = kinescopeStream,
                            type = ExtractorLinkType.M3U8
                        ) {
                            referer = playerReferer
                            headers = mapOf(
                                "Referer" to playerReferer,
                                "Origin" to playerOrigin,
                                "Accept" to "*/*"
                            )
                            quality = getQualityFromName(kinescopeStream)
                        })
                    }
                } else {
                    val result = runCatching {
                        loadExtractor(player, data, subtitleCallback, callback)
                    }
                    if (result.isSuccess) found = true
                }

                // Bazı embed URL'leri ikinci bir iframe döndürüyor.
                val nested = runCatching {
                    app.get(player, referer = data).document
                }.getOrNull()

                nested?.select("iframe[src], iframe[data-src], video[src], video source[src]")
                    ?.forEach { element ->
                        val nestedUrl = playerUrl(
                            element.attr("src").ifBlank { element.attr("data-src") },
                            player
                        ) ?: return@forEach

                        if (nestedUrl != player) {
                            runCatching {
                                loadExtractor(nestedUrl, player, subtitleCallback, callback)
                            }.onSuccess { found = true }
                        }
                    }

                val nestedHtml = nested?.html().orEmpty()
                for (stream in directLinks(nestedHtml)) {
                    found = true
                    callback(newExtractorLink(
                        source = name,
                        name = "HintFilmİzle Direct",
                        url = stream,
                        type = if (stream.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        referer = player
                        quality = getQualityFromName(stream)
                    })
                }
            }
        }

        return found
    }
}
