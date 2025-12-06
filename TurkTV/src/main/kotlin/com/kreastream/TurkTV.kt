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

    private val channelsJsonUrl =
        "https://raw.githubusercontent.com/kadireksi/kreaStream/builds/channels.json"
    private val streamsJsonUrl =
        "https://raw.githubusercontent.com/kadireksi/kreaStream/builds/streams.json"

    private var channels: List<ChannelConfig>? = null
    private var streams: List<LiveStreamConfig>? = null

    // Synthetic URLs for TV and Radio Hubs
    private val liveTvHubUrl = "turktv://live/tv"
    private val liveRadioHubUrl = "turktv://live/radio"

    // ------------------- DATA CLASSES -------------------
    data class SelectorBlock(
        val url: String,
        val container: String,
        val title: String,
        val link: String,
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
        val active: Boolean,
        val series: SelectorBlock,
        val episodes: SelectorBlock,
        val stream: StreamConfig
    )

    data class LiveStreamConfig(
        val key: String,
        val title: String,
        val active: Boolean,
        val poster: String?,
        val url: String,
        val requiresReferer: Boolean = false,
        val streamType: String = "tv"
    )

    // ------------------- JSON LOADING WITH DEBUG -------------------
    private suspend fun ensureLoaded() {
        Log.d("TurkTV", "ensureLoaded() called")
        
        if (channels == null) {
            try {
                Log.d("TurkTV", "Loading channels from: $channelsJsonUrl")
                val response = app.get(channelsJsonUrl)
                val channelsText = response.text
                Log.d("TurkTV", "Channels response status: ${response.statusCode}")
                Log.d("TurkTV", "Channels response length: ${channelsText.length}")
                
                if (channelsText.isNotBlank()) {
                    Log.d("TurkTV", "Parsing channels JSON...")
                    channels = parseJson<List<ChannelConfig>>(channelsText)?.filter { it.active }
                    Log.d("TurkTV", "Channels loaded: ${channels?.size ?: 0}")
                    channels?.forEachIndexed { index, channel ->
                        Log.d("TurkTV", "Channel $index: ${channel.name} (${channel.key}) - active: ${channel.active}")
                    }
                } else {
                    Log.e("TurkTV", "Channels response is blank!")
                    channels = emptyList()
                }
            } catch (e: Exception) {
                Log.e("TurkTV", "Error loading channels: ${e.message}")
                e.printStackTrace()
                channels = emptyList() 
            }
        } else {
            Log.d("TurkTV", "Channels already loaded: ${channels?.size ?: 0}")
        }
        
        if (streams == null) {
            try {
                Log.d("TurkTV", "Loading streams from: $streamsJsonUrl")
                val response = app.get(streamsJsonUrl)
                val streamsText = response.text
                Log.d("TurkTV", "Streams response status: ${response.statusCode}")
                Log.d("TurkTV", "Streams response length: ${streamsText.length}")
                
                if (streamsText.isNotBlank()) {
                    Log.d("TurkTV", "Parsing streams JSON...")
                    streams = parseJson<List<LiveStreamConfig>>(streamsText)?.filter { it.active }
                    Log.d("TurkTV", "Streams loaded: ${streams?.size ?: 0}")
                    streams?.forEachIndexed { index, stream ->
                        Log.d("TurkTV", "Stream $index: ${stream.title} (${stream.key}) - type: ${stream.streamType} - url: ${stream.url.take(50)}...")
                    }
                } else {
                    Log.e("TurkTV", "Streams response is blank!")
                    streams = emptyList()
                }
            } catch (e: Exception) {
                Log.e("TurkTV", "Error loading streams: ${e.message}")
                e.printStackTrace()
                streams = emptyList()
            }
        } else {
            Log.d("TurkTV", "Streams already loaded: ${streams?.size ?: 0}")
        }
        
        Log.d("TurkTV", "ensureLoaded() completed. Channels: ${channels?.size ?: 0}, Streams: ${streams?.size ?: 0}")
    }

    // ------------------- TEST FUNCTION TO DEBUG JSON -------------------
    suspend fun testJsonLoading(): String {
        val result = StringBuilder()
        result.appendLine("=== TurkTV JSON Loading Test ===")
        
        try {
            Log.d("TurkTV", "=== Starting JSON Test ===")
            
            // Test channels.json
            result.appendLine("\n--- Testing channels.json ---")
            val channelsResponse = app.get(channelsJsonUrl)
            result.appendLine("URL: $channelsJsonUrl")
            result.appendLine("Status: ${channelsResponse.statusCode}")
            result.appendLine("Response length: ${channelsResponse.text.length}")
            result.appendLine("First 500 chars: ${channelsResponse.text.take(500)}")
            
            if (channelsResponse.text.isNotBlank()) {
                try {
                    val testChannels = parseJson<List<ChannelConfig>>(channelsResponse.text)
                    result.appendLine("Parsed successfully: ${testChannels?.size ?: 0} channels")
                    testChannels?.take(3)?.forEachIndexed { i, ch ->
                        result.appendLine("  Channel $i: ${ch.name} (active: ${ch.active})")
                    }
                } catch (e: Exception) {
                    result.appendLine("Parse error: ${e.message}")
                }
            }
            
            // Test streams.json
            result.appendLine("\n--- Testing streams.json ---")
            val streamsResponse = app.get(streamsJsonUrl)
            result.appendLine("URL: $streamsJsonUrl")
            result.appendLine("Status: ${streamsResponse.statusCode}")
            result.appendLine("Response length: ${streamsResponse.text.length}")
            result.appendLine("First 500 chars: ${streamsResponse.text.take(500)}")
            
            if (streamsResponse.text.isNotBlank()) {
                try {
                    val testStreams = parseJson<List<LiveStreamConfig>>(streamsResponse.text)
                    result.appendLine("Parsed successfully: ${testStreams?.size ?: 0} streams")
                    testStreams?.take(3)?.forEachIndexed { i, st ->
                        result.appendLine("  Stream $i: ${st.title} (type: ${st.streamType})")
                    }
                } catch (e: Exception) {
                    result.appendLine("Parse error: ${e.message}")
                }
            }
            
        } catch (e: Exception) {
            result.appendLine("\n=== ERROR ===")
            result.appendLine("Exception: ${e.message}")
            e.printStackTrace()
        }
        
        Log.d("TurkTV", "Test result:\n$result")
        return result.toString()
    }

    // ------------------- MAIN PAGE -------------------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        Log.d("TurkTV", "getMainPage() called - page: $page, request: ${request.name}")
        ensureLoaded()

        val lists = mutableListOf<HomePageList>()
        
        // --- 1. LIVE STREAMS SECTION ---
        Log.d("TurkTV", "Creating live streams section")
        val liveItems = mutableListOf<SearchResponse>()
        
        // Live TV Item
        val tvItem = newMovieSearchResponse(
            "📺 Canlı TV", 
            liveTvHubUrl, 
            TvType.Live
        ).apply {
            posterUrl = "https://img.freepik.com/premium-vector/television-icon-logo-vector-design-template_827767-3402.jpg"
        }
        liveItems += tvItem
        Log.d("TurkTV", "Added TV item: ${tvItem.name} -> ${tvItem.url}")

        // Live Radio Item
        val radioItem = newMovieSearchResponse(
            "📻 Radyo", 
            liveRadioHubUrl, 
            TvType.Live
        ).apply {
            posterUrl = "https://img.freepik.com/premium-vector/retro-black-white-boombox_788759-25590.jpg"
        }
        liveItems += radioItem
        Log.d("TurkTV", "Added Radio item: ${radioItem.name} -> ${radioItem.url}")
        
        lists += HomePageList("🎬 Canlı Yayınlar", liveItems, true)
        Log.d("TurkTV", "Live section created with ${liveItems.size} items")
        
        // --- 2. SERIES SECTIONS ---
        channels?.let { channelList ->
            Log.d("TurkTV", "Processing ${channelList.size} channels")
            if (channelList.isNotEmpty()) {
                channelList.forEachIndexed { index, cfg ->
                    Log.d("TurkTV", "Processing channel $index: ${cfg.name}")
                    try {
                        val series = fetchSeries(cfg)
                        Log.d("TurkTV", "Channel ${cfg.name} has ${series.size} series")
                        if (series.isNotEmpty()) {
                            lists += HomePageList("📺 ${cfg.name} Diziler", series, true)
                        } else {
                            Log.d("TurkTV", "No series found for ${cfg.name}, adding placeholder")
                            lists += HomePageList("📺 ${cfg.name} Diziler", listOf(
                                newTvSeriesSearchResponse("Dizi bulunamadı", "", TvType.TvSeries) {
                                    this.posterUrl = "https://cdn-icons-png.flaticon.com/512/2748/2748558.png"
                                }
                            ), true)
                        }
                    } catch (e: Exception) {
                        Log.e("TurkTV", "Error processing channel ${cfg.name}: ${e.message}")
                        lists += HomePageList("📺 ${cfg.name} Diziler", listOf(
                            newTvSeriesSearchResponse("Yüklenemedi", "", TvType.TvSeries) {
                                this.posterUrl = "https://cdn-icons-png.flaticon.com/512/1828/1828666.png"
                            }
                        ), true)
                    }
                }
            } else {
                Log.w("TurkTV", "No channels loaded!")
                lists += HomePageList("📺 Diziler", listOf(
                    newTvSeriesSearchResponse("Kanal Yüklenemedi", "", TvType.TvSeries) {
                        this.posterUrl = "https://cdn-icons-png.flaticon.com/512/2748/2748558.png"
                    }
                ), true)
            }
        } ?: run {
            Log.e("TurkTV", "Channels is null!")
            lists += HomePageList("📺 Diziler", listOf(
                newTvSeriesSearchResponse("Kanal Yüklenemedi (null)", "", TvType.TvSeries) {
                    this.posterUrl = "https://cdn-icons-png.flaticon.com/512/2748/2748558.png"
                }
            ), true)
        }

        Log.d("TurkTV", "getMainPage() returning ${lists.size} lists")
        return newHomePageResponse(lists)
    }

    // ------------------- FETCH SERIES -------------------
    private suspend fun fetchSeries(cfg: ChannelConfig): List<SearchResponse> {
        val seriesList = mutableListOf<SearchResponse>()
        
        try {
            Log.d("TurkTV", "Fetching series from: ${cfg.series.url}")
            val doc = app.get(cfg.series.url).document
            val container = cfg.series.container
            val titleSelector = cfg.series.title
            val linkSelector = cfg.series.link
            val posterSelector = cfg.series.poster
            
            Log.d("TurkTV", "Selectors - container: $container, title: $titleSelector, link: $linkSelector")
            
            val elements = doc.select(container)
            Log.d("TurkTV", "Found ${elements.size} elements with selector: $container")
            
            elements.forEachIndexed { index, element ->
                try {
                    val title = element.select(titleSelector).text().trim()
                    val link = element.select(linkSelector).attr("href")
                    val poster = if (posterSelector != null) {
                        element.select(posterSelector).attr("src")
                    } else null
                    
                    Log.d("TurkTV", "Element $index - title: '$title', link: '$link', poster: ${poster?.take(30)}...")
                    
                    if (title.isNotBlank() && link.isNotBlank()) {
                        val fullLink = full(cfg.baseUrl, link)
                        if (fullLink != null) {
                            val series = newTvSeriesSearchResponse(title, fullLink, TvType.TvSeries) {
                                this.posterUrl = full(cfg.baseUrl, poster)
                                if (this.posterUrl.isNullOrBlank()) {
                                    this.posterUrl = "https://cdn-icons-png.flaticon.com/512/1828/1828666.png"
                                }
                            }
                            seriesList.add(series)
                            Log.d("TurkTV", "Added series: $title")
                        } else {
                            Log.d("TurkTV", "Could not create full link from: $link")
                        }
                    } else {
                        Log.d("TurkTV", "Skipping element - title or link blank")
                    }
                } catch (e: Exception) {
                    Log.e("TurkTV", "Error processing element $index: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e("TurkTV", "Error fetching series from ${cfg.name}: ${e.message}")
            e.printStackTrace()
        }
        
        Log.d("TurkTV", "Returning ${seriesList.size} series for ${cfg.name}")
        return seriesList
    }

    // ------------------- LOAD (Series Page) -------------------
    override suspend fun load(url: String): LoadResponse {
        Log.d("TurkTV", "load() called with URL: $url")
        ensureLoaded()
        
        // 1. Check for Live Hub URLs
        if (url == liveTvHubUrl || url == liveRadioHubUrl) {
            val isTv = url == liveTvHubUrl
            val typeToFilter = if (isTv) "tv" else "radio"
            val title = if (isTv) "📺 Canlı TV Kanalları" else "📻 Radyo Kanalları"
            
            Log.d("TurkTV", "Loading live hub: $title, filtering by type: $typeToFilter")
            
            val filteredStreams = streams
                ?.filter { it.streamType == typeToFilter }
                ?: emptyList()
            
            Log.d("TurkTV", "Found ${filteredStreams.size} streams for $typeToFilter")
            filteredStreams.forEachIndexed { i, s ->
                Log.d("TurkTV", "  Stream $i: ${s.title} - ${s.url.take(50)}...")
            }
            
            if (filteredStreams.isEmpty()) {
                Log.w("TurkTV", "No streams found for $typeToFilter")
                return newMovieLoadResponse(
                    name = title,
                    url = url,
                    type = TvType.Live,
                    data = url
                ) {
                    posterUrl = if (isTv) {
                        "https://img.freepik.com/premium-vector/television-icon-logo-vector-design-template_827767-3402.jpg"
                    } else {
                        "https://img.freepik.com/premium-vector/retro-black-white-boombox_788759-25590.jpg"
                    }
                    this.plot = "Henüz kanal yüklenmedi"
                }
            }
            
            // For live streams, we need to return a MovieLoadResponse
            Log.d("TurkTV", "Returning MovieLoadResponse for $title with ${filteredStreams.size} streams")
            return newMovieLoadResponse(
                name = title,
                url = url,
                type = TvType.Live,
                data = url  // Pass the URL as data for loadLinks
            ) {
                posterUrl = if (isTv) {
                    "https://img.freepik.com/premium-vector/television-icon-logo-vector-design-template_827767-3402.jpg"
                } else {
                    "https://img.freepik.com/premium-vector/retro-black-white-boombox_788759-25590.jpg"
                }
                this.plot = if (isTv) {
                    "Türk TV kanallarının canlı yayınları. Kanallar arasında geçiş yapmak için sağ/sol ok tuşlarını kullanın."
                } else {
                    "Türk radyo kanallarının canlı yayınları. Kanallar arasında geçiş yapmak için sağ/sol ok tuşlarını kullanın."
                }
            }
        }

        // 2. Original Series Logic
        Log.d("TurkTV", "Looking for channel matching URL: $url")
        val cfg = channels?.firstOrNull { url.contains(it.baseUrl, ignoreCase = true) }
        
        if (cfg == null) {
            Log.w("TurkTV", "No channel found for URL: $url")
            return newTvSeriesLoadResponse("Bulunamadı", url, TvType.TvSeries, emptyList()) {
                this.plot = "Bu kanal yapılandırılmamış"
                this.posterUrl = "https://cdn-icons-png.flaticon.com/512/2748/2748558.png"
            }
        }

        Log.d("TurkTV", "Found channel: ${cfg.name}, fetching episodes")
        val episodes = fetchEpisodes(cfg, url)
        Log.d("TurkTV", "Found ${episodes.size} episodes for ${cfg.name}")

        return newTvSeriesLoadResponse(cfg.name, url, TvType.TvSeries, episodes) {
            posterUrl = null
            plot = "${cfg.name} kanalına ait diziler"
        }
    }

    private suspend fun fetchEpisodes(cfg: ChannelConfig, url: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        try {
            Log.d("TurkTV", "Fetching episodes from: $url")
            val doc = app.get(url).document
            val ep = cfg.episodes

            Log.d("TurkTV", "Episode selectors - container: ${ep.container}, title: ${ep.title}, link: ${ep.link}")
            
            val elements = doc.select(ep.container)
            Log.d("TurkTV", "Found ${elements.size} episode elements")
            
            elements.forEachIndexed { index, el ->
                try {
                    val title = el.select(ep.title).text().ifBlank { "Bölüm ${index + 1}" }
                    val href = el.select(ep.link).attr("href")
                    
                    Log.d("TurkTV", "Episode $index - title: '$title', href: '$href'")
                    
                    val episodeUrl = full(cfg.baseUrl, href)
                    if (episodeUrl != null) {
                        episodes.add(
                            newEpisode(episodeUrl) {
                                this.name = title
                                this.episode = index + 1
                                this.season = 1
                            }
                        )
                        Log.d("TurkTV", "Added episode: $title")
                    } else {
                        Log.d("TurkTV", "Could not create full URL from: $href")
                    }
                } catch (e: Exception) {
                    Log.e("TurkTV", "Error processing episode $index: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e("TurkTV", "Error fetching episodes: ${e.message}")
            e.printStackTrace()
        }
        
        Log.d("TurkTV", "Returning ${episodes.size} episodes")
        return episodes
    }

    // ------------------- LOAD LINKS -------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("TurkTV", "loadLinks() called with data: $data")

        // Check if this is a live hub URL request
        if (data == liveTvHubUrl || data == liveRadioHubUrl) {
            val isTv = data == liveTvHubUrl
            val typeToFilter = if (isTv) "tv" else "radio"
            
            Log.d("TurkTV", "Processing live hub: $typeToFilter")
            
            val filteredStreams = streams
                ?.filter { it.streamType == typeToFilter }
                ?: emptyList()
            
            Log.d("TurkTV", "Found ${filteredStreams.size} streams for $typeToFilter")
            
            // For live hubs, we need to provide all streams as extractor links
            filteredStreams.forEachIndexed { index, live ->
                Log.d("TurkTV", "Creating extractor link $index: ${live.title}")
                callback(
                    newExtractorLink(
                        source = name,
                        name = live.title,
                        url = live.url
                    ){
                        this.referer = if (live.requiresReferer) mainUrl else ""
                        this.quality = Qualities.Unknown.value
                        this.type = if (live.url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        this.headers = mapOf(
                            "User-Agent" to "Mozilla/5.0",
                            "Accept" to "*/*",
                            "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
                            "Origin" to mainUrl,
                            "Referer" to mainUrl + "/"
                        )
                    }
                )
            }
            
            val success = filteredStreams.isNotEmpty()
            Log.d("TurkTV", "loadLinks for hub returning: $success")
            return success
        }

        // ── Individual live streams ──
        streams?.firstOrNull { it.url == data }?.let { live ->
            Log.d("TurkTV", "Found individual live stream: ${live.title}")
            callback(
                newExtractorLink(
                    source = name,
                    name = live.title,
                    url = live.url
                ){
                    this.referer = if (live.requiresReferer) mainUrl else ""
                    this.quality = Qualities.Unknown.value
                    this.type = if (live.url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    this.headers = mapOf(
                        "User-Agent" to "Mozilla/5.0",
                        "Accept" to "*/*",
                        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
                        "Origin" to mainUrl,
                        "Referer" to mainUrl + "/"
                    )
                }
            )
            Log.d("TurkTV", "loadLinks returning true for individual stream")
            return true
        }

        // ── Series episodes ──
        channels?.firstOrNull { data.contains(it.baseUrl, ignoreCase = true) }?.let { cfg ->
            Log.d("TurkTV", "Processing series episode from channel: ${cfg.name}")
            val doc = app.get(data).document

            var streamUrl: String? = doc.select("video source[src$='.m3u8'], source[type='application/x-mpegURL']")
                .attr("src")
                .takeIf { it.isNotBlank() }
                ?: doc.select("video source[src$='.mp4']").attr("src").takeIf { it.isNotBlank() }
                ?: Regex("""https?://[^\s"']+\.(?:m3u8|mp4)""").find(doc.html())?.value?.takeIf { it.isNotBlank() }
                ?: doc.select("iframe[src*='.m3u8'], iframe[src*='.mp4']").attr("src").takeIf { it.isNotBlank() }

            Log.d("TurkTV", "Found stream URL: ${streamUrl?.take(100)}...")
            
            if (!streamUrl.isNullOrBlank()) {
                val finalUrl = full(cfg.baseUrl, streamUrl) ?: streamUrl
                val linkType = if (finalUrl?.endsWith(".m3u8") == true) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                val quality = Qualities.Unknown.value

                if (finalUrl != null) {
                    Log.d("TurkTV", "Creating extractor link for series episode")
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "Stream",
                            url = finalUrl
                        ){
                            this.referer = if (cfg.stream.referer) cfg.baseUrl else mainUrl
                            this.quality = quality
                            this.type = linkType
                            if (cfg.stream.prefer.isNotBlank()) {
                                this.headers = mapOf(
                                    "Accept" to cfg.stream.prefer,
                                    "User-Agent" to "Mozilla/5.0",
                                    "Referer" to cfg.baseUrl
                                )
                            } else {
                                this.headers = mapOf(
                                    "User-Agent" to "Mozilla/5.0",
                                    "Referer" to cfg.baseUrl
                                )
                            }
                        }
                    )
                    Log.d("TurkTV", "loadLinks returning true for series episode")
                    return true
                }
            }
        }

        Log.d("TurkTV", "loadLinks returning false - no match found")
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
