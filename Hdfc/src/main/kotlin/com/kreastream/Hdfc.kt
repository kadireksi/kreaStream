package com.kreastream

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class Hdfc : MainAPI() {
    override var mainUrl = "https://www.hdfilmcehennemi.la"
    override var name = "HDFC"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // CloudFlare bypass
    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 50L
    override var sequentialMainPageScrollDelay = 50L

    // CloudFlare handling
    private val interceptor by lazy { CloudflareInterceptor() }

    class CloudflareInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val response = chain.proceed(request)
            val doc = Jsoup.parse(response.peekBody(1024 * 1024).string())

            if (doc.text().contains("Just a moment")) {
                Log.w("HDFC", "Cloudflare protection detected")
            }

            return response
        }
    }

    // ObjectMapper for JSON parsing
    private val objectMapper = jacksonObjectMapper().apply {
        registerModule(KotlinModule.Builder().build())
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    // Standard headers for requests
    private val standardHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:137.0) Gecko/20100101 Firefox/137.0",
        "Accept" to "*/*",
        "X-Requested-With" to "fetch"
    )

    // Main page categories
    override val mainPage = mainPageOf(
        "${mainUrl}/load/page/1/home/" to "Yeni Eklenen Filmler",
        "${mainUrl}/load/page/1/categories/nette-ilk-filmler/" to "Nette İlk Filmler",
        "${mainUrl}/load/page/1/home-series/" to "Yeni Eklenen Diziler",
        "${mainUrl}/load/page/1/categories/tavsiye-filmler-izle2/" to "Tavsiye Filmler",
        "${mainUrl}/load/page/1/imdb7/" to "IMDB 7+ Filmler",
        "${mainUrl}/load/page/1/mostLiked/" to "En Çok Beğenilenler",
        "${mainUrl}/load/page/1/genres/aile-filmleri-izleyin-6/" to "Aile Filmleri",
        "${mainUrl}/load/page/1/genres/aksiyon-filmleri-izleyin-5/" to "Aksiyon Filmleri",
        "${mainUrl}/load/page/1/genres/animasyon-filmlerini-izleyin-5/" to "Animasyon Filmleri",
        "${mainUrl}/load/page/1/genres/belgesel-filmlerini-izle-1/" to "Belgesel Filmleri",
        "${mainUrl}/load/page/1/genres/bilim-kurgu-filmlerini-izleyin-3/" to "Bilim Kurgu Filmleri",
        "${mainUrl}/load/page/1/genres/komedi-filmlerini-izleyin-1/" to "Komedi Filmleri",
        "${mainUrl}/load/page/1/genres/korku-filmlerini-izle-4/" to "Korku Filmleri",
        "${mainUrl}/load/page/1/genres/romantik-filmleri-izle-2/" to "Romantik Filmleri"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Update page number in URL
        val url = if (page == 1) {
            request.data
                .replace("/load/page/1/genres/", "/tur/")
                .replace("/load/page/1/categories/", "/category/")
                .replace("/load/page/1/imdb7/", "/imdb-7-puan-uzeri-filmler/")
        } else {
            request.data
                .replace("/page/1/", "/page/${page}/")
        }

        // Send API request
        val response = app.get(url, headers = standardHeaders, referer = mainUrl)

        // Return empty list if response is not successful
        if (response.text.contains("Sayfa Bulunamadı")) {
            Log.d("HDFC", "Sayfa bulunamadı: $url")
            return newHomePageResponse(request.name, emptyList())
        }

        try {
            // Parse JSON response
            val hdfc: HDFC = objectMapper.readValue(response.text)
            val document = Jsoup.parse(hdfc.html)

            Log.d("HDFC", "Kategori ${request.name} için ${document.select("a").size} sonuç bulundu")

            // Convert film/series cards to SearchResponse list
            val results = document.select("a").mapNotNull { it.toSearchResult() }

            return newHomePageResponse(request.name, results)
        } catch (e: Exception) {
            Log.e("HDFC", "JSON parse hatası (${request.name}): ${e.message}")
            return newHomePageResponse(request.name, emptyList())
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.attr("title")
            .takeIf { it.isNotEmpty() }
            ?.trim()
            ?: this.selectFirst("strong.poster-title")?.text()?.trim()
            ?: return null

        val href = fixUrlNull(this.attr("href")) ?: return null
        val posterUrl = fixUrlNull(
            this.selectFirst("img[data-src], img[src]")?.attr("data-src")
                ?: this.selectFirst("img")?.attr("src")
        )

        // Year
        val yearText = this.selectFirst(".poster-meta span")?.text()?.trim()
        val year = yearText?.toIntOrNull()

        // IMDb Score
        val scoreText = this.selectFirst(".poster-meta .imdb")?.ownText()?.trim()
        val score = scoreText?.toFloatOrNull()

        // Dub/Sub info
        val dubSubText = this.selectFirst(".poster-lang span")?.text()?.trim()

        // Determine which labels to show
        val hasDub = dubSubText?.contains("Dublaj", ignoreCase = true) == true
        val hasSub = dubSubText?.contains("Altyazı", ignoreCase = true) == true

        val tvType = if (this.attr("href").contains("/dizi/", ignoreCase = true)
            || this.attr("href").contains("/series", ignoreCase = true)
            || this.attr("href").contains("home-series", ignoreCase = true)
        ) TvType.TvSeries else TvType.Movie

        return newMovieSearchResponse(title, href, tvType) {
            this.posterUrl = posterUrl
            this.year = year
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.get(
            "${mainUrl}/search?q=${query}",
            headers = mapOf("X-Requested-With" to "fetch")
        ).parsedSafe<Results>() ?: return emptyList()

        val searchResults = mutableListOf<SearchResponse>()

        response.results.forEach { resultHtml ->
            val document = Jsoup.parse(resultHtml)

            val title = document.selectFirst("h4.title")?.text() ?: return@forEach
            val href = fixUrlNull(document.selectFirst("a")?.attr("href")) ?: return@forEach
            val posterUrl = fixUrlNull(document.selectFirst("img")?.attr("src"))
                ?: fixUrlNull(document.selectFirst("img")?.attr("data-src"))

            searchResults.add(
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = posterUrl?.replace("/thumb/", "/list/")
                }
            )
        }

        return searchResults
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("strong.poster-title")?.text()?.trim()
            ?: document.selectFirst("h1.section-title")?.text()?.substringBefore(" izle")
            ?: return null

        val poster = fixUrlNull(
            document.selectFirst("img.lazyload, img[data-src]")?.attr("data-src")
                ?: document.selectFirst("img[src]")?.attr("src")
        )

        // Year
        val yearText = document.selectFirst(".poster-meta span")?.text()?.trim()
        val year = yearText?.toIntOrNull()

        // IMDb Score
        val scoreText = document.selectFirst(".imdb, .popover-rating p")?.text()
            ?.substringBefore("(")?.trim()
        val score = scoreText?.toFloatOrNull()

        // Description
        val description = document.selectFirst(".popover-description, article.post-info-content > p")
            ?.text()?.replace("Özet", "")?.trim()

        // Genres
        val genresText = document.selectFirst(".popover-meta span:matchesOwn(Türler)")
            ?.parent()?.ownText()?.trim()
        val tags = genresText?.split(",")?.map { it.trim() } ?: emptyList()

        // Determine type (Movie or Series)
        val typeText = document.selectFirst(".popover-meta span:matchesOwn(Kategori)")
            ?.parent()?.ownText()?.lowercase()
        val tvType = when {
            typeText?.contains("dizi") == true -> TvType.TvSeries
            else -> TvType.Movie
        }

        // Actors
        val actors = document.select("div.post-info-cast a").map {
            val name = it.selectFirst("strong")?.text() ?: it.text()
            val image = fixUrlNull(it.selectFirst("img")?.attr("data-src"))
            Actor(name, image)
        }

        // Recommendations
        val recommendations = document.select("div.section-slider-container div.slider-slide").mapNotNull {
            val recName = it.selectFirst("a")?.attr("title") ?: return@mapNotNull null
            val recHref = fixUrlNull(it.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
            val recPosterUrl = fixUrlNull(it.selectFirst("img")?.attr("data-src"))
                ?: fixUrlNull(it.selectFirst("img")?.attr("src"))

            newTvSeriesSearchResponse(recName, recHref, TvType.TvSeries) {
                this.posterUrl = recPosterUrl
            }
        }

        // Trailer
        val trailer = document.selectFirst("div.post-info-trailer button")?.attr("data-modal")
            ?.substringAfter("trailer/")?.let { "https://www.youtube.com/embed/$it" }

        // Construct final response
        return if (tvType == TvType.TvSeries) {
            val episodes = document.select("div.seasons-tab-content a").mapNotNull {
                val epName = it.selectFirst("h4")?.text()?.trim() ?: return@mapNotNull null
                val epHref = fixUrlNull(it.attr("href")) ?: return@mapNotNull null
                val epEpisode = Regex("""(\d+)\. ?Bölüm""").find(epName)?.groupValues?.get(1)?.toIntOrNull()
                val epSeason = Regex("""(\d+)\. ?Sezon""").find(epName)?.groupValues?.get(1)?.toIntOrNull() ?: 1

                newEpisode(epHref) {
                    this.name = epName
                    this.season = epSeason
                    this.episode = epEpisode
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = Score.from10(score)
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = Score.from10(score)
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        
        Log.d("HDFC", "Starting loadLinks for: $data")
        
        // Extract the main iframe source
        val iframeSrc = fixUrlNull(
            document.selectFirst(".close")?.attr("data-src")
                ?: document.selectFirst(".rapidrame")?.attr("data-src")
        )

        Log.d("HDFC", "Found iframe source: $iframeSrc")

        if (iframeSrc != null) {
            if (iframeSrc.contains("hdfilmcehennemi.mobi")) {
                // Handle Close player
                Log.d("HDFC", "Processing Close player")
                handleClosePlayer(iframeSrc, subtitleCallback, callback)
            } else if (iframeSrc.contains("rplayer")) {
                // Handle Rapidrame player
                Log.d("HDFC", "Processing Rapidrame player")
                handleRapidramePlayer(iframeSrc, subtitleCallback, callback)
            }
        } else {
            Log.d("HDFC", "No iframe source found, trying direct extraction")
            // Try direct extraction from the page
            extractDirectVideoSources(document, callback)
        }

        // Also process alternative links from the main page
        Log.d("HDFC", "Processing alternative links")
        processAlternativeLinks(document, data, subtitleCallback, callback)

        return true
    }

    private suspend fun handleClosePlayer(
        iframeUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("HDFC", "Loading Close player iframe: $iframeUrl")
            val iframeDoc = app.get(iframeUrl, referer = mainUrl).document
            val baseUri = iframeDoc.location().substringBefore("/", "https://www.hdfilmcehennemi.mobi")

            // Extract subtitles
            iframeDoc.select("track[kind=captions]")
                .filter { it.attr("srclang") != "forced" }
                .forEach { track ->
                    val lang = track.attr("srclang").let {
                        when (it) {
                            "tr" -> "Türkçe"
                            "en" -> "İngilizce"
                            else -> it
                        }
                    }
                    val subUrl = track.attr("src").let { src ->
                        if (src.startsWith("http")) src else "$baseUri/$src".replace("//", "/")
                    }
                    Log.d("HDFC", "Found subtitle: $lang - $subUrl")
                    subtitleCallback(SubtitleFile(lang, subUrl))
                }

            // Extract video source using multiple methods
            extractVideoFromClosePlayer(iframeDoc, subtitleCallback, callback)
            
        } catch (e: Exception) {
            Log.e("HDFC", "Error handling Close player: ${e.message}")
        }
    }

    private suspend fun extractVideoFromClosePlayer(
        iframeDoc: org.jsoup.nodes.Document,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d("HDFC", "Extracting video from Close player")
        
        // Method 1: Look for video URLs in script tags
        val scripts = iframeDoc.select("script")
        Log.d("HDFC", "Found ${scripts.size} script tags")
        
        scripts.forEach { script ->
            val scriptContent = script.data()
            if (scriptContent.isNotEmpty()) {
                Log.d("HDFC", "Checking script content: ${scriptContent.length} chars")
                
                // Look for base64 encoded video URLs
                val base64Regex = Regex("""file_link\s*=\s*["']([^"']+)["']""")
                val base64Match = base64Regex.find(scriptContent)
                if (base64Match != null) {
                    val base64Data = base64Match.groupValues[1]
                    Log.d("HDFC", "Found base64 data: $base64Data")
                    try {
                        val decodedUrl = base64Decode(base64Data)
                        Log.d("HDFC", "Decoded URL: $decodedUrl")
                        if (decodedUrl.isNotEmpty()) {
                            callback.invoke(
                                newExtractorLink(
                                    name = "Close Player",
                                    url = decodedUrl,
                                    source = "Close"
                                )
                            )
                            return
                        }
                    } catch (e: Exception) {
                        Log.e("HDFC", "Error decoding base64: ${e.message}")
                    }
                }
                
                // Look for m3u8 URLs
                val m3u8Urls = Regex("""https?://[^\s"']+\.m3u8[^\s"']*""").findAll(scriptContent)
                m3u8Urls.forEach { match ->
                    val videoUrl = match.value
                    Log.d("HDFC", "Found m3u8 URL in script: $videoUrl")
                    callback.invoke(
                        newExtractorLink(
                            name = "Close Player (HLS)",
                            url = videoUrl,
                            source = "Close"
                        )
                    )
                }
                
                // Look for mp4 URLs
                val mp4Urls = Regex("""https?://[^\s"']+\.mp4[^\s"']*""").findAll(scriptContent)
                mp4Urls.forEach { match ->
                    val videoUrl = match.value
                    Log.d("HDFC", "Found mp4 URL in script: $videoUrl")
                    callback.invoke(
                        newExtractorLink(
                            name = "Close Player (MP4)",
                            url = videoUrl,
                            source = "Close"
                        )
                    )
                }
                
                // Look for JSON sources
                val sourcesRegex = Regex("""sources\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
                val sourcesMatch = sourcesRegex.find(scriptContent)
                if (sourcesMatch != null) {
                    try {
                        val sourcesJson = "[${sourcesMatch.groupValues[1]}]"
                        Log.d("HDFC", "Found JSON sources: $sourcesJson")
                        val sources: List<VideoSource> = objectMapper.readValue(sourcesJson)
                        sources.forEach { source ->
                            source.file?.let { fileUrl ->
                                Log.d("HDFC", "Found video source in JSON: $fileUrl")
                                callback.invoke(
                                    newExtractorLink(
                                        name = "Close Player",
                                        url = fileUrl,
                                        source = "Close"
                                    )
                                )
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("HDFC", "Error parsing JSON sources: ${e.message}")
                    }
                }
            }
        }

        // Method 2: Look for video elements
        val videoElements = iframeDoc.select("video")
        Log.d("HDFC", "Found ${videoElements.size} video elements")
        videoElements.forEach { video ->
            val src = video.attr("src")
            if (src.isNotEmpty()) {
                Log.d("HDFC", "Found video element src: $src")
                callback.invoke(
                    newExtractorLink(
                        name = "Close Player (Video)",
                        url = fixUrlNull(src) ?: src,
                        source = "Close"
                    )
                )
            }
            
            // Check for source tags inside video
            video.select("source").forEach { source ->
                val sourceSrc = source.attr("src")
                if (sourceSrc.isNotEmpty()) {
                    Log.d("HDFC", "Found source tag src: $sourceSrc")
                    callback.invoke(
                        newExtractorLink(
                            name = "Close Player (Source)",
                            url = fixUrlNull(sourceSrc) ?: sourceSrc,
                            source = "Close"
                        )
                    )
                }
            }
        }

        // Method 3: Try to use loadExtractor for the iframe URL itself
        if (scripts.isEmpty() && videoElements.isEmpty()) {
            Log.d("HDFC", "No direct video found, trying loadExtractor")
            val iframeUrl = iframeDoc.location()
            loadExtractor(iframeUrl, "$mainUrl/", subtitleCallback) { link ->
                callback.invoke(link)
            }
        }
    }

    private suspend fun handleRapidramePlayer(
        iframeUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("HDFC", "Loading Rapidrame player iframe: $iframeUrl")
            val iframeDoc = app.get(iframeUrl, referer = "$mainUrl/").document
            
            // Extract subtitles from JSON config
            extractSubtitlesFromRapidrame(iframeDoc, subtitleCallback)
            
            // Extract video sources
            extractVideoFromRapidrame(iframeDoc, subtitleCallback, callback)
            
        } catch (e: Exception) {
            Log.e("HDFC", "Error handling Rapidrame player: ${e.message}")
        }
    }

    private suspend fun extractSubtitlesFromRapidrame(
        iframeDoc: org.jsoup.nodes.Document,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val scriptContent = iframeDoc.toString()
        val tracksRegex = Regex("""tracks\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
        val tracksMatch = tracksRegex.find(scriptContent)
        
        if (tracksMatch != null) {
            val tracksJson = "[${tracksMatch.groupValues[1]}]"
            try {
                Log.d("HDFC", "Found subtitle tracks JSON: $tracksJson")
                val subtitles: List<SubtitleTrack> = objectMapper.readValue(tracksJson)
                subtitles.forEach { track ->
                    val lang = when (track.language) {
                        "en" -> "İngilizce"
                        "tr" -> if (track.label?.contains("Forced") == true) "Forced" else "Türkçe"
                        else -> track.language ?: "Unknown"
                    }
                    if (lang != "Forced") {
                        val subUrl = if (track.file?.startsWith("/") == true) {
                            "$mainUrl${track.file}"
                        } else {
                            track.file
                        }
                        subUrl?.let {
                            Log.d("HDFC", "Found subtitle: $lang - $it")
                            subtitleCallback(SubtitleFile(lang, it))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("HDFC", "Error parsing subtitle tracks: ${e.message}")
            }
        }
    }

    private suspend fun extractVideoFromRapidrame(
        iframeDoc: org.jsoup.nodes.Document,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d("HDFC", "Extracting video from Rapidrame player")
        val scriptContent = iframeDoc.toString()
        
        // Method 1: Extract from JSON sources
        val sourceRegex = Regex("""sources\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
        val sourceMatch = sourceRegex.find(scriptContent)
        
        if (sourceMatch != null) {
            val sourcesJson = "[${sourceMatch.groupValues[1]}]"
            try {
                Log.d("HDFC", "Found video sources JSON: $sourcesJson")
                val sources: List<VideoSource> = objectMapper.readValue(sourcesJson)
                sources.forEach { source ->
                    source.file?.let { fileUrl ->
                        Log.d("HDFC", "Found Rapidrame video: $fileUrl")
                        callback.invoke(
                            newExtractorLink(
                                name = "Rapidrame Player",
                                url = fileUrl,
                                source = "Rapidrame"
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("HDFC", "Error parsing video sources: ${e.message}")
            }
        }

        // Method 2: Look for direct video URLs in scripts
        val scripts = iframeDoc.select("script")
        scripts.forEach { script ->
            val scriptData = script.data()
            if (scriptData.isNotEmpty()) {
                // Look for m3u8 URLs
                val m3u8Urls = Regex("""https?://[^\s"']+\.m3u8[^\s"']*""").findAll(scriptData)
                m3u8Urls.forEach { match ->
                    val videoUrl = match.value
                    if (videoUrl.contains("rapidrame") || videoUrl.contains("hls")) {
                        Log.d("HDFC", "Found Rapidrame m3u8: $videoUrl")
                        callback.invoke(
                            newExtractorLink(
                                name = "Rapidrame Player (HLS)",
                                url = videoUrl,
                                source = "Rapidrame"
                            )
                        )
                    }
                }
            }
        }

        // Method 3: Try loadExtractor as fallback
        if (sourceMatch == null) {
            Log.d("HDFC", "No direct video found in Rapidrame, trying loadExtractor")
            val iframeUrl = iframeDoc.location()
            loadExtractor(iframeUrl, "$mainUrl/", subtitleCallback) { link ->
                callback.invoke(link)
            }
        }
    }

    private suspend fun extractDirectVideoSources(
        document: org.jsoup.nodes.Document,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d("HDFC", "Trying direct video extraction from page")
        
        // Look for video elements directly in the page
        val videoElements = document.select("video")
        videoElements.forEach { video ->
            val src = video.attr("src")
            if (src.isNotEmpty()) {
                Log.d("HDFC", "Found direct video element: $src")
                callback.invoke(
                    newExtractorLink(
                        name = "Direct Video",
                        url = fixUrlNull(src) ?: src,
                        source = "Direct"
                    )
                )
            }
        }
    }

    private suspend fun processAlternativeLinks(
        document: org.jsoup.nodes.Document,
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val alternativeLinks = document.select("div.alternative-links")
        Log.d("HDFC", "Found ${alternativeLinks.size} alternative link containers")
        
        alternativeLinks.map { element ->
            element to element.attr("data-lang").uppercase()
        }.forEach { (element, langCode) ->
            val buttons = element.select("button.alternative-link")
            Log.d("HDFC", "Found ${buttons.size} buttons for lang: $langCode")
            
            buttons.map { button ->
                button.text().replace("(HDrip Xbet)", "").trim() + " $langCode" to button.attr("data-video")
            }.forEach { (source, videoID) ->
                try {
                    Log.d("HDFC", "Processing alternative link: $source -> $videoID")
                    val apiResponse = app.get(
                        "${mainUrl}/video/$videoID/",
                        headers = mapOf(
                            "Content-Type" to "application/json",
                            "X-Requested-With" to "fetch"
                        ),
                        referer = data
                    ).text

                    var iframe = Regex("""data-src=\\"([^"]+)""").find(apiResponse)?.groupValues?.get(1)
                        ?.replace("\\", "") ?: return@forEach

                    if (iframe.contains("?rapidrame_id=")) {
                        iframe = "${mainUrl}/playerr/" + iframe.substringAfter("?rapidrame_id=")
                    }

                    Log.d("HDFC", "Alternative link iframe: $iframe")
                    
                    // Load the iframe and extract video
                    loadVideoFromIframe(source, iframe, data, subtitleCallback, callback)
                } catch (e: Exception) {
                    Log.e("HDFC", "Error processing alternative link $source: ${e.message}")
                }
            }
        }
    }

    private suspend fun loadVideoFromIframe(
        source: String,
        iframeUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("HDFC", "Loading video from iframe: $iframeUrl")
            val iframeDoc = app.get(iframeUrl, referer = referer).document
            
            // Look for video sources in the iframe
            val videoSources = iframeDoc.select("source[src]")
            videoSources.forEach { sourceElement ->
                val videoUrl = fixUrlNull(sourceElement.attr("src"))
                videoUrl?.let { url ->
                    Log.d("HDFC", "Found video source in iframe: $url")
                    callback.invoke(
                        newExtractorLink(
                            name = source,
                            url = url,
                            source = source
                        )
                    )
                }
            }

            // Also check for video elements
            val videoElements = iframeDoc.select("video[src]")
            videoElements.forEach { videoElement ->
                val videoUrl = fixUrlNull(videoElement.attr("src"))
                videoUrl?.let { url ->
                    Log.d("HDFC", "Found video element in iframe: $url")
                    callback.invoke(
                        newExtractorLink(
                            name = source,
                            url = url,
                            source = source
                        )
                    )
                }
            }
            
            // If no direct video found, try to extract from scripts
            if (videoSources.isEmpty() && videoElements.isEmpty()) {
                if (iframeUrl.contains("hdfilmcehennemi.mobi")) {
                    extractVideoFromClosePlayer(iframeDoc, subtitleCallback, callback)
                } else if (iframeUrl.contains("rplayer")) {
                    extractVideoFromRapidrame(iframeDoc, subtitleCallback, callback)
                } else {
                    // Try loadExtractor as last resort
                    Log.d("HDFC", "No direct video found in iframe, trying loadExtractor")
                    loadExtractor(iframeUrl, referer, subtitleCallback) { link ->
                        callback.invoke(link)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("HDFC", "Error loading video from iframe: ${e.message}")
        }
    }

    private fun base64Decode(encoded: String): String {
        return try {
            String(android.util.Base64.decode(encoded, android.util.Base64.DEFAULT))
        } catch (e: Exception) {
            Log.e("HDFC", "Base64 decode error: ${e.message}")
            ""
        }
    }

    // Data classes for JSON parsing
    data class Results(
        @JsonProperty("results") val results: List<String> = arrayListOf()
    )

    data class HDFC(
        @JsonProperty("html") val html: String,
        @JsonProperty("meta") val meta: Meta
    )

    data class Meta(
        @JsonProperty("title") val title: String,
        @JsonProperty("canonical") val canonical: Boolean,
        @JsonProperty("keywords") val keywords: Boolean
    )

    data class SubtitleTrack(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("language") val language: String? = null,
        @JsonProperty("kind") val kind: String? = null,
        @JsonProperty("default") val default: Boolean? = null
    )

    data class VideoSource(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("type") val type: String? = null
    )
}
