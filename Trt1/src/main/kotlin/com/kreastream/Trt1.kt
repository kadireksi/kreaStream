package com.kreastream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class Trt1 : MainAPI() {
    override var mainUrl = "https://www.trt1.com.tr"
    override var name = "TRT1"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
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
                "$mainUrl/diziler/$page?archive=true"
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
        
        // Function to parse episodes from a page
        fun parseEpisodesPage(pageUrl: String): List<Episode> {
            val episodeDoc = app.get(pageUrl).document
            return episodeDoc.select("div.grid_grid-wrapper__elAnh > div.h-full.w-full > a").mapNotNull { element ->
                val epTitle = element.selectFirst("div.card_card-title__IJ9af")?.text()?.trim() ?: return@mapNotNull null
                val epHref = element.attr("href")
                val epPoster = element.selectFirst("img")?.attr("src")
                val epDescription = element.selectFirst("p.card_card-description__0PSTi")?.text()?.trim() ?: ""
                
                // Extract episode number from title (e.g., "191. Bölüm" -> 191)
                val episodeNumber = epTitle.replace(Regex("[^0-9]"), "").toIntOrNull()
                
                Episode(
                    data = "$mainUrl$epHref",
                    name = epTitle,
                    posterUrl = epPoster,
                    episode = episodeNumber,
                    description = epDescription
                )
            }
        }

        // Get first page episodes
        episodes.addAll(parseEpisodesPage(episodesUrl))
        
        // Handle pagination
        val pagination = document.select("div.pagination_wrapper__FpNrb a.pagination_item__PAJVt")
        if (pagination.isNotEmpty()) {
            val lastPage = pagination.lastOrNull { it.text().matches(Regex("\\d+")) }?.text()?.toIntOrNull() ?: 1
            
            for (page in 2..lastPage) {
                val pageUrl = "$episodesUrl/$page"
                try {
                    episodes.addAll(parseEpisodesPage(pageUrl))
                } catch (e: Exception) {
                    // If page doesn't exist, break
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
        
        // Look for YouTube embed
        val iframe = document.selectFirst("iframe[src*='youtube.com/embed']")
        if (iframe != null) {
            val embedUrl = iframe.attr("src")
            val youtubeUrl = embedUrl.replace("youtube.com/embed", "youtube.com/watch")
            
            // Use YouTube extractor
            val extractor = YouTubeExtractor()
            extractor.getSafeUrl(youtubeUrl)?.forEach { link ->
                callback(link)
            }
            return true
        }
        
        // Alternative: Look for YouTube URL in script tags
        val scripts = document.select("script")
        for (script in scripts) {
            val scriptContent = script.html()
            if (scriptContent.contains("youtube.com/watch")) {
                val regex = Regex("""https://www\.youtube\.com/watch\?v=([^"']+)""")
                val match = regex.find(scriptContent)
                if (match != null) {
                    val youtubeUrl = match.value
                    val extractor = YouTubeExtractor()
                    extractor.getSafeUrl(youtubeUrl)?.forEach { link ->
                        callback(link)
                    }
                    return true
                }
            }
        }
        
        // Alternative: Look for canonical link
        val canonical = document.selectFirst("link[rel=canonical]")
        if (canonical != null) {
            val canonicalUrl = canonical.attr("href")
            if (canonicalUrl.contains("youtube.com/watch")) {
                val extractor = YouTubeExtractor()
                extractor.getSafeUrl(canonicalUrl)?.forEach { link ->
                    callback(link)
                }
                return true
            }
        }

        return false
    }
}