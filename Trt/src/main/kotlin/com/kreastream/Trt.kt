package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
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

    private val dummyTvUrl = tabiiUrl
    private val dummyRadioUrl = "https://www.trtdinle.com/radyolar"

    override val mainPage = mainPageOf(
        "live" to "TRT Canlı Yayınlar",
        //"radio" to "TRT Radyo",
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
       Get TV channels from Tabii JSON
       --------------------------------------------------------- */
    private suspend fun getTvChannels(): List<TabiiChannel> {
        val result = mutableListOf<TabiiChannel>()
        try {
            val sample = "$liveBase/trt1?trackId=150002"
            val response = app.get(sample)
            val doc = response.document
            val nextData = doc.selectFirst("#__NEXT_DATA__")?.data() ?: return emptyList()

            val json = JSONObject(nextData)
            val liveChannels = json.getJSONObject("props").getJSONObject("pageProps").getJSONArray("liveChannels")

            for (i in 0 until liveChannels.length()) {
                val ch = liveChannels.getJSONObject(i)
                val name = ch.getString("title")
                val slug = ch.getString("slug")

                // Logo
                var logoUrl = ""
                val images = ch.getJSONArray("images")
                for (j in 0 until images.length()) {
                    val img = images.getJSONObject(j)
                    if (img.getString("imageType") == "logo") {
                        val imgName = img.getString("name")
                        logoUrl = "https://cms-tabii-public-image.tabii.com/int/$imgName"
                        break
                    }
                }
                if (logoUrl.isBlank()) continue

                // Stream
                var streamUrl = ""
                val media = ch.getJSONArray("media")
                for (j in 0 until media.length()) {
                    val m = media.getJSONObject(j)
                    if (m.getString("type") == "hls" && m.getString("drmSchema") == "clear") {
                        streamUrl = m.getString("url")
                        break
                    }
                }
                if (streamUrl.isBlank()) continue

                result += TabiiChannel(name, slug, streamUrl, logoUrl, "$name")
            }
        } catch (e: Exception) {}

        return result
    }

    /* ---------------------------------------------------------
    Get Radio channels from __NUXT__ JSON
    --------------------------------------------------------- */
    private suspend fun getRadioChannels(): List<TabiiChannel> {
        val result = mutableListOf<TabiiChannel>()
        try {
            val response = app.get("https://www.trtdinle.com/radyolar", timeout = 15)
            val html = response.text

            // Extract the window.__NUXT__ object more reliably
            val nuxtRegex = Regex("""window\.__NUXT__\s*=\s*(\{.*?\});""")
            val nuxtMatch = nuxtRegex.find(html) ?: return emptyList()
            
            val nuxtJsonStr = nuxtMatch.groupValues[1]
            val json = JSONObject(nuxtJsonStr)

            // Navigate through the JSON structure to find channels
            val fetchData = json.optJSONObject("fetch")
            if (fetchData != null) {
                // Look for the data key containing channels
                val dataKeys = fetchData.keys()
                while (dataKeys.hasNext()) {
                    val key = dataKeys.next()
                    if (key.contains("data-v-")) {
                        val pageData = fetchData.getJSONObject(key)
                        
                        // Parse channels from different possible locations
                        parseChannelsFromData(pageData, result)
                    }
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
        return result.distinctBy { it.name } // Remove duplicates
    }

    private fun parseChannelsFromData(pageData: JSONObject, result: MutableList<TabiiChannel>) {
        try {
            // Parse channels array
            if (pageData.has("channels")) {
                val channelsArray = pageData.getJSONArray("channels")
                for (i in 0 until channelsArray.length()) {
                    parseSingleChannel(channelsArray.getJSONObject(i), result)
                }
            }
            
            // Parse groupContent
            if (pageData.has("groupContent")) {
                val groupContent = pageData.getJSONObject("groupContent")
                val groupKeys = groupContent.keys()
                while (groupKeys.hasNext()) {
                    val groupKey = groupKeys.next()
                    val groupArray = groupContent.getJSONArray(groupKey)
                    for (i in 0 until groupArray.length()) {
                        parseSingleChannel(groupArray.getJSONObject(i), result)
                    }
                }
            }
            
            // Parse noGroupContent
            if (pageData.has("noGroupContent")) {
                val noGroupArray = pageData.getJSONArray("noGroupContent")
                for (i in 0 until noGroupArray.length()) {
                    parseSingleChannel(noGroupArray.getJSONObject(i), result)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun parseSingleChannel(channel: JSONObject, result: MutableList<TabiiChannel>) {
        try {
            val name = channel.optString("title", "").takeIf { it.isNotBlank() } ?: return
            val audio = channel.optString("audio", "").takeIf { it.isNotBlank() } ?: return
            
            // Get cover image - try different possible fields
            var cover = channel.optString("cover", "")
            if (cover.isBlank()) {
                cover = channel.optString("imageUrl", "")
            }
            if (cover.isBlank()) {
                cover = channel.optString("featuredImage", "")
            }
            
            if (cover.isBlank()) return

            // Extract slug from path or generate from title
            val path = channel.optString("path", "")
            val slug = if (path.isNotBlank()) {
                path.substringAfterLast("/").ifBlank { 
                    name.lowercase().replace(" ", "-").replace("[^a-z0-9-]".toRegex(), "")
                }
            } else {
                name.lowercase().replace(" ", "-").replace("[^a-z0-9-]".toRegex(), "")
            }

            val description = channel.optString("description", name)

            result.add(
                TabiiChannel(
                    name = name,
                    slug = slug,
                    streamUrl = audio,
                    logoUrl = cover,
                    description = "$description - TRT Radyo"
                )
            )
        } catch (e: Exception) {
            // Skip this channel if there's any parsing error
        }
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
    private suspend fun getTrtSeries(archive: Boolean = false, page: Int = 1): List<SearchResponse> {
        return try {
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
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun fixTrtUrl(url: String): String = if (url.startsWith("http")) url else "$trt1Url$url"

    /* ---------------------------------------------------------
       5. Main page
       --------------------------------------------------------- */
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = when (request.data) {
            "live" -> listOf(
                newTvSeriesSearchResponse(
                    name = "TRT TV",
                    url = dummyTvUrl,
                    type = TvType.TvSeries
                ) {
                    this.posterUrl = "https://kariyer.trt.net.tr/wp-content/uploads/2022/01/trt-kariyer-logo.png"
                },
                 newTvSeriesSearchResponse(
                    name = "TRT Radyo",
                    url = dummyRadioUrl,
                    type = TvType.TvSeries
                ) {
                    this.posterUrl = "https://cdn-i.pr.trt.com.tr/trtdinle//w480/h480/q70/12467415.jpeg"
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
       6. Load – intercept dummy URLs for TV and Radio
       --------------------------------------------------------- */
    override suspend fun load(url: String): LoadResponse {
        // TV
        if (url == dummyTvUrl) {
            val channels = getTvChannels()
            return if (channels.isEmpty()) {
                buildLiveResponse(
                    listOf(
                        TabiiChannel(
                            name = "TRT 1",
                            slug = "trt1",
                            streamUrl = "https://tv-trt1.medya.trt.com.tr/master.m3u8",
                            logoUrl = "https://upload.wikimedia.org/wikipedia/tr/6/67/TRT_1_logo.png",
                            description = "TRT 1"
                        )
                    )
                )
            } else {
                buildLiveResponse(channels)
            }
        }

        // Radio
        if (url == dummyRadioUrl) {
            val channels = getRadioChannels()
            return if (channels.isEmpty()) {
                buildLiveResponse(
                    listOf(
                        TabiiChannel(
                            name = "TRT FM",
                            slug = "trt-fm",
                            streamUrl = "https://radio-trt-fm.medya.trt.com.tr/master.m3u8",
                            logoUrl = "https://cdn-i.pr.trt.com.tr/trtdinle/f_channel_b9f3c65ea803a398ff11f759fb5b59bc.jpeg",
                            description = "TRT FM"
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
                this.posterUrl = "https://kariyer.trt.net.tr/wp-content/uploads/2022/01/trt-kariyer-logo.png"
            }
        }

        // Series
        try {
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

            return newTvSeriesLoadResponse(
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
            url = dummyTvUrl,
            type = TvType.TvSeries,
            episodes = episodes
        ) {
            this.posterUrl = "https://kariyer.trt.net.tr/wp-content/uploads/2022/01/trt-kariyer-logo.png"
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

        getTvChannels()
            .filter { it.name.contains(query, ignoreCase = true) }
            .forEach { ch ->
                out += newMovieSearchResponse(ch.name, ch.streamUrl, TvType.Live) {
                    this.posterUrl = ch.logoUrl
                }
            }

        getRadioChannels()
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