package com.kreastream

import com.lagradost.cloudstream3.*

interface BaseTvChannel {
    val name: String
    val mainUrl: String
    val logoUrl: String
    val supportedTypes: Set<TvType>
    val lang: String
    
    suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse?
    suspend fun load(url: String): LoadResponse
    suspend fun search(query: String): List<SearchResponse>
    suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean
}