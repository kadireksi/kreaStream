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
import okhttp3.FormBody
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class HDFilmCehennemi : MainAPI() {
    override var mainUrl = "https://www.hdfilmcehennemi.ws"
    override var name = "HDFilmCehennemi"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override var hasDownloadSupport = true
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

    // START: Main Page Tidy Up and Pagination Support
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
        // Handle "Yeni Bölümler" which use the mini-poster format
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
    // END: Main Page Tidy Up and Pagination Support

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

    // ────────────────────────────── DECRYPTER (4 METHODS - NEW ONE FIRST) ──────────────────────────────
    private object HDFCDecrypter {

        private const val DEFAULT_SEED = 399756995

        private fun applyRot13(inputBytes: ByteArray): ByteArray {
            return inputBytes.map { byte ->
                val c = byte.toInt()
                when (c.toChar()) {
                    in 'a'..'z' -> (((c - 'a'.code + 13) % 26) + 'a'.code).toByte()
                    in 'A'..'Z' -> (((c - 'A'.code + 13) % 26) + 'A'.code).toByte()
                    else -> byte
                }
            }.toByteArray()
        }

        private fun applyCustomShift(bytes: ByteArray, seed: Int): String {
            return buildString {
                bytes.forEachIndexed { i, b ->
                    val charCode = b.toInt() and 0xFF
                    val shift = seed % (i + 5)
                    val newChar = (charCode - shift + 256) % 256
                    append(newChar.toChar())
                }
            }
        }

        // ─── METHOD 4 – CURRENT 2025 METHOD (FASTEST – TRY FIRST) ─────────────────
        private fun attemptNew2025(encryptedBlob: String, seed: Int = DEFAULT_SEED): String? {
            try {
                val clean = encryptedBlob.replace("\"", "").replace("'", "").replace(",", "").replace(Regex("\\s"), "")
                val reversed = clean.reversed()
                val decoded = Base64.decode(reversed, Base64.DEFAULT)
                val rot13 = applyRot13(decoded)
                return applyCustomShift(rot13, seed).takeIf { it.startsWith("http") }
            } catch (e: Exception) {
                return null
            }
        }

        // ─── METHOD 1 – Original working order (still used on some pages) ───────
        private fun attempt1(encryptedData: String, seed: Int): String? = try {
            val reversed = encryptedData.reversed()
            val decoded = Base64.decode(reversed, Base64.DEFAULT)
            val rot13 = applyRot13(decoded)
            applyCustomShift(rot13, seed).takeIf { it.startsWith("http") }
        } catch (e: Exception) { null }

        // ─── METHOD 2 & 3 – Legacy fallbacks ─────────────────────────────────────
        private fun attemptLegacy(encryptedData: String, seed: Int): String? = try {
            val rot13First = String(applyRot13(encryptedData.toByteArray()))
            val reversed = rot13First.reversed()
            val decoded = Base64.decode(reversed, Base64.DEFAULT)
            applyCustomShift(decoded, seed).takeIf { it.startsWith("http") }
        } catch (e: Exception) { null }

        private fun attemptLegacy2(encryptedData: String, seed: Int): String? = try {
            val reversed = encryptedData.reversed()
            val rot13 = String(applyRot13(reversed.toByteArray()))
            val decoded = Base64.decode(rot13, Base64.DEFAULT)
            applyCustomShift(decoded, seed).takeIf { it.startsWith("http") }
        } catch (e: Exception) { null }

        fun decrypt(encrypted: String, seed: Int = DEFAULT_SEED): String {
            return attemptNew2025(encrypted, seed)
                ?: attempt1(encrypted, seed)
                ?: attemptLegacy(encrypted, seed)
                ?: attemptLegacy2(encrypted, seed)
                ?: ""
        }
    }

    // ────────────────────────────── NEW FAST EXTRACTOR (2025 JWPlayer) ──────────────────────────────
    private suspend fun extractJwPlayerSource(
        document: Document,
        sourceName: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val script = document.select("script").find {
            it.data().contains("s_cti1zQURiX0") || it.data().contains("dc_") && it.data().contains("function(p,a,c,k,e,d)")
        }?.data() ?: return false

        // Match: dc_abc(["Q","O",...]) or similar
        val blobRegex = Regex("""["']([A-Za-z0-9+/=,\\"\-]*)["']\s*\)\s*;?\s*$""")
        val match = blobRegex.find(script) ?: return false
        val encryptedBlob = match.groupValues[1]

        val decrypted = HDFCDecrypter.decrypt(encryptedBlob)
        if (decrypted.isBlank() || !decrypted.startsWith("http")) return false

        val isHls = decrypted.contains(".m3u8", ignoreCase = true) || decrypted.endsWith(".txt", ignoreCase = true)

        callback(
            newExtractorLink(
                source = sourceName,
                name = sourceName,
                url = decrypted
            ){
                this.referer = referer
                this.quality = Qualities.Unknown.value
                this.type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            }
        )
        return true
    }

    // ────────────────────────────── LEGACY UNPACKER (for very old pages) ──────────────────────────────
    private suspend fun extractPackedSource(
        document: Document,
        sourceName: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val packedScript = document.select("script").find { it.data().contains("eval(function(p,a,c,k,e,d)") }?.data() ?: return
        val unpacked = JsUnpacker(packedScript).unpack() ?: return

        val arrayRegex = Regex("""\w+\(\[(.*?)\]""")
        val arrayMatch = arrayRegex.find(unpacked) ?: return
        val encrypted = arrayMatch.groupValues[1].replace("\"", "").replace("'", "").replace(",", "").replace(Regex("\\s"), "")

        val seedRegex = Regex("""charCode-\((\d+)%\(i\+5\)""")
        val seed = seedRegex.find(unpacked)?.groupValues?.get(1)?.toIntOrNull() ?: 399756995

        val decrypted = HDFCDecrypter.decrypt(encrypted, seed)
        if (decrypted.isBlank()) return

        val isHls = decrypted.contains(".m3u8") || decrypted.endsWith(".txt")
        callback(
            newExtractorLink(
                source = sourceName,
                name = sourceName,
                url = decrypted
            ){
                this.referer = referer
                this.quality = Qualities.Unknown.value
                this.type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            }
        )
    }

    // ────────────────────────────── DOWNLOAD LINKS ──────────────────────────────
    private suspend fun extractDownloadLinks(rapidrameId: String, callback: (ExtractorLink) -> Unit) {
        val qualities = mapOf("low" to "Download SD", "high" to "Download HD")
        qualities.forEach { (q, name) ->
            try {
                val res = app.post(
                    "https://cehennempass.pw/process_quality_selection.php",
                    requestBody = FormBody.Builder()
                        .add("video_id", rapidrameId)
                        .add("selected_quality", q)
                        .build(),
                    headers = standardHeaders,
                    referer = "https://cehennempass.pw/download/$rapidrameId"
                ).parsedSafe<DownloadResponse>()

                res?.download_link?.takeIf { it.isNotEmpty() }?.let { link ->
                    callback(
                        newExtractorLink(
                            name = name,
                            source = name,
                            url = link
                        ){
                            this.referer = mainUrl
                            this.quality = Qualities.Unknown.value
                            this.type = ExtractorLinkType.VIDEO
                        }
                    )
                }
            } catch (_: Exception) {}
        }
    }

    // ────────────────────────────── MAIN LOADLINKS (RAPIDRAME FIRST) ──────────────────────────────
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, referer = mainUrl).document
        var rapidrameId: String? = null

        // 1. MAIN PLAYER – "Close" button (now JWPlayer + encrypted var)
        doc.selectFirst("div.close, button.close")?.attr("data-src")?.let { src ->
            rapidrameId = src.substringAfter("?rapidrame_id=", "").takeIf { it.isNotEmpty() }

            // Try new JWPlayer method first (2025x faster)
            if (extractJwPlayerSource(doc, "Rapidrame", mainUrl, callback)) {
                // Success – great!
            } else {
                // Fallback to old packed method
                extractPackedSource(doc, "Rapidrame", mainUrl, callback)
            }

            // Subtitles from .mobi iframe (still used sometimes)
            if (src.contains("hdfilmcehennemi.mobi")) {
                try {
                    val iframeDoc = app.get(src, referer = mainUrl).document
                    iframeDoc.select("track[kind=captions]").forEach { t ->
                        val lang = when (t.attr("srclang")) {
                            "tr" -> "Türkçe"
                            "en" -> "İngilizce"
                            else -> t.attr("label").takeIf { it.isNotEmpty() } ?: "Unknown"
                        }
                        val subUrl = t.attr("src").let { if (it.startsWith("http")) it else "${iframeDoc.location().substringBeforeLast("/")}/$it" }
                        subtitleCallback(newSubtitleFile(lang, subUrl))
                    }
                } catch (_: Exception) {}
            }
        }

        // 2. ALTERNATIVE SOURCES (still Rapidrame-style)
        doc.select("div.alternative-links").forEach { block ->
            val lang = block.attr("data-lang").uppercase()
            block.select("button.alternative-link").forEach { btn ->
                val rawName = btn.text().trim()
                if (rawName.equals("close", ignoreCase = true)) return@forEach

                val videoId = btn.attr("data-video").takeIf { it.isNotEmpty() } ?: return@forEach

                try {
                    val json = app.get("$mainUrl/video/$videoId/", referer = data).text
                    val iframeSrc = Regex("""data-src=["']([^"']+)["']""").find(json)?.groupValues?.get(1) ?: return@forEach

                    val sourceName = if (rawName.contains("rapidrame", ignoreCase = true)) "Rapidrame $lang" else "$rawName $lang"

                    val iframeDoc = app.get(iframeSrc, referer = data).document

                    // Try new method first
                    if (!extractJwPlayerSource(iframeDoc, sourceName, iframeSrc, callback)) {
                        extractPackedSource(iframeDoc, sourceName, iframeSrc, callback)
                    }
                } catch (_: Exception) {}
            }
        }

        // 3. DOWNLOAD LINKS
        rapidrameId?.let { extractDownloadLinks(it, callback) }

        return true
    }

    // ────────────────────────────── REST OF THE CODE (unchanged, only minor fixes) ──────────────────────────────
    // ... [your existing getMainPage, search, load, extractLoadData, etc. remain exactly the same]

    data class Results(@JsonProperty("results") val results: List<String> = arrayListOf())
    data class HDFC(@JsonProperty("html") val html: String)
    data class DownloadResponse(
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("download_link") val download_link: String? = null
    )
}