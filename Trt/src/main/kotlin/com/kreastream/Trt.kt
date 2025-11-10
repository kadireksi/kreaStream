package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap

class Trt : MainAPI() {
    override var mainUrl = "https://www.tabii.com"
    override var name = "TRT"
    override val supportedTypes = setOf(TvType.Live, TvType.TvSeries)
    override var lang = "tr"
    override var hasMainPage = true

    private val tabiiUrl = "https://www.tabii.com/tr"
    private val trt1Url   = "https://www.trt1.com.tr"
    private val liveBase  = "$tabiiUrl/watch/live"

    private val dummyLiveUrl = tabiiUrl  // "https://www.tabii.com/tr"

    private val channelCache = ConcurrentHashMap<String, List<TabiiChannel>>()

    override val mainPage = mainPageOf(
        "live"    to "TRT Canlı",
        "series"  to "Güncel Diziler",
        "archive" to "Eski Diziler"
    )

    data class TabiiChannel(
        val name: String,
        val slug: String,
        val streamUrl: String,
        val logoUrl: String,
        val description: String = ""
    )

    /* ---------------------------------------------------------
       1. Get channel list – robust
       --------------------------------------------------------- */
    private suspend fun getAllLiveChannels(): List<Pair<String, String>> = try {
        val sample = "$liveBase/trt1?trackId=150002"
        val response = app.get(sample, timeout = 10)
        if (!response.isSuccessful) {
            return emptyList()
        }

        val doc = response.document
        val script = doc.select("script")
            .find { it.html().contains("channels") && it.html().contains("slug") }
            ?: return emptyList()

        val html = script.html()
        val json = Regex("""channels:\s*\[(.*?)\]""")
            .find(html)?.groupValues?.get(1)
            ?: Regex("""["']channels["']\s*:\s*\[(.*?)\]""")
                .find(html)?.groupValues?.get(1)
            ?: return emptyList()

        Regex("""\{"name":"([^"]+)","slug":"([^"]+)""")
            .findAll(json)
            .map { it.groupValues[1] to it.groupValues[2] }
            .toList()
            .takeIf { it.isNotEmpty() } ?: emptyList()
    } catch (e: Exception) { emptyList() }

    /* ---------------------------------------------------------
       2. Scrape per channel – with fallback logos
       --------------------------------------------------------- */
    private suspend fun getTabiiChannels(): List<TabiiChannel> {
        channelCache["live"]?.let { return it }

        val pairs = getAllLiveChannels()
        if (pairs.isEmpty()) return emptyList()

        val result = mutableListOf<TabiiChannel>()
        for ((name, slug) in pairs) {
            try {
                val url = "$liveBase/$slug?trackId=150002"
                val doc = app.get(url, timeout = 10).document

                var logo = doc.selectFirst("img.channel-logo")?.absUrl("src")
                    ?: doc.selectFirst("img[alt*='$name']")?.absUrl("src")

                if (logo.isNullOrBlank()) {
                    logo = when {
                        name.contains("TRT 1", true) -> "https://upload.wikimedia.org/wikipedia/tr/6/67/TRT_1_logo.png"
                        name.contains("TRT Haber", true) -> "https://upload.wikimedia.org/wikipedia/tr/6/6e/TRT_Haber_logo.png"
                        name.contains("TRT Spor", true) -> "https://upload.wikimedia.org/wikipedia/tr/8/8f/TRT_Spor_logo.png"
                        name.contains("TRT Çocuk", true) -> "https://upload.wikimedia.org/wikipedia/tr/7/7e/TRT_%C3%87ocuk_logo.png"
                        else -> "https://www.trt.net.tr/logos/our-logos/corporate/trt.png"
                    }
                }

                val script = doc.select("script")
                    .find { it.html().contains("playerConfig") }
                    ?.html()
                val stream = script?.let {
                    Regex("""streamUrl["']?\s*:\s*["']([^"']+\.m3u8[^"']*)["']""")
                        .find(it)?.groupValues?.get(1)
                }

                if (!stream.isNullOrBlank()) {
                    result += TabiiChannel(name, slug, stream, logo, "$name canlı yayın")
                }
                delay(120)
            } catch (e: Exception) { /* skip */ }
        }

        if (result.isNotEmpty()) channelCache["live"] = result
        return result
    }

    /* ---------------------------------------------------------
       3. Quality variants
       --------------------------------------------------------- */
    private fun generateQualityVariants(base: String): List<String> {
        val list = mutableListOf(base)
        try {
            if (base.contains("medya.trt.com.tr")) {
                val prefix = base.substringBeforeLast("/").removeSuffix("_master")
                listOf("360", "480", "720", "1080").forEach { q ->
                    list += "$prefix" + "_$q.m3u8"
                }
            }
        } catch (_: Exception) {}
        return list.distinct()
    }

