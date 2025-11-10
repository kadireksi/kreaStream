package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class Trt : MainAPI() {
    override var mainUrl = "https://www.tabii.com"
    override var name = "TRT"
    override val supportedTypes = setOf(TvType.Live, TvType.TvSeries)
    override var lang = "tr"
    override var hasMainPage = true

    private val tabiiUrl = "https://www.tabii.com/tr"
    private val trt1Url = "https://www.trt1.com.tr"

    override val mainPage = mainPageOf(
        "live" to "Canlı Yayınlar",
        "series" to "Güncel Diziler", 
        "archive" to "Eski Diziler"
    )

    // TRT Channel list with their tabii watch URLs
    private val trtChannels = listOf(
        "TRT 1" to "trt1",
        "TRT 2" to "trt2", 
        "TRT 3" to "trt3",
        "TRT Haber" to "trthaber",
        "TRT Spor" to "trtspor",
        "TRT Spor Yıldız" to "trtsporyildiz",
        "TRT Belgesel" to "trtbelgesel",
        "TRT Çocuk" to "trtcocuk",
        "TRT Çocuk Diyanet" to "trtdiyanetcocuk",
        "TRT Müzik" to "trtmuzik",
        "TRT Arabi" to "trtarabi",
        "TRT Avaz" to "trtavaz",
        "TRT Türk" to "trtturk",
        "TRT World" to "trtworld",
        "TRT Kurdi" to "trtkurdi"
    )

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

    // Replace the getTabiiChannels function with this:
    private suspend fun getTabiiChannels(): List<TabiiChannel> {
        val channels = mutableListOf<TabiiChannel>()
        
        try {
            // First, get the main tabii page to find all available channels
            val mainDocument = app.get(tabiiUrl).document
            
            // Look for channel links in the main page
            val channelLinks = mainDocument.select("a[href*='/watch/live/']")
            
            println("DEBUG: Found ${channelLinks.size} channel links on main page")
            
            // Extract channel slugs from URLs
            val channelSlugs = channelLinks.mapNotNull { link ->
                val href = link.attr("href")
                Regex("/watch/live/([^/?]+)").find(href)?.groupValues?.get(1)
            }.distinct()
            
            println("DEBUG: Found ${channelSlugs.size} unique channel slugs: $channelSlugs")
            
            // Process each channel
            for (channelSlug in channelSlugs) {
                try {
                    val watchUrl = "$tabiiUrl/watch/live/$channelSlug?trackId=150002"
                    println("DEBUG: Processing channel: $channelSlug from $watchUrl")
                    
                    val document = app.get(watchUrl).document
                    
                    // Extract channel name from the page
                    val channelName = extractChannelName(document, channelSlug)
                    
                    // Try to find m3u8 URL in the page
                    val m3u8Url = findM3u8UrlInTabii(document)
                    
                    // Try to find logo URL in the page
                    val logoUrl = findLogoUrlInTabii(document, channelName)
                    
                    if (m3u8Url != null && channelName.isNotEmpty()) {
                        // Generate multiple quality variants
                        val streamUrls = generateQualityVariants(m3u8Url)
                        
                        channels.add(
                            TabiiChannel(
                                name = channelName,
                                streamUrls = streamUrls,
                                logoUrl = logoUrl,
                                description = "$channelName canlı yayın"
                            )
                        )
                        println("DEBUG: Successfully added channel: $channelName with ${streamUrls.size} quality variants")
                    } else {
                        println("DEBUG: No m3u8 URL or channel name found for: $channelSlug")
                    }
                    
                    // Add delay to avoid rate limiting
                    kotlinx.coroutines.delay(200)
                    
                } catch (e: Exception) {
                    println("DEBUG: Error processing channel $channelSlug: ${e.message}")
                }
            }
            
        } catch (e: Exception) {
            println("DEBUG: Error fetching main tabii page: ${e.message}")
        }
        
        println("DEBUG: Total channels found: ${channels.size}")
        return channels
    }

    // Add this function to extract channel name from the page:
    private fun extractChannelName(document: org.jsoup.nodes.Document, channelSlug: String): String {
        // Method 1: Look for title or h1 tags
        val title = document.selectFirst("h1, title")?.text()?.trim()
        if (!title.isNullOrEmpty() && title.contains("TRT", ignoreCase = true)) {
            return title.substringBefore("|").substringBefore("-").trim()
        }
        
        // Method 2: Look for meta tags
        val metaTitle = document.selectFirst("meta[property='og:title']")?.attr("content")
        if (!metaTitle.isNullOrEmpty() && metaTitle.contains("TRT", ignoreCase = true)) {
            return metaTitle.substringBefore("|").substringBefore("-").trim()
        }
        
        // Method 3: Look for channel name in specific elements
        val channelNameElements = document.select("[class*='channel'], [class*='title'], [class*='name']")
        for (element in channelNameElements) {
            val text = element.text().trim()
            if (text.contains("TRT", ignoreCase = true) && text.length < 50) {
                return text
            }
        }
        
        // Method 4: Generate name from slug as fallback
        return channelSlug.replaceFirstChar { it.uppercase() }
            .replace("trt", "TRT ")
            .replace(Regex("[^a-zA-Z0-9 ]"), " ")
            .trim()
    }

    // Update the findM3u8UrlInTabii function to be more specific:
    private fun findM3u8UrlInTabii(document: org.jsoup.nodes.Document): String? {
        val scripts = document.select("script")
        for (script in scripts) {
            val scriptContent = script.html()
            
            // Look for specific TRT m3u8 patterns
            val patterns = listOf(
                Regex("""https://[^"']*trt[^"']*\.medya\.trt\.com\.tr[^"']*\.m3u8[^"']*"""),
                Regex("""https://[^"']*medya\.trt\.com\.tr[^"']*\.m3u8[^"']*"""),
                Regex("""["']?url["']?\s*:\s*["']([^"']+trt[^"']+\.m3u8[^"']*)["']"""),
                Regex("""["']?src["']?\s*:\s*["']([^"']+trt[^"']+\.m3u8[^"']*)["']"""),
                Regex("""["']?streamUrl["']?\s*:\s*["']([^"']+\.m3u8[^"']*)["']""")
            )
            
            for (pattern in patterns) {
                val matches = pattern.findAll(scriptContent)
                matches.forEach { match ->
                    val url = match.groupValues.getOrNull(1) ?: match.value
                    if (url.contains("m3u8") && url.contains("trt")) {
                        println("DEBUG: Found TRT m3u8 URL: $url")
                        return url
                    }
                }
            }
        }
        
        // Also check video elements and sources
        val videoSources = document.select("video source[src*=.m3u8], source[src*=.m3u8]")
        for (source in videoSources) {
            val src = source.attr("src")
            if (src.contains("trt")) {
                println("DEBUG: Found TRT m3u8 in source element: $src")
                return src
            }
        }
        
        return null
    }

    // Update the generateQualityVariants function:
    private fun generateQualityVariants(baseUrl: String): List<String> {
        val variants = mutableListOf(baseUrl)
        
        try {
            // Only generate variants for TRT URLs
            if (baseUrl.contains("trt.com.tr")) {
                val basePath = baseUrl.substringBeforeLast(".").removeSuffix("_master").removeSuffix("_360").removeSuffix("_480").removeSuffix("_720").removeSuffix("_1080").removeSuffix("_1440")
                val qualities = listOf("360", "480", "720", "1080", "1440")
                
                qualities.forEach { quality ->
                    val variantUrl = "${basePath}_${quality}.m3u8"
                    variants.add(variantUrl)
                }
                
                // Also try the master URL without quality suffix
                val masterUrl = "${basePath}.m3u8"
                if (masterUrl != baseUrl) {
                    variants.add(masterUrl)
                }
            }
        } catch (e: Exception) {
            println("DEBUG: Error generating quality variants: ${e.message}")
        }
        
        return variants.distinct()
    }

    private fun findLogoUrlInTabii(document: org.jsoup.nodes.Document, channelName: String): String {
        // Method 1: Look for logo in meta tags
        val metaLogo = document.selectFirst("meta[property='og:image']")?.attr("content")
        if (!metaLogo.isNullOrEmpty() && metaLogo.contains("logo", ignoreCase = true)) {
            return fixLogoUrl(metaLogo)
        }

        // Method 2: Look for logo in JSON-LD structured data
        val jsonLdScripts = document.select("script[type='application/ld+json']")
        for (script in jsonLdScripts) {
            val jsonContent = script.html()
            try {
                val logoPattern = Regex(""""logo"\s*:\s*"([^"]+)""")
                val match = logoPattern.find(jsonContent)
                if (match != null) {
                    val logoUrl = match.groupValues[1]
                    if (logoUrl.isNotEmpty()) {
                        return fixLogoUrl(logoUrl)
                    }
                }
            } catch (e: Exception) {
                // Continue to next method if parsing fails
            }
        }

        // Method 3: Look for logo in image tags with specific classes or attributes
        val logoImages = document.select("img[src*='logo'], img[alt*='${channelName}'], img[class*='logo'], img[class*='channel']")
        for (img in logoImages) {
            val src = img.attr("src")
            if (src.isNotEmpty() && (src.contains("logo", ignoreCase = true) || src.contains(channelName, ignoreCase = true))) {
                return fixLogoUrl(src)
            }
        }

        // Method 4: Look for logo in header or specific sections
        val headerLogo = document.selectFirst("header img, .header img, .logo img, .channel-logo img")?.attr("src")
        if (!headerLogo.isNullOrEmpty()) {
            return fixLogoUrl(headerLogo)
        }

        // Method 5: Look for any image that might be a logo based on common patterns
        val allImages = document.select("img")
        for (img in allImages) {
            val src = img.attr("src")
            val alt = img.attr("alt")
            val srcLower = src.lowercase()
            
            // Check if this looks like a logo based on common patterns
            if (src.isNotEmpty() && (
                srcLower.contains("logo") ||
                srcLower.contains("channel") ||
                srcLower.contains("trt") ||
                alt.contains(channelName, ignoreCase = true) ||
                srcLower.contains(channelName.lowercase().replace(" ", ""))
            )) {
                return fixLogoUrl(src)
            }
        }

        // Method 6: Look for logo in CSS background images
        val elementsWithBackground = document.select("[style*='background-image']")
        for (element in elementsWithBackground) {
            val style = element.attr("style")
            val bgPattern = Regex("""background-image\s*:\s*url\(['"]?([^'")]+)['"]?\)""")
            val match = bgPattern.find(style)
            if (match != null) {
                val bgUrl = match.groupValues[1]
                if (bgUrl.contains("logo", ignoreCase = true) || bgUrl.contains("trt", ignoreCase = true)) {
                    return fixLogoUrl(bgUrl)
                }
            }
        }

        // Fallback: Use default TRT logo if nothing found
        return "https://www.tabii.com/static/images/trt-logo.png"
    }

    private fun fixLogoUrl(url: String): String {
        var fixedUrl = url
        // Ensure the URL is absolute
        if (fixedUrl.startsWith("//")) {
            fixedUrl = "https:$fixedUrl"
        } else if (fixedUrl.startsWith("/")) {
            fixedUrl = "$tabiiUrl$fixedUrl"
        }
        
        // Remove any query parameters that might affect image quality
        fixedUrl = fixedUrl.substringBefore("?")
        
        // Ensure we're getting a good quality version
        if (fixedUrl.contains("w=") || fixedUrl.contains("width=")) {
            // Replace with higher quality parameters if possible
            fixedUrl = fixedUrl.replace(Regex("w=\\d+"), "w=400")
                .replace(Regex("width=\\d+"), "width=400")
                .replace(Regex("h=\\d+"), "h=400")
                .replace(Regex("height=\\d+"), "height=400")
        }
        
        return fixedUrl
    }

    private suspend fun getTrtSeries(archive: Boolean = false, page: Int = 1): List<TrtSeries> {
        val url = if (page == 1) {
            "$trt1Url/diziler?archive=$archive&order=title_asc"
        } else {
            "$trt1Url/diziler/$page?archive=$archive&order=title_asc"
        }
        
        println("DEBUG: Fetching series from: $url")
        val document = app.get(url).document
        
        val series = document.select("div.grid_grid-wrapper__elAnh > div.h-full.w-full > a").mapNotNull { element ->
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
        
        println("DEBUG: Found ${series.size} series on page $page")
        return series
    }

    private fun fixPosterUrlHorizontal(url: String?): String? {
        return url?.replace("webp/w800/h450", "webp/w600/h338")
            ?.replace("webp/w400/h600", "webp/w600/h338")
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
                println("DEBUG: MainPage Live - Found ${channels.size} channels")
                channels.map { channel ->
                    val mainStream = channel.streamUrls.firstOrNull() ?: ""
                    newMovieSearchResponse(channel.name, mainStream, TvType.Live) {
                        this.posterUrl = channel.logoUrl
                    }
                }
            }
            "series" -> {
                getTrtSeries(archive = false, page = page)
                    .map { series ->
                        newTvSeriesSearchResponse(series.title, series.url) {
                            this.posterUrl = series.posterUrl
                        }
                    }
            }
            "archive" -> {
                getTrtSeries(archive = true, page = page)
                    .map { series ->
                        newTvSeriesSearchResponse(series.title, series.url) {
                            this.posterUrl = series.posterUrl
                        }
                    }
            }
            else -> emptyList()
        }

        println("DEBUG: MainPage - ${request.name} page $page has ${items.size} items")

        val hasNext = when (request.data) {
            "series", "archive" -> {
                items.isNotEmpty()
            }
            else -> false
        }

        return newHomePageResponse(
            listOf(
                HomePageList(
                    name = request.name,
                    list = items,
                    isHorizontalImages = true
                )
            ),
            hasNext = hasNext
        )
    }

    override suspend fun load(url: String): LoadResponse {
        // Check if it's a live channel URL
        if (url.contains("m3u8") || trtChannels.any { url.contains(it.second, ignoreCase = true) }) {
            // Find the channel that matches this URL
            val channels = getTabiiChannels()
            val liveChannel = channels.find { channel ->
                channel.streamUrls.contains(url) || url.contains(channel.name, ignoreCase = true)
            }

            if (liveChannel != null) {
                return newMovieLoadResponse(liveChannel.name, url, TvType.Live, url) {
                    this.posterUrl = liveChannel.logoUrl
                    this.plot = liveChannel.description
                }
            }
        }

        // Otherwise, treat it as a series
        val document = app.get(url).document
        
        val title = document.selectFirst("h1")?.text()?.trim() ?: throw ErrorLoadingException("Title not found")
        val description = document.selectFirst("meta[name=description]")?.attr("content") ?: ""
        var poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        
        poster = fixPosterUrlHorizontal(poster)
        
        val episodes = mutableListOf<Episode>()
        
        // Get episodes from the bolum page with pagination
        val seriesSlug = url.removePrefix("$trt1Url/diziler/").substringBefore("?")
        var currentPage = 1
        var hasMoreEpisodes = true
        
        while (hasMoreEpisodes && currentPage <= 50) { // Safety limit
            try {
                val episodesUrl = if (currentPage == 1) {
                    "$trt1Url/diziler/$seriesSlug/bolum"
                } else {
                    "$trt1Url/diziler/$seriesSlug/bolum/$currentPage"
                }
                
                println("DEBUG: Loading episodes from: $episodesUrl")
                val episodeDoc = app.get(episodesUrl).document
                
                val pageEpisodes = episodeDoc.select("div.grid_grid-wrapper__elAnh > div.h-full.w-full > a").mapNotNull { element ->
                    val epTitle = element.selectFirst("div.card_card-title__IJ9af")?.text()?.trim() ?: return@mapNotNull null
                    val epHref = element.attr("href")
                    var epPoster = element.selectFirst("img")?.attr("src")
                    val epDescription = element.selectFirst("p.card_card-description__0PSTi")?.text()?.trim() ?: ""
                    
                    epPoster = fixPosterUrlHorizontal(epPoster)
                    
                    val episodeNumber = epTitle.replace(Regex("[^0-9]"), "").toIntOrNull() ?: currentPage
                    
                    newEpisode(fixTrtUrl(epHref)) {
                        this.name = epTitle
                        this.posterUrl = epPoster
                        this.episode = episodeNumber
                        this.description = epDescription
                    }
                }
                
                if (pageEpisodes.isNotEmpty()) {
                    episodes.addAll(pageEpisodes)
                    currentPage++
                    // Add small delay to avoid rate limiting
                    kotlinx.coroutines.delay(100)
                } else {
                    hasMoreEpisodes = false
                }
                
                // Safety check: don't load too many pages if there are many episodes
                if (episodes.size > 200) {
                    hasMoreEpisodes = false
                }
                
            } catch (e: Exception) {
                println("DEBUG: Error loading episode page $currentPage: ${e.message}")
                hasMoreEpisodes = false
            }
        }

        println("DEBUG: Loaded ${episodes.size} episodes for series: $title")

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
        if (data.contains("m3u8")) {
            // For m3u8 streams, use M3u8Helper to get all quality variants
            M3u8Helper.generateM3u8(
                name,
                data,
                "$tabiiUrl/",
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    "Referer" to tabiiUrl
                )
            ).forEach(callback)
            return true
        }

        // Handle series episodes (YouTube or m3u8)
        val document = app.get(data).document
        
        // Look for m3u8 streams
        val m3u8Url = findM3u8UrlInTabii(document)
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