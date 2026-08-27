package com.lagradost

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.extractors.ByseSX
import com.lagradost.cloudstream3.extractors.DoodLaExtractor
import com.lagradost.cloudstream3.extractors.FilemoonV2
import com.lagradost.cloudstream3.extractors.Gdriveplayer
import com.lagradost.cloudstream3.extractors.Odnoklassniki
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.extractors.UpstreamExtractor
import com.lagradost.cloudstream3.extractors.Vidoza
import com.lagradost.cloudstream3.extractors.Vidmoly
import com.lagradost.cloudstream3.extractors.Voe
import org.jsoup.Jsoup
import org.jsoup.nodes.Element


class Anizm : MainAPI() {
    override var mainUrl = "https://anizm.net"
    override var name = "Anizm"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {
        private const val mainServer = "https://anizmplayer.com"
    }

    override val mainPage = mainPageOf(
        "$mainUrl/anime-izle?sayfa=" to "Son Eklenen Animeler",
        "$mainUrl/kategoriler/4" to "Dram",
        "$mainUrl/kategoriler/2" to "Aksiyon",
        "$mainUrl/kategoriler/8" to "Bilim-Kurgu",
        "$mainUrl/kategoriler/20" to "Korku",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (request.data.contains("/kategoriler/")) {
            if (page <= 1) request.data else "${request.data}?page=$page"
        } else {
            request.data + page
        }

        val document = app.get(url).document

        val home = if (request.data.contains("/kategoriler/")) {
            val heading = document.select("h1, h2, h3, h4")
                .firstOrNull { it.text().contains("Kategorisindeki Animeler", ignoreCase = true) }

            val categoryContainer = generateSequence(heading?.parent()) { it.parent() }
                .take(8)
                .firstOrNull { container ->
                    container.select("a[href]").count { anchor ->
                        val href = fixUrl(anchor.attr("href").trim())
                        val text = anchor.text().trim()
                        href.startsWith(mainUrl) &&
                            !href.contains("/kategoriler/") &&
                            !href.contains("/anime-izle") &&
                            text.length > 2 &&
                            !text.equals("İzle", ignoreCase = true)
                    } >= 5
                }

            categoryContainer
                ?.select("a[href]")
                ?.mapNotNull { it.toSearchResult() }
                ?.distinctBy { it.url }
                .orEmpty()
        } else {
            document.select("div#episodesMiddle div.posterBlock > a")
                .mapNotNull { it.toSearchResult() }
                .distinctBy { it.url }
        }

        val hasNext = document.selectFirst(
            "div.nextBeforeButtons > div.ui > a.right:not(.disabled), " +
                "div.nextBeforeButtons a.right:not(.disabled), " +
                "a[rel=next]:not(.disabled)"
        ) != null

        return newHomePageResponse(request.name, home, hasNext = hasNext)
    }

