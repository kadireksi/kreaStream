package com.kreastream

import com.lagradost.cloudstream3.*

class YouTubeProvider : MainAPI() {

    override var mainUrl = "https://www.youtube.com"
    override var name = "YouTube"
    override val supportedTypes = setOf(TvType.Others)
    override val hasMainPage = true

    private val parser = YouTubeParser()

    /* ---------------- HOME ---------------- */

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val items = parser.getTrendingVideos().map {
            newMovieSearchResponse(it.name, it.url, TvType.Others) {
                posterUrl = it.thumbnailUrl
            }
        }

        return newHomePageResponse(
            listName = "Trending",
            list = items
        )
    }

    /* ---------------- SEARCH ---------------- */

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()

        parser.searchVideos(query).forEach {
            results += newMovieSearchResponse(it.name, it.url, TvType.Others) {
                posterUrl = it.thumbnailUrl
            }
        }

        parser.searchChannels(query).forEach {
            results += newMovieSearchResponse(it.name, it.url, TvType.Others) {
                posterUrl = it.thumbnailUrl
            }
        }

        parser.searchPlaylists(query).forEach {
            results += newMovieSearchResponse(it.name, it.url, TvType.Others) {
                posterUrl = it.thumbnailUrl
            }
        }

        return results
    }

    /* ---------------- LOAD ---------------- */

    override suspend fun load(url: String): LoadResponse {
        val info = parser.getVideo(url)

        return newMovieLoadResponse(info.name, info.url, TvType.Others, info.url) {
            posterUrl = info.thumbnailUrl
            plot = info.description?.content
        }
    }
}