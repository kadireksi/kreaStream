package com.kreastream

import com.lagradost.cloudstream3.*

class ShowTv : BaseTvChannel {
    override val name = "Show TV"
    override val mainUrl = "https://www.showtv.com.tr"
    override val logoUrl = "https://raw.githubusercontent.com/kadireksi/kreaStream/refs/heads/logos/TV/show-tv.png"
    override val supportedTypes = setOf(TvType.TvSeries)
    override val lang = "tr"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        // Implement Show TV series loading
        return null
    }

    override suspend fun load(url: String): LoadResponse {
        // Implement Show TV series loading
        return newMovieLoadResponse("Show TV", url, TvType.TvSeries, url)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Implement Show TV search
        return emptyList()
    }
}