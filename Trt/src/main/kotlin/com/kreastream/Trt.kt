package com.kreastream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup

class TrtProvider : MainAPI() {
    override var mainUrl = "https://www.tabii.com"
    override var name = "TRT & Tabii"
    override val supportedTypes = setOf(TvType.Live, TvType.TvSeries)
    override var lang = "tr"

    companion object {
        val info = ChannelRegistry.ChannelInfo(
            id = "trtprovider",
            title = "TRT & Tabii",
            sections = mapOf(
                "live" to "Canlı Yayınlar",
                "series" to "Güncel Diziler",
                "archive" to "Eski Diziler"
            )
        )
    }

    private val tabiiUrl = "https://www.tabii.com/tr"
    private val trt1Url = "https://www.trt1.com.tr"

    override val mainPage = mainPageOf(
        "live" to "Canlı Yayınlar",
        "series" to "Güncel Diziler", 
        "archive" to "Eski Diziler"
    )

    // Cache for tabii channels
    private var cachedTabiiChannels: List<TabiiChannel>? = null

    data class TabiiChannel(
        val name: String,
        val streamUrls: List<String>,
        val logoUrl: String,
        val description: String = ""
    )

    data class TrtSeries(
        val title: String,
        val url: String,
        val posterUrl: String?,
        val description: String = ""
    )

    private suspend fun getTabiiChannels(): List<TabiiChannel> {
        cachedTabiiChannels?.let { return it }

        val channels = mutableListOf<TabiiChannel>()
        try {
            val document = app.get(tabiiUrl).document
            val scriptElement = document.selectFirst("script#__NEXT_DATA__")
            val jsonContent = scriptElement?.html() ?: return emptyList()

            println("DEBUG: Found JSON data, length: ${jsonContent.length}")

            // Parse the JSON data to extract channel information
            val jsonObject = AppUtils.tryParseJson<Map<String, Any>>(jsonContent)
            val props = jsonObject?.get("props") as? Map<*, *>
            val pageProps = props?.get("pageProps") as? Map<*, *>
            
            // Try multiple possible JSON structures for channels
            val blocks = pageProps?.get("blocks") as? List<Map<*, *>>
            val initialData = pageProps?.get("initialData") as? Map<*, *>
            val channelsData = pageProps?.get("channels") as? List<Map<*, *>>

            println("DEBUG: Blocks found: ${blocks?.size}")
            println("DEBUG: InitialData found: ${initialData != null}")
            println("DEBUG: ChannelsData found: ${channelsData?.size}")

            // Method 1: Parse from blocks
            blocks?.forEach { block ->
                val items = block["items"] as? List<Map<*, *>>
                items?.forEach { item ->
                    parseChannelFromItem(item, channels)
                }
            }

            // Method 2: Parse from channels data
            channelsData?.forEach { channelData ->
                parseChannelData(channelData, channels)
            }

            // Method 3: Parse from initialData
            initialData?.let { data ->
                val dataBlocks = data["blocks"] as? List<Map<*, *>>
                dataBlocks?.forEach { block ->
                    val items = block["items"] as? List<Map<*, *>>
                    items?.forEach { item ->
                        parseChannelFromItem(item, channels)
                    }
                }
            }

            // Method 4: If still no channels, try direct string extraction
            if (channels.isEmpty()) {
                extractChannelsFromJsonString(jsonContent, channels)
            }

            println("DEBUG: Total channels found: ${channels.size}")

        } catch (e: Exception) {
            println("DEBUG: Error getting tabii channels: ${e.message}")
            e.printStackTrace()
        }

        cachedTabiiChannels = channels
        return channels
    }