    private fun getProperAnimeLink(uri: String): String {
        return if (uri.contains("-bolum")) {
            "$mainUrl/${uri.substringAfter("$mainUrl/").replace(Regex("-[0-9]+-bolum.*"), "")}"
        } else {
            uri
        }
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val link = if (tagName() == "a") this else selectFirst("a") ?: return null
        val rawHref = link.attr("href").trim()
        if (rawHref.isBlank()) return null

        val absoluteHref = fixUrl(rawHref)
        val href = getProperAnimeLink(absoluteHref)
        if (!href.startsWith(mainUrl)) return null
        if (href.contains("/kategoriler/") || href.contains("/anime-izle")) return null

        var card: Element = this
        if (tagName() == "a") {
            var parent = parent()
            var foundCard = false
            repeat(4) {
                if (parent == null) return@repeat
                if (parent.selectFirst("img") != null) {
                    card = parent
                    foundCard = true
                    return@repeat
                }
                parent = parent.parent()
            }
            if (!foundCard) return null
        }

        val title = card.selectFirst("div.title, h5.animeTitle a, .title, h5, h4, h3")?.text()?.trim()
            ?: card.selectFirst("img")?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
            ?: link.attr("title").trim().takeIf { it.isNotBlank() }
            ?: link.text().trim().takeIf { it.isNotBlank() && !it.equals("İzle", ignoreCase = true) }
            ?: return null

        val posterUrl = fixUrlNull(
            card.selectFirst("img")?.let { image ->
                image.attr("data-src").ifBlank {
                    image.attr("data-original").ifBlank {
                        image.attr("data-lazy-src").ifBlank {
                            image.attr("src")
                        }
                    }
                }
            }
        )

        val episodeText = card.selectFirst("div.truncateText, div.episodeBlock")?.text() ?: link.text()
        val episode = Regex("""([0-9]+).?\s?Bölüm""", RegexOption.IGNORE_CASE)
            .find(episodeText)?.groupValues?.getOrNull(1)?.toIntOrNull()

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addSub(episode)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get(
            "$mainUrl/fullViewSearch?search=$query&skip=0",
            headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        ).document

        return document.select("div.searchResultItem, div.posterBlock")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst(
            "h1, h2.anizm_pageTitle a, h2.anizm_pageTitle, .animeTitle, .title"
        )?.text()?.trim().takeIf { !it.isNullOrBlank() }
            ?: url.substringAfterLast("/").replace("-", " ").trim()

        val poster = fixUrlNull(
            document.selectFirst("div.infoPosterImg > img")?.let { img ->
                img.attr("src").ifBlank {
                    img.attr("data-src").ifBlank {
                        img.attr("data-original").ifBlank { img.attr("data-lazy-src") }
                    }
                }
            }
        )

        val episodes = document.select("a[href]")
            .mapNotNull { a ->
                val name = a.text().trim()
                val href = a.attr("href").trim()
                if (href.isBlank() || !href.contains("-bolum", ignoreCase = true)) return@mapNotNull null
                if (!name.contains("Bölüm", ignoreCase = true)) return@mapNotNull null
                Pair(fixUrl(href), name)
            }
            .distinctBy { it.first }
            .map { (href, name) ->
                newEpisode(href) { this.name = name }
            }

        val type = if (episodes.isEmpty()) TvType.Movie else TvType.Anime
        val trailer = document.selectFirst("iframe")?.attr("src")

        return newAnimeLoadResponse(title, url, type) {
            posterUrl = poster
            addEpisodes(DubStatus.Subbed, episodes)
            plot = document.selectFirst("div.infoDesc, .infoDesc, .description")?.text()?.trim()
            this.tags = document.select("span.dataValue span.ui.label, .ui.label").map { it.text() }
            addTrailer(trailer)
        }
    }

    private suspend fun invokeLokalSource(
        url: String,
        translator: String,
        sourceCallback: (ExtractorLink) -> Unit
    ) {
        app.get(url, referer = "$mainUrl/").document.select("script").find { script ->
            script.data().contains("eval(function(p,a,c,k,e,d)")
        }?.let {
            val key = getAndUnpack(it.data()).substringAfter("FirePlayer(\"").substringBefore("\",")
            val referer = "$mainServer/video/$key"
            val link = "$mainServer/player/index.php?data=$key&do=getVideo"

            app.post(
                link,
                data = mapOf("hash" to key, "r" to "$mainUrl/"),
                referer = referer,
                headers = mapOf(
                    "Accept" to "*/*",
                    "Origin" to mainServer,
                    "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                    "X-Requested-With" to "XMLHttpRequest"
                )
            ).parsedSafe<Source>()?.videoSource?.let { m3uLink ->
                M3u8Helper.generateM3u8(
                    "${this.name} ($translator)",
                    m3uLink,
                    referer
                ).forEach(sourceCallback)
            }
        }
    }

