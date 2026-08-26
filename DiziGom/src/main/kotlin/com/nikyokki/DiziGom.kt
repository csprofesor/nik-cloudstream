package com.nikyokki

import android.util.Log
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

class DiziGom : MainAPI() {
    override var mainUrl = "https://www.dizigom.love"
    override var name = "DiziGom"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries)

    private val genreRoutes = linkedMapOf(
        "Aile" to "aile", "Aksiyon" to "aksiyon", "Animasyon" to "animasyon",
        "Belgesel" to "belgesel", "Bilim Kurgu" to "bilim-kurgu", "Biyografi" to "biyografi",
        "Dram" to "dram", "Fantastik" to "fantastik", "Gençlik" to "genclik",
        "Gerilim" to "gerilim", "Gizem" to "gizem", "Komedi" to "komedi",
        "Korku" to "korku", "Macera" to "macera", "Polisiye" to "polisiye",
        "Romantik" to "romantik", "Savaş" to "savas", "Suç" to "suc", "Tarih" to "tarih"
    )

    override val mainPage = mainPageOf(
        *genreRoutes.map { (genre, slug) -> "$mainUrl/tur/$slug/" to genre }.toTypedArray()
    )

    private fun cleanUrl(value: String?): String? = value
        ?.replace("\\/", "/")
        ?.replace("\\u0026", "&")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let { if (it.startsWith("//")) "https:$it" else it }
        ?.let { fixUrlNull(it) }

    private fun Element.backgroundUrl(): String? {
        val style = attr("style")
        val match = Regex("url\\((?:\\\"|')?([^\\\"')]+)", RegexOption.IGNORE_CASE).find(style)
        return cleanUrl(match?.groupValues?.getOrNull(1))
    }

    private fun Element.posterUrl(): String? {
        val img = selectFirst("img")
        val attributes = listOf(
            "data-poster", "data-bg", "data-background", "data-image", "data-img",
            "data-thumb", "data-thumbnail", "data-cover", "data-url",
            "data-src", "data-lazy-src", "data-original", "data-wpfc-original-src",
            "data-lazyload", "data-lazy", "data-image-url", "data-poster-url",
            "data-srcset", "data-lazy-srcset", "srcset", "src"
        )

        val candidates = sequence {
            for (attribute in attributes) {
                yield(attr(attribute))
                if (img != null) yield(img.attr(attribute))
            }
            yield(backgroundUrl())
            if (img != null) {
                yield(img.attr("style"))
                yield(img.parent()?.attr("style"))
            }
        }

        return candidates
            .flatMap { value ->
                sequenceOf(value)
                    .filter { !it.isNullOrBlank() }
                    .flatMap { raw ->
                        raw!!.split(",").asSequence().map { it.trim().substringBefore(" ") }
                    }
            }
            .mapNotNull { cleanUrl(it) }
            .firstOrNull { url ->
                !url.equals("data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw==", true) &&
                    !url.startsWith("data:image/", true)
            }
    }

    private fun Element.findCard(): Element {
        if (hasClass("episode-box") || hasClass("single-item")) return this
        return generateSequence(this as Element?) { it.parent() }
            .take(12)
            .firstOrNull { it.hasClass("episode-box") || it.hasClass("single-item") }
            ?: this
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val card = findCard()
        val title = sequenceOf(
            card.selectFirst("div.serie-name a")?.text(),
            card.selectFirst(".serie-name")?.text(),
            card.selectFirst("a[title]")?.attr("title"),
            card.selectFirst("img")?.attr("alt"),
            card.selectFirst("img")?.attr("title"),
            card.attr("title")
        ).mapNotNull { it?.trim()?.takeIf { value -> value.isNotBlank() } }.firstOrNull() ?: return null

        val href = sequenceOf(
            card.selectFirst("a[href*='/diziler/']")?.attr("href"),
            card.selectFirst("a[href*='/dizi/']")?.attr("href"),
            card.selectFirst("a")?.attr("href"),
            attr("href")
        ).mapNotNull { cleanUrl(it) }.firstOrNull() ?: return null

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            posterUrl = card.posterUrl()
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = if (page <= 1) request.data else request.data.trimEnd('/') + "/page/$page/"
        val document = runCatching { app.get(pageUrl, referer = "$mainUrl/").document }.getOrNull()
            ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)

        val results = (document.select("div.episode-box, div.single-item, a[href*='/diziler/'], a[href*='/dizi/']")
            .mapNotNull { it.toMainPageResult() })
            .distinctBy { it.url }

        Log.d("DiziGom", "${request.name}: page=$page count=${results.size} url=$pageUrl")
        return newHomePageResponse(request.name, results, hasNext = results.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get(
            "$mainUrl/?s=${query.trim().replace(" ", "+")}",
            referer = "$mainUrl/"
        ).document
        return document.select("div.episode-box, div.single-item, a[href*='/diziler/'], a[href*='/dizi/']")
            .mapNotNull { it.toMainPageResult() }
            .distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    private fun Element.firstText(vararg selectors: String): String? = selectors.asSequence()
        .mapNotNull { selectFirst(it)?.text()?.trim() }
        .firstOrNull { it.isNotBlank() }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, referer = "$mainUrl/").document
        val title = document.firstText("div.serieTitle h1", ".serieTitle h1", "h1.entry-title", "article h1", "h1")
            ?: return null

        val poster = document.selectFirst("meta[property='og:image']")?.attr("content")?.let { cleanUrl(it) }
            ?: document.selectFirst("div.seriePoster")?.posterUrl()
            ?: document.selectFirst("div.seriePoster img")?.posterUrl()
            ?: document.selectFirst("[class*='seriePoster']")?.posterUrl()
            ?: document.selectFirst("article img, .entry-content img")?.posterUrl()
            ?: document.selectFirst("img")?.posterUrl()

        val description = document.firstText(
            "div.serieDescription p", ".serieDescription p", ".description p", ".entry-content p"
        )

        val year = Regex("(?:Yapım Yılı|Yapim Yili)\\s*:?\\s*(\\d{4})", RegexOption.IGNORE_CASE)
            .find(document.text())?.groupValues?.getOrNull(1)?.toIntOrNull()
        val rating = Regex("(?:IMDB|IMDb)\\s*:?\\s*([0-9]+(?:[.,][0-9]+)?)", RegexOption.IGNORE_CASE)
            .find(document.text())?.groupValues?.getOrNull(1)

        val tags = document.select("div.genreList a, .genreList a")
            .map { it.text().trim() }.filter { it.isNotBlank() }.distinct()

        val actors = document.select("div.owl-stage a, .cast a, .actors a")
            .mapNotNull { link ->
                val actor = link.text().trim()
                if (actor.isBlank()) null else Actor(actor, link.posterUrl())
            }.distinctBy { it.name }

        val episodes = document.select("div.bolumust, a[href*='-sezon-'][href*='-bolum']")
            .mapNotNull { element ->
                val link = if (element.tagName() == "a") element else element.selectFirst("a") ?: return@mapNotNull null
                val href = cleanUrl(link.attr("href")) ?: return@mapNotNull null
                val source = "${element.text()} ${link.attr("title")}".trim()
                val season = Regex("(\\d+)\\s*\\.?\\s*Sezon", RegexOption.IGNORE_CASE)
                    .find(source)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: Regex("-(\\d+)-sezon-", RegexOption.IGNORE_CASE).find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
                val episode = Regex("(\\d+)\\s*\\.?\\s*Bölüm", RegexOption.IGNORE_CASE)
                    .find(source)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: Regex("-(\\d+)-bolum", RegexOption.IGNORE_CASE).find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
                if (season == null || episode == null) return@mapNotNull null
                newEpisode(href) {
                    name = element.selectFirst("div.bolum-ismi")?.text()?.trim() ?: element.text().trim()
                    this.season = season
                    this.episode = episode
                }
            }.distinctBy { it.data }
            .sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            posterUrl = poster
            this.year = year
            plot = description
            this.tags = tags
            score = com.lagradost.cloudstream3.Score.from10(rating)
            addActors(actors)
        }
    }

    private fun extractPlayerUrl(document: org.jsoup.nodes.Document): String? {
        return document.select("iframe[src], frame[src]")
            .mapNotNull { cleanUrl(it.attr("src")) }
            .firstOrNull { it.contains("s.php", true) || it.contains("pilayerplay", true) }
            ?: Regex("https?://[^\\\"'\\s<>]+/s\\.php\\?[^\\\"'\\s<>]+", RegexOption.IGNORE_CASE)
                .find(document.html())?.value?.let { cleanUrl(it) }
    }

    private fun extractPlayerStream(html: String): String? {
        val stream = Regex(
            "[\\\"']stream[\\\"']\\s*:\\s*[\\\"']([^\\\"']+)[\\\"']",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.getOrNull(1)
        if (!stream.isNullOrBlank()) return cleanUrl(stream)

        return Regex(
            "https?://[^\\\"'\\s<>]+/api/stream\\.php(?:\\?[^\\\"'\\s<>]+)?",
            RegexOption.IGNORE_CASE
        ).find(html)?.value?.let { cleanUrl(it) }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("DiziGom", "Resolving episode: $data")
        val document = runCatching { app.get(data, referer = "$mainUrl/").document }.getOrNull() ?: return false

        val playerUrl = extractPlayerUrl(document)
        if (!playerUrl.isNullOrBlank()) {
            val playerResponse = runCatching { app.get(playerUrl, referer = data) }.getOrNull()
            val playerHtml = playerResponse?.text.orEmpty()
            val streamUrl = extractPlayerStream(playerHtml)

            if (!streamUrl.isNullOrBlank()) {
                Log.d("DiziGom", "PilayerPlay stream bulundu")
                callback(
                    newExtractorLink(
                        source = name,
                        name = "DiziGom 1080p",
                        url = streamUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        referer = playerUrl
                        quality = 1080
                    }
                )
                return true
            }

            val directPlayerUrl = Regex(
                "https?://[^\\\"'\\s<>]+(?:\\.m3u8(?:\\?[^\\\"'\\s<>]*)?|\\.mp4(?:\\?[^\\\"'\\s<>]*)?)",
                RegexOption.IGNORE_CASE
            ).findAll(playerHtml).map { cleanUrl(it.value) }.filterNotNull().distinct().toList()

            for (stream in directPlayerUrl) {
                callback(
                    newExtractorLink(source = name, name = "DiziGom", url = stream,
                        type = if (stream.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                        referer = playerUrl
                        quality = getQualityFromName(stream)
                    }
                )
            }
            if (directPlayerUrl.isNotEmpty()) return true
        }

        val directUrls = Regex(
            "https?://[^\\\"'\\s<>]+(?:\\.m3u8(?:\\?[^\\\"'\\s<>]*)?|\\.mp4(?:\\?[^\\\"'\\s<>]*)?)",
            RegexOption.IGNORE_CASE
        ).findAll(document.html()).map { cleanUrl(it.value) }.filterNotNull().distinct().toList()

        for (stream in directUrls) {
            callback(
                newExtractorLink(source = name, name = "DiziGom", url = stream,
                    type = if (stream.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                    referer = data
                    quality = getQualityFromName(stream)
                }
            )
        }
        return directUrls.isNotEmpty()
    }
}
