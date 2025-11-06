package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class TrtLive : TrMain() {
    override var mainUrl = "https://www.trt.net.tr"
    override var name = "TRT Canlı"
    override val supportedTypes = setOf(TvType.Live)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val channels = TrtUtils.liveChannels.map { (channelName, streamUrl, logoUrl) ->
            //val nowPlaying = TrtUtils.getNowPlaying(channelName)
            HomePageList(
                channelName,
                listOf(
                    newMovieSearchResponse(channelName, streamUrl, TvType.Live) {
                        this.posterUrl = logoUrl
                        //this.plot = nowPlaying ?: "Canlı yayın akışı"
                    }
                ),
                isHorizontalImages = true
            )
        }
        return newHomePageResponse(channels)
    }

    override suspend fun load(url: String): LoadResponse {
        val channel = TrtUtils.liveChannels.find { it.second == url }
        val name = channel?.first ?: "TRT Canlı"
        val logo = channel?.third

        return newMovieLoadResponse(name, url, TvType.Live, url) {
            this.posterUrl = logo
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        callback.invoke(
            newExtractorLink(
                name = "TRT",
                source = "TRT",
                url = data
            ){
                this.referer = mainUrl
                this.quality = Qualities.P480.value
                //this.isM3u8 = true
            }
        )
        return true
    }
}
