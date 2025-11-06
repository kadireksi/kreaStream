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
            newMovieSearchResponse(name, streamUrl, TvType.Live) {
                this.posterUrl = logoUrl
            }
        }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun load(url: String): LoadResponse {
        val channel = TrtUtils.liveChannels.find { it.second == url }
        
        return newMovieLoadResponse(
            channel?.first ?: "TRT Canlı", 
            url, 
            TvType.Live, 
            url
        ) {
            this.posterUrl = channel?.third
            this.plot = "TRT canlı yayın"
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
                this.quality = Qualities.P720.value
                //this.isM3u8 = true
            }
        )
        return true
    }
}