package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addEpisodes
import com.lagradost.cloudstream3.LoadResponse.Companion.addLinks
import com.lagradost.cloudstream3.LoadResponse.Companion.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.AppUtils.json

class TurkTV : MainAPI() {

    override var mainUrl = "https://TurkTV.tr"
    override var name = "Türk TV"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Live)

    // 🔧 Replace later with real URLs
    private val channelsJsonUrl = "https://raw.githubusercontent.com/kadireksi/builds/channels.json"
    private val streamsJsonUrl = "https://raw.githubusercontent.com/kadireksi/builds/streams.json"

    private var channels: List<ChannelConfig>? = null
    private var streams: List<LiveStreamConfig>? = null

    // -------------------------
    // JSON MODELS
    // -------------------------
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

    // -------------------------
    // JSON FETCHING
    // -------------------------
    private suspend inline fun <reified T> fetchJson(url: String): T {
        val txt = app.get(url).text
        return json.decodeFromString(txt)
    }

    private suspend fun ensureLoaded() {
        if (channels == null) channels = fetchJson(channelsJsonUrl)
        if (streams == null) streams = fetchJson(streamsJsonUrl)
    }

    // -------------------------
    // MAIN PAGE
    // -------------------------
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        ensureLoaded()
        val result = mutableListOf<HomePageList>()

        channels?.forEach { cfg ->
            val series = fetchSeries(cfg)
            result += HomePageList("${cfg.name} Diziler", series)
        }

        streams?.let { list ->
            if (list.isNotEmpty()) {
                result += HomePageList("Canlı Yayınlar", list.map {
                    newTvSeriesSearchResponse(
                        title = it.title,
                        url = it.url,
                        type = TvType.Live,
                    ) { posterUrl = it.poster }
                })
            }
        }

        return newHomePageResponse(result)
    }

    // -------------------------
    // FETCH SERIES
    // -------------------------
    private suspend fun fetchSeries(config: ChannelConfig): List<SearchResponse> {
        val doc = app.get(config.series.url).document
        val sel = config.series

        return doc.select(sel.container).mapNotNull { el ->
            val title = el.select(sel.title).text().ifBlank { return@mapNotNull null }

            val href = el.select(sel.link).attr("href")
                .ifBlank { return@mapNotNull null }

            val fixedUrl = fixUrl(config.baseUrl, href)

            val poster = sel.poster?.let { el.select(it).attr("src") }

            newTvSeriesSearchResponse(title, fixedUrl, TvType.TvSeries) {
                posterUrl = fixUrl(config.baseUrl, poster)
            }
        }
    }

    // -------------------------
    // LOAD (EPISODES + STREAMS)
    // -------------------------
    override suspend fun load(url: String): LoadResponse {

        ensureLoaded()

        val config = channels?.find { url.contains(it.baseUrl) }
            ?: return newTvSeriesLoadResponse("Unknown Source", url, TvType.TvSeries) {}

        val episodes = fetchEpisodes(config, url)

        return newTvSeriesLoadResponse("Bölümler", url, TvType.TvSeries) {
            posterUrl = null
            addEpisodes(
                episodes.map {
                    newEpisode(it.name, it.url)
                }
            )
            addLinks { callback ->
                resolveStream(config, url).forEach(callback)
            }
        }
    }

    // -------------------------
    // FETCH EPISODES
    // -------------------------
    private suspend fun fetchEpisodes(config: ChannelConfig, url: String): List<Episode> {
        val doc = app.get(url).document
        val ep = config.episodes

        return doc.select(ep.container).mapNotNull { el ->
            val title = el.select(ep.title).text().ifBlank { "Bölüm" }
            val href = el.select(ep.link).attr("href").ifBlank { return@mapNotNull null }
            Episode(title, fixUrl(config.baseUrl, href))
        }
    }

    // -------------------------
    // STREAM RESOLUTION (TEMP ATV)
    // -------------------------
    private suspend fun resolveStream(cfg: ChannelConfig, url: String): List<ExtractorLink> {
        val html = app.get(url).text
        val links = mutableListOf<ExtractorLink>()

        val match = Regex("""https.*?\.mp4""").find(html)?.value

        if (match != null) {
            links += newExtractorLink("ATV", "ATV • MP4", match) {
                if (cfg.stream.referer) referer = cfg.baseUrl
            }
        }

        return links
    }

    private fun fixUrl(base: String, url: String?): String? =
        if (url == null) null else if (url.startsWith("http")) url else base + url.trimStart('/')
}
