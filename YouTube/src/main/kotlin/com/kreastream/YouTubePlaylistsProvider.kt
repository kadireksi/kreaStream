package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.SubtitleFile

class YouTubePlaylistsProvider(language: String) : MainAPI() {

    override var mainUrl = "https://www.youtube.com"
    override var name = "YouTube Playlists"
    override val supportedTypes = setOf(TvType.Others)
    override val hasMainPage = false
    override var lang = language

    private val parser = YouTubeParser()

    override suspend fun search(query: String): List<SearchResponse> {
        return parser.searchPlaylists(query).map {
            TvSeriesSearchResponse(
                name = it.name,
                url = it.url,
                apiName = this.name,
                type = TvType.Others,
                posterUrl = it.thumbnailUrl
            )
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val playlist = parser.getPlaylist(url)

        val episodes = playlist.videos.map {
            Episode(
                data = it.url,
                name = it.name,
                posterUrl = it.thumbnailUrl
            )
        }

        return TvSeriesLoadResponse(
            name = playlist.name,
            url = playlist.url,
            apiName = this.name,
            type = TvType.Others,
            episodes = episodes,
            posterUrl = playlist.thumbnailUrl
        )
    }

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
