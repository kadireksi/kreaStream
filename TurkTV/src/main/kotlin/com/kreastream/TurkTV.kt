package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson

class TurkTV : MainAPI() {

    override var mainUrl = "https://turktv.local"
    override var name = "Türk TV"
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Live)
    override val hasMainPage = true

    // TEMP — replace with your GitHub links later:
    private val channelsJsonUrl = "https://YOUR_URL/channels.json"
    private val streamsJsonUrl = "https://YOUR_URL/streams.json"

    private var channels: List<ChannelConfig>? = null
    private var streams: List<LiveStreamConfig>? = null

    // ---- DATA MODELS ----
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


    // ---- JSON LOADING ----
    private suspend fun ensureLoaded() {
        if (channels == null) channels = parseJson(app.get(channelsJsonUrl).text)
        if (streams == null) streams = parseJson(app.get(streamsJsonUrl).text)
    }


    // ---- MAIN PAGE ----
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        ensureLoaded()

        val result = mutableListOf<HomePageList>()

        channels?.forEach { config ->
            result += HomePageList("${config.name} Diziler", fetchSeries(config))
        }

        streams?.let { liveList ->
            result += HomePageList(
                "Canlı Yayınlar",
                liveList.map {
                    newTvSeriesSearchResponse(
                        name = it.title,
                        url = it.url
                    ){
                        apiName = this.name,
                        type = TvType.Live,
                        posterUrl = it.poster
                    }
                }
            )
        }

        return HomePageResponse(result, false)
    }


    // ---- FETCH SERIES LIST ----
    private suspend fun fetchSeries(config: ChannelConfig): List<SearchResponse> {
        val doc = app.get(config.series.url).document
        val sel = config.series

        return doc.select(sel.container).mapNotNull { el ->
            val title = el.select(sel.title).text().ifBlank { return@mapNotNull null }
            val href = el.select(sel.link).attr("href").ifBlank { return@mapNotNull null }

            val fullUrl = normalize(config.baseUrl, href)
            val poster = sel.poster?.let { normalize(config.baseUrl, el.select(it).attr("src")) }

            newTvSeriesSearchResponse(
                name = title,
                url = fullUrl!!
            ){
                apiName = name,
                type = TvType.TvSeries,
                posterUrl = poster
            }
        }
    }


    // ---- LOAD SERIES PAGE ----
    override suspend fun load(url: String): LoadResponse {
        ensureLoaded()

        val config = channels?.find { url.contains(it.baseUrl, true) }
            ?: return newTvSeriesLoadResponse("HATA", url, name, TvType.TvSeries, emptyList())

        val episodes = fetchEpisodes(config, url)

        return newTvSeriesLoadResponse(
            name = config.name,
            url = url,
            apiName = name,
            type = TvType.TvSeries,
            episodes = episodes,
        )
    }


    // ---- FETCH EPISODES ----
    private suspend fun fetchEpisodes(config: ChannelConfig, url: String): List<Episode> {
        val doc = app.get(url).document
        val sel = config.episodes

        return doc.select(sel.container).mapNotNull { el ->
            val title = el.select(sel.title).text().ifBlank { "Bölüm" }
            val href = normalize(config.baseUrl, el.select(sel.link).attr("href")) ?: return@mapNotNull null

            newEpisode {
                name = title
                data = href
            }
        }
    }


    // ---- HELPERS ----
    private fun normalize(base: String, url: String?): String? {
        if (url == null) return null
        return if (url.startsWith("http")) url else base.trimEnd('/') + "/" + url.trimStart('/')
    }
}
