package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.kreastream.helpers.EpisodeHelper
import com.kreastream.helpers.ListingHelper
import com.kreastream.helpers.LiveHelper
import com.kreastream.helpers.StreamHelper
import com.kreastream.extractors.VideoExtractor

class TurkTV : MainAPI() {
    override var name = "Türk TV"
    override var mainUrl = "https://turktv.local"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Live)

    private val channelsUrl = "https://raw.githubusercontent.com/kadireksi/kreaStream/builds/channels.json"
    private val streamsUrl = "https://raw.githubusercontent.com/kadireksi/kreaStream/builds/streams.json"

    private var channels = listOf<ChannelConfig>()
    private var streams = listOf<StreamItem>()
    private var loaded = false

    private suspend fun ensureConfig() {
        if (loaded) return

        try {
            val chText = try { app.get(channelsUrl).text } catch (_: Exception) { AppUtils.readAllText("channels.json") }
            channels = parseJson(chText)
        } catch (_: Exception) {
            channels = emptyList()
        }

        try {
            val stText = try { app.get(streamsUrl).text } catch (_: Exception) { AppUtils.readAllText("streams.json") }
            streams = parseJson(stText)
        } catch (_: Exception) {
            streams = emptyList()
        }

        loaded = true
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        ensureConfig()

        val pages = mutableListOf<HomePageList>()

        val grouped = streams.groupBy { it.group ?: "TV" }
        for ((groupName, list) in grouped) {
            val header = when (groupName.lowercase()) {
                "radio", "radyo" -> "📻 Canlı Radyo"
                else -> "📺 Canlı TV"
            }

            val genres = list.flatMap { it.genres.split(",") }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()

            val items = mutableListOf<SearchResponse>()
            for (genre in genres) {
                val fakeUrl = "${header}_genre::${groupName}::${genre}"
                items.add(newSearchResponse(genre, fakeUrl, TvType.TvSeries) {
                    this.posterUrl = ""
                    this.description = "Canlı: $genre"
                })
            }
            if (items.isNotEmpty()) pages.add(HomePageList(header, items, true))
        }

        for (ch in channels.filter { it.active }) {
            for (listing in ch.listings) {
                val items = ListingHelper.loadListing(this, ch, listing)
                if (items.isNotEmpty()) pages.add(HomePageList("${ch.name} • ${listing.title}", items, listing.ishorizontal))
            }
        }

        return newHomePageResponse(pages)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        ensureConfig()
        val results = mutableListOf<SearchResponse>()

        for (s in streams.filter { it.title.contains(query, true) }) {
            results.add(newSearchResponse(s.title, s.url, TvType.Live) {
                this.posterUrl = s.poster
            })
        }

        for (ch in channels) {
            for (listing in ch.listings) {
                results += ListingHelper.searchListing(this, ch, listing, query)
            }
        }

        return results
    }

    override suspend fun load(url: String): LoadResponse {
        ensureConfig()

        if (url.contains("_genre::")) {
            val parts = url.split("::")
            if (parts.size >= 3) {
                val group = parts[1]
                val genre = parts[2]
                return LiveHelper.loadGenre(this, group, genre, streams)
            }
        }

        streams.firstOrNull { it.url == url }?.let { si ->
            return StreamHelper.loadStreamSingle(this, si)
        }

        val ch = channels.firstOrNull { url.startsWith(it.base_url) }
        if (ch != null) {
            return EpisodeHelper.loadDetails(this, ch, url)
        }

        throw ErrorLoadingException("Unknown url format: $url")
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        ensureConfig()

        streams.firstOrNull { it.url == data }?.let { si ->
            StreamHelper.loadStreamLinks(si, callback)
            return true
        }

        val ch = channels.firstOrNull { data.startsWith(it.base_url) }
        if (ch != null) return VideoExtractor.extract(this, ch, data, subtitleCallback, callback)

        if (data.contains(".m3u8", ignoreCase = true)) {
            M3u8Helper.generateM3u8(source = name, streamUrl = data, referer = mainUrl).forEach(callback)
            return true
        }

        return false
    }
}
