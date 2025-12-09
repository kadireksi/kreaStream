package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import android.util.Log

class TurkTV : MainAPI() {

    // =========================================================================
    // 1. PLUGIN CONFIGURATION
    // =========================================================================
    override var name = "Türk TV"
    override var mainUrl = "https://turktv.local" // Virtual URL
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Live, TvType.Movie)

    // Remote Configuration URLs
    private val channelsJsonUrl = "https://raw.githubusercontent.com/kadireksi/kreaStream/builds/channels.json"
    private val streamsJsonUrl = "https://raw.githubusercontent.com/kadireksi/kreaStream/builds/streams.json"

    // =========================================================================
    // 2. DYNAMIC DATA CLASSES
    // =========================================================================
    
    data class StreamItem(
        val id: String,
        val title: String,
        val group: String = "TV",
        val url: String,
        val poster: String? = null,
        val type: String = "Live",
        val is_audio: Boolean = false,
        val headers: Map<String, String> = emptyMap()
    )

    data class ChannelConfig(
        val id: String,
        val name: String,
        val base_url: String,
        val active: Boolean = true,
        val headers: Map<String, String> = emptyMap(),
        val listings: List<ListingConfig> = emptyList(),
        val detail_page: DetailConfig? = null,
        val episode_page: EpisodePageConfig? = null,
        val video_extraction: VideoExtractionConfig? = null
    )

    data class ListingConfig(
        val title: String,
        val path: String,
        val selectors: Map<String, String>,
        val ishorizontal: Boolean = false
    )

    data class DetailConfig(
        val selectors: Map<String, String>
    )

    data class EpisodePageConfig(
        val path_suffix: String = "",
        val selectors: Map<String, String>,
        val reverse_order: Boolean = false
    )

    data class VideoExtractionConfig(
        val methods: List<ExtractionMethod> = emptyList()
    )

    data class ExtractionMethod(
        val type: String, // "iframe", "regex", "direct_m3u8", "css"
        val selector: String? = null,
        val pattern: String? = null,
        val attribute: String? = null
    )

    private var channels: List<ChannelConfig> = emptyList()
    private var streams: List<StreamItem> = emptyList()

    // =========================================================================
    // 3. MAIN PAGE GENERATOR
    // =========================================================================
    
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page == 1) fetchConfiguration()

        val pages = mutableListOf<HomePageList>()

        val tvStreams = streams.filter { it.group == "TV" }
        val radioStreams = streams.filter { it.group == "Radio" }

        if (tvStreams.isNotEmpty()) {
            pages.add(HomePageList("📺 Canlı TV", tvStreams.map { it.toSearchResponse() }, true))
        }
        if (radioStreams.isNotEmpty()) {
            pages.add(HomePageList("📻 Radyo", radioStreams.map { it.toSearchResponse() }, true))
        }

        channels.filter { it.active }.forEach { channel ->
            channel.listings.forEach { listing ->
                try {
                    val url = fixUrl(listing.path, channel.base_url)
                    val doc = app.get(url, headers = channel.headers).document
                    
                    val items = extractItems(doc, listing.selectors, channel.base_url)
                    
                    if (items.isNotEmpty()) {
                        val header = "${channel.name} - ${listing.title}"
                        pages.add(HomePageList(header, items, listing.ishorizontal))
                    }
                } catch (e: Exception) {
                    Log.e("TurkTV", "Error loading ${channel.name}: ${e.message}")
                }
            }
        }

        return newHomePageResponse(pages)
    }

    // =========================================================================
    // 4. SEARCH
    // =========================================================================

    override suspend fun search(query: String): List<SearchResponse> {
        fetchConfiguration()
        val results = mutableListOf<SearchResponse>()
        
        streams.filter { it.title.contains(query, true) }.forEach { 
            results.add(it.toSearchResponse()) 
        }
        
        return results
    }

    // =========================================================================
    // 5. LOAD (DETAILS & EPISODES)
    // =========================================================================

    override suspend fun load(url: String): LoadResponse {
        fetchConfiguration()

        // Handle Live Streams
        val streamItem = streams.find { it.url == url }
        if (streamItem != null) {
            return newTvSeriesLoadResponse(streamItem.title, url, TvType.Live, 
                listOf(newEpisode(url) {
                    this.name = streamItem.title
                    this.posterUrl = streamItem.poster
                })
            ) {
                this.posterUrl = streamItem.poster
                this.plot = if(streamItem.is_audio) "Canlı Radyo Yayını" else "Canlı TV Yayını"
            }
        }

        // Handle Channel Content
        val channel = channels.find { url.startsWith(it.base_url) } 
            ?: throw Error("Channel config not found for this URL")

        val doc = app.get(url, headers = channel.headers).document
        
        val selectors = channel.detail_page?.selectors ?: emptyMap()
        val title = doc.smartSelect(selectors["title"]) ?: "Bilinmeyen Başlık"
        val plot = doc.smartSelect(selectors["description"])
        val poster = doc.smartSelect(selectors["poster"])?.let { fixUrl(it, channel.base_url) }
        val year = doc.smartSelect(selectors["year"])?.filter { it.isDigit() }?.toIntOrNull()
        val tags = doc.smartSelect(selectors["tags"], all = true)

        val episodes = mutableListOf<Episode>()
        
        val suffix = channel.episode_page?.path_suffix ?: ""
        val episodeDoc = if (suffix.isNotEmpty()) {
             val cleanUrl = if (url.endsWith("/")) url.dropLast(1) else url
             app.get("$cleanUrl$suffix", headers = channel.headers).document
        } else {
             doc
        }

        val epSelectors = channel.episode_page?.selectors
        if (epSelectors != null) {
            val container = epSelectors["container"] ?: ""
            val rows = episodeDoc.select(container)
            
            rows.forEachIndexed { index, row ->
                val epName = row.smartSelect(epSelectors["name"]) ?: "Bölüm ${index + 1}"
                val epUrl = row.smartSelect(epSelectors["url"])
                
                if (!epUrl.isNullOrBlank()) {
                    val fullEpUrl = fixUrl(epUrl, channel.base_url)
                    episodes.add(newEpisode(fullEpUrl) {
                        this.name = epName
                        this.episode = index + 1
                        this.posterUrl = poster
                    })
                }
            }
            
            if (channel.episode_page.reverse_order) {
                episodes.reverse()
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
        }
    }

    // =========================================================================
    // 6. LOAD LINKS (VIDEO EXTRACTION) - FIXED
    // =========================================================================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        fetchConfiguration()

        // -- A. Direct Live Stream --
        val live = streams.find { it.url == data }
        if (live != null) {
            // Determine type
            val type = if (live.url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            
            callback(
                newExtractorLink(
                    source = this.name,
                    name = live.title,
                    url = live.url
                ){
                    this.referer = live.headers["Referer"] ?: ""
                    this.quality = Qualities.Unknown.value
                    this.type = type
                    this.headers = live.headers
                }
            )
            return true
        }

        // -- B. Extracted VOD Content --
        val channel = channels.find { data.startsWith(it.base_url) } ?: return false
        val methods = channel.video_extraction?.methods ?: emptyList()
        
        try {
            val doc = app.get(data, headers = channel.headers).document
            
            methods.forEach { method ->
                when (method.type) {
                    "iframe" -> {
                        doc.select(method.selector ?: "iframe").forEach { iframe ->
                            val src = iframe.attr("src")
                            if (src.isNotEmpty()) {
                                // FIXED: loadExtractor requires subtitleCallback as 2nd arg (or 3rd if referer included)
                                loadExtractor(src, subtitleCallback, callback)
                            }
                        }
                    }
                    "regex" -> {
                        val html = doc.html()
                        val regex = method.pattern?.toRegex()
                        regex?.find(html)?.groupValues?.get(1)?.let { link ->
                            val cleanLink = link.replace("\\/", "/")
                            callback(
                                newExtractorLink(
                                    source = "Source",
                                    name = "Source",
                                    url = cleanLink
                                ){
                                    this.referer = ""
                                    this.quality = Qualities.Unknown.value
                                    this.type = ExtractorLinkType.VIDEO
                                }
                            )
                        }
                    }
                    "css" -> {
                        val link = doc.select(method.selector ?: "").attr(method.attribute ?: "src")
                        if(link.isNotEmpty()) {
                            val type = if (link.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            callback(
                                newExtractorLink(
                                    source = "Source",
                                    name = "Source",
                                    url = link
                                ){
                                    this.referer = ""
                                    this.quality = Qualities.Unknown.value
                                    this.type = type
                                }
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("TurkTV", "Error extracting links: ${e.message}")
        }
        
        return true
    }

    // =========================================================================
    // 7. HELPER FUNCTIONS
    // =========================================================================

    private suspend fun fetchConfiguration() {
        if (channels.isEmpty() || streams.isEmpty()) {
            try {
                val chanText = app.get(channelsJsonUrl).text
                channels = parseJson<List<ChannelConfig>>(chanText)
                
                val streamText = app.get(streamsJsonUrl).text
                streams = parseJson<List<StreamItem>>(streamText)
            } catch (e: Exception) {
                Log.e("TurkTV", "Failed to load config: ${e.message}")
            }
        }
    }

    private fun Element.smartSelect(selectorStr: String?): String? {
        if (selectorStr.isNullOrEmpty()) return null
        if (selectorStr == "self@text") return this.text()
        if (selectorStr == "self@value") return this.attr("value")
        
        val parts = selectorStr.split("@")
        val css = parts[0]
        val attr = if (parts.size > 1) parts[1] else "text"
        
        val el = this.selectFirst(css) ?: return null
        
        return when (attr) {
            "text" -> el.text().trim()
            "ownText" -> el.ownText().trim()
            "html" -> el.html()
            else -> el.attr(attr).trim()
        }
    }
    
    private fun Element.smartSelect(selectorStr: String?, all: Boolean): List<String> {
        if (selectorStr.isNullOrEmpty()) return emptyList()
        val parts = selectorStr.split("@")
        val css = parts[0]
        val attr = if (parts.size > 1) parts[1] else "text"
        
        return this.select(css).map { el ->
            when (attr) {
                "text" -> el.text().trim()
                else -> el.attr(attr).trim()
            }
        }
    }

    private fun extractItems(doc: Document, selectors: Map<String, String>, baseUrl: String): List<SearchResponse> {
        val container = selectors["container"] ?: return emptyList()
        val list = mutableListOf<SearchResponse>()
        
        doc.select(container).forEach { el ->
            val title = el.smartSelect(selectors["title"])
            val href = el.smartSelect(selectors["url"])
            val poster = el.smartSelect(selectors["poster"])
            
            if (title != null && href != null) {
                val fullUrl = fixUrl(href, baseUrl)
                val fullPoster = poster?.let { fixUrl(it, baseUrl) }
                
                list.add(newTvSeriesSearchResponse(title, fullUrl, TvType.TvSeries) {
                    this.posterUrl = fullPoster
                })
            }
        }
        return list
    }

    private fun fixUrl(url: String, baseUrl: String): String {
        if (url.startsWith("http")) return url
        val cleanBase = baseUrl.trimEnd('/')
        val cleanUrl = url.trimStart('/')
        return "$cleanBase/$cleanUrl"
    }

    private fun StreamItem.toSearchResponse(): SearchResponse {
        return newTvSeriesSearchResponse(this.title, this.url, TvType.Live) {
            this.posterUrl = this@toSearchResponse.poster
        }
    }
}
