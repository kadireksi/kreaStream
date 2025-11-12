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
        "series"  to "Güncel Diziler",
        "archive" to "Eski Diziler",
        "live" to "TRT Canlı TV & Radyo"
    )

    data class TvChannel(
        val name: String,
        val slug: String,
        val streamUrl: String,
        val logoUrl: String,
        val description: String = ""
    )

     data class RadioChannel(
        val name: String,
        val slug: String,
        val streamUrl: String,
        val logoUrl: String,
        val description: String = ""
    )

    private suspend fun getTvChannels(): List<TvChannel> {
        val result = mutableListOf<TvChannel>()
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
                if(!name.contains("tabii")) {
                    result += TvChannel(name, slug, streamUrl, logoUrl, "$name")
                } 
            }
        } catch (e: Exception) {}

        return result
    }

private suspend fun getRadioChannels(): List<RadioChannel> {
    val result = mutableListOf<RadioChannel>()
    try {
        val response = app.get("https://www.trtdinle.com/radyolar", timeout = 15)
        val document = response.document

        // Try to extract from the script content
        val scriptContent = document.select("script:containsData(window.__NUXT__)").firstOrNull()?.data()
            ?: document.select("script").find { it.html().contains("window.__NUXT__") }?.html()
            ?: return getFallbackRadioChannels()

        // Extract JSON part
        val jsonStr = scriptContent.substringAfter("window.__NUXT__=").substringBefore(";</script>")
        
        // Use Cloudstream3's json helper
        val json = AppUtils.tryParseJson<Map<String, Any>>(jsonStr) ?: return getFallbackRadioChannels()
        
        // Navigate to channels data - this structure might need adjustment based on actual JSON
        val data = (json["data"] as? List<Map<String, Any>>)?.firstOrNull() ?: return getFallbackRadioChannels()
        val channels = data["channels"] as? List<Map<String, Any>> ?: return getFallbackRadioChannels()

        for (channel in channels) {
            try {
                val title = channel["title"] as? String ?: continue
                val audio = channel["audio"] as? String ?: continue
                val imageUrl = channel["imageUrl"] as? String ?: ""
                val description = channel["description"] as? String ?: ""

                result += RadioChannel(
                    name = title,
                    slug = title.lowercase().replace(" ", "-"),
                    streamUrl = audio,
                    logoUrl = imageUrl,
                    description = description
                )
            } catch (e: Exception) {
                // Skip invalid channels
                continue
            }
        }

    } catch (e: Exception) {
        e.printStackTrace()
    }

    // If no channels found, use fallback
    return if (result.isEmpty()) getFallbackRadioChannels() else result
}

private fun findChannelsInJson(json: JSONObject): JSONArray? {
    return try {
        when {
            json.has("channels") -> json.getJSONArray("channels")
            json.has("data") -> {
                val data = json.getJSONArray("data")
                if (data.length() > 0) {
                    findChannelsInJson(data.getJSONObject(0))
                } else null
            }
            json.has("fetch") -> findChannelsInJson(json.getJSONObject("fetch"))
            else -> {
                // Recursively search through all keys
                for (key in json.keys()) {
                    val value = json.get(key)
                    if (value is JSONObject) {
                        findChannelsInJson(value)?.let { return it }
                    } else if (value is JSONArray && value.length() > 0) {
                        val firstItem = value.get(0)
                        if (firstItem is JSONObject && firstItem.has("title") && firstItem.has("audio")) {
                            return value
                        }
                    }
                }
                null
            }
        }
    } catch (e: Exception) {
        null
    }
}

private fun parseChannel(channel: JSONObject): RadioChannel? {
    return try {
        val title = channel.optString("title", "")
        val audioUrl = channel.optString("audio", "")
        val imageUrl = channel.optString("imageUrl", "") ?: channel.optString("featuredImage", "")
        val description = channel.optString("description", "")
        
        if (audioUrl.isNotBlank() && title.isNotBlank()) {
            val slug = title.lowercase()
                .replace(" ", "-")
                .replace("[^a-z0-9-]".toRegex(), "")
            
            RadioChannel(
                name = title,
                slug = slug,
                streamUrl = audioUrl,
                logoUrl = imageUrl,
                description = description
            )
        } else {
            null
        }
    } catch (e: Exception) {
        null
    }
}

