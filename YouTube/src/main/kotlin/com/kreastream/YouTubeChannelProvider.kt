package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.newEpisode
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.SubtitleFile

class YouTubeChannelProvider(language: String) : MainAPI() {

    override var mainUrl = "https://www.youtube.com"
    override var name = "YouTube Channels"
    override val supportedTypes = setOf(TvType.Others)
    override val hasMainPage = false
    override var lang = language

    private val parser = YouTubeParser()

    /* ---------------- SEARCH ---------------- */

    override suspend fun search(query: String): List<SearchResponse> {
        return parser.searchChannels(query).map {
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
        val channel = parser.getChannel(url)

        val episodes = channel.videos.map {
            newEpisode(it.url) {
                name = it.name
                posterUrl = it.thumbnailUrl
            }
        }

        return newTvSeriesLoadResponse(
            channel.name,
            channel.url,
            TvType.Others,
            episodes
        ) {
            posterUrl = channel.thumbnailUrl
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
