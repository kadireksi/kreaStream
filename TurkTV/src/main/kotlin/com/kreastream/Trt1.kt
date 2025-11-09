package com.kreastream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
//import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject


class Trt1 : MainAPI() {
    override var mainUrl = "https://www.trt1.com.tr"
    override var name = "TRT1"
    override val supportedTypes = setOf(TvType.TvSeries)
    override var lang = "tr"

    override val mainPage = mainPageOf(
        "$mainUrl/diziler?archive=false&order=title_asc" to "Güncel Diziler",
        "$mainUrl/diziler?archive=true&order=title_asc" to "Eski Diziler",
        //"$mainUrl/diziler?archive=false" to "Güncel Diziler",
        //"$mainUrl/diziler?archive=true" to "Eski Diziler"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) {
            request.data
        } else {
            // Handle pagination for archive (old series)
            if (request.data.contains("archive=true")) {
                "$mainUrl/diziler/$page?archive=true" + if (request.data.contains("order=title_asc")) "&order=title_asc" else ""
            } else {
                // For current series, there might be no pagination
                return newHomePageResponse(emptyList())
            }
        }

        try {
            val document = app.get(url).document
            val home = document.select("div.grid_grid-wrapper__elAnh > div.h-full.w-full > a").mapNotNull {
                it.toSearchResult()
            }

            return newHomePageResponse(
                listOf(
                    HomePageList(
                        name = request.name,
                        list = home,
                        isHorizontalImages = false
                    )
                )
            )
        } catch (e: Exception) {
            // Return empty list if page doesn't exist
            return newHomePageResponse(emptyList())
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("div.card_card-title__IJ9af")?.text()?.trim() ?: return null
        var href = this.attr("href")
        var posterUrl = this.selectFirst("img")?.attr("src")
        
        // Fix poster URL for continue watching
        posterUrl = fixPosterUrl(posterUrl)
        
        // Ensure href is an absolute URL that TurkTV can route correctly
        href = when {
            href.startsWith("http") -> href
            href.startsWith("/diziler/") -> "https://www.trt1.com.tr$href"
            href.startsWith("/") -> "https://www.trt1.com.tr$href"
            else -> "https://www.trt1.com.tr/$href"
        }
        
        // Make sure it's a TRT1 URL that TurkTV can recognize
        if (!href.contains("trt1.com.tr")) {
            href = "https://www.trt1.com.tr/diziler/$href"
        }
        
        return newTvSeriesSearchResponse(title, href) {
            this.posterUrl = posterUrl
        }
    }

    private fun fixPosterUrl(url: String?): String? {
        return url?.replace("webp/w800/h450", "webp/w400/h600")?.replace("/q75/", "/q85/")
    }

