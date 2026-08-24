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
    private val genreCache = mutableMapOf<String, Set<String>>()

    private suspend fun getArchive(): List<SearchResponse> {
        archiveCache?.let { return it }

        val document = runCatching {
            app.get("$mainUrl/dizi-izle/", referer = "$mainUrl/").document
        }.getOrNull() ?: return emptyList()

        val results = document
            .select("a[href*='/diziler/']")
            .mapNotNull { it.toArchiveResult() }
            .distinctBy { it.url }

        archiveCache = results
        return results
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val wantedGenre = normalizeGenre(request.name)
        val archive = getArchive()

        val results = if (wantedGenre.isBlank()) {
            archive
        } else {
            archive.filter { result ->
                genreCache[result.url]
                    .orEmpty()
                    .any { normalizeGenre(it) == wantedGenre }
            }
        }

        return newHomePageResponse(request.name, results)
    }

    private fun Element.toArchiveResult(): SearchResponse? {
        val href = fixUrlNull(attr("href")) ?: return null
        val title = text().trim().ifBlank { attr("title").trim() }
        if (title.isBlank()) return null

        val card = generateSequence(this as Element?) { it.parent() }
            .take(10)
            .firstOrNull { element ->
                element.selectFirst("img") != null &&
                    (element.text().contains("IMDb", true) ||
                        element.text().contains("Tür", true))
            } ?: parent()

        val cardText = card?.text().orEmpty()
        val image = card?.selectFirst("img") ?: selectFirst("img")
        val poster = fixUrlNull(
            image?.attr("data-src").orEmpty().ifBlank {
                image?.attr("data-lazy-src").orEmpty().ifBlank {
                    image?.attr("src").orEmpty()
                }
            }
        )

        val rating = Regex(
            "(?:IMDb|IMDB)\\s*:?\\s*([0-9]+(?:[.,][0-9]+)?)",
            RegexOption.IGNORE_CASE
        ).find(cardText)?.groupValues?.getOrNull(1)

        val genresFromLinks = card?.select("a")
            .orEmpty()
            .map { it.text().trim() }
            .filter { genre -> genres.any { normalizeGenre(it) == normalizeGenre(genre) } }
            .distinct()

        val typeText = Regex(
            "Tür\\s*:\\s*(.+?)(?:$|\\n)",
            RegexOption.IGNORE_CASE
        ).find(cardText)?.groupValues?.getOrNull(1).orEmpty()

        val genresFromText = typeText
            .split(",", "|", "/")
            .map { it.trim() }
            .filter { genre -> genres.any { normalizeGenre(it) == normalizeGenre(genre) } }

        val detectedGenres = (genresFromLinks + genresFromText)
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

                val card = generateSequence(link as Element?) { it.parent() }
                    .take(8)
                    .firstOrNull { it.selectFirst("img") != null }
                val image = card?.selectFirst("img")
                val poster = fixUrlNull(
                    image?.attr("data-src").orEmpty().ifBlank {
                        image?.attr("data-lazy-src").orEmpty().ifBlank {
                            image?.attr("src").orEmpty()
                        }
                    }
                )

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
        val tags = document.select("div.genreList a")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
        val rating = document.selectFirst("div.score")?.text()?.trim()
        val duration = document.select("div.serieMetaInformation div.totalSession")
            .lastOrNull()?.text()?.substringBefore(" ")?.toIntOrNull()
        val actors = document.select("div.owl-stage a").mapNotNull { a ->
            val actor = a.text().trim()
            if (actor.isBlank()) null
            else Actor(actor, fixUrlNull(a.selectFirst("img")?.attr("src")))
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
