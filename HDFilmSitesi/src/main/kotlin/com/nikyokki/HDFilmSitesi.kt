package com.nikyokki

import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.Actor
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
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import java.net.URLEncoder
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

    private fun normalizedHtml(html: String): String = html
        .replace("\\\"", "\"")
        .replace("\\/", "/")
        .replace("\\u002F", "/")
        .replace("\\x2F", "/")

    private fun posterMapFromHtml(html: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val normalized = normalizedHtml(html)
        val regex = Regex(
            """"slug"\s*:\s*"([^"]+)".{0,2500}?"(?:poster_url|posterUrl)"\s*:\s*"([^"]+)"""",
            RegexOption.DOT_MATCHES_ALL
        )
        regex.findAll(normalized).forEach { match ->
            val slug = match.groupValues[1].trim()
            val poster = fixUrlNull(match.groupValues[2].trim())
            if (slug.isNotBlank() && !poster.isNullOrBlank()) {
                result[slug] = poster
            }
        }
        return result
    }

    private fun Element.findPosterUrl(): String? {
        val image = selectFirst("img") ?: return null

        fun normalize(raw: String): String? {
            val value = raw.trim()
                .split(',')
                .firstOrNull()
                ?.trim()
                ?.substringBefore(" ")
                ?.trim()
                ?: return null

            if (value.isBlank() || value.startsWith("data:image", ignoreCase = true)) return null

            val lower = value.lowercase()
            if (lower.contains("placeholder") ||
                lower.contains("no-poster") ||
                lower.contains("no_poster") ||
                lower.contains("noimage") ||
                lower.contains("no-image") ||
                lower.contains("default-poster")
            ) return null

            return fixUrlNull(value)
        }

        sequenceOf(
            image.attr("data-src"),
            image.attr("data-lazy-src"),
            image.attr("data-original"),
            image.attr("data-srcset"),
            image.attr("src"),
            image.attr("srcset")
        ).forEach { raw ->
            normalize(raw)?.let { return it }
        }

        return null
    }

    private fun Element.findCardTitle(): String? =
        attr("aria-label").trim().takeIf { it.isNotEmpty() }
            ?: selectFirst("h3")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: selectFirst("img[alt]")?.attr("alt")?.substringBeforeLast(" izle")?.trim()

    private fun Element.findCardScore(): String? =
        select("span").map { it.text().trim().replace(',', '.') }
            .firstOrNull { Regex("""^([0-9]|10)(\.[0-9])?$""").matches(it) }

    private fun Element.cardSlug(): String? =
        attr("href").substringAfter("/film/", "").substringBefore("?").substringBefore("#")
            .trim('/').takeIf { it.isNotBlank() }

    private fun Element.toCardResult(posterMap: Map<String, String>): SearchResponse? {
        val href = fixUrlNull(attr("href")) ?: return null
        if (!href.contains("/film/")) return null
        val card = parent() ?: return null
        val title = findCardTitle() ?: return null
        val poster = cardSlug()?.let { posterMap[it] } ?: card.findPosterUrl()
        return newMovieSearchResponse(title, href, TvType.Movie) {
            posterUrl = poster
            posterHeaders = browserHeaders
            score = Score.from10(card.findCardScore())
        }
    }

    private fun Element.toFilmLinkResult(posterMap: Map<String, String>): SearchResponse? {
        val href = fixUrlNull(attr("href")) ?: return null
        if (!href.contains("/film/")) return null
        val title = findCardTitle() ?: return null
        val poster = cardSlug()?.let { posterMap[it] } ?: parent()?.findPosterUrl()
        return newMovieSearchResponse(title, href, TvType.Movie) {
            posterUrl = poster
            posterHeaders = browserHeaders
            score = Score.from10(findCardScore())
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data.trimEnd('/')}/page/$page"
        val document = app.get(url, headers = browserHeaders, referer = "$mainUrl/").document
        val posters = posterMapFromHtml(document.html())
        val cards = document.select("a.media-card__link")
            .mapNotNull { it.toCardResult(posters) }
            .distinctBy { it.url }
            .ifEmpty {
                document.select("a[href*='/film/']")
                    .mapNotNull { it.toFilmLinkResult(posters) }
                    .distinctBy { it.url }
            }
        return newHomePageResponse(request.name, cards)
    }

    private suspend fun searchPage(url: String): List<SearchResponse> {
        val document = runCatching {
            app.get(url, headers = browserHeaders, referer = "$mainUrl/").document
        }.getOrNull() ?: return emptyList()
        val posters = posterMapFromHtml(document.html())
        return document.select("a.media-card__link")
            .mapNotNull { it.toCardResult(posters) }
            .ifEmpty {
                document.select("a[href*='/film/']")
                    .mapNotNull { it.toFilmLinkResult(posters) }
            }
            .distinctBy { it.url }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        if (q.isBlank()) return emptyList()
        val candidates = listOf(
            "${mainUrl}/arama/$q",
            "${mainUrl}/arama?query=$q",
            "${mainUrl}/arama?q=$q",
            "${mainUrl}/arama?search=$q"
        )
        for (url in candidates) {
            val results = searchPage(url)
            if (results.isNotEmpty()) return results
        }
        return emptyList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    private fun nextJsMovieValue(html: String, key: String): String? {
        val normalized = normalizedHtml(html)
        val match = Regex(
            """"$key"\s*:\s*(?:"((?:\\.|[^"])*)"|([0-9]+(?:\.[0-9]+)?)|null)""",
            RegexOption.IGNORE_CASE
        ).find(normalized) ?: return null
        return match.groupValues[1].takeIf { it.isNotBlank() }
            ?.replace("\\/", "/")
            ?.replace("\\u002F", "/")
            ?: match.groupValues[2].takeIf { it.isNotBlank() }
    }

    private fun nextJsVidMixiUrl(html: String): String? =
        Regex("""https?://vidmixi\.com/embed/[A-Za-z0-9_-]+""", RegexOption.IGNORE_CASE)
            .find(normalizedHtml(html))?.value

    private fun jsonLdString(html: String, key: String): String? =
        Regex(""""$key"\s*:\s*"((?:\\.|[^"\\])*)"""")
            .find(normalizedHtml(html))?.groupValues?.getOrNull(1)
            ?.replace("\\/", "/")?.replace("\\u002F", "/")

    private fun jsonLdNumber(html: String, key: String): String? =
        Regex(""""$key"\s*:\s*([0-9]+(?:\.[0-9]+)?)""")
            .find(normalizedHtml(html))?.groupValues?.getOrNull(1)

    private fun nextJsActors(html: String): List<Actor> {
        val normalized = normalizedHtml(html)
        val actorBlock = Regex(
            """"actors"\s*:\s*\[(.*?)]""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        ).find(normalized)?.groupValues?.getOrNull(1) ?: return emptyList()

        return Regex("""\{([^{}]*)\}""").findAll(actorBlock).mapNotNull { match ->
            val obj = match.groupValues[1]
            val name = Regex(""""name"\s*:\s*"([^"]+)"""")
                .find(obj)?.groupValues?.getOrNull(1)?.trim() ?: return@mapNotNull null
            val photo = Regex(""""photo_url"\s*:\s*"(https?://[^"]+)"""")
                .find(obj)?.groupValues?.getOrNull(1)
                ?.replace(".avif", ".jpg")
            if (photo.isNullOrBlank()) Actor(name) else Actor(name, photo)
        }.distinctBy { it.name }.toList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = browserHeaders, referer = "$mainUrl/").document
        val html = document.html()
        val title = nextJsMovieValue(html, "title")
            ?: jsonLdString(html, "name")
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: return null
        val poster = fixUrlNull(
            nextJsMovieValue(html, "poster_url")
                ?: nextJsMovieValue(html, "posterUrl")
                ?: document.selectFirst("meta[property='og:image']")?.attr("content")
                ?: document.selectFirst("picture img[src]")?.attr("src")
        )
        val description = nextJsMovieValue(html, "description") ?: jsonLdString(html, "description")
        val year = nextJsMovieValue(html, "year")?.toIntOrNull()
        val tags = document.select("a[href^='/tur/']").map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
        val rating = nextJsMovieValue(html, "imdb_rating") ?: jsonLdNumber(html, "ratingValue")
        val duration = nextJsMovieValue(html, "duration")?.toIntOrNull()
        val actors = nextJsActors(html)
        val trailer = fixUrlNull(nextJsMovieValue(html, "trailer_url") ?: jsonLdString(html, "trailer"))
        val vidMixiUrl = nextJsVidMixiUrl(html)
        Log.d("HDS", "load -> $url | VidMixi=$vidMixiUrl | actors=${actors.size}")

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

    private fun hexBytes(value: String): ByteArray =
        ByteArray(value.length / 2) { i -> value.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    private fun evpBytesToKey(password: ByteArray, salt: ByteArray, keySize: Int, ivSize: Int): Pair<ByteArray, ByteArray> {
        val output = ArrayList<Byte>()
        var previous = ByteArray(0)
        while (output.size < keySize + ivSize) {
            val md5 = MessageDigest.getInstance("MD5")
            md5.update(previous)
            md5.update(password)
            md5.update(salt)
            previous = md5.digest()
            previous.forEach(output::add)
        }
        val all = output.toByteArray()
        return all.copyOfRange(0, keySize) to all.copyOfRange(keySize, keySize + ivSize)
    }

    private fun decryptVidMixi(cipherText: String, saltHex: String, password: String): String? = runCatching {
        val cipherBytes = Base64.decode(cipherText, Base64.DEFAULT)
        val (key, iv) = evpBytesToKey(password.toByteArray(Charsets.UTF_8), hexBytes(saltHex), 32, 16)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        cipher.doFinal(cipherBytes).toString(Charsets.UTF_8)
    }.getOrNull()

    private suspend fun resolveVidMixi(
        embedUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val embed = embedUrl.replace("\\/", "/").replace("\\u002F", "/")
        val embedHeaders = browserHeaders + mapOf(
            "Origin" to "https://vidmixi.com",
            "Referer" to "$mainUrl/"
        )
        val manifestHeaders = browserHeaders + mapOf(
            "Origin" to "https://vidmixi.com",
            "Referer" to embed
        )
        Log.d("HDS", "VidMixi resolve -> $embed")

        val response = runCatching {
            app.get(embed, headers = embedHeaders, referer = "$mainUrl/")
        }.getOrNull() ?: run {
            Log.d("HDS", "VidMixi embed request failed")
            return false
        }

        val html = normalizedHtml(response.text)
        val bePlayer = Regex(
            """bePlayer\(\s*['\"]([^'\"]+)['\"]\s*,\s*['\"](\{.*?\})['\"]\s*\)""",
            RegexOption.DOT_MATCHES_ALL
        ).find(html) ?: run {
            Log.d("HDS", "bePlayer bulunamadı")
            return false
        }

        val password = bePlayer.groupValues[1]
        val settings = bePlayer.groupValues[2]
        val ct = Regex(""""ct"\s*:\s*"([^"]+)"""").find(settings)?.groupValues?.getOrNull(1)
        val salt = Regex(""""s"\s*:\s*"([^"]+)"""").find(settings)?.groupValues?.getOrNull(1)
        val listUrl = if (ct != null && salt != null) {
            decryptVidMixi(ct, salt, password)?.let { decrypted ->
                Regex(""""video_location"\s*:\s*"([^"]+)"""")
                    .find(decrypted)?.groupValues?.getOrNull(1)
                    ?.replace("\\/", "/")
            }
        } else null

        val directList = Regex(
            """https?://vidmixi\.com/list/[A-Za-z0-9+/=_-]+""",
            RegexOption.IGNORE_CASE
        ).find(html)?.value
        val finalListUrl = listUrl ?: directList ?: run {
            Log.d("HDS", "VidMixi video_location/list bulunamadı")
            return false
        }

        val manifestResponse = runCatching {
            app.get(finalListUrl, headers = manifestHeaders, referer = embed)
        }.getOrNull() ?: run {
            Log.d("HDS", "VidMixi manifest request failed")
            return false
        }
        if (!manifestResponse.text.trimStart().startsWith("#EXTM3U")) {
            Log.d("HDS", "VidMixi manifest M3U8 değil")
            return false
        }

        Regex("""https?://vidmixi\.com/[^"'\s]+\.vtt""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .map { it.value }
            .distinct()
            .forEach { subtitleCallback(SubtitleFile("Türkçe", it)) }

        callback(newExtractorLink(this.name, "VidMixi", finalListUrl, ExtractorLinkType.M3U8) {
            referer = embed
            headers = manifestHeaders
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

        if (data.contains("vidmixi.com", ignoreCase = true)) {
            return resolveVidMixi(data, subtitleCallback, callback)
        }

        val document = runCatching {
            app.get(data, headers = browserHeaders, referer = "$mainUrl/").document
        }.getOrNull() ?: run {
            Log.d("HDS", "Film sayfası açılamadı -> $data")
            return false
        }
        val embeddedVidMixi = nextJsVidMixiUrl(document.html())
        Log.d("HDS", "Film sayfasındaki VidMixi -> $embeddedVidMixi")
        if (!embeddedVidMixi.isNullOrBlank() && resolveVidMixi(embeddedVidMixi, subtitleCallback, callback)) return true

        val directIframes = document.select("iframe[src], iframe[data-src]")
            .mapNotNull { fixUrlNull(it.attr("src").ifBlank { it.attr("data-src") }) }
            .distinct()

        for (candidate in directIframes) {
            val providerUrl = runCatching {
                app.get(candidate, headers = browserHeaders, referer = "$mainUrl/").url.toString()
            }.getOrDefault(candidate)

            if (providerUrl.contains("vidmixi.com", ignoreCase = true)) {
                if (resolveVidMixi(providerUrl, subtitleCallback, callback)) return true
            }
            if (runCatching { loadExtractor(providerUrl, subtitleCallback, callback) }.getOrDefault(false)) return true
        }
        return false
    }

    data class VidLop(
        @JsonProperty("hls") val hls: Boolean? = null,
        @JsonProperty("securedLink") val securedLink: String? = null
    )
}
