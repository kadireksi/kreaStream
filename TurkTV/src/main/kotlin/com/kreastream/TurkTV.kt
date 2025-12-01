// TurkishTV.kt - Multi-Channel + Live TV - FIXED & STABLE (2025)
package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class TurkTV : MainAPI() {
    override var name = "Türk TV"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Live)

    data class Channel(
        val key: String,
        val displayName: String,
        val baseUrl: String,
        val currentPath: String,
        val archivePath: String? = null,
        val liveStream: String? = null
    )

    private val channels = listOf(
        Channel("atv", "ATV", "https://www.atv.com.tr", "/diziler", "/eski-diziler",
            "https://atv.vodgc.net/atvhd/atv.m3u8"),
        Channel("kanald", "Kanal D", "https://www.kanald.com.tr", "/diziler/tum-diziler", "/diziler/arsiv-diziler",
            "https://kanald-live.ercdn.net/kanald/kanald.m3u8"),
        Channel("show", "Show TV", "https://www.showtv.com.tr", "/diziler/yayinda", "/diziler/arsiv",
            "https://showtv-live.ercdn.net/showtv/showtv.m3u8"),
        Channel("star", "Star TV", "https://www.startv.com.tr", "/diziler/yayinda-olanlar", "/diziler/arsiv-diziler",
            "https://startv-live.ercdn.net/startv/startv.m3u8"),
        Channel("now", "NOW (Fox)", "https://www.nowtv.com.tr", "/diziler/yayinda", "/diziler/arsiv",
            "https://nowtv-live.ercdn.net/nowtv/nowtv.m3u8"),
        Channel("trt1", "TRT 1", "https://www.trt1.com.tr", "/diziler", "/arsiv/diziler",
            "https://trt1.blutv.com.tr/live/trt1/trt1.m3u8")
    )

    override val mainPage = mainPageOf(
        "live://all" to "Canlı TV",

        // Current series
        "current://atv" to "ATV - Güncel Diziler",
        "current://kanald" to "Kanal D - Güncel Diziler",
        "current://show" to "Show TV - Güncel Diziler",
        "current://star" to "Star TV - Güncel Diziler",
        "current://now" to "NOW - Güncel Diziler",
        "current://trt1" to "TRT 1 - Güncel Diziler",

        // Archive series
        "archive://atv" to "ATV - Arşiv Diziler",
        "archive://kanald" to "Kanal D - Arşiv Diziler",
        "archive://show" to "Show TV - Arşiv Diziler",
        "archive://star" to "Star TV - Arşiv Diziler",
        "archive://now" to "NOW - Arşiv Diziler",
        "archive://trt1" to "TRT 1 - Arşiv Diziler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = mutableListOf<HomePageList>()

        when {
            request.data.startsWith("live://") -> {
                val liveItems = channels.mapNotNull { ch ->
                    ch.liveStream?.let {
                        newLiveSearchResponse("${ch.displayName} Canlı", it, TvType.Live) {
                            posterUrl = "${ch.baseUrl}/favicon.ico"
                        }
                    }
                }
                items.add(HomePageList("Canlı Yayınlar", liveItems))
            }

            request.data.startsWith("current://") || request.data.startsWith("archive://") -> {
                val parts = request.data.split("://", limit = 2)
                val type = parts[0]  // "current" or "archive"
                val channelKey = parts[1]

                val channel = channels.find { it.key == channelKey }
                    ?: return newHomePageResponse(items)

                val path = if (type == "current") channel.currentPath else (channel.archivePath ?: channel.currentPath)
                val url = channel.baseUrl + path

                val doc = app.get(url).document
                val series = doc.select("a[href*='/dizi/'], a[href*='/diziler/'], .series-card a, .card a, .dizi-card a")
                    .mapNotNull { it.toSeriesResponse(channel.baseUrl) }

                val title = "${channel.displayName} - ${if (type == "current") "Güncel" else "Arşiv"} Diziler"
                items.add(HomePageList(title, series))
            }
        }

        return newHomePageResponse(items)
    }

    private fun Element.toSeriesResponse(baseUrl: String): SearchResponse? {
        val link = this.takeIf { it.tagName() == "a" } ?: selectFirst("a") ?: return null
        var href = link.attr("href")
        if (href.startsWith("/")) href = baseUrl.removeSuffix("/") + href
        if (!href.contains("/dizi/") && !href.contains("/diziler/") && !href.contains("/program/")) return null

        val title = link.attr("title").takeIf { it.isNotBlank() }
            ?: link.selectFirst("h3, .title, .name, img")?.attr("alt")
            ?: link.text().takeIf { it.isNotBlank() } ?: return null

        val poster = link.selectFirst("img")
            ?.let { it.attr("data-src") ?: it.attr("src") }
            ?.takeIf { it.isNotBlank() }
            ?.let { if (it.startsWith("http")) it else baseUrl.removeSuffix("/") + it }

        return newTvSeriesSearchResponse(title.trim(), href, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val q = URLEncoder.encode(query, "UTF-8")

        channels.forEach { ch ->
            try {
                val doc = app.get("${ch.baseUrl}/arama?q=$q").document
                doc.select("a[href*='/dizi/'], a[href*='/diziler/']").mapNotNullTo(results) {
                    it.toSeriesResponse(ch.baseUrl)
                }
            } catch (e: Exception) {
                // Ignore failed channel search
            }
        }
        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val channel = channels.find { url.contains(it.baseUrl, ignoreCase = true) } ?: channels[0]
        val doc = app.get(url).document

        val title = doc.selectFirst("h1, .seo-h1, .title, meta[property='og:title']")
            ?.let { if (it.tagName() == "meta") it.attr("content") else it.text() } ?: "Bilinmeyen Dizi"

        val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
            ?: doc.selectFirst("img.poster, img.series-poster")?.attr("abs:src")

        val plot = doc.selectFirst(".description, .synopsis, meta[name='description']")
            ?.let { if (it.tagName() == "meta") it.attr("content") else it.text() }

        val episodes = doc.select("a[href*='/bolum/'], a[href*='/video/'], .episode-card a, .bolum a")
            .mapNotNull { el ->
                val epUrl = el.attr("href").let {
                    if (it.startsWith("http")) it else channel.baseUrl.removeSuffix("/") + it
                }
                if (!epUrl.contains("/bolum/") && !epUrl.contains("/video/")) return@mapNotNull null
                val name = el.text().trim().ifBlank { "Bölüm" }
                newEpisode(epUrl) { this.name = name }
            }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val response = app.get(data)
        val doc = response.document

        // 1. Iframe players
        doc.select("iframe[src*='player'], iframe#player, .player iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank()) {
                val fixed = if (src.startsWith("//")) "https:$src" else src
                loadExtractor(fixed, data, subtitleCallback, callback)
            }
        }

        // 2. Direct video from JS
        doc.select("script").forEach { script ->
            Regex("""["']file["']\s*:\s*["']([^"']+)["']""").find(script.data())?.groupValues?.get(1)?.let { videoUrl ->
                callback(newExtractorLink(
                    source = name,
                    name = "Direkt Video",
                    url = videoUrl
                ){
                    this.referer = data
                    this.quality = Qualities.Unknown.value
                    //this.isM3u8 = videoUrl.contains(".m3u8")
                })
            }
        }

        return true
    }
}
