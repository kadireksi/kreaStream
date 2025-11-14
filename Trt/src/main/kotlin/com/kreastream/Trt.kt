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
                url = dummyRadioUrl,
                timeout = 20,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                )
            )

            val html = response.text
            Log.d("TRT_RADIO", "HTML loaded, length: ${html.length}")

            // DEBUG: Dump first ~2000 chars to see structure (check logcat for __NUXT__)
            Log.d("TRT_RADIO", "HTML preview: ${html.take(2000)}")

            // Primary regex: Matches { ... "title":"..." ... "audio":"...m3u8" ... "cover":"...jpg" ... } - ANY ORDER
            // Uses lookaheads to find all fields in the same object block, non-greedy
            val pattern = Regex(
                """\{[^}]*?"title"\s*:\s*"([^"]+)"[^}]*?"audio"\s*:\s*"([^"]+?\.(?:m3u8|aac))"[^}]*?"cover"\s*:\s*"([^"]+?\.jpe?g[^"]*)"[^}]*\}""",
                RegexOption.DOT_MATCHES_ALL
            )

            var matches = pattern.findAll(html).toList()

            if (matches.isEmpty()) {
                Log.w("TRT_RADIO", "Primary regex failed → trying ultra-loose fallback")
                // Fallback: Even looser - any order, allows url as alternative to audio
                val fallback = Regex(
                    """\{[^}]*?("title"\s*:\s*"([^"]+)"[^}]*?("audio"|"url")\s*:\s*"([^"]+?\.(?:m3u8|aac))"[^}]*?"cover"\s*:\s*"([^"]+?\.jpe?g[^"]*)")""",
                    RegexOption.DOT_MATCHES_ALL
                )
                matches = fallback.findAll(html).toList()

                Log.d("TRT_RADIO", "Fallback found ${matches.size} matches")
            } else {
                Log.d("TRT_RADIO", "Primary found ${matches.size} matches")
            }

            if (matches.isEmpty()) {
                Log.w("TRT_RADIO", "No matches at all → fallback list")
                return getFallbackRadioChannels()
            }

            val seen = mutableSetOf<String>()
            for (m in matches.distinct()) {  // Dedupe matches
                // For fallback, parse groups carefully (group 2=name, 4=url, 5=logo)
                val name = if (m.groupValues.size > 2) m.groupValues[2] else m.groupValues[1]
                val streamUrl = if (m.groupValues.size > 4) m.groupValues[4].replace("\\u002F", "/") else m.groupValues[2].replace("\\u002F", "/")
                val logoUrl = if (m.groupValues.size > 5) m.groupValues[5].replace("\\u002F", "/") else m.groupValues[3].replace("\\u002F", "/")

                val trimmedName = name.trim()
                if (trimmedName.isBlank() || streamUrl.isBlank()) continue
                if (!seen.add(streamUrl)) continue

                Log.d("TRT_RADIO", "Extracted: $trimmedName → $streamUrl → $logoUrl")

                result += RadioChannel(
                    name = trimmedName,
                    slug = trimmedName.lowercase(Locale.ROOT).replace(" ", "-").replace(Regex("[^a-z0-9-]"), ""),
                    streamUrl = streamUrl,
                    logoUrl = logoUrl,
                    description = ""
                )
            }

            if (result.isNotEmpty()) {
                Log.i("TRT_RADIO", "SUCCESS: Loaded ${result.size} dynamic radios")
                result.forEach { Log.i("TRT_RADIO", "  - ${it.name}: ${it.streamUrl}") }
                return result
            }

        } catch (e: Exception) {
            Log.e("TRT_RADIO", "Exception in radio fetch", e)
        }

        Log.w("TRT_RADIO", "Full fallback activated")
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