package com.kreastream

import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
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
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class HDFilmCehennemi : MainAPI() {
    override var mainUrl              = "https://www.hdfilmcehennemi.la"
    override var name                 = "HDFilmCehennemi"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries)

    override var sequentialMainPage = true
    override var sequentialMainPageDelay       = 50L
    override var sequentialMainPageScrollDelay = 50L

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
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    private val standardHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:137.0) Gecko/20100101 Firefox/137.0",
        "Accept" to "*/*",
        "X-Requested-With" to "fetch"
    )

    override val mainPage = mainPageOf(
        "${mainUrl}/load/page/1/home/"                                    to "Yeni Eklenen Filmler",
        "${mainUrl}/load/page/1/categories/nette-ilk-filmler/"            to "Nette İlk Filmler",
        "${mainUrl}/load/page/1/home-series/"                             to "Yeni Eklenen Diziler",
        "${mainUrl}/load/page/1/categories/tavsiye-filmler-izle2/"        to "Tavsiye Filmler",
        "${mainUrl}/load/page/1/imdb7/"                                   to "IMDB 7+ Filmler",
        "${mainUrl}/load/page/1/mostLiked/"                               to "En Çok Beğenilenler",
        "${mainUrl}/load/page/1/genres/aile-filmleri-izleyin-6/"          to "Aile Filmleri",
        "${mainUrl}/load/page/1/genres/aksiyon-filmleri-izleyin-5/"       to "Aksiyon Filmleri",
        "${mainUrl}/load/page/1/genres/animasyon-filmlerini-izleyin-5/"   to "Animasyon Filmleri",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            request.data
                .replace("/load/page/1/genres/","/tur/")
                .replace("/load/page/1/categories/","/category/")
                .replace("/load/page/1/imdb7/","/imdb-7-puan-uzeri-filmler/")
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
            val results = document.select("a").mapNotNull { it.toSearchResult() }
            return newHomePageResponse(request.name, results)
        } catch (e: Exception) {
            return newHomePageResponse(request.name, emptyList())
        }
    }

    private fun Element.extractTitleWithDubInfo(): Pair<String, String?> {
        val title = this.selectFirst("strong.poster-title")?.text() ?: return "" to null
        val dubSub = this.selectFirst(".poster-lang span")?.text()?.trim()
        val hasDub = dubSub?.startsWith("Dublaj", ignoreCase = true) == true || dubSub?.startsWith("Yerli", ignoreCase = true) == true
        val newTitle = if (hasDub) "🇹🇷 $title" else title
        return newTitle to dubSub
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val (newTitle, _) = this.extractTitleWithDubInfo()
        val href = fixUrlNull(this.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img[data-src], img[src]")?.attr("data-src")
            ?: this.selectFirst("img")?.attr("src"))

        // 1. Extract the language text
        val lang = this.selectFirst(".poster-lang span")?.text()?.trim()
        if (lang.isNullOrBlank()) return null

        val yearText = this.selectFirst(".poster-meta span")?.text()?.trim()
        val scoreText = this.selectFirst(".poster-meta .imdb")?.ownText()?.trim()
        val score = scoreText?.toFloatOrNull()
        
        val tvType = if (this.attr("href").contains("/dizi/", ignoreCase = true)
            || this.attr("href").contains("/series", ignoreCase = true)
            || this.attr("href").contains("home-series", ignoreCase = true)
        ) TvType.TvSeries else TvType.Movie

        // 2. Add the language text to the posterHeaders list (FIXED: using Map<String, String>)
        return newMovieSearchResponse(newTitle, href, tvType) {
            this.posterUrl = posterUrl
            this.score = Score.from10(score)

            // Add year if found
            if (!yearText.isNullOrBlank()) {
                this.year = yearText.toIntOrNull()
            }
            
            // Add language tag if found
            if (!lang.isNullOrBlank()) {
                // FIXED: posterHeaders requires a Map<String, String>. Key is text, value can be empty.
                this.posterHeaders = mapOf(
                    lang to ""
                )
            }
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
            val title     = document.selectFirst("h4.title")?.text() ?: return@forEach
            val href      = fixUrlNull(document.selectFirst("a")?.attr("href")) ?: return@forEach
            val posterUrl = fixUrlNull(document.selectFirst("img")?.attr("src")) ?:
            fixUrlNull(document.selectFirst("img")?.attr("data-src"))
            
            // Extract language in search results
            val lang = document.selectFirst(".poster-lang")?.text()?.trim()
            // Extract score in search results
            val scoreText = document.selectFirst(".poster-meta .imdb")?.ownText()?.trim()
            val score = scoreText?.toFloatOrNull()

            searchResults.add(
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = posterUrl?.replace("/thumb/", "/list/")
                    // FIXED: Using extracted score
                    this.score = Score.from10(score) 
                    // FIXED: Using Map<String, String> for posterHeaders and extracted lang
                    if (!lang.isNullOrBlank()) {
                        this.posterHeaders = mapOf(
                            lang to ""
                        )
                    }
                }
            )
        }
        return searchResults
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        // Use a more specific selector for the title if the first one fails
        val title       = document.selectFirst("strong.poster-title")?.text()?.trim()
            ?: document.selectFirst("h1.section-title")?.text()?.substringBefore(" izle") ?: return null

        val poster      = fixUrlNull(document.select("aside.post-info-poster img.lazyload").lastOrNull()?.attr("data-src"))
        val tags        = document.select("div.post-info-genres a").map { it.text() }
        val year        = document.selectFirst("div.post-info-year-country a")?.text()?.trim()?.toIntOrNull()
        val tvType      = if (document.select("div.seasons").isEmpty()) TvType.Movie else TvType.TvSeries
        val description = document.selectFirst("article.post-info-content > p")?.text()?.trim()
        val score      = document.selectFirst("div.post-info-imdb-rating span")?.text()?.substringBefore("(")?.trim()?.toFloatOrNull()
        val actors      = document.select("div.post-info-cast a").map {
            Actor(it.selectFirst("strong")!!.text(), it.select("img").attr("data-src"))
        }

        val recommendations = document.select("div.section-slider-container div.slider-slide").mapNotNull {
            val recName      = it.selectFirst("a")?.attr("title") ?: return@mapNotNull null
            val recHref      = fixUrlNull(it.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
            val recPosterUrl = fixUrlNull(it.selectFirst("img")?.attr("data-src")) ?:
            fixUrlNull(it.selectFirst("img")?.attr("src"))

            newTvSeriesSearchResponse(recName, recHref, TvType.TvSeries) {
                this.posterUrl = recPosterUrl
            }
        }

        return if (tvType == TvType.TvSeries) {
            val trailer  = document.selectFirst("div.post-info-trailer button")?.attr("data-modal")
                ?.substringAfter("trailer/")?.let { "https://www.youtube.com/embed/$it" }
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

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl       = poster
                this.year            = year
                this.plot            = description
                this.tags            = tags
                this.score           = Score.from10(score)
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
            }
        } else {
            val trailer = document.selectFirst("div.post-info-trailer button")?.attr("data-modal")
                ?.substringAfter("trailer/")?.let { "https://www.youtube.com/embed/$it" }

            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl       = poster
                this.year            = year
                this.plot            = description
                this.tags            = tags
                this.score           = Score.from10(score)
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
            }
        }
    }

    // FIXED: Decrypts URL using ByteArray to prevent UTF-8 corruption
    private fun decryptHdfcUrl(encryptedData: String, seed: Int): String {
        try {
            // 1. Base64 Decode -> ByteArray (Crucial: Keep as bytes!)
            val bytes = Base64.decode(encryptedData, Base64.DEFAULT)

            // 2. Apply ROT13 to ASCII letters only (in-place modification of bytes)
            for (i in bytes.indices) {
                val b = bytes[i].toInt()
                if ((b in 65..90) || (b in 97..122)) { // A-Z or a-z
                    val isUpper = b <= 90
                    val base = if (isUpper) 65 else 97
                    // ROT13 logic
                    val shifted = ((b - base + 13) % 26) + base
                    bytes[i] = shifted.toByte()
                }
            }

            // 3. Reverse the byte array
            bytes.reverse()

            // 4. Custom Byte Shift Loop
            val sb = StringBuilder()
            for (i in bytes.indices) {
                val charCode = bytes[i].toInt() and 0xFF // Convert to unsigned int 0-255
                val shift = seed % (i + 5)
                val newChar = (charCode - shift + 256) % 256
                sb.append(newChar.toChar())
            }

            return sb.toString()
        } catch (e: Exception) {
            Log.e("HDFC", "Decryption failed", e)
            return ""
        }
    }

    private suspend fun invokeLocalSource(
        source: String,
        url: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val response = app.get(url, referer = "$mainUrl/")
            val script = response.document.select("script").find { 
                it.data().contains("eval(function(p,a,c,k,e,d)") 
            }?.data() ?: return

            // 1. Unpack the javascript
            val unpacked = JsUnpacker(script).unpack() ?: return
            
            // 2. Extract the encrypted array string
            val arrayRegex = Regex("""\w+\(\[(.*?)\]\)""")
            val arrayMatch = arrayRegex.find(unpacked)?.groupValues?.get(1) ?: return
            val encryptedString = arrayMatch.replace("\"", "").replace(",", "")

            // 3. Extract the math seed
            val seedRegex = Regex("""charCode-\((\d+)%\(i\+5\)\)""")
            val seed = seedRegex.find(unpacked)?.groupValues?.get(1)?.toIntOrNull() ?: 399756995

            // 4. Decrypt
            val decryptedUrl = decryptHdfcUrl(encryptedString, seed)
            
            if (decryptedUrl.isEmpty()) return
            Log.d("HDFC", "Decrypted URL: $decryptedUrl")

            // 5. Determine if it's HLS
            val isHls = decryptedUrl.contains(".m3u8") || decryptedUrl.endsWith(".txt")
            
            callback.invoke(
                newExtractorLink(
                    source  = source,
                    name    = source,
                    url     = decryptedUrl
                ){
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                    this.type    = if(isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                }
            )
        } catch (e: Exception) {
            Log.e("HDFC", "Error extracting local source", e)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        
        // 1. Check for iframe/rapidframe directly
        val iframealak = fixUrlNull(
            document.selectFirst(".close")?.attr("data-src")
                ?: document.selectFirst(".rapidrame")?.attr("data-src")
        ).toString()

        if (iframealak.contains("hdfilmcehennemi.mobi")) {
            // Process subtitles for mobi iframe
            try {
                val iframedoc = app.get(iframealak, referer = mainUrl).document
                val baseUri = iframedoc.location().substringBefore("/", "https://www.hdfilmcehennemi.mobi")
                iframedoc.select("track[kind=captions]").forEach { track ->
                    val lang = when (track.attr("srclang")) {
                        "tr" -> "Türkçe"
                        "en" -> "İngilizce"
                        else -> track.attr("srclang")
                    }
                    val subUrl = track.attr("src").let { if (it.startsWith("http")) it else "$baseUri/$it".replace("//", "/") }
                    subtitleCallback(SubtitleFile(lang, subUrl))
                }
            } catch (e: Exception) { Log.e("HDFC", "Sub extraction error", e) }
        }

        // 2. Check alternative links (buttons below player)
        document.select("div.alternative-links").forEach { element ->
            val langCode = element.attr("data-lang").uppercase()
            element.select("button.alternative-link").forEach { button ->
                val sourceName = button.text().replace("(HDrip Xbet)", "").trim() + " $langCode"
                val videoID = button.attr("data-video")
                
                val apiGet = app.get(
                    "${mainUrl}/video/$videoID/",
                    headers = mapOf("Content-Type" to "application/json", "X-Requested-With" to "fetch"),
                    referer = data
                ).text

                var iframe = Regex("""data-src=\\"([^"]+)""").find(apiGet)?.groupValues?.get(1)?.replace("\\", "") ?: ""
                
                if (iframe.contains("?rapidrame_id=")) {
                    iframe = "${mainUrl}/playerr/" + iframe.substringAfter("?rapidrame_id=")
                }

                if (iframe.isNotEmpty()) {
                    invokeLocalSource(sourceName, iframe, callback)
                }
            }
        }
        return true
    }

    data class Results(@JsonProperty("results") val results: List<String> = arrayListOf())
    data class HDFC(@JsonProperty("html") val html: String)
}
