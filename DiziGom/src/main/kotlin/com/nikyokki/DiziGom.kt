package com.nikyokki

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.Episode
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
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

class DiziGom : MainAPI() {
    override var mainUrl = "https://www.dizigom.love"
    override var name = "DiziGom"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries)

    // DiziGom'un güncel sitedeki tür menüsü.
    override val mainPage = mainPageOf(
        "${mainUrl}/tur/aile/" to "Aile",
        "${mainUrl}/tur/aksiyon/" to "Aksiyon",
        "${mainUrl}/tur/animasyon/" to "Animasyon",
        "${mainUrl}/tur/belgesel/" to "Belgesel",
        "${mainUrl}/tur/bilim-kurgu/" to "Bilim Kurgu",
        "${mainUrl}/tur/dram/" to "Dram",
        "${mainUrl}/tur/fantastik/" to "Fantastik",
        "${mainUrl}/tur/gerilim/" to "Gerilim",
        "${mainUrl}/tur/komedi/" to "Komedi",
        "${mainUrl}/tur/korku/" to "Korku",
        "${mainUrl}/tur/macera/" to "Macera",
        "${mainUrl}/tur/polisiye/" to "Polisiye",
        "${mainUrl}/tur/romantik/" to "Romantik",
        "${mainUrl}/tur/savas/" to "Savaş",
        "${mainUrl}/tur/suc/" to "Suç",
        "${mainUrl}/tur/tarih/" to "Tarih"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}/#p=$page", referer = "$mainUrl/").document

        if (page == 1) {
            return newHomePageResponse(
                request.name,
                document.select("div.episode-box").mapNotNull { it.toMainPageResult() }
            )
        }

        val taxInput = document.selectFirst("form.dizigom_advenced_search input")
        val tax = taxInput?.attr("name") ?: ""
        val value = taxInput?.attr("value") ?: ""

        val pagedoc = app.post(
            "$mainUrl/wp-admin/admin-ajax.php",
            referer = request.data,
            headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
            data = mapOf(
                "action" to "dizigom_search_action",
                "formData" to "$tax=$value",
                "paged" to page.toString(),
                // Site halen bu nonce'u kullanıyor; değiştiğinde istek başarısız olsa bile
                // ilk sayfadaki içerik çalışmaya devam eder.
                "_wpnonce" to "18a90a7287"
            )
        ).document

        return newHomePageResponse(
            request.name,
            pagedoc.select("div.episode-box").mapNotNull { it.toMainPageResult() }
        )
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val title = selectFirst("div.serie-name a")?.text()?.trim() ?: return null
        val href = fixUrlNull(selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(selectFirst("img")?.let { it.attr("data-src").ifBlank { it.attr("src") } })
        val score = selectFirst("div.episode-date")?.text()
            ?.substringAfter("IMDb:", "")?.trim()

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
            this.score = Score.from10(score)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=${query.trim().replace(" ", "+")}").document
        return document.select("div.single-item").mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = selectFirst("div.categorytitle a") ?: return null
        val title = link.text().trim()
        val href = fixUrlNull(link.attr("href")) ?: return null
        val image = selectFirst("img")
        val posterUrl = fixUrlNull(image?.attr("data-src").orEmpty().ifBlank { image?.attr("src").orEmpty() })

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, referer = "$mainUrl/").document
        val title = document.selectFirst("div.serieTitle h1")?.text()?.trim() ?: return null

        val poster = fixUrlNull(
            document.selectFirst("div.seriePoster")?.attr("style")
                ?.substringAfter("background-image:url(")
                ?.substringBefore(")")
                ?.trim(' ', '\'', '"')
        )
        val description = document.selectFirst("div.serieDescription p")?.text()?.trim()
        val year = document.selectFirst("div.airDateYear a")?.text()?.trim()?.toIntOrNull()
        val tags = document.select("div.genreList a").map { it.text().trim() }.filter { it.isNotEmpty() }
        val rating = document.selectFirst("div.score")?.text()?.trim()
        val duration = document.select("div.serieMetaInformation div.totalSession")
            .lastOrNull()?.text()?.substringBefore(" ")?.toIntOrNull()
        val actors = document.select("div.owl-stage a").mapNotNull { actor ->
            val actorName = actor.text().trim()
            if (actorName.isBlank()) null else Actor(actorName, fixUrlNull(actor.selectFirst("img")?.attr("src")))
        }

        val episodes = document.select("div.bolumust").mapNotNull { element ->
            val epHref = fixUrlNull(element.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
            val epName = element.selectFirst("div.bolum-ismi")?.text()?.trim()
            val parts = element.selectFirst("div.baslik")?.text()?.trim()?.split(" ") ?: emptyList()
            val season = parts.getOrNull(0)?.replace(".", "")?.toIntOrNull()
            val episode = parts.getOrNull(2)?.replace(".", "")?.toIntOrNull()

            newEpisode(epHref) {
                name = epName
                this.season = season
                this.episode = episode
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            posterUrl = poster
            this.year = year
            plot = description
            this.tags = tags
            this.duration = duration
            score = Score.from10(rating)
            addActors(actors)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val mapper = ObjectMapper()
            .registerModule(KotlinModule.Builder().build())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

        Log.d("DiziGom", "Episode: $data")

        val episodeDocument = app.get(data, referer = "$mainUrl/").document
        val scripts = episodeDocument.select("script")

        // Eski yapıda JSON-LD içindeki contentUrl kullanılıyordu. Script seçimini
        // sabit sıraya bağlamak yerine gerçekten contentUrl içeren script'i buluyoruz.
        val contentScript = scripts.firstOrNull { it.data().contains("contentUrl") }
            ?: episodeDocument.selectFirst("div#content script")

        val contentUrl = contentScript?.data()?.let { script ->
            runCatching { mapper.readValue<Gof>(script).contentUrl }.getOrNull()
                ?: Regex("""[\"']contentUrl[\"']\s*:\s*[\"']([^\"']+)[\"']""")
                    .find(script)?.groupValues?.getOrNull(1)
        }?.takeIf { it.isNotBlank() }

        if (contentUrl == null) {
            Log.e("DiziGom", "contentUrl bulunamadı")
            return false
        }

        // Önce sitenin verdiği URL'yi aynen deniyoruz. Eski sürümde kullanılan
        // zorunlu play. dönüşümü yalnızca ilk deneme kaynak vermediğinde uygulanıyor.
        val playerUrls = buildList {
            add(contentUrl)
            if (contentUrl.startsWith("https://") && !contentUrl.contains("://play.")) {
                add(contentUrl.replaceFirst("https://", "https://play."))
            }
        }.distinct()

        for (playerUrl in playerUrls) {
            val playerDocument = runCatching {
                app.get(playerUrl, referer = "$mainUrl/").document
            }.getOrNull() ?: continue

            val packedScript = playerDocument.select("script")
                .firstOrNull { it.data().contains("eval(function(p,a,c,k,e") }
                ?.data()

            val unpacked = packedScript?.let { JsUnpacker(it).unpack() } ?: ""
            val sourceText = unpacked
                .substringAfter("sources:", "")
                .substringAfter("sources =", "")
                .substringBefore("]", "")
                .replace("\\/", "/")
                .trim()

            if (sourceText.isBlank()) continue

            val source = parseSource(mapper, sourceText) ?: continue
            if (source.file.isBlank()) continue

            callback(
                newExtractorLink(
                    source = name,
                    name = "DiziGom ${source.label.ifBlank { "Otomatik" }}",
                    url = source.file,
                    type = ExtractorLinkType.M3U8
                ) {
                    referer = playerUrl
                    quality = getQualityFromName(source.label)
                }
            )
            return true
        }

        Log.e("DiziGom", "Video kaynağı bulunamadı: $data")
        return false
    }

    private fun parseSource(mapper: ObjectMapper, raw: String): Go? {
        // Önce mevcut JSON biçimini dene.
        runCatching { mapper.readValue<Go>(raw) }.getOrNull()?.let { return it }
        runCatching { mapper.readValue<List<Go>>("[$raw]").firstOrNull() }.getOrNull()?.let { return it }

        // Player bazen JS nesnesi döndürüyor: {file:"...",label:"1080p"}
        val file = Regex("""[\"']?file[\"']?\s*:\s*[\"']([^\"']+)[\"']""")
            .find(raw)?.groupValues?.getOrNull(1) ?: return null
        val label = Regex("""[\"']?label[\"']?\s*:\s*[\"']([^\"']*)[\"']""")
            .find(raw)?.groupValues?.getOrNull(1).orEmpty()
        val type = Regex("""[\"']?type[\"']?\s*:\s*[\"']([^\"']*)[\"']""")
            .find(raw)?.groupValues?.getOrNull(1).orEmpty()

        return Go(file = file, label = label, type = type)
    }

    data class Go(
        @JsonProperty("file") val file: String,
        @JsonProperty("label") val label: String = "",
        @JsonProperty("type") val type: String = ""
    )

    data class Gof(
        @JsonProperty("@context") val context: String = "",
        @JsonProperty("@type") val type: String = "",
        @JsonProperty("position") val position: String = "",
        @JsonProperty("name") val name: String = "",
        @JsonProperty("description") val description: String = "",
        @JsonProperty("thumbnailUrl") val thumbnailUrl: String = "",
        @JsonProperty("uploadDate") val uploadDate: String = "",
        @JsonProperty("duration") val duration: String = "",
        @JsonProperty("contentUrl") val contentUrl: String = "",
        @JsonProperty("timeRequired") val timeRequired: String = "",
        @JsonProperty("embedUrl") val embedUrl: String = "",
        @JsonProperty("interactionCount") val interactionCount: String = ""
    )
}
