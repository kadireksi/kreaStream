package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

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
        val requiresReferer: Boolean = false
    )

    // ------------------- JSON LOADING -------------------
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

    // ------------------- MAIN PAGE -------------------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        ensureLoaded()

        val lists = mutableListOf<HomePageList>()

        channels?.forEach { cfg ->
            val series = fetchSeries(cfg)
            if (series.isNotEmpty()) {
                lists += HomePageList("${cfg.name} Diziler", series)
            }
        }

        streams?.takeIf { it.isNotEmpty() }?.let { liveList ->
            lists += HomePageList(
                "Canlı Yayınlar",
                liveList.map { live ->
                    newTvSeriesSearchResponse(live.title, live.url, TvType.Live) {
                        posterUrl = live.poster
                    }
                }
            )
        }

        return newHomePageResponse(lists)
    }

    // ------------------- FETCH SERIES -------------------
    private suspend fun fetchSeries(cfg: ChannelConfig): List<SearchResponse> {
        val doc = app.get(cfg.series.url).document
        val block = cfg.series

        return doc.select(block.container).mapNotNull { el ->
            val title = el.select(block.title).text().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val href = el.select(block.link).attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val url = full(cfg.baseUrl, href) ?: return@mapNotNull null
            val poster = block.poster?.let { full(cfg.baseUrl, el.select(it).attr("src")) }

            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = poster
            }
        }
    }

    // ------------------- LOAD (series page) -------------------
    override suspend fun load(url: String): LoadResponse {
        ensureLoaded()

        val cfg = channels?.firstOrNull { url.contains(it.baseUrl, ignoreCase = true) }
            ?: return newTvSeriesLoadResponse("Bulunamadı", url, TvType.TvSeries, emptyList()) {}

        val episodes = fetchEpisodes(cfg, url)

        return newTvSeriesLoadResponse(cfg.name, url, TvType.TvSeries, episodes) {
            posterUrl = null
        }
    }

    private suspend fun fetchEpisodes(cfg: ChannelConfig, url: String): List<Episode> {
        val doc = app.get(url).document
        val ep = cfg.episodes

        return doc.select(ep.container).mapNotNull { el ->
            val title = el.select(ep.title).text().ifBlank { "Bölüm" }
            val href = el.select(ep.link).attr("href")
            val episodeUrl = full(cfg.baseUrl, href) ?: return@mapNotNull null

            newEpisode(episodeUrl) {
                this.name = title
            }
        }
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
                    this.headers = mapOf("User-Agent" to "Mozilla/5.0")
                }
            )
            return true
        }

        // ── Series episodes ──
        channels?.firstOrNull { data.contains(it.baseUrl, ignoreCase = true) }?.let { cfg ->
            val doc = app.get(data).document

            var streamUrl: String? = doc.select("video source[src$='.m3u8'], source[type='application/x-mpegURL']")
                .attr("src")
                .ifBlank { doc.select("video source[src$='.mp4']").attr("src") }
                .ifBlank {
                    Regex("""https?://[^\s"']+\.(?:m3u8|mp4)""").find(doc.html())?.value
                }

            if (!streamUrl.isNullOrBlank()) {
                val finalUrl = full(cfg.baseUrl, streamUrl)!!
                val linkType = if (finalUrl.endsWith(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                val quality = if (cfg.stream.prefer == "m3u8") Qualities.HD.value else Qualities.SD.value

                callback(
                    newExtractorLink(
                        source = name,
                        name = "Stream",
                        url = finalUrl
                    ){
                        this.referer = if (cfg.stream.referer) cfg.baseUrl else "",
                        this.quality = quality,
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

    // ------------------- HELPER -------------------
    private fun full(base: String, url: String?): String? = url?.let {
        if (it.startsWith("http")) it
        else "${base.removeSuffix("/")}/${it.trimStart('/')}"
    }
}
