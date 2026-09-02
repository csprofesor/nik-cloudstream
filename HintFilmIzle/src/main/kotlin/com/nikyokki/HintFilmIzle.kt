package com.nikyokki

import android.util.Log
import android.util.Base64

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
import com.lagradost.cloudstream3.network.WebViewResolver
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
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

class HintFilmIzle : MainAPI() {
    override var mainUrl = "https://www.hintfilmizle.com"
    override var name = "HintFilmİzle"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/film?order=DESC&orderby=date" to "Yeni Filmler",
        "$mainUrl/trendler" to "Trendler",

        // Türler — Korku ve genel "Film" kategorisi özellikle listede yok.
        "$mainUrl/tur/aile-filmleri" to "Aile",
        "$mainUrl/tur/aksiyon-filmleri" to "Aksiyon",
        "$mainUrl/tur/animasyon-filmleri" to "Animasyon",
        "$mainUrl/tur/bilim-kurgu-filmleri" to "Bilim Kurgu",
        "$mainUrl/tur/dram-filmleri" to "Dram",
        "$mainUrl/tur/fantastik-filmleri" to "Fantastik",
        "$mainUrl/tur/komedi-filmleri" to "Komedi",
        "$mainUrl/tur/macera-filmleri" to "Macera",
        "$mainUrl/tur/romantik-filmleri" to "Romantik",
        "$mainUrl/tur/savas-filmleri" to "Savaş",
        "$mainUrl/tur/suc-filmleri" to "Suç",
        "$mainUrl/tur/tarih-filmleri" to "Tarih",
        "$mainUrl/tur/gerilim-filmleri" to "Gerilim",

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
        /*
         * HintFilmIzle'nin HTML yapısı aynı sayfada birkaç farklı film listesi
         * barındırıyor: Günün En İyileri, Beklenenler ve asıl sonuç listesi.
         * Önceki sürüm heading'in 1-3 parent'ına güveniyordu. Site DOM'u
         * değiştiğinde bu yüzden sonuçlar tamamen kaybolabiliyordu.
         *
         * Burada href'i gerçek /film/ veya /dizi/ sayfasına giden bütün kartları
         * topluyoruz. Navigasyon linkleri zaten toSearchResult() içinde eleniyor.
         * Böylece ana sayfa, kategori, trend, sayfalama ve arama sonuçları aynı
         * extractor yolundan geçiyor.
         */
        val allAnchors = document.select(
            "a[href*='/film/'], a[href*='/dizi/']"
        )

        /*
         * Kategori sayfalarının HTML'inde üst tarafta bütün siteye ait
         * "Günün En İyileri" / "Beklenenler" gibi ortak listeler de bulunuyor.
         * Bunları doğrudan bütün href'leri tarayarak alırsak her kategori aynı
         * 10-20 filmi gösteriyor.
         *
         * Kategori/arama arşivinde gerçek sonuç listesinin başlangıcı h1'den
         * sonradır. Bu nedenle h1'in DOM konumundan önceki film linklerini
         * tamamen dışarıda bırakıyoruz. Böylece /tur/aile-filmleri yalnızca
         * Aile arşivini, /tur/aksiyon-filmleri yalnızca Aksiyon arşivini verir.
         *
         * H1 bulunmayan ana sayfa gibi özel listelerde ise eski davranışa
         * kontrollü olarak geri dönüyoruz.
         */
        val heading = document.selectFirst("main h1")
            ?: document.selectFirst("h1")

        val startIndex = heading?.let { document.allElements.indexOf(it) } ?: -1

        val anchors = if (startIndex >= 0) {
            allAnchors.filter { document.allElements.indexOf(it) > startIndex }
        } else {
            allAnchors
        }