private fun getFallbackRadioChannels(): List<RadioChannel> {
    return listOf(
        RadioChannel(
            name = "TRT FM",
            slug = "trt-fm",
            streamUrl = "https://radio-trt-fm.medya.trt.com.tr/master.m3u8",
            logoUrl = "https://cdn-i.pr.trt.com.tr/trtdinle/f_channel_b9f3c65ea803a398ff11f759fb5b59bc.jpeg",
            description = "Türkçe Pop"
        ),
        RadioChannel(
            name = "TRT Radyo 1",
            slug = "trt-radyo-1",
            streamUrl = "https://trt.radyotvonline.net/trt_1.aac",
            logoUrl = "https://cdn-i.pr.trt.com.tr/trtdinle/12467415.jpeg",
            description = "Haber ve Kültür"
        ),
        RadioChannel(
            name = "TRT Türkü",
            slug = "trt-turku",
            streamUrl = "https://rd-trtturku.medya.trt.com.tr/master.m3u8",
            logoUrl = "https://cdn-i.pr.trt.com.tr/trtdinle/12467466.jpeg",
            description = "Türk Halk Müziği"
        ),
        RadioChannel(
            name = "TRT Nağme",
            slug = "trt-nagme",
            streamUrl = "https://rd-trtnagme.medya.trt.com.tr/master.m3u8",
            logoUrl = "https://cdn-i.pr.trt.com.tr/trtdinle/12467465.jpeg",
            description = "Türk Sanat Müziği"
        ),
        RadioChannel(
            name = "TRT Radyo Haber",
            slug = "trt-radyo-haber",
            streamUrl = "https://trt.radyotvonline.net/trt_haber.aac",
            logoUrl = "https://cdn-i.pr.trt.com.tr/trtdinle/12530424_0-0-2048-1536.jpeg",
            description = "Sürekli Haber"
        )
    )
}

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

    override suspend fun load(url: String): LoadResponse {
        // TV
        if (url == dummyTvUrl) {
            val channels = getTvChannels()
            return if (channels.isEmpty()) {
                buildLiveTVResponse(
                    listOf(
                        TvChannel(
                            name = "TRT 1",
                            slug = "trt1",
                            streamUrl = "https://tv-trt1.medya.trt.com.tr/master.m3u8",
                            logoUrl = "https://upload.wikimedia.org/wikipedia/tr/6/67/TRT_1_logo.png",
                            description = "TRT 1"
                        )
                    )
                )
            } else {
                buildLiveTVResponse(channels)
            }
        }

        // Radio
        if (url == dummyRadioUrl) {
            val channels = getRadioChannels()
            return if (channels.isEmpty()) {
                buildLiveRadioResponse(
                    listOf(
                        RadioChannel(
                            name = "TRT FM",
                            slug = "trt-fm",
                            streamUrl = "https://radio-trt-fm.medya.trt.com.tr/master.m3u8",
                            logoUrl = "https://cdn-i.pr.trt.com.tr/trtdinle/f_channel_b9f3c65ea803a398ff11f759fb5b59bc.jpeg",
                            description = "TRT FM"
                        )
                    )
                )
            } else {
                buildLiveRadioResponse(channels)
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

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        } catch (e: Exception) {
            throw ErrorLoadingException("Dizi yüklenemedi")
        }
    }

    private suspend fun buildLiveTVResponse(channels: List<TvChannel>): LoadResponse {
        val episodes = channels.mapIndexed { i, ch ->
            newEpisode(ch.streamUrl) {
                name = ch.name
                posterUrl = ch.logoUrl
                episode = i + 1
                season = 1
                description = ch.description
            }
        }

        return newTvSeriesLoadResponse("TRT Tv", dummyTvUrl, TvType.TvSeries, episodes) {
            this.posterUrl = "https://kariyer.trt.net.tr/wp-content/uploads/2022/01/trt-kariyer-logo.png"
            this.plot = "TRT TV canlı yayın"
        }
    }

    private suspend fun buildLiveRadioResponse(channels: List<RadioChannel>): LoadResponse {
        val episodes = channels.mapIndexed { i, ch ->
            newEpisode(ch.streamUrl) {
                name = ch.name
                posterUrl = ch.logoUrl
                episode = i + 1
                season = 1
                description = ch.description
            }
        }

        return newTvSeriesLoadResponse("TRT Radyo", dummyRadioUrl, TvType.TvSeries, episodes) {
            this.posterUrl = "https://kariyer.trt.net.tr/wp-content/uploads/2022/01/trt-kariyer-logo.png"
            this.plot = "TRT Radyo canlı yayın"
        }
    }

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