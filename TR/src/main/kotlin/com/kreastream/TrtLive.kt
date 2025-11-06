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

    /** Main page with live channels */
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val items = TrtUtils.liveChannels.map { (name, streamUrl, logoUrl) ->
            newMovieLoadResponse(name, streamUrl, TvType.Live, streamUrl) {
                this.posterUrl = logoUrl
            }
        }

        return newHomePageResponse(request.name, items)
    }

    /** Direct load for individual live streams */
    override suspend fun load(url: String): LoadResponse {
        // Find the channel info from the URL
        val channelInfo = TrtUtils.liveChannels.find { it.second == url }
        
        return newMovieLoadResponse(
            channelInfo?.first ?: "TRT Canlı",
            url,
            TvType.Live,
            url
        ) {
            this.posterUrl = channelInfo?.third
            this.plot = "TRT canlı yayın akışı"
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Use M3u8Helper to get quality options for live streams
        M3u8Helper.generateM3u8(
            name = "TRT Live",
            data,
            mainUrl,
            headers = mapOf("Referer" to mainUrl)
        ).forEach(callback)
        
        return true
    }

    /** Search functionality for live channels */
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