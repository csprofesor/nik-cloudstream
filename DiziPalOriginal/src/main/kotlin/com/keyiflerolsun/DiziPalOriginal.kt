package com.keyiflerolsun

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class DiziPalOriginal : MainAPI() {
    override var mainUrl = "https://dizipal1581.com"
    override var name = "DiziPalOriginal"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)
    override var sequentialMainPage = true
    override var sequentialMainPageDelay       = 150L
    override var sequentialMainPageScrollDelay = 150L

    // ! CloudFlare v2
    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor      by lazy { CloudflareInterceptor(cloudflareKiller) }

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller): Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request  = chain.request()
            val response = chain.proceed(request)
            val doc      = Jsoup.parse(response.peekBody(1024 * 1024).string())

            if (doc.html().contains("Just a moment")) {
                return cloudflareKiller.intercept(chain)
            }

            return response
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/bolumler"                 to "Son Bölümler",
        "$mainUrl/yabanci-dizi-izle"        to "Yeni Diziler",
        "$mainUrl/hd-film-izle"             to "Yeni Filmler",
        "$mainUrl/kanal/netflix"            to "Netflix",
        "$mainUrl/kanal/exxen"              to "Exxen",
        "$mainUrl/kanal/max"                to "Max",
        "$mainUrl/kanal/disney"             to "Disney+",
        "$mainUrl/kanal/amazon"             to "Amazon Prime",
        "$mainUrl/kanal/tod"                to "TOD (beIN)",
        "$mainUrl/kanal/tabii"              to "Tabii",
        "$mainUrl/kanal/hulu"               to "Hulu"
    )

    private fun Element.href(): String? = attr("href").trim().takeIf { it.isNotEmpty() }
        ?: selectFirst("a[href]")?.attr("href")?.trim()?.takeIf { it.isNotEmpty() }

    private fun Element.extractPoster(): String? {
        val img = selectFirst("img") ?: selectFirst("[style*='background-image']")

        if (img != null && img.tagName() == "img") {
            for (attr in listOf("data-src", "data-lazy-src", "data-original", "data-poster", "data-image")) {
                val valStr = img.attr(attr).trim()
                if (valStr.isNotEmpty() && !valStr.startsWith("data:image")) {
                    return fixUrlNull(valStr)
                }
            }

            val srcset = img.attr("srcset").trim()
            if (srcset.isNotEmpty()) {
                val firstUrl = srcset.split(",").firstOrNull()?.trim()?.split(" ")?.firstOrNull()
                if (!firstUrl.isNullOrEmpty() && !firstUrl.startsWith("data:image")) {
                    return fixUrlNull(firstUrl)
                }
            }

            val src = img.attr("src").trim()
            if (src.isNotEmpty() && !src.startsWith("data:image") && !src.contains("placeholder", true)) {
                return fixUrlNull(src)
            }
        }

        for (el in listOf(this, img).filterNotNull()) {
            val style = el.attr("style")
            val match = Regex("url\\(['\"]?([^'\")]+)['\"]?\\)", RegexOption.IGNORE_CASE).find(style)
            if (match != null) {
                val bgUrl = match.groupValues[1].trim()
                if (bgUrl.isNotEmpty() && !bgUrl.startsWith("data:image")) {
                    return fixUrlNull(bgUrl)
                }
            }
        }

        return null
    }

    private fun Element.parseSonBolumler(): SearchResponse? {
        val name = selectFirst("img")?.attr("alt") ?: selectFirst("h2,h3,h4,span")?.text()?.trim() ?: return null
        val episode = selectFirst("div.episode, .episode")?.text()?.trim()
            ?.replace(". Sezon ", "x")?.replace(". Bölüm", "") ?: ""
        val title = if (episode.isNotEmpty()) "$name $episode" else name

        val href = fixUrlNull(selectFirst("a[href]")?.attr("href")) ?: return null
        val posterUrl = extractPoster()
        val seriesHref = href.substringBefore("/sezon").substringBefore("/bolum/")

        return newTvSeriesSearchResponse(title, seriesHref, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    private fun Element.parseYeniFilmler(): SearchResponse? {
        val title = selectFirst("img")?.attr("alt") ?: selectFirst("h2,h3,h4,span")?.text()?.trim() ?: return null
        val href = fixUrlNull(selectFirst("a[href]")?.attr("href")) ?: return null
        val posterUrl = extractPoster()

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val aTag = selectFirst("a[href]") ?: if (tagName() == "a") this else return null
        val href = fixUrlNull(aTag.href()) ?: return null
        if (href.isBlank()) return null

        val imgEl = selectFirst("img") ?: aTag.selectFirst("img")
        val title = (imgEl?.attr("alt")?.takeIf { it.isNotBlank() }
            ?: attr("title").takeIf { it.isNotBlank() }
            ?: aTag.attr("title").takeIf { it.isNotBlank() }
            ?: selectFirst("h2,h3,h4,h5,.title,.card-title,span")?.text()?.trim()
            ?: aTag.text().trim()).takeIf { it.isNotBlank() } ?: return null

        val posterUrl = extractPoster()

        return when {
            href.contains("/movies/", true) || href.contains("/film/", true) || href.contains("/movie/", true) -> {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = posterUrl
                }
            }
            href.contains("/bolum/", true) -> {
                val seriesHref = href.substringBefore("/sezon").substringBefore("/bolum/")
                newTvSeriesSearchResponse(title, seriesHref, TvType.TvSeries) {
                    this.posterUrl = posterUrl
                }
            }
            else -> {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = posterUrl
                }
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1 && !request.data.contains("/kanal/")) {
            if (request.data.contains("?")) "${request.data}&sayfa=$page" else "${request.data}?sayfa=$page"
        } else {
            request.data
        }

        val document = app.get(
            url, timeout = 10000, interceptor = interceptor, headers = getHeaders(mainUrl)
        ).document

        val items = when {
            request.data.contains("/bolumler") -> {
                document.select("div.new-added-list div.bg-\\[\\#22232a\\] , div.bg-\\[\\#22232a\\] , .card, article").mapNotNull { it.parseSonBolumler() }
            }
            request.data.contains("/hd-film-izle") || request.data.contains("/filmler") -> {
                document.select("div.new-added-list div.bg-\\[\\#22232a\\] , div.bg-\\[\\#22232a\\] , .card, article").mapNotNull { it.parseYeniFilmler() }
            }
            else -> {
                document.select("div.bg-\\[\\#22232a\\] , a[href*='/series/'], a[href*='/dizi/'], a[href*='/movies/'], a[href*='/film/']").mapNotNull { it.toSearchResponse() }
            }
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, items, items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return app.get(
            "$mainUrl/diziler?kelime=$encoded&durum=&tur=&type=&siralama=",
            timeout = 10000,
            interceptor = interceptor,
            headers = getHeaders(mainUrl)
        )
            .document
            .select("div.bg-\\[\\#22232a\\] , a[href*='/series/'], a[href*='/dizi/'], a[href*='/movies/'], a[href*='/film/']")
            .mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(
            url, timeout = 10000, interceptor = interceptor, headers = getHeaders(mainUrl)
        ).document

        val title = document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")?.substringBefore(" izle")?.trim()
            ?: return null
        val poster = fixUrlNull(document.selectFirst("meta[property='og:image']")?.attr("content"))
            ?: fixUrlNull(document.selectFirst("div.page-top img[alt]")?.attr("src"))
            ?: document.selectFirst("div.page-top")?.extractPoster()

        val year = document.selectXpath("//div[text()='Yıl']//following-sibling::div").text().trim().toIntOrNull()
            ?: document.selectFirst(".year, [itemprop='releaseDate']")?.text()?.trim()?.toIntOrNull()
        val description = document.selectFirst("div.summary p, [itemprop='description'], .description")?.text()?.trim()
        val tags = document.selectXpath("//div[text()='Kategoriler']//following-sibling::div").text().trim().split(" ").map { it.trim() }.filter { it.isNotEmpty() }
            .ifEmpty { document.select("a[href*='/tur/']").map { it.text() } }
        val duration = Regex("(\\d+)").find(document.selectXpath("//div[text()='Süre']//following-sibling::div").text())?.value?.toIntOrNull()
        val rating = document.selectFirst("span.imdb, .imdb-point, div:contains(IMDb), [itemprop='ratingValue']")?.text()?.trim()
        val actors = document.select("a[href*='/oyuncu/'], .cast a, .actors a").map { Actor(it.text()) }
        val trailer = document.selectFirst("iframe[src*='youtube'], video, .trailer iframe")?.attr("src")

        if (url.contains("/movies/", true) || url.contains("/film/", true)) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year      = year
                this.plot      = description
                this.tags      = tags
                this.duration  = duration
                if (rating != null) {
                    this.score = Score.from10(rating)
                }
                addActors(actors)
                addTrailer(trailer)
            }
        }

        val episodes = document.select("a[href*='/bolum/'], div.relative.w-full.flex.items-start.gap-4").mapNotNull { element ->
            val linkElement = if (element.tagName() == "a") element else element.selectFirst("a[href*='/bolum/']") ?: return@mapNotNull null
            val href = fixUrlNull(linkElement.attr("href")) ?: return@mapNotNull null
            val match = Regex("-(\\d+)x(\\d+)$").find(href) ?: return@mapNotNull null
            val epName = linkElement.selectFirst("h2")?.text()?.trim() ?: linkElement.text().trim().ifEmpty { "${match.groupValues[1]}. Sezon ${match.groupValues[2]}. Bölüm" }
            newEpisode(href) {
                name = epName
                season = match.groupValues[1].toIntOrNull()
                episode = match.groupValues[2].toIntOrNull()
            }
        }.distinctBy { it.data }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.year      = year
            this.plot      = description
            this.tags      = tags
            this.duration  = duration
            if (rating != null) {
                this.score = Score.from10(rating)
            }
            addActors(actors)
            addTrailer(trailer)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("DiziPalOriginal", "--> loadLinks ÇAĞRILDI. Gelen URL: $data")
        val doc = app.get(
            data, timeout = 10000, interceptor = interceptor, headers = getHeaders(mainUrl)
        ).document

        val encryptedText = doc.selectFirst("div[data-rm-k=true]")?.text() ?: ""
        Log.d("DiziPalOriginal", "--> Şifreli metin uzunluğu: ${encryptedText.length}")

        var iframeUrl = if (encryptedText.isNotEmpty()) {
            Log.d("DiziPalOriginal", "--> Şifreli veri bulundu, decrypt işlemine geçiliyor...")
            decryptDizipalData(encryptedText)
        } else {
            Log.w("DiziPalOriginal", "--> DİKKAT: Şifreli veri DOM'da YOK! Fallback iframe aranıyor...")
            doc.selectFirst("iframe")?.attr("src") ?: ""
        }

        Log.d("DiziPalOriginal", "--> Elde edilen Ham Iframe URL: $iframeUrl")

        if (iframeUrl.isNotEmpty()) {
            if (iframeUrl.startsWith("//")) {
                iframeUrl = "https:$iframeUrl"
            }
            Log.d("DiziPalOriginal", "--> Extractor'a gönderilen Final URL: $iframeUrl")

            DizipalOriginalPlayer().getUrl(
                url = iframeUrl,
                referer = data,
                subtitleCallback = subtitleCallback,
                callback = callback
            )
        } else {
            Log.e("DiziPalOriginal", "--> HATA: iframeUrl tamamen BOŞ. Video linki bulunamadı!")
        }
        return true
    }

    private fun String.decodeHex(): ByteArray {
        check(length % 2 == 0) { "Hex string çift uzunlukta olmalıdır" }
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun decryptDizipalData(rawJsonText: String): String {
        return try {
            val passphrase = "3hPn4uCjTVtfYWcjIcoJQ4cL1WWk1qxXI39egLYOmNv6IblA7eKJz68uU3eLzux1biZLCms0quEjTYniGv5z1JcKbNIsDQFSeIZOBZJz4is6pD7UyWDggWWzTLBQbHcQFpBQdClnuQaMNUHtLHTpzCvZy33p6I7wFBvL4fnXBYH84aUIyWGTRvM2G5cfoNf4705tO2kv"

            val ctMatch = """"ciphertext"\s*:\s*"([^"]+)"""".toRegex().find(rawJsonText)?.groupValues?.get(1)
                ?: return "".also { Log.e("DiziPalOriginal", "--> HATA: Regex 'ciphertext' değerini bulamadı!") }

            val ivMatch = """"iv"\s*:\s*"([^"]+)"""".toRegex().find(rawJsonText)?.groupValues?.get(1)
                ?: return "".also { Log.e("DiziPalOriginal", "--> HATA: Regex 'iv' değerini bulamadı!") }

            val saltMatch = """"salt"\s*:\s*"([^"]+)"""".toRegex().find(rawJsonText)?.groupValues?.get(1)
                ?: return "".also { Log.e("DiziPalOriginal", "--> HATA: Regex 'salt' değerini bulamadı!") }

            Log.d("DiziPalOriginal", "--> Regex başarılı. Key türetiliyor...")

            val salt = saltMatch.decodeHex()
            val iv = ivMatch.decodeHex()
            val ciphertext = Base64.decode(ctMatch, Base64.DEFAULT)

            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
            val spec = PBEKeySpec(passphrase.toCharArray(), salt, 999, 256)
            val secretKey = factory.generateSecret(spec)
            val secret = SecretKeySpec(secretKey.encoded, "AES")

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, secret, IvParameterSpec(iv))

            val decryptedBytes = cipher.doFinal(ciphertext)
            var finalUrl = String(decryptedBytes, Charsets.UTF_8).replace("\\/", "/")

            Log.d("DiziPalOriginal", "--> AES Çözümleme Başarılı. İlk Çıktı: $finalUrl")

            if (finalUrl.startsWith("://")) {
                finalUrl = "https$finalUrl"
            } else if (finalUrl.startsWith("//")) {
                finalUrl = "https:$finalUrl"
            } else if (!finalUrl.startsWith("http")) {
                finalUrl = "https://$finalUrl"
            }

            finalUrl
        } catch (e: Exception) {
            Log.e("DiziPalOriginal", "--> HATA: Decryption sırasında Exception fırlatıldı! Mesaj: ${e.message}")
            e.printStackTrace()
            ""
        }
    }

    private fun getHeaders(baseUrl: String): Map<String, String> {
        return mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
            "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
            "Referer" to baseUrl
        )
    }
}
