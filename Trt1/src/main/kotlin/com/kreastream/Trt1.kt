package com.lagradost.cloudstream3.trt1

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class TRT1Provider : MainAPI() {
    override var mainUrl = "https://www.trt1.com.tr"
    override var name = "TRT1"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/diziler?archive=false&order=title_asc" to "Güncel Diziler (A-Z)",
        "$mainUrl/diziler?archive=true&order=title_asc" to "Eski Diziler (A-Z)",
        "$mainUrl/diziler?archive=false" to "Güncel Diziler",
        "$mainUrl/diziler?archive=true" to "Eski Diziler"
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
                request.data
            }
        }

        val document = app.get(url).document
        val home = document.select("div.grid_grid-wrapper__elAnh > div.h-full.w-full > a").mapNotNull {
            it.toSearchResult()
        }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("div.card_card-title__IJ9af")?.text()?.trim() ?: return null
        val href = this.attr("href")
        val posterUrl = this.selectFirst("img")?.attr("src")
        
        return newTvSeriesSearchResponse(title, href) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return listOf()
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        val title = document.selectFirst("h1")?.text()?.trim() ?: throw ErrorLoadingException("Title not found")
        val description = document.selectFirst("meta[name=description]")?.attr("content") ?: ""
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        
        val episodes = mutableListOf<Episode>()
        
        // Get episodes from the bolum page
        val seriesSlug = url.removePrefix("$mainUrl/diziler/")
        val episodesUrl = "$mainUrl/diziler/$seriesSlug/bolum"
        
        // Function to parse episodes from a page with proper pagination
        suspend fun parseEpisodesPage(pageUrl: String): List<Episode> {
            val episodeDoc = app.get(pageUrl).document
            return episodeDoc.select("div.grid_grid-wrapper__elAnh > div.h-full.w-full > a").mapNotNull { element ->
                val epTitle = element.selectFirst("div.card_card-title__IJ9af")?.text()?.trim() ?: return@mapNotNull null
                val epHref = element.attr("href")
                val epPoster = element.selectFirst("img")?.attr("src")
                val epDescription = element.selectFirst("p.card_card-description__0PSTi")?.text()?.trim() ?: ""
                
                // Extract episode number from title (e.g., "191. Bölüm" -> 191)
                val episodeNumber = epTitle.replace(Regex("[^0-9]"), "").toIntOrNull()
                
                newEpisode("$mainUrl$epHref") {
                    this.name = epTitle
                    this.posterUrl = epPoster
                    this.episode = episodeNumber
                    this.description = epDescription
                }
            }
        }

        // Get first page episodes
        episodes.addAll(parseEpisodesPage(episodesUrl))
        
        // Handle pagination for episodes - get pagination from the episodes page, not series page
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
        val document = app.get(data).document
        
        // First, look for m3u8 streams in JSON data
        val m3u8Url = findM3u8Url(document)
        if (m3u8Url != null) {
            // For m3u8 streams, we need to use M3u8Helper to get quality options
            M3u8Helper.generateM3u8(
                name,
                m3u8Url,
                "$mainUrl/",
                headers = mapOf("Referer" to "$mainUrl/")
            ).forEach(callback)
            return true
        }
        
        // If no m3u8 found, look for YouTube embed
        val youtubeUrl = findYouTubeUrl(document)
        if (youtubeUrl != null) {
            // For YouTube, we need to extract the video ID and use it directly
            return handleYouTubeVideo(youtubeUrl, subtitleCallback, callback)
        }

        return false
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

    private suspend fun handleYouTubeVideo(
        youtubeUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Extract the video ID for direct YouTube access
        val videoId = youtubeUrl.substringAfter("v=").substringBefore("&")
        val directYoutubeUrl = "https://www.youtube.com/watch?v=$videoId"
        
        // Use the standard extractor with the direct YouTube URL
        // This should give us all available qualities since we're using the direct URL
        // instead of the embedded restricted version
        loadExtractor(directYoutubeUrl, directYoutubeUrl, subtitleCallback, callback)
        
        return true
    }
}
