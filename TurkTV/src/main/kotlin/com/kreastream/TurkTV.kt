package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class TurkTV : MainAPI() {
    override var name = "Türk TV"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Live)

    data class Channel(
        val name: String,
        val mainUrl: String,
        val currentSeries: String,
        val archiveSeries: String? = null,
        val liveUrl: String? = null,
        val liveName: String? = null
    )

    private val channels = listOf(
        Channel("ATV", "https://www.atv.com.tr", "/diziler", "/eski-diziler",
            liveUrl = "https://atv.vodgc.net/atvhd/atv.m3u8", liveName = "ATV Canlı"),

        Channel("Kanal D", "https://www.kanald.com.tr", "/diziler/tum-diziler", "/diziler/arsiv-diziler",
            liveUrl = "https://kanald-live.ercdn.net/kanald/kanald.m3u8", liveName = "Kanal D Canlı"),

        Channel("Show TV", "https://www.showtv.com.tr", "/diziler/yayinda", "/diziler/arsiv",
            liveUrl = "https://showtv-live.ercdn.net/showtv/showtv.m3u8", liveName = "Show TV Canlı"),

        Channel("Star TV", "https://www.startv.com.tr", "/diziler/yayinda-olanlar", "/diziler/arsiv-diziler",
            liveUrl = "https://startv-live.ercdn.net/startv/startv.m3u8", liveName = "Star TV Canlı"),

        Channel("NOW (Fox)", "https://www.nowtv.com.tr", "/diziler/yayinda", "/diziler/arsiv",
            liveUrl = "https://nowtv-live.ercdn.net/nowtv/nowtv.m3u8", liveName = "NOW Canlı"),

        Channel("TRT 1", "https://www.trt1.com.tr", "/diziler", "/arsiv/diziler",
            liveUrl = "https://trt1.blutv.com.tr/live/trt1/trt1.m3u8", liveName = "TRT 1 Canlı")
    )

    override val mainPage = mainPageOf(
        // Live TV Section
        "live" to "Canlı TV",

        // Current Series
        "current_atv" to "ATV - Güncel Diziler",
        "current_kanald" to "Kanal D - Güncel Diziler",
        "current_show" to "Show TV - Güncel Diziler",
        "current_star" to "Star TV - Güncel Diziler",
        "current_now" to "NOW - Güncel Diziler",
        "current_trt1" to "TRT 1 - Güncel Diziler",

        // Archive Series
        "archive_atv" to "ATV - Arşiv Diziler",
        "archive_kanald" to "Kanal D - Arşiv Diziler",
        "archive_show" to "Show TV - Arşiv Diziler",
        "archive_star" to "Star TV - Arşiv Diziler",
        "archive_now" to "NOW - Arşiv Diziler",
        "archive_trt1" to "TRT 1 - Arşiv Diziler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = mutableListOf<HomePageList>()

        when (request.name) {
            "Canlı TV" -> {
                val liveItems = channels.mapNotNull { ch ->
                    ch.liveUrl?.let {
                        newLiveSearchResponse(ch.liveName!!, it, TvType.Live) {
                            posterUrl = "${ch.mainUrl}/favicon.ico"
                        }
                    }
                }
                items.add(HomePageList("Canlı Yayınlar", liveItems, isHorizontalImages = false))
            }

            else -> {
                val (type, channelName) = request.name.split("_", limit = 2)
                val channel = channels.find { it.name.contains(channelName, ignoreCase = true) } ?: return newHomePageResponse(items)

                val url = when (type) {
                    "current" -> channel.mainUrl + channel.currentSeries
                    "archive" -> channel.mainUrl + (channel.archiveSeries ?: channel.currentSeries)
                    else -> return newHomePageResponse(items)
                }

                val doc = app.get(url).document
                val series = doc.select("a[href*='/dizi/'], a[href*='/diziler/'], .series-card a, .card a").mapNotNull {
                    it.toSeriesResponse(channel.mainUrl)
                }

                items.add(HomePageList("${channel.name} - ${if (type == "current") "Güncel" else "Arşiv"} Diziler", series))
            }
        }

        return newHomePageResponse(items)
    }

    private fun Element.toSeriesResponse(baseUrl: String): SearchResponse? {
        val a = this.takeIf { it.tagName() == "a" } ?: selectFirst("a") ?: return null
        val href = fixUrl(a.attr("href"), baseUrl)
        if (!href.contains("/dizi/") && !href.contains("/diziler/")) return null

        val title = a.attr("title").takeIf { it.isNotBlank() }
            ?: a.selectFirst("h3, .title, img")?.attr("alt")
            ?: a.text().takeIf { it.isNotBlank() } ?: return null

        val poster = a.selectFirst("img")?.attr("abs:src")
            ?: a.selectFirst("img")?.attr("data-src")?.let { fixUrl(it, baseUrl) }

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val safeQuery = URLEncoder.encode(query, "UTF-8")

        channels.forEach { ch ->
            try {
                val doc = app.get("${ch.mainUrl}/arama?q=$safeQuery").document
                doc.select("a[href*='/dizi/']").mapNotNullTo(results) { it.toSeriesResponse(ch.mainUrl) }
            } catch (_: Exception) { }
        }
        return results
    }

    override suspend fun load(url: String): LoadResponse {
        val channel = channels.find { url.contains(it.mainUrl.replace("https://www.", "")) } ?: channels[0]
        val doc = app.get(url).document

        val title = doc.selectFirst("h1, .seo-h1, meta[property='og:title']")?.text()
            ?: doc.selectFirst("meta[property='og:title']")?.attr("content") ?: "Bilinmeyen"

        val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")

        val plot = doc.selectFirst(".description, .synopsis")?.text()

        val episodes = doc.select("a[href*='/bolum/'], a[href*='/video/'], .episode-card a").mapNotNull {
            val href = fixUrl(it.attr("href"), channel.mainUrl)
            val name = it.text().trim().ifBlank { "Bölüm" }
            if (href.contains("/bolum/") || href.contains("/video/")) {
                newEpisode(href) { this.name = name }
            } else null
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

        val res = app.get(data)
        val doc = res.document

        // Iframe players
        doc.select("iframe[src*='player'], iframe#player").forEach { iframe ->
            val src = fixUrl(iframe.attr("src"), data)
            if (src.isNotBlank()) loadExtractor(src, data, subtitleCallback, callback)
        }

        // JS file: "file": "https..."
        doc.select("script").forEach { script ->
            Regex("""["']file["']\s*:\s*["']([^"']+)["']""").find(script.data())?.groupValues?.get(1)?.let { url ->
                callback(
                    newExtractorLink(
                        source = name,
                        name = "Direkt Video",
                        url = url
                    ){
                        this.referer = data
                        this.quality = Qualities.Unknown.value
                        //this.isM3u8 = url.contains(".m3u8")
                    }
                )
            }
        }

        return true
    }

    private fun fixUrl(url: String, base: String): String {
        if (url.startsWith("http")) return url
        if (url.startsWith("//")) return "https:$url"
        return if (url.startsWith("/")) base.removeSuffix("/") + url else base.removeSuffix("/") + "/" + url
    }
}
