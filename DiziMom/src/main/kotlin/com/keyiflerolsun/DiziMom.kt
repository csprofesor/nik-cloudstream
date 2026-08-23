// ! DiziMom provider - gsrepo

package com.keyiflerolsun

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
import com.lagradost.cloudstream3.newExtractorLink
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
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
        val name = this.selectFirst("div.episode-name a")?.text()?.substringBefore(" izle") ?: return null
        val title = name.replace(".Sezon ", "x").replace(".Bölüm", "")
        val epHref = fixUrlNull(this.selectFirst("div.episode-name a")?.attr("href")) ?: return null
        val epDoc = app.get(epHref).document
        val href = epDoc.selectFirst("div#benzerli a")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("a img")?.imageUrl()
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
    }

    private fun Element.diziler(): SearchResponse? {
        val title = this.selectFirst("div.categorytitle a")?.text()?.substringBefore(" izle") ?: return null
        val href = fixUrlNull(this.selectFirst("div.categorytitle a")?.attr("href")) ?: return null
        val posterUrl = this.selectFirst("div.cat-img img")?.imageUrl()
        val score = this.selectFirst("div.imdbp")?.text()?.replace("(IMDb:", "")?.replace(")", "")?.trim()
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
        val actors = document.selectXpath("//div[span[contains(text(), 'Oyuncular')]]").text().substringAfter("Oyuncular : ").split(", ").map { Actor(it.trim()) }
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

    private fun normalizeMediaUrl(value: String): String? {
        var url = value
            .trim()
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .replace("&quot;", "")
            .trim('"', '\'', '`', '”', '“', '’', '‘')

        if (url.startsWith("//")) url = "https:$url"
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        return url
    }

    private fun mediaUrlsFromHtml(html: String): List<String> {
        val regex = Regex(
            """https?:\\?/\\?/[^\\s\"'<>]+?(?:\\.m3u8|\\.mp4)(?:\\?[^\\s\"'<>]*)?""",
            RegexOption.IGNORE_CASE
        )
        return regex.findAll(html)
            .mapNotNull { normalizeMediaUrl(it.value) }
            .distinct()
            .toList()
    }

    private fun looksLikeMedia(url: String): Boolean =
        Regex("""\.(m3u8|mp4)(?:$|\?)""", RegexOption.IGNORE_CASE).containsMatchIn(url)

    private suspend fun addDirectMediaLink(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val normalized = normalizeMediaUrl(url) ?: return
        val type = if (normalized.contains(".m3u8", ignoreCase = true)) {
            ExtractorLinkType.M3U8
        } else {
            ExtractorLinkType.VIDEO
        }

        callback(
            newExtractorLink(
                source = "DiziMom",
                name = "DiziMom",
                url = normalized,
                type = type
            ) {
                this.referer = referer
                this.quality = 0
            }
        )
        Log.d("DZM", "direct media » $normalized")
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("DZM", "data » $data")

        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36"
        )

        val rootDocument = app.get(data, headers = headers, referer = mainUrl).document
        val links = linkedSetOf<String>()
        val visited = mutableSetOf<String>()

        fun addCandidate(value: String?) {
            val url = value?.trim()?.takeIf { it.isNotEmpty() } ?: return
            fixUrlNull(url)?.let { links.add(it) }
        }

        // First collect real media URLs that are present directly in the episode HTML.
        mediaUrlsFromHtml(rootDocument.outerHtml()).forEach { links.add(it) }

        rootDocument.select("iframe, embed, video, source").forEach { element ->
            listOf("src", "data-src", "data-lazy-src", "data-url", "data-embed", "file").forEach { attr ->
                addCandidate(element.attr(attr))
            }
        }

        rootDocument.select("a[data-embed], a[data-src], a[data-url], div.sources a, .sources a").forEach { element ->
            addCandidate(element.attr("href"))
            addCandidate(element.attr("data-embed"))
            addCandidate(element.attr("data-src"))
            addCandidate(element.attr("data-url"))
        }

        var loaded = false
        val queue = ArrayDeque<Pair<String, String>>()
        links.forEach { queue.add(it to data) }

        // Some DiziMom pages use an iframe player. Open the iframe itself and
        // inspect its HTML for the actual m3u8/mp4 before falling back to CS extractors.
        var depth = 0
        while (queue.isNotEmpty() && depth < 20) {
            val (link, referer) = queue.removeFirst()
            if (!visited.add(link)) continue
            depth++

            if (looksLikeMedia(link)) {
                addDirectMediaLink(link, referer, callback)
                loaded = true
                continue
            }

            try {
                val frameDocument = app.get(link, headers = headers, referer = referer).document
                val frameHtml = frameDocument.outerHtml()

                mediaUrlsFromHtml(frameHtml).forEach {
                    addDirectMediaLink(it, link, callback)
                    loaded = true
                }

                frameDocument.select("video, source, iframe, embed").forEach { element ->
                    listOf("src", "data-src", "data-lazy-src", "data-url", "data-embed", "file").forEach { attr ->
                        val candidate = fixUrlNull(element.attr(attr)) ?: return@forEach
                        if (looksLikeMedia(candidate)) {
                            addDirectMediaLink(candidate, link, callback)
                            loaded = true
                        } else if (visited.size < 20) {
                            queue.add(candidate to link)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d("DZM", "iframe parse failed: $link - ${e.message}")
            }

            // Let CloudStream's normal extractor system handle supported players too.
            try {
                if (loadExtractor(link, referer, subtitleCallback, callback)) {
                    loaded = true
                    Log.d("DZM", "extractor accepted » $link")
                }
            } catch (e: Exception) {
                Log.d("DZM", "Extractor failed: $link - ${e.message}")
            }
        }

        return loaded
    }
}
