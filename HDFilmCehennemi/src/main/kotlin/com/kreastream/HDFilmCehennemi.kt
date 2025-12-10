package com.kreastream

import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.FormBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlin.text.Charsets

class HDFilmCehennemi : MainAPI() {
    override var mainUrl              = "https://www.hdfilmcehennemi.ws"
    override var name                 = "HDFilmCehennemi"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override var hasDownloadSupport   = true 
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries)


    override var sequentialMainPage             = true
    override var sequentialMainPageDelay        = 50L
    override var sequentialMainPageScrollDelay  = 50L

    private val cloudflareKiller by lazy { CloudflareKiller() }
    
    class CloudflareKiller { 
        fun intercept(chain: Interceptor.Chain): Response = chain.proceed(chain.request()) 
    }
    
    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller): Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request  = chain.request()
            val response = chain.proceed(request)
            return response
        }
    }

    private val objectMapper = ObjectMapper().apply {
        registerModule(KotlinModule.Builder().build())
        configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    private val standardHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:137.0) Gecko/20100101 Firefox/137.0",
        "Accept" to "*/*",
        "X-Requested-With" to "fetch"
    )

    private data class PosterData(
        val title: String,
        val newTitle: String,
        val href: String,
        val posterUrl: String?,
        val lang: String?,
        val year: Int?,
        val score: Float?,
        val tvType: TvType,
        val hasDub: Boolean,
        val hasSub: Boolean 
    )

    private data class LoadData(
        val title: String,
        val newTitle: String,
        val poster: String?,
        val tags: List<String>,
        val year: Int?,
        val tvType: TvType,
        val description: String?,
        val score: Float?,
        val actors: List<Actor>,
        val trailer: String?
    )

    private fun Document.extractLoadData(): LoadData? {
        val title = this.selectFirst("h1.section-title")?.text()?.substringBefore(" izle") ?: return null
        val poster = fixUrlNull(this.select("aside.post-info-poster img.lazyload").lastOrNull()?.attr("data-src"))
        val tags = this.select("div.post-info-genres a").map { it.text() }
        val year = this.selectFirst("div.post-info-year-country a")?.text()?.trim()?.toIntOrNull()
        val tvType = if (this.select("div.seasons").isEmpty()) TvType.Movie else TvType.TvSeries
        val description = this.selectFirst("article.post-info-content > p")?.text()?.trim()
        val score = this.selectFirst("div.post-info-imdb-rating span")?.text()?.substringBefore("(")?.trim()?.toFloatOrNull()
        val lang = this.selectFirst(".language-link")?.text()?.trim()
        val hasDub = lang?.contains("Dublaj", ignoreCase = true) == true
        val newTitle = if (hasDub) "🇹🇷 ${title}" else title

        val actors = this.select("div.post-info-cast a").map {
            Actor(it.selectFirst("strong")?.text() ?: it.text(), fixUrlNull(it.selectFirst("img")?.attr("data-src")))
        }

        val trailer = this.selectFirst("div.post-info-trailer button")?.attr("data-modal")
            ?.substringAfter("trailer/")?.let { "https://www.youtube.com/embed/$it" }

        return LoadData(title, newTitle, poster, tags, year, tvType, description, score, actors, trailer)
    }

    private fun Element.extractPosterData(): PosterData? {
        val title = this.attr("title")
            .takeIf { it.isNotEmpty() }?.trim()
            ?: this.selectFirst("strong.poster-title")?.text()?.trim()
            ?: this.selectFirst("h4.title")?.text()?.trim()
            ?: return null

        val href = fixUrlNull(this.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img[data-src], img[src]")?.attr("data-src")
            ?: this.selectFirst("img")?.attr("src"))
            // FIX: Apply image path correction globally for standard posters
            ?.replace("/list/", "/")
            ?.replace("/thumb/", "/")

        val year = this.selectFirst(".poster-meta span")?.text()?.trim()?.toIntOrNull()
        val score = this.selectFirst(".poster-meta .imdb")?.ownText()?.trim()?.toFloatOrNull()
        
        // Use .poster-lang or .poster-meta for language info
        val lang = this.selectFirst(".poster-lang span, .poster-meta-genre span")?.text()?.trim()
        
        // Dubbed status: checks for "Dublaj" or "Yerli"
        val hasDub = lang?.contains("Dublaj", ignoreCase = true) == true || lang?.contains("Yerli", ignoreCase = true) == true
        
        // Subtitle status: checks for "Altyazılı"
        val hasSub = lang?.contains("Altyazılı", ignoreCase = true) == true
        
        val newTitle = if (hasDub) "🇹🇷 ${title}" else title

        val typeCheck = this.attr("href").contains("/dizi/", ignoreCase = true) || this.attr("href").contains("/series", ignoreCase = true)
        val tvType = if (typeCheck) TvType.TvSeries else TvType.Movie

        return PosterData(title, newTitle, href, posterUrl, lang, year, score, tvType, hasDub, hasSub)
    }

    override val mainPage = mainPageOf(
        "${mainUrl}/load/page/1/home/"                                      to "Yeni Filmler",
        "${mainUrl}/load/page/1/languages/turkce-dublajli-film-izleyin-3/"   to "Türkçe Dublaj Filmler",
        "${mainUrl}/load/page/1/countries/turkiye-2/"                        to "Türk Filmleri",
        "${mainUrl}/load/page/1/recent-episodes/"                            to "Yeni Bölümler",
        "${mainUrl}/load/page/1/home-series/"                                to "Yeni Diziler",
        "${mainUrl}/load/page/1/categories/tavsiye-filmler-izle2/"           to "Tavsiye Filmler",
        "${mainUrl}/load/page/1/genres/aksiyon-filmleri-izleyin-5/"          to "Aksiyon Filmleri",
        "${mainUrl}/load/page/1/genres/animasyon-filmlerini-izleyin-5/"      to "Animasyon Filmleri",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            request.data
                .replace("/load/page/1/genres/","/tur/")
                .replace("/load/page/1/categories/","/category/")
                .replace("/load/page/1/imdb7/","/imdb-7-puan-uzeri-filmler/")
                .replace("/load/page/1/languages/","/dil/")
                .replace("/load/page/1/countries/","/ulke/")
        } else {
            request.data.replace("/page/1/", "/page/${page}/")
        }

        val response = app.get(url, headers = standardHeaders, referer = mainUrl)

        if (response.text.contains("Sayfa Bulunamadı")) {
            return newHomePageResponse(request.name, emptyList())
        }

        try {
            val hdfc: HDFC = objectMapper.readValue(response.text, HDFC::class.java)
            val document = Jsoup.parse(hdfc.html)
            // Select all relevant link elements
            val results = document.select("a.poster, a.mini-poster").mapNotNull { it.toSearchResult() }
            return newHomePageResponse(request.name, results)
        } catch (e: Exception) {
            return newHomePageResponse(request.name, emptyList())
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        if (this.hasClass("mini-poster")) {
            val seriesTitle = this.selectFirst(".mini-poster-title")?.text()?.trim() ?: return null

            val href = fixUrlNull(this.attr("href")) ?: return null
            val episodeInfo = this.selectFirst(".mini-poster-episode-info")?.text()?.trim() ?: ""
            val posterUrl = fixUrlNull(this.selectFirst("img[data-src], img[src]")?.attr("data-src")
                ?: this.selectFirst("img")?.attr("src"))
                ?.replace("/list/", "/") 
                ?.replace("/thumb/", "/")

            // Format title to show episode info for easier identification
            val newName = "$seriesTitle - $episodeInfo"
            
            return newTvSeriesSearchResponse(newName, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        }
        
        // Handle standard posters for "Yeni Eklenen Diziler" and movies
        val data = this.extractPosterData() ?: return null
        
        return newMovieSearchResponse(data.newTitle, data.href, data.tvType) {
            this.posterUrl = data.posterUrl
            this.score = Score.from10(data.score)
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

            val data = document.selectFirst("a")?.extractPosterData() ?: return@forEach
            
            searchResults.add(
                newMovieSearchResponse(data.newTitle, data.href, data.tvType) {
                    this.posterUrl = data.posterUrl
                        ?.replace("/list/", "/")
                        ?.replace("/thumb/", "/")
                    this.score = Score.from10(data.score)
                }
            )
        }
        return searchResults
    }
    
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val data = document.extractLoadData() ?: return null

        val recommendations = document.select("div.section-slider-container div.slider-slide").mapNotNull {
            val recName      = it.selectFirst("a")?.attr("title") ?: return@mapNotNull null
            val recHref      = fixUrlNull(it.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
            val recPosterUrl = fixUrlNull(it.selectFirst("img")?.attr("data-src")) ?:
            fixUrlNull(it.selectFirst("img")?.attr("src"))

            newTvSeriesSearchResponse(recName, recHref, data.tvType) { 
                this.posterUrl = recPosterUrl
            }
        }

        return if (data.tvType == TvType.TvSeries) {
            val episodes = document.select("div.seasons-tab-content a").mapNotNull {
                val epName    = it.selectFirst("h4")?.text()?.trim() ?: return@mapNotNull null
                val epHref    = fixUrlNull(it.attr("href")) ?: return@mapNotNull null
                val epEpisode = Regex("""(\d+)\. ?Bölüm""").find(epName)?.groupValues?.get(1)?.toIntOrNull()
                val epSeason  = Regex("""(\d+)\. ?Sezon""").find(epName)?.groupValues?.get(1)?.toIntOrNull() ?: 1

                newEpisode(epHref) {
                    this.name = epName
                    this.season = epSeason
                    this.episode = epEpisode
                }
            }

            newTvSeriesLoadResponse(data.newTitle, url, data.tvType, episodes) {
                this.posterUrl       = data.poster
                this.year            = data.year
                this.plot            = data.description
                this.tags            = data.tags
                this.score           = Score.from10(data.score)
                this.recommendations = recommendations
                addActors(data.actors)
                addTrailer(data.trailer)
            }
        } else {
            newMovieLoadResponse(data.newTitle, url, data.tvType, url) {
                this.posterUrl       = data.poster
                this.year            = data.year
                this.plot            = data.description
                this.tags            = data.tags
                this.score           = Score.from10(data.score)
                this.recommendations = recommendations
                addActors(data.actors)
                addTrailer(data.trailer)
            }
        }
    }
    
    private object VideoJsDecrypter {

        fun decrypt(encryptedFragments: List<String>): String {
            if (encryptedFragments.isEmpty()) return ""

            // Step 1: Join all fragments
            val joined = encryptedFragments.joinToString("")

            // Step 2: Reverse the string
            val reversed = joined.reversed()

            // Step 3: Base64 decode TWICE
            val once = try {
                Base64.decode(reversed, Base64.NO_PADDING)
            } catch (e: Exception) { return "" }

            val twice = try {
                Base64.decode(once, Base64.NO_PADDING)
            } catch (e: Exception) { return "" }

            // Step 4: Custom per-character shift (the "unmix" loop)
            val sb = StringBuilder()
            for (i in twice.indices) {
                val cc = twice[i].toInt() and 0xFF
                val shift = 256 % (i + 5)
                val finalChar = (cc - shift + 256) % 256
                sb.append(finalChar.toChar())
            }
            return sb.toString().trim()
        }

        // Fallback: try single base64 + reverse + shift (some older variants)
        fun decryptFallback(joined: String): String {
            return try {
                val reversed = joined.reversed()
                val decoded = Base64.decode(reversed, Base64.NO_PADDING)
                val sb = StringBuilder()
                for (i in decoded.indices) {
                    val cc = decoded[i].toInt() and 0xFF
                    val shift = 256 % (i + 5)
                    sb.append((cc - shift + 256) % 256.toChar())
                }
                sb.toString()
            } catch (e: Exception) { "" }
        }
    }

    private suspend fun extractFromPlayerScript(
        script: String,
        sourceName: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // 1. Unpack base62 eval()
            val unpacked = JsUnpacker(script).unpack() ?: return

            // 2. Find encrypted array: decrypt(["abc","def",...])
            val arrayRegex = Regex("""decrypt\(\s*\[\s*(".*?")\s*(?:,\s*".*?")*\s*\]\s*\)""")
            val match = arrayRegex.find(unpacked) ?: return
            val rawArray = match.value

            // Extract strings inside ["...","...",...]
            val strings = Regex("""["']([^"']+)["']""").findAll(rawArray).map { it.groupValues[1] }.toList()
            if (strings.isEmpty()) return

            // 3. Decrypt using new 2025 method
            var url = VideoJsDecrypter.decrypt(strings)
            if (url.isBlank() || !url.contains("http")) {
                url = VideoJsDecrypter.decryptFallback(strings.joinToString(""))
            }
            if (url.isBlank() || !url.contains("http")) return

            if (seenUrls.contains(url)) return
            seenUrls.add(url)

            val isHls = url.contains(".m3u8") || url.contains(".txt")

            callback(
                newExtractorLink(
                    source = sourceName,
                    name = sourceName,
                    url = url
                ){
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                    this.type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                }
            )
        } catch (e: Exception) {
            Log.e("HDFC", "Decryption failed for $sourceName", e)
        }
    }

    private val seenUrls = mutableSetOf<String>()

    private suspend fun invokeLocalSource(
        source: String,
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val response = app.get(url, referer = "$mainUrl/")
            val script = response.document.select("script").find { 
                it.data().contains("eval(function(p,a,c,k,e,d)") 
            }?.data() ?: return

            // 1. Unpack the javascript
            val unpacked = JsUnpacker(script).unpack() ?: return
            
            // --- NEW: Extract Rapidrame ID for Download ---
            // Look for image: "https://.../aaktqas1ejb1.jpg"
            //val imageRegex = Regex("""image:\s*["'](.*?)["']""")
            //val imageUrl = imageRegex.find(unpacked)?.groupValues?.get(1)
            
            val callRegex = Regex("""\w+\(\[(.*?)\]\)""")
            val arrayContent = callRegex.find(unpacked)?.groupValues?.get(1) ?: return
            
            // Clean it up to get the single Base64 string
            val encryptedString = arrayContent.replace("\"", "").replace("'", "").replace(",", "").replace("\\s".toRegex(), "")

            // 3. Extract the math seed
            val seedRegex = Regex("""charCode-\((\d+)%\(i\+5\)\)""")
            val seed = seedRegex.find(unpacked)?.groupValues?.get(1)?.toIntOrNull() ?: 399756995

            // 4. Decrypt dynamically
            val decryptedUrl = HDFCDecrypter.dynamicDecrypt(encryptedString, seed)
            
            if (decryptedUrl.isEmpty()) return

            if (seenUrls.contains(decryptedUrl)) return
            seenUrls.add(decryptedUrl)

            val potentialRapidrameId = Regex("""/play/([^/]+)""").find(decryptedUrl)?.groupValues?.get(1)
            ?.takeIf { it.length > 5 }

            // 5. Determine if it's HLS 
            val isHls = decryptedUrl.contains(".m3u8") || decryptedUrl.endsWith(".txt")
            
            callback.invoke(
                newExtractorLink(
                    source  = source,
                    name    = source,
                    url     = decryptedUrl
                ){
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                    this.type    = if(isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                }
            )
        } catch (e: Exception) {
            Log.e("HDFC", "Error extracting local source", e)
        }
    }

    private suspend fun extractDownloadLinks(rapidrameId: String, callback: (ExtractorLink) -> Unit) {
        val downloadUrl = "https://cehennempass.pw/download/$rapidrameId" // Updated domain based on snippet
        
        val qualities = mapOf(
            "low" to "Download SD", 
            "high" to "Download HD"   
        )

        qualities.forEach { (qualityData, qualityName) ->
            // The process URL might also need updating or staying as cehennempass.pw
            // Trying the new domain for processing as well based on common patterns
            val postUrl = "https://cehennempass.pw/process_quality_selection.php" 
            
            val postBody = okhttp3.FormBody.Builder()
                .add("video_id", rapidrameId)
                .add("selected_quality", qualityData)
                .build()
            
            try {
                val response = app.post(
                    postUrl,
                    requestBody = postBody,
                    headers = standardHeaders,
                    referer = downloadUrl 
                ).parsedSafe<DownloadResponse>()

                val finalLink = response?.download_link

                if (!finalLink.isNullOrEmpty()) {
                    callback.invoke(
                        newExtractorLink(
                            source = name, 
                            name = qualityName,
                            url = finalLink
                        ) {
                            this.quality = Qualities.Unknown.value
                            this.type = ExtractorLinkType.VIDEO
                        }
                    )
                }
            } catch (e: Exception) {
                // Fallback to old domain if new one fails
                 Log.e("HDFC", "Download extraction failed", e)
            }
        }
    }

      override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        seenUrls.clear()
        val doc = app.get(data, referer = mainUrl).document

        // 1. Alternative sources (Rapidrame, etc.)
        doc.select("div.alternative-links button.alternative-link").forEach { btn ->
            val sourceName = btn.text().trim().let {
                if (it.contains("rapidrame", true)) "Rapidrame" else it
            }
            val iframeSrc = btn.attr("data-video").let { "${mainUrl}/video/$it/" }
                ?: return@forEach

            val iframeResp = app.get(iframeSrc, referer = data)
            val iframeUrl = iframeResp.document.selectFirst("iframe")?.attr("src")
                ?: Regex("""data-src=["']([^"']+)""").find(iframeResp.text)?.groupValues?.get(1)
                ?: return@forEach

            val playerPage = app.get(fixUrl(iframeUrl), referer = data).text
            val playerScript = Jsoup.parse(playerPage).select("script")
                .find { it.data().contains("eval(function(p,a,c,k,e,d)") }?.data()
                ?: return@forEach

            extractFromPlayerScript(playerScript, sourceName, iframeUrl, callback)
        }

        // 2. Default "Close" source
        doc.selectFirst(".close")?.attr("data-src")?.let { closeSrc ->
            val fullUrl = fixUrl(closeSrc)
            val closePage = app.get(fullUrl, referer = data).text
            val closeScript = Jsoup.parse(closePage).select("script")
                .find { it.data().contains("eval(function(p,a,c,k,e,d)") }?.data()

            if (closeScript != null) {
                extractFromPlayerScript(closeScript, "Close", fullUrl, callback)
            }

            // Extract subtitles from Close player
            try {
                Jsoup.parse(closePage).select("track[kind=captions]").forEach { track ->
                    val lang = when (track.attr("srclang")) {
                        "tr" -> "Türkçe"
                        "en" -> "English"
                        else -> track.attr("label")
                    }
                    var subUrl = track.attr("src")
                    if (!subUrl.startsWith("http")) {
                        subUrl = fullUrl.substringBeforeLast("/") + "/" + subUrl
                    }
                    subtitleCallback(SubtitleFile(lang, subUrl))
                }
            } catch (e: Exception) { }
        }

        // 3. Download links (via cehennempass.pw)
        val rapidrameId = doc.selectFirst("button[data-video]")?.attr("data-video")
            ?: doc.selectFirst(".close")?.attr("data-src")?.substringAfter("?rapidrame_id=")

        rapidrameId?.takeIf { it.length > 10 }?.let { id ->
            try {
                val dlPage = app.get("https://cehennempass.pw/download/$id", referer = data)
                Jsoup.parse(dlPage.text).select("a.download-btn").forEach { a ->
                    val name = a.text().trim()
                    val link = a.attr("href")
                    if (link.contains("http")) {
                        callback(
                            ExtractorLink(
                                source = "Download",
                                name = name,
                                url = link,
                                referer = "https://cehennempass.pw/",
                                quality = Qualities.Unknown.value,
                                type = ExtractorLinkType.VIDEO
                            )
                        )
                    }
                }
            } catch (e: Exception) { }
        }

        return seenUrls.isNotEmpty()
    }

    data class Results(@JsonProperty("results") val results: List<String> = arrayListOf())
    data class HDFC(@JsonProperty("html") val html: String)
    data class DownloadResponse(
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("download_link") val download_link: String? = null,
        @JsonProperty("message") val message: String? = null
    )
}