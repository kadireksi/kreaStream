package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.kreastream.helpers.ListingHelper
import com.kreastream.helpers.LiveHelper
import com.kreastream.helpers.EpisodeHelper
import com.kreastream.helpers.StreamHelper

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

        // remote-first, fallback to asset/local
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

        // 1) Live dynamic groups by stream.group
        val grouped = streams.groupBy { it.group ?: "TV" }
        grouped.forEach { (groupName, list) ->
            val header = when (groupName.lowercase()) {
                "radio", "radyo" -> "📻 Canlı Radyo"
                else -> "📺 Canlı TV"
            }

            // extract unique genres (split by comma in the JSON)
            val genres = list.flatMap { it.genres.split(",") }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()

            // Build SearchResponse items explicitly (avoid ambiguous lambda type inference)
            val items = mutableListOf<SearchResponse>()
            for (genre in genres) {
                // create an id-like url that our load() will recognise: "<header>_genre::<group>::<genre>"
                val fakeUrl = "${header}_genre::${groupName}::${genre}"
                items.add(newSearchResponse(genre, fakeUrl, TvType.TvSeries) {
                    this.posterUrl = ""
                    this.description = "Canlı: $genre"
                })
            }

            if (items.isNotEmpty()) pages.add(HomePageList(header, items, true))
        }

        // 2) Normal channel listings defined in channels.json
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

        // Search streams (live)
        for (s in streams.filter { it.title.contains(query, true) }) {
            results.add(newSearchResponse(s.title, s.url, TvType.Live) {
                this.posterUrl = s.poster
            })
        }

        // Search channel listings
        for (ch in channels) {
            for (listing in ch.listings) {
                results += ListingHelper.searchListing(this, ch, listing, query)
            }
        }

        return results
    }

    override suspend fun load(url: String): LoadResponse {
        ensureConfig()

        // Live genre: format "<header>_genre::<group>::<genre>"
        if (url.contains("_genre::")) {
            val parts = url.split("::")
            if (parts.size >= 3) {
                val group = parts[1]
                val genre = parts[2]
                return LiveHelper.loadGenre(this, group, genre, streams)
            }
        }

        // direct stream URL
        streams.firstOrNull { it.url == url }?.let { si ->
            return StreamHelper.loadStreamSingle(this, si)
        }

        // channel detail
        val ch = channels.firstOrNull { url.startsWith(it.base_url) }
        if (ch != null) {
            return EpisodeHelper.loadDetails(this, ch, url)
        }

        throw Error("Unknown url format")
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        ensureConfig()

        // if data is a stream url
        streams.firstOrNull { it.url == data }?.let { si ->
            StreamHelper.loadStreamLinks(si, callback)
            return true
        }

        // if data belongs to channel
        val ch = channels.firstOrNull { data.startsWith(it.base_url) }
        if (ch != null) return com.kreastream.extractors.VideoExtractor.extract(this, ch, data, subtitleCallback, callback)

        return false
    }
}
