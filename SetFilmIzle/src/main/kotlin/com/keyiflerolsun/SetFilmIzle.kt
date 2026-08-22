// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.nikyokki

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import org.json.JSONObject
import org.jsoup.Jsoup
import okhttp3.*

class SetFilmIzle : MainAPI() {
    override var mainUrl = "https://setfilmizle.ltd"
    override var name = "SetFilmIzle"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "${mainUrl}/tur/aile/" to "Aile",
        "${mainUrl}/tur/aksiyon/" to "Aksiyon",
        "${mainUrl}/tur/animasyon/" to "Animasyon",
        "${mainUrl}/tur/belgesel/" to "Belgesel",
        "${mainUrl}/tur/bilim-kurgu/" to "Bilim-Kurgu",
        "${mainUrl}/tur/biyografi/" to "Biyografi",
        "${mainUrl}/tur/dram/" to "Dram",
        "${mainUrl}/tur/fantastik/" to "Fantastik",
        "${mainUrl}/tur/gerilim/" to "Gerilim",
        "${mainUrl}/tur/gizem/" to "Gizem",
        "${mainUrl}/tur/komedi/" to "Komedi",
        "${mainUrl}/tur/korku/" to "Korku",
        "${mainUrl}/tur/macera/" to "Macera",
        "${mainUrl}/tur/romantik/" to "Romantik",
        "${mainUrl}/tur/suc/" to "Suç",
        "${mainUrl}/tur/tarih/" to "Tarih"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val home = document.select("div.items article").mapNotNull { it.toMainPageResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val title = selectFirst("h2")?.text() ?: return null
        val href = fixUrlNull(selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(selectFirst("img")?.attr("data-src"))
        return if (href.contains("/dizi/")) newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        else newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get(mainUrl).document
        val nonce = Regex("""nonce: '(.*)'""").find(document.html())?.groupValues?.get(1) ?: return emptyList()
        val response = app.post(
            "${mainUrl}/wp-admin/admin-ajax.php",
            headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
            data = mapOf("action" to "ajax_search", "nonce" to nonce, "search" to query)
        )
        val html = JSONObject(response.text).optString("html")
        return if (html.isBlank()) emptyList() else Jsoup.parse(html).select("div.items article").mapNotNull { it.toMainPageResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1")?.text()?.substringBefore(" izle")?.trim() ?: return null
        val poster = fixUrlNull(document.selectFirst("div.poster img")?.attr("src"))
        val description = document.selectFirst("div.wp-content p")?.text()?.trim()
        val year = document.selectFirst("a[href*='/yil/']")?.text()?.trim()?.toIntOrNull()
        val tags = document.select("div.sgeneros a").map { it.text() }
        val recommendations = document.select("div.srelacionados article").mapNotNull { element ->
            val t = element.selectFirst("a img")?.attr("alt") ?: return@mapNotNull null
            val h = fixUrlNull(element.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
            val p = fixUrlNull(element.selectFirst("a img")?.attr("data-src"))
            if (h.contains("/dizi/")) newTvSeriesSearchResponse(t, h, TvType.TvSeries) { posterUrl = p }
            else newMovieSearchResponse(t, h, TvType.Movie) { posterUrl = p }
        }
        if (url.contains("/dizi/")) {
            val episodes = document.select("div#episodes ul.episodios li").mapNotNull {
                val epHref = fixUrlNull(it.selectFirst("h4.episodiotitle a")?.attr("href")) ?: return@mapNotNull null
                val epName = it.selectFirst("h4.episodiotitle a")?.ownText()?.trim() ?: return@mapNotNull null
                val detail = it.selectFirst("h4.episodiotitle a")?.ownText()?.trim() ?: ""
                val season = Regex("(\\d+)\\. Sezon").find(detail)?.groupValues?.get(1)?.toIntOrNull()
                val episode = Regex("Sezon (\\d+)\\. Bölüm").find(detail)?.groupValues?.get(1)?.toIntOrNull()
                newEpisode(epHref) { name = epName; this.season = season; this.episode = episode }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster; plot = description; this.year = year; this.tags = tags; this.recommendations = recommendations; addTrailer(null)
            }
        }
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            posterUrl = poster; plot = description; this.year = year; this.tags = tags; this.recommendations = recommendations; addActors(emptyList()); addTrailer(null)
        }
    }

    private fun requestVideo(nonce: String, postId: String, playerName: String, partKey: String, referer: String): Response {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("action", "get_video_url")
            .addFormDataPart("nonce", nonce)
            .addFormDataPart("post_id", postId)
            .addFormDataPart("player_name", playerName)
            .addFormDataPart("part_key", partKey)
            .build()
        return OkHttpClient().newCall(Request.Builder().url("${mainUrl}/wp-admin/admin-ajax.php").header("Referer", referer).header("X-Requested-With", "XMLHttpRequest").post(body).build()).execute()
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data).document
        val nonce = document.selectFirst("div#playex")?.attr("data-nonce") ?: return false
        document.select("nav.player a").forEach { element ->
            val player = element.attr("data-player-name")
            val postId = element.attr("data-post-id")
            val partKey = element.attr("data-part-key")
            if (postId.isBlank() || postId.contains("event")) return@forEach
            runCatching {
                val text = requestVideo(nonce, postId, player, partKey, data).body.string()
                val url = JSONObject(text).optJSONObject("data")?.optString("url") ?: return@runCatching
                val finalUrl = if (url.contains("setplay") || partKey.isBlank()) url else "$url?partKey=$partKey"
                loadExtractor(finalUrl, "$mainUrl/", subtitleCallback, callback)
            }
        }
        return true
    }
}
