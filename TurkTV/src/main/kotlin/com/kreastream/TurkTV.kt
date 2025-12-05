package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString

class TurkTV : MainAPI() {

    override var mainUrl = "https://TurkTV.tr" // not used but required
    override var name = "Türk TV"
    override val lang = "tr"

    // 🔧 Replace these URLs after uploading JSONs
    private val channelsJsonUrl = "https://raw.githubusercontent.com/kadireksi/builds/channels.json"
    private val streamsJsonUrl  = "https://raw.githubusercontent.com/kadireksi/builds/streams.json"

    private val channels by lazy { runBlocking { fetchJson<List<ChannelConfig>>(channelsJsonUrl) } }
    private val liveStreams by lazy { runBlocking { fetchJson<List<LiveStreamConfig>>(streamsJsonUrl) } }

    @Serializable
    data class SelectorBlock(
        val url: String,
        val container: String,
        val title: String,
        val link: String,
        val poster: String? = null
    )

    @Serializable
    data class StreamConfig(
        val type: String,           // mp4, m3u8, mp4+m3u8
        val prefer: String,         // m3u8 or mp4
        val referer: Boolean = false
    )

    @Serializable
    data class ChannelConfig(
        val key: String,
        val name: String,
        val baseUrl: String,
        val series: SelectorBlock,
        val episodes: SelectorBlock,
        val stream: StreamConfig
    )

    @Serializable
    data class LiveStreamConfig(
        val key: String,
        val title: String,
        val poster: String?,
        val url: String,
        val requiresReferer: Boolean = false
    )

    // ---------------------
    // Helper JSON Loader
    // ---------------------
    private suspend inline fun <reified T> fetchJson(url: String): T {
        val response = app.get(url).text
        return AppUtils.json.decodeFromString(response)
    }

    // ---------------------
    // Home Page (Series + Live Section)
    // ---------------------
    override suspend fun getMainPage(): HomePageResponse {
        val lists = channels.map { config ->
            val series = fetchSeries(config)
            HomePageList("${config.name} Diziler", series)
        }.toMutableList()

        if (liveStreams.isNotEmpty()) {
            lists += HomePageList("Canlı Yayınlar", liveStreams.map {
                TvSeriesSearchResponse(it.title, it.url, it.poster, apiName = name)
            })
        }

        return newHomePageResponse(lists)
    }

    // ---------------------
    // Series Fetching
    // ---------------------
    private suspend fun fetchSeries(config: ChannelConfig): List<SearchResponse> {
        val doc = app.get(config.series.url).document

        return doc.select(config.series.container).mapNotNull { element ->
            val title = element.select(config.series.title).text()
                .takeIf { it.isNotBlank() } ?: return@mapNotNull null

            val url = element.select(config.series.link).attr("href")
                .takeIf { it.isNotBlank() } ?: return@mapNotNull null

            val poster = config.series.poster?.let {
                element.select(it).attr("src")
            }

            TvSeriesSearchResponse(
                title = title,
                url = fixUrl(config.baseUrl, url),
                posterUrl = fixUrl(config.baseUrl, poster),
                apiName = config.name
            )
        }
    }

    // ---------------------
    // Episode Fetching
    // ---------------------
    private suspend fun fetchEpisodes(config: ChannelConfig, url: String): List<Episode> {
        val doc = app.get(url).document

        return doc.select(config.episodes.container).mapNotNull { e ->
            val title = e.select(config.episodes.title).text().ifBlank { "Bölüm" }
            val epUrl = e.select(config.episodes.link).attr("href")
                .takeIf { it.isNotBlank() } ?: return@mapNotNull null

            Episode(title, fixUrl(config.baseUrl, epUrl))
        }
    }

    // ---------------------
    // Stream Resolver (ATV logic simplified for now)
    // ---------------------
    override suspend fun load(url: String): LoadResponse {
        val config = channels.find { url.contains(it.baseUrl) }
            ?: return newErrorLoadResponse("Unsupported channel")

        val episodes = fetchEpisodes(config, url)

        return newEpisodePage(episodes) {
            val links = resolveStream(config, url)
            links.forEach(it)
        }
    }

    private suspend fun resolveStream(config: ChannelConfig, url: String): List<ExtractorLink> {
        return extractAtvDynamic(url).sortedByDescending { it.quality }
    }

    // ---------------------
    // Temporary ATV Extractor (works for now)
    // ---------------------
    private suspend fun extractAtvDynamic(url: String): List<ExtractorLink> {
        val list = mutableListOf<ExtractorLink>()
        val html = app.get(url).text

        val regex = Regex("""https.*?\.mp4""")
        val mp4 = regex.find(html)?.value

        if (mp4 != null) {
            list += ExtractorLink("ATV", "ATV MP4", mp4, referer = "https://www.atv.com.tr")
        }

        return list
    }

    // ---------------------
    private fun fixUrl(base: String, url: String?): String? {
        if (url == null) return null
        return if (url.startsWith("http")) url else base + url.trimStart('/')
    }
}
