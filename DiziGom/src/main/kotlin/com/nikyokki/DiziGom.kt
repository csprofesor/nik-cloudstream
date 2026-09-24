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
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

class DiziGom : MainAPI() {
    override var mainUrl = "https://www.dizigom.icu"
    override var name = "DiziGom"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/dizi-izle/" to "Tüm Diziler",
        "$mainUrl/dizi-izle/?tur=Aksiyon" to "Aksiyon",
        "$mainUrl/dizi-izle/?tur=Animasyon" to "Animasyon",
        "$mainUrl/dizi-izle/?tur=Belgesel" to "Belgesel",
        "$mainUrl/dizi-izle/?tur=Bilim%20Kurgu" to "Bilim Kurgu",
        "$mainUrl/dizi-izle/?tur=Biyografi" to "Biyografi",
        "$mainUrl/dizi-izle/?tur=Dram" to "Dram",
        "$mainUrl/dizi-izle/?tur=Fantazi" to "Fantazi",
        "$mainUrl/dizi-izle/?tur=Gençlik" to "Gençlik",
        "$mainUrl/dizi-izle/?tur=Gerilim" to "Gerilim",
        "$mainUrl/dizi-izle/?tur=Gizem" to "Gizem",
        "$mainUrl/dizi-izle/?tur=Komedi" to "Komedi",
        "$mainUrl/dizi-izle/?tur=Korku" to "Korku",
        "$mainUrl/dizi-izle/?tur=Macera" to "Macera",
        "$mainUrl/dizi-izle/?tur=Polisiye" to "Polisiye",
        "$mainUrl/dizi-izle/?tur=Romantik" to "Romantik",
        "$mainUrl/dizi-izle/?tur=Savaş" to "Savaş",
        "$mainUrl/dizi-izle/?tur=Suç" to "Suç",
        "$mainUrl/dizi-izle/?tur=Tarih" to "Tarih"
    )

    private fun cleanUrl(value: String?): String? = value
        ?.replace("\\/", "/")
        ?.replace("\\u0026", "&")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let { if (it.startsWith("//")) "https:$it" else it }
        ?.let { fixUrlNull(it) }

    private fun Element.backgroundUrl(): String? {
        val style = attr("style")
        val match = Regex("url\\((?:\\\"|')?([^\\\"')]+)", RegexOption.IGNORE_CASE).find(style)
        return cleanUrl(match?.groupValues?.getOrNull(1))
    }

    // DDizi'deki çalışan yöntemle aynı mantık: özellikle img-back/img-back-cat
    // elemanlarını önce dene, sonra tüm lazy-load ve background kaynaklarını tara.
    private fun Element.posterUrl(): String? {
        val images = select("img.img-back, img.img-back-cat, img")
        val attributes = listOf(
            "data-src", "data-poster", "data-bg", "data-background", "data-image",
            "data-img", "data-thumb", "data-thumbnail", "data-cover", "data-url",
            "data-lazy-src", "data-original", "data-wpfc-original-src", "data-lazyload",
            "data-lazy", "data-image-url", "data-poster-url", "data-srcset",
            "data-lazy-srcset", "srcset", "src"
        )

        val candidates = sequence {
            // DDizi'nin çalışan parserındaki kritik selector doğrudan burada.
            for (img in images) {
                for (attribute in attributes) yield(img.attr(attribute))
                yield(img.attr("style"))
                yield(img.parent()?.attr("style"))
            }
            for (element in listOf(this@posterUrl)) {
                for (attribute in attributes) yield(element.attr(attribute))
                yield(element.attr("style"))
                yield(backgroundUrl())
            }
        }

        return candidates
            .filterNotNull()
            .filter { it.isNotBlank() }
            .flatMap { raw ->
                raw.split(",").asSequence().map { it.trim().substringBefore(" ") }
            }
            .mapNotNull { cleanUrl(it) }
            .firstOrNull { url ->
                !url.startsWith("data:image/", true) &&
                    !url.equals("about:blank", true) &&
                    !url.contains("placeholder", true) &&
                    !url.contains("placehold", true) &&
                    !url.contains("lazy", true)
            }
    }

    private fun Element.findCard(): Element {
        if (hasClass("episode-box") || hasClass("single-item") ||
            hasClass("dizi-boxpost") || hasClass("dizi-boxpost-cat")) return this
        return generateSequence(this as Element?) { it.parent() }
            .take(12)
            .firstOrNull {
                it.hasClass("episode-box") || it.hasClass("single-item") ||
                    it.hasClass("dizi-boxpost") || it.hasClass("dizi-boxpost-cat")
            }
            ?: this
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        // Güncel Dizigom kart yapısı: div.single-item
        val titleEl = selectFirst("div.serie-name a")
            ?: selectFirst("div.categorytitle a")
            ?: selectFirst(".categorytitle a")
            ?: selectFirst("a[href]")

        val href = titleEl?.attr("href")?.let { cleanUrl(it) } ?: return null
        val title = titleEl.text().trim().takeIf { it.isNotBlank() } ?: return null

        val poster = selectFirst("div.cat-img img")?.let { img ->
            listOf(
                img.attr("src"),
                img.attr("data-src"),
                img.attr("data-lazy-src"),
                img.attr("data-original"),
                img.attr("srcset")
            ).asSequence()
                .filter { it.isNotBlank() }
                .flatMap { raw -> raw.split(",").asSequence().map { it.trim().substringBefore(" ") } }
                .mapNotNull { cleanUrl(it) }
                .firstOrNull {
                    !it.startsWith("data:image/", true) &&
                    !it.contains("placeholder", true)
                }
        } ?: posterUrl()

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            posterUrl = poster
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = runCatching {
            app.get("${request.data}#p=$page", referer = "$mainUrl/").document
        }.getOrNull() ?: return newHomePageResponse(
            request.name,
            emptyList(),
            hasNext = false
        )

        if (page == 1) {
            val results = document.select("div.episode-box, div.single-item, div.dizi-boxpost, div.dizi-boxpost-cat")
                .mapNotNull { it.toMainPageResult() }
                .distinctBy { it.url }

            Log.d("DiziGom", "${request.name}: page=1 count=${results.size}")
            return newHomePageResponse(
                request.name,
                results,
                hasNext = results.isNotEmpty()
            )
        }

        val form = document.selectFirst("form.dizigom_advenced_search")
        val nonce = form?.selectFirst("input[name=_wpnonce]")?.attr("value")
            ?: document.selectFirst("input[name=_wpnonce]")?.attr("value")

        val taxInput = form?.select("input[name]")
            ?.firstOrNull { it.attr("name") != "_wpnonce" }

        val tax = taxInput?.attr("name")
        val value = taxInput?.attr("value")

        if (tax.isNullOrBlank() || value.isNullOrBlank() || nonce.isNullOrBlank()) {
            Log.d("DiziGom", "${request.name}: AJAX form bilgisi bulunamadı")
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }

        val pageDocument = runCatching {
            app.post(
                "$mainUrl/wp-admin/admin-ajax.php",
                cookies = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to request.data
                ),
                data = mapOf(
                    "action" to "dizigom_search_action",
                    "formData" to "$tax=$value",
                    "paged" to page.toString(),
                    "_wpnonce" to nonce
                )
            ).document
        }.getOrNull() ?: return newHomePageResponse(
            request.name,
            emptyList(),
            hasNext = false
        )

        val results = pageDocument.select("div.episode-box, div.single-item, div.dizi-boxpost, div.dizi-boxpost-cat")
            .mapNotNull { it.toMainPageResult() }
            .distinctBy { it.url }

        Log.d("DiziGom", "${request.name}: page=$page count=${results.size}")

        return newHomePageResponse(
            request.name,
            results,
            hasNext = results.isNotEmpty()
        )
    }



    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get(
            "$mainUrl/?s=${query.trim().replace(" ", "+")}",
            referer = "$mainUrl/"
        ).document
        return document.select("div.single-item, div.dizi-boxpost, div.dizi-boxpost-cat")
            .mapNotNull { it.toMainPageResult() }
            .distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    private fun Element.firstText(vararg selectors: String): String? = selectors.asSequence()
        .mapNotNull { selectFirst(it)?.text()?.trim() }
        .firstOrNull { it.isNotBlank() }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, referer = "$mainUrl/").document
        val title = document.firstText("div.serieTitle h1", ".serieTitle h1", "h1.entry-title", "article h1", "h1")
            ?: return null

        val poster = document.selectFirst("meta[property='og:image']")?.attr("content")?.let { cleanUrl(it) }
            ?: document.selectFirst("div.seriePoster")?.posterUrl()
            ?: document.selectFirst("div.seriePoster img")?.posterUrl()
            ?: document.selectFirst("[class*='seriePoster']")?.posterUrl()
            ?: document.selectFirst("div.dizi-boxpost, div.dizi-boxpost-cat")?.posterUrl()
            ?: document.selectFirst("article img, .entry-content img")?.posterUrl()
            ?: document.selectFirst("img.img-back, img.img-back-cat")?.posterUrl()
            ?: document.selectFirst("img")?.posterUrl()

        val description = document.firstText(
            "div.serieDescription p", ".serieDescription p", ".description p", ".entry-content p"
        )

        val year = Regex("(?:Yapım Yılı|Yapim Yili)\\s*:?\\s*(\\d{4})", RegexOption.IGNORE_CASE)
            .find(document.text())?.groupValues?.getOrNull(1)?.toIntOrNull()
        val rating = Regex("(?:IMDB|IMDb)\\s*:?\\s*([0-9]+(?:[.,][0-9]+)?)", RegexOption.IGNORE_CASE)
            .find(document.text())?.groupValues?.getOrNull(1)

        val tags = document.select("div.genreList a, .genreList a")
            .map { it.text().trim() }.filter { it.isNotBlank() }.distinct()

        val actors = document.select("div.owl-stage a, .cast a, .actors a")
            .mapNotNull { link ->
                val actor = link.text().trim()
                if (actor.isBlank()) null else Actor(actor, link.posterUrl())
            }.distinctBy { it.name }

        val episodes = document.select("div.bolumust, a[href*='-sezon-'][href*='-bolum']")
            .mapNotNull { element ->
                val link = if (element.tagName() == "a") element else element.selectFirst("a") ?: return@mapNotNull null
                val href = cleanUrl(link.attr("href")) ?: return@mapNotNull null
                val source = "${element.text()} ${link.attr("title")}".trim()
                val season = Regex("(\\d+)\\s*\\.?\\s*Sezon", RegexOption.IGNORE_CASE)
                    .find(source)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: Regex("-(\\d+)-sezon-", RegexOption.IGNORE_CASE).find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
                val episode = Regex("(\\d+)\\s*\\.?\\s*Bölüm", RegexOption.IGNORE_CASE)
                    .find(source)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: Regex("-(\\d+)-bolum", RegexOption.IGNORE_CASE).find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
                if (season == null || episode == null) return@mapNotNull null
                newEpisode(href) {
                    name = element.selectFirst("div.bolum-ismi")?.text()?.trim() ?: element.text().trim()
                    this.season = season
                    this.episode = episode
                }
            }.distinctBy { it.data }
            .sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))

        Log.d("DiziGom", "Loaded $title poster=$poster episodes=${episodes.size}")
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            posterUrl = poster
            this.year = year
            plot = description
            this.tags = tags
            score = com.lagradost.cloudstream3.Score.from10(rating)
            addActors(actors)
        }
    }

    private fun extractPlayerUrl(document: org.jsoup.nodes.Document): String? {
        return document.select("iframe[src], frame[src]")
            .mapNotNull { cleanUrl(it.attr("src")) }
            .firstOrNull { it.contains("s.php", true) || it.contains("pilavyerplay", true) || it.contains("pilayerplay", true) }
            ?: Regex("https?://[^\\\"'\\s<>]+/s\\.php\\?[^\\\"'\\s<>]+", RegexOption.IGNORE_CASE)
                .find(document.html())?.value?.let { cleanUrl(it) }
    }

    private fun extractPlayerStream(html: String): String? {
        val stream = Regex(
            "\"stream\"\\s*:\\s*\"([^\"]+)\"",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.getOrNull(1)
        if (!stream.isNullOrBlank()) return cleanUrl(stream)

        return Regex(
            "https?://[^\\\"'\\s<>]+/api/stream\\.php(?:\\?[^\\\"'\\s<>]+)?",
            RegexOption.IGNORE_CASE
        ).find(html)?.value?.let { cleanUrl(it) }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("DiziGom", "Resolving episode: $data")
        val document = runCatching { app.get(data, referer = "$mainUrl/").document }.getOrNull() ?: return false

        document.select("iframe[src], frame[src]").mapNotNull { it.attr("src") }.forEach {
            val src = cleanUrl(it) ?: return@forEach
            if (!src.contains("s.php", true) && !src.contains("pilavyerplay", true) && !src.contains("youtube", true) && !src.contains("pilayerplay", true)) {
                loadExtractor(src, data, subtitleCallback, callback)
            }
        }

        val playerUrl = extractPlayerUrl(document)
        if (!playerUrl.isNullOrBlank()) {
            val playerResponse = runCatching { app.get(playerUrl, referer = data) }.getOrNull()
            val playerHtml = playerResponse?.text.orEmpty()
            val streamUrl = extractPlayerStream(playerHtml)

            val subs = Regex(
                "\"subs\"\\s*:\\s*\\[(.*?)]",
                RegexOption.IGNORE_CASE
            ).find(playerHtml)?.groupValues?.getOrNull(1)

            if (!subs.isNullOrBlank()) {
                val subItems = Regex(
                    "\\{[^}]*\\}",
                    RegexOption.IGNORE_CASE
                ).findAll(subs)
                for (item in subItems) {
                    val lang = Regex("\"label\"\\s*:\\s*\"([^\"]+)\"").find(item.value)?.groupValues?.getOrNull(1) ?: "Turkce"
                    val src = Regex("\"src\"\\s*:\\s*\"([^\"]+)\"").find(item.value)?.groupValues?.getOrNull(1)
                    if (src != null) {
                        val subUrl = cleanUrl(src)
                        if (subUrl != null) {
                            subtitleCallback(SubtitleFile(lang, subUrl))
                        }
                    }
                }
            }

            if (!streamUrl.isNullOrBlank()) {
                Log.d("DiziGom", "PilayerPlay stream bulundu")
                callback(
                    newExtractorLink(
                        source = name,
                        name = "DiziGom 1080p",
                        url = streamUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        referer = playerUrl
                        quality = 1080
                    }
                )
                return true
            }

            val directPlayerUrl = Regex(
                "https?://[^\\\"'\\s<>]+(?:\\.m3u8(?:\\?[^\\\"'\\s<>]*)?|\\.mp4(?:\\?[^\\\"'\\s<>]*)?)",
                RegexOption.IGNORE_CASE
            ).findAll(playerHtml).map { cleanUrl(it.value) }.filterNotNull().distinct().toList()

            for (stream in directPlayerUrl) {
                callback(
                    newExtractorLink(source = name, name = "DiziGom", url = stream,
                        type = if (stream.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                        referer = playerUrl
                        quality = getQualityFromName(stream)
                    }
                )
            }
            if (directPlayerUrl.isNotEmpty()) return true
        }

        val directUrls = Regex(
            "https?://[^\\\"'\\s<>]+(?:\\.m3u8(?:\\?[^\\\"'\\s<>]*)?|\\.mp4(?:\\?[^\\\"'\\s<>]*)?)",
            RegexOption.IGNORE_CASE
        ).findAll(document.html()).map { cleanUrl(it.value) }.filterNotNull().distinct().toList()

        for (stream in directUrls) {
            callback(
                newExtractorLink(source = name, name = "DiziGom", url = stream,
                    type = if (stream.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                    referer = data
                    quality = getQualityFromName(stream)
                }
            )
        }
        return directUrls.isNotEmpty()
    }
}
