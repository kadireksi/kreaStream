package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document // Retained Jsoup import for Document
import org.jsoup.nodes.Element // Retained Jsoup import for Element
import android.util.Log // Retained explicit Android Log import

class TurkTV : MainAPI() {

    override var name = "Türk TV"
    override var mainUrl = "https://turktv.local"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Live)

    private val channelsJsonUrl = "https://raw.githubusercontent.com/kadireksi/kreaStream/builds/channels.json"
    private val streamsJsonUrl = "https://raw.githubusercontent.com/kadireksi/kreaStream/builds/streams.json"

    var channels: List<ChannelConfig>? = null
    var streams: List<LiveStreamConfig>? = null

    private var lastRefreshTime: Long = 0
    private val refreshInterval = 5 * 60 * 1000 // 5 minutes in milliseconds

    private val liveTvHubUrl = "https://turktv.local/live/tv"
    private val liveRadioHubUrl = "https://turktv.local/live/radio"

    data class LoadData(
        val originalTitle: String,
        val displayTitle: String,
        val poster: String?,
        val tags: List<String>,
        val year: Int?,
        val type: TvType,
        val description: String?,
        val score: Float?,
        val actors: List<Actor>,
        val trailer: String?
    )

    data class PosterData(
        val originalTitle: String,
        val displayTitle: String,
        val href: String,
        val posterUrl: String?,
        val lang: String?,
        val year: Int?,
        val score: Float?,
        val tvType: TvType,
        val hasDub: Boolean,
        val hasSub: Boolean
    )

    data class ChannelSelectorBlock(
        val container: String,
        val title: String? = null,
        val link: String? = null,
        val poster: String? = null,
        val year: String? = null,
        val score: String? = null,
        val lang: String? = null,
        val meta: String? = null,
        val isHorizontal: Boolean = false
    )

    data class SeriesSelectorBlock(
        val container: String,
        val title: String? = null,
        val poster: String? = null,
        val description: String? = null,
        val year: String? = null,
        val score: String? = null,
        val tags: String? = null,
        val actors: String? = null,
        val trailer: String? = null
    )

    data class EpisodeSelectorBlock(
        val container: String,
        val title: String? = null,
        val link: String? = null,
        val number: String? = null,
        val season: String? = null
    )

    data class StreamConfig(
        val type: String,
        val prefer: String,
        val referer: Boolean = false
    )

    data class ChannelConfig(
        val key: String,
        val name: String,
        val baseUrl: String,
        val active: Boolean = true,
        val seriesPageActive: String? = null, // <--- ADDED (restored)
        val seriesPageArchive: String? = null, // <--- ADDED (restored)
        val seriesList: ChannelSelectorBlock,
        val seriesListArchive: ChannelSelectorBlock? = null, // <--- ADDED (restored)
        val seriesDetail: SeriesSelectorBlock,
        val episodes: EpisodeSelectorBlock,
        val stream: StreamConfig,
        val episodesPageSuffix: String? = null // <--- NEW FIELD
    )

    data class LiveStreamConfig(
        val key: String,
        val title: String,
        val active: Boolean = true,
        val poster: String?,
        val url: String,
        val requiresReferer: Boolean = false,
        val streamType: String = "tv"
    )

    // ------------------- HELPER FUNCTIONS -------------------
    private fun fixUrlNull(url: String?): String? {
        return if (url.isNullOrBlank() || url == "null") null else {
            if (url.startsWith("http")) url else fixUrl(url)
        }
    }

    // Refactored to take a specific ChannelSelectorBlock
    private fun Element.extractPosterData(selectorBlock: ChannelSelectorBlock, baseUrl: String): PosterData? {
        // Get title from configured selector or default fallbacks
        val title = selectorBlock.title?.let { selector ->
            this.selectFirst(selector)?.text()?.trim()
        } ?: this.attr("title").takeIf { it.isNotEmpty() }?.trim()
        ?: this.selectFirst("strong.poster-title, h4.title, h3.title, h2.title, .title")?.text()?.trim()
        ?: return null

        // Get href from configured selector or default
        val href = selectorBlock.link?.let { selector ->
            this.selectFirst(selector)?.attr("href")
        } ?: this.attr("href")
        val fullUrl = fixUrlNull(href) ?: return null

        // Get poster from configured selector or default
        val rawPosterUrl = selectorBlock.poster?.let { selector ->
            this.selectFirst(selector)?.attr("src") ?: this.selectFirst(selector)?.attr("data-src")
        } ?: this.selectFirst("img[data-src], img[src]")?.attr("data-src")
            ?: this.selectFirst("img")?.attr("src")
        
        val posterUrl = fixUrlNull(rawPosterUrl)
            ?.replace("/list/", "/")
            ?.replace("/thumb/", "/")

        // Get year from configured selector or default
        val year = selectorBlock.year?.let { selector ->
            this.selectFirst(selector)?.text()?.trim()?.toIntOrNull()
        } ?: this.selectFirst(".poster-meta span, .year, .meta")?.text()?.trim()?.toIntOrNull()

        // Get score from configured selector or default
        val score = selectorBlock.score?.let { selector ->
            this.selectFirst(selector)?.ownText()?.trim()?.toFloatOrNull()
        } ?: this.selectFirst(".poster-meta .imdb, .rating, .score")?.ownText()?.trim()?.toFloatOrNull()
        
        // Get language info from configured selector or default
        val lang = selectorBlock.lang?.let { selector ->
            this.selectFirst(selector)?.text()?.trim()
        } ?: this.selectFirst(".poster-lang span, .poster-meta-genre span, .language")?.text()?.trim()
        
        // Dubbed status
        val hasDub = lang?.contains("Dublaj", ignoreCase = true) == true || 
                    lang?.contains("Yerli", ignoreCase = true) == true ||
                    lang?.contains("Türkçe", ignoreCase = true) == true
        
        // Subtitle status
        val hasSub = lang?.contains("Altyazılı", ignoreCase = true) == true
        
        val newTitle = if (hasDub) "🇹🇷 $title" else title

        // Determine type from href or other indicators
        val typeCheck = href.contains("/dizi/", ignoreCase = true) || 
                       href.contains("/series", ignoreCase = true) ||
                       selectorBlock.meta?.let { selector ->
                           this.selectFirst(selector)?.text()?.contains("Dizi", ignoreCase = true) == true
                       } ?: false
        
        val tvType = if (typeCheck) TvType.TvSeries else TvType.Movie

        return PosterData(
            originalTitle = title,
            displayTitle = newTitle,
            href = fullUrl,
            posterUrl = posterUrl,
            lang = lang,
            year = year,
            score = score,
            tvType = tvType,
            hasDub = hasDub,
            hasSub = hasSub
        )
    }

    private fun Document.extractLoadData(channel: ChannelConfig, baseUrl: String): LoadData? {
        // ... LoadData logic remains the same ...
        // Uses channel.seriesDetail which is a separate block, so no change needed here.
        // ... (function body remains as in previous response)
        
        // Get title from configured selector or default
        val originalTitle = channel.seriesDetail.title?.let { selector ->
            this.selectFirst(selector)?.text()?.trim()
        } ?: this.selectFirst("h1, h2, .section-title, .title")?.text()?.trim()
        ?.substringBefore(" izle") ?: return null

        // Get poster from configured selector or default
        val poster = channel.seriesDetail.poster?.let { selector ->
            fixUrlNull(this.selectFirst(selector)?.attr("src") ?: this.selectFirst(selector)?.attr("data-src"))
        } ?: fixUrlNull(this.selectFirst("img.lazyload, img.poster, img[src*='295x'], img[src*='poster']")?.attr("data-src"))

        // Get tags from configured selector or default
        val tags = channel.seriesDetail.tags?.let { selector ->
            this.select(selector).map { it.text().trim() }
        } ?: this.select("div.post-info-genres a, .genres a, .tags a, .categories a").map { it.text().trim() }

        // Get year from configured selector or default
        val year = channel.seriesDetail.year?.let { selector ->
            this.selectFirst(selector)?.text()?.trim()?.toIntOrNull()
        } ?: this.selectFirst("div.post-info-year-country a, .year, .release-date")?.text()?.trim()?.toIntOrNull()

        // Determine TV type
        val tvType = if (this.select("div.seasons, .seasons, .season-list").isNotEmpty()) TvType.TvSeries else TvType.Movie

        // Get description from configured selector or default
        val description = channel.seriesDetail.description?.let { selector ->
            this.selectFirst(selector)?.text()?.trim()
        } ?: this.selectFirst("article.post-info-content > p, .description, .plot, .synopsis")?.text()?.trim()

        // Get score from configured selector or default
        val score = channel.seriesDetail.score?.let { selector ->
            this.selectFirst(selector)?.text()?.trim()?.toFloatOrNull()
        } ?: this.selectFirst("div.post-info-imdb-rating span, .rating, .imdb, .score")?.text()
            ?.substringBefore("(")?.trim()?.toFloatOrNull()

        // Check for dubbed content
        val hasDub = originalTitle.contains("dublaj", ignoreCase = true) ||
                    description?.contains("dublaj", ignoreCase = true) == true ||
                    tags.any { it.contains("dublaj", ignoreCase = true) }
        
        val newTitle = if (hasDub) "🇹🇷 $originalTitle" else originalTitle

        // Get actors from configured selector or default
        val actors = channel.seriesDetail.actors?.let { selector ->
            this.select(selector).map {
                val actorName = it.selectFirst("strong")?.text() ?: it.text().trim()
                val actorImage = fixUrlNull(it.selectFirst("img")?.attr("data-src") ?: it.selectFirst("img")?.attr("src"))
                Actor(actorName, actorImage)
            }
        } ?: this.select("div.post-info-cast a, .cast a, .actors a").map {
            val actorName = it.selectFirst("strong")?.text() ?: it.text().trim()
            val actorImage = fixUrlNull(it.selectFirst("img")?.attr("data-src") ?: it.selectFirst("img")?.attr("src"))
            Actor(actorName, actorImage)
        }

        // Get trailer from configured selector or default
        val trailer = channel.seriesDetail.trailer?.let { selector ->
            this.selectFirst(selector)?.attr("data-modal")
                ?.substringAfter("trailer/")?.let { "https://www.youtube.com/embed/$it" }
        } ?: this.selectFirst("div.post-info-trailer button, .trailer button")?.attr("data-modal")
            ?.substringAfter("trailer/")?.let { "https://www.youtube.com/embed/$it" }

        return LoadData(
            originalTitle = originalTitle,
            displayTitle = newTitle,
            poster = poster,
            tags = tags,
            year = year,
            type = tvType,
            description = description,
            score = score,
            actors = actors,
            trailer = trailer
        )
    }

    // Refactored to accept a specific selectorBlock
    private suspend fun getChannelSeries(channel: ChannelConfig, path: String, listName: String, selectorBlock: ChannelSelectorBlock): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        try {
            Log.d("TurkTV", "Getting $listName series from ${channel.name} for main page using path: $path")
            
            // Construct the URL using the provided path
            val seriesUrl = "${channel.baseUrl}${path}"
            val doc = app.get(seriesUrl).document
            
            // Select series elements using the specific selector block's container
            val seriesElements = doc.select(selectorBlock.container)
            Log.d("TurkTV", "Found ${seriesElements.size} series on ${channel.name} ($listName)")
            
            seriesElements.forEach { element ->
                // Pass the specific selectorBlock to the extraction helper
                val posterData = element.extractPosterData(selectorBlock, channel.baseUrl)
                
                if (posterData != null) {
                    results.add(newTvSeriesSearchResponse(posterData.displayTitle, posterData.href, posterData.tvType) {
                        this.posterUrl = posterData.posterUrl
                        this.year = posterData.year
                    })
                }
            }
        } catch (e: Exception) {
            Log.e("TurkTV", "Error getting $listName series from ${channel.name}: ${e.message}", e)
        }
        return results
    }

    // ------------------- JSON LOADING WITH REFRESH -------------------
    private suspend fun ensureLoaded(forceRefresh: Boolean = false) {
        // ... (function body remains as in previous response)
        Log.d("TurkTV", "=== ensureLoaded() called, forceRefresh: $forceRefresh ===")
        
        val currentTime = System.currentTimeMillis()
        val shouldRefresh = forceRefresh || 
                           (lastRefreshTime == 0L) || 
                           (currentTime - lastRefreshTime > refreshInterval)
        
        if (shouldRefresh) {
            Log.d("TurkTV", "Forcing refresh of JSON data")
            channels = null
            streams = null
            lastRefreshTime = currentTime
        }
        
        if (channels == null) {
            channels = try {
                Log.d("TurkTV", "Loading channels.json...")
                val responseText = app.get(channelsJsonUrl).document.text()
                Log.d("TurkTV", "Response length: ${responseText.length}")
                
                if (responseText.isBlank()) {
                    Log.e("TurkTV", "Empty response")
                    emptyList()
                } else {
                    val parsed = parseJson<List<ChannelConfig>>(responseText)
                    Log.d("TurkTV", "Successfully parsed ${parsed?.size ?: 0} channels")
                    
                    val activeChannels = parsed?.filter { it.active } ?: emptyList()
                    activeChannels.forEachIndexed { i, ch ->
                        Log.d("TurkTV", "Active channel $i: ${ch.name} (${ch.key})")
                    }
                    
                    activeChannels
                }
            } catch (e: Exception) {
                Log.e("TurkTV", "Error loading channels: ${e.message}", e)
                emptyList()
            }
        }
        
        if (streams == null) {
            streams = try {
                Log.d("TurkTV", "Loading streams.json...")
                val responseText = app.get(streamsJsonUrl).document.text()
                Log.d("TurkTV", "Response length: ${responseText.length}")
                
                if (responseText.isBlank()) {
                    Log.e("TurkTV", "Empty response")
                    emptyList()
                } else {
                    val parsed = parseJson<List<LiveStreamConfig>>(responseText)
                    Log.d("TurkTV", "Successfully parsed ${parsed?.size ?: 0} streams")
                    
                    val activeStreams = parsed?.filter { it.active } ?: emptyList()
                    activeStreams.forEachIndexed { i, st ->
                        Log.d("TurkTV", "Active stream $i: ${st.title} (${st.streamType}) - URL: ${st.url.take(50)}...")
                    }
                    
                    activeStreams
                }
            } catch (e: Exception) {
                Log.e("TurkTV", "Error loading streams: ${e.message}", e)
                emptyList()
            }
        }
        
        Log.d("TurkTV", "=== ensureLoaded() completed ===")
        Log.d("TurkTV", "Active channels: ${channels?.size ?: 0}, Active streams: ${streams?.size ?: 0}")
    }

    // ------------------- MAIN PAGE WITH FORCE REFRESH -------------------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        Log.d("TurkTV", "=== getMainPage() called - page: $page ===")
        
        // Force refresh on first page load (when user opens the plugin)
        val forceRefresh = page == 1
        ensureLoaded(forceRefresh)

        val lists = mutableListOf<HomePageList>()
        
        // --- 1. LIVE STREAMS SECTION ---
        val liveItems = mutableListOf<SearchResponse>()
        
        // Live TV Item
        val tvStreams = streams?.filter { it.streamType == "tv" } ?: emptyList()
        liveItems += newTvSeriesSearchResponse(
            "📺 Canlı TV (${tvStreams.size})", 
            liveTvHubUrl, 
            TvType.TvSeries
        ).apply {
            posterUrl = "https://img.freepik.com/premium-vector/television-icon-logo-vector-design-template_827767-3402.jpg"
        }
        Log.d("TurkTV", "TV item shows ${tvStreams.size} channels")

        // Live Radio Item
        val radioStreams = streams?.filter { it.streamType == "radio" } ?: emptyList()
        liveItems += newTvSeriesSearchResponse(
            "📻 Radyo (${radioStreams.size})", 
            liveRadioHubUrl, 
            TvType.TvSeries
        ).apply {
            posterUrl = "https://img.freepik.com/premium-vector/retro-black-white-boombox_788759-25590.jpg"
        }
        Log.d("TurkTV", "Radio item shows ${radioStreams.size} channels")
        
        lists += HomePageList("🎬 Canlı Yayınlar", liveItems, true)
        
        // --- 2. SERIES SECTIONS (Using optional paths and selector blocks) ---
        if (channels != null && channels!!.isNotEmpty()) {
            Log.d("TurkTV", "Processing ${channels!!.size} channels for series lists")
            channels!!.forEach { cfg ->
                
                // Active Series Section
                cfg.seriesPageActive?.let { path ->
                    val seriesList = getChannelSeries(cfg, path, "Active", cfg.seriesList) // <--- Pass cfg.seriesList
                    if (seriesList.isNotEmpty()) {
                        lists += HomePageList("📺 ${cfg.name} Diziler", seriesList, cfg.seriesList?.isHorizontal)
                        Log.d("TurkTV", "Added ${seriesList.size} active series for ${cfg.name}")
                    }
                }
                
                // Archived Series Section (Only created if seriesPageArchive is provided)
                cfg.seriesPageArchive?.let { path ->
                    // Use archive selectors if available, fallback to active selectors
                    val selectorBlock = cfg.seriesListArchive ?: cfg.seriesList
                    val archiveList = getChannelSeries(cfg, path, "Archive", selectorBlock) // <--- Pass the determined selectorBlock
                    if (archiveList.isNotEmpty()) {
                        lists += HomePageList("📂 ${cfg.name} Arşiv Diziler", archiveList, cfg.seriesListArchive?.isHorizontal)
                        Log.d("TurkTV", "Added ${archiveList.size} archived series for ${cfg.name}")
                    }
                }
            }
        } else {
            Log.w("TurkTV", "No channels available, adding placeholder")
            lists += HomePageList("📺 Diziler", listOf(
                newTvSeriesSearchResponse("Kanal Yüklenemedi", "", TvType.TvSeries) {
                    this.posterUrl = "https://cdn-icons-png.flaticon.com/512/2748/2748558.png"
                }
            ), true)
        }

        return newHomePageResponse(lists)
    }

    // ------------------- SEARCH (Series List) -------------------
    override suspend fun search(query: String): List<SearchResponse> {
        Log.d("TurkTV", "=== search() called with query: $query ===")
        ensureLoaded()
        
        if (query.isEmpty()) {
            // Return all series from all channels when no query
            return getAllSeries()
        }
        
        val results = mutableListOf<SearchResponse>()
        
        channels?.forEach { channel ->
            // Use active page for search if available
            channel.seriesPageActive?.let { path ->
                try {
                    Log.d("TurkTV", "Searching on ${channel.name} (${channel.baseUrl})")
                    
                    // Get the series page URL using the configured active path
                    val seriesUrl = "${channel.baseUrl}${path}"
                    Log.d("TurkTV", "Fetching series from: $seriesUrl")
                    
                    val doc = app.get(seriesUrl).document
                    
                    // Extract series using the configured active selectors
                    val seriesElements = doc.select(channel.seriesList.container)
                    Log.d("TurkTV", "Found ${seriesElements.size} series elements on ${channel.name}")
                    
                    seriesElements.forEach { element ->
                        // Pass active selector block
                        val posterData = element.extractPosterData(channel.seriesList, channel.baseUrl)
                        
                        if (posterData != null) {
                            // Check if the series title matches the query (case-insensitive)
                            if (posterData.displayTitle.contains(query, ignoreCase = true) ||
                                posterData.originalTitle.contains(query, ignoreCase = true)) {
                                
                                Log.d("TurkTV", "Found series: ${posterData.displayTitle} -> ${posterData.href}")
                                
                                results.add(newTvSeriesSearchResponse(posterData.displayTitle, posterData.href, posterData.tvType) {
                                    this.posterUrl = posterData.posterUrl
                                    this.year = posterData.year
                                })
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("TurkTV", "Error searching on ${channel.name}: ${e.message}", e)
                }
            }
        }
        
        Log.d("TurkTV", "Search returned ${results.size} results")
        return results
    }

    // Get all series from all channels (for empty query)
    private suspend fun getAllSeries(): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        
        channels?.forEach { channel ->
            // Use active page for all series list
            channel.seriesPageActive?.let { path ->
                try {
                    Log.d("TurkTV", "Getting all active series from ${channel.name}")
                    
                    val seriesUrl = "${channel.baseUrl}${path}"
                    val doc = app.get(seriesUrl).document
                    
                    val seriesElements = doc.select(channel.seriesList.container)
                    Log.d("TurkTV", "Found ${seriesElements.size} series on ${channel.name}")
                    
                    seriesElements.forEach { element ->
                        // Pass active selector block
                        val posterData = element.extractPosterData(channel.seriesList, channel.baseUrl)
                        
                        if (posterData != null) {
                            results.add(newTvSeriesSearchResponse(posterData.displayTitle, posterData.href, posterData.tvType) {
                                this.posterUrl = posterData.posterUrl
                                this.year = posterData.year
                            })
                        }
                    }
                } catch (e: Exception) {
                    Log.e("TurkTV", "Error getting series from ${channel.name}: ${e.message}", e)
                }
            }
        }
        
        return results
    }

    // ------------------- LOAD (Series Page) -------------------
    override suspend fun load(url: String): LoadResponse {
        // ... (function body remains as in previous response)
        ensureLoaded()
        
        if (url == liveTvHubUrl) {  
            val tvStreams = streams?.filter { it.streamType == "tv" && it.active == true } ?: emptyList()
            
            val episodes = tvStreams.mapIndexed { index, stream ->
                newEpisode(stream.url) {
                    this.name = stream.title
                    this.posterUrl = stream.poster ?: "https://img.freepik.com/premium-vector/television-icon-logo-vector-design-template_827767-3402.jpg"
                    this.description = "Canlı TV yayını"
                    this.episode = index + 1
                    this.season = 1
                }
            }

            return newTvSeriesLoadResponse(
                name = "📺 TV (${tvStreams.size})",
                url = url,
                type = TvType.TvSeries,
                episodes = episodes
            ) {
                posterUrl = "https://img.freepik.com/premium-vector/television-icon-logo-vector-design-template_827767-3402.jpg"
                this.plot = "Türk TV kanallarının canlı yayınları.\n\n" +
                           "• Kanallar arasında geçiş yapmak için sonraki/önceki bölüm butonlarını kullanın\n" +
                           "• Her kanal otomatik olarak sonraki kanala geçiş yapabilir\n" +
                           "• ${tvStreams.size} kanal bulunmaktadır"
            }
        }
        
        if (url == liveRadioHubUrl) {    
            val radioStreams = streams?.filter { it.streamType == "radio" && it.active == true } ?: emptyList()
            
            val episodes = radioStreams.mapIndexed { index, stream ->
                newEpisode(stream.url) {
                    this.name = stream.title
                    this.posterUrl = stream.poster ?: "https://img.freepik.com/premium-vector/retro-black-white-boombox_788759-25590.jpg"
                    this.description = "Canlı radyo yayını"
                    this.episode = index + 1
                    this.season = 1
                }
            }

            return newTvSeriesLoadResponse(
                name = "📻 Radyo (${radioStreams.size})",
                url = url,
                type = TvType.TvSeries,
                episodes = episodes
            ) {
                posterUrl = "https://img.freepik.com/premium-vector/retro-black-white-boombox_788759-25590.jpg"
                this.plot = "Türk radyo kanallarının canlı yayınları.\n\n" +
                           "• Kanallar arasında geçiş yapmak için sonraki/önceki bölüm butonlarını kullanın\n" +
                           "• Her kanal otomatik olarak sonraki kanala geçiş yapabilir\n" +
                           "• ${radioStreams.size} kanal bulunmaktadır\n" +
                           "• Bu kanallar sadece ses içermektedir"
            }
        }

        // 2. Find which channel this series belongs to
        val channel = channels?.find { url.contains(it.baseUrl) }
        if (channel == null) {
            return newTvSeriesLoadResponse("Bulunamadı", url, TvType.TvSeries, emptyList()) {
                this.plot = "Bu kanal yapılandırılmamış"
                this.posterUrl = "https://cdn-icons-png.flaticon.com/512/2748/2748558.png"
            }
        }
                
        try {
            // 3. Fetch the series page
            val doc = app.get(url).document
            
            // 4. Extract series details using helper function
            val loadData = doc.extractLoadData(channel, channel.baseUrl)
            
            if (loadData == null) {
                return newTvSeriesLoadResponse("Hata", url, TvType.TvSeries, emptyList()) {
                    this.plot = "Sayfa yapısı tanınamadı"
                    this.posterUrl = "https://cdn-icons-png.flaticon.com/512/2748/2748558.png"
                }
            }
            
            // 5. Get episodes page URL
            val seriesPath = url.removePrefix(channel.baseUrl).trim('/')
            // Use configured suffix, default to "/bolumler"
            val episodesSuffix = channel.episodesPageSuffix ?: "/bolumler" // <--- NEW LOGIC
            val episodesUrl = "${channel.baseUrl}/$seriesPath${episodesSuffix}" // <--- USING NEW SUFFIX
            
            val episodesDoc = app.get(episodesUrl).document
            
                        // 6. Extract episodes using the configured selectors
            val episodes = mutableListOf<Episode>()
            
            // Try to find episodes from select dropdown or list
            val episodeContainers = episodesDoc.select(channel.episodes.container)
            
            if (episodeContainers.isNotEmpty()) {
                episodeContainers.forEach { option ->
                    val value = option.attr("value")
                    val text = option.text().trim()
                    
                    if (value.isNotBlank() && value != "bolum" && text.isNotBlank() && text != "Bölüm Seçiniz") {
                        // Create full episode URL
                        var episodeUrl = value
                        if (!episodeUrl.startsWith("http")) {
                            episodeUrl = if (episodeUrl.startsWith("/")) {
                                "${channel.baseUrl}$episodeUrl"
                            } else {
                                "${channel.baseUrl}/$episodeUrl"
                            }
                        }
                        
                        // Extract episode number from text
                        val episodeNum = text.filter { it.isDigit() }.toIntOrNull() ?: 1
                        
                        episodes.add(
                            newEpisode(episodeUrl) {
                                this.name = text
                                this.episode = episodeNum
                                this.season = 1 // Default season
                                this.posterUrl = loadData.poster
                            }
                        )
                    }
                }
            } else {
                // Method 2: Direct episode links (alternative method)
                episodesDoc.select("a[href*='/izle'], a[href*='/bolum']").forEach { link ->
                    val href = link.attr("href")
                    val text = link.text().trim()
                    
                    if (href.isNotBlank() && text.isNotBlank() && text.contains("Bölüm")) {
                        var episodeUrl = href
                        if (!episodeUrl.startsWith("http")) {
                            episodeUrl = if (episodeUrl.startsWith("/")) {
                                "${channel.baseUrl}$episodeUrl"
                            } else {
                                "${channel.baseUrl}/$episodeUrl"
                            }
                        }
                        
                        val episodeNum = text.filter { it.isDigit() }.toIntOrNull() ?: 1
                        
                        episodes.add(
                            newEpisode(episodeUrl) {
                                this.name = text
                                this.episode = episodeNum
                                this.season = 1
                                this.posterUrl = loadData.poster
                            }
                        )
                    }
                }
            }
            
            // Sort episodes by episode number (descending - newest first)
            val sortedEpisodes = episodes.sortedByDescending { it.episode }
            
            return newTvSeriesLoadResponse(
                name = loadData.displayTitle,
                url = url,
                type = loadData.type,
                episodes = sortedEpisodes
            ) {
                this.posterUrl = loadData.poster
                this.plot = loadData.description ?: "İzlemek için bir bölüm seçin."
                this.year = loadData.year
                this.score = Score.from10(loadData.score)
                this.tags = loadData.tags?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
                //this.actors = loadData.actors?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
            }
            
        } catch (e: Exception) {
            return newTvSeriesLoadResponse("Hata", url, TvType.TvSeries, emptyList()) {
                this.plot = "Seri yüklenirken hata oluştu: ${e.message}"
                this.posterUrl = "https://cdn-icons-png.flaticon.com/512/2748/2748558.png"
            }
        }
    }

    // ------------------- LOAD LINKS -------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // ... (function body remains as in previous response)
        ensureLoaded()

        // Individual live streams (when an episode is clicked)
        streams?.firstOrNull { it.url == data }?.let { live ->
            // Determine the correct link type
            val linkType = when {
                live.url.contains(".m3u8") -> ExtractorLinkType.M3U8
                live.url.contains(".aac") || live.url.contains(".mp3") || live.streamType == "radio" -> {
                    Log.d("TurkTV", "Detected audio stream, using MEDIA type")
                    ExtractorLinkType.VIDEO
                }
                else -> ExtractorLinkType.VIDEO
            }
            
            // Create appropriate headers based on stream type
            val headers = mutableMapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "Accept" to "*/*"
            )
            
            // Add referer only if required
            if (live.requiresReferer) {
                headers["Origin"] = mainUrl
                headers["Referer"] = mainUrl + "/"
            }
            
            // For AAC streams, we might need different headers
            if (live.url.contains(".aac")) {
                headers["Accept"] = "audio/*"
                headers["Accept-Encoding"] = "identity"
            }
            
            callback(
                newExtractorLink(
                    source = name,
                    name = live.title,
                    url = live.url
                ){
                    this.referer = if (live.requiresReferer) mainUrl else ""
                    this.quality = Qualities.Unknown.value
                    this.type = linkType
                    this.headers = headers
                }
            )
            return true
        }

        // Check if this is a series episode
        val channel = channels?.find { data.contains(it.baseUrl) }
        if (channel != null) {
            
            try {
                // Fetch the episode page
                val doc = app.get(data).document
                
                // Try to find the video source using multiple methods
                val videoSources = mutableListOf<String>()
                
                // Method 1: Check video tags
                doc.select("video source").forEach { source ->
                    val src = source.attr("src")
                    if (src.isNotBlank() && (src.contains(".m3u8") || src.contains(".mp4"))) {
                        videoSources.add(src)
                    }
                }
                
                // Method 2: Check iframe
                doc.select("iframe").forEach { iframe ->
                    val src = iframe.attr("src")
                    if (src.isNotBlank() && (src.contains("youtube") || src.contains("vimeo"))) {
                        videoSources.add(src)
                    }
                }
                
                // Method 3: Look for m3u8 in script tags
                doc.select("script").forEach { script ->
                    val content = script.html()
                    val m3u8Match = Regex("(https?://[^\"' ]+\\.m3u8[^\"' ]*)").findAll(content)
                    m3u8Match.forEach { match ->
                        videoSources.add(match.value)
                    }
                    
                    // Also look for mp4
                    val mp4Match = Regex("(https?://[^\"' ]+\\.mp4[^\"' ]*)").findAll(content)
                    mp4Match.forEach { match ->
                        videoSources.add(match.value)
                    }
                }
                
                // Method 4: Check for data-video attributes
                doc.select("[data-video]").forEach { elem ->
                    val videoUrl = elem.attr("data-video")
                    if (videoUrl.isNotBlank()) {
                        videoSources.add(videoUrl)
                    }
                }
                
                if (videoSources.isNotEmpty()) {
                    
                    videoSources.forEachIndexed { index, videoUrl ->
                        val linkType = when {
                            videoUrl.contains(".m3u8") -> ExtractorLinkType.M3U8
                            videoUrl.contains("youtube.com") || videoUrl.contains("youtu.be") -> ExtractorLinkType.M3U8
                            videoUrl.contains("vimeo.com") -> ExtractorLinkType.M3U8
                            else -> ExtractorLinkType.VIDEO
                        }
                        
                        val headers = mutableMapOf(
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                            "Accept" to "*/*"
                        )
                        
                        if (channel.stream.referer) {
                            headers["Origin"] = channel.baseUrl
                            headers["Referer"] = channel.baseUrl + "/"
                        }
                        
                        val sourceName = if (videoSources.size == 1) {
                            "${channel.name} Video"
                        } else {
                            "${channel.name} Video ${index + 1}"
                        }
                        
                        callback(
                            newExtractorLink(
                                source = name,
                                name = sourceName,
                                url = videoUrl
                            ){
                                this.referer = if (channel.stream.referer) channel.baseUrl else ""
                                this.quality = Qualities.Unknown.value
                                this.type = linkType
                                this.headers = headers
                            }
                        )
                    }
                    
                    return true
                } else {
                    Log.w("TurkTV", "No video sources found on episode page")
                }
            } catch (e: Exception) {
                Log.e("TurkTV", "Error loading episode links: ${e.message}", e)
            }
        }

        return false
    }
}