    /* ---------------------------------------------------------
       4. Series list
       --------------------------------------------------------- */
    private suspend fun getTrtSeries(archive: Boolean = false, page: Int = 1): List<SearchResponse> = try {
        val url = if (page == 1) {
            "$trt1Url/diziler?archive=$archive&order=title_asc"
        } else {
            "$trt1Url/diziler/$page?archive=$archive&order=title_asc"
        }

        app.get(url, timeout = 15).document
            .select("div.grid_grid-wrapper__elAnh > div.h-full.w-full > a")
            .mapNotNull { el ->
                val title = el.selectFirst("div.card_card-title__IJ9af")?.text()?.trim()
                    ?: return@mapNotNull null
                val href = el.attr("href")
                var poster = el.selectFirst("img")?.absUrl("src")
                poster = poster?.replace(Regex("webp/w\\d+/h\\d+"), "webp/w600/h338")
                    ?.replace("/q75/", "/q85/")

                newTvSeriesSearchResponse(title, fixTrtUrl(href)) {
                    this.posterUrl = poster
                }
            }
    } catch (e: Exception) { emptyList() }

    private fun fixTrtUrl(url: String): String =
        if (url.startsWith("http")) url else "$trt1Url$url"

    /* ---------------------------------------------------------
       5. Main page
       --------------------------------------------------------- */
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = when (request.data) {
            "live" -> listOf(
                newTvSeriesSearchResponse(
                    name = "TRT Canlı",
                    url = dummyLiveUrl,
                    type = TvType.TvSeries
                ) {
                    this.posterUrl = "https://www.trt.net.tr/logos/our-logos/corporate/trt.png"
                }
            )
            "series"  -> getTrtSeries(archive = false, page = page)
            "archive" -> getTrtSeries(archive = true,  page = page)
            else -> emptyList()
        }

        val hasNext = request.data in listOf("series", "archive") && items.isNotEmpty()

