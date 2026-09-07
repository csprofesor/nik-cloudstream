package com.nikyokki

import android.util.Base64
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
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class FilmIzleIlk : MainAPI() {
    override var mainUrl = "https://www.filmizleilk.fit"
    override var name = "Filmİzleİlk"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    override val mainPage = mainPageOf(
        "${mainUrl}/" to "Son Filmler",
        "${mainUrl}/film/aile-filmleri/" to "Aile",
        "${mainUrl}/film/aksiyon-filmleri/" to "Aksiyon",
        "${mainUrl}/film/amazon-prime-filmleri/" to "Amazon Prime",
        "${mainUrl}/film/animasyon-filmleri/" to "Animasyon",
        "${mainUrl}/film/ask-filmleri/" to "Aşk",
        "${mainUrl}/film/belgesel-filmleri/" to "Belgesel",
        "${mainUrl}/film/bilimkurgu-filmleri/" to "Bilim Kurgu",
        "${mainUrl}/film/biyografi-filmleri/" to "Biyografi",
        "${mainUrl}/film/cizgi-filmler/" to "Çizgi",
        "${mainUrl}/film/cocuk-filmleri/" to "Çocuk",
        "${mainUrl}/film/dram-filmleri/" to "Dram",
        "${mainUrl}/film/fantastik-filmler/" to "Fantastik",
        "${mainUrl}/film/gelecek-filmler/" to "Gelecek",
        "${mainUrl}/film/gerilim-filmleri/" to "Gerilim",
        "${mainUrl}/film/gizemli-filmler/" to "Gizemli",
        "${mainUrl}/film/hint-filmleri/" to "Hint",
        "${mainUrl}/film/komedi-filmleri/" to "Komedi",
        "${mainUrl}/film/kore-filmleri/" to "Kore",
        "${mainUrl}/film/korku-filmleri/" to "Korku",
        "${mainUrl}/film/macera-filmleri/" to "Macera",
        "${mainUrl}/film/muzikal-filmleri/" to "Müzikal",
        "${mainUrl}/film/netflix-filmleri/" to "Netflix",
        "${mainUrl}/film/nette-ilk-filmleri/" to "Nette İlk",
        "${mainUrl}/film/polisiye-filmleri/" to "Polisiye",
        "${mainUrl}/film/romantik-filmleri/" to "Romantik",
        "${mainUrl}/film/savas-filmleri/" to "Savaş",
        "${mainUrl}/film/spor-filmleri/" to "Spor",
        "${mainUrl}/film/suc-filmleri/" to "Suç",
        "${mainUrl}/film/tarihi-filmleri/" to "Tarihi",
        "${mainUrl}/film/tavsiye-filmleri/" to "Tavsiye",
        "${mainUrl}/film/turk-filmleri/" to "Türk",
        "${mainUrl}/film/western-filmleri/" to "Western"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val baseUrl = request.data.trimEnd('/')
        val pageUrl = if (page <= 1) {
            "$baseUrl/"
        } else {
            "$baseUrl/page/$page/"
        }
        val document = app.get(pageUrl, headers = browserHeaders).document

        val oldLayout = document.select("div.movie-box").mapNotNull { it.toSearchResult() }
        val home = if (oldLayout.isNotEmpty()) {
            oldLayout
        } else {
            document.select("a[href]").mapNotNull { it.toSearchResultFromLink() }.distinctBy { it.url }
        }

        Log.d("FII", "main page '${request.name}' page=$page url=$pageUrl results=${home.size}")
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("div.name a")?.text()?.trim() ?: return null
        val href = fixUrlNull(this.selectFirst("div.name a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("div.img img")?.attr("src"))
            ?: fixUrlNull(this.selectFirst("img")?.attr("src"))
        val score = this.selectFirst("div.rating span, div.imdb, .imdb, [class*=rating]")?.text()?.trim()
        return makeSearchResult(title, href, posterUrl, score)
    }

    private fun Element.toSearchResultFromLink(): SearchResponse? {
        val href = fixUrlNull(attr("href")) ?: return null
        if (!href.startsWith(mainUrl)) return null

        val path = href.removePrefix(mainUrl).substringBefore("?").trim('/')
        if (path.isBlank() || path == "page" || path.startsWith("film/") ||
            path.startsWith("kategori/") || path.startsWith("search") ||
            path.startsWith("giris") || path.startsWith("kayit") || path.startsWith("iletisim")) {
            return null
        }

        val title = text().trim().replace(Regex("\\s+"), " ")
            .takeIf { it.length >= 2 }
            ?: selectFirst("img")?.attr("alt")?.trim()
            ?: return null

        if (title.equals("Anasayfa", ignoreCase = true) ||
            title.equals("Daha fazla yükle", ignoreCase = true)) return null

        val posterUrl = fixUrlNull(selectFirst("img")?.attr("src"))
            ?: parents().take(5).asSequence()
                .mapNotNull { fixUrlNull(it.selectFirst("img")?.attr("src")) }
                .firstOrNull()

        val score = text().trim().let { Regex("(?:^|\\s)(\\d+(?:\\.\\d+)?)\\s*$").find(it)?.groupValues?.getOrNull(1) }
        return makeSearchResult(title, href, posterUrl, score)
    }

    private fun makeSearchResult(title: String, href: String, posterUrl: String?, score: String?): SearchResponse {
        return if (href.contains("/dizi/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.score = Score.from10(score)
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.score = Score.from10(score)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}?s=${query}", headers = browserHeaders).document
        val oldLayout = document.select("div.movie-box").mapNotNull { it.toSearchResult() }
        return if (oldLayout.isNotEmpty()) oldLayout
        else document.select("a[href]").mapNotNull { it.toSearchResultFromLink() }.distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = browserHeaders, referer = "${mainUrl}/").document
        val title = document.selectFirst("div.film h1")?.text()?.trim()
            ?: document.selectFirst("h1.film")?.text()?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: return null
        val poster = fixUrlNull(document.selectFirst("[property='og:image']")?.attr("content"))
            ?: fixUrlNull(document.selectFirst("meta[name='twitter:image']")?.attr("content"))
            ?: fixUrlNull(document.selectFirst("img")?.attr("src"))
        val description = document.selectFirst("div.description")?.text()?.trim()
            ?: document.selectFirst("[class*=description]")?.text()?.trim()
        var tags = document.select("ul.post-categories a").map { it.text() }
        val rating = document.selectFirst("div.imdb-count")?.text()?.trim()?.split(" ")?.first()
        val year = Regex("""(\\d+)""").find(document.selectFirst("li.release")?.text()?.trim() ?: "")?.groupValues?.get(1)?.toIntOrNull()
        val duration = Regex("""(\\d+)""").find(document.selectFirst("li.time")?.text()?.trim() ?: "")?.groupValues?.get(1)?.toIntOrNull()
        val recommendations = document.select("div.movie-box").mapNotNull { it.toSearchResult() }
        val actors = document.select("[href*='oyuncular']").map { Actor(it.text()) }

        if (url.contains("/dizi/")) {
            tags = document.select("div.category a").map { it.text() }
            val episodes = document.select("div.episode-box").mapNotNull {
                val epHref = fixUrlNull(it.selectFirst("div.name a")?.attr("href")) ?: return@mapNotNull null
                val ssnDetail = it.selectFirst("span.episodetitle")?.ownText()?.trim() ?: return@mapNotNull null
                val epDetail = it.selectFirst("span.episodetitle b")?.ownText()?.trim() ?: return@mapNotNull null
                val epName = "$ssnDetail - $epDetail"
                newEpisode(epHref) {
                    this.name = epName
                    this.season = ssnDetail.substringBefore(". ").toIntOrNull()
                    this.episode = epDetail.substringBefore(". ").toIntOrNull()
                }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = Score.from10(rating)
                this.duration = duration
                this.recommendations = recommendations
                addActors(actors)
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.year = year
            this.plot = description
            this.tags = tags
            this.score = Score.from10(rating)
            this.duration = duration
            this.recommendations = recommendations
            addActors(actors)
        }
    }

    private fun getIframe(sourceCode: String): String {
        val atob = Regex("""PHA\\+[0-9a-zA-Z+/=]*""").find(sourceCode)?.value ?: return ""
        val padding = 4 - atob.length % 4
        val atobPadded = if (padding < 4) atob.padEnd(atob.length + padding, '=') else atob
        val iframe = Jsoup.parse(String(Base64.decode(atobPadded, Base64.DEFAULT), Charsets.UTF_8))
        return fixUrlNull(iframe.selectFirst("iframe[src]")?.attr("src")) ?: ""
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("FII", "data » $data")
        val document = app.get(data, headers = browserHeaders, referer = "${mainUrl}/").document
        val iframes = mutableSetOf<String>()

        document.select("iframe[src]").forEach { element ->
            fixUrlNull(element.attr("src"))?.takeIf { it.isNotBlank() }?.let { iframes.add(it) }
        }

        document.select("div.parts-middle a[href]").forEach { element ->
            val alternative = fixUrlNull(element.attr("href")) ?: return@forEach
            runCatching {
                val alternativeDocument = app.get(alternative, headers = browserHeaders, referer = data).document
                getIframe(alternativeDocument.html()).takeIf { it.isNotBlank() }?.let { iframes.add(it) }
            }.onFailure { Log.e("FII", "alternative source failed", it) }
        }

        if (iframes.isEmpty()) {
            Log.e("FII", "No iframe/source found for $data")
            return false
        }

        val candidates = buildList {
            iframes.firstOrNull { it.contains("oneload", ignoreCase = true) }?.let(::add)
            iframes.firstOrNull { it.contains("ok.ru", ignoreCase = true) || it.contains("odnoklassniki", ignoreCase = true) }?.let(::add)
            iframes.firstOrNull { it.contains("vidmoly", ignoreCase = true) }?.let(::add)
            iframes.filterNot {
                it.contains("oneload", ignoreCase = true) ||
                it.contains("ok.ru", ignoreCase = true) ||
                it.contains("odnoklassniki", ignoreCase = true) ||
                it.contains("vidmoly", ignoreCase = true) ||
                it.contains("videopress", ignoreCase = true) ||
                it.contains("wordpress", ignoreCase = true)
            }.forEach(::add)
        }.distinct()

        if (candidates.isEmpty()) {
            Log.e("FII", "No supported playback provider found for $data")
            return false
        }

        for (provider in candidates) {
            Log.d("FII", "trying provider » $provider")
            var emittedLink = false
            val providerCallback: (ExtractorLink) -> Unit = { link ->
                emittedLink = true
                callback(link)
            }
            val result = runCatching {
                loadExtractor(provider, data, subtitleCallback, providerCallback)
            }.onFailure { Log.e("FII", "Extractor failed: $provider", it) }.getOrDefault(false)
            if (emittedLink) {
                Log.d("FII", "playable link emitted by » $provider")
                return true
            }
            Log.d("FII", "no playable link from » $provider (result=$result), trying next")
        }

        Log.e("FII", "All playback providers failed for $data")
        return false
    }
}
