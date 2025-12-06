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

    // ------------------- JSON LOADING -------------------
    private suspend fun ensureLoaded() {
        Log.d("TurkTV", "=== ensureLoaded() called ===")
        
        if (channels == null) {
            channels = try {
                Log.d("TurkTV", "Loading channels.json...")
                val responseText = app.get(channelsJsonUrl).text
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
                val responseText = app.get(streamsJsonUrl).text
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

    // ------------------- MAIN PAGE -------------------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        Log.d("TurkTV", "=== getMainPage() called ===")
        ensureLoaded()

        val lists = mutableListOf<HomePageList>()
        
        // --- 1. LIVE STREAMS SECTION ---
        val liveItems = mutableListOf<SearchResponse>()
        
        // Live TV Item
        val tvStreams = streams?.filter { it.streamType == "tv" } ?: emptyList()
        liveItems += newTvSeriesSearchResponse(
            "📺 Canlı TV", 
            liveTvHubUrl, 
            TvType.TvSeries
        ).apply {
            posterUrl = "https://img.freepik.com/premium-vector/television-icon-logo-vector-design-template_827767-3402.jpg"
        }
        Log.d("TurkTV", "TV item shows ${tvStreams.size} channels")

        // Live Radio Item
        val radioStreams = streams?.filter { it.streamType == "radio" } ?: emptyList()
        liveItems += newTvSeriesSearchResponse(
            "📻 Radyo", 
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
                    this.date = System.currentTimeMillis()
                    this.title = stream.title
                }
            }

            return newTvSeriesLoadResponse(
                name = "📺 Canlı TV Kanalları",
                url = url,
                type = TvType.TvSeries,
                episodes = episodes
            ) {
                posterUrl = "https://img.freepik.com/premium-vector/television-icon-logo-vector-design-template_827767-3402.jpg"
                this.plot = "Türk TV kanallarının canlı yayınları.\n\n" +
                           "• Kanallar arasında geçiş yapmak için sonraki/önceki bölüm butonlarını kullanın\n" +
                           "• Her kanal otomatik olarak sonraki kanala geçiş yapabilir\n" +
                           "• ${tvStreams.size} kanal bulunmaktadır"
                
                this.recommendations = tvStreams.map { stream ->
                    RecommendedResponse(
                        name = stream.title,
                        url = stream.url,
                        posterUrl = stream.poster
                    )
                }
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
                    this.date = System.currentTimeMillis()
                    this.title = stream.title
                    
                    // Add a marker that this is audio-only content
                    this.videoCodec = "AAC"
                    this.audioCodec = "AAC"
                }
            }

            return newTvSeriesLoadResponse(
                name = "📻 Radyo Kanalları",
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
                
                this.recommendations = radioStreams.map { stream ->
                    RecommendedResponse(
                        name = stream.title,
                        url = stream.url,
                        posterUrl = stream.poster
                    )
                }
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

        // Individual live streams (when an episode is clicked)
        streams?.firstOrNull { it.url == data }?.let { live ->
            Log.d("TurkTV", "Found individual live stream: ${live.title}")
            Log.d("TurkTV", "Stream URL: ${live.url}")
            
            // Determine the correct link type - FIXED for radio streams
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
                    
                    // For audio streams, we can add isAudio flag
                    if (live.streamType == "radio" || live.url.contains(".aac") || live.url.contains(".mp3")) {
                        this.isAudio = true
                    }
                }
            )
            Log.d("TurkTV", "loadLinks returning true for individual stream")
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
