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
            // Use newMovieSearchResponse for live streams
            newMovieSearchResponse(name, streamUrl, TvType.Live) {
                this.posterUrl = logoUrl
            }
        }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun load(url: String): LoadResponse {
        val channelInfo = TrtUtils.liveChannels.find { it.second == url }
        val title = channelInfo?.first ?: "TRT Canlı"
        val poster = channelInfo?.third

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
        M3u8Helper.generateM3u8(
            name = "TRT Live",
            data,
            mainUrl,
            headers = mapOf("Referer" to mainUrl)
        ).forEach(callback)
        return true
    }
}