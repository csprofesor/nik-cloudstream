// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer

class AnimeciX : MainAPI() {
    override var mainUrl              = "https://animecix.tv"
    override var name                 = "AnimeciX"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Anime)

    override var sequentialMainPage = true
    override var sequentialMainPageDelay       = 200L
    override var sequentialMainPageScrollDelay = 200L

    override val mainPage = mainPageOf(
        "${mainUrl}/secure/last-episodes"                          to "Son Eklenen Bölümler",
        "${mainUrl}/secure/titles?type=series&onlyStreamable=true" to "Seriler",
        "${mainUrl}/secure/titles?type=movie&onlyStreamable=true"  to "Filmler",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return if (request.data.contains("/last-episodes")) {
            val response = app.get(
                "${mainUrl}/secure/last-episodes?page=$page&perPage=10",
                headers = mapOf(
                    "x-e-h" to "7Y2ozlO+QysR5w9Q6Tupmtvl9jJp7ThFH8SB+Lo7NvZjgjqRSqOgcT2v4ISM9sP10LmnlYI8WQ==.xrlyOBFS5BHjQ2Lk"
                )
            ).parsedSafe<LastEpisodesResponse>()?.data ?: emptyList()

            val home = response.map {
                val formattedTitle = "S${it.seasonNumber}B${it.episodeNumber} - ${it.titleName}"
                newAnimeSearchResponse(
                    formattedTitle,
                    "${mainUrl}/secure/titles/${it.titleId}?titleId=${it.titleId}",
                    TvType.Anime
                ) {
                    this.posterUrl = fixUrlNull(it.titlePoster)
                }
            }

            newHomePageResponse(request.name, home)
        } else {
            val response = app.get(
                "${request.data}&page=${page}&perPage=16",
                headers = mapOf(
                    "x-e-h" to "7Y2ozlO+QysR5w9Q6Tupmtvl9jJp7ThFH8SB+Lo7NvZjgjqRSqOgcT2v4ISM9sP10LmnlYI8WQ==.xrlyOBFS5BHjQ2Lk"
                )
            ).parsedSafe<Category>()

            val home = response?.pagination?.data?.map { anime ->
                newAnimeSearchResponse(
                    anime.title,
                    "${mainUrl}/secure/titles/${anime.id}?titleId=${anime.id}",
                    TvType.Anime
                ) {
                    this.posterUrl = fixUrlNull(anime.poster)
                }
            } ?: listOf()

            newHomePageResponse(request.name, home)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.get("${mainUrl}/secure/search/${query}?limit=20").parsedSafe<Search>() ?: return listOf()

        return response.results.map { anime ->
            newAnimeSearchResponse(
                anime.title,
                "${mainUrl}/secure/titles/${anime.id}?titleId=${anime.id}",
                TvType.Anime
            ) {
                this.posterUrl = fixUrlNull(anime.poster)
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val response = app.get(
            url,
            headers = mapOf(
                "x-e-h" to "7Y2ozlO+QysR5w9Q6Tupmtvl9jJp7ThFH8SB+Lo7NvZjgjqRSqOgcT2v4ISM9sP10LmnlYI8WQ==.xrlyOBFS5BHjQ2Lk"
            )
        ).parsedSafe<Title>() ?: return null
        val episodes = mutableListOf<Episode>()
        val titleId  = url.substringAfter("?titleId=")

        if (response.title.titleType == "anime") {
            for (sezon in response.title.seasons) {
                val sezonResponse = app.get("${mainUrl}/secure/related-videos?episode=1&season=${sezon.number}&videoId=0&titleId=${titleId}").parsedSafe<TitleVideos>() ?: return null
                for (video in sezonResponse.videos) {
                    episodes.add(newEpisode(video.url) {
                        this.name = "${video.seasonNum}. Sezon ${video.episodeNum}. Bölüm"
                        this.season = video.seasonNum
                        this.episode = video.episodeNum
                    })
                }
            }
        } else {
            if (response.title.videos.isNotEmpty()) {
                episodes.add(newEpisode(response.title.videos.first().url) {
                    this.name    = "Filmi İzle"
                    this.season  = 1
                    this.episode = 1
                })
            }
        }

        return newTvSeriesLoadResponse(
            response.title.title,
            "${mainUrl}/secure/titles/${response.title.id}?titleId=${response.title.id}",
            TvType.Anime,
            episodes
        ) {
            this.posterUrl = fixUrlNull(response.title.poster)
            this.year      = response.title.year
            this.plot      = response.title.description
            this.tags      = response.title.tags.map { it.name }
            this.score     = Score.from10(response.title.rating)
            addActors(response.title.actors.map { Actor(it.name, fixUrlNull(it.poster)) })
            addTrailer(response.title.trailer)
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("ACX", "data » $data")
        val iframeLink = app.get(data, referer="${mainUrl}/").url
        Log.d("ACX", "iframeLink » $iframeLink")

        loadExtractor(iframeLink, "${mainUrl}/", subtitleCallback, callback)

        return true
    }
}