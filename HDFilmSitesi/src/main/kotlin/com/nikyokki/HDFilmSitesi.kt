package com.nikyokki

import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
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
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class HDFilmSitesi : MainAPI() {
    override var mainUrl = "https://dizifilmizle.to"
    override var name = "HDFilmSitesi"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie)

    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    override val mainPage = mainPageOf(
        "${mainUrl}/tur/aile" to "Aile",
        "${mainUrl}/tur/aksiyon" to "Aksiyon",
        "${mainUrl}/tur/animasyon" to "Animasyon",
        "${mainUrl}/tur/bilim-kurgu" to "Bilim Kurgu",
        "${mainUrl}/tur/belgesel" to "Belgesel",
        "${mainUrl}/tur/dram" to "Dram",
        "${mainUrl}/tur/fantastik" to "Fantastik",
        "${mainUrl}/tur/gerilim" to "Gerilim",
        "${mainUrl}/tur/gizem" to "Gizem",
        "${mainUrl}/tur/komedi" to "Komedi",
        "${mainUrl}/tur/korku" to "Korku",
        "${mainUrl}/tur/macera" to "Macera",
        "${mainUrl}/tur/romantik" to "Romantik",
        "${mainUrl}/tur/savas" to "Savaş",
        "${mainUrl}/tur/suc" to "Suç",
        "${mainUrl}/tur/western" to "Western"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data.trimEnd('/')}/page/$page"
        val document = app.get(url, headers = browserHeaders, referer = "$mainUrl/").document
        val html = document.html()
        val posterMap = posterMapFromHtml(html)

        val cards = document.select("a.media-card__link")
            .mapNotNull { it.toCardResult(posterMap) }
            .distinctBy { it.url }
            .toMutableList()

        if (cards.isEmpty()) {
            document.select("a[href*='/film/']")
                .mapNotNull { it.toFilmLinkResult(posterMap) }
                .distinctBy { it.url }
                .forEach { cards.add(it) }
        }

        return newHomePageResponse(request.name, cards)
    }

    private fun posterMapFromHtml(html: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val regex = Regex(
            """\\?[\"']slug\\?[\"']\s*:\s*\\?[\"']([^\"\\]+)\\?[\"'].{0,1200}?\\?[\"'](?:poster_url|posterUrl)\\?[\"']\s*:\s*\\?[\"'](https?://[^\"\\]+)\\?[\"']""",
            RegexOption.DOT_MATCHES_ALL
        )
        regex.findAll(html).forEach { match ->
            val slug = match.groupValues[1]
            val poster = match.groupValues[2]
                .replace("\\/", "/")
                .replace("\\u002F", "/")
            if (slug.isNotBlank() && poster.isNotBlank()) result[slug] = poster
        }
        return result
    }

    private fun Element.findPosterUrl(): String? {
        fun normalize(value: String): String? {
            val v = value.trim().substringBefore(" ").trim()
            if (v.isBlank() || v.startsWith("data:image")) return null
            return fixUrlNull(v)
        }

        val image = selectFirst("img") ?: return null
        sequenceOf(
            image.attr("src"),
            image.attr("data-src"),
            image.attr("data-lazy-src"),
            image.attr("data-original"),
            image.attr("srcset"),
            image.attr("data-srcset")
        ).forEach { raw ->
            if (raw.isNotBlank()) {
                val first = raw.split(',').firstOrNull()?.trim().orEmpty()
                normalize(first)?.let { return it }
            }
        }
        return null
    }

    private fun Element.findCardTitle(): String? {
        return attr("aria-label").trim().takeIf { it.isNotEmpty() }
            ?: selectFirst("h3")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: selectFirst("img[alt]")?.attr("alt")?.substringBeforeLast(" izle")?.trim()
    }

    private fun Element.findCardScore(): String? {
        return select("span")
            .map { it.text().trim().replace(',', '.') }
            .firstOrNull { Regex("^([0-9]|10)(\\.[0-9])?$").matches(it) }
    }

    private fun Element.cardSlug(): String? {
        return attr("href")
            .substringAfter("/film/", "")
            .substringBefore("?")
            .substringBefore("#")
            .trim('/')
            .takeIf { it.isNotBlank() }
    }

    private fun Element.toCardResult(posterMap: Map<String, String> = emptyMap()): SearchResponse? {
        val href = fixUrlNull(attr("href")) ?: return null
        if (!href.contains("/film/")) return null
        val card = parent() ?: return null
        val title = findCardTitle() ?: return null
        val poster = cardSlug()?.let { posterMap[it] } ?: card.findPosterUrl()
        val score = card.findCardScore()
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
            this.posterHeaders = browserHeaders
            this.score = Score.from10(score)
        }
    }

    private fun Element.toFilmLinkResult(posterMap: Map<String, String> = emptyMap()): SearchResponse? {
        val href = fixUrlNull(attr("href")) ?: return null
        if (!href.contains("/film/")) return null
        val title = attr("aria-label").trim().takeIf { it.isNotEmpty() }
            ?: selectFirst("h3")?.text()?.trim()
            ?: selectFirst("img[alt]")?.attr("alt")?.substringBeforeLast(" izle")?.trim()
            ?: return null
        val poster = cardSlug()?.let { posterMap[it] }
            ?: selectFirst("img")?.let { img ->
                fixUrlNull(img.attr("src").ifBlank { img.attr("data-src") })
            }
        val score = select("span").map { it.text().trim().replace(',', '.') }
            .firstOrNull { Regex("^([0-9]|10)(\\.[0-9])?$").matches(it) }
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
            this.posterHeaders = browserHeaders
            this.score = Score.from10(score)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/arama/${query}", headers = browserHeaders, referer = "$mainUrl/").document
        val posterMap = posterMapFromHtml(document.html())
        return document.select("a.media-card__link")
            .mapNotNull { it.toCardResult(posterMap) }
            .ifEmpty {
                document.select("a[href*='/film/']").mapNotNull { it.toFilmLinkResult(posterMap) }
            }
            .distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    private fun nextJsMovieValue(html: String, key: String): String? {
        val regex = Regex(
            """\\?[\"']$key\\?[\"']\s*:\s*(?:\\?[\"']((?:\\\\.|[^\"'\\])*)\\?[\"']|([0-9]+(?:\\.[0-9]+)?)|null)""",
            RegexOption.IGNORE_CASE
        )
        val match = regex.find(html) ?: return null
        return match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() }
            ?.replace("\\/", "/")
            ?.replace("\\u002F", "/")
            ?: match.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() }
    }

    private fun nextJsVidMixiUrl(html: String): String? {
        val normalized = html
            .replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\x2F", "/")
        return Regex(
            "https?://vidmixi\\.com/embed/[A-Za-z0-9_-]+",
            RegexOption.IGNORE_CASE
        ).find(normalized)?.value
    }

    private fun jsonLdString(html: String, key: String): String? {
        return Regex("""\"$key\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"""")
            .find(html)?.groupValues?.getOrNull(1)
            ?.replace("\\/", "/")?.replace("\\u002F", "/")
    }

    private fun jsonLdNumber(html: String, key: String): String? {
        return Regex("""\"$key\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)""")
            .find(html)?.groupValues?.getOrNull(1)
    }

    private fun nextJsActors(html: String): List<Actor> {
        val result = mutableListOf<Actor>()
        val photoRegex = Regex(
            """\\?[\"']photo_url\\?[\"']\s*:\s*\\?[\"'](https?://[^\"'\\]+)\\?[\"']""",
            RegexOption.IGNORE_CASE
        )
        val nameRegex = Regex(
            """\\?[\"']name\\?[\"']\s*:\s*\\?[\"']([^\"'\\]+)\\?[\"']""",
            RegexOption.IGNORE_CASE
        )
        photoRegex.findAll(html).forEach { match ->
            val prefix = html.substring(maxOf(0, match.range.first - 1500), match.range.first)
            val name = nameRegex.findAll(prefix).lastOrNull()?.groupValues?.getOrNull(1)?.trim()
            val photo = match.groupValues.getOrNull(1)
                ?.replace("\\/", "/")
                ?.replace("\\u002F", "/")
                ?.trim()
            if (!name.isNullOrBlank() && !photo.isNullOrBlank()) result.add(Actor(name, photo))
        }
        return result.distinctBy { it.name }
    }

    private fun jsonLdActors(html: String): List<Actor> {
        val actorBlock = Regex("""\"actor\"\\s*:\\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.getOrNull(1).orEmpty()
        return Regex("""\"name\"\\s*:\\s*\"([^\"]+)\"""")
            .findAll(actorBlock).map { Actor(it.groupValues[1]) }.toList().distinctBy { it.name }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = browserHeaders, referer = "$mainUrl/").document
        val html = document.html()
        val title = nextJsMovieValue(html, "title")
            ?: jsonLdString(html, "name")
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
            ?: return null
        val poster = fixUrlNull(
            nextJsMovieValue(html, "poster_url")
                ?: nextJsMovieValue(html, "posterUrl")
                ?: document.selectFirst("meta[property='og:image']")?.attr("content")
                ?: document.selectFirst("picture img[src]")?.attr("src")
        )
        val description = nextJsMovieValue(html, "description")
            ?: jsonLdString(html, "description")
            ?: document.selectFirst("[itemprop='description']")?.text()?.trim()
        val year = nextJsMovieValue(html, "year")?.toIntOrNull()
            ?: document.selectFirst("a[href^='/yil/']")?.text()?.trim()?.toIntOrNull()
            ?: Regex("\\b(19|20)\\d{2}\\b").find(document.selectFirst("h2")?.text().orEmpty())?.value?.toIntOrNull()
        val tags = document.select("a[href^='/tur/']").map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
        val rating = nextJsMovieValue(html, "imdb_rating")
            ?: jsonLdNumber(html, "ratingValue")
            ?: document.select("span").firstOrNull { it.text().trim().matches(Regex("[0-9](\\.[0-9])?")) }?.text()?.trim()
        val duration = nextJsMovieValue(html, "duration")?.toIntOrNull()
            ?: jsonLdString(html, "duration")?.let {
                Regex("PT(?:(\\d+)H)?(?:(\\d+)M)?").matchEntire(it)?.let { match ->
                    (match.groupValues.getOrNull(1)?.toIntOrNull() ?: 0) * 60 + (match.groupValues.getOrNull(2)?.toIntOrNull() ?: 0)
                }
            }
        val actors = nextJsActors(html).ifEmpty { jsonLdActors(html) }
            .ifEmpty {
                document.select("a[href*='/oyuncu/']").mapNotNull { link ->
                    val img = link.selectFirst("img")
                    val photo = fixUrlNull(img?.attr("src")?.ifBlank { img?.attr("data-src") })
                    val name = link.text().trim()
                    if (name.isBlank()) null else Actor(name, photo)
                }.distinctBy { it.name }
            }
        val trailer = fixUrlNull(
            nextJsMovieValue(html, "trailer_url")
                ?: document.selectFirst("[property='og:video']")?.attr("content")
                ?: jsonLdString(html, "trailer")
        )
        val vidMixiUrl = nextJsVidMixiUrl(html)
        val isSeries = document.selectFirst("div.part_buton_sec")?.text()?.contains("Sezon", ignoreCase = true) == true

        if (isSeries) {
            val episodes = mutableListOf<Episode>()
            val iframeSkici = IframeKodlayici()
            val pdataMatches = Regex("""pdata\['(.*?)'\]\s*=\s*'(.*?)';""").findAll(html)
            for (match in pdataMatches) {
                val key = match.groupValues[1]
                val value = match.groupValues[2]
                val iframeData = runCatching { iframeSkici.iframeCoz(value) }.getOrNull() ?: continue
                val iframeLink = runCatching { app.get(iframeData, headers = browserHeaders, referer = "$mainUrl/").url.toString() }.getOrDefault(iframeData)
                val season = key.substringAfter("prt_").substringBefore("sezon").toIntOrNull() ?: 1
                val episode = (key.substringAfter("sezon").toIntOrNull() ?: 0) + 1
                episodes.add(newEpisode(iframeLink) {
                    name = "${season}. Sezon ${episode}. Bölüm"
                    this.season = season
                    this.episode = episode
                })
            }
            return newTvSeriesLoadResponse(title.substringBefore(" izle"), url, TvType.TvSeries, episodes) {
                posterUrl = poster
                posterHeaders = browserHeaders + ("Referer" to "$mainUrl/")
                plot = description
                this.year = year
                this.tags = tags
                score = Score.from10(rating)
                this.duration = duration
                addActors(actors)
                addTrailer(trailer)
            }
        }

        return newMovieLoadResponse(title.substringBefore(" izle"), url, TvType.Movie, vidMixiUrl ?: url) {
            posterUrl = poster
            posterHeaders = browserHeaders + ("Referer" to "$mainUrl/")
            plot = description
            this.year = year
            this.tags = tags
            score = Score.from10(rating)
            this.duration = duration
            addActors(actors)
            addTrailer(trailer)
        }
    }

    private fun hexBytes(value: String): ByteArray {
        return ByteArray(value.length / 2) { i -> value.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    private fun evpBytesToKey(password: ByteArray, salt: ByteArray, keySize: Int, ivSize: Int): Pair<ByteArray, ByteArray> {
        val output = ArrayList<Byte>()
        var previous = ByteArray(0)
        while (output.size < keySize + ivSize) {
            val md5 = MessageDigest.getInstance("MD5")
            md5.update(previous)
            md5.update(password)
            md5.update(salt)
            previous = md5.digest()
            previous.forEach { output.add(it) }
        }
        val all = output.toByteArray()
        return all.copyOfRange(0, keySize) to all.copyOfRange(keySize, keySize + ivSize)
    }

    private fun decryptVidMixi(cipherText: String, ivHex: String, saltHex: String, password: String): String? {
        return runCatching {
            val cipherBytes = Base64.decode(cipherText, Base64.DEFAULT)
            val (key, derivedIv) = evpBytesToKey(password.toByteArray(Charsets.UTF_8), hexBytes(saltHex), 32, 16)
            val iv = if (ivHex.length == 32) hexBytes(ivHex) else derivedIv
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            cipher.doFinal(cipherBytes).toString(Charsets.UTF_8)
        }.getOrNull()
    }

    private suspend fun resolveVidMixi(
        embedUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val normalizedEmbed = embedUrl.replace("\\/", "/").replace("\\u002F", "/")
        val embedHeaders = browserHeaders + mapOf(
            "Origin" to "https://vidmixi.com",
            "Referer" to "$mainUrl/"
        )
        val manifestHeaders = browserHeaders + mapOf(
            "Origin" to "https://vidmixi.com",
            "Referer" to normalizedEmbed
        )
        Log.d("HDS", "VidMixi resolve -> $normalizedEmbed")

        val embed = runCatching {
            app.get(normalizedEmbed, headers = embedHeaders, referer = "$mainUrl/")
        }.getOrNull() ?: run {
            Log.d("HDS", "VidMixi embed request failed")
            return false
        }

        val html = embed.text
            .replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\x2F", "/")

        val bePlayer = Regex(
            """bePlayer\(\s*['\"]([^'\"]+)['\"]\s*,\s*['\"](\{.*?\})['\"]\s*\)""",
            RegexOption.DOT_MATCHES_ALL
        ).find(html)

        val listUrl = bePlayer?.let { match ->
            val password = match.groupValues[1]
            val settings = match.groupValues[2]
            val ct = Regex("""\"ct\"\s*:\s*\"([^\"]+)\"""").find(settings)?.groupValues?.getOrNull(1)
            val iv = Regex("""\"iv\"\s*:\s*\"([^\"]+)\"""").find(settings)?.groupValues?.getOrNull(1)
            val salt = Regex("""\"s\"\s*:\s*\"([^\"]+)\"""").find(settings)?.groupValues?.getOrNull(1)
            if (ct != null && iv != null && salt != null) {
                decryptVidMixi(ct, iv, salt, password)?.let { decrypted ->
                    Regex("""\"video_location\"\s*:\s*\"([^\"]+)\"""").find(decrypted)?.groupValues?.getOrNull(1)?.replace("\\/", "/")
                }
            } else null
        }

        val directList = Regex("https?://vidmixi\\.com/list/[A-Za-z0-9+/=_-]+", RegexOption.IGNORE_CASE).find(html)?.value
        val finalListUrl = listUrl ?: directList ?: run {
            Log.d("HDS", "VidMixi video_location/list bulunamadı")
            return false
        }
        val manifestResponse = runCatching {
            app.get(finalListUrl, headers = manifestHeaders, referer = normalizedEmbed)
        }.getOrNull() ?: run {
            Log.d("HDS", "VidMixi manifest request failed")
            return false
        }
        if (!manifestResponse.text.trimStart().startsWith("#EXTM3U")) {
            Log.d("HDS", "VidMixi manifest M3U8 değil")
            return false
        }

        Regex("""https?://vidmixi\.com/[^\"'\s]+\.vtt""", RegexOption.IGNORE_CASE)
            .findAll(html).map { it.value }.distinct()
            .forEach { subtitleCallback(SubtitleFile("Türkçe", it)) }

        callback(newExtractorLink(this.name, "VidMixi", finalListUrl, ExtractorLinkType.M3U8) {
            referer = normalizedEmbed
            this.headers = manifestHeaders
            quality = Qualities.Unknown.value
        })
        Log.d("HDS", "VidMixi başarıyla çözüldü -> $finalListUrl")
        return true
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("HDS", "loadLinks -> $data")

        if (data.contains("vidmixi.com", ignoreCase = true) && resolveVidMixi(data, subtitleCallback, callback)) return true

        val document = runCatching {
            app.get(data, headers = browserHeaders, referer = "$mainUrl/").document
        }.getOrNull() ?: run {
            Log.d("HDS", "Film sayfası açılamadı -> $data")
            return false
        }
        val html = document.html()

        val embeddedVidMixi = nextJsVidMixiUrl(html)
        Log.d("HDS", "Film sayfasındaki VidMixi -> $embeddedVidMixi")
        if (!embeddedVidMixi.isNullOrBlank() && resolveVidMixi(embeddedVidMixi, subtitleCallback, callback)) return true

        val pdataRegex = Regex("""pdata\['(.*?)'\]\s*=\s*'(.*?)';""")
        val encoded = pdataRegex.findAll(html).map { it.groupValues[2] }.toList()
        val directIframes = document.select("iframe[src], iframe[data-src]")
            .mapNotNull { fixUrlNull(it.attr("src").ifBlank { it.attr("data-src") }) }
        val candidates = (encoded.mapNotNull { runCatching { IframeKodlayici().iframeCoz(it) }.getOrNull() } + directIframes)
            .distinct()

        for (candidate in candidates) {
            val providerUrl = runCatching {
                app.get(candidate, headers = browserHeaders, referer = "$mainUrl/").url.toString()
            }.getOrDefault(candidate)
            when {
                providerUrl.contains("vidmixi.com", ignoreCase = true) -> {
                    if (resolveVidMixi(providerUrl, subtitleCallback, callback)) return true
                }
                providerUrl.contains("vidmody", ignoreCase = true) -> {
                    val player = runCatching { app.get(providerUrl, headers = browserHeaders, referer = "$mainUrl/").document }.getOrNull()
                    val id = player?.selectFirst("script")?.text()?.let { script ->
                        Regex("var\\s+id\\s*=\\s*['\"]([^'\"]+)['\"]").find(script)?.groupValues?.getOrNull(1)
                    }
                    if (!id.isNullOrBlank()) {
                        M3u8Helper.generateM3u8("VidMody", "https://vidmody.com/vs/$id", "$mainUrl/", headers = browserHeaders).forEach(callback)
                        return true
                    }
                }
                providerUrl.contains("vidlop", ignoreCase = true) -> {
                    val id = providerUrl.substringAfterLast("/")
                    val vidUrl = runCatching {
                        app.post(
                            "https://vidlop.com/player/index.php?data=$id&do=getVideo",
                            headers = browserHeaders + ("X-Requested-With" to "XMLHttpRequest"),
                            referer = "$mainUrl/"
                        ).parsedSafe<VidLop>()?.securedLink
                    }.getOrNull()
                    if (!vidUrl.isNullOrBlank()) {
                        callback(newExtractorLink(this.name, "VidLop", vidUrl, ExtractorLinkType.M3U8) {
                            referer = providerUrl
                            headers = browserHeaders
                            quality = Qualities.Unknown.value
                        })
                        return true
                    }
                }
                else -> {
                    if (runCatching { loadExtractor(providerUrl, subtitleCallback, callback) }.getOrDefault(false)) return true
                }
            }
        }
        return false
    }

    data class VidLop(
        @JsonProperty("hls") val hls: Boolean? = null,
        @JsonProperty("securedLink") val securedLink: String? = null
    )
}
