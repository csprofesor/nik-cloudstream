package com.keyiflerolsun

import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class HDFilmCehennemi : MainAPI() {
    override var mainUrl = "https://www.hdfilmcehennemi.nl"
    override var name = "HDFilmCehennemi"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )

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
        "${mainUrl}/load/page/sayfano/home/"                                       to "Yeni Eklenen Filmler",
        "${mainUrl}/load/page/sayfano/home-series/"                                to "Yeni Eklenen Diziler",
        "${mainUrl}/load/page/sayfano/categories/tavsiye-filmler-izle3/"           to "Tavsiye Filmler",
        "${mainUrl}/load/page/sayfano/imdb7/"                                      to "IMDB 7+ Filmler",
        "${mainUrl}/load/page/sayfano/mostCommented/"                              to "En Çok Yorumlananlar",
        "${mainUrl}/load/page/sayfano/mostLiked/"                                  to "En Çok Beğenilenler",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val objectMapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        val url = request.data.replace("sayfano", page.toString())
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:137.0) Gecko/20100101 Firefox/137.0",
            "Accept" to "*/*", "X-Requested-With" to "fetch"
        )
        val doc = app.get(url, headers = headers, referer = mainUrl, interceptor = interceptor).text
        val home: List<SearchResponse>?
        if (!doc.contains("Sayfa Bulunamadı")) {
            val aa: HDFC = objectMapper.readValue(doc)
            val document = Jsoup.parse(aa.html)

            home = document.select("a").mapNotNull { it.toSearchResult() }
            return newHomePageResponse(request.name, home)
        }
        return newHomePageResponse(request.name, emptyList())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.attr("title")
        val href = fixUrlNull(this.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src"))

        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.get(
            "${mainUrl}/search?q=${query}",
            headers = mapOf("X-Requested-With" to "fetch")
        ).parsedSafe<Results>() ?: return emptyList()
        val searchResults = mutableListOf<SearchResponse>()

        response.results.forEach { resultHtml ->
            val document = Jsoup.parse(resultHtml)

            val title     = document.selectFirst("h4.title")?.text() ?: return@forEach
            val href      = fixUrlNull(document.selectFirst("a")?.attr("href")) ?: return@forEach
            val posterUrl = fixUrlNull(document.selectFirst("img")?.attr("src")) ?: fixUrlNull(document.selectFirst("img")?.attr("data-src"))

            searchResults.add(
                newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl?.replace("/thumb/", "/list/") }
            )
        }

        return searchResults
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, interceptor = interceptor).document

        val title       = document.selectFirst("h1.section-title")?.text()?.substringBefore(" izle") ?: return null
        val poster      = fixUrlNull(document.select("aside.post-info-poster img.lazyload").lastOrNull()?.attr("data-src"))
        val tags        = document.select("div.post-info-genres a").map { it.text() }
        val year        = document.selectFirst("div.post-info-year-country a")?.text()?.trim()?.toIntOrNull()
        val tvType      = if (document.select("div.seasons").isEmpty()) TvType.Movie else TvType.TvSeries
        val description = document.selectFirst("article.post-info-content > p")?.text()?.trim()
        val actors      = document.select("div.post-info-cast a").map {
            Actor(it.selectFirst("strong")!!.text(), it.select("img").attr("data-src"))
        }

        val recommendations = document.select("div.section-slider-container div.slider-slide").mapNotNull {
                val recName      = it.selectFirst("a")?.attr("title") ?: return@mapNotNull null
                val recHref      = fixUrlNull(it.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
                val recPosterUrl = fixUrlNull(it.selectFirst("img")?.attr("data-src")) ?: fixUrlNull(it.selectFirst("img")?.attr("src"))

                newTvSeriesSearchResponse(recName, recHref, TvType.TvSeries) {
                    this.posterUrl = recPosterUrl
                }
            }

        return if (tvType == TvType.TvSeries) {
            val trailer  = document.selectFirst("div.post-info-trailer button")?.attr("data-modal")?.substringAfter("trailer/", "")?.let { if (it.isNotEmpty()) "https://www.youtube.com/watch?v=$it" else null }
            val episodes = document.select("div.seasons-tab-content a").mapNotNull {
                val epName    = it.selectFirst("h4")?.text()?.trim() ?: return@mapNotNull null
                val epHref    = fixUrlNull(it.attr("href")) ?: return@mapNotNull null
                val epEpisode = Regex("""(\d+)\. ?Bölüm""").find(epName)?.groupValues?.get(1)?.toIntOrNull()
                val epSeason  = Regex("""(\d+)\. ?Sezon""").find(epName)?.groupValues?.get(1)?.toIntOrNull() ?: 1

                newEpisode(epHref) {
                    this.name = epName
                    this.season = epSeason
                    this.episode = epEpisode
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl       = poster
                this.year            = year
                this.plot            = description
                this.tags            = tags
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
            }
        } else {
            val trailer = document.selectFirst("div.post-info-trailer button")?.attr("data-modal")?.substringAfter("trailer/", "")?.let { if (it.isNotEmpty()) "https://www.youtube.com/watch?v=$it" else null }
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl       = poster
                this.year            = year
                this.plot            = description
                this.tags            = tags
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
            }
        }
    }

    data class DecOp(val name: String, val rotShift: Int = 0)

    private fun decryptLocalUrl(script: String): String? {
        try {
            // 1. Yeni Şifreleme Algoritması (j3eq / a3w tarzı array ve key/ops tabanlı çözme)
            val newAlgoMatch = """var\s+(\w+)\s*=\s*(\w+)\(\[\s*((?:['"][^'"]+['"]\s*,?\s*)+)\]\);""".toRegex().find(script)
            if (newAlgoMatch != null) {
                val funcName = newAlgoMatch.groupValues[2]
                val arrayContent = newAlgoMatch.groupValues[3]
                
                val parts = arrayContent.split(",").map { 
                    it.trim().trim('\'', '"').replace("\\/", "/") 
                }
                var result = parts.joinToString("")

                val funcBodyMatch = """function $funcName\([^)]+\)\s*\{([\s\S]*?return\s+[^;]+;)\s*\}""".toRegex().find(script)
                if (funcBodyMatch != null) {
                    val funcBody = funcBodyMatch.groupValues[1]
                    val stringsMatch = """var\s+\w+\s*=\s*["']([^"']+)["'];\s*var\s+\w+\s*=\s*["']([^"']+)["'];""".toRegex().find(funcBody)
                    if (stringsMatch != null) {
                        val key1 = stringsMatch.groupValues[1]
                        val ops = stringsMatch.groupValues[2]

                        var lsv2 = 0
                        var jaj04 = 0
                        for (i in key1.indices) {
                            val qx97l = key1[i].code
                            lsv2 = (lsv2 * 31 + qx97l) % 251
                            jaj04 = (jaj04 xor (qx97l + i)) and 255
                        }
                        val r9q = (lsv2 + jaj04) % 256

                        for (op in ops.reversed()) {
                            if (op == 'b') {
                                var paddedResult = result
                                while (paddedResult.length % 4 != 0) {
                                    paddedResult += "="
                                }
                                result = String(Base64.decode(paddedResult, Base64.NO_WRAP), Charsets.ISO_8859_1)
                            } else if (op == 'v') {
                                result = result.reversed()
                            } else {
                                val g31 = (26 - ((op.code - 64) % 26)) % 26
                                val rot = java.lang.StringBuilder()
                                for (c in result) {
                                    if (c in 'a'..'z') {
                                        val shifted = c.code - 97 + g31
                                        rot.append((shifted % 26 + 97).toChar())
                                    } else if (c in 'A'..'Z') {
                                        val shifted = c.code - 65 + g31
                                        rot.append((shifted % 26 + 65).toChar())
                                    } else {
                                        rot.append(c)
                                    }
                                }
                                result = rot.toString()
                            }
                        }

                        val finalResult = java.lang.StringBuilder()
                        for (c in result) {
                            finalResult.append((c.code xor r9q).toChar())
                        }
                        return finalResult.toString()
                    }
                }
            }

            // 2. Eski Şifreleme Algoritması (Packer çözümü ile)
            val unpackedScript = unpackPackerJs(script) ?: script
            val partsMatch = """\(\[\s*((?:['"][^'"]+['"]\s*,?\s*)+)\]\)""".toRegex().find(unpackedScript)
            val parts = partsMatch?.groupValues?.get(1)?.split(",")?.map { 
                it.trim().trim('\'', '"').replace("\\/", "/") 
            } ?: return null

            val moduloMatch = """(\d+)\s*%\s*\(i\s*\+\s*(\d+)\)""".toRegex().find(unpackedScript)
            val magicNum = moduloMatch?.groupValues?.get(1)?.toLongOrNull() ?: 399756995L
            val magicOffset = moduloMatch?.groupValues?.get(2)?.toIntOrNull() ?: 5

            val funcBody = unpackedScript.substringAfter("function dc_").substringBefore("function d1x")

            val operations = mutableListOf<Pair<Int, DecOp>>()

            var index = funcBody.indexOf("atob(")
            while (index >= 0) {
                operations.add(Pair(index, DecOp("atob")))
                index = funcBody.indexOf("atob(", index + 1)
            }

            index = funcBody.indexOf("reverse")
            while (index >= 0) {
                operations.add(Pair(index, DecOp("reverse")))
                index = funcBody.indexOf("reverse", index + 1)
            }

            index = funcBody.indexOf("replace")
            while (index >= 0) {
                val block = funcBody.substring(index, minOf(index + 300, funcBody.length))
                var shift = 13
                val rotShiftMatch = """charCodeAt\(0\)\s*\+\s*(\d+)""".toRegex().find(block)
                if (rotShiftMatch != null) {
                    shift = rotShiftMatch.groupValues[1].toInt()
                } else {
                    val rotShiftMatch2 = """o\s*-\s*base\s*([+-])\s*(\d+)""".toRegex().find(block)
                    if (rotShiftMatch2 != null) {
                        val sign = rotShiftMatch2.groupValues[1]
                        val num = rotShiftMatch2.groupValues[2].toInt()
                        shift = if (sign == "-") (26 - num) % 26 else num
                    }
                }
                operations.add(Pair(index, DecOp("rot", shift)))
                index = funcBody.indexOf("replace", index + 1)
            }

            operations.sortBy { it.first }

            var result = parts.joinToString("")

            for (op in operations) {
                val action = op.second
                when (action.name) {
                    "reverse" -> {
                        result = result.reversed()
                    }
                    "atob" -> {
                        var paddedResult = result
                        while (paddedResult.length % 4 != 0) {
                            paddedResult += "="
                        }
                        result = String(Base64.decode(paddedResult, Base64.NO_WRAP), Charsets.ISO_8859_1)
                    }
                    "rot" -> {
                        val rotShift = action.rotShift
                        val rot = StringBuilder()
                        for (c in result) {
                            if (c in 'a'..'z') {
                                val shifted = c.code + rotShift
                                rot.append(if (shifted > 'z'.code) (shifted - 26).toChar() else shifted.toChar())
                            } else if (c in 'A'..'Z') {
                                val shifted = c.code + rotShift
                                rot.append(if (shifted > 'Z'.code) (shifted - 26).toChar() else shifted.toChar())
                            } else {
                                rot.append(c)
                            }
                        }
                        result = rot.toString()
                    }
                }
            }

            val unmix = StringBuilder()
            for (i in result.indices) {
                val charCode = result[i].code.toLong()
                val decryptedCode = (charCode - (magicNum % (i + magicOffset)) + 256) % 256
                unmix.append(decryptedCode.toInt().toChar())
            }

            return unmix.toString()

        } catch (e: Exception) {
            Log.e("HDCH", "decryptLocalUrl Error: ${e.message}")
            return null
        }
    }

    private suspend fun invokeLocalSource(source: String, url: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit ) {
        val script    = app.get(url, referer = "${mainUrl}/", interceptor = interceptor).document.select("script").find { it.data().contains("sources:") }?.data() ?: return
        Log.d("HDCH", "script » $script")
        val unpackedScript = getAndUnpack(script)
        val decryptedUrl = decryptLocalUrl(script) ?: decryptLocalUrl(unpackedScript) ?: return
        val lastUrl = decryptedUrl.substringAfter("https").let { "https$it" }
        val subData   = script.substringAfter("tracks: [").substringBefore("]")
        Log.d("HDCH", "subData » $subData")
        AppUtils.tryParseJson<List<SubSource>>("[${subData}]")?.filter { it.kind == "captions"}?.forEach {
            val subtitleUrl = "${mainUrl}${it.file}/"

            val headers = mapOf(
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:137.0) Gecko/20100101 Firefox/137.0",
                "Referer" to "subtitleUrl"
            )
            val subtitleResponse = app.get(subtitleUrl, headers = headers, allowRedirects=true, interceptor = interceptor)
            if (subtitleResponse.isSuccessful) {
                subtitleCallback(newSubtitleFile(it.language.toString(), subtitleUrl))
                Log.d("HDCH", "Subtitle added: $subtitleUrl")
            } else {
                Log.d("HDCH", "Subtitle URL inaccessible: ${subtitleResponse.code}")
            }
        }
        callback.invoke(
            newExtractorLink(
                source  = source,
                name    = source,
                url     = lastUrl,
                type    = ExtractorLinkType.M3U8
            ) {
                headers = mapOf("Referer" to "${mainUrl}/", "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36 Norton/124.0.0.0")
                quality = Qualities.Unknown.value
            }
        )
    }

    private fun unpackPackerJs(rawHtml: String): String? {
        return try {
            val startMarker = "eval(function(p,a,c,k,e,d){"
            val endMarker = ",0,{}))"

            val startIdx = rawHtml.indexOf(startMarker)
            if (startIdx == -1) return null

            val endIdx = rawHtml.indexOf(endMarker, startIdx + startMarker.length)
            if (endIdx == -1) return null

            val block = rawHtml.substring(startIdx, endIdx + endMarker.length)
            val packedStart = block.indexOf("}('") + 3
            val packedEnd = block.indexOf("',", packedStart)
            if (packedStart == -1 || packedEnd == -1) return null
            val packed = block.substring(packedStart, packedEnd)
            val afterPacked = block.substring(packedEnd + 2)
            val baseEnd = afterPacked.indexOf(",")
            if (baseEnd == -1) return null
            val base = afterPacked.substring(0, baseEnd).toInt()
            val afterBase = afterPacked.substring(baseEnd + 1)
            val countEnd = afterBase.indexOf(",")
            if (countEnd == -1) return null
            val count = afterBase.substring(0, countEnd).toInt()
            val dictQuoteStart = afterBase.indexOf("'") + 1
            val dictQuoteEnd = afterBase.indexOf("'.split", dictQuoteStart)
            if (dictQuoteStart == -1 || dictQuoteEnd == -1) return null
            val dictStr = afterBase.substring(dictQuoteStart, dictQuoteEnd)

            val dictionary = dictStr.split('|')
            val lookup = mutableMapOf<String, String>()

            var c = count - 1
            while (c >= 0) {
                val key = packerEncode(c, base)
                lookup[key] = if (c < dictionary.size && dictionary[c].isNotEmpty()) {
                    dictionary[c]
                } else {
                    key
                }
                c--
            }

            var result = packed
            val sortedKeys = lookup.keys.sortedByDescending { it.length }
            for (key in sortedKeys) {
                val value = lookup[key]!!
                result = result.replace(Regex("\\b${Regex.escape(key)}\\b"), value)
            }

            result
        } catch (e: Exception) {
            null
        }
    }

    private fun packerEncode(num: Int, base: Int): String {
        val digits = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        if (num == 0) return "0"
        var n = num
        val sb = StringBuilder()
        while (n > 0) {
            sb.insert(0, digits[n % base])
            n /= base
        }
        return sb.toString()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("HDCH", "data » $data")
        val document = app.get(data, interceptor = interceptor).document

        document.select("div.alternative-links").map { element ->
            element to element.attr("data-lang").uppercase()
        }.forEach { (element, langCode) ->
            element.select("button.alternative-link").map { button ->
                button.text().replace("(HDrip Xbet)", "").trim() + " $langCode" to button.attr("data-video")
            }.forEach { (source, videoID) ->
                val apiGet = app.get(
                    "${mainUrl}/video/$videoID/", interceptor = interceptor,
                    headers = mapOf(
                        "Content-Type" to "application/json",
                        "X-Requested-With" to "fetch"
                    ),
                    referer = data
                ).text
                Log.d("HDCH", "Found videoID: $videoID")
                var iframe = Regex("""data-src=\\"([^"]+)""").find(apiGet)?.groupValues?.get(1)?.replace("\\", "")
                    ?: Regex("""src=['"]([^'"]+)['"]""").find(apiGet)?.groupValues?.get(1)
                    ?: return@forEach
                if (iframe.contains("rapidrame")) {
                    iframe = "${mainUrl}/rplayer/" + iframe.substringAfter("?rapidrame_id=")
                } else if (iframe.contains("mobi")) {
                    val iframeDoc = Jsoup.parse(apiGet)
                    iframe = fixUrlNull(iframeDoc.selectFirst("iframe")?.attr("data-src")) ?: return@forEach
                }
                Log.d("HDCH", "$source » $videoID » $iframe")
                invokeLocalSource(source, iframe, subtitleCallback, callback)
            }
        }
        return true
    }

    private data class SubSource(
        @JsonProperty("file")    val file: String?  = null,
        @JsonProperty("label")   val label: String? = null,
        @JsonProperty("language") val language: String? = null,
        @JsonProperty("kind")    val kind: String?  = null
    )

    data class Results(
        @JsonProperty("results") val results: List<String> = arrayListOf()
    )
    data class HDFC(
        @JsonProperty("html") val html: String,
        @JsonProperty("meta") val meta: Meta
    )

    data class Meta(
        @JsonProperty("title") val title: String,
        @JsonProperty("canonical") val canonical: String,
        @JsonProperty("keywords") val keywords: Boolean
    )
}
