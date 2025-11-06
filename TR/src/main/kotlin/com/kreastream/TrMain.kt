package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class TrMain : MainAPI() {
    override var mainUrl = "https://www.trt1.com.tr"
    override var name = "TR Plugin (Debug)"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Live)

    private val parser by lazy { Trt1Parser() }
    private val live by lazy { TrtLive() }

    init {
        println("✅ TR Plugin initialized")
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val allLists = mutableListOf<HomePageList>()

        try {
            parser.getMainPage(page, request)?.items?.let { allLists.addAll(it) }
        } catch (e: Exception) {
            println("⚠️ Trt1Parser failed: ${e.message}")
        }

        try {
            live.getMainPage(page, request)?.items?.let { allLists.addAll(it) }
        } catch (e: Exception) {
            println("⚠️ TrtLive failed: ${e.message}")
        }

        // Fallback so plugin is always visible even if parsing fails
        if (allLists.isEmpty()) {
            allLists.add(
                HomePageList(
                    "Test Section",
                    listOf(
                        newMovieSearchResponse("TR Plugin Debug", mainUrl, TvType.Live) {
                            posterUrl =
                                "https://upload.wikimedia.org/wikipedia/commons/7/70/Logo_of_TRT1.png"
                            //plot = "Plugin loaded but no data (debug placeholder)"
                        }
                    )
                )
            )
        }

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
