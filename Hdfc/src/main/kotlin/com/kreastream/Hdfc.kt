package com.kreastream

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
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.IOException

private inline fun <reified T> okhttp3.Response.tryParseJson(): T? {
    return try {
        val mapper = jacksonObjectMapper().apply {
            registerModule(KotlinModule())
            configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        }
        body?.string()?.let { mapper.readValue(it, T::class.java) }
    } catch (e: IOException) {
        e.printStackTrace()
        null
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

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
                // Cloudflare protection detected
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
            return newHomePageResponse(request.name, emptyList())
        }

        try {
            // Parse JSON response
            val hdfc: HDFC = objectMapper.readValue(response.text)
            val document = Jsoup.parse(hdfc.html)

            // Convert film/series cards to SearchResponse list
            val results = document.select("a").mapNotNull { it.toSearchResult() }

            return newHomePageResponse(request.name, results)
        } catch (e: Exception) {
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
        ).tryParseJson<Results>() ?: return emptyList()

        val searchResults = mutableListOf<SearchResponse>()

        response.results.forEach { resultHtml ->
            val document = Jsoup.parse(resultHtml)

            val title = document.select("h4.title").first()?.text() ?: return@forEach
            val href = fixUrlNull(document.select("a").first()?.attr("href")) ?: return@forEach
            val posterUrl = fixUrlNull(document.select("img").first()?.attr("src"))
                ?: fixUrlNull(document.select("img").first()?.attr("data-src"))

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
        
        // Extract the main iframe source
        val closePlayerSrc = document.selectFirst(".close")?.attr("data-src")
        val rapidramePlayerSrc = document.selectFirst(".rapidrame")?.attr("data-src")

        // Process Close Player if available
        if (!closePlayerSrc.isNullOrEmpty()) {
            val iframeSrc = fixUrlNull(closePlayerSrc)
            if (iframeSrc != null) {
                handleClosePlayer(iframeSrc, subtitleCallback, callback)
            }
        }

        // Process Rapidrame Player if available  
        if (!rapidramePlayerSrc.isNullOrEmpty()) {
            val iframeSrc = fixUrlNull(rapidramePlayerSrc)
            if (iframeSrc != null) {
                handleRapidramePlayer(iframeSrc, subtitleCallback, callback)
            }
        }

        // If no players found, try alternative methods
        if (closePlayerSrc.isNullOrEmpty() && rapidramePlayerSrc.isNullOrEmpty()) {
            extractDirectVideoSources(document, callback)
        }

        // Also process alternative links from the main page
        processAlternativeLinks(document, data, subtitleCallback, callback)

        return true
    }

    private suspend fun handleClosePlayer(
        iframeUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val iframeDoc = app.get(iframeUrl, referer = mainUrl).document

            // Extract subtitles
            iframeDoc.select("track[kind=captions]")
                .filter { it.attr("srclang") != "forced" }
                .forEach { track ->
                    val lang = track.attr("srclang").let {
                        when (it) {
                            "tr" -> "Türkçe"
                            "en" -> "İngilizce"
                            "fr" -> "Fransızca"
                            "pt" -> "Portekizce"
                            else -> it
                        }
                    }
                    val subUrl = fixUrlNull(track.attr("src"))
                    if (!subUrl.isNullOrEmpty()) {
                        subtitleCallback(SubtitleFile(lang, subUrl))
                    }
                }

            // Extract video source
            extractVideoFromClosePlayer(iframeDoc, iframeUrl, subtitleCallback, callback)
            
        } catch (e: Exception) {
            // Error handling
        }
    }

    private suspend fun extractVideoFromClosePlayer(
        iframeDoc: org.jsoup.nodes.Document,
        iframeUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        var foundVideo = false
        
        // Method 1: Look for JSON-LD schema with contentUrl
        val jsonLdScript = iframeDoc.select("script[type='application/ld+json']").firstOrNull()
        if (jsonLdScript != null) {
            try {
                val jsonLdText = jsonLdScript.data()
                
                val contentUrlRegex = Regex(""""contentUrl"\s*:\s*"([^"]+)"""")
                val contentUrlMatch = contentUrlRegex.find(jsonLdText)
                if (contentUrlMatch != null) {
                    val videoUrl = contentUrlMatch.groupValues[1]
                    
                    if (videoUrl.contains(".m3u8") || videoUrl.contains(".mp4")) {
                        callback(newExtractorLink(
                            name = "Close Player (JSON-LD)",
                            url = videoUrl,
                            source = "Close"
                        ))
                        foundVideo = true
                        return
                    }
                }
            } catch (e: Exception) {
                // Error parsing JSON-LD
            }
        }

        // Method 2: Look for master.m3u8 or master.txt in scripts
        val scripts = iframeDoc.select("script")
        
        scripts.forEach { script ->
            val scriptContent = script.data()
            if (scriptContent.isNotEmpty()) {
                // Look for master.m3u8 or master.txt URLs
                val masterUrls = Regex("""https?://[^\s"']+/(master\.(m3u8|txt))[^\s"']*""").findAll(scriptContent)
                masterUrls.forEach { match ->
                    val videoUrl = match.value
                    
                    callback(newExtractorLink(
                        name = "Close Player (HLS)",
                        url = videoUrl,
                        source = "Close"
                    ))
                    foundVideo = true
                }

                // Look for base64 encoded URLs
                val base64Regex = Regex("""["']([A-Za-z0-9+/=]{20,})["']""")
                val base64Matches = base64Regex.findAll(scriptContent)
                
                base64Matches.forEach { match ->
                    val base64Data = match.groupValues[1]
                    try {
                        val decodedUrl = base64Decode(base64Data)
                        if (decodedUrl.isNotEmpty() && (decodedUrl.contains(".m3u8") || decodedUrl.contains(".mp4"))) {
                            callback(newExtractorLink(
                                name = "Close Player (Base64)",
                                url = decodedUrl,
                                source = "Close"
                            ))
                            foundVideo = true
                        }
                    } catch (e: Exception) {
                        // Ignore base64 decode errors
                    }
                }
            }
        }

        // Method 3: Use loadExtractor as last resort
        if (!foundVideo) {
            loadExtractor(iframeUrl, "$mainUrl/", subtitleCallback, callback)
        }
    }

    private suspend fun handleRapidramePlayer(
        iframeUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val iframeDoc = app.get(iframeUrl, referer = "$mainUrl/").document
            
            // Extract video sources
            extractVideoFromRapidrame(iframeDoc, iframeUrl, subtitleCallback, callback)
            
        } catch (e: Exception) {
            // Error handling
        }
    }

    private suspend fun extractVideoFromRapidrame(
        iframeDoc: org.jsoup.nodes.Document,
        iframeUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val scriptContent = iframeDoc.toString()
        var foundVideo = false
        
        // Method 1: Extract master.m3u8 from scripts
        val masterM3u8Regex = Regex("""https?://[^\s"']+\.rapidrame\.com[^\s"']*master\.m3u8[^\s"']*""")
        val masterMatch = masterM3u8Regex.find(scriptContent)
        
        if (masterMatch != null) {
            val videoUrl = masterMatch.value
            callback(newExtractorLink(
                name = "Rapidrame Player (HLS)",
                url = videoUrl,
                source = "Rapidrame"
            ))
            foundVideo = true
        }

        // Method 2: Use loadExtractor as fallback
        if (!foundVideo) {
            loadExtractor(iframeUrl, "$mainUrl/", subtitleCallback, callback)
        }
    }

    private suspend fun extractDirectVideoSources(
        document: org.jsoup.nodes.Document,
        callback: (ExtractorLink) -> Unit
    ) {
        // Look for video elements directly in the page
        val videoElements = document.select("video")
        videoElements.forEach { video ->
            val src = video.attr("src")
            if (src.isNotEmpty()) {
                callback(newExtractorLink(
                    name = "Direct Video",
                    url = fixUrlNull(src) ?: src,
                    source = "Direct"
                ))
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
        
        alternativeLinks.map { element ->
            element to element.attr("data-lang").uppercase()
        }.forEach { (element, langCode) ->
            val buttons = element.select("button.alternative-link")
            
            buttons.map { button ->
                button.text().replace("(HDrip Xbet)", "").trim() + " $langCode" to button.attr("data-video")
            }.forEach { (source, videoID) ->
                try {
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

                    // Load the iframe and extract video
                    loadVideoFromIframe(source, iframe, data, subtitleCallback, callback)
                } catch (e: Exception) {
                    // Error processing alternative link
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
            val iframeDoc = app.get(iframeUrl, referer = referer).document
            
            // Look for video sources in the iframe
            val videoSources = iframeDoc.select("source[src]")
            videoSources.forEach { sourceElement ->
                val videoUrl = fixUrlNull(sourceElement.attr("src"))
                videoUrl?.let { url ->
                    callback(newExtractorLink(
                        name = source,
                        url = url,
                        source = source
                    ))
                }
            }

            // Also check for video elements
            val videoElements = iframeDoc.select("video[src]")
            videoElements.forEach { videoElement ->
                val videoUrl = fixUrlNull(videoElement.attr("src"))
                videoUrl?.let { url ->
                    callback(newExtractorLink(
                        name = source,
                        url = url,
                        source = source
                    ))
                }
            }
            
            // If no direct video found, try to extract from scripts
            if (videoSources.isEmpty() && videoElements.isEmpty()) {
                if (iframeUrl.contains("hdfilmcehennemi.mobi")) {
                    extractVideoFromClosePlayer(iframeDoc, iframeUrl, subtitleCallback, callback)
                } else if (iframeUrl.contains("rplayer")) {
                    extractVideoFromRapidrame(iframeDoc, iframeUrl, subtitleCallback, callback)
                } else {
                    // Try loadExtractor as last resort
                    loadExtractor(iframeUrl, referer, subtitleCallback, callback)
                }
            }
        } catch (e: Exception) {
            // Error loading video from iframe
        }
    }

    private fun base64Decode(encoded: String): String {
        return try {
            String(android.util.Base64.decode(encoded, android.util.Base64.DEFAULT))
        } catch (e: Exception) {
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
