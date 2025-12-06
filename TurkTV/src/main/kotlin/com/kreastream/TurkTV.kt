package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import android.util.Log

class TurkTV : MainAPI() {

    override var name = "Türk TV"
    override var mainUrl = "https://www.atv.com.tr"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Live)

    private val channelsJsonUrl = "https://raw.githubusercontent.com/kadireksi/kreaStream/builds/channels.json"
    private val streamsJsonUrl = "https://raw.githubusercontent.com/kadireksi/kreaStream/builds/streams.json"

    // Remove the 'private' modifier so we can reset them
    var channels: List<ChannelConfig>? = null
    var streams: List<LiveStreamConfig>? = null

    // Add a timestamp to force refresh
    private var lastRefreshTime: Long = 0
    private val refreshInterval = 5 * 60 * 1000 // 5 minutes in milliseconds

    // Synthetic URLs for TV and Radio Hubs
    private val liveTvHubUrl = "https://turktv.local/live/tv"
    private val liveRadioHubUrl = "https://turktv.local/live/radio"

    // ------------------- DATA CLASSES -------------------
    data class SelectorBlock(
        val container: String,
        val title: String,
        val link: String,
        val url: String? = null,
        val poster: String? = null
    )

    data class StreamConfig(
        val type: String,
        val prefer: String,
        val referer: Boolean = false
    )

    data class ChannelConfig(
        val key: String,
        val name: String,
        val baseUrl: String,
        val active: Boolean = true,
        val series: SelectorBlock,
        val episodes: SelectorBlock,
        val stream: StreamConfig
    )

    data class LiveStreamConfig(
        val key: String,
        val title: String,
        val active: Boolean = true,
        val poster: String?,
        val url: String,
        val requiresReferer: Boolean = false,
        val streamType: String = "tv"
    )

    // ------------------- JSON LOADING WITH REFRESH -------------------
    private suspend fun ensureLoaded(forceRefresh: Boolean = false) {
        Log.d("TurkTV", "=== ensureLoaded() called, forceRefresh: $forceRefresh ===")
        
        val currentTime = System.currentTimeMillis()
        val shouldRefresh = forceRefresh || 
                           (lastRefreshTime == 0L) || 
                           (currentTime - lastRefreshTime > refreshInterval)
        
        if (shouldRefresh) {
            Log.d("TurkTV", "Forcing refresh of JSON data")
            channels = null
            streams = null
            lastRefreshTime = currentTime
        }
        
        if (channels == null) {
            channels = try {
                Log.d("TurkTV", "Loading channels.json...")
                val responseText = app.get(channelsJsonUrl).document.text()
                Log.d("TurkTV", "Response length: ${responseText.length}")
                
                if (responseText.isBlank()) {
                    Log.e("TurkTV", "Empty response")
                    emptyList()
                } else {
                    val parsed = parseJson<List<ChannelConfig>>(responseText)
                    Log.d("TurkTV", "Successfully parsed ${parsed?.size ?: 0} channels")
                    
                    val activeChannels = parsed?.filter { it.active } ?: emptyList()
                    activeChannels.forEachIndexed { i, ch ->
                        Log.d("TurkTV", "Active channel $i: ${ch.name} (${ch.key})")
                    }
                    
                    activeChannels
                }
            } catch (e: Exception) {
                Log.e("TurkTV", "Error loading channels: ${e.message}", e)
                emptyList()
            }
        }
        
        if (streams == null) {
            streams = try {
                Log.d("TurkTV", "Loading streams.json...")
                val responseText = app.get(streamsJsonUrl).document.text()
                Log.d("TurkTV", "Response length: ${responseText.length}")
                
                if (responseText.isBlank()) {
                    Log.e("TurkTV", "Empty response")
                    emptyList()
                } else {
                    val parsed = parseJson<List<LiveStreamConfig>>(responseText)
                    Log.d("TurkTV", "Successfully parsed ${parsed?.size ?: 0} streams")
                    
                    val activeStreams = parsed?.filter { it.active } ?: emptyList()
                    activeStreams.forEachIndexed { i, st ->
                        Log.d("TurkTV", "Active stream $i: ${st.title} (${st.streamType}) - URL: ${st.url.take(50)}...")
                    }
                    
                    activeStreams
                }
            } catch (e: Exception) {
                Log.e("TurkTV", "Error loading streams: ${e.message}", e)
                emptyList()
            }
        }
        
        Log.d("TurkTV", "=== ensureLoaded() completed ===")
        Log.d("TurkTV", "Active channels: ${channels?.size ?: 0}, Active streams: ${streams?.size ?: 0}")
    }

    // ------------------- MAIN PAGE WITH FORCE REFRESH -------------------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        Log.d("TurkTV", "=== getMainPage() called - page: $page ===")
        
        // Force refresh on first page load (when user opens the plugin)
        val forceRefresh = page == 1
        ensureLoaded(forceRefresh)

        val lists = mutableListOf<HomePageList>()
        
        // --- 1. LIVE STREAMS SECTION ---
        val liveItems = mutableListOf<SearchResponse>()
        
        // Live TV Item
        val tvStreams = streams?.filter { it.streamType == "tv" } ?: emptyList()
        liveItems += newTvSeriesSearchResponse(
            "📺 Canlı TV (${tvStreams.size})", 
            liveTvHubUrl, 
            TvType.TvSeries
        ).apply {
            posterUrl = "https://img.freepik.com/premium-vector/television-icon-logo-vector-design-template_827767-3402.jpg"
        }
        Log.d("TurkTV", "TV item shows ${tvStreams.size} channels")

        // Live Radio Item
        val radioStreams = streams?.filter { it.streamType == "radio" } ?: emptyList()
        liveItems += newTvSeriesSearchResponse(
            "📻 Radyo (${radioStreams.size})", 
            liveRadioHubUrl, 
            TvType.TvSeries
        ).apply {
            posterUrl = "https://img.freepik.com/premium-vector/retro-black-white-boombox_788759-25590.jpg"
        }
        Log.d("TurkTV", "Radio item shows ${radioStreams.size} channels")
        
        lists += HomePageList("🎬 Canlı Yayınlar", liveItems, true)
        
        // --- 2. SERIES SECTIONS ---
        if (channels != null && channels!!.isNotEmpty()) {
            Log.d("TurkTV", "Processing ${channels!!.size} channels")
            channels!!.forEach { cfg ->
                lists += HomePageList("📺 ${cfg.name} Diziler", listOf(
                    newTvSeriesSearchResponse("${cfg.name} Dizileri", cfg.series.url ?: "${cfg.baseUrl}/diziler", TvType.TvSeries) {
                        this.posterUrl = when (cfg.key) {
                            "atv" -> "https://iatv.tmgrup.com.tr/site/v2/i/atv-logo.png"
                            "showtv" -> "https://www.showtv.com.tr/assets/v4/images/common/logo/svg/show-tv-logo.svg"
                            "kanald" -> "https://static.kanald.com.tr/images/kanald-logo.svg"
                            "nowtv" -> "https://tpsbayxekmes.merlincdn.net//img/logos/logo.svg"
                            "trt1" -> "https://www.trt.net.tr/logos/our-logos/corporate/trt.png"
                            else -> "https://cdn-icons-png.flaticon.com/512/1828/1828666.png"
                        }
                    }
                ), true)
            }
        } else {
            Log.w("TurkTV", "No channels available, adding placeholder")
            lists += HomePageList("📺 Diziler", listOf(
                newTvSeriesSearchResponse("Kanal Yüklenemedi", "", TvType.TvSeries) {
                    this.posterUrl = "https://cdn-icons-png.flaticon.com/512/2748/2748558.png"
                }
            ), true)
        }

        return newHomePageResponse(lists)
    }

    // ------------------- SEARCH (Series List) -------------------
    override suspend fun search(query: String): List<SearchResponse> {
        Log.d("TurkTV", "=== search() called with query: $query ===")
        ensureLoaded()
        
        if (query.isEmpty()) return emptyList()
        
        val results = mutableListOf<SearchResponse>()
        
        channels?.forEach { channel ->
            try {
                Log.d("TurkTV", "Searching on ${channel.name} (${channel.baseUrl})")
                
                // Get the series page URL
                val seriesUrl = channel.series.url ?: "${channel.baseUrl}/diziler"
                Log.d("TurkTV", "Fetching series from: $seriesUrl")
                
                val doc = app.get(seriesUrl).document
                
                // Extract series using the configured selectors
                val seriesElements = doc.select(channel.series.container)
                Log.d("TurkTV", "Found ${seriesElements.size} series elements on ${channel.name}")
                
                seriesElements.forEach { element ->
                    val titleElement = element.selectFirst(channel.series.title)
                    val linkElement = element.selectFirst(channel.series.link)
                    val posterElement = element.selectFirst(channel.series.poster ?: "img")
                    
                    if (titleElement != null && linkElement != null) {
                        val title = titleElement.text().trim()
                        var href = linkElement.attr("href")
                        val poster = posterElement?.attr("src")
                        
                        // Check if the series title matches the query (case-insensitive)
                        if (title.contains(query, ignoreCase = true)) {
                            // Make URL absolute if needed
                            if (!href.startsWith("http")) {
                                href = full(channel.baseUrl, href) ?: href
                            }
                            
                            Log.d("TurkTV", "Found series: $title -> $href")
                            
                            results.add(newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                                this.posterUrl = poster
                            })
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("TurkTV", "Error searching on ${channel.name}: ${e.message}", e)
            }
        }
        
        Log.d("TurkTV", "Search returned ${results.size} results")
        return results
    }

    // ------------------- LOAD (Series Page) -------------------
    override suspend fun load(url: String): LoadResponse {
        Log.d("TurkTV", "=== load() called with URL: $url ===")
        ensureLoaded()
        
        // 1. Check for Live Hub URLs
        if (url == liveTvHubUrl) {
            Log.d("TurkTV", "Loading TV hub as episodes")
            
            val tvStreams = streams?.filter { it.streamType == "tv" } ?: emptyList()
            Log.d("TurkTV", "TV streams found: ${tvStreams.size}")
            
            // Create episodes for each TV channel
            val episodes = tvStreams.mapIndexed { index, stream ->
                newEpisode(stream.url) {
                    this.name = stream.title
                    this.posterUrl = stream.poster ?: "https://img.freepik.com/premium-vector/television-icon-logo-vector-design-template_827767-3402.jpg"
                    this.description = "Canlı TV yayını"
                    this.episode = index + 1
                    this.season = 1
                }
            }

            return newTvSeriesLoadResponse(
                name = "📺 Canlı TV Kanalları (${tvStreams.size})",
                url = url,
                type = TvType.TvSeries,
                episodes = episodes
            ) {
                posterUrl = "https://img.freepik.com/premium-vector/television-icon-logo-vector-design-template_827767-3402.jpg"
                this.plot = "Türk TV kanallarının canlı yayınları.\n\n" +
                           "• Kanallar arasında geçiş yapmak için sonraki/önceki bölüm butonlarını kullanın\n" +
                           "• Her kanal otomatik olarak sonraki kanala geçiş yapabilir\n" +
                           "• ${tvStreams.size} kanal bulunmaktadır"
            }
        }
        
        if (url == liveRadioHubUrl) {
            Log.d("TurkTV", "Loading Radio hub as episodes")
            
            val radioStreams = streams?.filter { it.streamType == "radio" } ?: emptyList()
            Log.d("TurkTV", "Radio streams found: ${radioStreams.size}")
            
            // Create episodes for each Radio channel
            val episodes = radioStreams.mapIndexed { index, stream ->
                newEpisode(stream.url) {
                    this.name = stream.title
                    this.posterUrl = stream.poster ?: "https://img.freepik.com/premium-vector/retro-black-white-boombox_788759-25590.jpg"
                    this.description = "Canlı radyo yayını"
                    this.episode = index + 1
                    this.season = 1
                }
            }

            return newTvSeriesLoadResponse(
                name = "📻 Radyo Kanalları (${radioStreams.size})",
                url = url,
                type = TvType.TvSeries,
                episodes = episodes
            ) {
                posterUrl = "https://img.freepik.com/premium-vector/retro-black-white-boombox_788759-25590.jpg"
                this.plot = "Türk radyo kanallarının canlı yayınları.\n\n" +
                           "• Kanallar arasında geçiş yapmak için sonraki/önceki bölüm butonlarını kullanın\n" +
                           "• Her kanal otomatik olarak sonraki kanala geçiş yapabilir\n" +
                           "• ${radioStreams.size} kanal bulunmaktadır\n" +
                           "• Bu kanallar sadece ses içermektedir"
            }
        }

        // 2. Find which channel this series belongs to
        val channel = channels?.find { url.contains(it.baseUrl) }
        if (channel == null) {
            Log.e("TurkTV", "No channel found for URL: $url")
            return newTvSeriesLoadResponse("Bulunamadı", url, TvType.TvSeries, emptyList()) {
                this.plot = "Bu kanal yapılandırılmamış"
                this.posterUrl = "https://cdn-icons-png.flaticon.com/512/2748/2748558.png"
            }
        }
        
        Log.d("TurkTV", "Found channel: ${channel.name} for URL: $url")
        
        try {
            // 3. Fetch the series page
            val doc = app.get(url).document
            
            // Extract series title and poster
            val title = doc.select("h1, h2, .title, .series-title").firstOrNull()?.text()?.trim()
                ?: url.substringAfterLast("/").replace("-", " ").replace("/", " ").trim().uppercase()
            
            val poster = doc.select("img[src*='295x410'], img[src*='295x123'], .poster img, picture img").firstOrNull()?.attr("src")
            
            Log.d("TurkTV", "Series title: $title, poster: $poster")
            
            // 4. Get episodes page URL
            val seriesPath = url.removePrefix(channel.baseUrl).trim('/')
            val episodesUrl = "${channel.baseUrl}/$seriesPath/bolumler"
            Log.d("TurkTV", "Fetching episodes from: $episodesUrl")
            
            val episodesDoc = app.get(episodesUrl).document
            
            // 5. Extract episodes using the configured selectors
            val episodes = mutableListOf<Episode>()
            
            // Try to find episodes from select dropdown
            val episodeSelect = episodesDoc.select(channel.episodes.container)
            Log.d("TurkTV", "Found episode select container: ${episodeSelect.size}")
            
            episodeSelect.select("option").forEach { option ->
                val value = option.attr("value")
                val text = option.text().trim()
                
                if (value.isNotBlank() && value != "bolum" && text.isNotBlank() && text != "Bölüm Seçiniz") {
                    // Create full episode URL
                    var episodeUrl = value
                    if (!episodeUrl.startsWith("http")) {
                        episodeUrl = full(channel.baseUrl, episodeUrl) ?: episodeUrl
                    }
                    
                    Log.d("TurkTV", "Found episode: $text -> $episodeUrl")
                    
                    // Extract episode number from text
                    val episodeNum = text.filter { it.isDigit() }.toIntOrNull() ?: 1
                    
                    episodes.add(
                        newEpisode(episodeUrl) {
                            this.name = text
                            this.episode = episodeNum
                            this.season = 1 // Default season
                            this.posterUrl = poster
                        }
                    )
                }
            }
            
            Log.d("TurkTV", "Total episodes found: ${episodes.size}")
            
            // Sort episodes by episode number (descending - newest first)
            val sortedEpisodes = episodes.sortedByDescending { it.episode }
            
            return newTvSeriesLoadResponse(
                name = title,
                url = url,
                type = TvType.TvSeries,
                episodes = sortedEpisodes
            ) {
                this.posterUrl = poster
                this.plot = "İzlemek için bir bölüm seçin."
            }
            
        } catch (e: Exception) {
            Log.e("TurkTV", "Error loading series: ${e.message}", e)
            return newTvSeriesLoadResponse("Hata", url, TvType.TvSeries, emptyList()) {
                this.plot = "Seri yüklenirken hata oluştu: ${e.message}"
                this.posterUrl = "https://cdn-icons-png.flaticon.com/512/2748/2748558.png"
            }
        }
    }

    // ------------------- LOAD LINKS -------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("TurkTV", "=== loadLinks() called with data: $data ===")
        ensureLoaded()

        // Individual live streams (when an episode is clicked)
        streams?.firstOrNull { it.url == data }?.let { live ->
            Log.d("TurkTV", "Found individual live stream: ${live.title}")
            Log.d("TurkTV", "Stream URL: ${live.url}")
            
            // Determine the correct link type
            val linkType = when {
                live.url.contains(".m3u8") -> ExtractorLinkType.M3U8
                live.url.contains(".aac") || live.url.contains(".mp3") || live.streamType == "radio" -> {
                    Log.d("TurkTV", "Detected audio stream, using MEDIA type")
                    ExtractorLinkType.VIDEO
                }
                else -> ExtractorLinkType.VIDEO
            }
            
            Log.d("TurkTV", "Using link type: $linkType")
            
            // Create appropriate headers based on stream type
            val headers = mutableMapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "Accept" to "*/*"
            )
            
            // Add referer only if required
            if (live.requiresReferer) {
                headers["Origin"] = mainUrl
                headers["Referer"] = mainUrl + "/"
            }
            
            // For AAC streams, we might need different headers
            if (live.url.contains(".aac")) {
                headers["Accept"] = "audio/*"
                headers["Accept-Encoding"] = "identity"
            }
            
            callback(
                newExtractorLink(
                    source = name,
                    name = live.title,
                    url = live.url
                ){
                    this.referer = if (live.requiresReferer) mainUrl else ""
                    this.quality = Qualities.Unknown.value
                    this.type = linkType
                    this.headers = headers
                }
            )
            Log.d("TurkTV", "loadLinks returning true for individual stream")
            return true
        }

        // Check if this is a series episode
        val channel = channels?.find { data.contains(it.baseUrl) }
        if (channel != null) {
            Log.d("TurkTV", "Found channel ${channel.name} for episode URL: $data")
            
            try {
                // Fetch the episode page
                val doc = app.get(data).document
                
                // Try to find the video source
                // Look for video tags, iframe, or script containing m3u8
                val videoSources = mutableListOf<String>()
                
                // Check video tags
                doc.select("video source").forEach { source ->
                    val src = source.attr("src")
                    if (src.isNotBlank() && (src.contains(".m3u8") || src.contains(".mp4"))) {
                        videoSources.add(src)
                    }
                }
                
                // Check iframe
                doc.select("iframe").forEach { iframe ->
                    val src = iframe.attr("src")
                    if (src.isNotBlank() && src.contains("youtube") || src.contains("vimeo")) {
                        videoSources.add(src)
                    }
                }
                
                // Look for m3u8 in script tags
                doc.select("script").forEach { script ->
                    val content = script.html()
                    val m3u8Match = Regex("(https?://[^\"' ]+\\.m3u8[^\"' ]*)").find(content)
                    m3u8Match?.let {
                        videoSources.add(it.value)
                    }
                }
                
                if (videoSources.isNotEmpty()) {
                    Log.d("TurkTV", "Found video sources: ${videoSources.size}")
                    
                    videoSources.forEachIndexed { index, videoUrl ->
                        val linkType = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        
                        val headers = mutableMapOf(
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                            "Accept" to "*/*"
                        )
                        
                        if (channel.stream.referer) {
                            headers["Origin"] = channel.baseUrl
                            headers["Referer"] = channel.baseUrl + "/"
                        }
                        
                        callback(
                            newExtractorLink(
                                source = name,
                                name = "${channel.name} Video ${index + 1}",
                                url = videoUrl
                            ){
                                this.referer = if (channel.stream.referer) channel.baseUrl else ""
                                this.quality = Qualities.Unknown.value
                                this.type = linkType
                                this.headers = headers
                            }
                        )
                    }
                    
                    Log.d("TurkTV", "loadLinks returning true for series episode")
                    return true
                } else {
                    Log.w("TurkTV", "No video sources found on episode page")
                }
            } catch (e: Exception) {
                Log.e("TurkTV", "Error loading episode links: ${e.message}", e)
            }
        }

        Log.d("TurkTV", "No match found, returning false")
        return false
    }

    // ------------------- HELPER -------------------
    private fun full(base: String, url: String?): String? = url?.let {
        if (it.startsWith("http")) it
        else {
            val baseUrl = base.removeSuffix("/")
            val trimmedUrl = it.trimStart('/')
            "$baseUrl/$trimmedUrl"
        }
    }
}
