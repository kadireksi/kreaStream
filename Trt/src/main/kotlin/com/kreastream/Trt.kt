package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import kotlinx.coroutines.delay

class Trt : MainAPI() {
    override var mainUrl = "https://www.tabii.com"
    override var name = "TRT"
    override val supportedTypes = setOf(TvType.Live, TvType.TvSeries)
    override var lang = "tr"
    override var hasMainPage = true

    private val tabiiUrl = "https://www.tabii.com/tr"
    private val trt1Url = "https://www.trt1.com.tr"
    private val liveBaseUrl = "$tabiiUrl/watch/live"

    override val mainPage = mainPageOf(
        "live" to "TRT Canlı",
        "series" to "Güncel Diziler",
        "archive" to "Eski Diziler"
    )

    data class TabiiChannel(
        val name: String,
        val slug: String,
        val streamUrl: String,
        val logoUrl: String,
        val description: String = ""
    )

    // Dynamically fetch all channels from JSON in any live page
    private suspend fun getAllLiveChannels(): List<Pair<String, String>> {
        return try {
            val sampleLiveUrl = "$liveBaseUrl/trt1?trackId=150002"
            val doc = app.get(sampleLiveUrl).document
            val script = doc.select("script").find { it.html().contains("windowObject") && it.html().contains("channels") }?.html()
                ?: return emptyList()

            // Extract JSON-like: "channels": [{"name":"TRT 1","slug":"trt1"}, ...]
            val jsonMatch = Regex("""["']channels["']\s*:\s*\[(.*?)\]""").find(script)
            val channelsJson = jsonMatch?.groupValues?.get(1) ?: return emptyList()

            Regex("""\{"name":"([^"]+)","slug":"([^"]+)""").findAll(channelsJson).map {
                it.groupValues[1] to it.groupValues[2]
            }.toList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun getTabiiChannels(): List<TabiiChannel> {
        val channelPairs = getAllLiveChannels()
        if (channelPairs.isEmpty()) return emptyList()

        val channels = mutableListOf<TabiiChannel>()

        for ((name, slug) in channelPairs) {
            try {
                val liveUrl = "$liveBaseUrl/$slug?trackId=150002"
                val doc = app.get(liveUrl).document

                // Logo: <img class="channel-logo">
                val logoUrl = doc.selectFirst("img.channel-logo")?.absUrl("src")
                    ?: doc.selectFirst("img[alt*='$name']")?.absUrl("src")
                    ?: ""

                // Stream: playerConfig.streamUrl
                val script = doc.select("script").find { it.html().contains("playerConfig") }?.html()
                val streamUrl = script?.let {
                    Regex("""["']?streamUrl["']?\s*:\s*["']([^"']+\.m3u8[^"']*)["']""").find(it)
                }?.groupValues?.get(1)

                if (!streamUrl.isNullOrBlank() && !logoUrl.isNullOrBlank()) {
                    channels.add(
                        TabiiChannel(
                            name = name,
                            slug = slug,
                            streamUrl = streamUrl,
                            logoUrl = logoUrl,
                            description = "$name canlı yayın"
                        )
                    )
                }

                delay(150) // Respectful scraping
            } catch (e: Exception) {
                // Skip broken channel
            }
        }

        return channels
    }

    private fun generateQualityVariants(baseUrl: String): List<String> {
        val variants = mutableListOf(baseUrl)
        try {
            if (baseUrl.contains("medya.trt.com.tr")) {
                val base = baseUrl.substringBeforeLast("/").removeSuffix("_master").substringBeforeLast("_")
                listOf("360", "480", "720", "1080").forEach { q ->
                    variants.add("$base_${q}.m3u8")
                }
            }
        } catch (_: Exception) {}
        return variants.distinct()
    }

    private suspend fun getTrtSeries(archive: Boolean = false, page: Int = 1): List<SearchResponse> {
        val url = if (page == 1) {
            "$trt1Url/diziler?archive=$archive&order=title_asc"
        } else {
            "$trt1Url/diziler/$page?archive=$archive&order=title_asc"
        }

        return app.get(url).document.select("div.grid_grid-wrapper__elAnh > div.h-full.w-full > a").mapNotNull { el ->
            val title = el.selectFirst("div.card_card-title__IJ9af")?.text()?.trim() ?: return@mapNotNull null
            val href = el.attr("href")
            var poster = el.selectFirst("img")?.absUrl("src")
            poster = poster?.replace(Regex("webp/w\\d+/h\\d+"), "webp/w600/h338")?.replace("/q75/", "/q85/")

            newTvSeriesSearchResponse(title, fixTrtUrl(href)) {
                this.posterUrl = poster
            }
        }
    }

    private fun fixTrtUrl(url: String): String = if (url.startsWith("http")) url else "$trt1Url$url"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = when (request.data) {
            "live" -> listOf(
                newTvSeriesSearchResponse("TRT Canlı", "trt://live", TvType.TvSeries) {
                    this.posterUrl = "https://www.trt.net.tr/images/trt-logo.png"
                }
            )
            "series" -> getTrtSeries(archive = false, page = page)
            "archive" -> getTrtSeries(archive = true, page = page)
            else -> emptyList()
        }

        val hasNext = request.data in listOf("series", "archive") && items.isNotEmpty()

        return newHomePageResponse(
            listOf(HomePageList(request.name, items, isHorizontal = true)),
            hasNext = hasNext
        )
    }

    override suspend fun load(url: String): LoadResponse {
        // === LIVE TV SERIES (All Channels as Episodes) ===
        if (url == "trt://live") {
            val channels = getTabiiChannels()
            val episodes = channels.mapIndexed { i, ch ->
                Episode(
                    name = ch.name,
                    data = ch.streamUrl,
                    posterUrl = ch.logoUrl,
                    episode = i + 1,
                    season = 1,
                    description = ch.description
                )
            }

            return newTvSeriesLoadResponse(
                title = "TRT Canlı Yayınlar",
                url = url,
                type = TvType.TvSeries,
                episodes = episodes
            ) {
                this.posterUrl = "https://www.trt.net.tr/images/trt-logo.png"
                this.plot = "Tüm TRT kanalları canlı yayın – Tabii"
            }
        }

        // === DIRECT M3U8 FALLBACK ===
        if (url.contains(".m3u8", true)) {
            val name = getAllLiveChannels().find { url.contains(it.second, true) }?.first ?: "TRT Canlı"
            return newMovieLoadResponse(name, url, TvType.Live, url) {
                this.posterUrl = "https://www.trt.net.tr/images/trt-logo.png"
            }
        }

        // === TV SERIES / EPISODE PAGE ===
        val doc = app.get(url).document
        val title = doc.selectFirst("h1")?.text()?.trim() ?: throw ErrorLoadingException("Title not found")
        val plot = doc.selectFirst("meta[name=description]")?.attr("content") ?: ""
        var poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        poster = poster?.replace(Regex("webp/w\\d+/h\\d+"), "webp/w600/h338")?.replace("/q75/", "/q85/")

        val seriesSlug = url.removePrefix("$trt1Url/diziler/").substringBefore("/")
        val episodes = mutableListOf<Episode>()
        var pageNum = 1
        var hasMore = true

        while (hasMore && pageNum <= 30) {
            try {
                val epUrl = if (pageNum == 1) "$trt1Url/diziler/$seriesSlug/bolum" else "$trt1Url/diziler/$seriesSlug/bolum/$pageNum"
                val epDoc = app.get(epUrl).document
                val pageEps = epDoc.select("div.grid_grid-wrapper__elAnh > div.h-full.w-full > a").mapNotNull { el ->
                    val epTitle = el.selectFirst("div.card_card-title__IJ9af")?.text()?.trim() ?: return@mapNotNull null
                    val href = el.attr("href")
                    var img = el.selectFirst("img")?.absUrl("src")
                    img = img?.replace(Regex("webp/w\\d+/h\\d+"), "webp/w600/h338")?.replace("/q75/", "/q85/")
                    val desc = el.selectFirst("p.card_card-description__0PSTi")?.text()?.trim() ?: ""
                    val epNum = epTitle.replace(Regex("[^0-9]"), "").toIntOrNull() ?: pageNum

                    newEpisode(fixTrtUrl(href)) {
                        this.name = epTitle
                        this.posterUrl = img
                        this.episode = epNum
                        this.description = desc
                    }
                }

                if (pageEps.isNotEmpty()) {
                    episodes.addAll(pageEps)
                    pageNum++
                    delay(100)
                } else {
                    hasMore = false
                }
            } catch (e: Exception) {
                hasMore = false
            }
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
        if (data.contains(".m3u8")) {
            generateQualityVariants(data).forEach { url ->
                M3u8Helper.generateM3u8(
                    source = name,
                    streamUrl = url,
                    referer = tabiiUrl,
                    headers = mapOf("User-Agent" to "Mozilla/5.0", "Referer" to tabiiUrl)
                ).forEach(callback)
            }
            return true
        }

        val doc = app.get(data).document
        val script = doc.select("script").find { it.html().contains("playerConfig") }?.html()
        val m3u8Url = script?.let {
            Regex("""["']?streamUrl["']?\s*:\s*["']([^"']+\.m3u8[^"']*)["']""").find(it)
        }?.groupValues?.get(1)

        if (m3u8Url != null) {
            generateQualityVariants(m3u8Url).forEach { url ->
                M3u8Helper.generateM3u8(name, url, tabiiUrl, headers = mapOf("Referer" to trt1Url)).forEach(callback)
            }
            return true
        }

        // YouTube fallback
        val ytUrl = doc.selectFirst("iframe[src*='youtube.com/embed']")?.attr("src")
            ?.let { "https://www.youtube.com/watch?v=${it.substringAfter("embed/").substringBefore("?")}" }
            ?: Regex("""https://www\.youtube\.com/watch\?v=([a-zA-Z0-9_-]+)""").find(doc.html())?.groupValues?.get(1)
                ?.let { "https://www.youtube.com/watch?v=$it" }

        if (ytUrl != null) {
            loadExtractor(ytUrl, tabiiUrl, subtitleCallback, callback)
            return true
        }

        return false
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()

        // Live channels
        getTabiiChannels().filter { it.name.contains(query, true) }.forEach { ch ->
            results.add(
                newMovieSearchResponse(ch.name, ch.streamUrl, TvType.Live) {
                    this.posterUrl = ch.logoUrl
                }
            )
        }

        // Series search
        try {
            val searchUrl = "$trt1Url/arama/$query?contenttype=series"
            app.get(searchUrl).document.select("div.grid_grid-wrapper__elAnh > div.h-full.w-full > a").mapNotNull { el ->
                val title = el.selectFirst("div.card_card-title__IJ9af")?.text()?.trim() ?: return@mapNotNull null
                val href = el.attr("href")
                if (!href.contains("/diziler/")) return@mapNotNull null
                var poster = el.selectFirst("img")?.absUrl("src")
                poster = poster?.replace(Regex("webp/w\\d+/h\\d+"), "webp/w600/h338")?.replace("/q75/", "/q85/")

                results.add(
                    newTvSeriesSearchResponse(title, fixTrtUrl(href)) { this.posterUrl = poster }
                )
            }
        } catch (_: Exception) {}

        return results
    }
}