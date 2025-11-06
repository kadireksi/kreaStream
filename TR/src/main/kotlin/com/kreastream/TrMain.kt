package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

/**
 * Main Turkish plugin hub combining TRT, ATV, etc.
 * Currently includes TRT (series + live)
 */
class TrMain : MainAPI() {
    override var mainUrl = "https://www.trt1.com.tr"
    override var name = "Türkiye TV"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Live)

    private val trtParser by lazy { Trt1Parser() }
    private val trtLive by lazy { TrtLive() }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val allLists = mutableListOf<HomePageList>()

        // TRT sections
        trtParser.getMainPage(page, request)?.items?.let { allLists.addAll(it) }

        // TRT Canlı
        trtLive.getMainPage(page, request)?.items?.let { allLists.addAll(it) }

        // later we’ll add:
        // atvParser.getMainPage(...)
        // showTvParser.getMainPage(...)
        // etc.

        return newHomePageResponse(allLists)
    }

    override suspend fun load(url: String): LoadResponse {
        return when {
            url.contains("/live", true) -> trtLive.load(url)
            url.contains("trt1.com.tr", true) -> trtParser.load(url)
            else -> trtParser.load(url)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return if (data.endsWith(".m3u8")) {
            trtLive.loadLinks(data, isCasting, subtitleCallback, callback)
        } else {
            trtParser.loadLinks(data, isCasting, subtitleCallback, callback)
        }
    }
}
