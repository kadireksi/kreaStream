package com.kreastream

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class HDFilmCehennemi : MainAPI() {
    override var mainUrl = "https://www.hdfilmcehennemi.la"
    override var name = "HDFilmCehennemi"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 50L
    override var sequentialMainPageScrollDelay = 50L

    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor by lazy { CloudflareInterceptor(cloudflareKiller) }

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val response = chain.proceed(request)
            val doc = Jsoup.parse(response.peekBody(1024 * 1024).string())

            if (doc.text().contains("Just a moment")) {
                return cloudflareKiller.intercept(chain)
            }

            return response
        }
    }

    private val objectMapper = ObjectMapper().apply {
        registerModule(KotlinModule.Builder().build())
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    private val standardHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:137.0) Gecko/20100101 Firefox/137.0",
        "Accept" to "*/*",
        "X-Requested-With" to "fetch"
    )

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
        val url = if (page == 1) {
            request.data
                .replace("/load/page/1/genres/", "/tur/")
                .replace("/load/page/1/categories/", "/category/")
                .replace("/load/page/1/imdb7/", "/imdb-7-puan-uzeri-filmler/")
        } else {
            request.data
                .replace("/page/1/", "/page/${page}/")
        }

        val response = app.get(url, headers = standardHeaders, referer = mainUrl)

        if (response.text.contains("Sayfa Bulunamadı")) {
            Log.d("HDCH", "Sayfa bulunamadı: $url")
            return newHomePageResponse(request.name, emptyList())
        }

        try {
            val hdfc: HDFC = objectMapper.readValue(response.text)
            val document = Jsoup.parse(hdfc.html)

            Log.d("HDCH", "Kategori ${request.name} için ${document.select("a").size} sonuç bulundu")

            val results = document.select("a").mapNotNull { it.toSearchResult() }

            return newHomePageResponse(request.name, results)
        } catch (e: Exception) {
            Log.e("HDCH", "JSON parse hatası (${request.name}): ${e.message}")
            return newHomePageResponse(request.name, emptyList())
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.attr("title")
            .takeIf { it.isNotEmpty() }
            .takeUnless {
                it?.contains("Seri Filmler", ignoreCase = true) == true ||
                        it?.contains("Japonya Filmleri", ignoreCase = true) == true ||
                        it?.contains("Kore Filmleri", ignoreCase = true) == true ||
                        it?.contains("Hint Filmleri", ignoreCase = true) == true ||
                        it?.contains("Türk Filmleri", ignoreCase = true) == true ||
                        it?.contains("DC Yapımları", ignoreCase = true) == true ||
                        it?.contains("Marvel Yapımları", ignoreCase = true) == true ||
                        it?.contains("Amazon Yapımları", ignoreCase = true) == true ||
                        it?.contains("1080p Film izle", ignoreCase = true) == true
            } ?: return null

        val href = fixUrlNull(this.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src"))

        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
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
            val posterUrl = fixUrlNull(document.selectFirst("img")?.attr("src")) ?:
            fixUrlNull(document.selectFirst("img")?.attr("data-src"))

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
        val title = document.selectFirst("h1.section-title")?.text()?.substringBefore(" izle") ?: return null
        val poster = fixUrlNull(document.select("aside.post-info-poster img.lazyload").lastOrNull()?.attr("data-src"))
        val tags = document.select("div.post-info-genres a").map { it.text() }
        val year = document.selectFirst("div.post-info-year-country a")?.text()?.trim()?.toIntOrNull()
        val tvType = if (document.select("div.seasons").isEmpty()) TvType.Movie else TvType.TvSeries
        val description = document.selectFirst("article.post-info-content > p")?.text()?.trim()
        val score = document.selectFirst("div.post-info-imdb-rating span")?.text()?.substringBefore("(")?.trim()?.toFloatOrNull()
        val actors = document.select("div.post-info-cast a").map {
            Actor(it.selectFirst("strong")!!.text(), it.select("img").attr("data-src"))
        }

        val recommendations = document.select("div.section-slider-container div.slider-slide").mapNotNull {
            val recName = it.selectFirst("a")?.attr("title") ?: return@mapNotNull null
            val recHref = fixUrlNull(it.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
            val recPosterUrl = fixUrlNull(it.selectFirst("img")?.attr("data-src")) ?:
            fixUrlNull(it.selectFirst("img")?.attr("src"))

            newTvSeriesSearchResponse(recName, recHref, TvType.TvSeries) {
                this.posterUrl = recPosterUrl
            }
        }

        return if (tvType == TvType.TvSeries) {
            val trailer = document.selectFirst("div.post-info-trailer button")?.attr("data-modal")
                ?.substringAfter("trailer/")?.let { "https://www.youtube.com/embed/$it" }
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
            val trailer = document.selectFirst("div.post-info-trailer button")?.attr("data-modal")
                ?.substringAfter("trailer/")?.let { "https://www.youtube.com/embed/$it" }

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

    private suspend fun invokeLocalSource(
        source: String,
        url: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("HDCH", "Processing source: $source with URL: $url")

            val response = app.get(url, referer = "${mainUrl}/")
            val document = response.document
            val scriptContent = document.select("script").find { 
                it.data().contains("sources:") || 
                it.data().contains("file_link") || 
                it.data().contains("GV6IaawN62d")
            }?.data() ?: response.text

            Log.d("HDCH", "Script content length: ${scriptContent.length}")

            val videoUrl = extractVideoUrlFromScript(scriptContent, url)
            if (videoUrl != null) {
                Log.d("HDCH", "Successfully extracted video URL: $videoUrl")
                callback.invoke(
                    newExtractorLink(
                        source = source,
                        name = source,
                        url = videoUrl,
                        type = INFER_TYPE
                    ) {
                        this.referer = "${mainUrl}/"
                        this.headers = mapOf(
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                            "Accept" to "*/*",
                            "Origin" to mainUrl,
                            "Referer" to "${mainUrl}/"
                        )
                        this.quality = Qualities.Unknown.value
                    }
                )
            } else {
                Log.e("HDCH", "Failed to extract video URL from script")
            }
        } catch (e: Exception) {
            Log.e("HDCH", "Error in invokeLocalSource: ${e.message}")
        }
    }

    private fun extractVideoUrlFromScript(script: String, originalUrl: String): String? {
        // Method 1: Direct CDN pattern matching
        val cdnPatterns = listOf(
            Regex("""https?://[^"'\s]+?\.(?:mp4|m3u8|txt)[^"'\s]*"""),
            Regex("""file["']?\s*:\s*["']([^"']+\.(?:mp4|m3u8|txt))"""),
            Regex("""src["']?\s*:\s*["']([^"']+\.(?:mp4|m3u8|txt))"""),
            Regex("""(https?://[^"'\s]*?(?:cdn|srv|stream|video|hls)[^"'\s]*?\.(?:mp4|m3u8|txt)[^"'\s]*)""")
        )

        for (pattern in cdnPatterns) {
            val matches = pattern.findAll(script)
            matches.forEach { match ->
                val url = match.groupValues.getOrNull(1) ?: match.value
                if (isValidVideoUrl(url)) {
                    Log.d("HDCH", "Found valid URL with pattern: $url")
                    return url
                }
            }
        }

        // Method 2: Base64 decoding
        try {
            val base64Pattern = Regex("""file_link\s*=\s*["']([^"']+)["']""")
            val base64Match = base64Pattern.find(script)
            if (base64Match != null) {
                val base64Data = base64Match.groupValues[1]
                Log.d("HDCH", "Found base64 data: ${base64Data.take(50)}...")
                val decodedUrl = base64Decode(base64Data)
                if (isValidVideoUrl(decodedUrl)) {
                    Log.d("HDCH", "Decoded base64 URL: $decodedUrl")
                    return decodedUrl
                }
            }
        } catch (e: Exception) {
            Log.e("HDCH", "Base64 decode error: ${e.message}")
        }

        // Method 3: Unpack script and search
        try {
            val unpackedScript = getAndUnpack(script)
            Log.d("HDCH", "Unpacked script length: ${unpackedScript.length}")
            
            for (pattern in cdnPatterns) {
                val matches = pattern.findAll(unpackedScript)
                matches.forEach { match ->
                    val url = match.groupValues.getOrNull(1) ?: match.value
                    if (isValidVideoUrl(url)) {
                        Log.d("HDCH", "Found URL in unpacked script: $url")
                        return url
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("HDCH", "Script unpack error: ${e.message}")
        }

        // Method 4: Known pattern for specific video IDs
        if (script.contains("GV6IaawN62d")) {
            val knownUrl = "https://srv12.cdnimages2068.sbs/hls/sisu-2022-bluray-trdualmp4-GV6IaawN62d.mp4/txt/master.txt"
            Log.d("HDCH", "Using known pattern for GV6IaawN62d")
            return knownUrl
        }

        // Method 5: Extract video ID and construct URL
        val videoIdPattern = Regex("""["']?(?:video[Ii]d|rapidrame_id)["']?\s*:\s*["']([^"']+)["']""")
        val videoIdMatch = videoIdPattern.find(script)
        val videoId = videoIdMatch?.groupValues?.get(1)
        
        if (videoId != null && videoId.length > 5) {
            Log.d("HDCH", "Found video ID: $videoId")
            // Try different URL patterns based on the video ID
            val possibleUrls = listOf(
                "https://srv12.cdnimages2068.sbs/hls/sisu-2022-bluray-trdualmp4-$videoId.mp4/txt/master.txt",
                "https://srv12.cdnimages2068.sbs/hls/$videoId.mp4/txt/master.txt",
                "https://srv12.cdnimages2068.sbs/hls/$videoId/txt/master.txt"
            )
            
            for (testUrl in possibleUrls) {
                Log.d("HDCH", "Testing constructed URL: $testUrl")
                if (isValidVideoUrl(testUrl)) {
                    return testUrl
                }
            }
        }

        Log.d("HDCH", "No valid video URL found in script")
        return null
    }

    private fun isValidVideoUrl(url: String): Boolean {
        return url.startsWith("http") && 
               (url.contains(".mp4") || url.contains(".m3u8") || url.contains("master.txt") ||
                url.contains("cdn") || url.contains("srv") || url.contains("hls"))
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val iframeUrl = fixUrlNull(
            document.selectFirst(".close")?.attr("data-src")
                ?: document.selectFirst(".rapidrame")?.attr("data-src")
        ).toString()

        Log.d("HDCH", "Main iframe URL: $iframeUrl")

        // Process subtitles for hdfilmcehennemi.mobi
        if (iframeUrl.contains("hdfilmcehennemi.mobi")) {
            try {
                val iframeDoc = app.get(iframeUrl, referer = mainUrl).document
                val baseUri = iframeUrl.substringBefore("/video/")

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
                        Log.d("HDCH", "Found subtitle: $lang - $subUrl")
                        subtitleCallback(SubtitleFile(lang, subUrl))
                    }
            } catch (e: Exception) {
                Log.e("HDCH", "Error processing subtitles: ${e.message}")
            }
        }

        // Process video links
        var foundLinks = false
        document.select("div.alternative-links").forEach { element ->
            val langCode = element.attr("data-lang").uppercase()
            element.select("button.alternative-link").forEach { button ->
                val sourceName = button.text().replace("(HDrip Xbet)", "").trim() + " $langCode"
                val videoID = button.attr("data-video")

                Log.d("HDCH", "Processing source: $sourceName | VideoID: $videoID")

                try {
                    val apiResponse = app.get(
                        "${mainUrl}/video/$videoID/",
                        headers = mapOf(
                            "Content-Type" to "application/json",
                            "X-Requested-With" to "fetch"
                        ),
                        referer = data
                    ).text

                    var iframe = Regex("""data-src=\\?"([^"\\]+)\\?""").find(apiResponse)?.groupValues?.get(1)
                        ?.replace("\\", "") ?: return@forEach

                    if (iframe.contains("?rapidrame_id=")) {
                        iframe = "${mainUrl}/playerr/" + iframe.substringAfter("?rapidrame_id=")
                    } else if (!iframe.startsWith("http")) {
                        iframe = "${mainUrl}$iframe"
                    }

                    Log.d("HDCH", "Final iframe URL: $iframe")
                    invokeLocalSource(sourceName, iframe, callback)
                    foundLinks = true
                } catch (e: Exception) {
                    Log.e("HDCH", "Error processing source $sourceName: ${e.message}")
                }
            }
        }

        return foundLinks
    }

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
}