        return anchors.mapNotNull { anchor ->
            val card = anchor.parents().firstOrNull {
                val links = it.select("a[href*='/film/'], a[href*='/dizi/']")
                val image = it.selectFirst(
                    "img, picture source, [style*='background'], [data-poster], [data-image]"
                )
                image != null && links.size <= 4 && it.text().length < 1200
            } ?: anchor

            anchor.toSearchResult(card)
        }.distinctBy { it.url }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = if (page <= 1) request.data else request.data.trimEnd('/') + "/page/" + page + "/"
        val document = runCatching {
            app.get(
                pageUrl,
                referer = "$mainUrl/",
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
                )
            ).document
        }.getOrNull()
            ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val results = extractResults(document, pageUrl)
        return newHomePageResponse(request.name, results, hasNext = results.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().takeIf { it.isNotBlank() } ?: return emptyList()
        val encoded = URLEncoder.encode(q, "UTF-8")

        /*
         * Site tarafındaki arama formu zaman zaman parametre adını değiştiriyor.
         * Tek bir endpoint'e bağımlı kalmıyoruz. Ayrıca /film sayfasını doğrudan
         * sorgu parametreleriyle deniyoruz; böylece CloudStream'de kullanıcı
         * aradığı filmi bulamasa bile kategori arşivinden sonuç alma şansımız var.
         */
        val urls = linkedSetOf(
            "$mainUrl/film?search=$encoded",
            "$mainUrl/film?s=$encoded",
            "$mainUrl/?s=$encoded",
            "$mainUrl/?search=$encoded",
            "$mainUrl/arama?q=$encoded",
            "$mainUrl/search?q=$encoded"
        )

        for (url in urls) {
            val results = runCatching {
                val response = app.get(
                    url,
                    referer = "$mainUrl/",
                    headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
                    )
                )
                extractResults(response.document, url)
            }.getOrDefault(emptyList())

            if (results.isNotEmpty()) return results
        }

        /*
         * Son çare: arşiv sayfalarını birkaç sayfa tarayıp başlık eşleşmesini
         * CloudStream tarafında yapıyoruz. Bu, site arama endpoint'i değişse bile
         * içerik keşfini ayakta tutar.
         */
        val needle = q.lowercase()
        for (page in 1..6) {
            val url = if (page == 1) "$mainUrl/film" else "$mainUrl/film/page/$page/"
            val results = runCatching {
                extractResults(
                    app.get(
                        url,
                        referer = "$mainUrl/film",
                        headers = mapOf(
                            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
                            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                            "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
                        )
                    ).document,
                    url
                )
            }.getOrDefault(emptyList())

            val matched = results.filter {
                it.name.lowercase().contains(needle)
            }
            if (matched.isNotEmpty()) return matched
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
        val document = app.get(
            url,
            referer = "$mainUrl/",
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
            )
        ).document
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
            """https?://[^"'\\s<>]+?\.(?:m3u8|mp4)(?:\?[^"'\\s<>]*)?""",
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
        val url = runCatching {
            when {
                raw.startsWith("//") -> "https:$raw"
                raw.startsWith("http://", true) || raw.startsWith("https://", true) -> raw
                else -> URI(baseUrl).resolve(raw).toString()
            }
        }.getOrNull()?.takeIf { it.startsWith("http", true) } ?: return null
        if (url.startsWith("data:", true) || url.startsWith("javascript:", true)) return null
        return url
    }

    private fun isTrailerPlayer(url: String): Boolean =
        listOf("youtube.com", "youtu.be", "youtube-nocookie.com")
            .any { url.contains(it, true) }


    private fun isIgnoredPlayer(url: String): Boolean {
        val u = url.lowercase()

        // Unrelated short-form/social/ad media that can be embedded on film pages.
        // Never let these become CloudStream film sources.
        val blockedHosts = listOf(
            "video.twimg.com",
            "twitter.com",
            "x.com",
            "t.co/",
            "youtube.com",
            "youtu.be",
            "youtube-nocookie.com",
            "facebook.com",
            "fb.watch",
            "instagram.com",
            "instagramcdn.com",
            "tiktok.com",
            "vimeo.com"
        )

        if (blockedHosts.any { u.contains(it) }) return true

        return u.contains("/ads/") ||
            u.contains("ads.") ||
            u.contains("/advert") ||
            u.contains("doubleclick.net") ||
            u.contains("googlesyndication.com")
    }


    private fun extractKinescopeSignedHls(html: String): String? {
        val normalized = html
            .replace("\\u0026", "&")
            .replace("\\u003F", "?")
            .replace("\\/", "/")
            .replace("&amp;", "&")

        fun decode(value: String): String {
            var v = value.trim()
                .replace("\\u0026", "&")
                .replace("\\u003F", "?")
                .replace("\\/", "/")
                .replace("&amp;", "&")
            if (v.contains("\\u")) {
                v = Regex("""\\u([0-9a-fA-F]{4})""").replace(v) {
                    it.groupValues[1].toInt(16).toChar().toString()
                }
            }
            return v
        }

        fun balancedObject(source: String, start: Int): String? {
            var depth = 0
            var quote: Char? = null
            var escaped = false
            for (i in start until source.length) {
                val c = source[i]
                if (quote != null) {
                    if (escaped) escaped = false
                    else if (c == '\\') escaped = true
                    else if (c == quote) quote = null
                    continue
                }
                if (c == '"' || c == '\'') {
                    quote = c
                    continue
                }
                if (c == '{') depth++
                else if (c == '}') {
                    depth--
                    if (depth == 0) return source.substring(start, i + 1)
                }
            }
            return null
        }

        val optionsMatch = Regex("""playerOptions\s*=\s*\{""", RegexOption.IGNORE_CASE)
            .find(normalized)
        val playerOptions = optionsMatch?.range?.last?.let { balancedObject(normalized, it) }

        val sourcePatterns = listOf(
            Regex("""["']hls["']\s*:\s*\{\s*["']src["']\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""["']shakahls["']\s*:\s*\{\s*["']src["']\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""\bhls\s*:\s*\{\s*src\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""\bshakahls\s*:\s*\{\s*src\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        )

        fun findSource(text: String): String? =
            sourcePatterns.asSequence()
                .flatMap { regex -> regex.findAll(text).asSequence() }
                .mapNotNull { match -> match.groupValues.getOrNull(1) }
                .map(::decode)
                .firstOrNull {
                    it.contains(".m3u8", true) &&
                        (it.contains("kinescopecdn.net", true) || it.contains("kinescope", true))
                }

        findSource(playerOptions ?: "")?.let { return it }
        findSource(normalized)?.let { return it }

        return Regex(
            """https?://[^"'\\s<>]+?\.m3u8(?:\?[^"'\\s<>]*)?""",
            RegexOption.IGNORE_CASE
        ).findAll(normalized)
            .map { decode(it.value) }
            .firstOrNull { it.contains("expires=", true) && it.contains("sign=", true) }
    }

    private companion object {
        const val KINESCOPE_DECODER_KEY = "RySdvcyu5iTUxn97vn4HwoniwgxaCynA"
    }

    private fun decodeKinescopeManifestResponse(response: String): String? {
        val body = response.trim()
        if (!body.startsWith("{") || !body.contains("\"p\"")) {
            return body.takeIf { it.startsWith("#EXTM3U") }
        }

        val p = Regex("""\"p\"\s*:\s*\"([^\"]+)\"""")
            .find(body)?.groupValues?.getOrNull(1) ?: return null

        return runCatching {
            val encrypted = Base64.decode(p.reversed(), Base64.DEFAULT)
            val key = KINESCOPE_DECODER_KEY.toByteArray(Charsets.UTF_8)
            val decoded = ByteArray(encrypted.size) { i ->
                (encrypted[i].toInt() and 0xff xor
                    (key[i % key.size].toInt() and 0xff)).toByte()
            }
            String(decoded, Charsets.UTF_8).trim()
        }.getOrNull()
    }

    private fun extractKinescopeVariant(playlist: String, baseUrl: String): String? {
        if (!playlist.contains("#EXTM3U")) return null

        val lines = playlist.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val variantIndex = lines.indexOfFirst {
            it.startsWith("#EXT-X-STREAM-INF", true)
        }
        if (variantIndex < 0) return null

        val uri = lines.drop(variantIndex + 1)
            .firstOrNull { !it.startsWith("#") }
            ?: return null

        return runCatching {
            when {
                uri.startsWith("http://", true) || uri.startsWith("https://", true) -> uri
                uri.startsWith("//") -> "https:$uri"
                else -> URI(baseUrl).resolve(uri).toString()
            }
        }.getOrNull()
    }

    private suspend fun loadKinescope(
        iframeUrl: String,
        parentUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return runCatching {
            /*
             * Resolve Kinescope in a browser and capture the signed HLS URL.
             * The working Kinescope clients pass the signed manifest onward and
             * use the embed URL as the Referer; they do not force the browser's
             * Origin header onto native HLS requests.
             */
            // player.hintfilmizle.com is browser-only in this environment.
            // Do not resolve it through the native HTTP client: that produces
            // UnknownHostException and prevents the WebView from reaching Kinescope.
            /*
             * Do not stop on the first .m3u8 request. Kinescope can request a
             * short preview/ad/auxiliary playlist before the actual movie HLS.
             * The old interceptUrl behavior therefore selected the same short
             * video for unrelated films.
             */
            val resolver = WebViewResolver(
                // Do not stop on the first request. The player first fetches a
                // JSON/API payload and only then obtains the encrypted HLS data.
                interceptUrl = Regex("""https?://[^"'\\s<>]*kinescopecdn\\.net/hls/[^"'\\s<>]+\\.m3u8(?:\\?[^"'\\s<>]*)?""", RegexOption.IGNORE_CASE),
                additionalUrls = listOf(
                    // Keep API/config/player requests while waiting for the
                    // final HLS request. interceptUrl must NOT match the embed
                    // page itself, otherwise WebViewResolver stops immediately.
                    Regex("""https?://[^"'\\s<>]+""", RegexOption.IGNORE_CASE)
                ),
                userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
                useOkhttp = false,
                timeout = 30_000L,
                script = """
                    (function() {
                        try {
                            var v = document.querySelector('video');
                            var sources = Array.from(document.querySelectorAll('video,source'))
                                .map(function(e) {
                                    return {
                                        src: e.currentSrc || e.src || '',
                                        type: e.getAttribute('type') || ''
                                    };
                                })
                                .filter(function(x) { return x.src; });

                            var resourceUrls = performance.getEntriesByType('resource')
                                .map(function(e) { return e.name; })
                                .filter(function(u) {
                                    return /m3u8|kinescope|kinescopecdn|hls|stream|media|video|asset|playback/i.test(u);
                                });

                            var globals = {};
                            ['current_video_id','currnetFileId','iframeUrl','apiPosterUrl','apiTrailerUrl']
                                .forEach(function(k) {
                                    try {
                                        if (typeof window[k] !== 'undefined') globals[k] = String(window[k]);
                                    } catch (_) {}
                                });

                            try {
                                if (typeof contentIds !== 'undefined') {
                                    globals.contentIds = JSON.stringify(contentIds);
                                }
                            } catch (_) {}

                            try {
                                if (typeof playerInstance !== 'undefined' && playerInstance) {
                                    globals.playerInstance = Object.keys(playerInstance).slice(0,80).join(',');
                                    try {
                                        if (typeof playerInstance.api !== 'undefined') {
                                            globals.playerApi = String(playerInstance.api);
                                        }
                                    } catch (_) {}
                                }
                            } catch (_) {}

                            return JSON.stringify({
                                href: location.href,
                                videoCurrentSrc: v ? (v.currentSrc || v.src || '') : '',
                                videoReadyState: v ? v.readyState : -1,
                                sources: sources,
                                resources: resourceUrls,
                                globals: globals
                            });
                        } catch (e) {
                            return JSON.stringify({error: String(e), href: location.href});
                        }
                    })()
                """.trimIndent(),
                scriptCallback = { result ->
                    Log.d("HintFilmIzle", "KINESCOPE_JS_GRAPH=$result")
                }
            )

            val resolved = runCatching {
                resolver.resolveUsingWebView(
                    url = iframeUrl,
                    referer = parentUrl,
                    headers = mapOf(
                        "Referer" to parentUrl,
                        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
                        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
                    )
                )
            }.onFailure {
                Log.e("HintFilmIzle", "KINESCOPE_WEBVIEW_RESOLVE_FAILED", it)
            }.getOrNull()

            val capturedRequests = resolved?.second.orEmpty()

            // Diagnostic dump: include every captured Kinescope/player request.
            // URLs are enough to compare two different film embeds without
            // dumping cookies or authorization headers.
            capturedRequests
                .map { it.url.toString() }
                .distinct()
                .forEach { url ->
                    Log.d("HintFilmIzle", "KINESCOPE_REQUEST=$url")
                }

            val allUrls = capturedRequests
                .map { it.url.toString() }
                .distinct()

            Log.d(
                "HintFilmIzle",
                "KINESCOPE_REQUEST_COUNT=" + allUrls.size
            )

            allUrls.forEach { url ->
                Log.d("HintFilmIzle", "KINESCOPE_REQUEST=$url")
            }

            val manifestCandidates = allUrls
                .filter { it.contains(".m3u8", true) }
                .distinct()

            Log.d(
                "HintFilmIzle",
                "KINESCOPE_MANIFEST_CANDIDATES=" + manifestCandidates.joinToString(" || ")
            )

            if (manifestCandidates.isEmpty()) {
                Log.e("HintFilmIzle", "KINESCOPE_NO_HLS_CANDIDATE")
                return false
            }

            // HintFilmIzle uses /embed/<video-id>, not /<video-id>/embed.
            val embedId = Regex("""/embed/(\d+)(?:[/?#]|$)""", RegexOption.IGNORE_CASE)
                .find(iframeUrl)?.groupValues?.getOrNull(1)

            fun manifestScore(url: String): Int {
                var score = 0
                if (embedId != null && url.contains(embedId, true)) score += 1000
                if (url.contains("/hls/", true)) score += 100
                if (url.contains("master", true) || url.contains("index", true)) score += 30
                if (url.contains("playlist", true)) score += 20
                if (url.contains("preview", true) || url.contains("trailer", true)) score -= 500
                if (url.contains("/ad", true) || url.contains("ads", true)) score -= 500
                return score
            }

            // Later requests win ties because the actual player playlist is
            // normally requested after the embed initializes.
            val manifestUrl = manifestCandidates
                .mapIndexed { index, url -> Triple(manifestScore(url), index, url) }
                .maxWithOrNull(
                    compareBy<Triple<Int, Int, String>> { it.first }.thenBy { it.second }
                )
                ?.third
                ?: return false

            Log.d(
                "HintFilmIzle",
                "KINESCOPE_SELECTED_MANIFEST=${manifestUrl} score=${manifestScore(manifestUrl)}"
            )

            val manifestRequest = capturedRequests.lastOrNull {
                it.url.toString() == manifestUrl
            } ?: return false

            val nativeManifestUrl = manifestUrl

            val captured = manifestRequest.headers
            fun capturedHeader(name: String): String? =
                captured[name]?.takeIf { it.isNotBlank() }

            val userAgent = capturedHeader("User-Agent")
                ?: "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36"

            /*
             * Keep the native HLS request deliberately small. In particular,
             * don't send Origin: the browser sends it because the request is
             * CORS/fetch traffic; ExoPlayer is not making a browser CORS
             * request. The signed URL + Referer are the relevant inputs.
             */
            val hlsHeaders = linkedMapOf<String, String>(
                "Referer" to (capturedHeader("Referer")
                    ?: iframeUrl.substringBefore("?").trimEnd('/') + "/"),
                "User-Agent" to userAgent
            )

            capturedHeader("Accept-Language")?.let {
                hlsHeaders["Accept-Language"] = it
            }

            /*
             * IMPORTANT:
             * Kinescope's signed manifest already contains the exact variant /
             * segment URLs generated by the browser. Do not parse/rebuild that
             * playlist here. CloudStream's player can consume the signed HLS
             * manifest directly and applies ExtractorLink.headers to the
             * manifest, variants and media segments.
             *
             * This also preserves cross-host URLs such as:
             *   river-*-kinescopecdn.net -> vbx-*-kinescopecdn.net
             * without M3u8Helper rewriting the playlist.
             */
            callback(
                newExtractorLink(
                    source = name,
                    name = "HintFilmİzle Kinescope",
                    url = nativeManifestUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    referer = hlsHeaders["Referer"] ?: iframeUrl
                    headers = hlsHeaders
                    quality = getQualityFromName(manifestUrl)
                }
            )
            true
        }.getOrDefault(false)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = runCatching {
            app.get(
                data,
                referer = "$mainUrl/",
                headers = mapOf(
                    "User-Agent" to
                        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
                )
            ).document
        }.getOrNull() ?: return false

        var found = false
        val players = linkedSetOf<String>()

        Log.d("HintFilmIzle", "FILM_DATA=$data")

        fun addUrl(value: String?, base: String = data) {
            if (value.isNullOrBlank()) return

            val cleaned = value
                .replace("\\/","/")
                .replace("\\u0026","&")
                .replace("&amp;","&")
                .trim()
                .trim('"', '\'')

            playerUrl(cleaned, base)?.let { url ->
                if (
                    !isIgnoredPlayer(url) &&
                    !isTrailerPlayer(url) &&
                    !url.startsWith(mainUrl, true)
                ) {
                    players.add(url)
                }
            }

            Regex("""https?://[^"'\\s<>]+""", RegexOption.IGNORE_CASE)
                .findAll(cleaned)
                .map { it.value.trimEnd('\\', '"', '\'', ')', ']', ';') }
                .mapNotNull { playerUrl(it, base) }
                .filter {
                    !isIgnoredPlayer(it) &&
                    !isTrailerPlayer(it) &&
                    !it.startsWith(mainUrl, true)
                }
                .forEach(players::add)
        }

        /*
         * Önce gerçek iframe/player URL'lerini topluyoruz. Kinescope olanlar
         * listenin başına alınır; böylece sayfadaki reklam MP4'ü "Direct"
         * kaynak olarak seçilip gerçek oynatıcıyı gölgelemez.
         */
        document.select(
            "iframe[src], iframe[data-src], iframe[data-url], iframe[data-iframe], " +
            "iframe[data-frame], frame[src], video[src], video[data-src], video[data-url], " +
            "video source[src], video source[data-src], video source[data-url], " +
            "a[href], a[data-url], a[data-embed], a[data-frame], a[data-video], " +
            "a[data-player], button[data-url], button[data-embed], button[data-frame], " +
            "button[data-video], button[data-player], [onclick], [data-url], " +
            "[data-embed], [data-frame], [data-video], [data-player]"
        ).forEach { element ->
            Log.d("HintFilmIzle", "ELEMENT tag=" + element.tagName() + " class=" + element.className() + " id=" + element.id())
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
            ).forEach { addUrl(it) }
        }

        Regex("""https?:\\?/\\?/[^"'\\s<>]+""", RegexOption.IGNORE_CASE)
            .findAll(document.html())
            .forEach { addUrl(it.value.replace("\\/","/")) }

        Log.d("HintFilmIzle", "PLAYER_LIST=" + players.joinToString(" || "))

        val kinescopePlayers = players.filter {
            it.contains("kinescope", true) ||
            it.contains("player.hintfilmizle.com", true)
        }
        Log.d("HintFilmIzle", "KINESCOPE_PLAYERS=" + kinescopePlayers.joinToString(" || "))

        val otherPlayers = players.filterNot {
            it.contains("kinescope", true) ||
            it.contains("player.hintfilmizle.com", true) ||
            isIgnoredPlayer(it)
        }

        /*
         * Kinescope'u generic extractor'dan geçirmiyoruz. Önce WebView ile
         * gerçek signed index.m3u8 yakalanıyor.
         */
        for (player in kinescopePlayers) {
            if (loadKinescope(player, data, subtitleCallback, callback)) {
                found = true
            }
        }

        /*
         * Kinescope başarılı olduysa parent HTML'deki doğrudan MP4/HLS
         * reklam URL'lerini kaynak olarak eklemiyoruz. Bu, senin gördüğün
         * "HintFilmİzle Direct -> 2004" durumunu engelliyor.
         */
        if (!found && kinescopePlayers.isEmpty()) {
            for (stream in directLinks(document.html())) {
                // Never expose social-media/ad/trailer media as the film source.
                // These pages contain unrelated short videos (e.g. X/Twitter),
                // which were previously being returned as "HintFilmİzle Direct".
                if (
                    stream.contains("kinescopecdn.net", true) ||
                    stream.contains("video.twimg.com", true) ||
                    stream.contains("twitter.com", true) ||
                    stream.contains("x.com", true) ||
                    stream.contains("t.co/", true) ||
                    stream.contains("youtube.com", true) ||
                    stream.contains("youtu.be", true) ||
                    stream.contains("facebook.com", true) ||
                    stream.contains("instagram.com", true) ||
                    stream.contains("ads.", true) ||
                    stream.contains("/ads/", true) ||
                    isTrailerPlayer(stream)
                ) continue

                found = true
                callback(newExtractorLink(
                    source = name,
                    name = "HintFilmİzle Direct",
                    url = stream,
                    type = if (stream.contains(".m3u8", true))
                        ExtractorLinkType.M3U8
                    else
                        ExtractorLinkType.VIDEO
                ) {
                    referer = data
                    quality = getQualityFromName(stream)
                })
            }
        }

        /*
         * Kinescope dışındaki sağlayıcılar eski generic extractor zincirinden
         * devam eder.
         */
        for (player in otherPlayers) {
            val loaded = runCatching {
                loadExtractor(
                    url = player,
                    referer = data,
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )
            }.getOrDefault(false)

            if (loaded) found = true

            val nested = runCatching {
                app.get(player, referer = data).document
            }.getOrNull()

            nested?.select(
                "iframe[src], iframe[data-src], iframe[data-frame], iframe[data-url], " +
                "video[src], video[data-src], video source[src], video source[data-src]"
            )?.forEach { element ->
                val nestedUrl = playerUrl(
                    element.attr("src")
                        .ifBlank { element.attr("data-src") }
                        .ifBlank { element.attr("data-frame") }
                        .ifBlank { element.attr("data-url") },
                    player
                ) ?: return@forEach

                if (
                    nestedUrl == player ||
                    nestedUrl.startsWith(mainUrl, true) ||
                    isIgnoredPlayer(nestedUrl) ||
                    isTrailerPlayer(nestedUrl)
                ) return@forEach

                val nestedLoaded = runCatching {
                    loadExtractor(
                        url = nestedUrl,
                        referer = player,
                        subtitleCallback = subtitleCallback,
                        callback = callback
                    )
                }.getOrDefault(false)

                if (nestedLoaded) found = true
            }

            directLinks(nested?.html().orEmpty()).forEach { stream ->
                if (
                    stream.contains("kinescopecdn.net", true) ||
                    isIgnoredPlayer(stream)
                ) return@forEach

                if (stream.contains(".m3u8", true)) {
                    val links = runCatching {
                        M3u8Helper.generateM3u8(
                            source = name,
                            streamUrl = stream,
                            referer = player,
                            headers = mapOf(
                                "Referer" to player,
                                "User-Agent" to
                                    "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
                                    "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
                                "Accept" to "*/*"
                            ),
                            name = "HintFilmİzle"
                        )
                    }.getOrDefault(emptyList())

                    if (links.isNotEmpty()) {
                        links.forEach(callback)
                        found = true
                    }
                } else {
                    callback(newExtractorLink(
                        source = name,
                        name = "HintFilmİzle Direct",
                        url = stream,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        referer = player
                        quality = getQualityFromName(stream)
                    })
                    found = true
                }
            }
        }

        return found
    }

}
