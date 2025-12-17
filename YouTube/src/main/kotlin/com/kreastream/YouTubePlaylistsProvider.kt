package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.newEpisode
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.SubtitleFile

class YouTubePlaylistsProvider(language: String) : MainAPI() {

    override var mainUrl = "https://www.youtube.com"
    override var name = "YouTube Playlists"
    override val supportedTypes = setOf(TvType.Others)
    override val hasMainPage = false
    override var lang = language

    private val parser = YouTubeParser()

    /* ---------------- SEARCH ---------------- */

    override suspend fun search(query: String): List<SearchResponse> {
        return parser.searchPlaylists(query).map {
            newTvSeriesSearchResponse(
                it.name,
                it.url,
                TvType.Others
            ) {
                posterUrl = it.thumbnailUrl
            }
        }
    }

    /* ---------------- LOAD ---------------- */

    override suspend fun load(url: String): LoadResponse {
        val playlist = parser.getPlaylist(url)

        val episodes = playlist.videos.map {
            newEpisode(it.url) {
                name = it.name
                posterUrl = it.thumbnailUrl
            }
        }

        return newTvSeriesLoadResponse(
            playlist.name,
            playlist.url,
            TvType.Others,
            episodes
        ) {
            posterUrl = playlist.thumbnailUrl
        }
    }

    /* ---------------- LINKS ---------------- */

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        YouTubeExtractor().getUrl(data, null, subtitleCallback, callback)
        return true
    }
}
