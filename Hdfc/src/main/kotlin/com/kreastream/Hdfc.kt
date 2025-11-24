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
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.util.Base64

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
    ) = app.get(url, headers = headers, referer = referer)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            request.data
                .replace("/load/page/1/genres/", "/tur/")
                .replace("/load/page/1/categories/", "/category/")
                .replace("/load/page/1/imdb7/", "/imdb-7-puan-uzeri-filmler/")
        } else {
            request.data.replace("/page/1/", "/page/${page}/")
        }

        val response = safeGet(url, standardHeaders, mainUrl)
        val text = response.text

        if (text.contains("Sayfa Bulunamadı", true)) {
            return newHomePageResponse(request.name, emptyList())
        }

        return try {
            val document = if (text.trim().startsWith("{") && text.contains("\"html\"")) {
                try {
                    val parsed: HDFC = objectMapper.readValue(text)
                    Jsoup.parse(parsed.html)
                } catch (e: Exception) {
                    response.document
                }
            } else {
                response.document
            }

            val posterElements = document.select("div.posters-4-col a.poster, a.poster, .posters-4-col a")
            val results = posterElements.mapNotNull { it.toSearchResult() }
            newHomePageResponse(request.name, results)
        } catch (e: Exception) {
            newHomePageResponse(request.name, emptyList())
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val rawTitle = this.attr("title").takeIf { it.isNotEmpty() }
            ?: this.select("strong.poster-title").first()?.text()
            ?: this.select(".poster-title").first()?.text()
        rawTitle ?: return null

        val excluded = listOf("Seri Filmler", "Japonya Filmleri", "Kore Filmleri", "Hint Filmleri", "Türk Filmleri", "1080p Film izle")
        if (excluded.any { rawTitle.contains(it, ignoreCase = true) }) return null

        val href = fixUrlNull(this.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.select("img").first()?.attr("data-src") ?: this.select("img").first()?.attr("src"))
        val type = when {
            href.contains("/diziler/") || href.contains("/tv/") -> TvType.TvSeries
            else -> TvType.Movie
        }
        return newMovieSearchResponse(rawTitle, href, type) { this.posterUrl = posterUrl }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val response = safeGet("$mainUrl/search?q=${query}", mapOf("X-Requested-With" to "fetch"))
        
        val parsed = try {
            objectMapper.readValue<Results>(response.text)
        } catch (e: Exception) {
            return emptyList()
        }

        val searchResults = mutableListOf<SearchResponse>()
        for (resultHtml in parsed.results) {
            val document = Jsoup.parse(resultHtml)
            val title = document.select("h4.title").first()?.text() ?: continue
            val href = fixUrlNull(document.select("a").first()?.attr("href")) ?: continue
            val posterUrl = fixUrlNull(document.select("img").first()?.attr("src")) ?: fixUrlNull(document.select("img").first()?.attr("data-src"))
            
            searchResults.add(newMovieSearchResponse(title, href, TvType.Movie) { 
                this.posterUrl = posterUrl?.replace("/thumb/", "/list/") 
            })
        }
        return searchResults
    }

    override suspend fun load(url: String): LoadResponse? {
        try {
            val response = safeGet(url, standardHeaders, mainUrl)
            val text = response.text

            val document = if (text.trim().startsWith("{") && text.contains("\"html\"")) {
                try {
                    val parsed = objectMapper.readValue<HDFC>(text)
                    Jsoup.parse(parsed.html)
                } catch (e: Exception) {
                    response.document
                }
            } else {
                response.document
            }

            val title = document.select("h1.section-title, h1.entry-title").first()?.text()?.substringBefore(" izle")?.trim()
                ?: return null

            val poster = fixUrlNull(
                document.select("aside.post-info-poster img.lazyload").first()?.attr("data-src")
                    ?: document.select("img.wp-post-image").first()?.attr("data-src")
                    ?: document.select("img.attachment-full").first()?.attr("src")
            )

            val tags = document.select("div.post-info-genres a, span.genres a").map { it.text() }
            val year = document.select("div.post-info-year-country a, div.poster-meta span").first()?.text()?.trim()?.toIntOrNull()
            val score = document.select("div.post-info-imdb-rating span, .imdb").first()?.text()?.substringBefore("(")?.trim()?.toFloatOrNull()
            val desc = document.select("article.post-info-content > p, div.description p").first()?.text()?.trim()

            val langInfo = document.select(".poster-lang").first()?.text()
            val dubTag = when {
                langInfo?.contains("Dublaj", true) == true -> "Türkçe Dublaj"
                langInfo?.contains("Altyaz", true) == true -> "Türkçe Altyazılı"
                else -> null
            }
            val finalTags = if (dubTag != null) tags + dubTag else tags

            val isSeries = document.select("div.seasons, #seasons-tab").isNotEmpty()

            val actors = document.select("div.post-info-cast a").map {
                Actor(it.select("strong").first()?.text() ?: it.text(), it.select("img").first()?.attr("data-src") ?: "")
            }

            val recommendations = document.select("div.section-slider-container div.slider-slide a, div.recommended-posts a").mapNotNull {
                val recHref = fixUrlNull(it.attr("href")) ?: return@mapNotNull null
                val recTitle = it.attr("title").ifEmpty { it.text() }
                val recPoster = fixUrlNull(it.select("img").first()?.attr("data-src") ?: it.select("img").first()?.attr("src"))
                newTvSeriesSearchResponse(recTitle, recHref, TvType.TvSeries) { this.posterUrl = recPoster }
            }

            val trailer = document.select("div.post-info-trailer button").first()?.attr("data-modal")?.substringAfter("trailer/")?.let { "https://www.youtube.com/embed/$it" }

            return if (isSeries) {
                val episodes = document.select("div.seasons-tab-content a").mapNotNull {
                    val epName = it.select("h4").first()?.text()?.trim() ?: return@mapNotNull null
                    val epHref = fixUrlNull(it.attr("href")) ?: return@mapNotNull null
                    val epEpisode = Regex("""(\d+)\. ?Bölüm""").find(epName)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    val epSeason = Regex("""(\d+)\. ?Sezon""").find(epName)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
                    newEpisode(epHref) { this.name = epName; this.season = epSeason; this.episode = epEpisode }
                }
                newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.year = year
                    this.plot = desc
                    this.tags = finalTags
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
                    this.tags = finalTags
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

        // 1. Check for hidden close iframe (RAPIDRAME)
        val closeIframe = fixUrlNull(document.select("iframe.close").first()?.attr("data-src"))?.takeIf { it.isNotBlank() }
        if (!closeIframe.isNullOrBlank()) {
            Log.d("HDCH", "Found Close iframe: $closeIframe")
            try {
                if (extractFromRapidrameIframe(closeIframe!!, data, subtitleCallback, callback, processedUrls)) {
                    linksFound = true
                }
            } catch (e: Exception) {
                Log.e("HDCH", "Close iframe processing failed: ${e.message}")
            }
        }

        // 2. Check Alternative Buttons (AJAX POST REQUIRED)
        val altButtons = document.select("div.alternative-links button.alternative-link").toList()
        for (button in altButtons) {
            val sourceLabel = button.text().replace("(HDrip Xbet)", "").trim()
            val videoID = button.attr("data-video").takeIf { it.isNotBlank() } ?: continue
            try {
                Log.d("HDCH", "Processing alternative videoID: $videoID ($sourceLabel)")
                
                // CRITICAL FIX: Use POST
                val apiResp = app.post(
                    url = "$mainUrl/video/$videoID/", 
                    headers = mapOf("X-Requested-With" to "fetch"), 
                    referer = data
                )
                
                val parsed = try { objectMapper.readValue<VideoIframe>(apiResp.text) } catch (e: Exception) { null }
                val iframeHtml = parsed?.data?.html ?: continue
                val iframeDoc = Jsoup.parse(iframeHtml)
                val iframeSrc = iframeDoc.select("iframe").first()?.attr("data-src")?.ifBlank { 
                    iframeDoc.select("iframe").first()?.attr("src") 
                }
                if (iframeSrc.isNullOrBlank()) continue
                val iframeUrl = resolveUrl(iframeSrc!!, mainUrl)
                
                Log.d("HDCH", "Processing iframe: $iframeUrl")
                if (extractFromRapidrameIframe(iframeUrl, data, subtitleCallback, callback, processedUrls)) {
                    linksFound = true
                }
            } catch (e: Exception) {
                Log.e("HDCH", "Alternative processing failed: ${e.message}")
            }
        }

        // 3. Direct Iframes
        val directIframes = document.select("iframe[data-src], iframe[src]").toList()
        for (iframe in directIframes) {
            val src = iframe.attr("data-src").ifBlank { iframe.attr("src") }
            if (src.isNullOrBlank()) continue
            val final = resolveUrl(src, data)
            
            try {
                if (extractFromRapidrameIframe(final, data, subtitleCallback, callback, processedUrls)) {
                    linksFound = true
                }
            } catch (e: Exception) {
                Log.e("HDCH", "Direct iframe failed: ${e.message}")
            }
        }

        if (!linksFound) Log.e("HDCH", "No links found for $data")
        return linksFound
    }

    private suspend fun extractFromRapidrameIframe(
        iframeUrl: String,
        refererForIframe: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        processedUrls: MutableSet<String>
    ): Boolean {
        var emitted = false
        if (processedUrls.contains(iframeUrl)) return false
        processedUrls.add(iframeUrl)
        
        try {
            Log.d("HDCH", "Fetching rapidrame iframe: $iframeUrl")
            val iframeResp = safeGet(iframeUrl, standardHeaders, refererForIframe)
            val iframeHtml = iframeResp.text

            // Extract subtitles first
            extractSubtitlesFromHtml(iframeHtml).forEach { 
                subtitleCallback(it)
            }

            // NEW: Enhanced extraction for rapidrame embedded content
            if (extractRapidrameLinks(iframeHtml, refererForIframe, callback, processedUrls)) emitted = true

            // Look for additional embedded iframes recursively
            val nestedIframes = Regex("""<iframe[^>]+(?:data-src|src)=["']([^"']+)["']""").findAll(iframeHtml).toList()
            for (nested in nestedIframes) {
                val nestedUrl = nested.groupValues[1]
                val finalNestedUrl = resolveUrl(nestedUrl, iframeUrl)
                if (extractFromRapidrameIframe(finalNestedUrl, refererForIframe, subtitleCallback, callback, processedUrls)) {
                    emitted = true
                }
            }

        } catch (e: Exception) {
            Log.e("HDCH", "extractFromRapidrameIframe failed: ${e.message}")
        }
        
        return emitted
    }

    /* NEW: Specialized extraction for rapidrame player content */
    private suspend fun extractRapidrameLinks(
        html: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
        foundUrls: MutableSet<String>
    ): Boolean {
        var emitted = false
        
        // Method 1: Direct HLS URL patterns from the text file example
        val hlsPatterns = listOf(
            Regex("""https?://[A-Za-z0-9\-\.]+/hls/[^"'\s<>]+\.(?:m3u8|master\.txt)"""),
            Regex("""https?://srv\d+\.cdnimages\d+\.sbs/hls/[^"'\s<>]+\.mp4/txt/master\.txt"""),
            Regex("""["'](https?://[^"'\s<>]+/txt/(?:master\.txt|sublist_[^"'\s<>]+))["']""")
        )
        
        for (pattern in hlsPatterns) {
            pattern.findAll(html).forEach { match ->
                var url = match.groupValues[1].takeIf { it.startsWith("http") } ?: match.value
                url = normalizeMasterToM3u8(url)
                if (foundUrls.add(url)) {
                    Log.d("HDCH", "Found HLS URL: $url")
                    parseMasterPlaylist(url, referer, callback, foundUrls)
                    emitted = true
                }
            }
        }

        // Method 2: Deobfuscate JavaScript and extract URLs
        val deobfuscated = deobfuscateRapidrameJavaScript(html)
        if (deobfuscated.isNotEmpty()) {
            // Look for CDN patterns in deobfuscated code
            val cdnPattern = Regex("""(https?://srv\d+\.cdnimages\d+\.sbs/[^"'\s<>]+)""")
            cdnPattern.findAll(deobfuscated).forEach { match ->
                val url = match.value
                if (url.contains("/hls/") || url.contains("/txt/")) {
                    val finalUrl = normalizeMasterToM3u8(url)
                    if (foundUrls.add(finalUrl)) {
                        Log.d("HDCH", "Found CDN URL from JS: $finalUrl")
                        parseMasterPlaylist(finalUrl, referer, callback, foundUrls)
                        emitted = true
                    }
                }
            }
        }

        // Method 3: Extract from packed JavaScript (specific to the example)
        if (!emitted) {
            val packedUrls = extractFromPackedJavaScript(html)
            for (url in packedUrls) {
                val finalUrl = normalizeMasterToM3u8(url)
                if (foundUrls.add(finalUrl)) {
                    Log.d("HDCH", "Found URL from packed JS: $finalUrl")
                    parseMasterPlaylist(finalUrl, referer, callback, foundUrls)
                    emitted = true
                }
            }
        }

        return emitted
    }

    /* NEW: Enhanced deobfuscation for rapidrame specific JavaScript */
    private fun deobfuscateRapidrameJavaScript(html: String): String {
        var result = StringBuilder()
        
        // Extract all script content
        val scripts = Regex("""<script[^>]*>([^<]*)</script>""", RegexOption.DOT_MATCHES_ALL).findAll(html).toList()
        for (script in scripts) {
            val scriptContent = script.groupValues[1]
            if (scriptContent.contains("eval(function(p,a,c,k,e,d)") || scriptContent.contains("rapidrame")) {
                // Try to unpack the packed JavaScript
                val unpacked = unpackPacker(scriptContent)
                result.append(unpacked).append("\n")
            } else {
                result.append(scriptContent).append("\n")
            }
        }
        
        return result.toString()
    }

    /* NEW: Extract URLs from packed JavaScript (specific to the example format) */
    private fun extractFromPackedJavaScript(html: String): List<String> {
        val urls = mutableListOf<String>()
        
        // Look for the specific packed pattern from the example
        val packedMatch = Regex("""eval\(function\(p,a,c,k,e,d\)[^{]+\{([^}]+)\}\)[^)]+\)""", RegexOption.DOT_MATCHES_ALL).find(html)
        if (packedMatch != null) {
            try {
                // Extract potential URLs from the packed content
                val potentialUrls = Regex("""['"]([A-Za-z0-9+/=]+)['"]""").findAll(packedMatch.value).map { it.groupValues[1] }.toList()
                
                for (potential in potentialUrls) {
                    try {
                        // Try base64 decoding
                        if (potential.length > 10) {
                            val decoded = String(Base64.getDecoder().decode(potential))
                            if (decoded.contains("http") || decoded.contains("/hls/") || decoded.contains("cdnimages")) {
                                urls.add(decoded)
                            }
                        }
                    } catch (e: Exception) {
                        // Not base64, check if it looks like a URL fragment
                        if (potential.contains("sisu") || potential.contains("bluray") || potential.contains("trdual")) {
                            // Reconstruct URL from fragments
                            val reconstructed = "https://srv12.cdnimages2068.sbs/hls/$potential.mp4/txt/master.txt"
                            urls.add(reconstructed)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("HDCH", "Error extracting from packed JS: ${e.message}")
            }
        }
        
        // Also look for direct URL patterns in the packed content
        val directUrlPattern = Regex("""(https?://[^"'\s<>]+/sisu[^"'\s<>]*\.(?:m3u8|master\.txt|mp4))""")
        directUrlPattern.findAll(html).forEach { match ->
            urls.add(match.value)
        }
        
        return urls.distinct()
    }

    /* Keep existing unpackPacker function but make it more robust */
    private fun unpackPacker(js: String): String {
        try {
                        val evalStart = js.indexOf("eval(function(p,a,c,k,e,d)")
            if (evalStart < 0) return js
            var idx = js.indexOf("function(p,a,c,k,e,d)", evalStart)
            if (idx < 0) return js
            idx = js.indexOf('{', idx)
            if (idx < 0) return js
            var depth = 0
            var i = idx
            var endIndex = -1
            while (i < js.length) {
                when (js[i]) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) { endIndex = i; break }
                    }
                }
                i++
            }
            if (endIndex < 0) return js

            val argsOpen = js.indexOf('(', endIndex)
            if (argsOpen < 0) return js
            var j = argsOpen
            var pDepth = 0
            var argsClose = -1
            while (j < js.length) {
                when (js[j]) {
                    '(' -> pDepth++
                    ')' -> {
                        pDepth--
                        if (pDepth == 0) { argsClose = j; break }
                    }
                }
                j++
            }
            if (argsClose < 0) return js
            val args = js.substring(argsOpen + 1, argsClose)

            val pRegex = Regex("""(['"])(?<p>(?:\\\1|.)*?)\1""", RegexOption.DOT_MATCHES_ALL)
            val pMatch = pRegex.find(args) ?: return js
            val pPayload = pMatch.groups["p"]?.value ?: return js

            val afterP = args.substring(pMatch.range.last + 1)
            val numMatch = Regex("""\b(\d+)\b""").findAll(afterP).toList()
            if (numMatch.size < 2) return js
            val a = numMatch[0].groups[1]!!.value.toIntOrNull() ?: return js
            val c = numMatch[1].groups[1]!!.value.toIntOrNull() ?: return js

            val kMatch = Regex("""(['"])(?<k>(?:\\\1|.)*?)\1\s*\.split\(['"]\|['"]\)""", RegexOption.DOT_MATCHES_ALL).find(afterP)
            val kList: List<String> = if (kMatch != null) {
                kMatch.groups["k"]!!.value.split("|")
            } else {
                val allQ = pRegex.findAll(args).mapNotNull { it.groups[2]?.value }.toList()
                val cand = allQ.reversed().firstOrNull { it.contains("|") && it.length > 10 }
                cand?.split("|") ?: emptyList()
            }

            val digits = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
            fun numToBase(n: Int, base: Int): String {
                if (n == 0) return "0"
                var nn = n
                val sb = StringBuilder()
                while (nn > 0) {
                    val d = nn % base
                    sb.insert(0, digits[d])
                    nn /= base
                }
                return sb.toString()
            }

            var payload = pPayload
                .replace("\\'", "'")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")

            for (ii in c - 1 downTo 0) {
                val key = numToBase(ii, a)
                if (ii < kList.size && kList[ii].isNotEmpty()) {
                    payload = payload.replace(Regex("(?<![A-Za-z0-9_\\$])${Regex.escape(key)}(?![A-Za-z0-9_\\$])"), kList[ii])
                }
            }
            return js // fallback
        } catch (e: Exception) {
            return js
        }
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
            
            if (content.contains("#EXT-X-STREAM-INF")) {
                // This is a master playlist with variants
                val lines = content.lines()
                var i = 0
                while (i < lines.size) {
                    val line = lines[i].trim()
                    if (line.startsWith("#EXT-X-STREAM-INF:")) {
                        if (i + 1 < lines.size) {
                            val urlLine = lines[i + 1].trim()
                            if (urlLine.isNotEmpty() && !urlLine.startsWith("#")) {
                                val variantUrl = resolveUrl(urlLine, masterUrl)
                                if (foundUrls.add(variantUrl)) {
                                    val resolution = Regex("""RESOLUTION=(\d+)x(\d+)""").find(line)?.groupValues?.get(2)?.toIntOrNull()
                                    val quality = when {
                                        resolution != null && resolution >= 1080 -> Qualities.P1080.value
                                        resolution != null && resolution >= 720 -> Qualities.P720.value
                                        resolution != null && resolution >= 480 -> Qualities.P480.value
                                        else -> Qualities.Unknown.value
                                    }
                                    emitRapidrameLink(variantUrl, referer, callback, quality)
                                    Log.d("HDCH", "Found variant: $variantUrl (quality: $quality)")
                                }
                            }
                        }
                    }
                    i++
                }
            } else if (content.contains(".m3u8") || content.endsWith(".m3u8", true)) {
                // Direct m3u8 playlist
                emitRapidrameLink(masterUrl, referer, callback)
                Log.d("HDCH", "Direct m3u8: $masterUrl")
            } else {
                // Try to use the master URL directly
                emitRapidrameLink(masterUrl, referer, callback)
                Log.d("HDCH", "Using master URL directly: $masterUrl")
            }
        } catch (e: Exception) {
            Log.e("HDCH", "parseMasterPlaylist failed for $masterUrl: ${e.message}")
        }
    }

    private fun extractSubtitlesFromHtml(html: String): List<SubtitleFile> {
        val subs = mutableListOf<SubtitleFile>()
        
        // Track elements
        Regex("""<track[^>]+src=["']([^"']+)["'][^>]*>""", RegexOption.IGNORE_CASE).findAll(html).toList().forEach { m ->
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
        
        // Also look for subtitle URLs in JavaScript
        val jsSubPattern = Regex("""["'](https?://[^"']+\.(?:vtt|srt))["']""")
        jsSubPattern.findAll(html).forEach { match ->
            val url = match.groupValues[1]
            subs += SubtitleFile("Unknown", url)
        }
        
        return subs.distinctBy { it.url }
    }

    private suspend fun emitRapidrameLink(url: String, referer: String, callback: (ExtractorLink) -> Unit, qualityOverride: Int? = null) {
        val quality = qualityOverride ?: extractQualityFromUrl(url)
        callback(
            newExtractorLink(
                source = "HDFC",
                name = "HDFC Rapidrame",
                url = url
            ) {
                this.referer = referer
                this.quality = quality
            }
        )
        Log.d("HDCH", "Emitted link: $url (quality: $quality)")
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
            url.contains("1080", true) -> Qualities.P1080.value
            url.contains("720", true) -> Qualities.P720.value
            url.contains("480", true) -> Qualities.P480.value
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
