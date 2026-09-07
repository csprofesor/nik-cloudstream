package com.nikyokki

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.Episode
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
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

class HDFilmSitesi : MainAPI() {
    override var mainUrl = "https://dizifilmizle.to"
    override var name = "HDFilmSitesi"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie)

    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    override val mainPage = mainPageOf(
        "${mainUrl}/tur/aile" to "Aile",
        "${mainUrl}/tur/aksiyon" to "Aksiyon",
        "${mainUrl}/tur/animasyon" to "Animasyon",
        "${mainUrl}/tur/bilim-kurgu" to "Bilim Kurgu",
        "${mainUrl}/tur/belgesel" to "Belgesel",
        "${mainUrl}/tur/dram" to "Dram",
        "${mainUrl}/tur/fantastik" to "Fantastik",
        "${mainUrl}/tur/gerilim" to "Gerilim",
        "${mainUrl}/tur/gizem" to "Gizem",
        "${mainUrl}/tur/komedi" to "Komedi",
        "${mainUrl}/tur/korku" to "Korku",
        "${mainUrl}/tur/macera" to "Macera",
        "${mainUrl}/tur/romantik" to "Romantik",
        "${mainUrl}/tur/savas" to "Savaş",
        "${mainUrl}/tur/suc" to "Suç",
        "${mainUrl}/tur/western" to "Western",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data.trimEnd('/')}/page/$page"
        val document = app.get(url, headers = browserHeaders, referer = "$mainUrl/").document

        val cards = document.select("div.movie_box").mapNotNull { it.toMainPageResult() }
            .ifEmpty {
                document.select("a[href*='/film/']")
                    .mapNotNull { it.toFilmLinkResult() }
            }
            .distinctBy { it.url }
            .toMutableList()

        for (card in cards) {
            if (card.posterUrl.isNullOrBlank()) {
                runCatching {
                    val detail = app.get(card.url, headers = browserHeaders, referer = "$mainUrl/").document
                    card.posterUrl = fixUrlNull(
                        detail.selectFirst("meta[property='og:image']")?.attr("content")
                            ?: detail.selectFirst("meta[name='twitter:image']")?.attr("content")
                            ?: detail.selectFirst("[property='og:image']")?.attr("content")
                    )
                }
            }
            card.posterHeaders = browserHeaders + ("Referer" to "${mainUrl}/")
        }

        return newHomePageResponse(request.name, cards)
    }

    private fun Element.findPosterUrl(): String? {
        fun normalize(raw: String): String? {
            val value = raw
                .trim()
                .removePrefix("\"")
                .removeSuffix("\"")
                .removePrefix("'")
                .removeSuffix("'")
                .trim()
                .substringBefore(Regex("\\s+"))
                .trim()
            if (value.isBlank() || value.startsWith("data:image")) return null
            return fixUrlNull(value)
        }

        var current: Element? = this
        repeat(8) {
            val image = current?.selectFirst("img")
            if (image != null) {
                val candidates = sequenceOf(
                    image.attr("data-src"),
                    image.attr("data-lazy-src"),
                    image.attr("data-original"),
                    image.attr("data-image"),
                    image.attr("data-srcset"),
                    image.attr("srcset"),
                    image.attr("src")
                )

                for (candidate in candidates) {
                    if (candidate.isBlank()) continue
                    val urls = if (candidate.contains(',')) candidate.split(',') else listOf(candidate)
                    for (url in urls) {
                        normalize(url)?.let { return it }
                    }
                }
            }

            val style = current?.attr("style").orEmpty()
            val background = Regex("url\\(['\\\"]?([^'\\\")]+)")
                .find(style)
                ?.groupValues
                ?.getOrNull(1)
            normalize(background.orEmpty())?.let { return it }

            current = current?.parent()
        }
        return null
    }

    private fun Element.findCardTitle(): String? {
        return sequenceOf(
            selectFirst("h2")?.text(),
            selectFirst("h3")?.text(),
            selectFirst("h4")?.text(),
            selectFirst("[class*=title]")?.text(),
            selectFirst("img[alt]")?.attr("alt")?.substringBeforeLast(" izle"),
            selectFirst("a[title]")?.attr("title"),
            text()
        ).mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }.firstOrNull()
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val href = fixUrlNull(selectFirst("a[href]")?.attr("href")) ?: return null
        val title = findCardTitle() ?: return null
        val posterUrl = findPosterUrl()
        val score = selectFirst("span.box.imdb, [class*=imdb], [class*=rating]")?.text()?.trim()
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.posterHeaders = browserHeaders + ("Referer" to "${mainUrl}/")
            this.score = Score.from10(score)
        }
    }

    private fun Element.toFilmLinkResult(): SearchResponse? {
        val href = fixUrlNull(attr("href")) ?: return null
        if (!href.contains("/film/")) return null
        val title = findCardTitle() ?: return null
        val posterUrl = findPosterUrl()
        val score = selectFirst("span.box.imdb, [class*=imdb], [class*=rating]")?.text()?.trim()
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.posterHeaders = browserHeaders
            this.score = Score.from10(score)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/arama/${query}", headers = browserHeaders, referer = "$mainUrl/").document
        return document.select("div.movie_box").mapNotNull { it.toMainPageResult() }
            .ifEmpty { document.select("a[href*='/film/']").mapNotNull { it.toFilmLinkResult() } }
            .distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = browserHeaders, referer = "$mainUrl/").document
        val title = sequenceOf(
            document.selectFirst("h1")?.text(),
            document.selectFirst("meta[property='og:title']")?.attr("content"),
            document.selectFirst("meta[name='twitter:title']")?.attr("content")
        ).mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
            .map { it.substringBefore(" izle").trim() }.firstOrNull() ?: return null

        val poster = fixUrlNull(
            document.selectFirst("meta[property='og:image']")?.attr("content")
                ?: document.selectFirst("meta[name='twitter:image']")?.attr("content")
        )
        val description = document.selectFirst("div[itemprop='description']")?.text()?.substringAfter("⭐")
            ?.substringAfter("izleyin.")?.substringAfter("konusu:")?.trim()
        val year = document.selectFirst("span[itemprop='name']")?.text()?.trim()?.toIntOrNull()
            ?: Regex("\\b(19|20)\\d{2}\\b").find(document.selectFirst("h1")?.parent()?.text().orEmpty())?.value?.toIntOrNull()
        val tags = document.select("a[rel='category']").map { it.text().substringBefore(" Filmleri") }
        val rating = document.selectFirst("div.puanlar span")?.text()?.trim()?.substringAfter("IMDb")
        val duration = document.selectFirst("span[itemprop='duration']")?.text()?.split(" ")?.first()?.trim()?.toIntOrNull()
        val actors = document.select("a.cast").map { Actor(it.text(), it.attr("href")) }
        val trailer = fixUrlNull(document.selectFirst("[property='og:video']")?.attr("content"))

        if (document.selectFirst("div.part_buton_sec")?.text()?.contains("Sezon") == true) {
            val episodes = mutableListOf<Episode>()
            val iframeSkici = IframeKodlayici()
            val pdataMatches = Regex("""pdata\[\'(.*?)'\] = \'(.*?)\';""").findAll(document.html())
            val pdataList = pdataMatches.map { it.destructured }.toList()
            for (pdata in pdataList) {
                val key = pdata.component1()
                val value = pdata.component2()
                val iframeData = iframeSkici.iframeCoz(value!!)
                val iframeLink = app.get(iframeData, headers = browserHeaders, referer = "${mainUrl}/").url.toString()
                val sz_num = key.substringAfter("prt_").substringBefore("sezon").toIntOrNull() ?: 1
                var ep_num = key.substringAfter("sezon").toIntOrNull()
                if (ep_num != null) ep_num += 1 else ep_num = 1
                episodes.add(newEpisode(iframeLink) {
                    this.name = "${sz_num}. Sezon ${ep_num}. Bölüm"
                    this.season = sz_num
                    this.episode = ep_num
                })
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.posterHeaders = browserHeaders + ("Referer" to "${mainUrl}/")
                this.plot = description
                this.year = year
                this.tags = tags
                this.score = Score.from10(rating)
                this.duration = duration
                addActors(actors)
                addTrailer(trailer)
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.posterHeaders = browserHeaders
                this.plot = description
                this.year = year
                this.tags = tags
                this.score = Score.from10(rating)
                this.duration = duration
                addActors(actors)
                addTrailer(trailer)
            }
        }
    }

    private suspend fun resolveVidMixi(
        embedUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val headers = browserHeaders + mapOf(
            "Origin" to "https://vidmixi.com",
            "Referer" to embedUrl
        )

        val embed = runCatching {
            app.get(
                embedUrl,
                headers = headers,
                referer = "https://vidmixi.com/"
            )
        }.getOrNull() ?: return false

        val html = embed.text
            .replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\x2F", "/")

        val listUrl = Regex(
            "https?://vidmixi\\.com/list/[A-Za-z0-9+/=_-]+",
            RegexOption.IGNORE_CASE
        ).find(html)?.value
            ?: Regex(
                "['\\\"](/list/[A-Za-z0-9+/=_-]+)['\\\"]",
                RegexOption.IGNORE_CASE
            ).find(html)?.groupValues?.getOrNull(1)?.let { "https://vidmixi.com$it" }
            ?: Regex(
                "(?:url|src|playlist|manifest)\\s*[:=]\\s*['\\\"]([^'\\\"]*?/list/[A-Za-z0-9+/=_-]+)['\\\"]",
                RegexOption.IGNORE_CASE
            ).find(html)?.groupValues?.getOrNull(1)?.let {
                if (it.startsWith("http")) it else "https://vidmixi.com$it"
            }
            ?: return false

        val manifestResponse = runCatching {
            app.get(
                listUrl,
                headers = headers,
                referer = embedUrl
            )
        }.getOrNull() ?: return false

        if (!manifestResponse.text.trimStart().startsWith("#EXTM3U")) return false

        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = "VidMixi",
                url = manifestResponse.url.toString().ifBlank { listUrl },
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = embedUrl
                this.headers = headers
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("HDS", "loadLinks -> $data")

        // Direct provider URL.
        if (data.contains("vidmixi.com", ignoreCase = true) &&
            resolveVidMixi(data, subtitleCallback, callback)
        ) return true

        val document = runCatching {
            app.get(data, headers = browserHeaders, referer = "${mainUrl}/").document
        }.getOrNull() ?: return false

        // The old parser used an incorrectly escaped raw regex. This is the exact
        // assignment format used by the current site.
        val pdataRegex = Regex("""pdata\['(.*?)'\]\s*=\s*'(.*?)';""")
        val encoded = pdataRegex.findAll(document.html()).map { it.groupValues[2] }.toList()

        val directIframes = document.select("iframe[src], iframe[data-src]")
            .mapNotNull { iframe ->
                fixUrlNull(iframe.attr("src").ifBlank { iframe.attr("data-src") })
            }

        val candidates = (encoded.mapNotNull { value ->
            runCatching { IframeKodlayici().iframeCoz(value) }.getOrNull()
        } + directIframes).distinct()

        for (candidate in candidates) {
            val providerUrl = runCatching {
                app.get(candidate, headers = browserHeaders, referer = "${mainUrl}/").url.toString()
            }.getOrDefault(candidate)

            when {
                providerUrl.contains("vidmixi.com", ignoreCase = true) -> {
                    if (resolveVidMixi(providerUrl, subtitleCallback, callback)) return true
                }

                providerUrl.contains("vidmody", ignoreCase = true) -> {
                    val player = runCatching {
                        app.get(providerUrl, headers = browserHeaders, referer = "${mainUrl}/").document
                    }.getOrNull()
                    val id = player?.selectFirst("script")?.text()?.let { script ->
                        Regex("var\\s+id\\s*=\\s*['\"]([^'\"]+)['\"]").find(script)?.groupValues?.getOrNull(1)
                    }
                    if (!id.isNullOrBlank()) {
                        M3u8Helper.generateM3u8(
                            "VidMody",
                            "https://vidmody.com/vs/$id",
                            "${mainUrl}/",
                            headers = browserHeaders
                        ).forEach(callback)
                        return true
                    }
                }

                providerUrl.contains("vidlop", ignoreCase = true) -> {
                    val id = providerUrl.substringAfterLast("/")
                    val vidUrl = runCatching {
                        app.post(
                            "https://vidlop.com/player/index.php?data=$id&do=getVideo",
                            headers = browserHeaders + ("X-Requested-With" to "XMLHttpRequest"),
                            referer = "${mainUrl}/"
                        ).parsedSafe<VidLop>()?.securedLink
                    }.getOrNull()
                    if (!vidUrl.isNullOrBlank()) {
                        callback.invoke(newExtractorLink(
                            source = this.name,
                            name = "VidLop",
                            url = vidUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = providerUrl
                            this.headers = browserHeaders
                            this.quality = Qualities.Unknown.value
                        })
                        return true
                    }
                }

                else -> {
                    if (runCatching { loadExtractor(providerUrl, subtitleCallback, callback) }.getOrDefault(false)) {
                        return true
                    }
                }
            }
        }

        return false
    }

    data class VidLop(@JsonProperty("hls") val hls: Boolean? = null, @JsonProperty("securedLink") val securedLink: String? = null)
}