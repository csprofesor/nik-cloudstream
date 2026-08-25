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
import com.lagradost.cloudstream3.utils.JsUnpacker
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
        "Aile" to "aile",
        "Aksiyon" to "aksiyon",
        "Animasyon" to "animasyon",
        "Belgesel" to "belgesel",
        "Bilim Kurgu" to "bilim-kurgu",
        "Biyografi" to "biyografi",
        "Dram" to "dram",
        "Fantastik" to "fantastik",
        "Gençlik" to "genclik",
        "Gerilim" to "gerilim",
        "Gizem" to "gizem",
        "Komedi" to "komedi",
        "Korku" to "korku",
        "Macera" to "macera",
        "Polisiye" to "polisiye",
        "Romantik" to "romantik",
        "Savaş" to "savas",
        "Suç" to "suc",
        "Tarih" to "tarih"
    )

    override val mainPage = mainPageOf(
        *genreRoutes.map { (genre, slug) -> "$mainUrl/tur/$slug/" to genre }.toTypedArray()
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = if (page <= 1) request.data else request.data.trimEnd('/') + "/page/$page/"
        val document = runCatching { app.get(pageUrl, referer = "$mainUrl/").document }.getOrNull()

        if (document == null) {
            Log.d("DiziGom", "${request.name}: document alınamadı url=$pageUrl")
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }

        // The current DiziGom markup may contain episode-box wrappers, but on
        // some genre/list pages the series cards are exposed directly as links.
        // Do not use ?: on mapNotNull here: an empty first result must fall back
        // to the direct /diziler/ links.
        val boxedResults = document.select("div.episode-box")
            .mapNotNull { it.toMainPageResult() }
            .distinctBy { it.url }

        val directResults = document.select("a[href*='/diziler/']")
            .mapNotNull { it.toMainPageResult() }
            .distinctBy { it.url }

        val results = (boxedResults + directResults)
            .distinctBy { it.url }

        Log.d("DiziGom", "${request.name}: page=$page count=${results.size} boxed=${boxedResults.size} direct=${directResults.size} url=$pageUrl")
        return newHomePageResponse(request.name, results, hasNext = results.isNotEmpty())
    }

    private fun Element.posterUrl(): String? {
        val img = selectFirst("img") ?: return null
        return sequenceOf(
            img.attr("data-src"),
            img.attr("data-lazy-src"),
            img.attr("data-original"),
            img.attr("data-image"),
            img.attr("src")
        ).firstOrNull { it.isNotBlank() }
            ?.substringBefore(",")
            ?.trim()
            ?.substringBefore(" ")
            ?.let { fixUrlNull(it) }
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val card = if (hasClass("episode-box")) this else generateSequence(this as Element?) { it.parent() }
            .take(10)
            .firstOrNull { it.hasClass("episode-box") }
            ?: this

        val title = sequenceOf(
            card.selectFirst("div.serie-name a")?.text(),
            card.selectFirst(".serie-name")?.text(),
            card.selectFirst("img")?.attr("alt"),
            card.selectFirst("img")?.attr("title"),
            card.attr("title"),
            selectFirst("img")?.attr("alt")
        )
            .mapNotNull { it?.trim()?.takeIf { value -> value.isNotBlank() } }
            .firstOrNull()
            ?: return null

        val href = sequenceOf(
            card.selectFirst("a[href*='/diziler/']")?.attr("href"),
            card.selectFirst("a[href*='/dizi/']")?.attr("href"),
            card.selectFirst("a")?.attr("href"),
            attr("href")
        )
            .mapNotNull { it?.trim()?.takeIf { value -> value.isNotBlank() } }
            .firstOrNull()
            ?.let { fixUrlNull(it) }
            ?: return null

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            posterUrl = card.posterUrl()
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get(
            "$mainUrl/?s=${query.trim().replace(" ", "+")}",
            referer = "$mainUrl/"
        ).document

        return document.select("div.episode-box, div.single-item, a[href*='/diziler/']")
            .mapNotNull { it.toMainPageResult() }
            .distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    private fun Element.backgroundPoster(): String? {
        val style = attr("style")
        val background = Regex("url\\((?:\\\"|')?([^\\\"')]+)", RegexOption.IGNORE_CASE)
            .find(style)?.groupValues?.getOrNull(1)
        return background?.let { fixUrlNull(it) }
    }

    private fun Element.firstText(vararg selectors: String): String? = selectors.asSequence()
        .mapNotNull { selectFirst(it)?.text()?.trim() }
        .firstOrNull { it.isNotBlank() }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, referer = "$mainUrl/").document
        val title = document.firstText(
            "div.serieTitle h1",
            ".serieTitle h1",
            "h1.entry-title",
            "article h1",
            "h1"
        ) ?: return null

        val poster = document.selectFirst("div.seriePoster")?.backgroundPoster()
            ?: document.selectFirst("div.seriePoster img")?.posterUrl()
            ?: document.selectFirst("meta[property='og:image']")?.attr("content")?.let { fixUrlNull(it) }

        val description = document.firstText(
            "div.serieDescription p",
            ".serieDescription p",
            ".description p",
            ".entry-content p"
        )

        val year = Regex("(?:Yapım Yılı|Yapim Yili)\\s*:?\\s*(\\d{4})", RegexOption.IGNORE_CASE)
            .find(document.text())?.groupValues?.getOrNull(1)?.toIntOrNull()

        val rating = Regex("(?:IMDB|IMDb)\\s*:?\\s*([0-9]+(?:[.,][0-9]+)?)", RegexOption.IGNORE_CASE)
            .find(document.text())?.groupValues?.getOrNull(1)

        val tags = document.select("div.genreList a, .genreList a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val actors = document.select("div.owl-stage a, .cast a, .actors a")
            .mapNotNull { link ->
                val actor = link.text().trim()
                if (actor.isBlank()) null else Actor(actor, link.selectFirst("img")?.posterUrl())
            }
            .distinctBy { it.name }

        val episodes = document.select("div.bolumust, a[href*='-sezon-'][href*='-bolum']")
            .mapNotNull { element ->
                val link = if (element.tagName() == "a") element else element.selectFirst("a") ?: return@mapNotNull null
                val href = fixUrlNull(link.attr("href")) ?: return@mapNotNull null
                val source = "${element.text()} ${link.attr("title")}".trim()

                val season = Regex("(\\d+)\\s*\\.?\\s*Sezon", RegexOption.IGNORE_CASE)
                    .find(source)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: Regex("-(\\d+)-sezon-", RegexOption.IGNORE_CASE)
                        .find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()

                val episode = Regex("(\\d+)\\s*\\.?\\s*Bölüm", RegexOption.IGNORE_CASE)
                    .find(source)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: Regex("-(\\d+)-bolum", RegexOption.IGNORE_CASE)
                        .find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()

                if (season == null || episode == null) return@mapNotNull null

                newEpisode(href) {
                    name = element.selectFirst("div.bolum-ismi")?.text()?.trim() ?: element.text().trim()
                    this.season = season
                    this.episode = episode
                }
            }
            .distinctBy { it.data }
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

    private fun extractJsonLdContentUrl(document: org.jsoup.nodes.Document): String? {
        val script = document.select("script[type='application/ld+json']")
            .firstOrNull { it.data().contains("contentUrl", ignoreCase = true) }
            ?.data()
            ?: return null

        return Regex("[\\\"']contentUrl[\\\"']\\s*:\\s*[\\\"']([^\\\"']+)[\\\"']")
            .find(script)?.groupValues?.getOrNull(1)
            ?.replace("\\/", "/")
    }

    private fun extractSourceLinks(text: String): List<Triple<String, String, String>> {
        val sources = text.substringAfter("sources:[", "")
            .substringBefore("]", "")
            .replace("\\/", "/")

        if (sources.isBlank()) return emptyList()

        val regex = Regex(
            "[\\\"']file[\\\"']\\s*:\\s*[\\\"']([^\\\"']+)[\\\"'].*?" +
                "[\\\"']label[\\\"']\\s*:\\s*[\\\"']([^\\\"']*)[\\\"'].*?" +
                "[\\\"']type[\\\"']\\s*:\\s*[\\\"']([^\\\"']*)[\\\"']",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )

        return regex.findAll(sources).map {
            Triple(it.groupValues[1], it.groupValues[2], it.groupValues[3])
        }.toList()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("DiziGom", "Resolving episode: $data")
        val document = runCatching { app.get(data, referer = "$mainUrl/").document }.getOrNull() ?: return false

        // DiziGom's player exposes its real video URL in the JSON-LD contentUrl.
        // The old /api/token.php resolver is no longer used here.
        val contentUrl = extractJsonLdContentUrl(document)
        if (!contentUrl.isNullOrBlank()) {
            val playUrl = contentUrl.replaceFirst("https://", "https://play.")
            val iframeDocument = runCatching {
                app.get(playUrl, referer = "$mainUrl/").document
            }.getOrNull()

            if (iframeDocument != null) {
                val packed = iframeDocument.select("script")
                    .firstOrNull { it.data().contains("eval(function(p,a,c,k,e", ignoreCase = false) }
                    ?.data()
                    .orEmpty()

                val unpacked = if (packed.isNotBlank()) {
                    runCatching { JsUnpacker(packed).unpack().orEmpty() }.getOrDefault("")
                } else ""

                val candidates = extractSourceLinks(unpacked)
                for ((file, label, type) in candidates) {
                    if (file.isBlank()) continue
                    val isM3u8 = type.contains("mpegurl", true) || file.contains(".m3u8", true)
                    callback(
                        newExtractorLink(
                            source = name,
                            name = if (label.isBlank()) name else label,
                            url = file,
                            type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            referer = "$mainUrl/"
                            quality = getQualityFromName(label)
                        }
                    )
                }

                if (candidates.isNotEmpty()) return true
            }
        }

        // Fallback: pick a directly exposed m3u8/video URL if the player markup changed.
        val directUrls = Regex("https?://[^\\\"'\\s<>]+(?:\\.m3u8(?:\\?[^\\\"'\\s<>]*)?|\\.mp4(?:\\?[^\\\"'\\s<>]*)?)", RegexOption.IGNORE_CASE)
            .findAll(document.html())
            .map { it.value.replace("\\/", "/") }
            .distinct()
            .toList()

        for (url in directUrls) {
            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = url,
                    type = if (url.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    referer = "$mainUrl/"
                    quality = 1080
                }
            )
        }

        return directUrls.isNotEmpty()
    }
}
