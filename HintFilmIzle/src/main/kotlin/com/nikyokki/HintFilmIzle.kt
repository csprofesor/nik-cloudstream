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
        val document = runCatching { app.get(pageUrl, referer = "$mainUrl/").document }.getOrNull()
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
                val response = app.get(url, referer = "$mainUrl/")
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
                    app.get(url, referer = "$mainUrl/film").document,
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
        /*
         * Kinescope embeds expose the playable source in:
         *
         *   var playerOptions = {
         *       playlist: [{
         *           sources: {
         *               hls: { src: "https://.../master.m3u8?...&sign=..." },
         *               shakahls: { src: "https://.../master.m3u8?...&sign=..." }
         *           }
         *       }]
         *   }
         *
         * This is the same structure used by the current Kinescope web player.
         * Do not manufacture a master.m3u8 URL from the embed id: the CDN URL
         * carries a short-lived signed query string and that is what the browser
         * actually plays.
         */
        val normalized = html
            .replace("\\u0026", "&")
            .replace("\\u003F", "?")
            .replace("\\/", "/")
            .replace("\\\"", "\"")
            .replace("&amp;", "&")

        fun decodeCandidate(value: String): String? {
            var candidate = value
                .trim()
                .trimEnd('"', '\'', ')', ']', '}')
                .replace("\\u0026", "&")
                .replace("\\/", "/")
                .replace("\\u003F", "?")
                .replace("&amp;", "&")

            if (candidate.contains("\\u")) {
                candidate = runCatching {
                    Regex("""\\u([0-9a-fA-F]{4})""").replace(candidate) {
                        it.groupValues[1].toInt(16).toChar().toString()
                    }
                }.getOrDefault(candidate)
            }

            return cleanUrl(candidate)
        }

        val candidates = linkedSetOf<String>()

        /*
         * First target playerOptions directly. This is more reliable than
         * looking for arbitrary m3u8 strings in the entire document because
         * it follows Kinescope's actual player data model.
         */
        val sourcePatterns = listOf(
            Regex(
                """["']hls["']\s*:\s*\{\s*["']src["']\s*:\s*["']([^"']+)["']""",
                RegexOption.IGNORE_CASE
            ),
            Regex(
                """["']shakahls["']\s*:\s*\{\s*["']src["']\s*:\s*["']([^"']+)["']""",
                RegexOption.IGNORE_CASE
            ),
            Regex(
                """\bhls\s*:\s*\{\s*src\s*:\s*["']([^"']+)["']""",
                RegexOption.IGNORE_CASE
            ),
            Regex(
                """\bshakahls\s*:\s*\{\s*src\s*:\s*["']([^"']+)["']""",
                RegexOption.IGNORE_CASE
            )
        )

        sourcePatterns.forEach { regex ->
            regex.findAll(normalized).forEach { match ->
                match.groupValues.getOrNull(1)
                    ?.let(::decodeCandidate)
                    ?.takeIf { it.contains(".m3u8", true) }
                    ?.let(candidates::add)
            }
        }

        /*
         * Fallback for player versions that serialize playerOptions slightly
         * differently. Search for every absolute HLS manifest, including
         * escaped JSON URLs.
         */
        Regex(
            """https?://[^"'\s<>]+?\.m3u8(?:\?[^"'\s<>]*)?""",
            RegexOption.IGNORE_CASE
        ).findAll(normalized).forEach { match ->
            decodeCandidate(match.value)
                ?.let(candidates::add)
        }

        /*
         * Some versions HTML-encode the ampersands before the script reaches
         * the DOM. Decode those as a final pass.
         */
        Regex(
            """https?://[^"'\s<>]+?\.m3u8(?:\?[^"'\s<>]*)?""",
            RegexOption.IGNORE_CASE
        ).findAll(html.replace("&amp;", "&")).forEach { match ->
            decodeCandidate(match.value)
                ?.let(candidates::add)
        }

        /*
         * Prefer a signed Kinescope CDN manifest. The signature is generated
         * by the player and expires, so it must always be taken from the
         * current embed response rather than cached or synthesized.
         */
        return candidates.firstOrNull {
            it.contains(".m3u8", true) &&
                it.contains("expires=", true) &&
                it.contains("sign=", true)
        } ?: candidates.firstOrNull {
            it.contains(".m3u8", true) &&
                it.contains("kinescopecdn.net", true)
        } ?: candidates.firstOrNull()
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
                    // Prefer the exact signed manifest produced by the embed/player.
                    // The expires/sign/token values are short-lived, so do not synthesize
                    // or cache them. Only use the public /master.m3u8 fallback when the
                    // current embed response exposes no manifest at all.
                    val embeddedKinescopeStream = extractKinescopeHls(playerHtml)
                        ?: directLinks(
                            playerHtml
                                .replace("\\u0026", "&")
                                .replace("\\/","/")
                        ).firstOrNull { it.contains(".m3u8", true) }

                    /*
                     * Do not fabricate a Kinescope master URL from the embed id.
                     * The CDN/project embed can use a different video identifier
                     * and the actual HLS URL is signed. Only use the URL extracted
                     * from the current player response.
                     */
                    val kinescopeEntryPoint = embeddedKinescopeStream

                    // Do NOT fetch the HLS URL here and parse its response.
                    // Kinescope's /master.m3u8 endpoint is itself the playable HLS
                    // entry point; its response contains relative .ts/.m3u8 URLs,
                    // not another absolute manifest. The previous resolver therefore
                    // returned null even though the browser could play the same video.
                    //
                    // Let ExoPlayer follow Kinescope's redirect to the short-lived
                    // kinescopecdn.net manifest. The browser trace shows that the CDN
                    // validates the request with the embed host as Origin and root
                    // Referer, so those headers are attached to the media link.
                    val kinescopeStream = kinescopeEntryPoint

                    if (kinescopeStream != null) {
                        found = true
                        val playerOrigin = runCatching {
                            URI(player).let { "${it.scheme}://${it.host}" }
                        }.getOrDefault("https://kinescope.io")

                        // Chrome uses strict-origin-when-cross-origin here. The
                        // cross-origin request therefore normally carries the iframe
                        // origin as Referer, not the complete signed embed URL. Also,
                        // the media request can be same-origin with the iframe CDN host.
                        callback(newExtractorLink(
                            source = name,
                            name = "HintFilmİzle Kinescope",
                            url = kinescopeStream,
                            type = ExtractorLinkType.M3U8
                        ) {
                            /*
                             * Browser trace is the important distinction here:
                             * the actual media host is vbx-25.kinescopecdn.net while
                             * the iframe document is river-3-329.kinescopecdn.net.
                             *
                             * For a <video>/HLS media request Chrome does NOT need us
                             * to invent CORS request headers. In particular, sending
                             * an Origin/Sec-Fetch-Mode/Sec-Fetch-Dest combination that
                             * was not present in the browser request can make a CDN
                             * signature/access rule reject the request with 403.
                             *
                             * CloudStream's ExtractorLink.referer is enough to put
                             * the browser-style origin Referer on the manifest and
                             * its child segment requests.
                             */
                            referer = "$playerOrigin/"
                            headers = mapOf(
                                // Kinescope CDN medya isteklerinde embed iframe'in
                                // Origin ve Referer bağlamını kontrol edebiliyor.
                                // Chrome'un cross-origin HLS isteğinde görülen model:
                                // Origin = iframe origin, Referer = iframe origin.
                                "Origin" to playerOrigin.removeSuffix("/"),
                                "Referer" to "$playerOrigin/"
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
