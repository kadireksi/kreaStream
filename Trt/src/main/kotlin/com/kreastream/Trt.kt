package com.kreastream

import kotlin.text.RegexOption
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.json.JSONArray
import kotlinx.coroutines.delay
import android.util.Log
import java.util.Locale


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
        "live" to "TRT Tv & Radyo"
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
            val response = app.get(
                dummyRadioUrl,
                timeout = 20,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                )
            )
            val html = response.text

            // Step 1: Extract the __NUXT__ script content (the big function call)
            val nuxtRegex = Regex("""window\.__NUXT__\s*=\s*\(function\([^)]*\)\s*\{[^}]+\}\)\s*\(\s*([^)]*)\s*\)\s*;\s*</script>""", setOf(RegexOption.DOT_MATCHES_ALL))
            val nuxtMatch = nuxtRegex.find(html) ?: run {
                Log.w("TRT", "No __NUXT__ script found → fallback")
                return getFallbackRadioChannels()
            }
            var rawArgs = nuxtMatch.groupValues[1]

            // Step 2: Find the main data payload (last big argument, after variables)
            // Skip initial vars (short strings/numbers) until the giant nested object starts with '{'
            val dataStart = rawArgs.indexOfLast { it == '{' && rawArgs.substringBefore(it).count { c -> c == ',' } > 20 } // Heuristic: after ~20 vars
            if (dataStart == -1) {
                Log.w("TRT", "No data payload found → fallback")
                return getFallbackRadioChannels()
            }
            var rawJson = rawArgs.substring(dataStart)

            // Balance closing paren/brace to extract complete object
            var balance = 0
            var braceBalance = 0
            var i = 0
            while (i < rawJson.length) {
                when (rawJson[i]) {
                    '{' -> braceBalance++
                    '}' -> {
                        braceBalance--
                        if (braceBalance == 0) break
                    }
                    '(' -> balance++
                    ')' -> balance--
                }
                i++
            }
            rawJson = rawJson.substring(0, i + 1)

            // Step 3: Clean escapes (Unicode / → \, quotes, etc.)
            rawJson = rawJson
                .replace(Regex("\\\\u002F"), "/")
                .replace(Regex("\\\\""), "\"")
                .replace(Regex("\\\\n"), "\n")
                .replace(Regex("\\\\t"), "\t")

            // Step 4: Parse as JSON (the payload is a flat object like {data: [...], state: {...}})
            val nuxtObj = JSONObject(rawJson)

            // Step 5: Navigate to channels (from txt: data[0].noGroupContent + grouped like "10319021":[...])
            var channelsArray = JSONArray()

            // Try data[0].noGroupContent first (ungrouped channels)
            if (nuxtObj.has("data")) {
                val dataArr = nuxtObj.getJSONArray("data")
                if (dataArr.length() > 0) {
                    val pageData = dataArr.getJSONObject(0)
                    if (pageData.has("noGroupContent")) {
                        channelsArray = pageData.getJSONArray("noGroupContent")
                    }
                }
            }

            // Add grouped channels (e.g., "10319021": [...array of channels])
            if (nuxtObj.has("data") || nuxtObj.has("state")) {  // Fallback to state if needed
                val root = if (nuxtObj.has("data")) nuxtObj.getJSONArray("data").getJSONObject(0) else nuxtObj
                root.keys().forEachRemaining { key ->
                    if (key.matches(Regex("\\d+"))) {  // Group IDs like 10319021
                        val group = root.optJSONArray(key)
                        if (group != null) {
                            for (j in 0 until group.length()) {
                                channelsArray.put(group.getJSONObject(j))
                            }
                        }
                    }
                }
            }

            if (channelsArray.length() == 0) {
                Log.w("TRT", "No channels in JSON → fallback")
                return getFallbackRadioChannels()
            }

            // Step 6: Map to RadioChannel
            val seenUrls = mutableSetOf<String>()
            for (i in 0 until channelsArray.length()) {
                val ch = channelsArray.getJSONObject(i)

                val name = ch.optString("title", "").trim()
                val streamUrl = ch.optString("audio", ch.optString("url", "")).trim()  // "audio" from txt
                val logoUrl = ch.optString("cover", ch.optString("imageUrl", "")).trim()
                val description = ch.optString("description", "")

                if (name.isBlank() || streamUrl.isBlank() || !streamUrl.contains(".m3u8") && !streamUrl.contains(".aac")) continue
                if (!seenUrls.add(streamUrl)) continue  // Dedupe

                result += RadioChannel(
                    name = name,
                    slug = name.lowercase(Locale.ROOT).replace(" ", "-").replace(Regex("[^a-z0-9-]"), ""),
                    streamUrl = streamUrl,
                    logoUrl = logoUrl,
                    description = description
                )
            }

            if (result.isNotEmpty()) {
                Log.i("TRT", "Dynamically loaded ${result.size} radios: ${result.joinToString { it.name }}")
                return result
            }

        } catch (e: Exception) {
            Log.e("TRT", "Dynamic radio parse failed: ${e.message}", e)
            // Optionally log snippet for debug: Log.d("TRT", "HTML preview: ${html.take(1000)}")
        }

        // Ultimate fallback (your original hardcoded, but only if dynamic fails)
        Log.w("TRT", "Dynamic failed → using minimal fallback")
        return getFallbackRadioChannels()
    }

    private fun getFallbackRadioChannels(): List<RadioChannel> {
        return listOf(

            RadioChannel(
                name = "TRT Türkü",
                slug = "trt-turku",
                streamUrl = "https://rd-trtturku.medya.trt.com.tr/master.m3u8",
                logoUrl = "https://cdn-i.pr.trt.com.tr/trtdinle/w480/h360/q70/12467466.jpeg",
                description = "Türk Halk Müziği"
            ),
            RadioChannel(
                name = "TRT 1",
                slug = "trt-1",
                streamUrl = "https://radio-trterzurum.medya.trt.com.tr/master.m3u8",
                logoUrl = "https://cdn-i.pr.trt.com.tr/trtdinle//w480/h360/q70/12467502.jpeg",
                description = "TRT 1"
            ),
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
                    this.posterUrl = "https://www.trt.net.tr/logos/our-logos/corporate/trt.png"
                },
                 newTvSeriesSearchResponse(
                    name = "TRT Radyo",
                    url = dummyRadioUrl,
                    type = TvType.TvSeries
                ) {
                    this.posterUrl = "https://port-rotf.pr.trt.com.tr/r/trtdinle//w480/h360/q70/12530507_0-0-2048-1536.jpeg"
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