package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

class TrMain : MainAPI() {
    override var mainUrl = "https://trt1.com.tr"
    override var name = "TR Plugin (Debug)"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasSearch = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Live)

    private val parser = Trt1Parser()
    private val live = TrtLive()

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val parserHome = parser.getMainPage(page, request)
        val liveHome = live.getMainPage(page, request)

        val allLists = mutableListOf<HomePageList>()

        parserHome?.items?.let { allLists.addAll(it) }
        liveHome?.items?.let { allLists.addAll(it) }

        return newHomePageResponse(allLists)
    }

    override suspend fun load(url: String): LoadResponse {
        return when {
            url.contains("trt1.com.tr", true) -> parser.load(url)
            url.endsWith(".m3u8") -> live.load(url)
            else -> parser.load(url)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return if (data.endsWith(".m3u8")) {
            live.loadLinks(data, isCasting, subtitleCallback, callback)
        } else {
            parser.loadLinks(data, isCasting, subtitleCallback, callback)
        }
    }
}