    private suspend fun invokeSistennSource(
        iframeUrl: String,
        translator: String,
        sourceCallback: (ExtractorLink) -> Unit
    ) {
        val base = "https://sistenn.uns.bio"
        val videoId = iframeUrl.substringAfter("#", "").trim()
        if (videoId.isBlank()) return

        fun findM3u8(body: String): String? {
            return Regex("""(?:https?:)?//[^"\\s]+\\.m3u8(?:\\?[^"\\s]+)?|/hlsmod/[^"\\s]+""")
                .find(body)?.value?.let { found ->
                    when {
                        found.startsWith("//") -> "https:$found"
                        found.startsWith("/") -> "$base$found"
                        else -> found
                    }
                }
        }

        fun emit(url: String) {
            M3u8Helper.generateM3u8(
                "${this.name} ($translator)",
                url,
                iframeUrl
            ).forEach(sourceCallback)
        }

        val infoResponse = runCatching {
            app.get(
                "$base/api/v1/info?id=$videoId",
                referer = iframeUrl,
                headers = mapOf("Accept" to "application/json, text/plain, */*")
            ).text
        }.getOrNull() ?: return

        findM3u8(infoResponse)?.let { emit(it); return }

        val token = Regex("""\"(?:t|token)\":\s*\"([^\"]+)\"""")
            .find(infoResponse)?.groupValues?.getOrNull(1)

        val playerResponse = if (!token.isNullOrBlank()) {
            runCatching {
                app.get(
                    "$base/api/v1/player?t=${java.net.URLEncoder.encode(token, "UTF-8")}",
                    referer = iframeUrl,
                    headers = mapOf("Accept" to "application/json, text/plain, */*")
                ).text
            }.getOrNull().orEmpty()
        } else {
            ""
        }

        findM3u8(playerResponse)?.let { emit(it); return }

        val kx = Regex("""\"kx\":\s*\"([^\"]+)\"""")
            .find(playerResponse)?.groupValues?.getOrNull(1)
            ?: return

        val encodedKx = java.net.URLEncoder.encode(kx, "UTF-8")
        val videoResponses = listOf(
            "$base/api/v1/video?id=$encodedKx",
            "$base/api/v1/download?id=$encodedKx",
            "$base/api/v1/folder?id=$encodedKx"
        )

        for (endpoint in videoResponses) {
            val response = runCatching {
                app.get(
                    endpoint,
                    referer = iframeUrl,
                    headers = mapOf("Accept" to "application/json, text/plain, */*")
                ).text
            }.getOrNull() ?: continue

            findM3u8(response)?.let { emit(it); return }

            val encrypted = response.trim().removePrefix("\"").removeSuffix("\"")
            if (encrypted.length > 32 && encrypted.matches(Regex("[0-9a-fA-F]+"))) {
                val decrypted = listOf("1234567890oiuytr", "0123456789abcdef").firstNotNullOfOrNull { iv ->
                    runCatching {
                        val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
                        val keySpec = javax.crypto.spec.SecretKeySpec("kiemtienmua911ca".toByteArray(), "AES")
                        val ivSpec = javax.crypto.spec.IvParameterSpec(iv.toByteArray())
                        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, keySpec, ivSpec)
                        val bytes = encrypted.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                        cipher.doFinal(bytes).toString(Charsets.UTF_8)
                    }.getOrNull()
                }
                decrypted?.let { plain ->
                    findM3u8(plain)?.let { emit(it); return }
                    Regex("""\"source\":\"([^\"]+)\"""")
                        .find(plain)?.groupValues?.getOrNull(1)
                        ?.replace("\\/","/")
                        ?.let { emit(it); return }
                }
            }
        }
    }

    private suspend fun invokeKnownExtractor(
        link: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val host = runCatching {
            java.net.URI(link).host?.lowercase()?.removePrefix("www.")
        }.getOrNull() ?: return false

        val extractor: ExtractorApi = when {
            host == "voe.sx" || host.endsWith(".voe.sx") -> Voe()
            host == "vidmoly.me" || host == "vidmoly.to" || host == "vidmoly.biz" ||
                host == "vidmoly.net" -> Vidmoly()
            host.contains("gdriveplayer") || host == "databasegdriveplayer.co" ||
                host == "gdriveplayer.to" -> Gdriveplayer()
            host == "ok.ru" || host.endsWith(".ok.ru") ||
                host == "odnoklassniki.ru" || host.endsWith(".odnoklassniki.ru") -> Odnoklassniki()
            host == "filemoon.to" || host == "filemoon.in" || host == "filemoon.sx" -> FilemoonV2()
            host == "byse.sx" || host.endsWith(".byse.sx") -> ByseSX()
            host.contains("streamwish") || host.contains("embedwish") ||
                host.contains("dwish.") || host.contains("mwish.") -> StreamWishExtractor()
            host.contains("dood.") || host.contains("doodstream") -> DoodLaExtractor()
            host == "vidoza.net" || host.endsWith(".vidoza.net") ||
                host == "videzz.net" -> Vidoza()
            host == "upstream.to" || host.endsWith(".upstream.to") -> UpstreamExtractor()
            else -> return false
        }

        runCatching {
            extractor.getUrl(link, referer, subtitleCallback, callback)
        }.onFailure {
            Log.d("Anizm", "Extractor ${extractor.name} failed for $link: ${it.message}")
        }
        return true
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(
            data,
            headers = mapOf(
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )
        ).document

        // Anizm has used several different attributes for translator/player
        // links over time. Collect all known variants instead of depending on
        // one exact DOM attribute.
        val translatorElements = document.select(
            "[translator], [data-translator], a[href*='translator'], a[href*='/video/']"
        )

        val translatorLinks = translatorElements.mapNotNull { element ->
            val url = sequenceOf(
                element.attr("translator"),
                element.attr("data-translator"),
                element.attr("data-url"),
                element.attr("href")
            ).map { it.trim() }.firstOrNull { it.isNotBlank() }
                ?: return@mapNotNull null

            val absolute = fixUrl(url)
            val name = element.selectFirst(".title, .translatorCompactBox, .translatorName, .name")
                ?.text()?.trim()
                .orEmpty()
                .ifBlank { element.text().trim() }

            Pair(absolute, name)
        }.distinctBy { it.first }

        for ((url, translator) in translatorLinks) {
            safeApiCall {
                val response = app.get(
                    url,
                    referer = data,
                    headers = mapOf(
                        "Accept" to "application/json, text/javascript, */*; q=0.01",
                        "X-Requested-With" to "XMLHttpRequest"
                    )
                )

                val translatorData = response.parsedSafe<Translators>()?.data
                    ?: response.text.takeIf { it.contains("video", ignoreCase = true) }

                if (translatorData.isNullOrBlank()) return@safeApiCall

                val translatorDocument = Jsoup.parse(translatorData)

                // Accept video/data-video/data-src/href so a provider changing
                // its markup doesn't make every source disappear.
                val videoUrls = translatorDocument.select(
                    "[video], [data-video], [data-src], a[href]"
                ).mapNotNull { video ->
                    sequenceOf(
                        video.attr("video"),
                        video.attr("data-video"),
                        video.attr("data-src"),
                        video.attr("href")
                    ).map { it.trim() }
                        .firstOrNull { it.isNotBlank() }
                        ?.let { fixUrl(it) }
                }.filter {
                    it.contains("/video/") || it.contains("/player/") ||
                        it.contains("http", ignoreCase = true)
                }.distinct()

                for (videoUrl in videoUrls) {
                    safeApiCall {
                        val playerResponse = app.get(
                            videoUrl,
                            referer = data,
                            headers = mapOf(
                                "Accept" to "application/json, text/javascript, */*; q=0.01",
                                "X-Requested-With" to "XMLHttpRequest"
                            )
                        )

                        val iframeHtml = playerResponse.parsedSafe<Videos>()?.player
                            ?: playerResponse.text

                        if (iframeHtml.isNullOrBlank()) return@safeApiCall

                        val playerDocument = Jsoup.parse(iframeHtml)

                        // Some versions return iframe src, others data-src,
                        // embed/src or the provider URL directly.
                        val links = playerDocument.select(
                            "iframe, video, source, a"
                        ).flatMap { node ->
                            listOf(
                                node.attr("src"),
                                node.attr("data-src"),
                                node.attr("data-video"),
                                node.attr("href")
                            )
                        }.map { it.trim() }
                            .filter { it.isNotBlank() }
                            .map { fixUrl(it) }
                            .distinct()

                        for (link in links) {
                            when {
                                link.startsWith(mainServer, ignoreCase = true) -> {
                                    invokeLokalSource(link, translator, callback)
                                }
                                link.contains("sistenn.uns.bio", ignoreCase = true) -> {
                                    invokeSistennSource(link, translator, callback)
                                }
                                else -> {
                                    if (!invokeKnownExtractor(
                                            link,
                                            data,
                                            subtitleCallback,
                                            callback
                                        )
                                    ) {
                                        safeApiCall {
                                            loadExtractor(
                                                link,
                                                data,
                                                subtitleCallback,
                                                callback
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        return true
    }

    data class Source(
        @JsonProperty("videoSource") val videoSource: String?,
    )

    data class Videos(
        @JsonProperty("player") val player: String?,
    )

    data class Translators(
        @JsonProperty("data") val data: String?,
    )

}