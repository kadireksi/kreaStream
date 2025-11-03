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
import com.lagradost.nicehttp.NiceResponse
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.IOException

private inline fun <reified T> parseJsonResponse(response: NiceResponse): T? {
    return try {
        val mapper = jacksonObjectMapper().apply {
            registerModule(KotlinModule())
            configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        }
        response.text?.let { mapper.readValue(it, T::class.java) }
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
        val response = parseJsonResponse<Results>(
            app.get(
                "${mainUrl}/search?q=${query}",
                headers = mapOf("X-Requested-With" to "fetch")
            )
        ) ?: return emptyList()

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
        
        // Extract the main iframe sources
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
            val iframeDoc = app.get(iframeUrl, referer = mainUrl, headers = mapOf(
                "User-Agent" to (standardHeaders["User-Agent"] as? String ?: ""),
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "en-US,en;q=0.5",
                "Accept-Encoding" to "gzip, deflate, br",
                "DNT" to "1",
                "Connection" to "keep-alive",
                "Upgrade-Insecure-Requests" to "1"
            )).document

            // Extract subtitles
            iframeDoc.select("track[kind=subtitles], track[kind=captions]")
                .filter { it.attr("srclang") != "forced" }
                .forEach { track ->
                    val lang = track.attr("srclang").let {
                        when (it) {
                            "tr" -> "Türkçe"
                            "en" -> "İngilizce"
                            "fr" -> "Fransızca"
                            "pt" -> "Portekizce"
                            "es" -> "İspanyolca"
                            "de" -> "Almanca"
                            "it" -> "İtalyanca"
                            "ru" -> "Rusça"
                            "ar" -> "Arapça"
                            else -> it
                        }
                    }
                    val subUrl = fixUrlNull(track.attr("src"))
                    if (!subUrl.isNullOrEmpty()) {
                        subtitleCallback(SubtitleFile(lang, subUrl))
                    }
                }

            // Extract video source with enhanced methods
            extractVideoFromClosePlayer(iframeDoc, iframeUrl, subtitleCallback, callback)
            
        } catch (e: Exception) {
            // If direct extraction fails, try loadExtractor as last resort
            loadExtractor(iframeUrl, mainUrl, subtitleCallback, callback)
        }
    }

    private suspend fun extractVideoFromClosePlayer(
        iframeDoc: org.jsoup.nodes.Document,
        iframeUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        var foundVideo = false

        // === METHOD 1: Extract from video.js configuration ===
        iframeDoc.select("script").forEach { script ->
            val scriptContent = script.data() ?: script.html()
            
            // Look for video.js configuration with sources
            if (scriptContent.contains("videojs") || scriptContent.contains("sources")) {
                try {
                    // Extract JSON-like configuration
                    val sourcesRegex = Regex("""sources\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
                    val match = sourcesRegex.find(scriptContent)
                    
                    if (match != null) {
                        val sourcesText = match.groupValues[1]
                        val urlRegex = Regex("""(https?://[^\s"']+\.(?:m3u8|mp4)[^\s"']*)""")
                        
                        urlRegex.findAll(sourcesText).forEach { urlMatch ->
                            val videoUrl = urlMatch.value
                            val extractorLink = newExtractorLink(
                                name = "Close Player (Direct)",
                                url = videoUrl,
                                source = "Close"
                            ) {
                                referer = iframeUrl
                                quality = Qualities.Unknown.value
                                isM3u8 = videoUrl.contains(".m3u8")
                            }
                            callback(extractorLink)
                            foundVideo = true
                        }
                    }
                } catch (e: Exception) {
                    // Continue to next method
                }
            }
        }
                    }
                } catch (e: Exception) {
                    // Continue to next method
                }
            }
        }

        // === METHOD 2: Extract from data attributes ===
        if (!foundVideo) {
            iframeDoc.select("video, [data-src]").forEach { element ->
                val videoUrl = element.attr("data-src") ?: element.attr("src")
                if (videoUrl.isNotEmpty() && (videoUrl.contains(".m3u8") || videoUrl.contains(".mp4"))) {
                    callback(
                        newExtractorLink(
                            name = "Close Player (Data Source)",
                            url = fixUrlNull(videoUrl) ?: videoUrl,
                            source = "Close"
                        ){
                            this.referer = iframeUrl;
                            this.quality = Qualities.Unknown.value;
                            this.isM3u8 = videoUrl.contains(".m3u8")
                        }
                    )
                    foundVideo = true
                }
            }
        }

        // === METHOD 3: Improved URL pattern detection ===
        if (!foundVideo) {
            val possiblePatterns = listOf(
                Regex("""https?://[^"'\s]*?cdn[^"'\s]*?\.(?:m3u8|mp4|master\.txt)"""),
                Regex("""https?://[^"'\s]*?srv[^"'\s]*?\.(?:m3u8|mp4|master\.txt)"""),
                Regex("""https?://[^"'\s]*?stream[^"'\s]*?\.(?:m3u8|mp4|master\.txt)"""),
                Regex("""file\s*:\s*["'](https?://[^"']+\.(?:m3u8|mp4))["']"""),
                Regex("""src\s*:\s*["'](https?://[^"']+\.(?:m3u8|mp4))["']""")
            )
            
            val pageContent = iframeDoc.toString()
            possiblePatterns.forEach { pattern ->
                pattern.findAll(pageContent).forEach { match ->
                    val videoUrl = match.value.replace("file:", "").replace("src:", "").trim().trim('"', '\'')
                    if (videoUrl.isNotEmpty() && (videoUrl.contains(".m3u8") || videoUrl.contains(".mp4"))) {
                        callback(
                            newExtractorLink(
                                name = "Close Player (Pattern)",
                                url = videoUrl,
                                source = "Close"
                            ){
                                this.referer = iframeUrl;
                                this.quality = Qualities.Unknown.value;
                                this.isM3u8 = videoUrl.contains(".m3u8")
                            }
                        )
                        foundVideo = true
                    }
                }
            }
        }

        // === METHOD 4: Dynamic URL construction with better patterns ===
        if (!foundVideo) {
            try {
                // Extract video ID from URL
                val videoId = iframeUrl.substringAfter("/embed/").substringBefore("/")
                if (videoId.isNotEmpty()) {
                    val baseDomains = listOf(
                        "https://srv10.cdnimages1332.sbs",
                        "https://srv9.cdnimages1332.sbs", 
                        "https://srv8.cdnimages1332.sbs",
                        "https://srv7.cdnimages1332.sbs"
                    )
                    
                    val urlPatterns = baseDomains.flatMap { base ->
                        listOf(
                            "$base/hls/$videoId/master.txt",
                            "$base/hls/$videoId/master.m3u8",
                            "$base/video/$videoId/master.m3u8",
                            "$base/stream/$videoId/master.txt"
                        )
                    }
                    
                    urlPatterns.forEach { testUrl ->
                        // Test if URL is accessible
                        try {
                            val testResponse = app.get(testUrl, headers = mapOf(
                                "Referer" to iframeUrl,
                                "Origin" to "https://closeplayer.hdfilmcehennemi.mobi"
                            ), allowRedirects = false)
                            
                            if (testResponse.status in 200..299) {
                                val link = newExtractorLink(
                                    name = "Close Player (Dynamic)",
                                    url = testUrl,
                                    source = "Close"
                                ) {
                                    referer = iframeUrl
                                    quality = Qualities.Unknown.value
                                    isM3u8 = testUrl.contains(".m3u8")
                                }
                                callback(link)
                                foundVideo = true
                                return@forEach
                            }
                        } catch (e: Exception) {
                            // Continue to next pattern
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore errors in dynamic construction
            }
        }

        // === METHOD 5: Enhanced script unpacking ===
        if (!foundVideo) {
            iframeDoc.select("script").forEach { script ->
                val content = script.data() ?: script.html()
                if (content.contains("eval(function(p,a,c,k,e,d)")) {
                    try {
                        val unpacked = JsPackerUnpacker.unpack(content)
                        
                        // Look for various video URL patterns in unpacked script
                        val videoPatterns = listOf(
                            Regex("""https?://[^\s"']*?\.m3u8[^\s"']*"""),
                            Regex("""https?://[^\s"']*?\.mp4[^\s"']*"""),
                            Regex("""https?://[^\s"']*?master\.txt[^\s"']*"""),
                            Regex("""file\s*:\s*["'](https?://[^"']+)["']""")
                        )
                        
                        videoPatterns.forEach { pattern ->
                            pattern.findAll(unpacked).forEach { match ->
                                var videoUrl = match.value
                                if (videoUrl.startsWith("file:")) {
                                    videoUrl = match.groupValues[1]
                                }
                                
                                if (videoUrl.isNotEmpty() && (videoUrl.contains(".m3u8") || videoUrl.contains(".mp4"))) {
                                    val link = newExtractorLink(
                                        name = "Close Player (Unpacked)",
                                        url = videoUrl,
                                        source = "Close"
                                    ) {
                                        referer = iframeUrl
                                        quality = Qualities.Unknown.value
                                        isM3u8 = videoUrl.contains(".m3u8")
                                    }
                                    callback(link)
                                    foundVideo = true
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // Ignore unpacking errors
                    }
                }
            }
        }

        // === FINAL FALLBACK: Use loadExtractor ===
        if (!foundVideo) {
            loadExtractor(iframeUrl, mainUrl, subtitleCallback, callback)
        }
    }

    private suspend fun handleRapidramePlayer(
        iframeUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val iframeDoc = app.get(iframeUrl, referer = "$mainUrl/", headers = mapOf(
                "User-Agent" to standardHeaders["User-Agent"],
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )).document
            
            // Extract video sources
            extractVideoFromRapidrame(iframeDoc, iframeUrl, subtitleCallback, callback)
            
        } catch (e: Exception) {
            // Error handling - fallback to loadExtractor
            loadExtractor(iframeUrl, "$mainUrl/", subtitleCallback, callback)
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
            ) {
                referer = iframeUrl
                quality = Qualities.Unknown.value
                isM3u8 = true
            })
            foundVideo = true
        }

        // Method 2: Look for MP4 sources
        if (!foundVideo) {
            val mp4Regex = Regex("""https?://[^\s"']+\.rapidrame\.com[^\s"']*\.mp4[^\s"']*""")
            val mp4Match = mp4Regex.find(scriptContent)
            
            if (mp4Match != null) {
                val videoUrl = mp4Match.value
callback(newExtractorLink(
                    name = "Rapidrame Player (MP4)",
                    url = videoUrl,
                    source = "Rapidrame"
                ) {
                    referer = iframeUrl
                    quality = Qualities.Unknown.value
                    isM3u8 = false
                })
                foundVideo = true
            }
        }

        // Method 3: Extract from data attributes
        if (!foundVideo) {
            iframeDoc.select("video, source").forEach { element ->
                val src = element.attr("src")
                if (src.isNotEmpty() && (src.contains(".m3u8") || src.contains(".mp4"))) {
callback(newExtractorLink(
                        name = "Rapidrame Player (Direct)",
                        url = fixUrlNull(src) ?: src,
                        source = "Rapidrame"
                    ) {
                        referer = iframeUrl
                        quality = Qualities.Unknown.value
                        isM3u8 = src.contains(".m3u8")
                    })
                    foundVideo = true
                }
            }
        }

        // Method 4: Use loadExtractor as fallback
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
                ) {
                    quality = Qualities.Unknown.value
                })
            }
        }

        // Also check for source elements inside video tags
        document.select("video source").forEach { source ->
            val src = source.attr("src")
            if (src.isNotEmpty()) {
callback(newExtractorLink(
                    name = "Direct Video Source",
                    url = fixUrlNull(src) ?: src,
                    source = "Direct"
                ) {
                    quality = Qualities.Unknown.value
                    isM3u8 = src.contains(".m3u8")
                })
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
            val iframeDoc = app.get(iframeUrl, referer = referer, headers = standardHeaders).document
            
            // Look for video sources in the iframe
            val videoSources = iframeDoc.select("source[src]")
            videoSources.forEach { sourceElement ->
                val videoUrl = fixUrlNull(sourceElement.attr("src"))
                videoUrl?.let { url ->
callback(newExtractorLink(
                        name = source,
                        url = url,
                        source = source
                    ) {
                        quality = Qualities.Unknown.value
                        isM3u8 = url.contains(".m3u8")
                    })
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
                    ) {
                        quality = Qualities.Unknown.value
                        isM3u8 = url.contains(".m3u8")
                    })
                }
            }
            
            // If no direct video found, try to extract from scripts based on player type
            if (videoSources.isEmpty() && videoElements.isEmpty()) {
                if (iframeUrl.contains("hdfilmcehennemi.mobi") || iframeUrl.contains("close")) {
                    extractVideoFromClosePlayer(iframeDoc, iframeUrl, subtitleCallback, callback)
                } else if (iframeUrl.contains("rplayer") || iframeUrl.contains("rapidrame")) {
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

    // ------------------------------------------------------------
    //  Dean-Edwards-Packer unpacker – 100% Kotlin, no external deps
    // ------------------------------------------------------------
    private object JsPackerUnpacker {
        /** Unpacks a string that was produced by the classic packer. */
        fun unpack(packed: String): String {
            // 1. extract the four arguments: code, base, count, dictionary
            val regex = Regex("""eval\(function\(p,a,c,k,e,d\)\{.*?\}\('(.*?)',(\d+),(\d+),'(.*?)'\.split\('\|'\),0,\{\}\)\)""")
            val m = regex.find(packed) ?: throw IllegalArgumentException("Not a packed script")
            val (code, baseStr, cntStr, dictStr) = m.destructured
            val base = baseStr.toInt()
            val dictionary = dictStr.split('|')

            // 2. decode the packed payload (base-62 numbers + escaped chars)
            fun decode(s: String): String = buildString {
                var i = 0
                while (i < s.length) {
                    when (s[i]) {
                        '\\' -> {
                            i++
                            when (s[i]) {
                                'b' -> append('\b')
                                'f' -> append('\u000C')
                                'n' -> append('\n')
                                'r' -> append('\r')
                                't' -> append('\t')
                                '\'' -> append('\'')
                                '\\' -> append('\\')
                                'x' -> {
                                    i++
                                    val hex = s.substring(i, i + 2)
                                    append(hex.toInt(16).toChar())
                                    i += 2
                                }
                                'u' -> {
                                    i++
                                    val hex = s.substring(i, i + 4)
                                    append(hex.toInt(16).toChar())
                                    i += 4
                                }
                                else -> append(s[i])
                            }
                            i++
                        }
                        else -> {
                            var num = 0
                            var start = i
                            while (i < s.length && s[i].let { it.isLetterOrDigit() || it in "_$" }) {
                                val d = when (val ch = s[i]) {
                                    in '0'..'9' -> ch - '0'
                                    in 'a'..'z' -> ch - 'a' + 10
                                    in 'A'..'Z' -> ch - 'A' + 10
                                    else -> break
                                }
                                num = num * base + d
                                i++
                            }
                            // if the number is followed by '(' it is a recursive call → decode inner part
                            if (i < s.length && s[i] == '(') {
                                var depth = 1
                                var p = i + 1
                                while (depth > 0 && p < s.length) {
                                    when (s[p]) { '(' -> depth++; ')' -> depth--; }
                                    p++
                                }
                                append(decode(s.substring(i + 1, p - 1)))
                                i = p
                            } else {
                                append(dictionary.getOrNull(num) ?: num.toString(base))
                            }
                        }
                    }
                }
            }

            // 3. first pass – replace dictionary words
            var js = decode(code)

            // 4. remove the helper boiler-plate that the packer injects
            js = js.replace(Regex("""while\s*\(\s*c\s*--\s*\)\s*\{[^}]*\}"""), "")
                .replace(Regex("""if\s*\(!''.replace\([^)]*\)\)\s*\{[^}]*\}"""), "")
                .replace(Regex("""k=\[function\(e\)\{return d\[e\]\}\];e=function\(\)\{return'\\w+'};c=1"""), "")

            return js
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
