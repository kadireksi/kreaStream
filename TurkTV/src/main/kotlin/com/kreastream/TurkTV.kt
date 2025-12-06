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

    private var channels: List<ChannelConfig>? = null
    private var streams: List<LiveStreamConfig>? = null

    // Synthetic URLs for TV and Radio Hubs
    private val liveTvHubUrl = "https://turktv.local/live/tv"
    private val liveRadioHubUrl = "https://turktv.local/live/radio"

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

    // ------------------- JSON LOADING WITH PROPER ERROR HANDLING -------------------
    private suspend fun ensureLoaded() {
        Log.d("TurkTV", "=== ensureLoaded() called ===")
        
        if (channels == null) {
            channels = try {
                Log.d("TurkTV", "Attempting to load channels.json from: $channelsJsonUrl")
                val responseText = app.get(channelsJsonUrl).text
                Log.d("TurkTV", "Response length: ${responseText.length}")
                
                if (responseText.length < 10) {
                    Log.e("TurkTV", "Response too short - might be empty or error page")
                    emptyList()
                } else {
                    Log.d("TurkTV", "First 200 chars of response: ${responseText.take(200)}")
                    
                    val parsed = parseJson<List<ChannelConfig>>(responseText)
                    Log.d("TurkTV", "Parsed ${parsed?.size ?: 0} channels")
                    
                    // Filter active channels
                    val activeChannels = parsed?.filter { it.active } ?: emptyList()
                    Log.d("TurkTV", "Active channels: ${activeChannels.size}")
                    
                    activeChannels
                }
            } catch (e: Exception) {
                Log.e("TurkTV", "EXCEPTION loading channels.json: ${e.message}", e)
                emptyList()
            }
        }
        
        if (streams == null) {
            streams = try {
                Log.d("TurkTV", "Attempting to load streams.json from: $streamsJsonUrl")
                val responseText = app.get(streamsJsonUrl).text
                Log.d("TurkTV", "Response length: ${responseText.length}")
                
                if (responseText.length < 10) {
                    Log.e("TurkTV", "Response too short - might be empty or error page")
                    emptyList()
                } else {
                    Log.d("TurkTV", "First 200 chars of response: ${responseText.take(200)}")
                    
                    val parsed = parseJson<List<LiveStreamConfig>>(responseText)
                    Log.d("TurkTV", "Parsed ${parsed?.size ?: 0} streams")
                    
                    // Filter active streams
                    val activeStreams = parsed?.filter { it.active } ?: emptyList()
                    Log.d("TurkTV", "Active streams: ${activeStreams.size}")
                    
                    activeStreams.forEachIndexed { i, stream ->
                        Log.d("TurkTV", "Stream $i: ${stream.title} (type: ${stream.streamType})")
                    }
                    
                    activeStreams
                }
            } catch (e: Exception) {
                Log.e("TurkTV", "EXCEPTION loading streams.json: ${e.message}", e)
                emptyList()
            }
        }
        
        Log.d("TurkTV", "=== ensureLoaded() completed ===")
        Log.d("TurkTV", "Channels: ${channels?.size ?: 0}, Streams: ${streams?.size ?: 0}")
    }

    // ------------------- MAIN PAGE -------------------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        Log.d("TurkTV", "=== getMainPage() called ===")
        ensureLoaded()

        val lists = mutableListOf<HomePageList>()
        
        // --- 1. LIVE STREAMS SECTION ---
        val liveItems = mutableListOf<SearchResponse>()
        
        // Live TV Item
        liveItems += newMovieSearchResponse(
            "📺 Canlı TV", 
            liveTvHubUrl, 
            TvType.Live
        ).apply {
            posterUrl = "https://img.freepik.com/premium-vector/television-icon-logo-vector-design-template_827767-3402.jpg"
        }

        // Live Radio Item
        liveItems += newMovieSearchResponse(
            "📻 Radyo", 
            liveRadioHubUrl, 
            TvType.Live
        ).apply {
            posterUrl = "https://img.freepik.com/premium-vector/retro-black-white-boombox_788759-25590.jpg"
        }
        
        lists += HomePageList("🎬 Canlı Yayınlar", liveItems, true)
        
        // --- 2. SERIES SECTIONS ---
        if (channels != null && channels!!.isNotEmpty()) {
            Log.d("TurkTV", "Processing ${channels!!.size} channels")
            channels!!.forEach { cfg ->
                lists += HomePageList("📺 ${cfg.name} Diziler", listOf(
                    newTvSeriesSearchResponse("${cfg.name} Dizileri", "${cfg.baseUrl}/diziler", TvType.TvSeries) {
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

    // ------------------- LOAD (Series Page) -------------------
    override suspend fun load(url: String): LoadResponse {
        Log.d("TurkTV", "=== load() called with URL: $url ===")
        
        // 1. Check for Live Hub URLs
        if (url == liveTvHubUrl) {
            Log.d("TurkTV", "Loading TV hub")
            ensureLoaded()
            
            val tvStreams = streams?.filter { it.streamType == "tv" } ?: emptyList()
            Log.d("TurkTV", "TV streams found: ${tvStreams.size}")
            
            return newMovieLoadResponse(
                name = "📺 Canlı TV Kanalları",
                url = url,
                type = TvType.Live,
                data = url
            ) {
                posterUrl = "https://img.freepik.com/premium-vector/television-icon-logo-vector-design-template_827767-3402.jpg"
                this.plot = "Türk TV kanallarının canlı yayınları. ${tvStreams.size} kanal bulundu."
            }
        }
        
        if (url == liveRadioHubUrl) {
            Log.d("TurkTV", "Loading Radio hub")
            ensureLoaded()
            
            val radioStreams = streams?.filter { it.streamType == "radio" } ?: emptyList()
            Log.d("TurkTV", "Radio streams found: ${radioStreams.size}")
            
            return newMovieLoadResponse(
                name = "📻 Radyo Kanalları",
                url = url,
                type = TvType.Live,
                data = url
            ) {
                posterUrl = "https://img.freepik.com/premium-vector/retro-black-white-boombox_788759-25590.jpg"
                this.plot = "Türk radyo kanallarının canlı yayınları. ${radioStreams.size} kanal bulundu."
            }
        }

        // 2. Original Series Logic
        return newTvSeriesLoadResponse("Bulunamadı", url, TvType.TvSeries, emptyList()) {
            this.plot = "Bu kanal yapılandırılmamış"
            this.posterUrl = "https://cdn-icons-png.flaticon.com/512/2748/2748558.png"
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

        // Check if this is a live hub URL request
        if (data == liveTvHubUrl || data == liveRadioHubUrl) {
            val isTv = data == liveTvHubUrl
            val typeToFilter = if (isTv) "tv" else "radio"
            
            Log.d("TurkTV", "Processing live hub: $typeToFilter")
            
            val filteredStreams = streams
                ?.filter { it.streamType == typeToFilter }
                ?: emptyList()
            
            Log.d("TurkTV", "Found ${filteredStreams.size} streams for $typeToFilter")
            
            // For live hubs, provide all streams as extractor links
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

        // Individual live streams
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
            return true
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
