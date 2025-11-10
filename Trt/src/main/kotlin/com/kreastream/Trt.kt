package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import kotlinx.coroutines.delay

class Trt : MainAPI() {
    override var mainUrl = "https://www.tabii.com"
    override var name = "TRT"
    override val supportedTypes = setOf(TvType.Live, TvType.TvSeries)
    override var lang = "tr"
    override var hasMainPage = true

    private val tabiiUrl = "https://www.tabii.com/tr"
    private val trt1Url   = "https://www.trt1.com.tr"
    private val liveBase  = "$tabiiUrl/watch/live"

    // Use REAL homepage as dummy – it exists, no 404
    private val dummyLiveUrl = tabiiUrl  // "https://www.tabii.com/tr"

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
       1. Get channel list from JSON on any live page
       --------------------------------------------------------- */
    private suspend fun getAllLiveChannels(): List<Pair<String, String>> = try {
        val sample = "$liveBase/trt1?trackId=150002"
        val doc = app.get(sample).document
        val script = doc.select("script")
            .find { it.html().contains("windowObject") && it.html().contains("channels") }
            ?.html() ?: return emptyList()

        val json = Regex("""["']channels["']\s*:\s*\[(.*?)\]""")
            .find(script)?.groupValues?.get(1) ?: return emptyList()

        Regex("""\{"name":"([^"]+)","slug":"([^"]+)""")
            .findAll(json)
            .map { it.groupValues[1] to it.groupValues[2] }
            .toList()
    } catch (e: Exception) { emptyList() }

    /* ---------------------------------------------------------
       2. Scrape logo + m3u8 per channel
       --------------------------------------------------------- */
    private suspend fun getTabiiChannels(): List<TabiiChannel> {
        val pairs = getAllLiveChannels()
        if (pairs.isEmpty()) return emptyList()

        val result = mutableListOf<TabiiChannel>()
        for ((name, slug) in pairs) {
            try {
                val url = "$liveBase/$slug?trackId=150002"
                val doc = app.get(url).document

                val logo = doc.selectFirst("img.channel-logo")?.absUrl("src")
                    ?: doc.selectFirst("img[alt*='$name']")?.absUrl("src")
                    ?: ""

                val script = doc.select("script")
                    .find { it.html().contains("playerConfig") }
                    ?.html()
                val stream = script?.let {
                    Regex("""["']?streamUrl["']?\s*:\s*["']([^"']+\.m3u8[^"']*)["']""")
                        .find(it)?.groupValues?.get(1)
                }

                if (!stream.isNullOrBlank() && logo.isNotBlank()) {
                    result += TabiiChannel(
                        name = name,
                        slug = slug,
                        streamUrl = stream,
                        logoUrl = logo,
                        description = "$name canlı yayın"
                    )
                }
                delay(150)
            } catch (e: Exception) { /* skip */ }
        }
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
                    list += "$prefix" + "_" + "$q.m3u8"
                }
            }
        } catch (_: Exception) {}
        return list.distinct()
    }

    /* ---------------------------------------------------------
       4. Series list
       --------------------------------------------------------- */
    private suspend fun getTrtSeries(archive: Boolean = false, page: Int = 1): List<SearchResponse> {
        val url = if (page == 1) {
            "$trt1Url/diziler?archive=$archive&order=title_asc"
        } else {
            "$trt1Url/diziler/$page?archive=$archive&order=title_asc"
        }

        return app.get(url).document
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
    }

    private fun fixTrtUrl(url: String): String =
        if (url.startsWith("http")) url else "$trt1Url$url"

    /* ---------------------------------------------------------
       5. Main page – use homepage as dummy URL
       --------------------------------------------------------- */
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = when (request.data) {
            "live" -> listOf(
                newTvSeriesSearchResponse(
                    name = "TRT Canlı",
                    url = dummyLiveUrl,           // https://www.tabii.com/tr
                    type = TvType.TvSeries
                ) {
                    this.posterUrl = "https://www.trt.net.tr/images/trt-logo.png"
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
       6. Load – intercept homepage URL → skip app.get()
       --------------------------------------------------------- */
    override suspend fun load(url: String): LoadResponse {
        // LIVE SERIES – intercept homepage
        if (url == dummyLiveUrl) {
            val channels = getTabiiChannels()
            if (channels.isEmpty()) {
                throw ErrorLoadingException("Canlı yayınlar yüklenemedi.")
            }

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
                url = url,
                type = TvType.TvSeries,
                episodes = episodes
            ) {
                this.posterUrl = "https://www.trt.net.tr/images/trt-logo.png"
                this.plot = "Tüm TRT kanalları canlı yayın – Tabii"
            }
        }

        // Direct m3u8
        if (url.contains(".m3u8", ignoreCase = true)) {
            val chanName = getAllLiveChannels()
                .find { url.contains(it.second, true) }?.first
                ?: "TRT Canlı"
            return newMovieLoadResponse(chanName, url, TvType.Live, url) {
                this.posterUrl = "https://www.trt.net.tr/images/trt-logo.png"
            }
        }

        // Normal series page
        val doc = app.get(url).document
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
                val epDoc = app.get(epUrl).document
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

        return newTvSeriesLoadResponse(
            name = title,
            url = url,
            type = TvType.TvSeries,
            episodes = episodes
        ) {
            this.posterUrl = poster
            this.plot = plot
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

        val doc = app.get(data).document
        val script = doc.select("script")
            .find { it.html().contains("playerConfig") }
            ?.html()
        val m3u8 = script?.let {
            Regex("""["']?streamUrl["']?\s*:\s*["']([^"']+\.m3u8[^"']*)["']""")
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
            app.get(sUrl).document
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