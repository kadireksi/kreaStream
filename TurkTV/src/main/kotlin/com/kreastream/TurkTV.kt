package com.kreastream
import com.lagradost.cloudstream3.*
    import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils
import com.kreastream.helpers.*
    import com.kreastream.extractors.*
    class TurkTV: MainAPI() {
        override
        var name = "Türk TV"
        override
        var mainUrl = "https://turktv.local"
        override
        var lang = "tr"
        override val hasMainPage = true
        override val supportedTypes = setOf(TvType.TvSeries, TvType.Live)
        private val channelsUrl = "https://raw.githubusercontent.com/kadireksi/
        kreaStream / builds / channels.json "
        private val streamsUrl = "https://raw.githubusercontent.com/kadireksi/
        kreaStream / builds / streams.json "
        private
        var channels = listOf < ChannelConfig > ()
        private
        var streams = listOf < StreamItem > ()
        private
        var loaded = false
        private suspend fun ensureConfig() {
            if (loaded) return
            try {
                channels = parseJson(app.get(channelsUrl).text)
            } catch (e: Exception) {
                // try local fallback or keep empty
            }
            try {
                streams = parseJson(app.get(streamsUrl).text)
            } catch (e: Exception) {}
            loaded = true
        }
        override suspend fun getMainPage(page: Int, request: MainPageRequest):
            HomePageResponse {
                ensureConfig()
                val pages = mutableListOf < HomePageList > ()
                // 1) Live dynamic groups by stream.group
                val grouped = streams.groupBy {
                    it.group ? : "TV"
                }
                grouped.forEach {
                    (groupName, list) - >
                    val header = when(groupName.toLowerCase()) {
                        "radio",
                        "radyo" - > "📻 Canlı Radyo"
                        else - > "📺 Canlı TV"
                    }
                    // extract unique genres
                    val genres = list.flatMap {
                        it.genres.split(",")
                    }.map {
                        it.trim()
                    }.filter {
                        it.isNotEmpty()
                    }.distinct()
                    // create a SearchResponse for each genre — clicking genre opens
                    a load() that lists episodes(channels)
                    val items = genres.map {
                        genre - >
                            newSearchResponse(genre, "${header}_genre::$groupName::
                                $genre ", TvType.TvSeries) {
                                this.posterUrl = ""
                                this.description = "Canlı: $genre"
                            }
                    }
                    if (items.isNotEmpty()) pages.add(HomePageList(header, items,
                        true))
                }
                // 2) Normal channel listings defined in channels.json
                channels.filter {
                    it.active
                }.forEach {
                    ch - >
                        ch.listings.forEach {
                            listing - >
                                val items = ListingHelper.loadListing(app, ch, listing)
                            if (items.isNotEmpty()) pages.add(HomePageList("${ch.name} •
                                    $ {
                                        listing.title
                                    }
                                    ", items, listing.ishorizontal))
                                }
                            }
                            return newHomePageResponse(pages)
                        }
                    override suspend fun search(query: String): List < SearchResponse > {
                        ensureConfig()
                        val results = mutableListOf < SearchResponse > ()
                        // Search streams
                        streams.filter {
                            it.title.contains(query, true)
                        }.forEach {
                            results.add(it.toSearchResponse())
                        }
                        // Search channels listings
                        channels.forEach {
                            ch - >
                                ch.listings.forEach {
                                    listing - >
                                        results += ListingHelper.searchListing(app, ch, listing,
                                            query)
                                }
                        }
                        return results
                    }
                    override suspend fun load(url: String): LoadResponse {
                        ensureConfig()
                        // 1) Live genre: format used above ->
                        "<header>_genre::<group>::<genre>"
                        if (url.contains("_genre::")) {
                            val parts = url.split("::")
                            if (parts.size >= 3) {
                                val group = parts[1]
                                val genre = parts[2]
                                return LiveHelper.loadGenre(group, genre, streams)
                            }
                        }
                        // 2) Stream direct
                        streams.firstOrNull {
                            it.url == url
                        }?.let {
                            si - >
                                return StreamHelper.loadStreamSingle(si)
                        }
                        // 3) Channel detail (long form url inside channel base)
                        val ch = channels.firstOrNull {
                            url.startsWith(it.base_url)
                        }
                        if (ch != null) {
                            return EpisodeHelper.loadDetails(app, ch, url)
                        }
                        throw Error("Unknown url format")
                    }
                    override suspend fun loadLinks(
                        data: String,
                        isCasting: Boolean,
                        subtitleCallback: (SubtitleFile) - > Unit,
                        callback: (ExtractorLink) - > Unit
                    ): Boolean {
                        ensureConfig()
                        // if data is a stream url
                        streams.firstOrNull {
                            it.url == data
                        }?.let {
                            si - >
                                StreamHelper.loadStreamLinks(si, callback)
                            return true
                        }
                        // if data belongs to channel
                        val ch = channels.firstOrNull {
                            data.startsWith(it.base_url)
                        }
                        if (ch != null) return VideoExtractor.extract(app, ch, data,
                            subtitleCallback, callback)
                        return false
                    }
                }
