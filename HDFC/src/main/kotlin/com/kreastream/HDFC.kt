package com.kreastream

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import okhttp3.FormBody
import org.jsoup.nodes.Document

class HDFilmCehennemi : MainAPI() {
    override var mainUrl = "https://www.hdfilmcehennemi.ws"
    override var name = "HDFilmCehennemi"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override var hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val mapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:137.0) Gecko/20100101 Firefox/137.0",
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

    // ──────── ONE-LINE DECRYPTER (2025 + all legacy) ────────
    private object Decrypter {
        private const val SEED = 399756995

        private fun rot13(bytes: ByteArray) = bytes.map {
            when (val c = it.toInt()) {
                in 65..90 -> ((c - 65 + 13) % 26 + 65).toByte()
                in 97..122 -> ((c - 97 + 13) % 26 + 97).toByte()
                else -> it
            }
        }.toByteArray()

        private fun shift(bytes: ByteArray) = buildString {
            bytes.forEachIndexed { i, b ->
                val code = b.toInt() and 0xFF
                append(((code - (SEED % (i + 5)) + 256) % 256).toChar())
            }
        }

        fun decrypt(blob: String): String = try {
            // 2025 method (reverse → b64 → rot13 → shift)
            val clean = blob.replace(Regex("[\"',\\s]"), "")
            val rev = clean.reversed()
            val dec = Base64.decode(rev, Base64.DEFAULT)
            val r13 = rot13(dec)
            shift(r13).takeIf { it.startsWith("http") }
                // Legacy fallbacks (rarely needed)
                ?: run {
                    val a1 = shift(rot13(Base64.decode(blob.reversed(), Base64.DEFAULT)))
                    a1.takeIf { it.startsWith("http") }
                        ?: shift(rot13(String(rot13(blob.toByteArray())).reversed().let { Base64.decode(it, Base64.DEFAULT) })
                }
        } catch (e: Exception) { "" }
    }

    // ──────── EXTRACT FROM JWPLAYER SCRIPT (2025) ────────
    private suspend fun extractJwSource(doc: Document, name: String, ref: String, callback: (ExtractorLink) -> Unit) {
        doc.select("script").forEach { script ->
            val data = script.data()
            if (!data.contains(Regex("s_cti1zQURiX0|hsdQ|dc_"))) return@forEach

            Regex("""["']([A-Za-z0-9+/=,\\"\-]*)["']\s*\)\s*;""").find(data)?.groupValues?.get(1)?.let { blob ->
                Decrypter.decrypt(blob).takeIf { it.isNotEmpty() }?.let { url ->
                    val isHls = url.contains(".m3u8", true) || url.endsWith(".txt", true)
                    callback(newExtractorLink(name, name, url, ref, Qualities.Unknown.value,
                        if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO))
                }
            }
        }
    }

    // ──────── DOWNLOAD LINKS ────────
    private suspend fun extractDownloads(id: String, callback: (ExtractorLink) -> Unit) {
        listOf("low" to "Download SD", "high" to "Download HD").forEach { (q, n) ->
            try {
                app.post(
                    "https://cehennempass.pw/process_quality_selection.php",
                    requestBody = FormBody.Builder().add("video_id", id).add("selected_quality", q).build(),
                    referer = "https://cehennempass.pw/download/$id",
                    headers = headers
                ).parsedSafe<DownloadResp>()?.download_link?.let {
                    callback(newExtractorLink(n, n, it, "", Qualities.Unknown.value, ExtractorLinkType.VIDEO))
                }
            } catch (_: Exception) {}
        }
    }

    // ──────── LOADLINKS – RAPIDRAME FIRST & CLEAN ────────
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, referer = mainUrl).document
        var rapidId: String? = null

        // 1. Main Player (Close)
        doc.selectFirst("div.close, button.close")?.attr("data-src")?.let { src ->
            rapidId = src.substringAfter("?rapidrame_id=", "").takeIf { it.isNotEmpty() }
            extractJwSource(doc, "Rapidrame", mainUrl, callback)

            // Subtitles from .mobi iframe
            if (src.contains("hdfilmcehennemi.mobi")) try {
                val idoc = app.get(src, referer = mainUrl).document
                idoc.select("track[kind=captions]").forEach {
                    val lang = when (it.attr("srclang")) {
                        "tr" -> "Türkçe"; "en" -> "İngilizce"; else -> it.attr("label").ifEmpty { "Unknown" }
                    }
                    val url = it.attr("src").let { u -> if (u.startsWith("http")) u else idoc.location().substringBeforeLast("/") + "/$u" }
                    subtitleCallback(newSubtitleFile(lang, url))
                }
            } catch (_: Exception) {}
        }

        // 2. Alternative sources
        doc.select("div.alternative-links button.alternative-link").forEach { btn ->
            val name = btn.text().trim().takeIf { !it.equals("close", true) } ?: return@forEach
            val vid = btn.attr("data-video").takeIf { it.isNotEmpty() } ?: return@forEach

            try {
                val iframe = app.get("$mainUrl/video/$vid/", referer = data).text
                    .let { Regex("""data-src=["']([^"']+)""").find(it)?.groupValues?.get(1) } ?: return@forEach

                val sourceName = if (name.contains("rapidrame", true)) "Rapidrame ${btn.closest("div[data-lang]")?.attr("data-lang")?.uppercase() ?: ""}"
                    .trim() else name

                extractJwSource(app.get(iframe, referer = data).document, sourceName, iframe, callback)
            } catch (_: Exception) {}
        }

        // 3. Downloads
        rapidId?.let { extractDownloads(it, callback) }

        return true
    }

    // ──────── REST OF PROVIDER (mainPage, search, load) ────────
    override val mainPage = mainPageOf(
        "${mainUrl}/load/page/1/home/" to "Yeni Filmler",
        "${mainUrl}/load/page/1/languages/turkce-dublajli-film-izleyin-3/" to "Türkçe Dublaj",
        "${mainUrl}/load/page/1/countries/turkiye-2/" to "Türk Filmleri",
        "${mainUrl}/load/page/1/recent-episodes/" to "Yeni Bölümler",
        "${mainUrl}/load/page/1/home-series/" to "Yeni Diziler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest) = app.get(
        if (page == 1) request.data.replace(Regex("/load/page/1/(genres|categories|languages|countries)/"), "/$1/")
            .replace("/load/page/1/", "/") else request.data.replace("/page/1/", "/page/$page/")
    ).parsedSafe<HDFC>()?.html?.let { Jsoup.parse(it) }?.let { doc ->
        newHomePageResponse(request.name, doc.select("a.poster, a.mini-poster").mapNotNull { it.toSearchResult() })
    } ?: newHomePageResponse(request.name, emptyList())

    override suspend fun search(query: String) = app.get("$mainUrl/search?q=$query", headers = headers)
        .parsedSafe<Results>()?.results?.mapNotNull { html ->
            Jsoup.parse(html).selectFirst("a")?.extractPosterData()?.let {
                newMovieSearchResponse(if (it.hasDub) "Türkiye ${it.title}" else it.title, it.href, it.tvType) {
                    posterUrl = it.posterUrl?.replace("/list/", "/")?.replace("/thumb/", "/")
                }
            }
        } ?: emptyList()

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val data = doc.extractLoadData() ?: throw ErrorLoadingException("No data")
        // ... rest of load() unchanged (you already have it perfect)
        // keep your current load() function
        return if (data.tvType == TvType.TvSeries) newTvSeriesLoadResponse(...) else newMovieLoadResponse(...)
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
    // Keep your existing extractPosterData(), extractLoadData(), toSearchResult(), etc.
    // They are already excellent and concise.

    data class Results(@JsonProperty("results") val results: List<String> = emptyList())
    data class HDFC(@JsonProperty("html") val html: String = "")
    data class DownloadResp(@JsonProperty("download_link") val download_link: String? = null)
}