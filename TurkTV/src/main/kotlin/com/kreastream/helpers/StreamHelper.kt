package com.kreastream.helpers

import com.lagradost.cloudstream3.*
import com.kreastream.*

object StreamHelper {
    fun StreamItem.toSearchResponse(api: MainAPI): SearchResponse {
        return api.newTvSeriesSearchResponse(this.title, this.url, TvType.Live) { this.posterUrl = this@toSearchResponse.poster }
    }

    fun loadStreamSingle(api: MainAPI, si: StreamItem): LoadResponse {
        val ep = api.newEpisode(si.url) { this.name = si.title; this.posterUrl = si.poster }
        return api.newTvSeriesLoadResponse(si.title, si.url, TvType.Live, listOf(ep)) {
            this.posterUrl = si.poster
            this.plot = if (si.is_audio) "Canlı radyo" else "Canlı TV"
        }
    }

    fun loadStreamLinks(si: StreamItem, callback: (ExtractorLink) -> Unit) {
        val type = if (si.url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        callback(newExtractorLink("Live", si.title, si.url) {
            this.headers = si.headers
            this.quality = Qualities.Unknown.value
            this.type = type
            this.referer = si.headers["Referer"] ?: ""
        })
    }
}
