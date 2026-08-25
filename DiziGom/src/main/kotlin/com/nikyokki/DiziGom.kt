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
        val results = document?.select("a[href*='/diziler/']")
            ?.mapNotNull { it.toMainPageResult() }
            ?.distinctBy { it.url }
            .orEmpty()
        Log.d("DiziGom", "${request.name}: page=$page count=${results.size} url=$pageUrl")
        return newHomePageResponse(request.name, results, hasNext = results.isNotEmpty())
    }

    private fun Element.findCard(): Element = generateSequence(this as Element?) { it.parent() }
        .take(10)
        .firstOrNull { it.hasClass("episode-box") }
        ?: generateSequence(this as Element?) { it.parent() }
            .take(10)
            .firstOrNull { it.selectFirst("img") != null || it.attr("style").contains("background", true) }
        ?: this

    private fun Element.poster(): String? {
        val image = selectFirst("img")
        val imageUrl = image?.let {
            sequenceOf(
                it.attr("data-src"),
                it.attr("data-lazy-src"),
                it.attr("data-original"),
                it.attr("data-image"),
                it.attr("src")
            ).firstOrNull { value -> value.isNotBlank() }
        }
        if (!imageUrl.isNullOrBlank()) return fixUrlNull(imageUrl.substringBefore(",").trim())

        val sourceUrl = selectFirst("source")?.let {
            sequenceOf(it.attr("data-srcset"), it.attr("srcset"), it.attr("data-src"), it.attr("src"))
                .firstOrNull { value -> value.isNotBlank() }
        }
        if (!sourceUrl.isNullOrBlank()) return fixUrlNull(sourceUrl.substringBefore(",").trim().substringBefore(" "))

        val style = attr("style")
        val background = Regex("url\\((?:\\\"|')?([^\\\"')]+)", RegexOption.IGNORE_CASE)
            .find(style)?.groupValues?.getOrNull(1)
        if (!background.isNullOrBlank()) return fixUrlNull(background)

        val dataBackground = sequenceOf(
            attr("data-bg"),
            attr("data-background"),
            attr("data-background-image"),
            attr("data-image")
        ).firstOrNull { it.isNotBlank() }
        return fixUrlNull(dataBackground.orEmpty())
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val href = fixUrlNull(attr("href")) ?: return null
        val card = findCard()
        val title = sequenceOf(
            attr("title"),
            selectFirst("img")?.attr("alt"),
            card.selectFirst("img")?.attr("alt"),
            card.selectFirst("div.serie-name a")?.text(),
            text()
        ).map { it?.trim().orEmpty() }.firstOrNull { it.isNotBlank() } ?: return null
        val rating = Regex("(?:IMDb|IMDB)\\s*:?\\s*([0-9]+(?:[.,][0-9]+)?)", RegexOption.IGNORE_CASE)
            .find(card.text())?.groupValues?.getOrNull(1)
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            posterUrl = card.poster() ?: poster()
            score = Score.from10(rating)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=${query.trim().replace(" ", "+")}", referer = "$mainUrl/").document
        return document.select("a[href*='/diziler/']").mapNotNull { it.toMainPageResult() }.distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    private fun Element.backgroundPoster(): String? = Regex("url\\((?:\\\"|')?([^\\\"')]+)", RegexOption.IGNORE_CASE)
        .find(attr("style"))?.groupValues?.getOrNull(1)?.let { fixUrlNull(it) }

    private fun Element.firstText(vararg selectors: String): String? = selectors.asSequence()
        .mapNotNull { selectFirst(it)?.text()?.trim() }.firstOrNull { it.isNotBlank() }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, referer = "$mainUrl/").document
        val title = document.firstText("div.serieTitle h1", ".serieTitle h1", "h1.entry-title", "article h1", "h1") ?: return null
        val poster = document.selectFirst("div.seriePoster")?.backgroundPoster()
            ?: document.selectFirst("div.seriePoster img")?.poster()
            ?: document.selectFirst("meta[property='og:image']")?.attr("content")?.let { fixUrlNull(it) }
        val description = document.firstText("div.serieDescription p", ".serieDescription p", ".description p", ".entry-content p")
        val year = Regex("(?:Yapım Yılı|Yapim Yili)\\s*:?\\s*(\\d{4})", RegexOption.IGNORE_CASE).find(document.text())?.groupValues?.getOrNull(1)?.toIntOrNull()
        val rating = Regex("(?:IMDB|IMDb)\\s*:?\\s*([0-9]+(?:[.,][0-9]+)?)", RegexOption.IGNORE_CASE).find(document.text())?.groupValues?.getOrNull(1)
        val tags = document.select("div.genreList a, .genreList a").map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
        val actors = document.select("div.owl-stage a, .cast a, .actors a").mapNotNull { link ->
            val actor = link.text().trim(); if (actor.isBlank()) null else Actor(actor, link.selectFirst("img")?.poster())
        }.distinctBy { it.name }
        val episodes = document.select("div.bolumust, a[href*='-sezon-'][href*='-bolum']").mapNotNull { element ->
            val link = if (element.tagName() == "a") element else element.selectFirst("a") ?: return@mapNotNull null
            val href = fixUrlNull(link.attr("href")) ?: return@mapNotNull null
            val source = "${element.text()} ${link.attr("title")}".trim()
            val season = Regex("(\\d+)\\s*\\.?\\s*Sezon", RegexOption.IGNORE_CASE).find(source)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("-(\\d+)-sezon-", RegexOption.IGNORE_CASE).find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val episode = Regex("(\\d+)\\s*\\.?\\s*Bölüm", RegexOption.IGNORE_CASE).find(source)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("-(\\d+)-bolum", RegexOption.IGNORE_CASE).find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (season == null || episode == null) return@mapNotNull null
            newEpisode(href) { name = element.selectFirst("div.bolum-ismi")?.text()?.trim() ?: element.text().trim(); this.season = season; this.episode = episode }
        }.distinctBy { it.data }.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            posterUrl = poster; this.year = year; plot = description; this.tags = tags; score = Score.from10(rating); addActors(actors)
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data, referer = "$mainUrl/").document
        val frames = document.select("iframe[src], iframe[data-src], iframe[data-lazy-src], .player iframe").mapNotNull { iframe ->
            fixUrlNull(iframe.attr("src").ifBlank { iframe.attr("data-src").ifBlank { iframe.attr("data-lazy-src") } })
        }.distinct()

        var found = false
        for (frame in frames) {
            val loaded = runCatching {
                val playerSource = app.get(frame, referer = "$mainUrl/").text
                val videoId = Regex("[\\\"']v[\\\"']\\s*:\\s*[\\\"']([^\\\"']+)").find(playerSource)?.groupValues?.getOrNull(1)
                    ?: return@runCatching false

                val playerBase = frame.substringBefore("/assets/").trimEnd('/')
                val tokenUrl = "$playerBase/api/token.php?v=$videoId"
                val tokenResponse = app.get(tokenUrl, referer = frame).text
                val token = Regex("[\\\"']token[\\\"']\\s*:\\s*[\\\"']([^\\\"']+)").find(tokenResponse)?.groupValues?.getOrNull(1)
                    ?: return@runCatching false

                val streamUrl = "$playerBase/api/stream.php?v=$videoId&token=$token"
                callback(
                    ExtractorLink(
                        source = "DiziGom",
                        name = "Pilavyer",
                        url = streamUrl,
                        referer = frame,
                        quality = 1080,
                        isM3u8 = true
                    )
                )
                true
            }.getOrDefault(false)
            found = loaded || found
        }
        return found
    }
}
