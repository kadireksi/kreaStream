package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.runBlocking

class TurkTV : MainAPI() {

    override var name = "Türk TV"
    override var mainUrl = "https://www.atv.com.tr"  // Real default base
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Live)

    private val channelsJsonUrl = "https://raw.githubusercontent.com/kadireksi/kreaStream/builds/channels.json"
    private val streamsJsonUrl  = "https://raw.githubusercontent.com/kadireksi/kreaStream/builds/streams.json"

    private var channels: List<ChannelConfig>? = null
    private var streams: List<LiveStreamConfig>? = null

    // -------- DATA MODELS --------
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
        val requiresReferer: Boolean = false
    )

    // ---------- JSON LOADING ----------
    private suspend fun ensureLoaded() {
        if (channels == null) {
            try {
                channels = parseJson<List<ChannelConfig>>(app.get(channelsJsonUrl).text)
            } catch (e: Exception) {
                channels = emptyList()
            }
        }
        if (streams == null) {
            try {
                streams = parseJson<List<LiveStreamConfig>>(app.get(streamsJsonUrl).text)
            } catch (e: Exception) {
                streams = emptyList()
            }
        }
    }

    // ---------- MAIN PAGE ----------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        ensureLoaded()

        val lists = mutableListOf<HomePageList>()

        channels?.forEach { cfg ->
            lists += HomePageList("${cfg.name} Diziler", fetchSeries(cfg))
        }

        streams?.let {
            lists += HomePageList(
                "Canlı Yayınlar",
                it.map { live ->
                    newTvSeriesSearchResponse(live.title, live.url, TvType.Live) {
                        posterUrl = live.poster
                    }
                }
            )
        }

        return newHomePageResponse(lists)
    }

    // ---------- FETCH SERIES ----------
    private suspend fun fetchSeries(cfg: ChannelConfig): List<SearchResponse> {
        val doc = app.get(cfg.series.url).document
        val block = cfg.series

        val elements = doc.select(block.container)
        if (elements.isEmpty()) {
        }

        return elements.mapNotNull { el ->
            val title = el.select(block.title).text().ifBlank { return@mapNotNull null }
            val href = el.select(block.link).attr("href").ifBlank { return@mapNotNull null }
            val url = full(cfg.baseUrl, href)
            val poster = block.poster?.let { full(cfg.baseUrl, el.select(it).attr("src")) }

            newTvSeriesSearchResponse(title, url!!, TvType.TvSeries) {
                posterUrl = poster
            }
        }
    }

    // ---------- LOAD SERIES ----------
    override suspend fun load(url: String): LoadResponse {
        ensureLoaded()

        val cfg = channels?.find { url.contains(it.baseUrl, true) }
            ?: return newTvSeriesLoadResponse("Bulunamadı", url, TvType.TvSeries, emptyList()) {}

        val eps = fetchEpisodes(cfg, url)

        return newTvSeriesLoadResponse(cfg.name, url, TvType.TvSeries, eps) {
            posterUrl = null
        }
    }

    private suspend fun fetchEpisodes(cfg: ChannelConfig, url: String): List<Episode> {
        val doc = app.get(url).document
        val ep = cfg.episodes

        val elements = doc.select(ep.container)
        if (elements.isEmpty()) {
        }

        return elements.mapNotNull { el ->
            val title = el.select(ep.title).text().ifBlank { "Bölüm" }
            val href = full(cfg.baseUrl, el.select(ep.link).attr("href")) ?: return@mapNotNull null

            newEpisode {
                this.name = title
                this.data = href
            }
        }
    }

    // ---------- LOAD LINKS ----------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Handle live streams (data == live.url)
        streams?.find { data == it.url }?.let { live ->
            callback(
                newExtractorLink(
                    source = name,
                    name = live.title,
                    url = live.url
                ){
                    this.referer = if (live.requiresReferer) mainUrl else null
                    this.quality = Qualities.Unknown.value
                    this.type = ExtractorLinkType.M3U8   
                    this.headers = mapOf("User-Agent" to "Mozilla/5.0")
                }
            )
            return true
        }

        // Handle series episodes
        channels?.find { data.contains(it.baseUrl) }?.let { cfg ->
            val doc = app.get(data).document
            // Robust extraction: Prefer M3U8/MP4 from video tags or regex
            var streamUrl = doc.select("video source[src$='.m3u8'], source[type='application/x-mpegURL']").attr("src")
                .ifBlank { doc.select("video source[src$='.mp4']").attr("src") }
                .ifBlank { 
                    // Regex fallback for embedded URLs
                    doc.html().find("""https?://[^\s"]+\.(?:mp4|m3u8)""") ?: ""
                }
            if (streamUrl.isNotBlank()) {
                val fullUrl = full(cfg.baseUrl, streamUrl)
                val linkType = if (fullUrl!!.endsWith(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                val quality = if (cfg.stream.prefer == "m3u8") Qualities.HD.value else Qualities.SD.value
                callback(
                    newExtractorLink(
                    source = name,
                    name = "Episode Stream",
                    url = fullUrl
                    ){
                        this.referer = if (cfg.stream.referer) cfg.baseUrl else null
                        this.quality = quality
                        this.type = linkType
                        if (cfg.stream.prefer.isNotBlank()) {
                            this.headers = mapOf("Accept" to cfg.stream.prefer)
                        }
                    }
                )
                return true
            }
        }
        return false
    }

    // ---------- HELPERS ----------
    private fun full(base: String, url: String?): String? =
        url?.let { 
            if (it.startsWith("http")) it 
            else (base.removeSuffix("/") + "/" + it.trimStart('/').trimStart('/')) 
        }
}
