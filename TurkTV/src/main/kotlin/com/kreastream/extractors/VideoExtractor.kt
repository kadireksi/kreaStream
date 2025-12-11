package com.kreastream.extractors

import com.lagradost.cloudstream3.*
import com.kreastream.*

object VideoExtractor {
    suspend fun extract(
        api: MainAPI,
        ch: ChannelConfig,
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        cb: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val doc = api.app.get(url, headers = ch.headers).document
            var found = false
            ch.video_extraction?.methods?.forEach { m ->
                if (ExtractorRouter.runMethod(api, m, ch, doc, subtitleCallback, cb)) found = true
            }
            found
        } catch (e: Exception) {
            false
        }
    }
}
