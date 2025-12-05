package com.kreastream

import com.lagradost.cloudstream3.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

class TurkTV : MainAPI() {

    override var mainUrl = "https://TurkTV.tr"
    override var name = "Türk TV"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Live)

    private val channelsJsonUrl = "https://raw.githubusercontent.com/kadireksi/builds/channels.json"
    private val streamsJsonUrl = "https://raw.githubusercontent.com/kadireksi/builds/streams.json"

    private var channels: List<ChannelConfig>? = null
    private var streams: List<LiveStreamConfig>? = null

    // ---------- MODELS ----------
    @kotlinx.serialization.Serializable
    data class SelectorBlock(
        val url: String,
        val container: String,
        val title: String,
        val link: String,
        val poster: String? = null
    )

    @kotlinx.serialization.Serializable
    data class StreamConfig(
        val type: String,
        val prefer: String,
        val referer: Boolean = false
    )

    @kotlinx.serialization.Serializable
    data class ChannelConfig(
        val key: String,
        val name: String,
        val baseUrl: String,
        val series: SelectorBlock,
        val episodes: SelectorBlock,
        val stream: StreamConfig
    )

    @kotlinx.serialization.Serializable
    data class LiveStreamConfig(
        val key: String,
        val title: String,
        val poster: String?,
        val url: String,
        val requiresReferer: Boolean = false
    )

    // ---------- JSON FETCH ----------
    private suspend inline fun <reified T> fetchJson(url: String): T {
        val txt = app.get(url).text
        return Json.decodeFromString(txt)
    }

    private suspend fun ensureLoaded() {
        if (channels == null) channels = fetchJson(channelsJsonUrl)
        if (streams == null) streams = fetchJson(streamsJsonUrl)
    }

    // ---------- MAIN PAGE ----------
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        ensureLoaded()

        val lists = mutableListOf<HomePageList>()

        channels?.forEach { cfg ->
            val series = fetchSeries(cfg)
            lists += HomePageList("${cfg.name} Diziler", series)
        }

        streams?.let {
            lists += HomePageList("Canlı Yayınlar",
                it.map { s -> TvSeriesSearchResponse(s.title, s.url, name, TvType.Live, posterUrl = s.poster) }
            )
        }

        return HomePageResponse(lists, false)
    }

    // ---------- FETCH SERIES ----------
    private suspend fun fetchSeries(config: ChannelConfig): List<SearchResponse> {
        val doc = app.get(config.series.url).document
        val sel = config.series

        return doc.select(sel.container).mapNotNull { el ->
            val title = el.select(sel.title).text().ifBlank { return@mapNotNull null }
            val href = el.select(sel.link).attr("href").ifBlank { return@mapNotNull null }
            val url = fixUrl(config.baseUrl, href)
            val poster = sel.poster?.let { fixUrl(config.baseUrl, el.select(it).attr("src")) }

            TvSeriesSearchResponse(title, url!!, name, TvType.TvSeries, posterUrl = poster)
        }
    }

    // ---------- LOAD ----------
    override suspend fun load(url: String): LoadResponse {
        ensureLoaded()

        val cfg = channels?.find { url.contains(it.baseUrl, true) }
            ?: return TvSeriesLoadResponse("Unknown Source", url, name, TvType.TvSeries)

        val eps = fetchEpisodes(cfg, url).toMutableList()

        return TvSeriesLoadResponse(cfg.name, url, name, TvType.TvSeries).apply {
            episodes = eps
            posterUrl = null
            plot = null
        }
    }

    private suspend fun fetchEpisodes(cfg: ChannelConfig, url: String): List<Episode> {
        val doc = app.get(url).document
        val ep = cfg.episodes

        return doc.select(ep.container).mapNotNull { el ->
            val title = el.select(ep.title).text().ifBlank { "Bölüm" }
            val href = fixUrl(cfg.baseUrl, el.select(ep.link).attr("href")) ?: return@mapNotNull null
            Episode(data = href, name = title)
        }
    }

    // FIX URL
    private fun fixUrl(base: String, url: String?): String? =
        url?.let { if (it.startsWith("http")) it else base + it.trimStart('/') }
}
