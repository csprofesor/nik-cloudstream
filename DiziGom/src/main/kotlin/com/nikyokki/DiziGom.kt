package com.nikyokki

import android.util.Log
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
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    private val genres = listOf(
        "Aile", "Aksiyon", "Animasyon", "Belgesel", "Bilim Kurgu", "Dram",
        "Fantastik", "Gerilim", "Komedi", "Korku", "Macera", "Polisiye",
        "Romantik", "Savaş", "Suç", "Tarih"
    )

    override val mainPage = mainPageOf(
        *genres.map { "$mainUrl/dizi-izle/" to it }.toTypedArray()
    )

    private var archiveCache: List<SearchResponse>? = null
    private val archiveMutex = Mutex()
    private val genreCache = mutableMapOf<String, Set<String>>()

    private suspend fun getArchive(): List<SearchResponse> {
        archiveCache?.let { return it }
        return archiveMutex.withLock {
            archiveCache?.let { return@withLock it }
            val document = runCatching {
                app.get("$mainUrl/dizi-izle/", referer = "$mainUrl/").document
            }.getOrNull() ?: return@withLock emptyList()

            val results = document
                .select("a[href*='/diziler/']")
                .mapNotNull { it.toArchiveResult() }
                .distinctBy { it.url }

            archiveCache = results
            results
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val wantedGenre = normalizeGenre(request.name)
        val archive = getArchive()
        val results = archive.filter { result ->
            genreCache[result.url].orEmpty().any { normalizeGenre(it) == wantedGenre }
        }
        return newHomePageResponse(request.name, results)
    }

    /** Find the smallest useful card containing this exact series link. */
    private fun Element.findSeriesCard(href: String): Element? {
        return generateSequence(this as Element?) { it.parent() }
            .take(10)
            .firstOrNull { element ->
                element.select("a[href='${href.replace("'", "\\'")}']").isNotEmpty() &&
                    element.selectFirst("img") != null
            }
    }

    private fun Element.extractPoster(): String? {
        val image = selectFirst("img") ?: return null
        val raw = listOf(
            image.attr("data-src"),
            image.attr("data-lazy-src"),
            image.attr("data-original"),
            image.attr("data-image"),
            image.attr("data-fallback-src"),
            image.attr("src")
        ).firstOrNull { it.isNotBlank() }
            ?: image.attr("style").takeIf { it.contains("url(", true) }
                ?.substringAfter("url(", "")?.substringBefore(")")
                ?.trim(' ', '\'', '"')

        return fixUrlNull(raw.orEmpty())
    }

    private fun Element.toArchiveResult(): SearchResponse? {
        val href = fixUrlNull(attr("href")) ?: return null
        val title = text().trim().ifBlank { attr("title").trim() }
        if (title.isBlank()) return null

        // Do not select the first image from a large page container. The old
        // implementation could climb to a wrapper containing several series,
        // making every result inherit the same poster. We now stop at the
        // closest ancestor that contains this exact series link and an image.
        val card = findSeriesCard(href) ?: parent()
        val cardText = card?.text().orEmpty()

        // Prefer an image inside the link itself, then the closest card image.
        val poster = fixUrlNull(
            selectFirst("img")?.let { image ->
                listOf(
                    image.attr("data-src"),
                    image.attr("data-lazy-src"),
                    image.attr("data-original"),
                    image.attr("data-image"),
                    image.attr("src")
                ).firstOrNull { it.isNotBlank() }
            }.orEmpty()
        ) ?: card?.extractPoster()

        val rating = Regex(
            "(?:IMDb|IMDB)\\s*:?\\s*([0-9]+(?:[.,][0-9]+)?)",
            RegexOption.IGNORE_CASE
        ).find(cardText)?.groupValues?.getOrNull(1)

        val typeText = Regex(
            "Tür\\s*:\\s*(.*?)(?=\\s+(?:Favorilere Ekle|Yapım Yılı|Oyuncular|IMDb)\\b|$)",
            RegexOption.IGNORE_CASE
        ).find(cardText)?.groupValues?.getOrNull(1).orEmpty()

        val detectedGenres = typeText
            .split(",", "|", "/")
            .map { it.trim() }
            .filter { value -> genres.any { normalizeGenre(it) == normalizeGenre(value) } }
            .distinctBy(::normalizeGenre)

        genreCache[href] = detectedGenres.toSet()

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            posterUrl = poster
            score = Score.from10(rating)
        }
    }

    private fun normalizeGenre(value: String): String = value
        .trim()
        .lowercase()
        .replace("ı", "i")
        .replace("ş", "s")
        .replace("ğ", "g")
        .replace("ü", "u")
        .replace("ö", "o")
        .replace("ç", "c")
        .replace("fantazi", "fantastik")
        .replace(Regex("\\s+"), " ")

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get(
            "$mainUrl/?s=${query.trim().replace(" ", "+")}",
            referer = "$mainUrl/"
        ).document

        return document
            .select("a[href*='/diziler/']")
            .mapNotNull { link ->
                val href = fixUrlNull(link.attr("href")) ?: return@mapNotNull null
                val title = link.text().trim().ifBlank { link.attr("title").trim() }
                if (title.isBlank()) return@mapNotNull null

                val card = link.findSeriesCard(href)
                val poster = fixUrlNull(
                    link.selectFirst("img")?.let { image ->
                        listOf(
                            image.attr("data-src"),
                            image.attr("data-lazy-src"),
                            image.attr("data-original"),
                            image.attr("data-image"),
                            image.attr("src")
                        ).firstOrNull { it.isNotBlank() }
                    }.orEmpty()
                ) ?: card?.extractPoster()

                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    posterUrl = poster
                }
            }
            .distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, referer = "$mainUrl/").document
        val title = document.selectFirst("div.serieTitle h1")?.text()?.trim() ?: return null
        val poster = fixUrlNull(
            document.selectFirst("div.seriePoster")?.attr("style")
                ?.substringAfter("background-image:url(")
                ?.substringBefore(")")
                ?.trim(' ', '\'', '"')
        )
        val description = document.selectFirst("div.serieDescription p")?.text()?.trim()
        val year = document.selectFirst("div.airDateYear a")?.text()?.trim()?.toIntOrNull()
        val tags = document.select("div.genreList a").map { it.text().trim() }.filter { it.isNotEmpty() }
        val rating = document.selectFirst("div.score")?.text()?.trim()
        val duration = document.select("div.serieMetaInformation div.totalSession")
            .lastOrNull()?.text()?.substringBefore(" ")?.toIntOrNull()
        val actors = document.select("div.owl-stage a").mapNotNull { a ->
            val actor = a.text().trim()
            if (actor.isBlank()) null else Actor(actor, fixUrlNull(a.selectFirst("img")?.attr("src")))
        }
        val episodes = document.select("div.bolumust").mapNotNull { e ->
            val href = fixUrlNull(e.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
            val parts = e.selectFirst("div.baslik")?.text()?.trim()?.split(" ") ?: emptyList()
            newEpisode(href) {
                name = e.selectFirst("div.bolum-ismi")?.text()?.trim()
                season = parts.getOrNull(0)?.replace(".", "")?.toIntOrNull()
                episode = parts.getOrNull(2)?.replace(".", "")?.toIntOrNull()
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            posterUrl = poster
            this.year = year
            plot = description
            this.tags = tags
            this.duration = duration
            score = Score.from10(rating)
            addActors(actors)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, referer = "$mainUrl/").document
        Log.d("DiziGom", "Episode: $data")

        val iframeUrls = document
            .select("iframe[src], iframe[data-src], div#content iframe, .player iframe")
            .mapNotNull { fixUrlNull(it.attr("src").ifBlank { it.attr("data-src") }) }
            .filter { it.isNotBlank() }
            .distinct()

        var matched = false
        for (iframe in iframeUrls) {
            if (iframe.contains(".m3u8", true)) {
                callback(newExtractorLink(name, "$name HLS", iframe, ExtractorLinkType.M3U8) {
                    referer = "$mainUrl/"
                })
                matched = true
            } else if (iframe.contains(".mp4", true)) {
                callback(newExtractorLink(name, "$name MP4", iframe, ExtractorLinkType.VIDEO) {
                    referer = "$mainUrl/"
                })
                matched = true
            } else {
                matched = runCatching {
                    loadExtractor(iframe, "$mainUrl/", subtitleCallback, callback)
                }.getOrDefault(false) || matched
            }
        }

        if (!matched) {
            Regex("""[\"']contentUrl[\"']\s*:\s*[\"']([^\"']+)[\"']""")
                .findAll(document.html())
                .map { it.groupValues[1] }
                .mapNotNull { fixUrlNull(it) }
                .distinct()
                .forEach { videoUrl ->
                    matched = runCatching {
                        loadExtractor(videoUrl, "$mainUrl/", subtitleCallback, callback)
                    }.getOrDefault(false) || matched
                }
        }

        return matched
    }
}