    private fun parseChannelFromItem(item: Map<*, *>, channels: MutableList<TabiiChannel>) {
        try {
            // Try different channel data structures
            val channelData = item["channel"] as? Map<*, *>
            if (channelData != null) {
                parseChannelData(channelData, channels)
            } else {
                // Try direct item parsing
                val name = item["name"] as? String ?: item["title"] as? String ?: ""
                val logo = item["logo"] as? String ?: item["image"] as? String ?: item["poster"] as? String ?: ""
                val streamUrl = item["streamUrl"] as? String ?: item["url"] as? String ?: item["source"] as? String

                if (name.isNotEmpty() && streamUrl != null && streamUrl.isNotEmpty()) {
                    val streamUrls = mutableListOf(streamUrl)
                    
                    // Add multiple quality variants if it's an m3u8 stream
                    if (streamUrl.contains(".m3u8")) {
                        // Generate quality variants
                        val baseUrl = streamUrl.substringBeforeLast("_")
                        val qualities = listOf("360", "480", "720", "1080", "1440")
                        qualities.forEach { quality ->
                            val qualityUrl = "${baseUrl}_${quality}.m3u8"
                            streamUrls.add(qualityUrl)
                        }
                    }

                    channels.add(
                        TabiiChannel(
                            name = name,
                            streamUrls = streamUrls,
                            logoUrl = if (logo.startsWith("http")) logo else "$mainUrl$logo"
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun parseChannelData(channelData: Map<*, *>, channels: MutableList<TabiiChannel>) {
        try {
            val name = channelData["name"] as? String ?: channelData["title"] as? String ?: ""
            val logo = channelData["logo"] as? String ?: channelData["image"] as? String ?: channelData["poster"] as? String ?: ""
            var streamUrl = channelData["streamUrl"] as? String ?: channelData["url"] as? String ?: channelData["source"] as? String

            // Try to find stream URL in nested structures
            if (streamUrl == null) {
                val sources = channelData["sources"] as? List<Map<*, *>>
                sources?.forEach { source ->
                    streamUrl = source["url"] as? String ?: source["src"] as? String
                }
            }

            if (streamUrl == null) {
                val video = channelData["video"] as? Map<*, *>
                streamUrl = video?.get("url") as? String ?: video?.get("src") as? String
            }

            if (name.isNotEmpty() && streamUrl != null && streamUrl.isNotEmpty()) {
                val streamUrls = mutableListOf(streamUrl)
                
                // Add multiple quality variants for m3u8 streams
                if (streamUrl.contains(".m3u8")) {
                    val baseUrl = streamUrl.substringBeforeLast("_")
                    val qualities = listOf("360", "480", "720", "1080", "1440")
                    qualities.forEach { quality ->
                        val qualityUrl = "${baseUrl}_${quality}.m3u8"
                        streamUrls.add(qualityUrl)
                    }
                }

                channels.add(
                    TabiiChannel(
                        name = name,
                        streamUrls = streamUrls,
                        logoUrl = if (logo.startsWith("http")) logo else "$mainUrl$logo"
                    )
                )
                println("DEBUG: Added channel: $name")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun extractChannelsFromJsonString(jsonContent: String, channels: MutableList<TabiiChannel>) {
        try {
            // Look for channel patterns in the JSON string
            val channelPatterns = listOf(
                Regex(""""channel"\s*:\s*\{[^}]+"name"\s*:\s*"([^"]+)"[^}]+"logo"\s*:\s*"([^"]*)"[^}]+"streamUrl"\s*:\s*"([^"]*)"""),
                Regex(""""name"\s*:\s*"([^"]+)"[^}]+"logo"\s*:\s*"([^"]*)"[^}]+"streamUrl"\s*:\s*"([^"]*)"""),
                Regex(""""title"\s*:\s*"([^"]+)"[^}]+"logo"\s*:\s*"([^"]*)"[^}]+"url"\s*:\s*"([^"]*)""")
            )
            
            channelPatterns.forEach { pattern ->
                val matches = pattern.findAll(jsonContent)
                matches.forEach { match ->
                    val name = match.groupValues[1]
                    val logo = match.groupValues[2]
                    val streamUrl = match.groupValues[3]
                    
                    if (name.isNotEmpty() && streamUrl.isNotEmpty() && streamUrl.contains("m3u8")) {
                        val streamUrls = mutableListOf(streamUrl)
                        
                        // Add quality variants
                        if (streamUrl.contains(".m3u8")) {
                            val baseUrl = streamUrl.substringBeforeLast("_")
                            val qualities = listOf("360", "480", "720", "1080", "1440")
                            qualities.forEach { quality ->
                                val qualityUrl = "${baseUrl}_${quality}.m3u8"
                                streamUrls.add(qualityUrl)
                            }
                        }

                        channels.add(
                            TabiiChannel(
                                name = name,
                                streamUrls = streamUrls,
                                logoUrl = if (logo.startsWith("http")) logo else "$mainUrl$logo"
                            )
                        )
                        println("DEBUG: Added channel from regex: $name")
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun getTrtSeries(archive: Boolean = false): List<TrtSeries> {
        val url = "$trt1Url/diziler?archive=$archive&order=title_asc"
        val document = app.get(url).document
        
        return document.select("div.grid_grid-wrapper__elAnh > div.h-full.w-full > a").mapNotNull { element ->
            val title = element.selectFirst("div.card_card-title__IJ9af")?.text()?.trim() ?: return@mapNotNull null
            val href = element.attr("href")
            var posterUrl = element.selectFirst("img")?.attr("src")
            
            // Fix poster URL for horizontal aspect ratio
            posterUrl = fixPosterUrlHorizontal(posterUrl)
            
            TrtSeries(
                title = title,
                url = fixTrtUrl(href),
                posterUrl = posterUrl
            )
        }
    }

    private fun fixPosterUrlHorizontal(url: String?): String? {
        return url?.replace("webp/w800/h450", "webp/w600/h338") // 16:9 aspect ratio
            ?.replace("webp/w400/h600", "webp/w600/h338") // Change vertical to horizontal
            ?.replace("/q75/", "/q85/")
            ?.replace("w800/h450", "w600/h338")
            ?.replace("w400/h600", "w600/h338")
    }

    private fun fixTrtUrl(url: String): String {
        return if (url.startsWith("http")) url else "$trt1Url$url"
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val items = when (request.data) {
            "live" -> {
                val channels = getTabiiChannels()
                println("DEBUG: MainPage - Found ${channels.size} live channels")
                channels.map { channel ->
                    val mainStream = channel.streamUrls.firstOrNull() ?: ""
                    newMovieSearchResponse(channel.name, mainStream, TvType.Live) {
                        this.posterUrl = channel.logoUrl
                        this.plot = channel.description
                    }
                }
            }
            "series" -> {
                val series = getTrtSeries(false)
                series.map { series ->
                    newTvSeriesSearchResponse(series.title, series.url) {
                        this.posterUrl = series.posterUrl
                        this.plot = series.description
                    }
                }
            }
            "archive" -> {
                val series = getTrtSeries(true)
                series.map { series ->
                    newTvSeriesSearchResponse(series.title, series.url) {
                        this.posterUrl = series.posterUrl
                        this.plot = series.description
                    }
                }
            }
            else -> emptyList()
        }

        println("DEBUG: MainPage - ${request.name} has ${items.size} items")

        return newHomePageResponse(
            listOf(
                HomePageList(
                    name = request.name,
                    list = items,
                    isHorizontalImages = true // Force horizontal layout
                )
            )
        )
    }

    override suspend fun load(url: String): LoadResponse {
        // Check if it's a live channel
        val tabiiChannels = getTabiiChannels()
        val liveChannel = tabiiChannels.find { channel ->
            channel.streamUrls.contains(url) || url.contains(channel.name, ignoreCase = true)
        }

        if (liveChannel != null) {
            return newMovieLoadResponse(liveChannel.name, url, TvType.Live, url) {
                this.posterUrl = liveChannel.logoUrl
                this.plot = "${liveChannel.name} canlı yayın"
            }
        }

        // Otherwise, treat it as a series
        val document = app.get(url).document
        
        val title = document.selectFirst("h1")?.text()?.trim() ?: throw ErrorLoadingException("Title not found")
        val description = document.selectFirst("meta[name=description]")?.attr("content") ?: ""
        var poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        
        poster = fixPosterUrlHorizontal(poster)
        
        val episodes = mutableListOf<Episode>()
        
        // Get episodes from the bolum page
        val seriesSlug = url.removePrefix("$trt1Url/diziler/")
        val episodesUrl = "$trt1Url/diziler/$seriesSlug/bolum"
        
        // Parse episodes
        suspend fun parseEpisodesPage(pageUrl: String): List<Episode> {
            val episodeDoc = app.get(pageUrl).document
            return episodeDoc.select("div.grid_grid-wrapper__elAnh > div.h-full.w-full > a").mapNotNull { element ->
                val epTitle = element.selectFirst("div.card_card-title__IJ9af")?.text()?.trim() ?: return@mapNotNull null
                val epHref = element.attr("href")
                var epPoster = element.selectFirst("img")?.attr("src")
                val epDescription = element.selectFirst("p.card_card-description__0PSTi")?.text()?.trim() ?: ""
                
                epPoster = fixPosterUrlHorizontal(epPoster)
                
                val episodeNumber = epTitle.replace(Regex("[^0-9]"), "").toIntOrNull()
                
                newEpisode(fixTrtUrl(epHref)) {
                    this.name = epTitle
                    this.posterUrl = epPoster
                    this.episode = episodeNumber
                    this.description = epDescription
                }
            }
        }

        // Get first page episodes
        try {
            episodes.addAll(parseEpisodesPage(episodesUrl))
        } catch (e: Exception) {
            // Alternative episode structure
            val alternativeEpisodes = document.select("a[href*='/bolum/']").mapNotNull { element ->
                val epHref = element.attr("href")
                if (epHref.contains("/bolum/") && !epHref.contains("/bolum/1")) {
                    val epTitle = element.selectFirst("div, span, h3")?.text()?.trim() ?: "Bölüm"
                    val epPoster = element.selectFirst("img")?.attr("src")?.let { fixPosterUrlHorizontal(it) }
                    
                    newEpisode(fixTrtUrl(epHref)) {
                        this.name = epTitle
                        this.posterUrl = epPoster
                    }
                } else {
                    null
                }
            }
            episodes.addAll(alternativeEpisodes)
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Check if it's a live stream URL
        val tabiiChannels = getTabiiChannels()
        val liveChannel = tabiiChannels.find { channel ->
            channel.streamUrls.contains(data)
        }

        if (liveChannel != null) {
            // Handle live stream with multiple quality options
            liveChannel.streamUrls.forEach { streamUrl ->
                // Only add valid URLs
                if (streamUrl.isNotEmpty() && streamUrl.startsWith("http")) {
                    val quality = when {
                        streamUrl.contains("1440") -> Qualities.P1440.value
                        streamUrl.contains("1080") -> Qualities.P1080.value
                        streamUrl.contains("720") -> Qualities.P720.value
                        streamUrl.contains("480") -> Qualities.P480.value
                        streamUrl.contains("360") -> Qualities.P360.value
                        else -> Qualities.Unknown.value
                    }

                    callback(
                        newExtractorLink(
                            name = "${liveChannel.name} ${getQualityName(quality)}",
                            source = name,
                            url = streamUrl
                        ) {
                            this.referer = tabiiUrl
                            this.quality = quality
                            this.headers = mapOf(
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                                "Referer" to tabiiUrl
                            )
                        }
                    )
                }
            }
            return true
        }

        // Handle series episodes (YouTube or m3u8)
        val document = app.get(data).document
        
        // Look for m3u8 streams
        val m3u8Url = findM3u8Url(document)
        if (m3u8Url != null) {
            M3u8Helper.generateM3u8(
                name,
                m3u8Url,
                "$trt1Url/",
                headers = mapOf("Referer" to "$trt1Url/")
            ).forEach(callback)
            return true
        }
        
        // Look for YouTube embed
        val youtubeUrl = findYouTubeUrl(document)
        if (youtubeUrl != null) {
            return handleYouTubeVideo(youtubeUrl, subtitleCallback, callback)
        }

        return false
    }

    private fun findM3u8Url(document: org.jsoup.nodes.Document): String? {
        val scripts = document.select("script")
        for (script in scripts) {
            val scriptContent = script.html()
            
            val patterns = listOf(
                Regex(""""mediaSrc"\s*:\s*\[\s*\{[^}]*"url"\s*:\s*"([^"]+\.m3u8[^"]*)"""),
                Regex(""""media"\s*:\s*\{[^}]*"src"\s*:\s*"([^"]+\.m3u8[^"]*)"""),
                Regex("""https://[^"\s]+\.m3u8[^"\s]*""")
            )
            
            patterns.forEach { pattern ->
                val match = pattern.find(scriptContent)
                if (match != null && (match.value.contains("trt.com.tr") || match.groupValues.getOrNull(1)?.contains("trt.com.tr") == true)) {
                    return match.groupValues.getOrNull(1) ?: match.value
                }
            }
        }
        return null
    }

    private fun findYouTubeUrl(document: org.jsoup.nodes.Document): String? {
        // Look for YouTube embed in iframe
        val iframe = document.selectFirst("iframe[src*='youtube.com/embed']")
        if (iframe != null) {
            val embedUrl = iframe.attr("src")
            val videoId = embedUrl.substringAfter("embed/").substringBefore("?")
            return "https://www.youtube.com/watch?v=$videoId"
        }
        
        // Look in script tags
        val scripts = document.select("script")
        for (script in scripts) {
            val scriptContent = script.html()
            
            val patterns = listOf(
                Regex("""https://www\.youtube\.com/watch\?v=([^"']+)"""),
                Regex("""https://www\.youtube\.com/embed/([a-zA-Z0-9_-]+)"""),
                Regex(""""contentUrl"\s*:\s*"https://www\.youtube\.com/watch\?v=([^"]+)""")
            )
            
            patterns.forEach { pattern ->
                val match = pattern.find(scriptContent)
                if (match != null) {
                    return if (match.value.contains("embed")) {
                        "https://www.youtube.com/watch?v=${match.groupValues[1]}"
                    } else {
                        match.value
                    }
                }
            }
        }
        
        return null
    }

    private suspend fun handleYouTubeVideo(
        youtubeUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val videoId = youtubeUrl.substringAfter("v=").substringBefore("&")
        val watchUrl = "https://www.youtube.com/watch?v=$videoId"

        val extractedLinks = mutableListOf<ExtractorLink>()

        loadExtractor(
            watchUrl,
            referer = "https://www.youtube.com/",
            subtitleCallback = subtitleCallback
        ) { link ->
            extractedLinks.add(link)
        }

        if (extractedLinks.isEmpty()) return false

        for (link in extractedLinks) {
            callback(
                newExtractorLink(
                    name = "YouTube",
                    source = "YouTube",
                    url = link.url
                ) {
                    this.quality = link.quality
                    this.referer = "https://www.youtube.com/"
                    this.headers = link.headers ?: mapOf("User-Agent" to "Mozilla/5.0")
                }
            )
        }

        return true
    }

    private fun getQualityName(quality: Int): String {
        return when (quality) {
            Qualities.P480.value -> "480p"
            Qualities.P360.value -> "360p"
            Qualities.P720.value -> "720p"
            Qualities.P1080.value -> "1080p"
            Qualities.P1440.value -> "1440p"
            else -> "Unknown"
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        
        // Search in live channels
        val channels = getTabiiChannels()
        println("DEBUG: Search - Found ${channels.size} channels to search")
        channels.filter { it.name.contains(query, ignoreCase = true) }
            .forEach { channel ->
                val mainStream = channel.streamUrls.firstOrNull() ?: ""
                results.add(
                    newMovieSearchResponse(channel.name, mainStream, TvType.Live) {
                        this.posterUrl = channel.logoUrl
                    }
                )
            }
        
        // Search in TRT series
        val searchUrl = "$trt1Url/arama/${query}?contenttype=series"
        try {
            val document = app.get(searchUrl).document
            document.select("div.grid_grid-wrapper__elAnh > div.h-full.w-full > a").mapNotNull { element ->
                val title = element.selectFirst("div.card_card-title__IJ9af")?.text()?.trim() ?: return@mapNotNull null
                val href = element.attr("href")
                var posterUrl = element.selectFirst("img")?.attr("src")
                
                posterUrl = fixPosterUrlHorizontal(posterUrl)
                
                if (href.contains("/diziler/")) {
                    results.add(
                        newTvSeriesSearchResponse(title, fixTrtUrl(href)) {
                            this.posterUrl = posterUrl
                        }
                    )
                }
            }
        } catch (e: Exception) {
            // Ignore search errors
        }
        
        println("DEBUG: Search - Found ${results.size} results for query: $query")
        return results
    }
}