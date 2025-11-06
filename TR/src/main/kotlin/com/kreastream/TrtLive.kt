    package com.kreastream
    import com.lagradost.cloudstream3.*
    import com.lagradost.cloudstream3.utils.*

    class TrtLive : MainAPI() {
    override var mainUrl = "https://www.trt.net.tr"
    override var name = "TRT Canlı"
    override val supportedTypes = setOf(TvType.Live)


    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val liveList = listOf(
            newTvSeriesSearchResponse("TRT Canlı Yayınlar", "https://www.trt.net.tr/live", TvType.Live) {
            posterUrl = "https://upload.wikimedia.org/wikipedia/commons/7/70/Logo_of_TRT1.png"
            //plot = "TRT kanallarını canlı izleyin"
            }
        )
        return newHomePageResponse(listOf(HomePageList("TRT Canlı", liveList)))
    }


    override suspend fun load(url: String): LoadResponse {
        val episodes = TrtUtils.liveChannels.map { (channelName, streamUrl, logoUrl) ->
            Episode(
            data = streamUrl,
            name = channelName,
            posterUrl = logoUrl
            )
        }
        return newTvSeriesLoadResponse("TRT Canlı", url, TvType.Live, url) {
        this.episodes = episodes
        posterUrl = "https://upload.wikimedia.org/wikipedia/commons/7/70/Logo_of_TRT1.png"
        //plot = "TRT canlı yayın akışı"
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