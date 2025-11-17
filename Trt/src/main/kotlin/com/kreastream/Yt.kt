package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.extractors.*

class Yt : ExtractorApi() {
    override val name = "YouTubeExtractor"
    override val mainUrl = "https://www.youtube.com"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        // Just use the default YouTube extraction which already handles multiple qualities
        loadExtractor(url, referer ?: mainUrl, subtitleCallback, callback)
    }
}