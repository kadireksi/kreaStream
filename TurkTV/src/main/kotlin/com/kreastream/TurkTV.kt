package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class TurkTV : MainAPI() {
    override var mainUrl = "https://turktv.com"
    override var name = "Türk TV"
    override var hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Live)
    override var lang = "tr"

    // All available TV channels
    private val allChannels = listOf<BaseTvChannel>(
        Trt1(),
        TrtLive(),
        StarTv(),
        Atv(),
        ShowTv()
    )

    override val mainPage = mainPageOf(
        // TRT1 Sections
        "trt1_series" to "TRT1 - Güncel Diziler",
        "trt1_archive" to "TRT1 - Eski Diziler",
        
        // Live TV Sections
        "live_tv" to "TRT Canlı TV",
        "live_radio" to "TRT Canlı Radyo",
        
        // Other Channels (will be populated when implemented)
        "startv" to "Star TV",
        "atv" to "ATV",
        "showtv" to "Show TV"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val items = when (request.data) {
            // TRT1 Series
            "trt1_series" -> getChannelSection(Trt1::class, "https://www.trt1.com.tr/diziler?archive=false&order=title_asc")
            "trt1_archive" -> getChannelSection(Trt1::class, "https://www.trt1.com.tr/diziler?archive=true&order=title_asc")
            
            // Live TV
            "live_tv" -> getChannelSection(TrtLive::class, "tv")
            "live_radio" -> getChannelSection(TrtLive::class, "radio")
            
            // Other channels
            "startv" -> getChannelMainPage(StarTv::class)
            "atv" -> getChannelMainPage(Atv::class)
            "showtv" -> getChannelMainPage(ShowTv::class)
            
            else -> emptyList()
        }

        return newHomePageResponse(
            listOf(
                HomePageList(
                    name = request.name,
                    list = items,
                    isHorizontalImages = true
                )
            )
        )
    }

    private suspend fun <T : BaseTvChannel> getChannelSection(channelClass: Class<T>, section: String): List<SearchResponse> {
        val channel = allChannels.find { it::class.java == channelClass }
        return channel?.getMainPage(1, MainPageRequest("", section))?.items?.firstOrNull()?.list ?: emptyList()
    }

    private suspend fun <T : BaseTvChannel> getChannelMainPage(channelClass: Class<T>): List<SearchResponse> {
        val channel = allChannels.find { it::class.java == channelClass }
        return channel?.getMainPage(1, MainPageRequest("", ""))?.items?.firstOrNull()?.list ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        // Find the appropriate channel to handle this URL
        val channel = allChannels.find { url.contains(it.mainUrl) }
        return channel?.load(url) ?: throw ErrorLoadingException("No channel found for URL: $url")
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Delegate to the appropriate channel
        val channel = allChannels.find { data.contains(it.mainUrl) }
        return channel?.loadLinks(data, isCasting, subtitleCallback, callback) ?: false
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        
        // Search across all channels
        allChannels.forEach { channel ->
            results.addAll(channel.search(query))
        }
        
        return results.distinctBy { it.url }
    }
}