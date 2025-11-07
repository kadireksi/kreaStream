package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class TrtLive : MainAPI() {
    override var mainUrl = "https://www.trt.net.tr"
    override var name = "TRT Canlı"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "tr"

    // Live channels data - embedded directly in the class
    private val liveChannels = listOf(
        LiveChannel("TRT 1", "https://tv-trt1.medya.trt.com.tr/master_720.m3u8", "https://upload.wikimedia.org/wikipedia/commons/7/70/Logo_of_TRT1.png"),
        LiveChannel("TRT 2", "https://tv-trt2.medya.trt.com.tr/master_720.m3u8", "https://upload.wikimedia.org/wikipedia/commons/7/70/Logo_of_TRT2.png"),
        LiveChannel("TRT Spor", "https://tv-trtspor1.medya.trt.com.tr/master_720.m3u8", "https://upload.wikimedia.org/wikipedia/commons/a/a4/TRT_Spor_logo.png"),
        LiveChannel("TRT Belgesel", "https://tv-trtbelgesel.medya.trt.com.tr/master_720.m3u8", "https://upload.wikimedia.org/wikipedia/commons/3/37/TRT_Belgesel_logo.png"),
        LiveChannel("TRT Çocuk", "https://tv-trtcocuk.medya.trt.com.tr/master_720.m3u8", "https://upload.wikimedia.org/wikipedia/commons/8/8f/TRT_%C3%87ocuk_logo.png"),
        LiveChannel("TRT Arabi", "https://tv-trtarabi.medya.trt.com.tr/master_720.m3u8", "https://upload.wikimedia.org/wikipedia/commons/8/8c/TRT_Arabi_logo.png"),
        LiveChannel("TRT Avaz", "https://tv-trtavaz.medya.trt.com.tr/master_720.m3u8", "https://upload.wikimedia.org/wikipedia/commons/0/0c/TRT_Avaz_logo.png"),
        LiveChannel("TRT Türk", "https://tv-trtturk.medya.trt.com.tr/master_720.m3u8", "https://upload.wikimedia.org/wikipedia/commons/3/3c/TRT_T%C3%BCrk_logo.png"),
        LiveChannel("TRT World", "https://tv-trtworld.medya.trt.com.tr/master_720.m3u8", "https://upload.wikimedia.org/wikipedia/commons/6/6a/TRT_World_logo.png")
    )

    override val mainPage = mainPageOf(
        "" to "TRT Canlı Yayınlar"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val items = liveChannels.map { channel ->
            newMovieSearchResponse(channel.name, channel.streamUrl, TvType.Live) {
                this.posterUrl = channel.logoUrl
            }
        }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun load(url: String): LoadResponse {
        val channel = liveChannels.find { it.streamUrl == url }
        
        return newMovieLoadResponse(
            title = channel?.name ?: "TRT Canlı",
            url = url,
            type = TvType.Live,
            data = url
        ) {
            this.posterUrl = channel?.logoUrl
            this.plot = "TRT canlı yayın"
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // For M3U8 streams, generate quality options
        M3u8Helper.generateM3u8(
            name = name,
            fileUrl = data,
            referer = mainUrl
        ).forEach(callback)
        
        return true
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return liveChannels
            .filter { it.name.contains(query, ignoreCase = true) }
            .map { channel ->
                newMovieSearchResponse(channel.name, channel.streamUrl, TvType.Live) {
                    this.posterUrl = channel.logoUrl
                }
            }
    }

    // Data class for channel information
    private data class LiveChannel(
        val name: String,
        val streamUrl: String,
        val logoUrl: String
    )
}