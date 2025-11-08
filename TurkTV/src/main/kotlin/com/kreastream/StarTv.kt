package com.kreastream

import com.lagradost.cloudstream3.*

class StarTv : BaseTvChannel {
    override val name = "Star TV"
    override val mainUrl = "https://www.startv.com.tr"
    override val logoUrl = "https://raw.githubusercontent.com/kadireksi/kreaStream/refs/heads/logos/TV/star-tv.png"
    override val supportedTypes = setOf(TvType.TvSeries)
    override val lang = "tr"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        // Implement Star TV series loading
        return null
    }

    override suspend fun load(url: String): LoadResponse {
        // Implement Star TV series loading
        return newMovieLoadResponse("Star TV", url, TvType.TvSeries, url)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Implement Star TV search
        return emptyList()
    }
}