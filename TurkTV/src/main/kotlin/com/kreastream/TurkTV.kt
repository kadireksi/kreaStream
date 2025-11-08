package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class TurkTV : MainAPI() {
    override var mainUrl = "https://turktv.com"
    override var name = "Türk TV"
    override var hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Live)
    override var lang = "tr"

    // Channel instances
    private val trt1 by lazy { Trt1() }
    private val trtLive by lazy { TrtLive() }
    //private val starTv by lazy { StarTv() }
    //private val atv by lazy { Atv() }
    //private val showTv by lazy { ShowTv() }

    override val mainPage = mainPageOf(
        // TRT1 Sections - these will be handled by Trt1.kt
        "trt1_series" to "TRT 1 - Güncel Diziler",
        "trt1_archive" to "TRT 1 - Eski Diziler",
        
        // Live TV Sections - handled by TrtLive.kt
        "live_tv" to "TRT Canlı TV",
        "live_radio" to "TRT Canlı Radyo",
        
        // Other Channels
        //"startv" to "Star TV",
        //"atv" to "ATV",
        //"showtv" to "Show TV"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val items = when (request.data) {
            // TRT1 Series - delegate to Trt1.kt
            "trt1_series" -> getTrt1Section("$mainUrl/diziler?archive=false&order=title_asc")
            "trt1_archive" -> getTrt1Section("$mainUrl/diziler?archive=true&order=title_asc")
            
            // Live TV - delegate to TrtLive.kt
            "live_tv" -> getLiveTvChannels()
            "live_radio" -> getLiveRadioChannels()
            
            // Other channels - delegate to respective parsers
            // "startv" -> getChannelMainPage(starTv)
            // "atv" -> getChannelMainPage(atv)
            // "showtv" -> getChannelMainPage(showTv)
            
            else -> emptyList()
        }

        return newHomePageResponse(request.name, items)
    }

    private suspend fun getTrt1Section(url: String): List<SearchResponse> {
        return trt1.getMainPage(1, MainPageRequest("", url))?.items?.firstOrNull()?.list ?: emptyList()
    }

    private suspend fun getChannelMainPage(channel: MainAPI): List<SearchResponse> {
        return channel.getMainPage(1, MainPageRequest("", ""))?.items?.firstOrNull()?.list ?: emptyList()
    }

    private suspend fun getLiveTvChannels(): List<SearchResponse> {
        return trtLive.getMainPage(1, MainPageRequest("TV Kanalları", "tv"))
            ?.items?.firstOrNull()?.list ?: emptyList()
    }

    private suspend fun getLiveRadioChannels(): List<SearchResponse> {
        return trtLive.getMainPage(1, MainPageRequest("Radyo Kanalları", "radio"))
            ?.items?.firstOrNull()?.list ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        // Delegate to appropriate provider based on URL
        return when {
            url.contains("trt1.com.tr") -> trt1.load(url)
            url.contains("trt.net.tr") || url.contains(".m3u8") || url.contains(".aac") -> trtLive.load(url)
            //url.contains("startv.com.tr") -> starTv.load(url)
            //url.contains("atv.com.tr") -> atv.load(url)
            //url.contains("showtv.com.tr") -> showTv.load(url)
            else -> throw ErrorLoadingException("No provider found for URL: $url")
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Delegate to appropriate provider
        return when {
            data.contains("trt1.com.tr") -> trt1.loadLinks(data, isCasting, subtitleCallback, callback)
            data.contains("trt.net.tr") || data.contains(".m3u8") || data.contains(".aac") -> 
                trtLive.loadLinks(data, isCasting, subtitleCallback, callback)
            //data.contains("startv.com.tr") -> starTv.loadLinks(data, isCasting, subtitleCallback, callback)
            //data.contains("atv.com.tr") -> atv.loadLinks(data, isCasting, subtitleCallback, callback)
            //data.contains("showtv.com.tr") -> showTv.loadLinks(data, isCasting, subtitleCallback, callback)
            else -> false
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        
        // Search across all providers
        results.addAll(trt1.search(query))
        results.addAll(trtLive.search(query))
        //results.addAll(starTv.search(query))
        //results.addAll(atv.search(query))
        //results.addAll(showTv.search(query))
        
        return results.distinctBy { it.url }
    }
}