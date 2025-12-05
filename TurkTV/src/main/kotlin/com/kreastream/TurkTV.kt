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
    private val channelsJsonUrl = "https://YOUR_URL/channels.json"
    private val streamsJsonUrl  = "https://YOUR_URL/streams.json"

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

    // ---------- LOAD SERIES ----------
    override suspend fun load(url: String): LoadResponse {
        ensureLoaded()

        val cfg = channels?.find { url.contains(it.baseUrl, true) }
            ?: return newTvSeriesLoadResponse("Bulunamadı", url, TvType.TvSeries) {}

        val eps = fetchEpisodes(cfg, url)

        return newTvSeriesLoadResponse(cfg.name, url, TvType.TvSeries) {
            posterUrl = null
            episodes = eps
        }
    }

    // ---------- FETCH EPISODES ----------
    private suspend fun fetchEpisodes(cfg: ChannelConfig, url: String): List<Episode> {
        val doc = app.get(url).document
        val ep = cfg.episodes

        return doc.select(ep.container).mapNotNull { el ->
            val title = el.select(ep.title).text().ifBlank { "Bölüm" }
            val href = full(cfg.baseUrl, el.select(ep.link).attr("href")) ?: return@mapNotNull null

            newEpisode(title, href)
        }
    }

    // ---------- HELPERS ----------
    private fun full(base: String, url: String?): String? =
        url?.let { if (it.startsWith("http")) it else base + it.trimStart('/') }
}
