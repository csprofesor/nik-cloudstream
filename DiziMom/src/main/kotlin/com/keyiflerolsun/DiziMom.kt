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
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class DiziMom : MainAPI() {
    override var mainUrl = "https://www.dizimom.work"
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
        val href = fixUrlNull(epDoc.selectFirst("div#benzerli a")?.attr("href")) ?: return null
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
        val year = document.selectXpath("//div[span[contains(text(), 'Yapım Yılı')]]").text()
            .substringAfter("Yapım Yılı : ").trim().toIntOrNull()
        val description = document.selectFirst("div.category_desc")?.text()?.trim()
        val tags = document.select("div.genres a").map { it.text().trim() }.filter { it.isNotBlank() }
        val rating = document.selectXpath("//div[span[contains(text(), 'IMDB')]]").text()
            .substringAfter("IMDB : ").trim()
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

    private fun normalizeUrl(value: String?): String? {
        var url = value?.trim()?.replace("\\/", "/")?.replace("&amp;", "&") ?: return null
        url = url.trim('"', '\'', '`', '”', '“', '’', '‘', ',', ';')
        if (url.startsWith("//")) url = "https:$url"
        return url.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    private fun addCandidate(set: LinkedHashSet<String>, value: String?) {
        val normalized = normalizeUrl(value) ?: fixUrlNull(value ?: "")
        if (!normalized.isNullOrBlank()) set.add(normalized)
    }

    private fun collectCandidates(document: org.jsoup.nodes.Document, set: LinkedHashSet<String>) {
        document.select("iframe, video, source, embed, [src], [data-src], [data-lazy-src], [data-url], [data-embed], [file]").forEach { element ->
            listOf("src", "data-src", "data-lazy-src", "data-url", "data-embed", "file").forEach { attr ->
                if (element.hasAttr(attr)) addCandidate(set, element.attr(attr))
            }
        }
        document.select("a[href]").forEach { anchor ->
            val text = anchor.text().lowercase()
            val href = anchor.attr("href")
            if (text.contains("vimo") || text.contains("vidmoly") || text.contains("soft") ||
                text.contains("godok") || text.contains("god ok") || text.contains("player") ||
                href.contains("vimo", true) || href.contains("vidmoly", true) ||
                href.contains("peacemaker", true) || href.contains("hdmom", true) ||
                href.contains("hdplayersystem", true) || href.contains("videoseyret", true)) {
                addCandidate(set, href)
            }
        }
        document.select("div.sources a, .sources a, .alternatif a, .alternative a, [class*=source] a, [class*=player] a")
            .forEach { addCandidate(set, it.attr("href")) }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        )
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<Pair<String, String>>()
        val initial = linkedSetOf<String>()

        val document = try {
            app.get(data, headers = headers, referer = mainUrl).document
        } catch (_: Exception) {
            return false
        }

        collectCandidates(document, initial)
        initial.forEach { queue.addLast(it to data) }

        var loaded = false
        var scanned = 0

        while (queue.isNotEmpty() && scanned < 40) {
            val (url, referer) = queue.removeFirst()
            if (!visited.add(url)) continue
            scanned++

            try {
                loadExtractor(url, referer, subtitleCallback, callback)
                loaded = true
            } catch (_: Exception) {
            }

            val lower = url.lowercase()
            if (lower.contains(".m3u8") || lower.contains(".mp4")) continue

            try {
                val player = app.get(url, headers = headers, referer = referer)
                val html = player.text.replace("\\/", "/").replace("&amp;", "&")

                Regex("""https?://[^\s\"'<>]+?\.(?:m3u8|mp4)(?:\?[^\s\"'<>]*)?""", RegexOption.IGNORE_CASE)
                    .findAll(html)
                    .map { it.value }
                    .distinct()
                    .forEach { media ->
                        try {
                            loadExtractor(media, url, subtitleCallback, callback)
                            loaded = true
                        } catch (_: Exception) {
                        }
                    }

                val nested = linkedSetOf<String>()
                collectCandidates(player.document, nested)
                nested.forEach { next ->
                    if (!visited.contains(next) && queue.size < 40) queue.addLast(next to url)
                }
            } catch (_: Exception) {
            }
        }

        return loaded
    }
}