        return newHomePageResponse(
            listOf(HomePageList(request.name, items, true)),
            hasNext = hasNext
        )
    }

    /* ---------------------------------------------------------
       6. Load – intercept homepage → build live list
       --------------------------------------------------------- */
    override suspend fun load(url: String): LoadResponse {
        // LIVE SERIES
        if (url == dummyLiveUrl) {
            val channels = getTabiiChannels()
            return if (channels.isEmpty()) {
                // Fallback: TRT 1
                buildLiveResponse(
                    listOf(
                        TabiiChannel(
                            name = "TRT 1",
                            slug = "trt1",
  streamUrl = "https://tv-trt1.medya.trt.com.tr/master.m3u8",
                            logoUrl = "https://upload.wikimedia.org/wikipedia/tr/6/67/TRT_1_logo.png",
                            description = "TRT 1 canlı yayın"
                        )
                    )
                )
            } else {
                buildLiveResponse(channels)
            }
        }

        // Direct m3u8
        if (url.contains(".m3u8", ignoreCase = true)) {
            return newMovieLoadResponse("TRT Canlı", url, TvType.Live, url) {
                this.posterUrl = "https://www.trt.net.tr/logos/our-logos/corporate/trt.png"
            }
        }

        // Normal series
        return try {
            val doc = app.get(url, timeout = 15).document
            val title = doc.selectFirst("h1")?.text()?.trim()
                ?: throw ErrorLoadingException("Başlık bulunamadı")
            val plot = doc.selectFirst("meta[name=description]")?.attr("content") ?: ""
            var poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            poster = poster?.replace(Regex("webp/w\\d+/h\\d+"), "webp/w600/h338")
                ?.replace("/q75/", "/q85/")

            val seriesSlug = url.removePrefix("$trt1Url/diziler/").substringBefore("/")
            val episodes = mutableListOf<Episode>()
            var pageNum = 1
            var more = true

            while (more && pageNum <= 30) {
                try {
                    val epUrl = if (pageNum == 1) {
                        "$trt1Url/diziler/$seriesSlug/bolum"
                    } else {
                        "$trt1Url/diziler/$seriesSlug/bolum/$pageNum"
                    }
                    val epDoc = app.get(epUrl, timeout = 10).document
                    val pageEps = epDoc.select("div.grid_grid-wrapper__elAnh > div.h-full.w-full > a")
                        .mapNotNull { el ->
                            val epTitle = el.selectFirst("div.card_card-title__IJ9af")?.text()?.trim()
                                ?: return@mapNotNull null
                            val href = el.attr("href")
                            var img = el.selectFirst("img")?.absUrl("src")
                            img = img?.replace(Regex("webp/w\\d+/h\\d+"), "webp/w600/h338")
                                ?.replace("/q75/", "/q85/")
                            val desc = el.selectFirst("p.card_card-description__0PSTi")?.text()?.trim() ?: ""
                            val epNum = epTitle.replace(Regex("[^0-9]"), "").toIntOrNull() ?: pageNum

                            newEpisode(fixTrtUrl(href)) {
                                name = epTitle
                                posterUrl = img
                                episode = epNum
                                description = desc
                            }
                        }

                    if (pageEps.isNotEmpty()) {
                        episodes += pageEps
                        pageNum++
                        delay(100)
                    } else more = false
                } catch (e: Exception) { more = false }
            }

            newTvSeriesLoadResponse(
                name = title,
                url = url,
                type = TvType.TvSeries,
                episodes = episodes
            ) {
                this.posterUrl = poster
                this.plot = plot
            }
        } catch (e: Exception) {
            throw ErrorLoadingException("Dizi yüklenemedi")
        }
    }

    // SUSPEND FUNCTION – fixes build error
    private suspend fun buildLiveResponse(channels: List<TabiiChannel>): LoadResponse {
        val episodes = channels.mapIndexed { i, ch ->
            newEpisode(ch.streamUrl) {
                name = ch.name
                posterUrl = ch.logoUrl
                episode = i + 1
                season = 1
                description = ch.description
            }
        }

        return newTvSeriesLoadResponse(
            name = "TRT Canlı Yayınlar",
            url = dummyLiveUrl,
            type = TvType.TvSeries,
            episodes = episodes
        ) {
            this.posterUrl = "https://www.trt.net.tr/images/trt-logo.png"
            this.plot = "Tüm TRT kanalları canlı yayın – Tabii"
        }
    }

    /* ---------------------------------------------------------
       7. Load links
       --------------------------------------------------------- */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.contains(".m3u8", ignoreCase = true)) {
            generateQualityVariants(data).forEach { u ->
                M3u8Helper.generateM3u8(
                    source = name,
                    streamUrl = u,
                    referer = tabiiUrl,
                    headers = mapOf("User-Agent" to "Mozilla/5.0", "Referer" to tabiiUrl)
                ).forEach(callback)
            }
            return true
        }

        try {
            val doc = app.get(data, timeout = 10).document
            val script = doc.select("script")
                .find { it.html().contains("playerConfig") }
                ?.html()
            val m3u8 = script?.let {
                Regex("""streamUrl["']?\s*:\s*["']([^"']+\.m3u8[^"']*)["']""")
                    .find(it)?.groupValues?.get(1)
            }

            if (m3u8 != null) {
                generateQualityVariants(m3u8).forEach { u ->
                    M3u8Helper.generateM3u8(
                        source = name,
                        streamUrl = u,
                        referer = trt1Url,
                        headers = mapOf("Referer" to trt1Url)
                    ).forEach(callback)
                }
                return true
            }

            val yt = doc.selectFirst("iframe[src*='youtube.com/embed']")
                ?.attr("src")
                ?.let { "https://www.youtube.com/watch?v=${it.substringAfter("embed/").substringBefore("?")}" }
                ?: Regex("""https://www\.youtube\.com/watch\?v=([a-zA-Z0-9_-]+)""")
                    .find(doc.html())?.groupValues?.get(1)
                    ?.let { "https://www.youtube.com/watch?v=$it" }

            if (yt != null) {
                loadExtractor(yt, tabiiUrl, subtitleCallback, callback)
                return true
            }
        } catch (e: Exception) {}

        return false
    }

    /* ---------------------------------------------------------
       8. Search
       --------------------------------------------------------- */
    override suspend fun search(query: String): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()

        getTabiiChannels()
            .filter { it.name.contains(query, ignoreCase = true) }
            .forEach { ch ->
                out += newMovieSearchResponse(ch.name, ch.streamUrl, TvType.Live) {
                    this.posterUrl = ch.logoUrl
                }
            }

        try {
            val sUrl = "$trt1Url/arama/$query?contenttype=series"
            app.get(sUrl, timeout = 10).document
                .select("div.grid_grid-wrapper__elAnh > div.h-full.w-full > a")
                .mapNotNull { el ->
                    val title = el.selectFirst("div.card_card-title__IJ9af")?.text()?.trim()
                        ?: return@mapNotNull null
                    val href = el.attr("href")
                    if (!href.contains("/diziler/")) return@mapNotNull null
                    var poster = el.selectFirst("img")?.absUrl("src")
                    poster = poster?.replace(Regex("webp/w\\d+/h\\d+"), "webp/w600/h338")
                        ?.replace("/q75/", "/q85/")

                    out += newTvSeriesSearchResponse(title, fixTrtUrl(href)) {
                        this.posterUrl = poster
                    }
                }
        } catch (_: Exception) {}

        return out
    }
}