package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class TrtMain : MainAPI() {
    override var mainUrl = "https://www.trt1.com.tr"
    override var name = "TRT"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Live)

    private val parser = Trt1Parser()

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pages = mutableListOf<HomePageList>()

        // 🟦 Section 1: Güncel Diziler
        val newSeries = parser.getMainPage(1, MainPageRequest("Güncel Diziler", "$mainUrl/diziler?archive=false&order=title_asc"))
        pages.add(HomePageList("TRT Diziler", newSeries.list.first().list))

        // 🟩 Section 2: Eski Diziler
        val oldSeries = parser.getMainPage(1, MainPageRequest("Eski Diziler", "$mainUrl/diziler?archive=true&order=title_asc"))
        pages.add(HomePageList("TRT Eski Diziler", oldSeries.list.first().list))

        // 🔴 Section 3: Canlı Yayınlar
        val liveItem = newTvSeriesSearchResponse("Canlı Yayınlar", "$mainUrl/canli") {
            this.posterUrl = "https://upload.wikimedia.org/wikipedia/commons/7/70/Logo_of_TRT1.png"
        }
        pages.add(HomePageList("Canlı Yayınlar", listOf(liveItem)))

        return newHomePageResponse(pages)
    }

    override suspend fun load(url: String): LoadResponse {
        // Handle Canlı Yayınlar page
        if (url.endsWith("/canli")) {
            val episodes = mutableListOf<Episode>()

            for ((name, streamUrl, logo) in TrtUtils.getLiveChannels()) {
                val nowPlaying = TrtUtils.getNowPlaying(name)
                episodes.add(
                    newEpisode(streamUrl) {
                        this.name = name
                        this.posterUrl = logo
                        this.description = nowPlaying ?: "Canlı yayın akışı"
                    }
                )
            }

            return newTvSeriesLoadResponse("Canlı Yayınlar", "$mainUrl/canli", TvType.Live, episodes) {
                this.posterUrl = "https://upload.wikimedia.org/wikipedia/commons/7/70/Logo_of_TRT1.png"
            }
        }

        // Otherwise, delegate to original parser
        return parser.load(url)
    }


    override suspend fun search(query: String): List<SearchResponse> {
        return parser.search(query)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Handle direct .m3u8 links (for live)
        if (data.endsWith(".m3u8")) {
            M3u8Helper.generateM3u8(
                source = "TRT",
                streamUrl = data,
                referer = mainUrl
            ).forEach(callback)
            return true
        }

        // Delegate everything else (TRT episodes) to original parser
        return parser.loadLinks(data, isCasting, subtitleCallback, callback)
    }
}
