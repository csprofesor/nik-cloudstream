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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jsoup.nodes.Document
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
        "Aksiyon", "Animasyon", "Belgesel", "Bilim Kurgu", "Biyografi", "Dram",
        "Fantastik", "Gençlik", "Gerilim", "Gizem", "Komedi", "Korku", "Macera",
        "Polisiye", "Romantik", "Savaş", "Suç", "Tarih"
    )

    override val mainPage = mainPageOf(
        *genres.map { "$mainUrl/dizi-izle/" to it }.toTypedArray()
    )

    private data class ArchiveEntry(
        val result: SearchResponse,
        val genres: Set<String>
    )

    @Volatile
    private var archiveCache: List<ArchiveEntry>? = null

    private suspend fun getArchive(): List<ArchiveEntry> {
        archiveCache?.let { return it }

        val first = app.get("$mainUrl/dizi-izle/", referer = "$mainUrl/").document
        val lastPage = first
            .select("a[href*='/dizi-izle/page/']")
            .mapNotNull { link ->
                Regex("/dizi-izle/page/(\\d+)/")
                    .find(link.attr("href"))
                    ?.groupValues?.getOrNull(1)?.toIntOrNull()
            }
            .maxOrNull() ?: 1

        val documents = coroutineScope {
            (1..lastPage).map { page ->
                async {
                    if (page == 1) {
                        first
                    } else {
                        runCatching {
                            app.get(
                                "$mainUrl/dizi-izle/page/$page/",
                                referer = "$mainUrl/"
                            ).document
                        }.getOrNull()
                    }
                }
            }.map { it.await() }
        }

        val entries = documents
            .filterNotNull()
            .flatMap { document ->
                document.select("div.episode-box").mapNotNull { it.toArchiveEntry() }
            }
            .distinctBy { it.result.url }

        archiveCache = entries
        return entries
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

    private fun extractGenres(text: String): Set<String> {
        val typeText = Regex(
            "Tür\\s*:?\\s*(.*?)(?=Favorilere Ekle|$)",
            RegexOption.IGNORE_CASE
        ).find(text.replace(Regex("\\s+"), " "))?.groupValues?.getOrNull(1).orEmpty()

        return genres
            .filter { genre ->
                Regex(
                    "(?<!\\p{L})${Regex.escape(genre)}(?!\\p{L})",
                    RegexOption.IGNORE_CASE
                ).containsMatchIn(typeText)
            }
            .map(::normalizeGenre)
            .toSet()
    }

    private fun Element.extractPoster(): String? {
        val image = selectFirst("img") ?: return null
        val raw = listOf(
            image.attr("data-src"),
            image.attr("data-lazy-src"),
            image.attr("data-original"),
            image.attr("data-image"),
            image.attr("src")
        ).firstOrNull { it.isNotBlank() }
            ?.substringBefore(",")
            ?.trim()

        return fixUrlNull(raw.orEmpty())
    }

    private fun Element.toArchiveEntry(): ArchiveEntry? {
        val link = selectFirst("div.serie-name a") ?: selectFirst("a[href*='/diziler/']") ?: return null
        val href = fixUrlNull(link.attr("href")) ?: return null
        val title = link.text().trim().ifBlank {
            selectFirst("img")?.attr("alt")?.trim().orEmpty()
        }
        if (title.isBlank()) return null

        val cardText = text().replace(Regex("\\s+"), " ").trim()
        val rating = Regex(
            "(?:IMDb|IMDB)\\s*:?\\s*([0-9]+(?:[.,][0-9]+)?)",
            RegexOption.IGNORE_CASE
        ).find(cardText)?.groupValues?.getOrNull(1)

        val result = newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            posterUrl = extractPoster()
            score = Score.from10(rating)
        }

        return ArchiveEntry(result, extractGenres(cardText))
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val archive = getArchive()
        val wanted = normalizeGenre(request.name)
        val filtered = archive.filter { entry -> wanted in entry.genres }
        val pageSize = 20
        val start = (page - 1).coerceAtLeast(0) * pageSize
        val results = filtered.drop(start).take(pageSize).map { it.result }
        val hasNext = start + results.size < filtered.size

        return newHomePageResponse(request.name, results, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get(
            "$mainUrl/?s=${query.trim().replace(" ", "+")}",
            referer = "$mainUrl/"
        ).document

        return document
            .select("div.single-item, div.episode-box")
            .mapNotNull { element ->
                if (element.hasClass("episode-box")) {
                    element.toArchiveEntry()?.result
                } else {
                    val link = element.selectFirst("div.categorytitle a") ?: return@mapNotNull null
                    val href = fixUrlNull(link.attr("href")) ?: return@mapNotNull null
                    val title = link.text().trim()
                    if (title.isBlank()) return@mapNotNull null
                    newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                        posterUrl = element.selectFirst("img")?.let { fixUrlNull(it.attr("src")) }
                    }
                }
            }
            .distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    private fun Element.extractBackgroundUrl(): String? {
        return Regex("url\\((?:\\\"|')?([^\\\"')]+)", RegexOption.IGNORE_CASE)
            .find(attr("style"))
            ?.groupValues?.getOrNull(1)
            ?.let { fixUrlNull(it) }
    }

    private fun Element.firstText(vararg selectors: String): String? =
        selectors.asSequence()
            .mapNotNull { selectFirst(it)?.text()?.trim() }
            .firstOrNull { it.isNotBlank() }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, referer = "$mainUrl/").document

        val title = document.firstText(
            "div.serieTitle h1", ".serieTitle h1", "h1.entry-title", "article h1", "h1"
        ) ?: return null

        val poster = document.selectFirst("div.seriePoster")?.extractBackgroundUrl()
            ?: document.selectFirst("div.seriePoster img")?.extractPoster()
            ?: document.selectFirst("meta[property='og:image']")?.attr("content")?.let { fixUrlNull(it) }
            ?: document.selectFirst("article img")?.extractPoster()

        val description = document.firstText(
            "div.serieDescription p", ".serieDescription p", ".description p", ".entry-content p"
        )

        val year = Regex("(?:Yapım Yılı|Yapim Yili)\\s*:?\\s*(\\d{4})", RegexOption.IGNORE_CASE)
            .find(document.text())?.groupValues?.getOrNull(1)?.toIntOrNull()

        val rating = Regex(
            "(?:IMDB|IMDb)\\s*:?\\s*([0-9]+(?:[.,][0-9]+)?)",
            RegexOption.IGNORE_CASE
        ).find(document.text())?.groupValues?.getOrNull(1)

        val tags = document.select("div.genreList a, .genreList a, a[href*='/tur/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }

        val actors = document.select("div.owl-stage a, .cast a, .actors a")
            .mapNotNull { actorLink ->
                val actor = actorLink.text().trim()
                if (actor.isBlank()) return@mapNotNull null
                Actor(actor, actorLink.selectFirst("img")?.extractPoster())
            }
            .distinctBy { it.name }

        val episodes = document.select("a[href*='-sezon-'][href*='-bolum']")
            .mapNotNull { episodeLink ->
                val href = fixUrlNull(episodeLink.attr("href")) ?: return@mapNotNull null
                val label = episodeLink.text().trim()
                val source = "$label ${episodeLink.attr("title")}".trim()
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
                    name = label.replace(Regex("^İzledim\\s*", RegexOption.IGNORE_CASE), "").trim()
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
            .select("iframe[src], iframe[data-src], iframe[data-lazy-src], .player iframe, div#content iframe")
            .mapNotNull { iframe ->
                fixUrlNull(
                    iframe.attr("src")
                        .ifBlank { iframe.attr("data-src") }
                        .ifBlank { iframe.attr("data-lazy-src") }
                )
            }
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
            val contentUrls = Regex(
                "[\\\"']contentUrl[\\\"']\\s*:\\s*[\\\"']([^\\\"']+)[\\\"']",
                RegexOption.IGNORE_CASE
            )
                .findAll(document.html())
                .map { it.groupValues[1] }
                .mapNotNull { fixUrlNull(it) }
                .distinct()

            for (videoUrl in contentUrls) {
                matched = runCatching {
                    loadExtractor(videoUrl, "$mainUrl/", subtitleCallback, callback)
                }.getOrDefault(false) || matched
            }
        }

        return matched
    }
}