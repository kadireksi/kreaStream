package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class TrtLive : MainAPI() {
    override var mainUrl = "https://www.trt.net.tr"
    override var name = "TRT Canlı"
    override val supportedTypes = setOf(TvType.Live)

    /** Only one section on main page: TRT Canlı */
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val list = listOf(
            newTvSeriesSearchResponse("TRT Canlı", "https://trt.net.tr/live", TvType.Live) {
                posterUrl = "https://upload.wikimedia.org/wikipedia/commons/7/70/Logo_of_TRT1.png"
                //plot = "TRT kanallarını canlı izleyin"
            }
        )
        return newHomePageResponse(
            listOf(HomePageList("TRT Canlı", list, isHorizontalImages = true))
        )
    }

    /** When user clicks “TRT Canlı” → list each channel as an episode */
    override suspend fun load(url: String): LoadResponse {
        val episodes = TrtUtils.liveChannels.map { (name, streamUrl, logoUrl) ->
            val nowPlaying = TrtUtils.getNowPlaying(name)
            newEpisode(streamUrl) {
                this.name = name
                this.posterUrl = logoUrl
                this.description = nowPlaying ?: "Canlı yayın akışı"
            }
        }

        return newTvSeriesLoadResponse("TRT Canlı", url, TvType.Live, episodes) {
            posterUrl = "https://upload.wikimedia.org/wikipedia/commons/7/70/Logo_of_TRT1.png"
            //plot = "TRT canlı yayınları"
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
