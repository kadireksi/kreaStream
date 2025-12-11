package com.kreastream.extractors

import com.lagradost.cloudstream3.*
import com.kreastream.*
import org.jsoup.nodes.Document

object ExtractorRouter {
    suspend fun runMethod(
        api: MainAPI,
        method: ExtractionMethod,
        ch: ChannelConfig,
        doc: Document,
        subtitleCallback: (SubtitleFile) -> Unit,
        cb: (ExtractorLink) -> Unit
    ): Boolean {
        when (method.type) {
            "iframe" -> {
                val sel = method.selector ?: "iframe"
                val iframes = doc.select(sel)
                iframes.forEach { iframe ->
                    val src = iframe.attr(method.attribute ?: "src")
                    if (src.isNotBlank()) {
                        try { api.loadExtractor(src, ch.base_url, subtitleCallback, cb) } catch (_: Exception) {
                            try { api.loadExtractor(src, subtitleCallback, cb) } catch (_: Exception) { }
                        }
                    }
                }
                return iframes.isNotEmpty()
            }
            "css" -> {
                val attr = method.attribute ?: "src"
                val valStr = doc.select(method.selector ?: "").attr(attr)
                if (valStr.isNotBlank()) {
                    cb(api.newExtractorLink("css", "source", valStr) { this.quality = Qualities.Unknown.value })
                    return true
                }
            }
            "regex" -> {
                val html = doc.html()
                val pat = method.pattern ?: return false
                Regex(pat).find(html)?.groupValues?.getOrNull(1)?.let { l ->
                    cb(api.newExtractorLink("regex","src", l) { this.quality = Qualities.Unknown.value })
                    return true
                }
            }
            "direct_m3u8" -> {
                val scripts = doc.select("script").map { it.html() }
                var found = false
                scripts.forEach { s ->
                    Regex("(https?://[^\"']+\\.m3u8[^\"']*)").find(s)?.groupValues?.get(1)?.let { u ->
                        cb(api.newExtractorLink("m3u8", "HLS", u) { this.type = ExtractorLinkType.M3U8; this.quality = Qualities.Unknown.value })
                        found = true
                    }
                }
                return found
            }
            "youtube" -> {
                Regex("youtube.*(?:v=|embed/)([a-zA-Z0-9_-]+)").find(doc.html())?.groupValues?.get(1)?.let { id ->
                    val url = "https://www.youtube.com/watch?v=$id"
                    try { api.loadExtractor(url, api.mainUrl, subtitleCallback, cb); return true } catch (_: Exception) {}
                }
            }
        }
        return false
    }
}
