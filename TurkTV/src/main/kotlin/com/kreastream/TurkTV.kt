// TurkTV.kt - Enhanced Turkish TV Plugin with Full TRT Support
package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import kotlin.text.RegexOption
import org.json.JSONObject
import org.json.JSONArray
import kotlinx.coroutines.delay
import android.util.Log

class TurkTV : MainAPI() {
    override var name = "Türk TV"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Live)
    
    private val tabiiUrl = "https://www.tabii.com/tr"
    private val trt1Url = "https://www.trt1.com.tr"
    private val showTvUrl = "https://www.showtv.com.tr"
    private val liveBase = "$tabiiUrl/watch/live"
    private val dummyTvUrl = tabiiUrl
    private val dummyRadioUrl = "https://www.trtdinle.com/radyolar"

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
        Channel("show", "Show TV", showTvUrl, "/diziler", null,
            null), // Will be fetched dynamically
        Channel("star", "Star TV", "https://www.startv.com.tr", "/diziler/yayinda-olanlar", "/diziler/arsiv-diziler",
            "https://startv-live.ercdn.net/startv/startv.m3u8"),
        Channel("now", "NOW (Fox)", "https://www.nowtv.com.tr", "/diziler/yayinda", "/diziler/arsiv",
            "https://nowtv-live.ercdn.net/nowtv/nowtv.m3u8"),
        Channel("trt1", "TRT 1", "https://www.trt1.com.tr", "/diziler?archive=false", "/diziler?archive=true",
            null, isTrt = true)
    )

    override val mainPage = mainPageOf(
        "trt_series" to "TRT - Güncel Diziler",
        "trt_archive_series" to "TRT - Arşiv Diziler",
        "trt_programs" to "TRT - Programlar",
        "trt_archive_programs" to "TRT - Arşiv Programlar",
        
        "atv_current" to "ATV - Güncel Diziler",
        "atv_archive" to "ATV - Arşiv Diziler",
        "kanald_current" to "Kanal D - Güncel Diziler",
        "kanald_archive" to "Kanal D - Arşiv Diziler",
        "show_current" to "Show TV - Diziler",
        "star_current" to "Star TV - Güncel Diziler",
        "star_archive" to "Star TV - Arşiv Diziler",
        "now_current" to "NOW - Güncel Diziler",
        "now_archive" to "NOW - Arşiv Diziler",

        "live_tv" to "TRT Canlı TV",
        "live_radio" to "TRT Canlı Radyo",
        "other_live" to "Diğer TV Kanalları"
    )

    // === TRT Data Structures ===
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

    data class RawEpisode(
        val title: String,
        val url: String,
        val posterUrl: String?,
        val description: String,
        val extractedNum: Int?
    )

    // === Show TV Live Stream Functions ===
    private suspend fun getShowTvLiveStreams(): Map<String, String> {
        return try {
            val url = "$showTvUrl/canli-yayin"
            val response = app.get(url, timeout = 10)
            val html = response.text
            
            val streams = mutableMapOf<String, String>()
            
            // Look for videoUrl variable
            val videoUrlRegex = Regex("""var\s+videoUrl\s*=\s*["']([^"']+)["']""")
            val videoUrlMatch = videoUrlRegex.find(html)
            videoUrlMatch?.let {
                val streamUrl = it.groupValues[1]
                if (streamUrl.isNotBlank() && streamUrl.contains(".m3u8")) {
                    streams["Show TV"] = streamUrl
                }
            }
            
            // Look for showturkVideoUrl variable
            val showturkUrlRegex = Regex("""var\s+showturkVideoUrl\s*=\s*["']([^"']+)["']""")
            val showturkMatch = showturkUrlRegex.find(html)
            showturkMatch?.let {
                val streamUrl = it.groupValues[1]
                if (streamUrl.isNotBlank() && streamUrl.contains(".m3u8")) {
                    streams["Show Türk"] = streamUrl
                }
            }
            
            // Fallback: search for any m3u8 URLs in scripts
            if (streams.isEmpty()) {
                val allM3u8Regex = Regex("""https?://[^"'\s]+\.m3u8[^"'\s]*""")
                val allMatches = allM3u8Regex.findAll(html).toList()
                
                // Try to identify Show TV and Show Türk streams by common patterns
                val showTvMatch = allMatches.find { it.value.contains("showtv", ignoreCase = true) }
                val showturkMatch = allMatches.find { it.value.contains("showturk", ignoreCase = true) }
                
                showTvMatch?.let { streams["Show TV"] = it.value }
                showturkMatch?.let { streams["Show Türk"] = it.value }
            }
            
            streams
        } catch (e: Exception) {
            Log.e("TurkTV", "Error fetching Show TV live streams: ${e.message}")
            mapOf(
                "Show TV" to "https://showtv-live.ercdn.net/showtv/showtv.m3u8",
                "Show Türk" to "https://ciner-live.ercdn.net/showturk/playlist.m3u8"
            )
        }
    }

    // === TRT Functions from Trt.kt ===
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
        } catch (e: Exception) {
            Log.e("TurkTV", "getTvChannels error: ${e.message}")
        }
        return result
    }

    private suspend fun getRadioChannels(): List<RadioChannel> {
        return listOf(
            RadioChannel(
                name = "TRT FM",
                slug = "trt-fm",
                streamUrl = "https://trt.radyotvonline.net/trt_fm.aac",
                logoUrl = "https://cdn-i.pr.trt.com.tr/trtdinle/w480/h360/q70/12467418.jpeg",
                description = "Türkçe Pop ve güncel müzik"
            ),
            RadioChannel(
                name = "TRT Radyo 1",
                slug = "trt-radyo-1",
                streamUrl = "https://trt.radyotvonline.net/trt_1.aac",
                logoUrl = "https://cdn-i.pr.trt.com.tr/trtdinle/w480/h360/q70/12467415.jpeg",
                description = "Haber, kültür ve klasik müzik"
            ),
            RadioChannel(
                name = "TRT Nağme",
                slug = "trt-nagme",
                streamUrl = "https://rd-trtnagme.medya.trt.com.tr/master_128.m3u8",
                logoUrl = "https://cdn-i.pr.trt.com.tr/trtdinle/w480/h360/q70/12467465.jpeg",
                description = "Türk Sanat Müziği"
            ),
            RadioChannel(
                name = "TRT Türkü",
                slug = "trt-turku",
                streamUrl = "https://rd-trtturku.medya.trt.com.tr/master_128.m3u8",
                logoUrl = "https://cdn-i.pr.trt.com.tr/trtdinle/w480/h360/q70/12467466.jpeg",
                description = "Türk Halk Müziği"
            ),
            RadioChannel(
                name = "Memleketim FM",
                slug = "memleketim-fm",
                streamUrl = "https://radio-trtmemleketimfm.medya.trt.com.tr/master_128.m3u8",
                logoUrl = "https://cdn-i.pr.trt.com.tr/trtdinle/w480/h360/q70/12467512.jpeg",
                description = "24 Saat Kesintisiz Müzik"
            ),
            RadioChannel(
                name = "TRT Radyo Haber",
                slug = "trt-radyo-haber",
                streamUrl = "https://trt.radyotvonline.net/trt_haber.aac",
                logoUrl = "https://cdn-i.pr.trt.com.tr/trtdinle/w480/h360/q70/12530424_0-0-2048-1536.jpeg",
                description = "Sürekli haber akışı"
            ),
            RadioChannel(
                name = "TRT Radyo 3",
                slug = "trt-radyo-3",
                streamUrl = "https://rd-trtradyo3.medya.trt.com.tr/master_128.m3u8",
                logoUrl = "https://cdn-i.pr.trt.com.tr/trtdinle/w480/h360/q70/12467462.jpeg",
                description = "Klasik, caz, rock ve dünya müziği"
            ),
            RadioChannel(
                name = "TRT Kurdi",
                slug = "trt-kurdi",
                streamUrl = "https://radio-trtradyo6.medya.trt.com.tr/master_128.m3u8",
                logoUrl = "https://cdn-i.pr.trt.com.tr/trtdinle/w480/h360/q70/12467484.jpeg",
                description = "Kürtçe Müzik Yayını"
            )
        )
    }

    private suspend fun getTrtContent(contentType: String, archive: Boolean = false, page: Int = 1): List<SearchResponse> {
        return try {
            val url = if (page == 1) {
                "$trt1Url/$contentType?archive=$archive&order=title_asc"
            } else {
                "$trt1Url/$contentType/$page?archive=$archive&order=title_asc"
            }
            
            val response = app.get(url, timeout = 15)
            val document = response.document
            
            val items = document.select("div.grid_grid-wrapper__elAnh > div.h-full.w-full > a")

            items.mapNotNull { el ->
                val title = el.selectFirst("div.card_card-title__IJ9af")?.text()?.trim()
                if (title == null) return@mapNotNull null
                
                val href = el.attr("href")
                if (href.isBlank()) return@mapNotNull null
                
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

    private fun extractEpisodeNumber(title: String): Int? {
        return try {
            val patterns = listOf(
                Regex("""(\d{1,4})\s*\.?\s*[Bb]ölüm"""),
                Regex("""[Bb]ölüm\s*(\d{1,4})"""),
                Regex("""[Ee]pisode\s*(\d{1,4})"""),
                Regex("""\b(\d{1,4})\b""")
            )
            
            for (pattern in patterns) {
                val match = pattern.find(title)
                if (match != null) {
                    return match.groupValues[1].toIntOrNull()
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun fixTrtUrl(url: String): String = if (url.startsWith("http")) url else "$trt1Url$url"

    // === Show TV Specific Functions ===
    private suspend fun getShowTvSeries(page: Int = 1): List<SearchResponse> {
        return try {
            val url = if (page == 1) "$showTvUrl/diziler" else "$showTvUrl/diziler?page=$page"
            
            val doc = app.get(url, timeout = 15).document
            
            val items = doc.select("ul.grid li div[data-name='box-type6']").mapNotNull { item ->
                val a = item.selectFirst("a.group")
                val title = a?.attr("title") ?: return@mapNotNull null
                val href = a.attr("href")
                if (href.isBlank() || !href.contains("/dizi/tanitim/")) return@mapNotNull null
                
                val poster = item.selectFirst("img")?.attr("src")?.let {
                    if (it.startsWith("http")) it else showTvUrl + it
                }
                
                newTvSeriesSearchResponse(title.trim(), showTvUrl + href, TvType.TvSeries) {
                    this.posterUrl = poster
                }
            }
            
            items
        } catch (e: Exception) {
            Log.e("TurkTV", "Error loading Show TV series: ${e.message}")
            emptyList()
        }
    }

    private suspend fun getShowTvEpisodes(seriesUrl: String): List<Episode> {
        return try {
            val doc = app.get(seriesUrl, timeout = 15).document
            
            // Extract episodes from the select dropdown
            val select = doc.selectFirst("select#seasonWithJs")
            if (select != null) {
                return select.select("option[data-href]").mapNotNull { option ->
                    val href = option.attr("data-href")
                    if (href.isBlank()) return@mapNotNull null
                    
                    val title = option.text().trim()
                    val seasonId = option.attr("data-season-id").toIntOrNull() ?: 1
                    val episodeNum = extractEpisodeNumber(title) ?: 0
                    
                    newEpisode(showTvUrl + href) {
                        name = title
                        season = seasonId
                        episode = episodeNum
                    }
                }.reversed() // Reverse to show episodes in chronological order
            }
            
            // Fallback: try to find episode links in the page
            val fallbackEpisodes = doc.select("a[href*='/dizi/tum_bolumler/']").mapNotNull { a ->
                val href = a.attr("href")
                if (href.isBlank()) return@mapNotNull null
                
                val title = a.text().trim().ifBlank { "Bölüm" }
                val episodeNum = extractEpisodeNumber(title) ?: 0
                
                newEpisode(showTvUrl + href) {
                    name = title
                    episode = episodeNum
                    season = 1
                }
            }
            
            fallbackEpisodes
        } catch (e: Exception) {
            Log.e("TurkTV", "Error loading Show TV episodes: ${e.message}")
            emptyList()
        }
    }

    private fun extractShowTvM3u8(jsonStr: String): String? {
        return try {
            // Clean the JSON string
            var cleanJson = jsonStr.trim()
            if (cleanJson.startsWith("data-hope-video='")) {
                cleanJson = cleanJson.removePrefix("data-hope-video='").removeSuffix("'")
            }
            
            // Parse JSON
            val json = JSONObject(cleanJson)
            val media = json.getJSONObject("media")
            
            // Try to get M3U8 URLs first (better quality)
            if (media.has("m3u8")) {
                val m3u8Array = media.getJSONArray("m3u8")
                if (m3u8Array.length() > 0) {
                    val m3u8 = m3u8Array.getJSONObject(0)
                    return m3u8.getString("src")
                }
            }
            
            // Fallback to MP4
            if (media.has("mp4")) {
                val mp4Array = media.getJSONArray("mp4")
                // Get the highest quality (last one in array)
                if (mp4Array.length() > 0) {
                    val highestQuality = mp4Array.getJSONObject(mp4Array.length() - 1)
                    return highestQuality.getString("src")
                }
            }
            
            null
        } catch (e: Exception) {
            Log.e("Show TV", "Error parsing Show TV video JSON: ${e.message}")
            
            // Fallback regex extraction
            Regex("""["']src["']\s*:\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE)
                .find(jsonStr)?.groupValues?.get(1)
        }
    }

    // === Other Channel Functions ===
    private suspend fun getChannelSeries(channel: Channel, isArchive: Boolean, page: Int = 1): List<SearchResponse> {
        return try {
            when (channel.key) {
                "show" -> getShowTvSeries(page)
                else -> {
                    val path = if (isArchive) channel.archivePath ?: channel.currentPath else channel.currentPath
                    val url = if (page == 1) channel.baseUrl + path else "${channel.baseUrl}$path?page=$page"
                    
                    val doc = app.get(url, timeout = 15).document
                    
                    val items = doc.select("a[href*='/dizi/'], .series-card a, .card a, article a").mapNotNull { el ->
                        var href = el.attr("href")
                        if (href.startsWith("/")) href = channel.baseUrl + href
                        
                        // Filter for series only
                        if (!href.contains("/dizi/") && !href.contains("/program/")) return@mapNotNull null
                        
                        val title = el.attr("title").takeIf { it.isNotBlank() }
                            ?: el.selectFirst("h3, h2, .title, .name, img")?.attr("alt")
                            ?: el.text().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        
                        val poster = el.selectFirst("img")?.attr("data-src") 
                            ?: el.selectFirst("img")?.attr("src")
                            ?.let { if (it.startsWith("http")) it else channel.baseUrl + it }
                        
                        newTvSeriesSearchResponse(title.trim(), href, TvType.TvSeries) {
                            this.posterUrl = poster
                        }
                    }
                    
                    items
                }
            }
        } catch (e: Exception) {
            Log.e("TurkTV", "Error loading ${channel.displayName} series: ${e.message}")
            emptyList()
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = when (request.data) {
            
            // TRT Content Sections
            "trt_series" -> getTrtContent("diziler", archive = false, page = page)
            "trt_archive_series" -> getTrtContent("diziler", archive = true, page = page)
            "trt_programs" -> getTrtContent("programlar", archive = false, page = page)
            "trt_archive_programs" -> getTrtContent("programlar", archive = true, page = page)
            
            // Other Channel Sections
            "atv_current" -> getChannelSeries(channels[0], isArchive = false, page = page)
            "atv_archive" -> getChannelSeries(channels[0], isArchive = true, page = page)
            "kanald_current" -> getChannelSeries(channels[1], isArchive = false, page = page)
            "kanald_archive" -> getChannelSeries(channels[1], isArchive = true, page = page)
            "show_current" -> getShowTvSeries(page)
            "star_current" -> getChannelSeries(channels[3], isArchive = false, page = page)
            "star_archive" -> getChannelSeries(channels[3], isArchive = true, page = page)
            "now_current" -> getChannelSeries(channels[4], isArchive = false, page = page)
            "now_archive" -> getChannelSeries(channels[4], isArchive = true, page = page)
            
            // TRT Live Sections
            "live_tv" -> {
                val tvChannels = getTvChannels()
                tvChannels.map { ch ->
                    newLiveSearchResponse(ch.name, ch.streamUrl, TvType.Live) {
                        this.posterUrl = ch.logoUrl
                    }
                }
            }
            "live_radio" -> {
                val radioChannels = getRadioChannels()
                radioChannels.map { ch ->
                    newLiveSearchResponse(ch.name, ch.streamUrl, TvType.Live) {
                        this.posterUrl = ch.logoUrl
                    }
                }
            }
            "other_live" -> {
                val liveItems = mutableListOf<SearchResponse>()
                
                // Get Show TV live streams dynamically
                val showStreams = getShowTvLiveStreams()
                showStreams.forEach { (name, streamUrl) ->
                    liveItems.add(newLiveSearchResponse(name, streamUrl, TvType.Live) {
                        this.posterUrl = when (name) {
                            "Show TV" -> "https://www.showtv.com.tr/images/showtv-logo.png"
                            "Show Türk" -> "https://www.showtv.com.tr/images/showturk-logo.png"
                            else -> "https://www.showtv.com.tr/favicon.ico"
                        }
                    })
                }
                
                // Add other channels (excluding Show TV since we handle it dynamically)
                channels.filterNot { it.isTrt && it.key != "show" }.forEach { ch ->
                    ch.liveStream?.let {
                        liveItems.add(newLiveSearchResponse("${ch.displayName} Canlı", it, TvType.Live) {
                            this.posterUrl = "${ch.baseUrl}/favicon.ico"
                        })
                    }
                }
                
                liveItems
            }

            else -> emptyList()
        }

        val hasNext = when {
            request.data.startsWith("trt_") && (request.data.contains("series") || request.data.contains("programs")) -> {
                if (items.isNotEmpty() && page <= 3) {
                    val nextItems = when (request.data) {
                        "trt_series" -> getTrtContent("diziler", archive = false, page = page + 1)
                        "trt_archive_series" -> getTrtContent("diziler", archive = true, page = page + 1)
                        "trt_programs" -> getTrtContent("programlar", archive = false, page = page + 1)
                        "trt_archive_programs" -> getTrtContent("programlar", archive = true, page = page + 1)
                        else -> emptyList()
                    }
                    nextItems.isNotEmpty()
                } else {
                    items.isNotEmpty()
                }
            }
            request.data == "show_current" -> {
                items.isNotEmpty()
            }
            request.data.endsWith("_current") || request.data.endsWith("_archive") -> {
                items.isNotEmpty()
            }
            else -> false
        }

        return newHomePageResponse(
            listOf(HomePageList(request.name, items, true)),
            hasNext = hasNext
        )
    }

    override suspend fun load(url: String): LoadResponse {
        // Handle live stream URLs
        if (url.contains(".m3u8", ignoreCase = true) || url.contains(".aac", ignoreCase = true)) {
            val name = when {
                url.contains("showtv", ignoreCase = true) -> "Show TV"
                url.contains("showturk", ignoreCase = true) -> "Show Türk"
                else -> "Canlı Yayın"
            }
            return newMovieLoadResponse(
                name = name,
                url = url,
                type = TvType.Live,
                data = url
            ) {
                this.posterUrl = when {
                    url.contains("showtv", ignoreCase = true) -> "https://www.showtv.com.tr/images/showtv-logo.png"
                    url.contains("showturk", ignoreCase = true) -> "https://www.showtv.com.tr/images/showturk-logo.png"
                    else -> "https://www.trt.net.tr/logos/our-logos/corporate/trt.png"
                }
            }
        }

        // Handle Show TV content
        if (url.contains(showTvUrl) && url.contains("/dizi/tanitim/")) {
            try {
                val doc = app.get(url, timeout = 15).document
                
                val title = doc.selectFirst("h1")?.text()?.trim()
                    ?: doc.selectFirst("meta[property='og:title']")?.attr("content")
                    ?: throw ErrorLoadingException("Başlık bulunamadı")
                
                val plot = doc.selectFirst("meta[name=description]")?.attr("content") ?: ""
                val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
                    ?: doc.selectFirst("img[src*='.jpg'], img[src*='.jpeg'], img[src*='.png']")?.attr("src")
                    ?.let { if (it.startsWith("http")) it else showTvUrl + it }
                
                val episodes = getShowTvEpisodes(url)
                
                return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.plot = plot
                }
            } catch (e: Exception) {
                throw ErrorLoadingException("Show TV dizisi yüklenemedi: ${e.message}")
            }
        }

        // Handle TRT content
        if (url.contains(trt1Url)) {
            try {
                val doc = app.get(url, timeout = 15).document
                val title = doc.selectFirst("h1")?.text()?.trim()
                    ?: throw ErrorLoadingException("Başlık bulunamadı")
                val plot = doc.selectFirst("meta[name=description]")?.attr("content") ?: ""
                var poster = doc.selectFirst("picture.card_card-image__T64bP img")?.attr("src")?.let { fixTrtUrl(it) }
                poster = poster?.replace(Regex("webp/w\\d+/h\\d+"), "webp/w600/h338")?.replace("/q75/", "/q85/")

                val basePath = if (url.contains("/diziler/")) "diziler" else "programlar"
                val slug = url.removePrefix("$trt1Url/$basePath/").substringBefore("/")
                val episodesPath = "bolum"
                val rawEpisodes = mutableListOf<RawEpisode>()
                var pageNum = 1
                var more = true

                while (more && pageNum <= 30) {
                    try {
                        val epUrl = if (pageNum == 1) {
                            "$trt1Url/$basePath/$slug/$episodesPath"
                        } else {
                            "$trt1Url/$basePath/$slug/$episodesPath/$pageNum"
                        }
                        val epDoc = app.get(epUrl, timeout = 10).document
                        val pageRaws = epDoc.select("div.grid_grid-wrapper__elAnh > div.h-full.w-full > a")
                            .mapNotNull { el ->
                                val epTitle = el.selectFirst("div.card_card-title__IJ9af")?.text()?.trim()
                                    ?: return@mapNotNull null
                                val href = el.attr("href")
                                var img = el.selectFirst("img")?.absUrl("src")
                                img = img?.replace(Regex("webp/w\\d+/h\\d+"), "webp/w600/h338")
                                    ?.replace("/q75/", "/q85/")
                                val desc = el.selectFirst("p.card_card-description__0PSTi")?.text()?.trim() ?: ""
                                val extracted = extractEpisodeNumber(epTitle)

                                var episodeUrl = fixTrtUrl(href)
                                RawEpisode(epTitle, episodeUrl, img, desc, extracted)
                            }

                        if (pageRaws.isNotEmpty()) {
                            rawEpisodes += pageRaws
                            pageNum++
                            delay(100)
                        } else more = false
                    } catch (e: Exception) { 
                        more = false 
                        Log.e("TurkTV", "Error loading episodes page $pageNum: ${e.message}")
                    }
                }

                val numbered = rawEpisodes.filter { it.extractedNum != null && it.extractedNum!! > 0 }.sortedBy { it.extractedNum }
                val unnumbered = rawEpisodes.filter { it.extractedNum == null || it.extractedNum == 0 }
                
                var nextEpNum = if (numbered.isNotEmpty()) numbered.last().extractedNum!! + 1 else 1
                
                val episodes = mutableListOf<Episode>()
                for (raw in numbered) {
                    episodes += newEpisode(raw.url) {
                        name = raw.title
                        this.posterUrl = raw.posterUrl
                        episode = raw.extractedNum!!
                        description = raw.description
                    }
                }
                
                for (raw in unnumbered) {
                    episodes += newEpisode(raw.url) {
                        name = raw.title
                        this.posterUrl = raw.posterUrl
                        episode = nextEpNum++
                        description = raw.description
                    }
                }

                return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.plot = plot
                }
            } catch (e: Exception) {
                throw ErrorLoadingException("TRT içeriği yüklenemedi: ${e.message}")
            }
        }

        // Handle other channels
        val channel = channels.find { url.contains(it.baseUrl, ignoreCase = true) } ?: throw ErrorLoadingException("Kanal bulunamadı")
        
        try {
            val doc = app.get(url, timeout = 15).document
            
            val title = doc.selectFirst("h1, .seo-h1, meta[property='og:title']")
                ?.let { if (it.tagName() == "meta") it.attr("content") else it.text() } 
                ?: doc.title().substringBefore(" | ")
            
            val plot = doc.selectFirst(".description, meta[name='description']")?.attr("content") ?: ""
            val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
                ?: doc.selectFirst(".poster img, .cover img")?.attr("src")
            
            // Try to find episodes
            val episodes = doc.select("a[href*='/bolum/'], a[href*='/video/'], .episode-list a, .episode-item a")
                .mapNotNull { el ->
                    var href = el.attr("href")
                    if (href.startsWith("/")) href = channel.baseUrl + href
                    if (!href.contains("/bolum/") && !href.contains("/video/")) return@mapNotNull null
                    
                    val epTitle = el.attr("title").takeIf { it.isNotBlank() }
                        ?: el.selectFirst("h3, .title, .name")?.text()
                        ?: "Bölüm"
                    
                    newEpisode(href) { 
                        name = epTitle.trim()
                    }
                }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        } catch (e: Exception) {
            throw ErrorLoadingException("Dizi yüklenemedi: ${e.message}")
        }
    }

    private fun extractM3u8FromJson(jsonStr: String): String? {
        return try {
            var cleanJson = jsonStr.trim()
            if (cleanJson.startsWith("var ") || cleanJson.startsWith("let ") || cleanJson.startsWith("const ")) {
                cleanJson = cleanJson.substringAfterLast("= ").trim().trimEnd(';')
            }
            if (cleanJson.startsWith("{") && cleanJson.endsWith("}")) {
                val config = JSONObject(cleanJson)
                var streamUrl = config.optString("streamUrl")
                if (streamUrl.contains(".m3u8")) return streamUrl

                fun findInJson(obj: JSONObject): String? {
                    if (obj.has("streamUrl")) {
                        val url = obj.getString("streamUrl")
                        if (url.contains(".m3u8")) return url
                    }
                    if (obj.has("sources")) {
                        val sources = obj.getJSONArray("sources")
                        for (i in 0 until sources.length()) {
                            val src = sources.getJSONObject(i)
                            if (src.optString("type") == "application/x-mpegURL" || src.optString("file").contains(".m3u8")) {
                                return src.optString("file", src.optString("src", src.optString("url")))
                            }
                        }
                    }
                    if (obj.has("media") || obj.has("playlist")) {
                        val arr = if (obj.has("media")) obj.getJSONArray("media") else obj.getJSONArray("playlist")
                        for (i in 0 until arr.length()) {
                            val item = arr.getJSONObject(i)
                            if (item.optString("type") == "hls" || item.optString("format") == "hls") {
                                return item.optString("url", item.optString("src", item.optString("streamUrl")))
                            }
                        }
                    }
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val value = obj.get(key)
                        if (value is JSONObject) {
                            val found = findInJson(value)
                            if (found != null) return found
                        }
                    }
                    return null
                }

                return findInJson(config)
            }
            null
        } catch (e: Exception) {
            Regex("""["']?streamUrl["']?\s*:\s*["']([^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE)
                .find(jsonStr)?.groupValues?.get(1)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Handle Show TV episode URLs
        if (data.contains(showTvUrl) && data.contains("/dizi/tum_bolumler/")) {
            try {
                val doc = app.get(data, timeout = 10).document
                
                // Look for the hope-video element
                val hopeVideoDiv = doc.selectFirst("div.hope-video[data-hope-video]")
                if (hopeVideoDiv != null) {
                    val videoJson = hopeVideoDiv.attr("data-hope-video")
                    val streamUrl = extractShowTvM3u8(videoJson)
                    
                    if (streamUrl != null) {
                        // Try to extract subtitles
                        try {
                            val subtitleMatch = Regex("""["']subtitles["']\s*:\s*\[([^\]]+)\]""").find(videoJson)
                            subtitleMatch?.let {
                                val subtitlesStr = it.groupValues[1]
                                val subtitleRegex = Regex("""["']src["']\s*:\s*["']([^"']+\.vtt[^"']*)["']""")
                                subtitleRegex.findAll(subtitlesStr).forEach { match ->
                                    val subtitleUrl = match.groupValues[1]
                                    subtitleCallback.invoke(SubtitleFile("Türkçe", subtitleUrl))
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("TurkTV", "Error extracting subtitles: ${e.message}")
                        }
                        
                        if (streamUrl.contains(".m3u8")) {
                            M3u8Helper.generateM3u8(
                                source = "Show TV",
                                streamUrl = streamUrl,
                                referer = showTvUrl,
                                headers = mapOf(
                                    "User-Agent" to "Mozilla/5.0",
                                    "Referer" to showTvUrl,
                                    "Origin" to showTvUrl
                                )
                            ).forEach(callback)
                        } else {
                            // MP4 stream
                            callback(newExtractorLink(
                                source = "Show TV",
                                name = "Show TV",
                                url = streamUrl
                            ) {
                                this.referer = showTvUrl
                                this.quality = if (streamUrl.contains("1080")) Qualities.P1080.value
                                    else if (streamUrl.contains("720")) Qualities.P720.value
                                    else if (streamUrl.contains("480")) Qualities.P480.value
                                    else if (streamUrl.contains("360")) Qualities.P360.value
                                    else Qualities.Unknown.value
                            })
                        }
                        return true
                    }
                }
                
                // Fallback: try to find iframe
                val iframe = doc.selectFirst("iframe[src*='player']")
                if (iframe != null) {
                    val src = iframe.attr("src")
                    if (src.isNotBlank()) {
                        return loadExtractor(if (src.startsWith("http")) src else "https:$src", data, subtitleCallback, callback)
                    }
                }
                
            } catch (e: Exception) {
                Log.e("TurkTV", "Error loading Show TV links: ${e.message}")
            }
        }
        
        // Handle direct stream URLs (including live streams)
        if (data.contains(".m3u8", ignoreCase = true)) {
            M3u8Helper.generateM3u8(
                source = name,
                streamUrl = data,
                referer = when {
                    data.contains("showtv", ignoreCase = true) -> showTvUrl
                    data.contains("trt", ignoreCase = true) -> trt1Url
                    else -> "https://www.google.com"
                },
                headers = mapOf("User-Agent" to "Mozilla/5.0")
            ).forEach(callback)
            return true
        } else if (data.endsWith(".aac", ignoreCase = true)) {
            callback(newExtractorLink(
                source = name,
                name = "Audio AAC",
                url = data
            ) {
                this.referer = "https://www.trtdinle.com"
                this.quality = Qualities.Unknown.value
            })
            return true
        }

        // Handle YouTube embeds
        if (data.contains("youtube.com") || data.contains("youtu.be")) {
            return loadExtractor(data, "", subtitleCallback, callback)
        }

        // Try to extract from page (for other channels)
        try {
            val doc = app.get(data, timeout = 10).document
            
            // Try JSON extraction first
            val scripts = doc.select("script")
            for (script in scripts) {
                val scriptContent = script.html()
                if (scriptContent.contains("playerConfig", ignoreCase = true) || scriptContent.contains("streamUrl", ignoreCase = true)) {
                    val m3u8Url = extractM3u8FromJson(scriptContent)
                    if (m3u8Url != null) {
                        M3u8Helper.generateM3u8(
                            source = name,
                            streamUrl = m3u8Url,
                            referer = data,
                            headers = mapOf("Referer" to data, "User-Agent" to "Mozilla/5.0")
                        ).forEach(callback)
                        return true
                    }
                }
            }

            // Try iframe extraction
            val iframe = doc.selectFirst("iframe[src*='player']")
            if (iframe != null) {
                val src = iframe.attr("src")
                if (src.isNotBlank()) {
                    return loadExtractor(if (src.startsWith("http")) src else "https:$src", data, subtitleCallback, callback)
                }
            }

            // Try direct m3u8 regex
            for (script in scripts) {
                val html = script.html()
                val m = Regex("""https?://[^"'\s]+?\.m3u8[^"'\s]*""", RegexOption.IGNORE_CASE).find(html)
                if (m != null) {
                    val found = m.value
                    M3u8Helper.generateM3u8(
                        source = name,
                        streamUrl = found,
                        referer = data,
                        headers = mapOf("Referer" to data)
                    ).forEach(callback)
                    return true
                }
            }

        } catch (e: Exception) {
            Log.e("TurkTV", "loadLinks error for $data: ${e.message}")
        }

        return false
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()

        // Search live channels
        getTvChannels()
            .filter { it.name.contains(query, ignoreCase = true) }
            .forEach { ch ->
                out += newLiveSearchResponse(ch.name, ch.streamUrl, TvType.Live) {
                    posterUrl = ch.logoUrl
                }
            }

        getRadioChannels()
            .filter { it.name.contains(query, ignoreCase = true) }
            .forEach { ch ->
                out += newLiveSearchResponse(ch.name, ch.streamUrl, TvType.Live) {
                    posterUrl = ch.logoUrl
                }
            }

        // Search Show TV live streams
        try {
            val showStreams = getShowTvLiveStreams()
            showStreams.forEach { (name, streamUrl) ->
                if (name.contains(query, ignoreCase = true)) {
                    out += newLiveSearchResponse(name, streamUrl, TvType.Live) {
                        posterUrl = when (name) {
                            "Show TV" -> "https://www.showtv.com.tr/assets/v4/images/common/logo/svg/show-tv-logo.svg"
                            "Show Türk" -> "https://www.showturk.com.tr/scripts/assets/dist/images/logo.svg"
                            else -> "https://www.showtv.com.tr/favicon.ico"
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // Search other live channels
        channels.filterNot { it.isTrt && it.key != "show" }.forEach { ch ->
            ch.liveStream?.let {
                if (ch.displayName.contains(query, ignoreCase = true)) {
                    out += newLiveSearchResponse("${ch.displayName} Canlı", it, TvType.Live) {
                        posterUrl = "${ch.baseUrl}/favicon.ico"
                    }
                }
            }
        }

        // Search TRT content
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

        // Search Show TV content
        try {
            val searchUrl = "$showTvUrl/arama?q=$query"
            val doc = app.get(searchUrl, timeout = 10).document
            
            // Look for series in search results
            doc.select("a[href*='/dizi/tanitim/']").forEach { el ->
                val href = el.attr("href")
                val title = el.attr("title").takeIf { it.isNotBlank() }
                    ?: el.selectFirst("h3, .title")?.text()
                    ?: return@forEach
                
                val poster = el.selectFirst("img")?.attr("src")
                    ?.let { if (it.startsWith("http")) it else showTvUrl + it }
                
                out += newTvSeriesSearchResponse(title.trim(), showTvUrl + href, TvType.TvSeries) {
                    this.posterUrl = poster
                }
            }
        } catch (_: Exception) {}

        // Search other channel content
        for (channel in channels.filterNot { it.isTrt && it.key != "show" }) {
            try {
                val searchUrl = "${channel.baseUrl}/search?q=${query}"
                val doc = app.get(searchUrl, timeout = 10).document
                doc.select("a[href*='/dizi/']").forEach { el ->
                    var href = el.attr("href")
                    if (href.startsWith("/")) href = channel.baseUrl + href
                    
                    val title = el.attr("title").takeIf { it.isNotBlank() }
                        ?: el.selectFirst("h3, .title")?.text()
                        ?: return@forEach
                    
                    val poster = el.selectFirst("img")?.attr("src")
                        ?.let { if (it.startsWith("http")) it else channel.baseUrl + it }
                    
                    out += newTvSeriesSearchResponse(title.trim(), href, TvType.TvSeries) {
                        this.posterUrl = poster
                    }
                }
            } catch (_: Exception) {}
        }

        return out
    }
}
