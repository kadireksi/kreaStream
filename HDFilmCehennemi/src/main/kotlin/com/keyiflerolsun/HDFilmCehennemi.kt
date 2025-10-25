package com.keyiflerolsun

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
import org.jsoup.nodes.Document
import java.net.URI

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
    private val interceptor      by lazy { CloudflareInterceptor(cloudflareKiller) }

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller): Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request  = chain.request()
            val response = chain.proceed(request)
            val doc      = Jsoup.parse(response.peekBody(1024 * 1024).string())
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
        "${mainUrl}/load/page/1/home/"                        to "Yeni Eklenen Filmler",
        "${mainUrl}/load/page/1/categories/nette-ilk-filmler/"            to "Nette İlk Filmler",
        "${mainUrl}/load/page/1/home-series/"                             to "Yeni Eklenen Diziler",
        "${mainUrl}/load/page/1/categories/tavsiye-filmler-izle2/"        to "Tavsiye Filmler",
        "${mainUrl}/load/page/1/imdb7/"                                   to "IMDB 7+ Filmler",
        "${mainUrl}/load/page/1/mostLiked/"                               to "En Çok Beğenilenler",
        "${mainUrl}/load/page/1/genres/aile-filmleri-izleyin-6/"          to "Aile Filmleri",
        "${mainUrl}/load/page/1/genres/aksiyon-filmleri-izleyin-5/"       to "Aksiyon Filmleri",
        "${mainUrl}/load/page/1/genres/animasyon-filmlerini-izleyin-5/"   to "Animasyon Filmleri",
        "${mainUrl}/load/page/1/genres/belgesel-filmlerini-izle-1/"       to "Belgesel Filmleri",
        "${mainUrl}/load/page/1/genres/bilim-kurgu-filmlerini-izleyin-3/" to "Bilim Kurgu Filmleri",
        "${mainUrl}/load/page/1/genres/komedi-filmlerini-izleyin-1/"      to "Komedi Filmleri",
        "${mainUrl}/load/page/1/genres/korku-filmlerini-izle-4/"          to "Korku Filmleri",
        "${mainUrl}/load/page/1/genres/romantik-filmleri-izle-2/"         to "Romantik Filmleri"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            request.data
                .replace("/load/page/1/genres/","/tur/")
                .replace("/load/page/1/categories/","/category/")
                .replace("/load/page/1/imdb7/","/imdb-7-puan-uzeri-filmler/")
        } else {
            request.data
                .replace("/page/1/", "/page/${page}/")
        }

        val response = app.get(url, headers = standardHeaders, referer = mainUrl)

        if (response.text.contains("Sayfa Bulunamadı")) {
            Log.d("HDCH", "Sayfa bulunamadı: $url")
            return newHomePageResponse(request.name, emptyList())
        }

        return try {
            val hdfc: HDFC = objectMapper.readValue(response.text)
            val document = Jsoup.parse(hdfc.html)
            val results = document.select("a").mapNotNull { it.toSearchResult() }
            newHomePageResponse(request.name, results)
        } catch (e: Exception) {
            Log.e("HDCH", "JSON parse hatası (${request.name}): ${e.message}")
            newHomePageResponse(request.name, emptyList())
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.attr("title")
            .takeIf { it.isNotEmpty() }
            .takeUnless {
                it.contains("Seri Filmler", ignoreCase = true)
                        || it.contains("Japonya Filmleri", ignoreCase = true)
                        || it.contains("Kore Filmleri", ignoreCase = true)
                        || it.contains("Hint Filmleri", ignoreCase = true)
                        || it.contains("Türk Filmleri", ignoreCase = true)
                        || it.contains("DC Yapımları", ignoreCase = true)
                        || it.contains("Marvel Yapımları", ignoreCase = true)
                        || it.contains("Amazon Yapımları", ignoreCase = true)
                        || it.contains("1080p Film izle", ignoreCase = true)
            } ?: return null

        val href      = fixUrlNull(this.attr("href")) ?: return null
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
            val title     = document.selectFirst("h4.title")?.text() ?: return@forEach
            val href      = fixUrlNull(document.selectFirst("a")?.attr("href")) ?: return@forEach
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

        val title       = document.selectFirst("h1.section-title")?.text()?.substringBefore(" izle") ?: return null
        val poster      = fixUrlNull(document.select("aside.post-info-poster img.lazyload").lastOrNull()?.attr("data-src"))
        val tags        = document.select("div.post-info-genres a").map { it.text() }
        val year        = document.selectFirst("div.post-info-year-country a")?.text()?.trim()?.toIntOrNull()
        val tvType      = if (document.select("div.seasons").isEmpty()) TvType.Movie else TvType.TvSeries
        val description = document.selectFirst("article.post-info-content > p")?.text()?.trim()
        val score       = document.selectFirst("div.post-info-imdb-rating span")?.text()?.substringBefore("(")?.trim()?.toFloatOrNull()
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
                this.score = Score.from10(score)
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        val iframealak = fixUrlNull(
            document.selectFirst(".close")?.attr("data-src")
                ?: document.selectFirst(".rapidrame")?.attr("data-src")
        )?.takeIf { it.isNotBlank() }

        if (iframealak == null) {
            Log.e("HDCH", "iframe not found on $data")
            // still try alternative-links flow below
        } else {
            // If iframe is directly present on page (rare), try extracting sources from it
            try {
                extractFromIframeUrl(iframealak, data, subtitleCallback, callback)
            } catch (e: Exception) {
                Log.d("HDCH", "Direct iframe extraction failed: ${e.message}")
            }
        }

        // Process alternative-links section: video buttons that return JSON containing iframe HTML
        document.select("div.alternative-links").forEach { element ->
            val langCode = element.attr("data-lang").uppercase()
            element.select("button.alternative-link").forEach { button ->
                val sourceLabel = button.text().replace("(HDrip Xbet)", "").trim() + " $langCode"
                val videoID = button.attr("data-video").takeIf { it.isNotBlank() } ?: run {
                    Log.e("HDCH", "Missing videoID for $sourceLabel on $data")
                    return@forEach
                }

                try {
                    val apiResponse = app.get(
                        "$mainUrl/video/$videoID/",
                        headers = mapOf(
                            "Content-Type" to "application/json",
                            "X-Requested-With" to "fetch"
                        ),
                        referer = data
                    ).parsedSafe<VideoIframe>()

                    if (apiResponse == null || apiResponse.data.html.isBlank()) {
                        Log.e("HDCH", "Failed to parse iframe JSON for videoID $videoID")
                        return@forEach
                    }

                    val iframeDoc = Jsoup.parse(apiResponse.data.html)
                    val iframeSrc = iframeDoc.selectFirst("iframe")?.attr("data-src")?.takeIf { it.isNotBlank() }

                    if (iframeSrc == null) {
                        Log.e("HDCH", "iframe src not found in JSON html for videoID $videoID")
                        return@forEach
                    }

                    // Normalize iframe URL
                    val iframeUrl = fixUrlNull(resolveUrl(iframeSrc, mainUrl)) ?: run {
                        Log.e("HDCH", "iframe src invalid for videoID $videoID")
                        return@forEach
                    }

                    Log.d("HDCH", "$sourceLabel » $videoID » $iframeUrl")

                    extractFromIframeUrl(iframeUrl, data, subtitleCallback, callback)
                } catch (e: Exception) {
                    Log.e("HDCH", "Error processing videoID $videoID: ${e.message}")
                }
            }
        }

        return true
    }

    private suspend fun extractFromIframeUrl(
        iframeUrl: String,
        refererForIframe: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val iframeDocResponse = app.get(iframeUrl, referer = refererForIframe)
        val iframeDoc = iframeDocResponse.document

        // Subtitle extraction if present
        try {
            val baseUri = iframeDoc.location().substringBefore("/", iframeDoc.location())
            iframeDoc.select("track[kind=captions]").filter { it.attr("srclang") != "forced" }.forEach { track ->
                val lang = when (track.attr("srclang")) {
                    "tr" -> "Türkçe"
                    "en" -> "İngilizce"
                    else -> track.attr("srclang")
                }
                val src = track.attr("src")
                val subUrl = if (src.startsWith("http")) src else "${baseUri.trimEnd('/')}/${src.trimStart('/')}"
                subtitleCallback(SubtitleFile(lang, subUrl))
            }
        } catch (e: Exception) {
            Log.d("HDCH", "Subtitle extraction failed: ${e.message}")
        }

        // Try multiple strategies to locate the real video file:
        // 1) Inline scripts in iframe page
        val scriptsText = iframeDoc.select("script").mapNotNull { it.data() }.joinToString("\n")
        // Try to find file_link base64 or similar tokens
        val fileLinkEncoded = Regex("""file_link\s*=\s*"([^"]+)"""").find(scriptsText)?.groupValues?.getOrNull(1)
            ?: Regex("""file_link\s*:\s*"([^"]+)"""").find(scriptsText)?.groupValues?.getOrNull(1)
            ?: Regex("""["'](?:file|file_link)["']\s*[:=]\s*["']([^"']+)["']""").find(scriptsText)?.groupValues?.getOrNull(1)

        if (!fileLinkEncoded.isNullOrBlank()) {
            val decoded = runCatching { base64Decode(fileLinkEncoded) }.getOrNull()
            if (!decoded.isNullOrBlank()) {
                Log.d("HDCH", "Found decoded video from file_link: $decoded")
                callback.invoke(
                    newExtractorLink("HDFC", "HDFC", decoded, INFER_TYPE) {
                        this.referer = mainUrl
                        this.headers = mapOf("User-Agent" to standardHeaders["User-Agent"]!!)
                        this.quality = Qualities.Unknown.value
                    }
                )
                return
            }
        }

        // 2) Look for "sources" arrays or file fields in script text (common with JW/Flowplayer)
        val sourcesMatch = Regex("""sources\s*:\s*

\[([^\]

]+)\]

""", RegexOption.IGNORE_CASE).find(scriptsText)
        if (sourcesMatch != null) {
            val inner = sourcesMatch.groupValues[1]
            val fileMatch = Regex("""file\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(inner)
            val fileUrl = fileMatch?.groupValues?.getOrNull(1)
            if (!fileUrl.isNullOrBlank()) {
                val finalFile = resolveUrl(fileUrl, iframeDoc.location())
                Log.d("HDCH", "Found source file in sources array: $finalFile")
                callback.invoke(
                    newExtractorLink("HDFC", "HDFC", finalFile, INFER_TYPE) {
                        this.referer = mainUrl
                        this.headers = mapOf("User-Agent" to standardHeaders["User-Agent"]!!)
                        this.quality = Qualities.Unknown.value
                    }
                )
                return
            }
        }

        // 3) If page loads external movie.js or other remote JS, fetch and inspect it
        val scriptSrcs = iframeDoc.select("script[src]").mapNotNull { it.attr("src") }.distinct()
        for (src in scriptSrcs) {
            try {
                val jsUrl = resolveUrl(src, iframeDoc.location())
                val jsText = app.get(jsUrl, referer = iframeDoc.location()).text
                val decodedFromJs = extractVideoFromJs(jsText)
                if (!decodedFromJs.isNullOrBlank()) {
                    Log.d("HDCH", "Found video in external JS $jsUrl: $decodedFromJs")
                    callback.invoke(
                        newExtractorLink("HDFC", "HDFC", decodedFromJs, INFER_TYPE) {
                            this.referer = mainUrl
                            this.headers = mapOf("User-Agent" to standardHeaders["User-Agent"]!!)
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    return
                }
            } catch (e: Exception) {
                Log.d("HDCH", "External JS fetch failed for $src: ${e.message}")
            }
        }

        // 4) Try unpacking obfuscated inline scripts (eval-packed)
        val unpacked = runCatching { getAndUnpack(scriptsText) }.getOrNull()
        if (!unpacked.isNullOrBlank()) {
            val decodedFromUnpacked = extractVideoFromJs(unpacked)
            if (!decodedFromUnpacked.isNullOrBlank()) {
                Log.d("HDCH", "Found video in unpacked script: $decodedFromUnpacked")
                callback.invoke(
                    newExtractorLink("HDFC", "HDFC", decodedFromUnpacked, INFER_TYPE) {
                        this.referer = mainUrl
                        this.headers = mapOf("User-Agent" to standardHeaders["User-Agent"]!!)
                        this.quality = Qualities.Unknown.value
                    }
                )
                return
            }
        }

        Log.e("HDCH", "No video found in iframe page: $iframeUrl")
    }

    private fun extractVideoFromJs(jsText: String): String? {
        // Common patterns: base64 file_link, sources: [{file:"..."}], direct http(s) links
        val b64 = Regex("""file_link\s*=\s*["']([A-Za-z0-9+/=]+)["']""").find(jsText)?.groupValues?.getOrNull(1)
        if (!b64.isNullOrBlank()) {
            return runCatching { base64Decode(b64) }.getOrNull()
        }

        val srcFile = Regex("""file\s*[:=]\s*["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE).find(jsText)?.groupValues?.getOrNull(1)
        if (!srcFile.isNullOrBlank()) return srcFile

        // Sometimes file is encoded as atob("...") or as btoa result
        val atobMatch = Regex("""atob\(\s*["']([A-Za-z0-9+/=]+)["']\s*\)""").find(jsText)?.groupValues?.getOrNull(1)
        if (!atobMatch.isNullOrBlank()) {
            return runCatching { base64Decode(atobMatch) }.getOrNull()
        }

        // Try to find direct mp4/m3u8 links
        val direct = Regex("""https?://[^\s"']+\.(m3u8|mp4|mpd)[^\s"']*""", RegexOption.IGNORE_CASE).find(jsText)?.value
        if (!direct.isNullOrBlank()) return direct

        return null
    }

    private fun resolveUrl(url: String, base: String): String {
        return try {
            val baseUri = URI(base)
            baseUri.resolve(url).toString()
        } catch (e: Exception) {
            // fallback: try to normalize simple cases
            if (url.startsWith("//")) "https:$url" else if (url.startsWith("/")) "${mainUrl.trimEnd('/')}$url" else url
        }
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

    data class VideoIframe(
        @JsonProperty("success") val success: Boolean,
        @JsonProperty("data") val data: IframeData
    )

    data class IframeData(
        @JsonProperty("html") val html: String
    )
}
