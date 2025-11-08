package com.kreastream

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.ExtractorLink

interface BaseTvChannel {
    val channelName: String
    val channelUrl: String
    val channelLogo: String
    val channelTypes: Set<TvType>
    val channelLang: String
    
    suspend fun getChannelMainPage(page: Int, request: MainPageRequest): HomePageResponse?
    suspend fun loadChannel(url: String): LoadResponse
    suspend fun searchChannel(query: String): List<SearchResponse>
    suspend fun loadChannelLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean
}