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

    // Keep the same category names shown in the user's CloudStream TV layout.
    // The site itself currently exposes these genres in its Dizi archive.
    override val mainPage = mainPageOf(
        "$mainUrl/dizi-izle/" to "Aile",
        "$mainUrl/dizi-izle/" to "Aksiyon",
        "$mainUrl/dizi-izle/" to "Animasyon",
        "$mainUrl/dizi-izle/" to "Belgesel",
        "$mainUrl/dizi-izle/" to "Bilim Kurgu",
        "$mainUrl/dizi-izle/" to "Dram",
        "$mainUrl/dizi-izle/" to "Fantastik",
        "$mainUrl/dizi-izle/" to "Gerilim",
        "$mainUrl/dizi-izle/" to "Komedi",
        "$mainUrl/dizi-izle/" to "Korku",
        "$mainUrl/dizi-izle/" to "Macera",
        "$mainUrl/dizi-izle/" to "Polisiye",
        "$mainUrl/dizi-izle/" to "Romantik",
        "$mainUrl/dizi-izle/" to "Savaş",
        "$mainUrl/dizi-izle/" to "Suç",
        "$mainUrl/dizi-izle/" to "Tarih"
    )

    private fun pageUrl(page: Int): String =
        if (page <= 1) "$mainUrl/dizi-izle/"
        else "$mainUrl/dizi-izle/page/$page/"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val wantedGenre = request.name.trim()

        // Do not depend on one obsolete CSS class. The current archive exposes
        // the actual series URLs as /diziler/... links, so cards are rebuilt
        // from those links and their nearest metadata container.
        val pagesToRead = if (page == 1) listOf(1, 2, 3) else listOf(page)
        val results = mutableListOf<SearchResponse>()

        for (pageNumber in pagesToRead) {
            val document = runCatching {
                app.get(pageUrl(pageNumber), referer = "$mainUrl/").document
            }.getOrNull() ?: continue

            results += extractResults(document, wantedGenre)
                .filter { result -> results.none { it.url == result.url } }

            // Three pages are enough to make sparse categories visible while
            // avoiding hundreds of requests when CloudStream opens the home.
            if (page == 1 && results.size >= 12) break
        }

        return newHomePageResponse(request.name, results.distinctBy { it.url })
    }

    private fun extractResults(document: org.jsoup.nodes.Document, genre: String): List<SearchResponse> {
        return document
            .select("a[href*='/diziler/']")
            .mapNotNull { link -> link.toMainPageResult(genre) }
            .distinctBy { it.url }
    }

    private fun Element.toMainPageResult(genre: String): SearchResponse? {
        val href = fixUrlNull(attr("href")) ?: return null
        val title = text().trim().ifBlank { attr("title").trim() }
        if (title.isBlank()) return null

        // Walk up the DOM until we find the card containing the metadata.
        // This works with both the old episode-box markup and the newer archive markup.
        val card = generateSequence(this as Element?) { it.parent() }
            .take(10)
            .firstOrNull { element ->
                val text = element.text()
                element.selectFirst("img") != null &&
                    (text.contains("IMDb", true) || text.contains("Tür", true))
            } ?: parent()

        val cardText = card?.text().orEmpty()
        if (genre.isNotBlank() && !cardHasGenre(card, cardText, genre)) return null

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

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            posterUrl = poster
            score = Score.from10(rating)
        }
    }

    private fun cardHasGenre(card: Element?, cardText: String, wanted: String): Boolean {
        val normalizedWanted = normalizeGenre(wanted)

        val genreLinks = card?.select("a").orEmpty()
            .map { normalizeGenre(it.text()) }
            .filter { it.isNotBlank() }

        if (genreLinks.any { it == normalizedWanted }) return true

        // Current archive cards also expose genres in a plain "Tür : ..." line.
        val typeText = Regex(
            "Tür\\s*:\\s*(.+?)(?:$|\\n)",
            RegexOption.IGNORE_CASE
        ).find(cardText)?.groupValues?.getOrNull(1).orEmpty()

        return typeText.split(",", "|", "/")
            .map { normalizeGenre(it) }
            .any { it == normalizedWanted }
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
            "$mainUrl/?s=${query.trim().replace(" ", "+")}"
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
                .forEach { url ->
                    matched = runCatching {
                        loadExtractor(url, "$mainUrl/", subtitleCallback, callback)
                    }.getOrDefault(false) || matched
                }
        }

        return matched
    }
}
