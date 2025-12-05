package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson

class TurkTV : MainAPI() {

    override var name = "Türk TV"
    override var mainUrl = "https://turktv.local"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Live)

    // Update these later
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
        if (channels == null) channels = parseJson(app.get(channelsJsonUrl).text)
        if (streams == null) streams = parseJson(app.get(streamsJsonUrl).text)
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

        return doc.select(block.container).mapNotNull { el ->
            val title = el.select(block.title).text().ifBlank { return@mapNotNull null }
            val href = el.select(block.link).attr("href").ifBlank { return@mapNotNull null }
            val url = full(cfg.baseUrl, href)
            val poster = block.poster?.let { full(cfg.baseUrl, el.select(it).attr("src")) }

            newTvSeriesSearchResponse(title, url!!, TvType.TvSeries) {
                posterUrl = poster
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Quick live stream check (from getMainPage)
        streams?.find { data == it.url }?.let { live ->
            callback(newExtractorLink(
                source = name,
                name = live.title,
                url = live.url,
                referer = if (live.requiresReferer) mainUrl else null,
                quality = Qualities.Unknown.value,
                type = ExtractorLinkType.VIDEO  // Assume VIDEO; use M3U8 if HLS
            ) {
                // Lambda for additional config if needed, e.g., headers
                this.headers = mapOf("User-Agent" to "Mozilla/5.0")  // Example
            })
            return true
        }

        // For series episodes: Find config and extract
        channels?.find { data.contains(it.baseUrl) }?.let { cfg ->
            // Example: Fetch episode page and extract via cfg.stream
            val doc = app.get(data).document
            val streamUrl = doc.select(cfg.stream.type /* e.g., "source" */).attr("src")  // Customize selector
            if (streamUrl.isNotBlank()) {
                val fullUrl = full(cfg.baseUrl, streamUrl)
                callback(newExtractorLink(
                    source = name,
                    name = "Episode Stream",
                    url = fullUrl!!,
                    referer = if (cfg.stream.referer) cfg.baseUrl else null,
                    quality = Qualities.HD.value,  // Assume or parse
                    type = ExtractorLinkType.VIDEO  // Or M3U8/DASH based on URL
                ) {
                    // Lambda for extra props, e.g., if prefer is set
                    if (cfg.stream.prefer.isNotBlank()) {
                        this.headers = mapOf("Prefer" to cfg.stream.prefer)
                    }
                })
                return true
            }
        }
        return false
    }

    private suspend fun fetchEpisodes(cfg: ChannelConfig, url: String): List<Episode> {
        val doc = app.get(url).document
        val ep = cfg.episodes

        return doc.select(ep.container).mapNotNull { el ->
            val title = el.select(ep.title).text().ifBlank { "Bölüm" }
            val href = full(cfg.baseUrl, el.select(ep.link).attr("href")) ?: return@mapNotNull null

            newEpisode {
                this.name = title
                this.data = href
            }
        }
    }

    // ---------- HELPERS ----------
    private fun full(base: String, url: String?): String? =
        url?.let { if (it.startsWith("http")) it else base + it.trimStart('/') }
}
