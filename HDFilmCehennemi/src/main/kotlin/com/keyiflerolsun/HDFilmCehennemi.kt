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
        val rawTitle = this.attr("title").takeIf { it.isNotEmpty() } ?: return null

        val excluded = listOf(
            "Seri Filmler", "Japonya Filmleri", "Kore Filmleri", "Hint Filmleri",
            "Türk Filmleri", "DC Yapımları", "Marvel Yapımları", "Amazon Yapımları",
            "1080p Film izle"
        )

        if (excluded.any { rawTitle.contains(it, ignoreCase = true) }) return null

        val href      = fixUrlNull(this.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src"))

        return newMovieSearchResponse(rawTitle, href, TvType.Movie) { this.posterUrl = posterUrl }
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
        } else {
            try {
                extractFromIframeUrl(iframealak, data, subtitleCallback, callback)
            } catch (e: Exception) {
                Log.d("HDCH", "Direct iframe extraction failed: ${e.message}")
            }
        }

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

// Replace existing extractFromIframeUrl(...) with this improved version
private suspend fun extractFromIframeUrl(
    iframeUrl: String,
    refererForIframe: String,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    val iframeDocResponse = app.get(iframeUrl, referer = refererForIframe)
    val iframeDoc = iframeDocResponse.document

    // subtitles (unchanged)
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

    // 1) Collect scripts text (inline + remote references)
    val inlineScripts = iframeDoc.select("script").mapNotNull { it.data() }.joinToString("\n")
    val scriptSrcs = iframeDoc.select("script[src]").mapNotNull { it.attr("src") }.distinct()

    // 2) Try to unpack inline scripts first (this often reveals eval-packed variables like s_366WeEc1Du7)
    val unpackedInline = runCatching { getAndUnpack(inlineScripts) }.getOrNull()
    val scriptsText = listOfNotNull(unpackedInline, inlineScripts).firstOrNull() ?: inlineScripts

    Log.d("HDCH", "Scripts length: ${scriptsText.length}")

    // 3) Try to find sources usage that references a variable, e.g. sources: [{file:s_366WeEc1Du7}]
    val varUsageMatch = Regex("""sources\s*:\s*

\[\s*\{\s*file\s*:\s*([A-Za-z0-9_]+)\s*}""", RegexOption.IGNORE_CASE).find(scriptsText)
    if (varUsageMatch != null) {
        val varName = varUsageMatch.groupValues[1]
        Log.d("HDCH", "Found variable usage for source: $varName")

        // Try to resolve variable value in unpacked text first
        val varValue = run {
            // 1) look for simple var/let/const assignment: var s_366WeEc1Du7 = "..." or s_366WeEc1Du7="..."
            Regex("""(?:var|let|const)?\s*${Regex.escape(varName)}\s*=\s*["']([^"']+)["']""")
                .find(scriptsText)?.groupValues?.getOrNull(1)
                // 2) look for atob("...") or atob('...') usage: varName = atob("...")
                ?: Regex("""${Regex.escape(varName)}\s*=\s*atob\(\s*["']([A-Za-z0-9+/=]+)["']\s*\)""")
                    .find(scriptsText)?.groupValues?.getOrNull(1)?.let { runCatching { base64Decode(it) }.getOrNull() }
                // 3) look for assignment through the eval/unpacked tokens (common after getAndUnpack)
                ?: Regex("""["']${Regex.escape(varName)}["']\s*[,=]\s*["']([A-Za-z0-9+/=]+)["']""").find(scriptsText)?.groupValues?.getOrNull(1)
                // 4) full search for a base64-looking token near varName in unpacked text
                ?: Regex("""([A-Za-z0-9+/=]{40,})""").findAll(scriptsText).mapNotNull { it.groupValues.getOrNull(1) }
                    .firstOrNull { candidate ->
                        // heuristic: candidate appears close to varName
                        val idxVar = scriptsText.indexOf(varName)
                        val idxCand = scriptsText.indexOf(candidate)
                        idxVar >= 0 && idxCand >= 0 && Math.abs(idxVar - idxCand) < 400
                    }?.let { runCatching { base64Decode(it) }.getOrNull() }
        }

        if (!varValue.isNullOrBlank()) {
            // If varValue is base64-decoded content that yields a URL or another JS, try to extract direct urls
            Log.d("HDCH", "Resolved $varName => $varValue")
            val direct = extractVideoFromJs(varValue)
            if (!direct.isNullOrBlank()) {
                callback.invoke(
                    newExtractorLink("HDFC", "HDFC", direct, INFER_TYPE) {
                        this.referer = mainUrl
                        this.headers = mapOf("User-Agent" to standardHeaders["User-Agent"]!!)
                        this.quality = Qualities.Unknown.value
                    }
                )
                return
            }
            // varValue itself might be a direct URL
            if (varValue.startsWith("http")) {
                Log.d("HDCH", "Using variable value as direct URL: $varValue")
                callback.invoke(
                    newExtractorLink("HDFC", "HDFC", varValue, INFER_TYPE) {
                        this.referer = mainUrl
                        this.headers = mapOf("User-Agent" to standardHeaders["User-Agent"]!!)
                        this.quality = Qualities.Unknown.value
                    }
                )
                return
            }
        } else {
            Log.d("HDCH", "Could not resolve variable $varName from unpacked scripts")
        }
    }

    // 4) Try common patterns against the unpacked scriptsText
    // a) file_link base64 like file_link="..."
    val fileLinkEncoded = Regex("""file_link\s*=\s*["']([A-Za-z0-9+/=]+)["']""").find(scriptsText)?.groupValues?.getOrNull(1)
    if (!fileLinkEncoded.isNullOrBlank()) {
        val decoded = runCatching { base64Decode(fileLinkEncoded) }.getOrNull()
        if (!decoded.isNullOrBlank()) {
            Log.d("HDCH", "Found decoded file_link => $decoded")
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

    // b) sources: [{file:"..."}] after unpacking
    val sourcesMatch = Regex("""sources\s*:\s*

\[\s*\{\s*file\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(scriptsText)
    if (sourcesMatch != null) {
        val fileUrl = sourcesMatch.groupValues[1]
        val finalFile = resolveUrl(fileUrl, iframeDoc.location())
        Log.d("HDCH", "Found sources file => $finalFile")
        callback.invoke(
            newExtractorLink("HDFC", "HDFC", finalFile, INFER_TYPE) {
                this.referer = mainUrl
                this.headers = mapOf("User-Agent" to standardHeaders["User-Agent"]!!)
                this.quality = Qualities.Unknown.value
            }
        )
        return
    }

    // c) direct https links in unpacked scripts
    val directLink = Regex("""https?://[^\s"']+\.(m3u8|mp4|mpd)[^\s"']*""", RegexOption.IGNORE_CASE).find(scriptsText)?.value
    if (!directLink.isNullOrBlank()) {
        Log.d("HDCH", "Found direct media link => $directLink")
        callback.invoke(
            newExtractorLink("HDFC", "HDFC", directLink, INFER_TYPE) {
                this.referer = mainUrl
                this.headers = mapOf("User-Agent" to standardHeaders["User-Agent"]!!)
                this.quality = Qualities.Unknown.value
            }
        )
        return
    }

    // 5) If nothing found in inline/unpacked scripts, try external JS resources (movie.js etc)
    for (src in scriptSrcs) {
        try {
            val jsUrl = resolveUrl(src, iframeDoc.location())
            val jsText = app.get(jsUrl, referer = iframeDoc.location()).text
            val unpackedJs = runCatching { getAndUnpack(jsText) }.getOrNull() ?: jsText
            val decodedFromJs = extractVideoFromJs(unpackedJs)
            if (!decodedFromJs.isNullOrBlank()) {
                Log.d("HDCH", "Found video in external JS $jsUrl => $decodedFromJs")
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

    // 6) Try unpacking the combined inline scripts again more aggressively
    val aggressiveUnpacked = runCatching { getAndUnpack(inlineScripts + "\n" + scriptSrcs.joinToString("\n") { try { app.get(resolveUrl(it, iframeDoc.location()), referer = iframeDoc.location()).text } catch (_: Exception) { "" } }) }.getOrNull()
    if (!aggressiveUnpacked.isNullOrBlank()) {
        val final = extractVideoFromJs(aggressiveUnpacked)
        if (!final.isNullOrBlank()) {
            Log.d("HDCH", "Found video in aggressive unpack => $final")
            callback.invoke(
                newExtractorLink("HDFC", "HDFC", final, INFER_TYPE) {
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


 // Replace existing extractVideoFromJs(...) with this enhanced resolver
private fun extractVideoFromJs(jsText: String): String? {
    if (jsText.isBlank()) return null

    // 1) atob("base64") pattern
    val atobMatch = Regex("""atob\(\s*["']([A-Za-z0-9+/=]+)["']\s*\)""").find(jsText)?.groupValues?.getOrNull(1)
    if (!atobMatch.isNullOrBlank()) {
        runCatching { val dec = base64Decode(atobMatch); if (!dec.isNullOrBlank()) return dec }
    }

    // 2) file_link = "base64..."
    val b64 = Regex("""file_link\s*=\s*["']([A-Za-z0-9+/=]+)["']""").find(jsText)?.groupValues?.getOrNull(1)
    if (!b64.isNullOrBlank()) {
        val dec = runCatching { base64Decode(b64) }.getOrNull()
        if (!dec.isNullOrBlank()) return dec
    }

    // 3) direct assignment var s_xxx = "http..." or = 'http...'
    val varUrl = Regex("""(?:var|let|const)?\s*([A-Za-z0-9_]+)\s*=\s*["'](https?://[^"']+)["']""").find(jsText)?.groupValues?.getOrNull(2)
    if (!varUrl.isNullOrBlank()) return varUrl

    // 4) sources: [{file:"..."}]
    val sourcesFile = Regex("""sources\s*:\s*

\[\s*\{\s*file\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(jsText)?.groupValues?.getOrNull(1)
    if (!sourcesFile.isNullOrBlank()) return sourcesFile

    // 5) direct media links (m3u8/mp4/mpd)
    val direct = Regex("""https?://[^\s"']+\.(m3u8|mp4|mpd)[^\s"']*""", RegexOption.IGNORE_CASE).find(jsText)?.value
    if (!direct.isNullOrBlank()) return direct

    // 6) base64 token that decodes to http link
    val base64Token = Regex("""([A-Za-z0-9+/=]{40,})""").findAll(jsText).mapNotNull { it.groupValues.getOrNull(1) }.firstOrNull()
    if (!base64Token.isNullOrBlank()) {
        val decoded = runCatching { base64Decode(base64Token) }.getOrNull()
        if (!decoded.isNullOrBlank() && decoded.startsWith("http")) return decoded
    }

    return null
}


    private fun resolveUrl(url: String, base: String): String {
        return try {
            val baseUri = URI(base)
            baseUri.resolve(url).toString()
        } catch (e: Exception) {
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
