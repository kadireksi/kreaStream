package com.kreastream

import com.lagradost.cloudstream3.*

class Atv : BaseTvChannel {
    override val name = "ATV"
    override val mainUrl = "https://www.atv.com.tr"
    override val logoUrl = "https://raw.githubusercontent.com/kadireksi/kreaStream/refs/heads/logos/TV/atv.png"
    override val supportedTypes = setOf(TvType.TvSeries)
    override val lang = "tr"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        // Implement ATV series loading
        return null
    }

    override suspend fun load(url: String): LoadResponse {
        // Implement ATV series loading
        return newMovieLoadResponse("ATV", url, TvType.TvSeries, url)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Implement ATV search
        return emptyList()
    }
}