// ! DiziMom provider - gsrepo

package com.keyiflerolsun

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
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class DiziMom : MainAPI() {
    override var mainUrl = "https://www.dizimom.surf"
    override var name = "DiziMom"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
        "${mainUrl}/tum-bolumler/page/" to "Son Bölümler",
        "${mainUrl}/yerli-dizi-izle/page/" to "Yerli Diziler",
        "${mainUrl}/yabanci-dizi-izle/page/" to "Yabancı Diziler",
        "${mainUrl}/tv-programlari-izle/page/" to "TV Programları",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}${page}/").document
        val home = if (request.data.contains("/tum-bolumler/")) {
            document.select("div.episode-box").mapNotNull { it.sonBolumler() }
        } else {
            document.select("div.single-item").mapNotNull { it.diziler() }
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.imageUrl(): String? {
        val raw = listOf(
            attr("data-src"), attr("data-lazy-src"), attr("data-original"),
            attr("data-image"), attr("data-url"), attr("src")
        ).firstOrNull { it.isNotBlank() && !it.startsWith("data:image") }
            ?: attr("srcset").substringBefore(",").substringBefore(" ").takeIf { it.isNotBlank() }
            ?: return null
        return fixUrlNull(raw)
    }

    private suspend fun Element.sonBolumler(): SearchResponse? {
        val name = selectFirst("div.episode-name a")?.text()?.substringBefore(" izle") ?: return null
        val title = name.replace(".Sezon ", "x").replace(".Bölüm", "")
        val epHref = fixUrlNull(selectFirst("div.episode-name a")?.attr("href")) ?: return null
        val epDoc = app.get(epHref).document
        val href = epDoc.selectFirst("div#benzerli a")?.attr("href") ?: return null
        val posterUrl = selectFirst("a img")?.imageUrl()
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
    }

    private fun Element.diziler(): SearchResponse? {
        val title = selectFirst("div.categorytitle a")?.text()?.substringBefore(" izle") ?: return null
        val href = fixUrlNull(selectFirst("div.categorytitle a")?.attr("href")) ?: return null
        val posterUrl = selectFirst("div.cat-img img")?.imageUrl()
        val score = selectFirst("div.imdbp")?.text()?.replace("(IMDb:", "")?.replace(")", "")?.trim()
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
            this.score = Score.from10(score)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/?s=${query}").document
        return document.select("div.single-item").mapNotNull { it.diziler() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("div.title h1")?.text()?.substringBefore(" izle") ?: return null
        val poster = document.selectFirst("div.category_image img")?.imageUrl() ?: return null
        val year = document.selectXpath("//div[span[contains(text(), 'Yapım Yılı')]]").text().substringAfter("Yapım Yılı : ").trim().toIntOrNull()
        val description = document.selectFirst("div.category_desc")?.text()?.trim()
        val tags = document.select("div.genres a").mapNotNull { it.text().trim() }
        val rating = document.selectXpath("//div[span[contains(text(), 'IMDB')]]").text().substringAfter("IMDB : ").trim()
        val actors = document.selectXpath("//div[span[contains(text(), 'Oyuncular')]]").text()
            .substringAfter("Oyuncular : ").split(", ").filter { it.isNotBlank() }.map { Actor(it.trim()) }
        val episodes = document.select("div.bolumust").mapNotNull {
            val epName = it.selectFirst("div.baslik")?.text()?.trim() ?: return@mapNotNull null
            val epHref = fixUrlNull(it.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
            val epEpisode = Regex("""(\d+)\.Bölüm""").find(epName)?.groupValues?.get(1)?.toIntOrNull()
            val epSeason = Regex("""(\d+)\.Sezon""").find(epName)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            newEpisode(epHref) {
                this.name = epName.substringBefore(" izle").replace(title, "").trim()
                this.season = epSeason
                this.episode = epEpisode
            }
        }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.year = year
            this.plot = description
            this.tags = tags
            this.score = Score.from10(rating)
            addActors(actors)
        }
    }

    private fun normalizeUrl(value: String): String? {
        var url = value.trim().replace("\\/", "/").replace("&amp;", "&")
            .trim('"', '\'', '`', '”', '“', '’', '‘')
        if (url.startsWith("//")) url = "https:$url"
        return url.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    private fun addCandidate(set: LinkedHashSet<String>, value: String?) {
        val normalized = normalizeUrl(value ?: "") ?: fixUrlNull(value ?: "")
        if (!normalized.isNullOrBlank()) set.add(normalized)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36"
        )
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<Pair<String, String>>()
        val initial = linkedSetOf<String>()
        val document = try {
            app.get(data, headers = headers, referer = mainUrl).document
        } catch (_: Exception) {
            return false
        }

        document.select("iframe, video, source, embed, [src], [data-src], [data-lazy-src], [data-url], [data-embed], [file]").forEach { element ->
            listOf("src", "data-src", "data-lazy-src", "data-url", "data-embed", "file").forEach { attr ->
                if (element.hasAttr(attr)) addCandidate(initial, element.attr(attr))
            }
        }
        document.select("div.sources a, .sources a").forEach { addCandidate(initial, it.attr("href")) }

        initial.forEach { queue.addLast(it to data) }
        var loaded = false
        var scanned = 0

        while (queue.isNotEmpty() && scanned < 24) {
            val (url, referer) = queue.removeFirst()
            if (!visited.add(url)) continue
            scanned++

            val lower = url.lowercase()
            if (lower.contains(".m3u8") || lower.contains(".mp4")) {
                try {
                    loadExtractor(url, referer, subtitleCallback, callback)
                    loaded = true
                } catch (_: Exception) { }
                continue
            }

            try {
                val player = app.get(url, headers = headers, referer = referer)
                val html = player.text
                Regex("""https?://[^\s\"'<>]+?\.(?:m3u8|mp4)(?:\?[^\s\"'<>]*)?""", RegexOption.IGNORE_CASE)
                    .findAll(html)
                    .map { it.value.replace("\\/", "/").replace("&amp;", "&") }
                    .distinct()
                    .forEach {
                        try {
                            loadExtractor(it, url, subtitleCallback, callback)
                            loaded = true
                        } catch (_: Exception) { }
                    }

                player.document.select("iframe, video, source, embed, [src], [data-src], [data-lazy-src], [data-url], [data-embed], [file]").forEach { element ->
                    listOf("src", "data-src", "data-lazy-src", "data-url", "data-embed", "file").forEach { attr ->
                        if (!element.hasAttr(attr)) return@forEach
                        val next = normalizeUrl(element.attr(attr)) ?: fixUrlNull(element.attr(attr)) ?: return@forEach
                        if (!visited.contains(next) && queue.size < 24) queue.addLast(next to url)
                    }
                }
            } catch (_: Exception) { }

            try {
                loadExtractor(url, referer, subtitleCallback, callback)
                loaded = true
            } catch (_: Exception) { }
        }

        return loaded
    }
}
