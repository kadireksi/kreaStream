package com.kreastream

import com.kreastream.extractors.*
import com.kreastream.helpers.*
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson


class TurkTV : MainAPI() {
    override var name = "Türk TV"
    override var mainUrl = "https://turktv.local"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Live)


    private val channelsUrl =
        "https://raw.githubusercontent.com/kadireksi/kreaStream/builds/channels.json"
    private val streamsUrl =
        "https://raw.githubusercontent.com/kadireksi/kreaStream/builds/streams.json"


    private var channels = listOf<ChannelConfig>()
    private var streams = listOf<StreamItem>()
    private var loaded = false


    private suspend fun ensureConfig() {
        if (loaded) return
        try {
            channels = parseJson(app.get(channelsUrl).text)
        } catch (e: Exception) {
// try local fallback or keep empty
        }
        try {
            streams = parseJson(app.get(streamsUrl).text)
        } catch (e: Exception) {
        }
        loaded = true
    }


    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        ensureConfig()


        val pages = mutableListOf<HomePageList>()


// 1) Live dynamic groups by stream.group
        val grouped = streams.groupBy { it.group ?: "TV" }
        grouped.forEach { (groupName, list) ->
            val header = when (groupName.toLowerCase()) {
                "radio", "radyo" -> "📻 Canlı Radyo"
                else -> "📺 Canlı TV"
            }
// extract unique genres
            val genres =
                list.flatMap { it.genres.split(",") }.map { it.trim() }.filter { it.isNotEmpty() }
                    .distinct()
// create a SearchResponse for each genre — clicking genre opens a load() that lists episodes (channels)
            val items = genres.map { genre ->
                newSearchResponse(genre, "${header}_genre::$groupName::$genre", TvType.TvSeries) {
                    this.posterUrl = ""
                    this.description = "Canlı: $genre"
                }
            }
            if (items.isNotEmpty()) pages.add(HomePageList(header, items, true))
        }


// 2) Normal channel listings defined in channels.json
        channels.filter { it.active }.forEach { ch ->
            ch.listings.forEach { listing ->
                val items = ListingHelper.loadListing(app, ch, listing)
                if (items.isNotEmpty()) pages.add(
                    HomePageList(
                        "${ch.name} • ${listing.title}",
                        items,
                        listing.ishorizontal
                    )
                )
            }
        }
