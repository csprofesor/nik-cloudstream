// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import org.json.JSONObject
import org.jsoup.Jsoup
import okhttp3.*

class SetFilmIzle : MainAPI() {
    override var mainUrl = "https://www.setfilmizle.ltd"
    override var name = "SetFilmIzle"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "${mainUrl}/tur/aile/" to "Aile", "${mainUrl}/tur/aksiyon/" to "Aksiyon", "${mainUrl}/tur/animasyon/" to "Animasyon", "${mainUrl}/tur/belgesel/" to "Belgesel", "${mainUrl}/tur/bilim-kurgu/" to "Bilim-Kurgu", "${mainUrl}/tur/biyografi/" to "Biyografi", "${mainUrl}/tur/dini/" to "Dini", "${mainUrl}/tur/dram/" to "Dram", "${mainUrl}/tur/fantastik/" to "Fantastik", "${mainUrl}/tur/genclik/" to "Gençlik", "${mainUrl}/tur/gerilim/" to "Gerilim", "${mainUrl}/tur/gizem/" to "Gizem", "${mainUrl}/tur/komedi/" to "Komedi", "${mainUrl}/tur/korku/" to "Korku", "${mainUrl}/tur/macera/" to "Macera", "${mainUrl}/tur/mini-dizi/" to "Mini Dizi", "${mainUrl}/tur/muzik/" to "Müzik", "${mainUrl}/tur/program/" to "Program", "${mainUrl}/tur/romantik/" to "Romantik", "${mainUrl}/tur/savas/" to "Savaş", "${mainUrl}/tur/spor/" to "Spor", "${mainUrl}/tur/suc/" to "Suç", "${mainUrl}/tur/tarih/" to "Tarih", "${mainUrl}/tur/western/" to "Western")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        return newHomePageResponse(request.name, document.select("div.items article").mapNotNull { it.toMainPageResult() })
    }
    private fun Element.toMainPageResult(): SearchResponse? {
        val title = selectFirst("h2")?.text() ?: return null
        val href = fixUrlNull(selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(selectFirst("img")?.attr("data-src"))
        return if (href.contains("/dizi/")) newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl } else newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }
    override suspend fun search(query: String): List<SearchResponse> {
        val mainPage = app.get(mainUrl).document
        val nonce = Regex("""nonce: '(.*)'""").find(mainPage.html())?.groupValues?.get(1) ?: ""
        val search = app.post("${mainUrl}/wp-admin/admin-ajax.php", headers = mapOf("X-Requested-With" to "XMLHttpRequest"), data = mapOf("action" to "ajax_search", "nonce" to nonce, "search" to query))
        return Jsoup.parse(JSONObject(search.text).getString("html")).select("div.items article").mapNotNull { it.toMainPageResult() }
    }
    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1")?.text()?.substringBefore(" izle")?.trim() ?: return null
        val poster = fixUrlNull(document.selectFirst("div.poster img")?.attr("src"))
        val description = document.selectFirst("div.wp-content p")?.text()?.trim()
        var year = document.selectFirst("div.extra span.C a")?.text()?.trim()?.toIntOrNull()
        val tags = document.select("div.sgeneros a").map { it.text() }
        var duration = document.selectFirst("span.runtime")?.text()?.split(" ")?.first()?.trim()?.toIntOrNull()
        val recommendations = document.select("div.srelacionados article").mapNotNull { toRecommendationResult(it) }
        val actors = document.select("span.valor a").map { Actor(it.text()) }
        val trailer = Regex("""embed/(.*)\?rel""").find(document.html())?.groupValues?.get(1)?.let { "https://www.youtube.com/embed/$it" }
        if (url.contains("/dizi/")) {
            year = document.selectFirst("a[href*='/yil/']")?.text()?.trim()?.toIntOrNull()
            duration = document.selectFirst("div#info span:containsOwn(Dakika)")?.text()?.split(" ")?.first()?.trim()?.toIntOrNull()
            val episodes = document.select("div#episodes ul.episodios li").mapNotNull { el ->
                val epHref = fixUrlNull(el.selectFirst("h4.episodiotitle a")?.attr("href")) ?: return@mapNotNull null
                val epName = el.selectFirst("h4.episodiotitle a")?.ownText()?.trim() ?: return@mapNotNull null
                val epDetail = el.selectFirst("h4.episodiotitle a")?.ownText()?.trim() ?: return@mapNotNull null
                newEpisode(epHref) { name = epName; season = epDetail.substringBefore(". Sezon").toIntOrNull(); episode = epDetail.split("Sezon ").last().substringBefore(". Bölüm").toIntOrNull() }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) { posterUrl = poster; plot = description; this.year = year; this.tags = tags; this.duration = duration; this.recommendations = recommendations; addActors(actors); addTrailer(trailer) }
        }
        return newMovieLoadResponse(title, url, TvType.Movie, url) { posterUrl = poster; plot = description; this.year = year; this.tags = tags; this.duration = duration; this.recommendations = recommendations; addActors(actors); addTrailer(trailer) }
    }
    private fun toRecommendationResult(el: Element): SearchResponse? {
        val title = el.selectFirst("a img")?.attr("alt") ?: return null
        val href = fixUrlNull(el.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(el.selectFirst("a img")?.attr("data-src"))
        return if (href.contains("/dizi/")) newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl } else newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }
    private fun sendMultipartRequest(nonce: String, postId: String, playerName: String, partKey: String, referer: String): Response {
        val requestBody = MultipartBody.Builder().setType(MultipartBody.FORM).apply { addFormDataPart("action", "get_video_url"); addFormDataPart("nonce", nonce); addFormDataPart("post_id", postId); addFormDataPart("player_name", playerName); addFormDataPart("part_key", partKey) }.build()
        return OkHttpClient().newCall(Request.Builder().url("${mainUrl}/wp-admin/admin-ajax.php").header("Referer", referer).header("X-Requested-With", "XMLHttpRequest").post(requestBody).build()).execute()
    }
    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data).document
        document.select("nav.player a").map { Triple(it.attr("data-player-name"), it.attr("data-post-id"), it.attr("data-part-key").takeIf { k -> k.isNotEmpty() }) }.forEach { (name, sourceId, partKey) ->
            if (sourceId.contains("event") || sourceId.isEmpty()) return@forEach
            val nonce = document.selectFirst("div#playex")?.attr("data-nonce") ?: ""
            val sourceBody = sendMultipartRequest(nonce, sourceId, name, partKey ?: "", data).body.string()
            val sourceIframe = JSONObject(sourceBody).optJSONObject("data")?.optString("url") ?: return@forEach
            val finalUrl = if (sourceIframe.contains("setplay")) sourceIframe else if (partKey != null) "$sourceIframe?partKey=$partKey" else sourceIframe
            loadExtractor(finalUrl, "$mainUrl/", subtitleCallback, callback)
        }
        return true
    }
}
