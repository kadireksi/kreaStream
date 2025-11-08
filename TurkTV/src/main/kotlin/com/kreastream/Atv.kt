package com.kreastream

import com.lagradost.cloudstream3.*

class Atv : MainAPI(), BaseTvChannel {
    override val name = "ATV"
    override val mainUrl = "https://www.atv.com.tr"
    override val logoUrl = "https://raw.githubusercontent.com/kadireksi/kreaStream/refs/heads/logos/TV/atv.png"
    override val supportedTypes = setOf(TvType.TvSeries)
    override val lang = "tr"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        // TODO: Implement ATV series loading
        return null
    }

    override suspend fun load(url: String): LoadResponse {
        // TODO: Implement ATV series loading
        return newMovieLoadResponse("ATV", url, TvType.TvSeries, url)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // TODO: Implement ATV search
        return emptyList()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // TODO: Implement ATV video extraction
        return false
    }
}