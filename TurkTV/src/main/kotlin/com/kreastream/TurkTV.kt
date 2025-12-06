package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.newExtractorLink
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
    private val liveChannelsUrl = "turktv://livechannels"
    private val radioChannelsUrl = "turktv://radiochannels"

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
        val series: SelectorBlock,
        val episodes: SelectorBlock,
        val stream: StreamConfig
    )

    data class LiveStreamConfig(
        val key: String,
        val title: String,
        val poster: String?,
        val url: String,
        val requiresReferer: Boolean = false,
        val streamType: String = "tv"
    )

    // ------------------- JSON LOADING -------------------
    private suspend fun ensureLoaded() {
        if (channels == null) {
            try {
                Log.d("DEBUG: Loading channels from $channelsJsonUrl")
                val channelsText = app.get(channelsJsonUrl).text
                Log.d("DEBUG: Channels JSON received: ${channelsText.length} characters")
                channels = parseJson<List<ChannelConfig>>(channelsText)
                Log.d("DEBUG: Channels parsed successfully: ${channels?.size} channels")
            } catch (e: Exception) {
                Log.d("DEBUG: Failed to load channels: ${e.message}")
                e.printStackTrace()
                channels = emptyList() 
            }
        }
        if (streams == null) {
            try {
                Log.d("DEBUG: Loading streams from $streamsJsonUrl")
                val streamsText = app.get(streamsJsonUrl).text
                Log.d("DEBUG: Streams JSON received: ${streamsText.length} characters")
                streams = parseJson<List<LiveStreamConfig>>(streamsText)
                Log.d("DEBUG: Streams parsed successfully: ${streams?.size} streams")
            } catch (e: Exception) {
                Log.d("DEBUG: Failed to load streams: ${e.message}")
                e.printStackTrace()
                streams = emptyList()
            }
        }
    }

    // ------------------- MAIN PAGE -------------------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        ensureLoaded()

        val lists = mutableListOf<HomePageList>()
        
        // --- 1. LIVE STREAMS SECTION (Combined TV and Radio) ---
        val liveItems = mutableListOf<SearchResponse>()
        
        // Check if we have streams loaded
        if (streams.isNullOrEmpty()) {
            Log.d("DEBUG: No streams loaded, showing placeholder")
            // Live TV Item (placeholder)
            liveItems += newTvSeriesSearchResponse(
                "Canlı TV (Yakında)", 
                liveChannelsUrl, 
                TvType.TvSeries
            ).apply {
                posterUrl = "https://cdn-icons-png.flaticon.com/512/3198/3198691.png"
            }

            // Live Radio Item (placeholder)
            liveItems += newTvSeriesSearchResponse(
                "Radyo (Yakında)", 
                radioChannelsUrl, 
                TvType.TvSeries
            ).apply {
                posterUrl = "https://cdn-icons-png.flaticon.com/512/3106/3106776.png"
            }
        } else {
            Log.d("DEBUG: Streams loaded, showing real items")
            // Live TV Item (real)
            liveItems += newTvSeriesSearchResponse(
                "Canlı TV", 
                liveChannelsUrl, 
                TvType.TvSeries
            ).apply {
                posterUrl = "https://cdn-icons-png.flaticon.com/512/3198/3198691.png"
            }

            // Live Radio Item (real)
            liveItems += newTvSeriesSearchResponse(
                "Radyo", 
                radioChannelsUrl, 
                TvType.TvSeries
            ).apply {
                posterUrl = "https://cdn-icons-png.flaticon.com/512/3106/3106776.png"
            }
        }
        
        lists += HomePageList("Canlı Yayınlar", liveItems)
        
        // --- 2. SERIES SECTIONS ---
        if (channels.isNullOrEmpty()) {
            Log.d("DEBUG: No channels loaded, showing error")
            // Add a placeholder if no channels loaded
            lists += HomePageList("Diziler", listOf(
                newTvSeriesSearchResponse("Kanal Yüklenemedi", "", TvType.TvSeries).apply {
                    posterUrl = "https://cdn-icons-png.flaticon.com/512/157/157933.png"
                }
            ))
        } else {
            Log.d("DEBUG: Channels loaded: ${channels!!.size} channels")
            channels!!.forEach { cfg ->
                try {
                    Log.d("DEBUG: Fetching series for ${cfg.name}")
                    val series = fetchSeries(cfg)
                    Log.d("DEBUG: Fetched ${series.size} series for ${cfg.name}")
                    if (series.isNotEmpty()) {
                        lists += HomePageList("${cfg.name} Diziler", series)
                    } else {
                        // Add placeholder if no series found
                        lists += HomePageList("${cfg.name} Diziler", listOf(
                            newTvSeriesSearchResponse("Dizi Bulunamadı", "", TvType.TvSeries).apply {
                                posterUrl = "https://cdn-icons-png.flaticon.com/512/157/157933.png"
                            }
                        ))
                    }
                } catch (e: Exception) {
                    Log.d("DEBUG: Error fetching series for ${cfg.name}: ${e.message}")
                    e.printStackTrace()
                    // Add error placeholder
                    lists += HomePageList("${cfg.name} Diziler", listOf(
                        newTvSeriesSearchResponse("Hata: ${e.message}", "", TvType.TvSeries).apply {
                            posterUrl = "https://cdn-icons-png.flaticon.com/512/157/157933.png"
                        }
                    ))
                }
            }
        }

        return newHomePageResponse(lists)
    }

    // ------------------- FETCH SERIES -------------------
    private suspend fun fetchSeries(cfg: ChannelConfig): List<SearchResponse> {
        val seriesList = mutableListOf<SearchResponse>()
        
        try {
            Log.d("DEBUG: Fetching series from URL: ${cfg.series.url}")
            val doc = app.get(cfg.series.url).document
            val container = cfg.series.container
            val titleSelector = cfg.series.title
            val linkSelector = cfg.series.link
            val posterSelector = cfg.series.poster
            
            Log.d("DEBUG: Using selectors - Container: $container, Title: $titleSelector, Link: $linkSelector")
            
            val elements = doc.select(container)
            Log.d("DEBUG: Found ${elements.size} elements with container selector")
            
            elements.forEachIndexed { index, element ->
                try {
                    val title = element.select(titleSelector).text().trim()
                    val link = element.select(linkSelector).attr("href")
                    val poster = if (posterSelector != null) {
                        element.select(posterSelector).attr("src")
                    } else null
                    
                    Log.d("DEBUG: Element $index - Title: '$title', Link: '$link', Poster: '$poster'")
                    
                    if (title.isNotBlank() && link.isNotBlank()) {
                        val fullLink = full(cfg.baseUrl, link)
                        if (fullLink != null) {
                            seriesList.add(
                                newTvSeriesSearchResponse(title, fullLink, TvType.TvSeries) {
                                    this.posterUrl = full(cfg.baseUrl, poster)
                                }
                            )
                            Log.d("DEBUG: Added series: $title")
                        }
                    }
                } catch (e: Exception) {
                    Log.d("DEBUG: Error processing element $index: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.d("DEBUG: Error fetching series: ${e.message}")
            e.printStackTrace()
        }
        
        Log.d("DEBUG: Total series fetched: ${seriesList.size}")
        return seriesList
    }

    // ------------------- LOAD (Series Page) -------------------
    override suspend fun load(url: String): LoadResponse {
        ensureLoaded()
        
        // 1. Check for Synthetic Live Hub URLs
        val isLiveTv = url == liveChannelsUrl
        val isRadio = url == radioChannelsUrl

        if (isLiveTv || isRadio) {
            val typeToFilter = if (isLiveTv) "tv" else "radio"
            val title = if (isLiveTv) "Canlı TV Kanalları" else "Radyo Kanalları"
            
            val filteredStreams = streams
                ?.filter { it.streamType == typeToFilter }
                ?: emptyList()
            
            Log.d("DEBUG: Loading $title with ${filteredStreams.size} streams")
            
            if (filteredStreams.isEmpty()) {
                // Return "Coming Soon" if no streams
                return newTvSeriesLoadResponse("$title (Yakında)", url, TvType.Live, emptyList()) {
                    posterUrl = if (isLiveTv) {
                        "https://cdn-icons-png.flaticon.com/512/3198/3198691.png"
                    } else {
                        "https://cdn-icons-png.flaticon.com/512/3106/3106776.png"
                    }
                }
            }
            
            // Create episodes for live streams
            val episodes = filteredStreams.map { live ->
                newEpisode(live.url) { 
                    this.name = live.title
                    this.posterUrl = live.poster ?: if (isLiveTv) {
                        "https://cdn-icons-png.flaticon.com/512/3198/3198691.png"
                    } else {
                        "https://cdn-icons-png.flaticon.com/512/3106/3106776.png"
                    }
                    this.description = "Canlı yayın"
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.Live, episodes) {
                posterUrl = if (isLiveTv) {
                    "https://cdn-icons-png.flaticon.com/512/3198/3198691.png"
                } else {
                    "https://cdn-icons-png.flaticon.com/512/3106/3106776.png"
                }
            }
        }

        // 2. Original Series Logic (Main Series Page)
        val cfg = channels?.firstOrNull { url.contains(it.baseUrl, ignoreCase = true) }
            ?: return newTvSeriesLoadResponse("Bulunamadı", url, TvType.TvSeries, emptyList()) {}

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
                        }
                    )
                } catch (e: Exception) {
                    // Skip this episode if there's an error
                }
            }
        } catch (e: Exception) {
            // Return empty list if there's an error
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

        // ── Live streams ──
        streams?.firstOrNull { it.url == data }?.let { live ->
            callback(
                newExtractorLink(
                    source = name,
                    name = live.title,
                    url = live.url
                ){
                    this.referer = if (live.requiresReferer) mainUrl else ""
                    this.quality = Qualities.Unknown.value
                    this.type = ExtractorLinkType.M3U8
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

            // Build the stream URL step by step to avoid nullable chain issues
            val source1 = doc.select("video source[src\$='.m3u8'], source[type='application/x-mpegURL']").attr("src")
            val streamUrl1 = if (source1.isNotBlank()) source1 else ""
            
            val streamUrl2 = if (streamUrl1.isBlank()) {
                doc.select("video source[src\$='.mp4']").attr("src")
            } else streamUrl1
            
            val streamUrl3 = if (streamUrl2.isBlank()) {
                Regex("""https?://[^\s"']+\.(?:m3u8|mp4)""").find(doc.html())?.value ?: ""
            } else streamUrl2
            
            val streamUrl4 = if (streamUrl3.isBlank()) {
                // Try alternative selectors
                val iframeSrc = doc.select("iframe[src*='.m3u8'], iframe[src*='.mp4']").attr("src")
                iframeSrc
            } else streamUrl3

            if (streamUrl4.isNotBlank()) {
                val finalUrl = full(cfg.baseUrl, streamUrl4) ?: streamUrl4
                
                // Ensure finalUrl is not null
                if (finalUrl != null) {
                    val linkType = if (finalUrl.endsWith(".m3u8")) {
                        ExtractorLinkType.M3U8
                    } else {
                        ExtractorLinkType.VIDEO
                    }
                    
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "Stream",
                            url = finalUrl
                        ) {
                            this.referer = if (cfg.stream.referer) cfg.baseUrl else mainUrl
                            this.quality = Qualities.Unknown.value
                            this.type = linkType
                            val headers = mutableMapOf(
                                "User-Agent" to "Mozilla/5.0",
                                "Referer" to cfg.baseUrl
                            )
                            if (cfg.stream.prefer.isNotBlank()) {
                                headers["Accept"] = cfg.stream.prefer
                            }
                            this.headers = headers
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
