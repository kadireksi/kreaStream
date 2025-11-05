package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class TrtLive : MainAPI() {
    override var mainUrl = "https://www.trt.net.tr"
    override var name = "TRT Canlı"
    override val supportedTypes = setOf(TvType.Live)

    override suspend fun getMainPage(): HomePageResponse {
        val channels = TrtUtils.liveChannels.map { (name, url, logo) ->
            val now = TrtUtils.getNowPlaying(name)
            HomePageList(
                name,
                listOf(
                    newMovieSearchResponse(name, url, TvType.Live) {
                        this.posterUrl = logo
                        this.plot = now ?: "Canlı yayın akışı"
                    }
                ),
                isHorizontalImages = true
            )
        }
        return newHomePageResponse(channels)
    }

    override suspend fun load(url: String): LoadResponse {
        val name = TrtUtils.liveChannels.find { it.second == url }?.first ?: "TRT Canlı"
        return newMovieLoadResponse(name, url, TvType.Live, url) {
            this.posterUrl = TrtUtils.liveChannels.find { it.second == url }?.third
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
                url = data,
                referer = mainUrl,
                quality = Qualities.P720.value,
                isM3u8 = true
            )
        )
        return true
    }
}