    private fun fixUrl(url: String): String {
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "https://www.trt1.com.tr$url"
            else -> "https://www.trt1.com.tr/diziler/$url"
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/arama/${query}?contenttype=series"
        val document = app.get(searchUrl).document
        
        return document.select("div.grid_grid-wrapper__elAnh > div.h-full.w-full > a").mapNotNull { element ->
            val title = element.selectFirst("div.card_card-title__IJ9af")?.text()?.trim() ?: return@mapNotNull null
            var href = fixUrl(element.attr("href"))
            var posterUrl = element.selectFirst("img")?.attr("src")
            
            // Fix poster URL
            posterUrl = fixPosterUrl(posterUrl)
            
            // Format URL to be recognizable by TurkTV
            href = when {
                href.startsWith("http") -> href
                href.startsWith("/diziler/") -> "https://www.trt1.com.tr$href"
                href.startsWith("/") -> "https://www.trt1.com.tr$href"
                else -> "https://www.trt1.com.tr/diziler/$href"
            }
            
            // Check if it's a series (diziler in URL)
            if (href.contains("/diziler/")) {
                newTvSeriesSearchResponse(title, href) {
                    this.posterUrl = posterUrl
                }
            } else {
                null // Skip non-series results
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        try {
            val document = app.get(url).document
            
            val title = document.selectFirst("h1")?.text()?.trim() ?: throw ErrorLoadingException("Title not found")
            val description = document.selectFirst("meta[name=description]")?.attr("content") ?: ""
            var poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            
            // Fix poster URL for better quality in continue watching
            poster = fixPosterUrl(poster)
            
            val episodes = mutableListOf<Episode>()
            
            // Get episodes from the bolum page
            val seriesSlug = url.removePrefix("https://www.trt1.com.tr/diziler/").removeSuffix("/")
            val episodesUrl = "https://www.trt1.com.tr/diziler/$seriesSlug/bolum"
        
        // Function to parse episodes from a page with proper pagination
        suspend fun parseEpisodesPage(pageUrl: String): List<Episode> {
            val episodeDoc = app.get(pageUrl).document
            return episodeDoc.select("div.grid_grid-wrapper__elAnh > div.h-full.w-full > a").mapNotNull { element ->
                val epTitle = element.selectFirst("div.card_card-title__IJ9af")?.text()?.trim() ?: return@mapNotNull null
                val epHref = fixUrl(element.attr("href"))
                var epPoster = element.selectFirst("img")?.attr("src")
                val epDescription = element.selectFirst("p.card_card-description__0PSTi")?.text()?.trim() ?: ""
                
                // Fix episode poster URL
                epPoster = fixPosterUrl(epPoster)
                
                // Extract episode number from title (e.g., "191. Bölüm" -> 191)
                val episodeNumber = epTitle.replace(Regex("[^0-9]"), "").toIntOrNull()
                
                newEpisode(fixUrl(epHref)) {
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
            // If episodes page doesn't exist, try alternative episode structure
            val alternativeEpisodes = document.select("a[href*='/bolum/']").mapNotNull { element ->
                val epHref = fixUrl(element.attr("href"))
                if (epHref.contains("/bolum/") && !epHref.contains("/bolum/1")) {
                    val epTitle = element.selectFirst("div, span, h3")?.text()?.trim() ?: "Bölüm"
                    val epPoster = element.selectFirst("img")?.attr("src")?.let { fixPosterUrl(it) }
                    
                    newEpisode(fixUrl(epHref)) {
                        this.name = epTitle
                        this.posterUrl = epPoster
                    }
                } else {
                    null
                }
            }
            episodes.addAll(alternativeEpisodes)
        }
        
        // Handle pagination for episodes - get pagination from the episodes page, not series page
        if (episodes.isNotEmpty()) {
            try {
                val episodesDocument = app.get(episodesUrl).document
                val pagination = episodesDocument.select("div.pagination_wrapper__FpNrb a.pagination_item__PAJVt")
                if (pagination.isNotEmpty()) {
                    // Find the last page number from pagination
                    val lastPage = pagination.mapNotNull { 
                        it.text().toIntOrNull() 
                    }.maxOrNull() ?: 1
                    
                    // Get episodes from all pages
                    for (page in 2..lastPage) {
                        val pageUrl = "$episodesUrl/$page"
                        try {
                            val pageEpisodes = parseEpisodesPage(pageUrl)
                            if (pageEpisodes.isNotEmpty()) {
                                episodes.addAll(pageEpisodes)
                            } else {
                                break
                            }
                        } catch (e: Exception) {
                            // If page doesn't exist or error occurs, break
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                // If we can't get pagination, just use the episodes we have
            }
        }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
            }
        } catch (e: Exception) {
            throw ErrorLoadingException("Failed to load TRT1 series: ${e.message}")
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        
        // Method 1: Try to find video in embedded players
        val videoSources = document.select("video source")
        for (source in videoSources) {
            val src = source.attr("src")
            if (src.contains(".m3u8")) {
                M3u8Helper.generateM3u8(
                    name,
                    src,
                    "$mainUrl/",
                    headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                        "Referer" to "$mainUrl/",
                        "Origin" to mainUrl
                    )
                ).forEach(callback)
                return true
            }
        }
        
        // Method 2: Look for m3u8 in script tags with better patterns
        val m3u8Url = findM3u8UrlImproved(document)
        if (m3u8Url != null) {
            M3u8Helper.generateM3u8(
                name,
                m3u8Url,
                "$mainUrl/",
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    "Referer" to "$mainUrl/",
                    "Origin" to mainUrl
                )
            ).forEach(callback)
            return true
        }
        
        // Method 3: Try YouTube
        val youtubeUrl = findYouTubeUrl(document)
        if (youtubeUrl != null) {
            return loadExtractor(youtubeUrl, "$mainUrl/", subtitleCallback, callback)
        }
        
        // Method 4: Try generic extractor as fallback
        return loadExtractor(data, "$mainUrl/", subtitleCallback, callback)
    }

    private fun findM3u8UrlImproved(document: org.jsoup.nodes.Document): String? {
        val scripts = document.select("script")
        
        // Look for JSON data with video sources
        for (script in scripts) {
            val scriptContent = script.html()
            
            // Pattern 1: Look for video sources in JSON
            val jsonPattern = Regex("""(?i)"(?:video|file|url|src)"\s*:\s*"([^"]+\.m3u8[^"]*)""")
            val matches = jsonPattern.findAll(scriptContent)
            for (match in matches) {
                val url = match.groupValues[1]
                if (url.contains("trt") && url.contains(".m3u8")) {
                    return url
                }
            }
            
            // Pattern 2: Look for base64 encoded JSON
            if (scriptContent.contains("base64") || scriptContent.contains("JSON.parse")) {
                try {
                    // Try to extract URLs from complex JSON structures
                    val urlPattern = Regex("""https://[^"\s]+\.m3u8[^"\s]*""")
                    val urlMatches = urlPattern.findAll(scriptContent)
                    for (urlMatch in urlMatches) {
                        val url = urlMatch.value
                        if (url.contains("trt") && url.endsWith(".m3u8")) {
                            return url
                        }
                    }
                } catch (e: Exception) {
                    // Continue to next pattern
                }
            }
        }
        
        // Look for data attributes
        val videoElements = document.select("[data-video-src], [data-src]")
        for (element in videoElements) {
            val src = element.attr("data-video-src") ?: element.attr("data-src")
            if (src.contains(".m3u8")) {
                return src
            }
        }
        
        return null
    }

    private fun findM3u8Url(document: org.jsoup.nodes.Document): String? {
        // Look for m3u8 URLs in script tags with JSON data
        val scripts = document.select("script")
        for (script in scripts) {
            val scriptContent = script.html()
            
            // Pattern 1: Look for "mediaSrc" with m3u8 URL
            val mediaSrcPattern = Regex(""""mediaSrc"\s*:\s*\[\s*\{[^}]*"url"\s*:\s*"([^"]+\.m3u8[^"]*)""")
            val mediaSrcMatch = mediaSrcPattern.find(scriptContent)
            if (mediaSrcMatch != null) {
                return mediaSrcMatch.groupValues[1]
            }
            
            // Pattern 2: Look for "src" in media object with m3u8 URL
            val mediaSrcPattern2 = Regex(""""media"\s*:\s*\{[^}]*"src"\s*:\s*"([^"]+\.m3u8[^"]*)""")
            val mediaSrcMatch2 = mediaSrcPattern2.find(scriptContent)
            if (mediaSrcMatch2 != null) {
                return mediaSrcMatch2.groupValues[1]
            }
            
            // Pattern 3: Look for direct m3u8 URLs in the script
            val m3u8Pattern = Regex("""https://[^"\s]+\.m3u8[^"\s]*""")
            val m3u8Match = m3u8Pattern.find(scriptContent)
            if (m3u8Match != null && m3u8Match.value.contains("trt.com.tr")) {
                return m3u8Match.value
            }
        }
        
        return null
    }

    private fun findYouTubeUrl(document: org.jsoup.nodes.Document): String? {
        // Look for YouTube embed in iframe
        val iframe = document.selectFirst("iframe[src*='youtube.com/embed']")
        if (iframe != null) {
            val embedUrl = iframe.attr("src")
            // Extract video ID and create direct YouTube URL
            val videoId = embedUrl.substringAfter("embed/").substringBefore("?")
            return "https://www.youtube.com/watch?v=$videoId"
        }
        
        // Look for YouTube URL in script tags
        val scripts = document.select("script")
        for (script in scripts) {
            val scriptContent = script.html()
            
            // Look for YouTube watch URLs
            if (scriptContent.contains("youtube.com/watch")) {
                val regex = Regex("""https://www\.youtube\.com/watch\?v=([^"']+)""")
                val match = regex.find(scriptContent)
                if (match != null) {
                    return match.value
                }
            }
            
            // Look for YouTube embed URLs that might be in JSON
            if (scriptContent.contains("youtube.com/embed")) {
                val regex = Regex("""https://www\.youtube\.com/embed/([a-zA-Z0-9_-]+)""")
                val match = regex.find(scriptContent)
                if (match != null) {
                    val videoId = match.groupValues[1]
                    return "https://www.youtube.com/watch?v=$videoId"
                }
            }
            
            // Look for video IDs in contentUrl
            if (scriptContent.contains("contentUrl")) {
                val regex = Regex(""""contentUrl"\s*:\s*"https://www\.youtube\.com/watch\?v=([^"]+)""")
                val match = regex.find(scriptContent)
                if (match != null) {
                    return "https://www.youtube.com/watch?v=${match.groupValues[1]}"
                }
            }
        }
        
        // Look for canonical link
        val canonical = document.selectFirst("link[rel=canonical]")
        if (canonical != null) {
            val canonicalUrl = canonical.attr("href")
            if (canonicalUrl.contains("youtube.com/watch")) {
                return canonicalUrl
            }
        }

        return null
    }

    private suspend fun getYoutubeStreams(videoId: String): List<Pair<String, Int>> {
        val apiUrl = "https://www.youtube.com/youtubei/v1/player?key=AIzaSyAO_FQyR3E6Uo7I0QFh0v4H4KQj6YkzZ5Y"
        val payload = """
            {
            "context": {
                "client": {
                "hl": "tr",
                "clientName": "WEB",
                "clientVersion": "2.20240725.00.00"
                }
            },
            "videoId": "$videoId"
            }
        """.trimIndent()

        val response = app.post(
            apiUrl,
            requestBody = payload.toRequestBody("application/json".toMediaType())
        ).text

        val result = mutableListOf<Pair<String, Int>>()

        try {
            val root = org.json.JSONObject(response)
            val streamingData = root.optJSONObject("streamingData") ?: return emptyList()
            val formats = streamingData.optJSONArray("formats") ?: return emptyList()

            for (i in 0 until formats.length()) {
                val fmt = formats.optJSONObject(i) ?: continue
                val url = fmt.optString("url", "")
                if (url.isBlank()) continue

                val qualityLabel = fmt.optString("qualityLabel", "Unknown")
                val q = qualityLabel.replace("p", "").toIntOrNull() ?: Qualities.Unknown.value

                result.add(Pair(url, q))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return result
    }

    private suspend fun handleYouTubeVideo(
        youtubeUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val videoId = youtubeUrl.substringAfter("v=").substringBefore("&")
        val watchUrl = "https://www.youtube.com/watch?v=$videoId"

        // Ask the core extractor dispatcher to resolve YouTube
        val extractedLinks = mutableListOf<ExtractorLink>()

        // This internally calls the YouTube extractor (multi-quality)
        loadExtractor(
            watchUrl,
            referer = "https://www.youtube.com/",
            subtitleCallback = subtitleCallback
        ) { link ->
            extractedLinks.add(link)
        }

        if (extractedLinks.isEmpty()) return false

        // Wrap every resolved stream into newExtractorLink
        for (link in extractedLinks) {
            callback(
                newExtractorLink(
                    name = "YouTube",
                    source = "YouTube",
                    url = link.url
                ) {
                    this.quality = Qualities.P720.value
                    this.referer = "https://www.youtube.com/"
                    //this.isM3u8 = link.isM3u8
                    this.headers = link.headers ?: mapOf("User-Agent" to "Mozilla/5.0")
                }
            )
        }

        return true
    }

    private suspend fun debugPage(url: String) {
        try {
            val document = app.get(url).document
            println("=== DEBUG PAGE: $url ===")
            println("Title: ${document.title()}")
            
            // Log all script tags that might contain video data
            val scripts = document.select("script")
            scripts.forEachIndexed { index, script ->
                val content = script.html()
                if (content.contains("m3u8") || content.contains("youtube") || content.contains("video")) {
                    println("Script $index contains video references")
                }
            }
            
            // Log all video elements
            val videos = document.select("video")
            println("Found ${videos.size} video elements")
            
            videos.forEachIndexed { index, video ->
                println("Video $index sources:")
                video.select("source").forEach { source ->
                    println("  - ${source.attr("src")}")
                }
            }
            
        } catch (e: Exception) {
            println("Debug error: ${e.message}")
        }
    }

}
