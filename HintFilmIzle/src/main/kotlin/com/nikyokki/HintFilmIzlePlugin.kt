package com.nikyokki

// Kinescope WebView resolver: only the real HLS manifest terminates the resolver.
// Analytics/ad requests are blocked in-page without using interceptUrl.

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.network.CloudflareKiller
import okhttp3.Interceptor
import okhttp3.Response
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
import com.lagradost.cloudstream3.mainPageOf

import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.ArrayDeque

class HintFilmIzle : MainAPI() {
    override var mainUrl = "https://www.hintfilmizle.com"
    override var name = "HintFilmİzle"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor      by lazy { CloudflareInterceptor(cloudflareKiller) }

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request    = chain.request()
            val response   = chain.proceed(request)
            val bodySample = response.peekBody(1024 * 1024).string()
            if (
                bodySample.contains("Güvenlik taramasından geçiriliyorsunuz")
                || bodySample.contains("cf-browser-verification")
                || bodySample.contains("Checking your browser")
                || bodySample.contains("just a moment", ignoreCase = true)
                || response.code in listOf(403, 503, 429)
            ) {
                response.close()
                return cloudflareKiller.intercept(chain)
            }
            return response
        }
    }

    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    private fun headers() = mapOf("User-Agent" to ua, "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7")

    override val mainPage = mainPageOf(
        "$mainUrl/tur/aile-filmleri" to "Aile", "$mainUrl/tur/aksiyon-filmleri" to "Aksiyon",
        "$mainUrl/tur/animasyon-filmleri" to "Animasyon", "$mainUrl/tur/bilim-kurgu-filmleri" to "Bilim Kurgu",
        "$mainUrl/tur/dram-filmleri" to "Dram", "$mainUrl/tur/fantastik-filmleri" to "Fantastik",
        "$mainUrl/tur/komedi-filmleri" to "Komedi", "$mainUrl/tur/korku-filmleri" to "Korku",
        "$mainUrl/tur/macera-filmleri" to "Macera", "$mainUrl/tur/romantik-filmleri" to "Romantik",
        "$mainUrl/tur/savas-filmleri" to "Savaş", "$mainUrl/tur/suc-filmleri" to "Suç",
        "$mainUrl/tur/tarih-filmleri" to "Tarih", "$mainUrl/tur/gerilim-filmleri" to "Gerilim",
        "$mainUrl/netflix-izle" to "Netflix"
    )

    private fun fix(value: String?, base: String = mainUrl): String? {
        val raw = value?.replace("\\/", "/")?.replace("\\u0026", "&")?.replace("&amp;", "&")?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            when {
                raw.startsWith("//") -> "https:$raw"
                raw.startsWith("http://", true) || raw.startsWith("https://", true) -> raw
                raw.startsWith("/") -> mainUrl + raw
                else -> URI(base).resolve(raw).toString()
            }
        }.getOrNull()?.takeIf { it.startsWith("http", true) }
    }

    private fun Element.poster(): String? {
        val attrs = listOf("data-src", "data-lazy-src", "data-original", "data-image", "data-poster", "data-thumb", "src")
        select("img, picture source, [data-background], .poster, .thumb").forEach { img ->
            val bg = img.attr("data-background").ifBlank { img.attr("style") }
            if (bg.contains("url")) {
                Regex("url\\(['\"]?([^'\")]+)['\"]?\\)").find(bg)?.groupValues?.get(1)?.let { fix(it) }?.let { return it }
            }
            attrs.firstNotNullOfOrNull {
                fix(img.attr(it))?.takeIf { u -> !u.startsWith("data:") && !u.contains("placeholder", true) }
            }?.let { return it }
        }
        return null
    }

    private fun cleanTitle(value: String?): String? = value?.trim()
        ?.replace(Regex("\\s+"), " ")
        ?.replace(Regex("\\s+(Türkçe\\s+(Altyazı|Dublaj)|izle)\\s*$", RegexOption.IGNORE_CASE), "")
        ?.trim()
        ?.takeIf { it.isNotBlank() && !it.equals("Giriş yap", true) && !it.equals("Oturum Aç", true) }

    private fun titleOf(card: Element): String? = sequenceOf(
        card.selectFirst("h1")?.text(), card.selectFirst(".film-title")?.text(), card.selectFirst(".movie-title")?.text(),
        card.selectFirst(".entry-title")?.text(), card.selectFirst(".single-title")?.text(), card.selectFirst(".post-title")?.text(),
        card.selectFirst(".card-title")?.text(), card.selectFirst("h2")?.text(), card.selectFirst("h3")?.text(),
        card.selectFirst(".title")?.text(), card.selectFirst(".name")?.text(), card.selectFirst("img")?.attr("alt"), card.attr("title"),
        card.text()
    ).mapNotNull(::cleanTitle).firstOrNull()

    private fun detailTitle(doc: Document, url: String): String? {
        val target = url.substringBefore("?").trimEnd('/')
        val exactLink = doc.select("a[href]").firstOrNull { a ->
            val href = fix(a.attr("href"), url)?.substringBefore("?")?.trimEnd('/')
            href.equals(target, true) && cleanTitle(a.text()) != null
        }
        return sequenceOf(exactLink?.text(), doc.selectFirst("main h1")?.text(), doc.selectFirst("article h1")?.text(), doc.selectFirst("h1")?.text(), doc.selectFirst(".film-title")?.text(), doc.selectFirst(".movie-title")?.text(), doc.selectFirst(".entry-title")?.text()).mapNotNull(::cleanTitle).firstOrNull()
            ?: url.substringBefore("?").substringAfterLast('/').replace(Regex("[-_]+"), " ").replaceFirstChar { it.uppercase() }
    }

    private fun rating(card: Element): String? {
        val scoreText = card.selectFirst(".imdb, .rating, span.score, small, .puan")?.text() ?: card.text()
        return Regex("(?<!\\d)(?:10(?:[.,]0+)?|[1-9](?:[.,]\\d{1,3})?)(?!\\d)").findAll(scoreText)
            .mapNotNull { it.value.replace(',', '.').toFloatOrNull() }
            .firstOrNull { it in 0f..10f }?.toString()
    }

    private fun Element.toResult(card: Element = this): SearchResponse? {
        val href = fix(attr("href")) ?: return null
        val path = href.removePrefix(mainUrl).substringBefore("?").trimEnd('/')
        if (!href.startsWith(mainUrl, true) || (!path.startsWith("/film/") && !path.startsWith("/dizi/"))) return null
        val title = titleOf(card) ?: path.substringAfterLast('/').replace(Regex("[-_]+"), " ")
        return if (path.startsWith("/dizi/")) newTvSeriesSearchResponse(title, href, TvType.TvSeries) { posterUrl = card.poster(); score = Score.from10(rating(card)) }
        else newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = card.poster(); score = Score.from10(rating(card)) }
    }

    private fun cardFor(anchor: Element): Element = anchor.parents().firstOrNull { p -> p.select("img").isNotEmpty() && p.select("a[href*='/film/'],a[href*='/dizi/']").size <= 4 } ?: anchor
    private fun categorySlug(data: String): String? = data.substringBefore("?").trimEnd('/').substringAfter("/tur/", "").takeIf { it.isNotBlank() }
    private fun categoryMatches(card: Element, slug: String): Boolean = card.select("a[href*='/tur/']").any { a -> val href = fix(a.attr("href")) ?: return@any false; href.substringBefore("?").trimEnd('/').equals("$mainUrl/tur/$slug", ignoreCase = true) }
    private fun results(doc: Document, slug: String? = null): List<SearchResponse> = doc.select("a[href*='/film/'],a[href*='/dizi/'], .film-box a, .movie-item a, .item a, .film-item a").mapNotNull { a -> val card = cardFor(a); if (slug != null && !categoryMatches(card, slug)) null else a.toResult(card) }.distinctBy { it.url }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = request.data.substringBefore("?").trimEnd('/'); val q = request.data.substringAfter("?", "").takeIf { it.isNotBlank() }
        val url = if (page <= 1) request.data else base + "/page/$page/" + if (q != null) "?$q" else ""
        val slug = categorySlug(request.data)
        var response = runCatching { app.get(url, referer = "$mainUrl/", headers = headers(), interceptor = interceptor) }.getOrNull() ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)
        var doc = response.document; var r = results(doc, slug)
        if (slug != null && r.isEmpty()) {
            val fallbackUrl = if (page <= 1) "$mainUrl/film?order=DESC&orderby=date" else "$mainUrl/film/page/$page/?order=DESC&orderby=date"
            response = runCatching { app.get(fallbackUrl, referer = "$mainUrl/", headers = headers(), interceptor = interceptor) }.getOrNull() ?: response
            doc = response.document; r = results(doc, slug)
        }
        return newHomePageResponse(request.name, r, hasNext = r.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        listOf("$mainUrl/film?search=$q", "$mainUrl/film?s=$q", "$mainUrl/?s=$q", "$mainUrl/?search=$q", "$mainUrl/arama?q=$q").forEach { url -> val r = runCatching { results(app.get(url, referer = "$mainUrl/", headers = headers(), interceptor = interceptor).document) }.getOrDefault(emptyList()); if (r.isNotEmpty()) return r }
        return emptyList()
    }
    override suspend fun quickSearch(query: String) = search(query)

    private fun body(doc: Document) = doc.body()?.text()?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
    private fun label(text: String, name: String, next: String): String? = Regex("${Regex.escape(name)}\\s*[:\\-]?\\s*(.*?)\\s*(?=${Regex.escape(next)}|$)", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(text)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
    
    private fun genres(doc: Document, text: String): List<String> {
        val dom = doc.select("a[href*='/tur/'], .genres a, .genre a, .categories a, .tags a").map { it.text().trim() }.filter { it.isNotBlank() && !it.equals("Film", true) && !it.equals("Dizi", true) }.distinct()
        if (dom.isNotEmpty()) return dom
        return label(text, "Türü", "Bu Film özeti")?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
    }

    private fun actors(doc: Document): List<Actor> {
        val actorElements = doc.select("a[href*='/oyuncular/'], a[href*='/oyuncu/'], a[href*='/actor/'], a[href*='/cast/'], .cast-item, .actor-item")
        val result = actorElements.mapNotNull { el ->
            val name = cleanTitle(el.selectFirst("h4, span, .name")?.text() ?: el.text()) ?: return@mapNotNull null
            if (name.length > 100 || name.length < 2) return@mapNotNull null
            val img = fix(el.selectFirst("img")?.poster())
            Actor(name, img)
        }.distinctBy { it.name }
        if (result.isNotEmpty()) return result

        val heading = doc.select("h1,h2,h3,h4,h5,h6").firstOrNull { it.text().contains("Öne Çıkan Oyuncular", true) }
        val container = heading?.parents()?.firstOrNull { p -> val count = p.select("a").size; count in 1..20 && p.text().contains("Yönetmen", true) }
        return container?.select("a")?.mapNotNull { cleanTitle(it.text())?.takeIf { n -> n.length < 100 }?.let { n -> Actor(n) } }.orEmpty()
    }
    private fun plot(doc: Document, text: String): String? {
        doc.selectFirst("meta[property='og:description'], meta[name='description']")?.attr("content")?.takeIf { it.isNotBlank() }?.let { return it.trim() }
        val section = Regex("GENEL BAKIŞ\\s+(.*?)(?=BU FİLM ÖZETİ|HATA BİLDİR|FRAGMAN|ÖNE ÇIKAN OYUNCULAR|YÖNETMEN|ÜLKE\\s)", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(text)?.groupValues?.get(1) ?: return null
        val cleaned = section.replace(Regex("^Türü\\s*:\\s*.*?(?=ÇEVİRİ\\s*:)", RegexOption.IGNORE_CASE), "").replace(Regex("^ÇEVİRİ\\s*:\\s*.*?(?=[A-ZÇĞİÖŞÜ][a-zçğıöşü])", RegexOption.IGNORE_CASE), "").replace(Regex("\\s+"), " ").trim()
        return cleaned.takeIf { it.length >= 10 }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = runCatching { app.get(url, referer = "$mainUrl/", headers = headers(), interceptor = interceptor).document }.getOrNull() ?: return null
        val text = body(doc); val title = detailTitle(doc, url) ?: return null
        val poster = fix(doc.selectFirst("meta[property='og:image'],meta[name='twitter:image']")?.attr("content")) ?: doc.selectFirst("article,.movie-detail,.film-detail")?.poster()
        val year = Regex("YAPIM YILI\\s+(\\d{4})", RegexOption.IGNORE_CASE).find(text)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: Regex("\\b(19|20)\\d{2}\\b").find(title)?.value?.toIntOrNull()
        val imdb = doc.selectFirst(".imdb-score, .puan, [itemprop='ratingValue']")?.text() ?: Regex("IMDB\\s*(?:PUANI|Puanı)?\\s*([0-9]+(?:[.,][0-9]+)?)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)
        val duration = Regex("SÜRE\\s+(\\d+)\\s*dk", RegexOption.IGNORE_CASE).find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val tag = genres(doc, text); val cast = actors(doc)
        val rec = results(doc).ifEmpty { listOf(newMovieSearchResponse("Hint Filmleri", "$mainUrl/film", TvType.Movie) {}) }
        val p = plot(doc, text)
        if (url.contains("/dizi/", true) || doc.selectFirst(".episodes,.episode-list,.seasons") != null) {
            val eps = doc.select("a[href*='/dizi/'],a[href*='sezon'],a[href*='bolum'],.episode a,.episodes a,.episode-list a").mapIndexedNotNull { index, a ->
                val u = fix(a.attr("href")) ?: return@mapIndexedNotNull null
                val t = "${a.text()} ${a.attr("title")}"
                val ss = Regex("(?:s|sezon[\\s._-]*)(\\d+)", RegexOption.IGNORE_CASE).find(t)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: Regex("(\\d+)\\s*[.,]?\\s*Sezon", RegexOption.IGNORE_CASE).find(t)?.groupValues?.getOrNull(1)?.toIntOrNull()
                val ee = Regex("(?:e|bölüm[\\s._-]*)(\\d+)", RegexOption.IGNORE_CASE).find(t)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: Regex("(\\d+)\\s*[.,]?\\s*Bölüm", RegexOption.IGNORE_CASE).find(t)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: Regex("\\b(\\d+)\\b").find(a.text())?.value?.toIntOrNull()
                if (u == url) return@mapIndexedNotNull null
                newEpisode(u) {
                    name = a.text().trim().ifBlank { "Bölüm ${index + 1}" }
                    season = ss ?: 1
                    episode = ee ?: (index + 1)
                }
            }.distinctBy { it.data }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, eps) { posterUrl=poster; this.year=year; plot=p; tags=tag; score=Score.from10(imdb); this.duration=duration; addActors(cast); recommendations=rec }
        }
        return newMovieLoadResponse(title, url, TvType.Movie, url) { posterUrl=poster; this.year=year; plot=p; tags=tag; score=Score.from10(imdb); this.duration=duration; addActors(cast); recommendations=rec }
    }

    private fun player(value: String?, base: String): String? {
        val u = fix(value, base) ?: return null
        if (u.contains("youtube", true) || u.contains("schema.org", true) || u.contains("imdb.com", true) || u.contains("google.com/search", true) || u.contains("yandex", true) || u.contains("dmca.com", true) || u.contains("wp-content", true) || u.contains("wp-includes", true) || u.contains("javascript:", true)) return null
        return u
    }

    private val kinescopeManifestRegex = Regex(
        "https?://[^\"'\\s<>]*(?:kinescopecdn\\.net|kinescope\\.io)/[^\"'\\s<>]*\\.m3u8(?:\\?[^\"'\\s<>]*)?",
        RegexOption.IGNORE_CASE
    )
    private val kinescopeApiRegex = Regex("https?://[^\"'\\s<>]*(?:kinescopecdn\\.net|kinescope\\.io)/[^\"'\\s<>]*/api/[^\"'\\s<>]+", RegexOption.IGNORE_CASE)

    private fun normalizeKinescopeValue(value: String?): String? = value
        ?.replace("\\/", "/")
        ?.replace("\\u0026", "&")
        ?.replace("\\u003d", "=")
        ?.replace("&amp;", "&")
        ?.trim()
        ?.trim('"')
        ?.takeIf { it.isNotBlank() }

    private fun firstManifest(value: String?): String? = value
        ?.let(::normalizeKinescopeValue)
        ?.let { normalized ->
            kinescopeManifestRegex.find(normalized)?.value
                ?: normalized.takeIf { '%' in it }?.let { encoded ->
                    runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrNull()
                }?.let { kinescopeManifestRegex.find(it)?.value }
        }

    private fun decodeBase64Candidates(value: String): List<String> {
        fun decodeBytes(candidate: String): ByteArray? {
            val normalized = candidate.replace('-', '+').replace('_', '/')
            val padded = normalized.padEnd(normalized.length + (4 - normalized.length % 4) % 4, '=')
            return runCatching { Base64.decode(padded, Base64.NO_WRAP) }.getOrNull()
        }

        val key = "RySdvcyu5iTUxn97vn4HwoniwgxaCynA".toByteArray()
        return linkedSetOf(value, value.reversed()).flatMap { candidate ->
            val decoded = decodeBytes(candidate) ?: return@flatMap emptyList()
            buildList {
                add(String(decoded, Charsets.UTF_8))
                add(String(ByteArray(decoded.size) { i -> (decoded[i].toInt() xor key[i % key.size].toInt()).toByte() }, Charsets.UTF_8))
            }
        }.distinct()
    }

    private fun findManifestInJson(value: String): String? {
        val root = runCatching { JSONTokener(value).nextValue() }.getOrNull() ?: return null
        val queue = ArrayDeque<Any?>()
        val seenStrings = linkedSetOf<String>()
        queue.add(root)

        fun enqueueJson(candidate: String): String? {
            var parsed: Any? = runCatching { JSONTokener(candidate).nextValue() }.getOrNull() ?: return null
            repeat(4) {
                when (parsed) {
                    is JSONObject, is JSONArray -> {
                        queue.add(parsed)
                        return null
                    }
                    is String -> {
                        val normalized = normalizeKinescopeValue(parsed as String) ?: return null
                        firstManifest(normalized)?.let { return it }
                        parsed = runCatching { JSONTokener(normalized).nextValue() }.getOrNull() ?: return null
                    }
                    else -> return null
                }
            }
            return null
        }

        while (queue.isNotEmpty()) {
            when (val current = queue.removeFirst()) {
                is JSONObject -> {
                    val keys = current.keys()
                    while (keys.hasNext()) queue.add(current.opt(keys.next()))
                }
                is JSONArray -> for (i in 0 until current.length()) queue.add(current.opt(i))
                is String -> {
                    val normalized = normalizeKinescopeValue(current) ?: continue
                    if (!seenStrings.add(normalized)) continue
                    firstManifest(normalized)?.let { return it }
                    enqueueJson(normalized)?.let { return it }
                    decodeBase64Candidates(normalized).forEach { decoded ->
                        val candidate = normalizeKinescopeValue(decoded) ?: return@forEach
                        if (seenStrings.add(candidate)) {
                            firstManifest(candidate)?.let { return it }
                            enqueueJson(candidate)?.let { return it }
                        }
                    }
                }
            }
        }

        return null
    }

    private fun extractKinescopePlayerOptions(html: String): String? {
        val jsonStr = Regex("playerOptions\\s*=\\s*(\\{.+?\\});", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
            ?: Regex("config\\s*=\\s*(\\{.+?\\});", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
            ?: return null
        return findManifestInJson(jsonStr)
    }

    private fun parseKinescopeApiJson(resp: String): String? {
        Log.d("HintFilmIzle", "KINE_RESP_PREVIEW=${resp.take(300)}")
        val json = runCatching { JSONObject(resp) }.getOrNull() ?: return null
        val data = json.optJSONObject("data") ?: json
        Log.d("HintFilmIzle", "KINE_DATA_KEYS=${data.keys().asSequence().toList()}")
        
        val files = data.optJSONObject("files")
        if (files != null) {
            Log.d("HintFilmIzle", "KINE_FILES_KEYS=${files.keys().asSequence().toList()}")
            files.optString("hls").takeIf { it.isNotBlank() }?.let { return normalizeKinescopeValue(it) }
            files.optString("dash").takeIf { it.isNotBlank() }?.let { return normalizeKinescopeValue(it) }
            files.optString("mp4").takeIf { it.isNotBlank() }?.let { return normalizeKinescopeValue(it) }
        }
        
        listOf("master_playlist", "playlist", "hls", "dash", "link", "url", "stream").forEach { key ->
            data.optString(key).takeIf { it.isNotBlank() }?.let { return normalizeKinescopeValue(it) }
        }
        
        listOf("outputs", "streams", "variants", "sources", "quality").forEach { containerKey ->
            val arr = data.optJSONArray(containerKey)
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    listOf("url", "file", "src", "link", "hls", "playlist").forEach { subKey ->
                        item.optString(subKey).takeIf { it.isNotBlank() }?.let { return normalizeKinescopeValue(it) }
                    }
                }
            }
        }
        
        return null
    }
    private fun decodeKinescopeManifestResponse(responseBody: String): String? {
        firstManifest(responseBody)?.let { return it }
        findManifestInJson(responseBody)?.let { return it }
        val encrypted = runCatching { JSONObject(responseBody).optString("p") }.getOrNull()
            ?.let(::normalizeKinescopeValue)
            ?: return null
        return decodeBase64Candidates(encrypted).firstNotNullOfOrNull(::firstManifest)
    }

    private fun redactUrlForLog(url: String): String = url.substringBefore('?') + if (url.contains("?")) "?<redacted>" else ""

    private suspend fun kinescope(kine: String, parent: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean = runCatching {
        var embedUrl = kine
        val playerHtml = runCatching {
            app.get(kine, referer = parent, headers = headers(), interceptor = interceptor).text
        }.getOrNull()

        if (!playerHtml.isNullOrBlank()) {
            extractKinescopePlayerOptions(playerHtml)?.let { direct ->
                callback(newExtractorLink(source = name, name = "HintFilmİzle Kinescope", url = direct, type = ExtractorLinkType.M3U8) {
                    referer = kine
                    headers = mapOf("Referer" to kine, "Origin" to mainUrl, "User-Agent" to ua)
                    quality = getQualityFromName(direct)
                })
                return@runCatching true
            }
            kinescopeManifestRegex.find(playerHtml)?.value?.let { direct ->
                callback(newExtractorLink(source = name, name = "HintFilmİzle Kinescope", url = direct, type = ExtractorLinkType.M3U8) {
                    referer = kine
                    headers = mapOf("Referer" to kine, "Origin" to mainUrl, "User-Agent" to ua)
                    quality = getQualityFromName(direct)
                })
                return@runCatching true
            }
            decodeKinescopeManifestResponse(playerHtml)?.let { direct ->
                callback(newExtractorLink(source = name, name = "HintFilmİzle Kinescope", url = direct, type = ExtractorLinkType.M3U8) {
                    referer = kine
                    headers = mapOf("Referer" to kine, "Origin" to mainUrl, "User-Agent" to ua)
                    quality = getQualityFromName(direct)
                })
                return@runCatching true
            }
            val iframeSrc = Regex("iframe[^>]+src=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(playerHtml)?.groupValues?.get(1)
            if (!iframeSrc.isNullOrBlank()) {
                embedUrl = fix(iframeSrc, kine) ?: kine
            }
        }

        val id = extractKinescopeId(embedUrl) ?: extractKinescopeId(kine) ?: return false

        val targets = buildList {
            add("https://kinescope.io/api/v1/videos/$id")
            add("https://kinescope.io/api/v1/embed/$id")
            add("https://kinescopecdn.net/api/v1/videos/$id")
            add("https://river-3-329.kinescopecdn.net/677113747/embed/$id?design=3&lang=tr")
            add("https://kinescope.io/embed/$id")
            add("https://embed.kinescope.io/embed/$id")
            add(embedUrl)
            add(kine)
        }.distinct()

        for (target in targets) {
            Log.d("HintFilmIzle", "TRYING_KINE_TARGET=$target")
            val isApi = target.contains("/api/")
            val targetReferer = if (isApi) "https://kinescope.io/embed/$id" else parent
            val targetOrigin = if (isApi) "https://kinescope.io" else mainUrl
            val resp = runCatching {
                app.get(target, referer = targetReferer, headers = mapOf(
                    "Referer" to targetReferer,
                    "Origin" to targetOrigin,
                    "X-Requested-With" to "XMLHttpRequest",
                    "User-Agent" to ua,
                    "Accept" to "text/html,application/xhtml+xml,application/json,application/xml;q=0.9,*/*;q=0.8"
                ), interceptor = interceptor).text
            }.getOrNull()

            if (!resp.isNullOrBlank()) {
                Log.d("HintFilmIzle", "GOT_RESP_LEN=${resp.length}")
                parseKinescopeApiJson(resp)?.let { direct ->
                    Log.d("HintFilmIzle", "FOUND_API_JSON_MANIFEST=$direct")
                    callback(newExtractorLink(source = name, name = "HintFilmİzle Kinescope", url = direct, type = if (direct.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                        referer = target
                        headers = mapOf("Referer" to target, "Origin" to "https://kinescope.io", "User-Agent" to ua)
                        quality = getQualityFromName(direct)
                    })
                    return@runCatching true
                }
                extractKinescopePlayerOptions(resp)?.let { direct ->
                    Log.d("HintFilmIzle", "FOUND_PLAYER_OPTIONS=$direct")
                    callback(newExtractorLink(source = name, name = "HintFilmİzle Kinescope", url = direct, type = ExtractorLinkType.M3U8) {
                        referer = target
                        headers = mapOf("Referer" to target, "Origin" to "https://kinescope.io", "User-Agent" to ua)
                        quality = getQualityFromName(direct)
                    })
                    return@runCatching true
                }
                kinescopeManifestRegex.find(resp)?.value?.let { direct ->
                    Log.d("HintFilmIzle", "FOUND_MANIFEST_REGEX=$direct")
                    callback(newExtractorLink(source = name, name = "HintFilmİzle Kinescope", url = direct, type = ExtractorLinkType.M3U8) {
                        referer = target
                        headers = mapOf("Referer" to target, "Origin" to "https://kinescope.io", "User-Agent" to ua)
                        quality = getQualityFromName(direct)
                    })
                    return@runCatching true
                }
                findManifestInJson(resp)?.let { direct ->
                    Log.d("HintFilmIzle", "FOUND_MANIFEST_IN_JSON=$direct")
                    callback(newExtractorLink(source = name, name = "HintFilmİzle Kinescope", url = direct, type = ExtractorLinkType.M3U8) {
                        referer = target
                        headers = mapOf("Referer" to target, "Origin" to "https://kinescope.io", "User-Agent" to ua)
                        quality = getQualityFromName(direct)
                    })
                    return@runCatching true
                }
                decodeKinescopeManifestResponse(resp)?.let { direct ->
                    Log.d("HintFilmIzle", "FOUND_DECODED_MANIFEST=$direct")
                    callback(newExtractorLink(source = name, name = "HintFilmİzle Kinescope", url = direct, type = ExtractorLinkType.M3U8) {
                        referer = target
                        headers = mapOf("Referer" to target, "Origin" to "https://kinescope.io", "User-Agent" to ua)
                        quality = getQualityFromName(direct)
                    })
                    return@runCatching true
                }
            }
        }

        false
    }.getOrElse { Log.e("HintFilmIzle","KINESCOPE_FAILED",it); false }

    override suspend fun loadLinks(data:String,isCasting:Boolean,subtitleCallback:(SubtitleFile)->Unit,callback:(ExtractorLink)->Unit):Boolean {
        val doc=runCatching{app.get(data,referer="$mainUrl/",headers=headers(),interceptor=interceptor).document}.getOrNull()?:return false
        val players=linkedSetOf<String>();fun add(value:String?){player(value,data)?.let{players.add(it)}}
        documentFrames(doc,data,::add)
        
        var linkCount = 0
        val wrappedCallback: (ExtractorLink) -> Unit = { link ->
            linkCount++
            callback(link)
        }

        val ctx = HintFilmIzlePlugin.pluginContext
        for(p in players){
            if (linkCount > 0) break
            val isKine = p.contains("kinescope", true) || p.contains("kinescopecdn", true) || p.contains("player.hintfilmizle.com", true)
            if (isKine) {
                if (ctx != null) {
                    runCatching {
                        HintFilmIzleWebViewExtractor(ctx, name).getUrl(p, data, subtitleCallback, wrappedCallback)
                    }
                }
                if (linkCount > 0) break
                runCatching {
                    kinescope(p, data, subtitleCallback, wrappedCallback)
                }
                if (linkCount > 0) break
            } else {
                runCatching {
                    loadExtractor(p, data, subtitleCallback, wrappedCallback)
                }
                if (linkCount > 0) break
            }
        }

        if (linkCount == 0) {
            doc.select("iframe").forEach { iframe ->
                if (linkCount > 0) return@forEach
                val src = fix(iframe.attr("src").ifBlank { iframe.attr("data-src") }, data)
                if (!src.isNullOrBlank()) {
                    runCatching { loadExtractor(src, data, subtitleCallback, wrappedCallback) }
                }
            }
        }

        return linkCount > 0
    }

    private fun extractKinescopeId(url: String): String? {
        val cleaned = url.substringBefore("?").trimEnd('/')
        val uuidMatch = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", RegexOption.IGNORE_CASE).find(cleaned)
        if (uuidMatch != null) return uuidMatch.value
        val m = Regex("/embed/([A-Za-z0-9_-]+)|v=([A-Za-z0-9_-]+)", RegexOption.IGNORE_CASE).find(cleaned)
        if (m != null) return m.groupValues[1].ifBlank { m.groupValues[2] }
        return cleaned.substringAfterLast('/').takeIf { it.isNotBlank() && it != "embed" && it != "videos" && it != "api" }
    }

    private suspend fun documentFrames(doc: Document, base: String, add: (String?) -> Unit) {
        doc.select("[data-frame], iframe[src], iframe[data-src], iframe[data-url], iframe[data-iframe], frame[src], video[src], video[data-src], video[data-url], video source[src], video source[data-src]").forEach { e -> listOf(e.attr("data-frame"), e.attr("src"), e.attr("data-src"), e.attr("data-url"), e.attr("data-iframe")).forEach(add) }
        doc.select("a[data-frame], a[data-url], a[data-embed], a[data-video], a[data-src], a[data-link], button[data-url], button[data-embed], button[data-video], button[data-src], button[data-link], .alternatifler a, .player-tabs a, .server-tabs a, .partlar a, .bolumler a, .linkler a").forEach { e -> 
            listOf(e.attr("data-frame"), e.attr("data-url"), e.attr("data-embed"), e.attr("data-video"), e.attr("data-src"), e.attr("data-link"), e.attr("href")).forEach(add) 
        }
        doc.select("[data-publisher-id][data-id]").forEach { e -> val pub = e.attr("data-publisher-id").trim(); val id = e.attr("data-id").trim(); if (pub.isNotBlank() && id.isNotBlank()) add("https://river-3-329.kinescopecdn.net/$pub/embed/$id?design=3&lang=tr") }

        val postId = doc.selectFirst("[data-post], [data-post-id], [data-id], input[name='post_id']")?.attr("value")
            ?: doc.selectFirst("[data-post], [data-post-id], [data-id]")?.attr("data-post")
            ?: doc.selectFirst("[data-post], [data-post-id], [data-id]")?.attr("data-id")

        if (!postId.isNullOrBlank()) {
            runCatching {
                val ajaxResp = app.post(
                    "$mainUrl/wp-admin/admin-ajax.php",
                    referer = base,
                    headers = headers() + mapOf("X-Requested-With" to "XMLHttpRequest"),
                    data = mapOf("action" to "get_embed", "post_id" to postId, "id" to postId)
                ).text
                if (ajaxResp.isNotBlank()) {
                    val ajaxDoc = Jsoup.parse(ajaxResp)
                    ajaxDoc.select("iframe[src], iframe[data-src], [data-url]").forEach { e ->
                        add(e.attr("src").ifBlank { e.attr("data-src") }.ifBlank { e.attr("data-url") })
                    }
                    Regex("https?://[^\\\"'\\s<>]+", RegexOption.IGNORE_CASE).findAll(ajaxResp).forEach { add(it.value) }
                }
            }
        }

        doc.select("script").forEach { s ->
            Regex("https?://[^\\\"'\\s<>]+", RegexOption.IGNORE_CASE).findAll(s.data()).forEach { add(it.value) }
            val scriptData = s.data()
            if (scriptData.contains("var cfg =") && scriptData.contains("playerId")) {
                val idMatch = Regex(""""playerId"\s*:\s*"([^"]+)"""").find(scriptData)
                val pubMatch = Regex("""setAttribute\('data-publisher-id',\s*'([^']+)'\)""").find(scriptData)
                val id = idMatch?.groupValues?.get(1)
                val pub = pubMatch?.groupValues?.get(1) ?: "677113747"
                if (!id.isNullOrBlank()) {
                    add("https://river-3-329.kinescopecdn.net/$pub/embed/$id?design=3&lang=tr")
                    add("https://kinescope.io/$id")
                    add("https://embed.kinescope.io/$id")
                    add("https://player.hintfilmizle.com/embed/$id")
                }
            }
        }
    }
}