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

    override val mainPage = mainPageOf(
        // TRT1 Sections - these will be handled by Trt1.kt
        "trt1_series" to "TRT 1 - Güncel Diziler",
        "trt1_archive" to "TRT 1 - Eski Diziler",
        
        // Live TV Sections - handled by TrtLive.kt
        "live_tv" to "TRT Canlı TV",
        "live_radio" to "TRT Canlı Radyo",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val items = when (request.data) {
            // TRT1 Series - delegate to Trt1.kt
            "trt1_series" -> getTrt1Section("https://www.trt1.com.tr/diziler?archive=false&order=title_asc", page)
            "trt1_archive" -> getTrt1Section("https://www.trt1.com.tr/diziler?archive=true&order=title_asc", page)
            
            // Live TV - delegate to TrtLive.kt
            "live_tv" -> getLiveTvChannels()
            "live_radio" -> getLiveRadioChannels()
            
            else -> emptyList()
        }

        return newHomePageResponse(
            listOf(
                HomePageList(
                    name = request.name,
                    list = items,
                    isHorizontalImages = request.data.startsWith("live_") // Horizontal for live, vertical for series
                )
            )
        )
    }

    private suspend fun getTrt1Section(url: String, page: Int): List<SearchResponse> {
        return try {
            // Use the actual TRT1 URL instead of the TurkTV URL
            trt1.getMainPage(page, MainPageRequest("", url, false))
                ?.items?.firstOrNull()?.list ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun getLiveTvChannels(): List<SearchResponse> {
        return try {
            trtLive.getMainPage(1, MainPageRequest("TV Kanalları", "tv", false))
                ?.items?.firstOrNull()?.list ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun getLiveRadioChannels(): List<SearchResponse> {
        return try {
            trtLive.getMainPage(1, MainPageRequest("Radyo Kanalları", "radio", false))
                ?.items?.firstOrNull()?.list ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        // Delegate to appropriate provider based on URL
        return when {
            url.contains("trt1.com.tr") -> trt1.load(url)
            url.contains("trt.net.tr") || url.contains(".m3u8") || url.contains(".aac") -> trtLive.load(url)
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
            else -> false
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        
        // Search across all providers
        try {
            results.addAll(trt1.search(query))
        } catch (e: Exception) {
            // Ignore search errors
        }
        
        try {
            results.addAll(trtLive.search(query))
        } catch (e: Exception) {
            // Ignore search errors
        }
        
        return results.distinctBy { it.url }
    }
}