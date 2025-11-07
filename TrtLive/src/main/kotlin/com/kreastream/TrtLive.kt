package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class TrtLive : MainAPI() {
    override var mainUrl = "https://www.trt.net.tr"
    override var name = "TRT Canlı"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "tr"

    private val channels = listOf(
        "TRT 1" to "https://tv-trt1.medya.trt.com.tr/master_720.m3u8",
        "TRT 2" to "https://tv-trt2.medya.trt.com.tr/master_720.m3u8",
        "TRT Spor" to "https://tv-trtspor1.medya.trt.com.tr/master_720.m3u8",
        "TRT Belgesel" to "https://tv-trtbelgesel.medya.trt.com.tr/master_720.m3u8"
    )

    override val mainPage = mainPageOf(
        "" to "TRT Canlı Yayınlar"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val items = channels.map { (name, url) ->
            newMovieSearchResponse(name, url, TvType.Live)
        }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun load(url: String): LoadResponse {
        val name = channels.find { it.second == url }?.first ?: "TRT Canlı"
        return newMovieLoadResponse(name, url, TvType.Live, url)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        callback(
            ExtractorLink(
                name,
                "TRT Live",
                data,
                mainUrl,
                Qualities.P720.value,
                true
            )
        )
        return true
    }
}