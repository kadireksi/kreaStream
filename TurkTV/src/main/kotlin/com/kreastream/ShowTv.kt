package com.kreastream

import com.lagradost.cloudstream3.*

class ShowTv : MainAPI(), BaseTvChannel {
    override val name = "Show TV"
    override val mainUrl = "https://www.showtv.com.tr"
    override val logoUrl = "https://raw.githubusercontent.com/kadireksi/kreaStream/refs/heads/logos/TV/show-tv.png"
    override val supportedTypes = setOf(TvType.TvSeries)
    override val lang = "tr"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        // TODO: Implement Show TV series loading
        return null
    }

    override suspend fun load(url: String): LoadResponse {
        // TODO: Implement Show TV series loading
        return newMovieLoadResponse("Show TV", url, TvType.TvSeries, url)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // TODO: Implement Show TV search
        return emptyList()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // TODO: Implement Show TV video extraction
        return false
    }
}