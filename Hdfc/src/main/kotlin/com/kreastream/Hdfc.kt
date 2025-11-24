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
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.util.*
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.NiceResponse

class Hdfc : MainAPI() {
    override var mainUrl = "https://www.hdfilmcehennemi.la"
    override var name = "HDFilmCehennemi"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 50L
    override var sequentialMainPageScrollDelay = 50L

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
        "${mainUrl}/load/page/1/home-series/" to "Yeni Eklenen Diziler",
        "${mainUrl}/load/page/1/imdb7/" to "IMDB 7+ Filmler",
        "${mainUrl}/load/page/1/mostLiked/" to "En Çok Beğenilenler"
    )

    private suspend fun safeGet(
        url: String,
        headers: Map<String, String> = standardHeaders,
        referer: String? = null
    ): NiceResponse {
        var response = app.get(url, headers = headers, referer = referer)
        val body = response.text
        if (body.contains("Just a moment", ignoreCase = true)
            || body.contains("Checking your browser", ignoreCase = true)
            || response.code == 403
        ) {
            Log.w("HDCH", "Cloudflare detected for $url – retrying with CloudflareKiller")
            return CloudflareKiller().bypass(url, headers)
        }
        return response
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            request.data
                .replace("/load/page/1/genres/", "/tur/")
                .replace("/load/page/1/categories/", "/category/")
                .replace("/load/page/1/imdb7/", "/imdb-7-puan-uzeri-filmler/")
        } else {
            request.data.replace("/page/1/", "/page/${page}/")
        }

        Log.d("HDCH", "getMainPage -> $url")
        val response = safeGet(url, standardHeaders, mainUrl)
        val text = response.text

        if (text.contains("Sayfa Bulunamadı", true)) {
            Log.d("HDCH", "Sayfa bulunamadı: $url")
            return newHomePageResponse(request.name, emptyList())
        }

        return try {
            val document = if (text.trim().startsWith("{") && text.contains("\"html\"")) {
                try {
                    val parsed: HDFC = objectMapper.readValue(text)
                    Jsoup.parse(parsed.html)
                } catch (e: Exception) {
                    Log.e("HDCH", "getMainPage JSON parse failed: ${e.message}")
                    response.document
                }
            } else {
                response.document
            }

            val posterElements = document.select("div.posters-4-col a.poster, a.poster, .posters-4-col a")
            Log.d("HDCH", "Found posters: ${posterElements.size}")

            val results = posterElements.mapNotNull { it.toSearchResult() }
            newHomePageResponse(request.name, results)
        } catch (e: Exception) {
            Log.e("HDCH", "getMainPage failed: ${e.message}")
            newHomePageResponse(request.name, emptyList())
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val rawTitle = this.attr("title").takeIf { it.isNotEmpty() }
            ?: this.selectFirst("strong.poster-title")?.text()
            ?: this.selectFirst(".poster-title")?.text()
        rawTitle ?: return null

        val excluded = listOf("Seri Filmler", "Japonya Filmleri", "Kore Filmleri", "Hint Filmleri", "Türk Filmleri", "1080p Film izle")
        if (excluded.any { rawTitle.contains(it, ignoreCase = true) }) return null

        val href = fixUrlNull(this.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src") ?: this.selectFirst("img")?.attr("src"))
        val type = when {
            href.contains("/diziler/") || href.contains("/tv/") -> TvType.TvSeries
            else -> TvType.Movie
        }
        return newMovieSearchResponse(rawTitle, href, type) { this.posterUrl = posterUrl }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val response = safeGet("$mainUrl/search?q=${query}", mapOf("X-Requested-With" to "fetch"))
        val parsed = response.parsedSafe<Results>() ?: return emptyList()
        val searchResults = mutableListOf<SearchResponse>()
        parsed.results.forEach { resultHtml ->
            val document = Jsoup.parse(resultHtml)
            val title = document.selectFirst("h4.title")?.text() ?: return@forEach
            val href = fixUrlNull(document.selectFirst("a")?.attr("href")) ?: return@forEach
            val posterUrl = fixUrlNull(document.selectFirst("img")?.attr("src")) ?: fixUrlNull(document.selectFirst("img")?.attr("data-src"))
            searchResults.add(newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl?.replace("/thumb/", "/list/") })
        }
        return searchResults
    }

    override suspend fun load(url: String): LoadResponse? {
        try {
            Log.d("HDCH", "Loading details: $url")

            var response = safeGet(url, standardHeaders, mainUrl)
            var text = response.text

            val document = if (text.trim().startsWith("{") && text.contains("\"html\"")) {
                try {
                    val parsed = objectMapper.readValue<HDFC>(text)
                    Jsoup.parse(parsed.html)
                } catch (e: Exception) {
                    Log.e("HDCH", "load JSON parse failed: ${e.message}")
                    response.document
                }
            } else {
                response.document
            }

            val title = document.selectFirst("h1.section-title, h1.entry-title")?.text()?.substringBefore(" izle")?.trim()
                ?: run {
                    Log.e("HDCH", "No title found for $url")
                    return null
                }

            val poster = fixUrlNull(
                document.selectFirst("aside.post-info-poster img.lazyload")?.attr("data-src")
                    ?: document.selectFirst("img.wp-post-image")?.attr("data-src")
                    ?: document.selectFirst("img.attachment-full")?.attr("src")
            )

            val tags = document.select("div.post-info-genres a, span.genres a").map { it.text() }
            val year = document.selectFirst("div.post-info-year-country a, div.poster-meta span")?.text()?.trim()?.toIntOrNull()
            val score = document.selectFirst("div.post-info-imdb-rating span, .imdb")?.text()?.substringBefore("(")?.trim()?.toFloatOrNull()
            val desc = document.selectFirst("article.post-info-content > p, div.description p")?.text()?.trim()

            val langInfo = document.selectFirst(".poster-lang")?.text()
            val dubTag = when {
                langInfo?.contains("Dublaj", true) == true -> "Türkçe Dublaj"
                langInfo?.contains("Altyaz", true) == true -> "Türkçe Altyazılı"
                else -> null
            }

            val isSeries = document.select("div.seasons, #seasons-tab").isNotEmpty()

            val actors = document.select("div.post-info-cast a").map {
                Actor(it.selectFirst("strong")?.text() ?: it.text(), it.selectFirst("img")?.attr("data-src") ?: "")
            }

            val recommendations = document.select("div.section-slider-container div.slider-slide a, div.recommended-posts a").mapNotNull {
                val recHref = fixUrlNull(it.attr("href")) ?: return@mapNotNull null
                val recTitle = it.attr("title").ifEmpty { it.text() }
                val recPoster = fixUrlNull(it.selectFirst("img")?.attr("data-src") ?: it.selectFirst("img")?.attr("src"))
                newTvSeriesSearchResponse(recTitle, recHref, TvType.TvSeries) { this.posterUrl = recPoster }
            }

            val trailer = document.selectFirst("div.post-info-trailer button")?.attr("data-modal")?.substringAfter("trailer/")?.let { "https://www.youtube.com/embed/$it" }

            return if (isSeries) {
                val episodes = document.select("div.seasons-tab-content a").mapNotNull {
                    val epName = it.selectFirst("h4")?.text()?.trim() ?: return@mapNotNull null
                    val epHref = fixUrlNull(it.attr("href")) ?: return@mapNotNull null
                    val epEpisode = Regex("""(\d+)\. ?Bölüm""").find(epName)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    val epSeason = Regex("""(\d+)\. ?Sezon""").find(epName)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
                    newEpisode(epHref) { this.name = epName; this.season = epSeason; this.episode = epEpisode }
                }
                newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.year = year
                    this.plot = desc
                    this.tags = tags + listOfNotNull(dubTag)
                    this.score = Score.from10(score)
                    this.recommendations = recommendations
                    addActors(actors)
                    addTrailer(trailer)
                }
            } else {
                newMovieLoadResponse(title, url, TvType.Movie, url) {
                    this.posterUrl = poster
                    this.year = year
                    this.plot = desc
                    this.tags = tags + listOfNotNull(dubTag)
                    this.score = Score.from10(score)
                    this.recommendations = recommendations
                    addActors(actors)
                    addTrailer(trailer)
                }
            }
        } catch (e: Exception) {
            Log.e("HDCH", "load() failed: ${e.message}")
            return null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("HDCH", "loadLinks -> $data")
        var linksFound = false
        val processedUrls = mutableSetOf<String>()

        val pageResp = safeGet(data, standardHeaders, mainUrl)
        val document = pageResp.document

        val closeIframe = fixUrlNull(document.selectFirst(".close")?.attr("data-src"))?.takeIf { it.isNotBlank() }
        if (!closeIframe.isNullOrBlank()) {
            Log.d("HDCH", "Found Close iframe: $closeIframe")
            try {
                if (extractFromIframeUrl(closeIframe, data, subtitleCallback, callback, processedUrls)) {
                    linksFound = true
                }
            } catch (e: Exception) {
                Log.e("HDCH", "Close iframe processing failed: ${e.message}")
            }
        }

        document.select("div.alternative-links button.alternative-link").forEach { button ->
            val sourceLabel = button.text().replace("(HDrip Xbet)", "").trim()
            val videoID = button.attr("data-video").takeIf { it.isNotBlank() } ?: return@forEach
            try {
                Log.d("HDCH", "Processing alternative videoID: $videoID ($sourceLabel)")
                val apiResp = safeGet("$mainUrl/video/$videoID/", mapOf("X-Requested-With" to "fetch"), data)
                val parsed = apiResp.parsedSafe<VideoIframe>()
                val iframeHtml = parsed?.data?.html ?: return@forEach
                val iframeSrc = Jsoup.parse(iframeHtml).selectFirst("iframe")?.attr("data-src")?.ifBlank { 
                    Jsoup.parse(iframeHtml).selectFirst("iframe")?.attr("src") 
                }
                if (iframeSrc.isNullOrBlank()) return@forEach
                val iframeUrl = resolveUrl(iframeSrc, mainUrl)
                
                Log.d("HDCH", "Processing iframe: $iframeUrl")
                if (extractFromIframeUrl(iframeUrl, data, subtitleCallback, callback, processedUrls)) {
                    linksFound = true
                }
            } catch (e: Exception) {
                Log.e("HDCH", "Alternative processing failed: ${e.message}")
            }
        }

        document.select("iframe[data-src], iframe[src]").forEach { iframe ->
            val src = iframe.attr("data-src").ifBlank { iframe.attr("src") }
            if (src.isNullOrBlank()) return@forEach
            val final = resolveUrl(src, data)
            
            try {
                if (extractFromIframeUrl(final, data, subtitleCallback, callback, processedUrls)) {
                    linksFound = true
                }
            } catch (e: Exception) {
                Log.e("HDCH", "Direct iframe failed: ${e.message}")
            }
        }

        if (!linksFound) Log.e("HDCH", "No links found for $data")
        return linksFound
    }

    private suspend fun extractFromIframeUrl(
        iframeUrl: String,
        refererForIframe: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        processedUrls: MutableSet<String>
    ): Boolean {
        var emitted = false
        
        try {
            Log.d("HDCH", "Fetching iframe: $iframeUrl")
            val iframeResp = safeGet(iframeUrl, standardHeaders, refererForIframe)
            val iframeHtml = iframeResp.text

            extractSubtitlesFromHtml(iframeHtml).forEach { 
                subtitleCallback(it)
                Log.d("HDCH", "Subtitle: ${it.lang} -> ${it.url}")
            }

            when {
                iframeUrl.contains("hdfilmcehennemi.mobi", ignoreCase = true) -> {
                    if (extractCloseLinks(iframeHtml, refererForIframe, callback, processedUrls)) emitted = true
                }
                iframeUrl.contains("/rplayer/", ignoreCase = true) -> {
                    if (extractRapidrameLinks(iframeHtml, refererForIframe, callback, processedUrls)) emitted = true
                }
                else -> {
                    if (extractCloseLinks(iframeHtml, refererForIframe, callback, processedUrls)) emitted = true
                    if (extractRapidrameLinks(iframeHtml, refererForIframe, callback, processedUrls)) emitted = true
                }
            }

            Regex("""/rplayer/([A-Za-z0-9_\-]+)/?""").find(iframeHtml)?.groupValues?.getOrNull(1)?.let { id ->
                try {
                    val rplayerUrl = "$mainUrl/rplayer/$id/"
                    Log.d("HDCH", "Found rplayer reference: $rplayerUrl")
                    if (extractFromIframeUrl(rplayerUrl, refererForIframe, subtitleCallback, callback, processedUrls)) {
                        emitted = true
                    }
                } catch (e: Exception) {
                    Log.e("HDCH", "rplayer fetch failed: ${e.message}")
                }
            }

        } catch (e: Exception) {
            Log.e("HDCH", "extractFromIframeUrl failed: ${e.message}")
        }
        
        return emitted
    }

    private suspend fun extractCloseLinks(
        html: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
        foundUrls: MutableSet<String>
    ): Boolean {
        var emitted = false

        Regex("""https?://srv\d+\.cdnimages\d*\.sbs/[^\s"'<>]+?(?:master\.txt|index\.m3u8)""", RegexOption.IGNORE_CASE)
            .findAll(html).forEach { m ->
                val url = m.value.substringBefore("?").substringBefore("#")
                if (foundUrls.add(url)) {
                    val normalized = normalizeMasterToM3u8(url)
                    emitCloseLink(normalized, referer, callback)
                    emitted = true
                }
            }

        Regex("""https?://[^\s"'<>]+?/txt/master\.txt""", RegexOption.IGNORE_CASE)
            .findAll(html).forEach { m ->
                val url = m.value
                if (foundUrls.add(url)) {
                    val normalized = normalizeMasterToM3u8(url)
                    emitCloseLink(normalized, referer, callback)
                    emitted = true
                }
            }

        Regex("""contentUrl["\s:]+["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(html).forEach { m ->
                val url = m.groupValues[1]
                if (url.contains("cdnimages", ignoreCase = true) && foundUrls.add(url)) {
                    val normalized = normalizeMasterToM3u8(url)
                    emitCloseLink(normalized, referer, callback)
                    emitted = true
                }
            }

        return emitted
    }

    private suspend fun extractRapidrameLinks(
        html: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
        foundUrls: MutableSet<String>
    ): Boolean {
        var emitted = false

        val deobfuscated = deobfuscateJavaScript(html)
        
        Regex("""https?://s\d+\.rapidrame\.com/[^\s"'<>]+?master\.m3u8[^\s"'<>]*""", RegexOption.IGNORE_CASE)
            .findAll(deobfuscated).forEach { m ->
                val url = m.value
                if (foundUrls.add(url)) {
                    parseMasterPlaylist(url, referer, callback, foundUrls)
                    emitted = true
                }
            }

        Regex("""https?://s\d+\.rapidrame\.com/[^\s"'<>]+?index-f\d+-[^\s"'<>]+?\.m3u8[^\s"'<>]*""", RegexOption.IGNORE_CASE)
            .findAll(deobfuscated).forEach { m ->
                val url = m.value
                if (foundUrls.add(url)) {
                    emitRapidrameLink(url, referer, callback)
                    emitted = true
                }
            }

        Regex("""contentUrl["\s:]+["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(deobfuscated).forEach { m ->
                val url = m.groupValues[1]
                if (url.contains("rapidrame", ignoreCase = true) && foundUrls.add(url)) {
                    if (url.contains("master.m3u8")) {
                        parseMasterPlaylist(url, referer, callback, foundUrls)
                    } else {
                        emitRapidrameLink(url, referer, callback)
                    }
                    emitted = true
                }
            }

        return emitted
    }

    private suspend fun parseMasterPlaylist(
        masterUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
        foundUrls: MutableSet<String>
    ) {
        try {
            Log.d("HDCH", "Parsing master playlist: $masterUrl")
            val response = safeGet(masterUrl, standardHeaders, referer)
            val content = response.text
            
            val lines = content.lines()
            var i = 0
            while (i < lines.size) {
                val line = lines[i].trim()
                if (line.startsWith("#EXT-X-STREAM-INF:")) {
                    if (i + 1 < lines.size) {
                        val urlLine = lines[i + 1].trim()
                        if (urlLine.isNotEmpty() && !urlLine.startsWith("#")) {
                            val variantUrl = if (urlLine.startsWith("http")) {
                                urlLine
                            } else {
                                resolveUrl(urlLine, masterUrl)
                            }
                            
                            if (foundUrls.add(variantUrl)) {
                                val bandwidth = Regex("""BANDWIDTH=(\d+)""").find(line)?.groupValues?.get(1)?.toIntOrNull()
                                val resolution = Regex("""RESOLUTION=(\d+)x(\d+)""").find(line)?.groupValues?.get(1)
                                
                                val quality = when {
                                    resolution != null && resolution.toIntOrNull()?: 0 >= 1920 -> Qualities.P1080.value
                                    resolution != null && resolution.toIntOrNull() ?: 0 >= 1280 -> Qualities.P720.value
                                    resolution != null && resolution.toIntOrNull() ?: 0 >= 640 -> Qualities.P480.value
                                    bandwidth != null && bandwidth >= 2000000 -> Qualities.P1080.value
                                    bandwidth != null && bandwidth >= 1000000 -> Qualities.P720.value
                                    else -> Qualities.P480.value
                                }
                                
                                emitRapidrameLink(variantUrl, referer, callback, quality)
                            }
                        }
                    }
                }
                i++
            }
        } catch (e: Exception) {
            Log.e("HDCH", "Failed to parse master playlist: ${e.message}")
        }
    }

    private fun deobfuscateJavaScript(html: String): String {
        var result = html
        
        val evalPattern = Regex("""eval\(function\(p,a,c,k,e,d\)\{[^}]+\}[^)]+\)\)""", RegexOption.MULTILINE)
        evalPattern.findAll(html).forEach { match ->
            try {
                val packed = match.value
                val dataMatch = Regex("""\{[^}]*\}\.split\(['"]([^'"]+)['"]\)""").find(packed)
                if (dataMatch != null) {
                    result += " " + dataMatch.groupValues[1]
                }
            } catch (e: Exception) {
                Log.e("HDCH", "Deobfuscation failed: ${e.message}")
            }
        }
        
        Regex("""atob\(['"]([^'"]+)['"]\)""").findAll(html).forEach { match ->
            try {
                val encoded = match.groupValues[1]
                val decoded = String(Base64.getDecoder().decode(encoded))
                result += " $decoded"
            } catch (e: Exception) {
                Log.e("HDCH", "atob decode failed: ${e.message}")
            }
        }
        
        return result
    }

    private fun extractSubtitlesFromHtml(html: String): List<SubtitleFile> {
        val subs = mutableListOf<SubtitleFile>()
        
        Regex("""<track[^>]+src=["']([^"']+)["'][^>]*>""", RegexOption.IGNORE_CASE).findAll(html).forEach { m ->
            val src = m.groupValues[1]
            val tag = m.value
            val lang = when {
                tag.contains("srclang=\"tr\"", true) || src.contains("-tr", true) || src.contains("_Turkish", true) -> "Türkçe"
                tag.contains("srclang=\"en\"", true) || src.contains("-en", true) -> "English"
                tag.contains("forced", true) -> "Forced"
                else -> Regex("""label=["']([^"']+)["']""").find(tag)?.groupValues?.get(1) ?: "Unknown"
            }
            val final = if (src.startsWith("http")) src else resolveUrl(src, mainUrl)
            subs += SubtitleFile(lang, final)
        }
        
        Regex("""https?://[^\s"'<>]+\.(?:vtt|srt)""", RegexOption.IGNORE_CASE).findAll(html).forEach { m ->
            val url = m.value.substringBefore("?").substringBefore("#")
            val lang = when {
                url.contains("-tr", true) || url.contains("_Turkish", true) -> "Türkçe"
                url.contains("-en", true) || url.contains("_English", true) -> "English"
                url.contains("forced", true) -> "Forced"
                else -> "Unknown"
            }
            subs += SubtitleFile(lang, url)
        }
        
        return subs.distinctBy { it.url }
    }

    private suspend fun emitCloseLink(url: String, referer: String, callback: (ExtractorLink) -> Unit) {
        val normalized = normalizeMasterToM3u8(url)
        val quality = extractQualityFromUrl(normalized)
        callback(
            newExtractorLink(
                source = "HDFC Close",
                name = "HDFC Close",
                url = normalized
            ) {
                this.referer = referer
                this.quality = quality
                //this.isM3u8 = true
                this.headers = standardHeaders + mapOf("Origin" to mainUrl, "Referer" to referer)
            }
        )
        Log.d("HDCH", "emitCloseLink -> $normalized [Q:$quality]")
    }

    private suspend fun emitRapidrameLink(url: String, referer: String, callback: (ExtractorLink) -> Unit, qualityOverride: Int? = null) {
        val quality = qualityOverride ?: extractQualityFromUrl(url)
        callback(
            newExtractorLink(
                source = "HDFC Rapidrame",
                name = "HDFC Rapidrame",
                url = url,
                referer = referer,
                quality = quality,
                isM3u8 = true
            ) {
                this.headers = standardHeaders + mapOf("Origin" to mainUrl, "Referer" to referer)
            }
        )
        Log.d("HDCH", "emitRapidrameLink -> $url [Q:$quality]")
    }

    private fun normalizeMasterToM3u8(url: String): String {
        return when {
            url.endsWith("/txt/master.txt", true) -> url.replace("/txt/master.txt", "/index.m3u8")
            url.endsWith("master.txt", true) -> url.replace("master.txt", "index.m3u8")
            else -> url
        }
    }

    private fun extractQualityFromUrl(url: String): Int {
        return when {
            url.contains("2160", true) || url.contains("4k", true) -> Qualities.P2160.value
            url.contains("1080", true) || url.contains("f2-v1", true) -> Qualities.P1080.value
            url.contains("720", true) -> Qualities.P720.value
            url.contains("480", true) || url.contains("f1-v1", true) -> Qualities.P480.value
            url.contains("360", true) -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private fun resolveUrl(url: String, base: String): String {
        return try {
            if (url.startsWith("//")) {
                val b = URI(base)
                "${b.scheme}:$url"
            } else if (url.startsWith("http")) {
                url
            } else {
                val baseUri = URI(base)
                baseUri.resolve(url).toString()
            }
        } catch (e: Exception) {
            Log.e("HDCH", "resolveUrl error: ${e.message}")
            url
        }
    }

    private fun fixUrlNull(url: String?): String? {
        if (url == null) return null
        val t = url.trim()
        return when {
            t.startsWith("//") -> "https:$t"
            t.startsWith("http") -> t
            t.startsWith("/") -> mainUrl.trimEnd('/') + t
            else -> t
        }
    }

    data class Results(@JsonProperty("results") val results: List<String> = arrayListOf())
    data class HDFC(@JsonProperty("html") val html: String = "", @JsonProperty("meta") val meta: Any? = null)
    data class VideoIframe(@JsonProperty("success") val success: Boolean = false, @JsonProperty("data") val data: IframeData = IframeData())
    data class IframeData(@JsonProperty("html") val html: String = "")
}
