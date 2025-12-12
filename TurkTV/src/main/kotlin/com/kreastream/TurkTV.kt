package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.json.JSONObject
import org.json.JSONArray
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

    private val dummyTvUrl = "${mainUrl}/tv"
    private val dummyRadioUrl = "${mainUrl}/radio"
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
        val backgroundPosterUrl: String? = null,
        val headers: Map<String, String> = emptyMap(),
        val genres: String = ""
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
        val ishorizontal: Boolean
    )

    data class DetailConfig(
        val selectors: Map<String, String>
    )

    data class EpisodePageConfig(
        val path_suffix: String = "",
        val selectors: Map<String, String>,
        val reverse_order: Boolean
    )

    data class VideoExtractionConfig(
        val methods: List<ExtractionMethod> = emptyList()
    )

    data class ExtractionMethod(
        val type: String, // "iframe", "regex", "direct_m3u8", "css", "youtube"
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

        //val tvStreams = streams.filter { it.group == "TV" }
        //val radioStreams = streams.filter { it.group == "Radio" }

        val grouped = streams.groupBy { it.group ?: "TV" }
        for ((groupName, list) in grouped) {
            val header = when (groupName.lowercase()) {
                "radio", "radyo" -> "📻 Canlı Radyo"
                else -> "📺 Canlı TV"
            }

            val genres = list.flatMap { it.genres.split(",") }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()

            val items = mutableListOf<SearchResponse>()
            for (genre in genres) {
                val fakeUrl = "${header}_genre::${groupName}::${genre}"
                items.add(newTvSeriesSearchResponse(genre, fakeUrl, TvType.TvSeries) {
                    this.posterUrl = ""
                    this.plot = "Canlı: $genre"
                })
            }
            if (items.isNotEmpty()) pages.add(HomePageList(header, items, true))
        }

        channels.filter { it.active }.forEach { channel ->
            channel.listings.forEach { listing ->
                try {
                    val url = buildListingUrl(listing.path, channel.base_url, page)
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

    // build listing URL with basic pagination support:
    private fun buildListingUrl(path: String, baseUrl: String, page: Int): String {
        var p = path
        if (p.contains("{page}")) {
            p = p.replace("{page}", page.toString())
        } else if (page > 1) {
            // append page param
            p = if (p.contains("?")) "$p&page=$page" else "$p?page=$page"
        }
        return fixUrl(p, baseUrl)
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
                    //this.backgroundPosterUrl = if(streamItem.is_audio === true) streamItem.poster else null
                })
            ) {
                this.posterUrl = streamItem.poster
                this.backgroundPosterUrl = if(streamItem.is_audio) streamItem.poster else null
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
        val epSelectors = channel.episode_page?.selectors

        if (epSelectors != null) {
            // Attempt to gather episodes possibly across paginated pages (basic approach)
            val seenUrls = mutableSetOf<String>()
            for (pIndex in 1..5) { // try up to 5 pages
                try {
                    val episodeDoc = if (suffix.isNotEmpty()) {
                        val cleanUrl = if (url.endsWith("/")) url.dropLast(1) else url
                        val pageSuffix = if (pIndex == 1) suffix else "$suffix${if (suffix.contains("?")) "&" else "?"}page=$pIndex"
                        app.get("$cleanUrl$pageSuffix", headers = channel.headers).document
                    } else {
                        val pageUrl = if (pIndex == 1) url else "$url${if (url.contains("?")) "&" else "?"}page=$pIndex"
                        app.get(pageUrl, headers = channel.headers).document
                    }

                    val container = epSelectors["container"] ?: ""
                    val rows = episodeDoc.select(container)

                    if (rows.isEmpty()) {
                        // no further pages
                        break
                    }

                    rows.forEachIndexed { index, row ->
                        val epName = row.smartSelect(epSelectors["name"]) ?: "Bölüm ${episodes.size + 1}"
                        val epUrlRaw = row.smartSelect(epSelectors["url"])
                        if (!epUrlRaw.isNullOrBlank()) {
                            val fullEpUrl = fixUrl(epUrlRaw, channel.base_url)
                            if (seenUrls.add(fullEpUrl)) {
                                episodes.add(newEpisode(fullEpUrl) {
                                    this.name = epName
                                    this.episode = episodes.size + 1
                                    this.posterUrl = poster
                                })
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("TurkTV", "Episode pagination fetch error: ${e.message}")
                    break
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
    // 6. LOAD LINKS (VIDEO EXTRACTION)
    // =========================================================================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        fetchConfiguration()

        var found = false

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
                            var src = iframe.attr("src")
                            if (src.isNotEmpty()) {
                                src = fixUrl(src, channel.base_url)
                                // loadExtractor signature may accept (url, referer?, subtitleCallback, callback)
                                try {
                                    loadExtractor(src, subtitleCallback, callback)
                                } catch (e: Exception) {
                                    // fallback with referer param if plugin expects it
                                    try { loadExtractor(src, channel.base_url, subtitleCallback, callback) } catch (_: Exception) {}
                                }
                                found = true
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
                                    this.referer = channel.base_url
                                    this.quality = Qualities.Unknown.value
                                    this.type = if (cleanLink.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                }
                            )
                            found = true
                        }
                    }
                    "css" -> {
                        val link = doc.select(method.selector ?: "").attr(method.attribute ?: "src")
                        if(link.isNotEmpty()) {
                            val fixed = fixUrl(link, channel.base_url)
                            val type = if (fixed.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            callback(
                                newExtractorLink(
                                    source = "Source",
                                    name = "Source",
                                    url = fixed
                                ){
                                    this.referer = channel.base_url
                                    this.quality = Qualities.Unknown.value
                                    this.type = type
                                }
                            )
                            found = true
                        }
                    }
                    "direct_m3u8" -> {
                        // Search scripts for embedded JSON/player config containing m3u8
                        val scripts = doc.select("script")
                        for (script in scripts) {
                            val scriptContent = script.html()
                            if (scriptContent.contains("playerConfig", ignoreCase = true) || scriptContent.contains("streamUrl", ignoreCase = true)) {
                                val m3u8Url = extractM3u8FromJson(scriptContent)
                                if (m3u8Url != null) {
                                    M3u8Helper.generateM3u8(
                                        source = name,
                                        streamUrl = m3u8Url,
                                        referer = channel.base_url,
                                        headers = mapOf(
                                            "Referer" to channel.base_url,
                                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                                        )
                                    ).forEach { callback(it) }
                                    found = true
                                    return true
                                }
                            }
                        }
                    }
                    "youtube" -> {
                        val yt = doc.selectFirst("iframe[src*='youtube.com/embed']")
                            ?.attr("src")
                            ?.let { "https://www.youtube.com/watch?v=${it.substringAfter("embed/").substringBefore("?")}" }
                            ?: Regex("""https://www\.youtube\.com/watch\?v=([a-zA-Z0-9_-]+)""")
                                .find(doc.html())?.groupValues?.get(1)
                                ?.let { "https://www.youtube.com/watch?v=$it" }

                        if (yt != null) {
                            try {
                                loadExtractor(yt, subtitleCallback, callback)
                            } catch (e: Exception) {
                                try { loadExtractor(yt, channel.base_url, subtitleCallback, callback) } catch (_: Exception) {}
                            }
                            found = true
                            return true
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("TurkTV", "Error extracting links: ${e.message}")
        }
        
        return found
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
        // support self@<attr> generically
        if (selectorStr.startsWith("self@")) {
            val attr = selectorStr.substringAfter("self@")
            return if (attr == "text") this.text().trim() else this.attr(attr).trim()
        }
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
        if (selectorStr.startsWith("self@")) {
            val attr = selectorStr.substringAfter("self@")
            return listOf(if (attr == "text") this.text().trim() else this.attr(attr).trim()).filter { it.isNotEmpty() }
        }
        val parts = selectorStr.split("@")
        val css = parts[0]
        val attr = if (parts.size > 1) parts[1] else "text"
        
        return this.select(css).map { el ->
            when (attr) {
                "text" -> el.text().trim()
                else -> el.attr(attr).trim()
            }
        }.filter { it.isNotEmpty() }
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
