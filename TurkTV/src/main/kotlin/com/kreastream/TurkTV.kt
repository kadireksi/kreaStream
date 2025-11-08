package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class TurkTV : MainAPI() {
    override var mainUrl = "https://www.turktv.com"
    override var name = "Türk TV"
    override var hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Live)
    override var lang = "tr"

    private val mainLogo = "https://raw.githubusercontent.com/kadireksi/kreaStream/refs/heads/logos/TV/trt.png"

    // Register all TV channel parsers
    private val channelParsers = listOf(
        Trt1(),
        // Add more channels here as you create them:
        // StarTv(),
        // Atv(),
        // ShowTv(),
    )

    // Register live TV provider
    private val trtLive by lazy { TrtLive() }

    override val mainPage = mainPageOf(
        // Series sections from various channels
        "trt1_series" to "TRT1 - Güncel Diziler",
        "trt1_archive" to "TRT1 - Eski Diziler",
        // Add more channel sections:
        "startv" to "Star TV Diziler",
        "atv" to "ATV Diziler", 
        "showtv" to "Show TV Diziler",
        
        // Live TV sections
        "live_tv" to "Canlı TV Kanalları",
        "live_radio" to "Canlı Radyo Kanalları"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val items = when (request.data) {
            // TRT1 Series
            "trt1_series" -> loadSeriesFromUrl("https://www.trt1.com.tr/diziler?archive=false&order=title_asc")
            "trt1_archive" -> loadSeriesFromUrl("https://www.trt1.com.tr/diziler?archive=true&order=title_asc")
            
            // Other TV channels (add as you implement them)
            "startv" -> getChannelMainPage("Star TV")
            "atv" -> getChannelMainPage("ATV")
            "showtv" -> getChannelMainPage("Show TV")
            
            // Live TV
            "live_tv" -> getLiveTvChannels()
            "live_radio" -> getLiveRadioChannels()
            
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

    private suspend fun loadSeriesFromUrl(url: String): List<SearchResponse> {
        return try {
            val document = app.get(url).document
            document.select("div.grid_grid-wrapper__elAnh > div.h-full.w-full > a").mapNotNull { element ->
                val title = element.selectFirst("div.card_card-title__IJ9af")?.text()?.trim() ?: return@mapNotNull null
                val href = element.attr("href")
                var posterUrl = element.selectFirst("img")?.attr("src")
                
                // Fix poster URL
                posterUrl = posterUrl?.replace("webp/w800/h450", "webp/w400/h600")?.replace("/q75/", "/q85/")
                
                val fullUrl = if (href.startsWith("http")) href else "https://www.trt1.com.tr$href"
                
                newTvSeriesSearchResponse(title, fullUrl) {
                    this.posterUrl = posterUrl
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun getChannelMainPage(channelName: String): List<SearchResponse> {
        val parser = channelParsers.find { it.name == channelName }
        return parser?.getMainPage(1, MainPageRequest(channelName, ""))?.items?.firstOrNull()?.list ?: emptyList()
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
        // First check if it's a live channel (handled by TrtLive)
        if (url.contains("trt.net.tr") || url.contains(".m3u8") || url.contains(".aac")) {
            return trtLive.load(url)
        }

        // Then check TV channel parsers
        for (parser in channelParsers) {
            if (url.contains(parser.mainUrl)) {
                return parser.load(url)
            }
        }

        // Fallback - try TrtLive as last resort
        return trtLive.load(url)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Delegate to appropriate parser based on URL
        return when {
            data.contains("trt.net.tr") || data.contains(".m3u8") || data.contains(".aac") -> {
                trtLive.loadLinks(data, isCasting, subtitleCallback, callback)
            }
            data.contains("trt1.com.tr") -> {
                // For TRT1 series, use Trt1 parser
                val trt1Parser = channelParsers.find { it is Trt1 } as? Trt1
                trt1Parser?.loadLinks(data, isCasting, subtitleCallback, callback) ?: false
            }
            else -> false
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        
        // Search in live channels
        results.addAll(trtLive.search(query))
        
        // Search in TV channel parsers
        channelParsers.forEach { parser ->
            results.addAll(parser.search(query))
        }
        
        return results.distinctBy { it.url }
    }
}