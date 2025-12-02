// TurkTV.kt - Ultimate Turkish TV Plugin (2025) - FULL TRT 1 SUPPORT
package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder
import org.json.JSONObject

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
        val liveStream: String? = null,
        val isTrt: Boolean = false
    )

    private val channels = listOf(
        Channel("atv", "ATV", "https://www.atv.com.tr", "/diziler", "/eski-diziler",
            "https://trkvz.daioncdn.net/atv/atv.m3u8?ce=3&app=d1ce2d40-5256-4550-b02e-e73c185a314e&st=0F2E3SdO1jy-8fTaE7rqXQ&e=1764664587&ppid=21d804be91b8e91f11bb3789ff83f0b4&gdpr=0"),
        Channel("kanald", "Kanal D", "https://www.kanald.com.tr", "/diziler/tum-diziler", "/diziler/arsiv-diziler",
            "https://demiroren.daioncdn.net/kanald/kanald.m3u8?app=kanald_web&ce=3&ppid=c1bfdca3b54034e7be9660071f17d132"),
        Channel("show", "Show TV", "https://www.showtv.com.tr", "/diziler/yayinda", "/diziler/arsiv",
            "https://showtv-live.ercdn.net/showtv/showtv.m3u8"),
        Channel("star", "Star TV", "https://www.startv.com.tr", "/diziler/yayinda-olanlar", "/diziler/arsiv-diziler",
            "https://startv-live.ercdn.net/startv/startv.m3u8"),
        Channel("now", "NOW (Fox)", "https://www.nowtv.com.tr", "/diziler/yayinda", "/diziler/arsiv",
            "https://nowtv-live.ercdn.net/nowtv/nowtv.m3u8"),
        Channel("trt1", "TRT 1", "https://www.trt1.com.tr", "/diziler?archive=false", "/diziler?archive=true",
            "https://tv-trt1.medya.trt.com.tr/master.m3u8", isTrt = true)
    )

    override val mainPage = mainPageOf(
        "live://all" to "Canlı TV",
        "trt_live" to "TRT Canlı TV & Radyo",

        "current://atv" to "ATV - Güncel Diziler",
        "current://kanald" to "Kanal D - Güncel Diziler",
        "current://show" to "Show TV - Güncel Diziler",
        "current://star" to "Star TV - Güncel Diziler",
        "current://now" to "NOW - Güncel Diziler",
        "current://trt1" to "TRT 1 - Güncel Diziler",

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
            request.data == "trt_live" -> {
                val tvChannels = getTrtLiveTvChannels()
                val radioChannels = getTrtRadioChannels()
                val allLive = tvChannels + radioChannels
                items.add(HomePageList("TRT Canlı TV & Radyo", allLive.map {
                    newLiveSearchResponse(it.name, it.url, TvType.Live) { posterUrl = it.poster }
                }))
            }

            request.data.startsWith("live://") -> {
                val liveItems = channels.mapNotNull { ch ->
                    ch.liveStream?.let {
                        newLiveSearchResponse("${ch.displayName} Canlı", it, TvType.Live) {
                            posterUrl = "${ch.baseUrl}/favicon.ico"
                        }
                    }
                }
                items.add(HomePageList("Diğer Kanallar - Canlı", liveItems))
            }

            request.data.startsWith("current://") || request.data.startsWith("archive://") -> {
                val parts = request.data.split("://", limit = 2)
                val type = parts[0]
                val key = parts[1]
                val channel = channels.find { it.key == key } ?: return newHomePageResponse(items)

                if (channel.isTrt) {
                    val isArchive = type == "archive"
                    val series = getTrtSeries(archive = isArchive, page = page)
                    items.add(HomePageList("${channel.displayName} - ${if (isArchive) "Arşiv" else "Güncel"} Diziler", series))
                } else {
                    val path = if (type == "current") channel.currentPath else (channel.archivePath ?: channel.currentPath)
                    val url = channel.baseUrl + path
                    val doc = app.get(url).document
                    val series = doc.select("a[href*='/dizi/'], a[href*='/diziler/'], .series-card a, .card a")
                        .mapNotNull { it.toSeriesResponse(channel.baseUrl) }
                    items.add(HomePageList("${channel.displayName} - ${if (type == "current") "Güncel" else "Arşiv"} Diziler", series))
                }
            }
        }
        return newHomePageResponse(items)
    }

    private fun Element.toSeriesResponse(baseUrl: String): SearchResponse? {
        val a = this.takeIf { it.tagName() == "a" } ?: selectFirst("a") ?: return null
        var href = a.attr("href")
        if (href.startsWith("/")) href = baseUrl.removeSuffix("/") + href
        if (!href.contains("/dizi/") && !href.contains("/program/")) return null

        val title = a.attr("title").takeIf { it.isNotBlank() }
            ?: a.selectFirst("h3, .title, img")?.attr("alt")
            ?: a.text().takeIf { it.isNotBlank() } ?: return null

        val poster = a.selectFirst("img")?.attr("data-src") ?: a.selectFirst("img")?.attr("src")
            ?.let { if (it.startsWith("http")) it else baseUrl.removeSuffix("/") + it }

        return newTvSeriesSearchResponse(title.trim(), href, TvType.TvSeries) { this.posterUrl = poster }
    }

    // === TRT 1 SPECIAL PARSING (from your Trt.kt) ===
    private data class LiveChannel(val name: String, val url: String, val poster: String)

    private suspend fun getTrtLiveTvChannels(): List<LiveChannel> {
        return try {
            val doc = app.get("https://www.tabii.com/tr/watch/live/trt1").document
            val json = doc.selectFirst("#__NEXT_DATA__")?.data() ?: return emptyList()
            val obj = JSONObject(json)
            val channels = obj.getJSONObject("props")
                .getJSONObject("pageProps")
                .getJSONArray("liveChannels")

            (0 until channels.length()).mapNotNull { i ->
                val ch = channels.getJSONObject(i)
                val name = ch.getString("title")
                if (name.contains("tabii", ignoreCase = true)) return@mapNotNull null
                val logo = ch.getJSONArray("images").let { imgs ->
                    (0 until imgs.length()).firstOrNull { imgs.getJSONObject(it).getString("imageType") == "logo" }
                        ?.let { imgs.getJSONObject(it).getString("name") }
                        ?.let { "https://cms-tabii-public-image.tabii.com/int/$it" }
                } ?: return@mapNotNull null

                val stream = ch.getJSONArray("media").let { media ->
                    (0 until media.length()).firstOrNull {
                        media.getJSONObject(it).getString("type") == "hls" && media.getJSONObject(it).getString("drmSchema") == "clear"
                    }?.let { media.getJSONObject(it).getString("url") }
                } ?: return@mapNotNull null

                LiveChannel(name, stream, logo)
            }
        } catch (e: Exception) { emptyList() }
    }

    private fun getTrtRadioChannels(): List<LiveChannel> = listOf(
        LiveChannel("TRT Radyo 1", "https://trt.radyotvonline.net/trt_1.aac", "https://cdn-i.pr.trt.com.tr/trtdinle/w480/h360/q70/12467415.jpeg"),
        LiveChannel("TRT FM", "https://trt.radyotvonline.net/trt_fm.aac", "https://cdn-i.pr.trt.com.tr/trtdinle/w480/h360/q70/12467418.jpeg"),
        LiveChannel("TRT Nağme", "https://rd-trtnagme.medya.trt.com.tr/master_128.m3u8", "https://cdn-i.pr.trt.com.tr/trtdinle/w480/h360/q70/12467465.jpeg"),
        LiveChannel("TRT Türkü", "https://rd-trtturku.medya.trt.com.tr/master_128.m3u8", "https://cdn-i.pr.trt.com.tr/trtdinle/w480/h360/q70/12467466.jpeg")
    )

    private suspend fun getTrtSeries(archive: Boolean, page: Int): List<SearchResponse> {
        return try {
            val url = if (page == 1) "https://www.trt1.com.tr/diziler?archive=$archive"
            else "https://www.trt1.com.tr/diziler/$page?archive=$archive"
            val doc = app.get(url).document
            doc.select("div.grid_grid-wrapper__elAnh > div.h-full.w-full > a").mapNotNull { el ->
                val title = el.selectFirst("div.card_card-title__IJ9af")?.text()?.trim() ?: return@mapNotNull null
                val href = el.attr("href")
                var poster = el.selectFirst("img")?.absUrl("src")
                    ?.replace(Regex("webp/w\\d+/h\\d+"), "webp/w600/h338")
                    ?.replace("/q75/", "/q85/")
                newTvSeriesSearchResponse(title, "https://www.trt1.com.tr$href", TvType.TvSeries) { this.posterUrl = poster }
            }
        } catch (e: Exception) { emptyList() }
    }

    override suspend fun load(url: String): LoadResponse {
        val channel = channels.find { url.contains(it.baseUrl, ignoreCase = true) } ?: channels[0]

        if (channel.isTrt) {
            return loadTrtSeries(url)
        }

        val doc = app.get(url).document
        val title = doc.selectFirst("h1, .seo-h1, meta[property='og:title']")
            ?.let { if (it.tagName() == "meta") it.attr("content") else it.text() } ?: "Bilinmeyen"

        val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
        val plot = doc.selectFirst(".description, meta[name='description']")?.attr("content")

        val episodes = doc.select("a[href*='/bolum/'], a[href*='/video/']").mapNotNull { el ->
            val href = el.attr("href").let { if (it.startsWith("http")) it else channel.baseUrl.removeSuffix("/") + it }
            if (!href.contains("/bolum/") && !href.contains("/video/")) return@mapNotNull null
            newEpisode(href) { name = el.text().trim().ifBlank { "Bölüm" } }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    private suspend fun loadTrtSeries(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1")?.text()?.trim() ?: "TRT Dizi"
        val poster = doc.selectFirst("picture.card_card-image__T64bP img")?.absUrl("src")
            ?.replace(Regex("webp/w\\d+/h\\d+"), "webp/w600/h338")?.replace("/q75/", "/q85/")

        val episodes = mutableListOf<Episode>()
        var page = 1
        while (page <= 20) {
            val epUrl = if (page == 1) url.replace(Regex("/[^/]+$"), "/bolum")
            else "$url/bolum/$page"
            try {
                val epDoc = app.get(epUrl).document
                val cards = epDoc.select("div.grid_grid-wrapper__elAnh > div.h-full.w-full > a")
                if (cards.isEmpty()) break
                cards.forEach { el ->
                    val epTitle = el.selectFirst("div.card_card-title__IJ9af")?.text()?.trim() ?: "Bölüm"
                    val href = el.attr("href")
                    val img = el.selectFirst("img")?.absUrl("src")
                        ?.replace(Regex("webp/w\\d+/h\\d+"), "webp/w600/h338")?.replace("/q75/", "/q85/")
                    episodes += newEpisode("https://www.trt1.com.tr$href") {
                        name = epTitle
                        posterUrl = img
                    }
                }
                page++
            } catch (e: Exception) { break }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        if (data.contains(".m3u8")) {
            M3u8Helper.generateM3u8(name, data, "https://www.trt1.com.tr").forEach(callback)
            return true
        }

        if (data.contains("youtube.com") || data.contains("youtu.be")) {
            loadExtractor(data, "", subtitleCallback, callback)
            return true
        }

        val doc = app.get(data).document
        doc.select("iframe[src*='player'], script").forEach { el ->
            if (el.tagName() == "iframe") {
                val src = el.attr("src")
                if (src.isNotBlank()) loadExtractor(fixUrl(src), data, subtitleCallback, callback)
            } else {
                val script = el.html()
                Regex("""["']file["']\s*:\s*["']([^"']+)["']""").find(script)?.groupValues?.get(1)?.let { url ->
                    callback(newExtractorLink(name, "Direkt", url, data, Qualities.Unknown.value, url.contains(".m3u8")))
                }
            }
        }
        return true
    }

    private fun fixUrl(url: String): String = if (url.startsWith("http")) url else "https:$url"
}
