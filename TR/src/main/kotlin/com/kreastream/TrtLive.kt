package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class TrtLive : MainAPI() {
    override var mainUrl = "https://www.trt.net.tr"
    override var name = "TRT Canlı"
    override val supportedTypes = setOf(TvType.Live)

    override val mainPage = mainPageOf(
        "" to "TRT Canlı Yayınlar"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val items = TrtUtils.liveChannels.map { (name, streamUrl, logoUrl) ->
            // Create search response for each live channel
            newMovieSearchResponse(name, streamUrl, TvType.Live) {
                this.posterUrl = logoUrl
            }
        }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun load(url: String): LoadResponse {
        // Find channel info
        val channel = TrtUtils.liveChannels.find { it.second == url }
        val title = channel?.first ?: "TRT Canlı"
        val poster = channel?.third

        // For live streams, we return a simple load response
        return newMovieLoadResponse(title, url, TvType.Live, url) {
            this.posterUrl = poster
            this.plot = "TRT canlı yayın"
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // For M3U8 streams, use M3u8Helper
        M3u8Helper.generateM3u8(
            name = "TRT Live",
            data,
            mainUrl,
            headers = mapOf("Referer" to mainUrl)
        ).forEach(callback)
        
        return true
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return TrtUtils.liveChannels
            .filter { it.first.contains(query, ignoreCase = true) }
            .map { (name, streamUrl, logoUrl) ->
                newMovieSearchResponse(name, streamUrl, TvType.Live) {
                    this.posterUrl = logoUrl
                }
            }
    }
}