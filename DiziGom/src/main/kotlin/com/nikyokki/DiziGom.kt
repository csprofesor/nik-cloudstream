package com.nikyokki

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class DiziGom : MainAPI() {
    override var mainUrl = "https://dizigom1.com"
    override var name = "DiziGom"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override suspend fun search(query: String): List<SearchResponse> {
        return app.post("$mainUrl/wp-admin/admin-ajax.php", data = mapOf("action" to "search", "s" to query)).document.select(".search-item").mapNotNull {
            val link = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val title = it.selectFirst(".title")?.text() ?: it.text().trim()
            val poster = it.selectFirst("img")?.attr("src")
            newMovieSearchResponse(title, link, TvType.TvSeries, poster)
        }
    }

    private fun Element.posterUrl(): String? {
        val attributes = listOf("data-src", "data-lazy-src", "data-original", "src", "data-image")
        val images = if (tagName() == "img") listOf(this) else select("img")
        val candidates = sequenceOf(
            *images.flatMap { img ->
                attributes.map { attr -> img.attr(attr) } + listOf(img.attr("style"), img.parent()?.attr("style"))
            }.toTypedArray(),
            *attributes.map { attr -> attr(attr) }.toTypedArray(),
            attr("style"),
            backgroundUrl()
        )

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
                    !url.contains("placehold", true)
            }
    }

    private fun Element.findCard(): Element {
        if (hasClass("episode-box") || hasClass("single-item") ||
            hasClass("dizi-boxpost") || hasClass("dizi-boxpost-cat")) return this

        return parent()?.let { parent ->
            if (parent.hasClass("episode-box") || parent.hasClass("single-item") ||
                parent.hasClass("dizi-boxpost") || parent.hasClass("dizi-boxpost-cat")) parent else this
        } ?: this
    }

    private fun cleanUrl(value: String?): String? {
        val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val match = Regex("url\\((?:'|\\\")?([^'\\\")]+)(?:'|\\\")?\\)", RegexOption.IGNORE_CASE).find(raw)
        val url = match?.groupValues?.getOrNull(1)?.trim() ?: raw
        return url.trim('"', '\'')
            .replace("\\/", "/")
            .takeIf { it.startsWith("http://") || it.startsWith("https://") || it.startsWith("//") }
            ?.let { if (it.startsWith("//")) "https:$it" else it }
    }

    private fun Element.backgroundUrl(): String? = attr("style")
}
