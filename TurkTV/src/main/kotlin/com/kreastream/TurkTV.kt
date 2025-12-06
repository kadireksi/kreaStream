package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

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

    // ------------------- JSON LOADING -------------------
    private suspend fun ensureLoaded() {
        if (channels == null) {
            try {
                val channelsText = app.get(channelsJsonUrl).text
                if (channelsText.isNotBlank()) {
                    channels = parseJson<List<ChannelConfig>>(channelsText)?.filter { it.active }
                } else {
                    channels = emptyList()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                channels = emptyList() 
            }
        }
        if (streams == null) {
            try {
                val streamsText = app.get(streamsJsonUrl).text
                if (streamsText.isNotBlank()) {
                    streams = parseJson<List<LiveStreamConfig>>(streamsText)?.filter { it.active }
                } else {
                    streams = emptyList()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                streams = emptyList()
            }
        }
    }

    // ------------------- MAIN PAGE -------------------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        ensureLoaded()

        val lists = mutableListOf<HomePageList>()
        
        // --- 1. LIVE STREAMS SECTION ---
        val liveItems = mutableListOf<SearchResponse>()
        
        // Live TV Item - Use TvType.Live for live content
        liveItems += newMovieSearchResponse(
            "📺 Canlı TV", 
            liveTvHubUrl, 
            TvType.Live  // Use TvType.Live for live streams
        ).apply {
            posterUrl = "https://img.freepik.com/premium-vector/television-icon-logo-vector-design-template_827767-3402.jpg"
        }

        // Live Radio Item - Use TvType.Live for live content
        liveItems += newMovieSearchResponse(
            "📻 Radyo", 
            liveRadioHubUrl, 
            TvType.Live  // Use TvType.Live for live streams
        ).apply {
            posterUrl = "https://img.freepik.com/premium-vector/retro-black-white-boombox_788759-25590.jpg"
        }
        
        lists += HomePageList("🎬 Canlı Yayınlar", liveItems, true)
        
        // --- 2. SERIES SECTIONS ---
        channels?.let { channelList ->
            if (channelList.isNotEmpty()) {
                channelList.forEach { cfg ->
                    try {
                        val series = fetchSeries(cfg)
                        if (series.isNotEmpty()) {
                            lists += HomePageList("📺 ${cfg.name} Diziler", series, true)
                        } else {
                            // Add placeholder if no series found
                            lists += HomePageList("📺 ${cfg.name} Diziler", listOf(
                                newTvSeriesSearchResponse("Dizi bulunamadı", "", TvType.TvSeries) {
                                    this.posterUrl = "https://cdn-icons-png.flaticon.com/512/2748/2748558.png"
                                }
                            ), true)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        lists += HomePageList("📺 ${cfg.name} Diziler", listOf(
                            newTvSeriesSearchResponse("Yüklenemedi", "", TvType.TvSeries) {
                                this.posterUrl = "https://cdn-icons-png.flaticon.com/512/1828/1828666.png"
                            }
                        ), true)
                    }
                }
            } else {
                lists += HomePageList("📺 Diziler", listOf(
                    newTvSeriesSearchResponse("Kanal Yüklenemedi", "", TvType.TvSeries) {
                        this.posterUrl = "https://cdn-icons-png.flaticon.com/512/2748/2748558.png"
                    }
                ), true)
            }
        }

        return newHomePageResponse(lists)
    }

    // ------------------- FETCH SERIES -------------------
    private suspend fun fetchSeries(cfg: ChannelConfig): List<SearchResponse> {
        val seriesList = mutableListOf<SearchResponse>()
        
        try {
            val doc = app.get(cfg.series.url).document
            val container = cfg.series.container
            val titleSelector = cfg.series.title
            val linkSelector = cfg.series.link
            val posterSelector = cfg.series.poster
            
            doc.select(container).forEach { element ->
                try {
                    val title = element.select(titleSelector).text().trim()
                    val link = element.select(linkSelector).attr("href")
                    val poster = if (posterSelector != null) {
                        element.select(posterSelector).attr("src")
                    } else null
                    
                    if (title.isNotBlank() && link.isNotBlank()) {
                        val fullLink = full(cfg.baseUrl, link)
                        if (fullLink != null) {
                            seriesList.add(
                                newTvSeriesSearchResponse(title, fullLink, TvType.TvSeries) {
                                    this.posterUrl = full(cfg.baseUrl, poster)
                                    if (this.posterUrl.isNullOrBlank()) {
                                        this.posterUrl = "https://cdn-icons-png.flaticon.com/512/1828/1828666.png"
                                    }
                                }
                            )
                        }
                    }
                } catch (e: Exception) {
                    // Skip this element
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        return seriesList
    }

    // ------------------- LOAD (Series Page) -------------------
    override suspend fun load(url: String): LoadResponse {
        ensureLoaded()
        
        // 1. Check for Live Hub URLs
        if (url == liveTvHubUrl || url == liveRadioHubUrl) {
            val isTv = url == liveTvHubUrl
            val typeToFilter = if (isTv) "tv" else "radio"
            val title = if (isTv) "📺 Canlı TV Kanalları" else "📻 Radyo Kanalları"
            
            val filteredStreams = streams
                ?.filter { it.streamType == typeToFilter }
                ?: emptyList()
            
            if (filteredStreams.isEmpty()) {
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
            
            // For live streams, we need to return a MovieLoadResponse, not TvSeries
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
        val cfg = channels?.firstOrNull { url.contains(it.baseUrl, ignoreCase = true) }
            ?: return newTvSeriesLoadResponse("Bulunamadı", url, TvType.TvSeries, emptyList()) {
                this.plot = "Bu kanal yapılandırılmamış"
                this.posterUrl = "https://cdn-icons-png.flaticon.com/512/2748/2748558.png"
            }

        val episodes = fetchEpisodes(cfg, url)

        return newTvSeriesLoadResponse(cfg.name, url, TvType.TvSeries, episodes) {
            posterUrl = null
            plot = "${cfg.name} kanalına ait diziler"
        }
    }

    private suspend fun fetchEpisodes(cfg: ChannelConfig, url: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        try {
            val doc = app.get(url).document
            val ep = cfg.episodes

            doc.select(ep.container).forEachIndexed { index, el ->
                try {
                    val title = el.select(ep.title).text().ifBlank { "Bölüm ${index + 1}" }
                    val href = el.select(ep.link).attr("href")
                    val episodeUrl = full(cfg.baseUrl, href) ?: return@forEachIndexed

                    episodes.add(
                        newEpisode(episodeUrl) {
                            this.name = title
                            this.episode = index + 1
                            this.season = 1
                        }
                    )
                } catch (e: Exception) {
                    // Skip this episode
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        return episodes
    }

    // ------------------- LOAD LINKS -------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        // Check if this is a live hub URL request
        if (data == liveTvHubUrl || data == liveRadioHubUrl) {
            val isTv = data == liveTvHubUrl
            val typeToFilter = if (isTv) "tv" else "radio"
            
            val filteredStreams = streams
                ?.filter { it.streamType == typeToFilter }
                ?: emptyList()
            
            // For live hubs, we need to provide all streams as extractor links
            filteredStreams.forEach { live ->
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
            
            return filteredStreams.isNotEmpty()
        }

        // ── Individual live streams ──
        streams?.firstOrNull { it.url == data }?.let { live ->
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

        // ── Series episodes ──
        channels?.firstOrNull { data.contains(it.baseUrl, ignoreCase = true) }?.let { cfg ->
            val doc = app.get(data).document

            var streamUrl: String? = doc.select("video source[src$='.m3u8'], source[type='application/x-mpegURL']")
                .attr("src")
                .takeIf { it.isNotBlank() }
                ?: doc.select("video source[src$='.mp4']").attr("src").takeIf { it.isNotBlank() }
                ?: Regex("""https?://[^\s"']+\.(?:m3u8|mp4)""").find(doc.html())?.value?.takeIf { it.isNotBlank() }
                ?: doc.select("iframe[src*='.m3u8'], iframe[src*='.mp4']").attr("src").takeIf { it.isNotBlank() }

            if (!streamUrl.isNullOrBlank()) {
                val finalUrl = full(cfg.baseUrl, streamUrl) ?: streamUrl
                val linkType = if (finalUrl?.endsWith(".m3u8") == true) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                val quality = Qualities.Unknown.value

                if (finalUrl != null) {
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
                    return true
                }
            }
        }

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
