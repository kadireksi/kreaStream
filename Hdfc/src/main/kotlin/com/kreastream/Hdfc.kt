package com.kreastream

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI

class Hdfc : MainAPI() {
    override var name = "HDFC"
    override var mainUrl = "https://www.hdfilmcehennemi.la"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // Jackson JSON mapper
    private val mapper = com.fasterxml.jackson.databind.ObjectMapper().apply {
        registerModule(KotlinModule.Builder().build())
        configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    // --------------------------------------------------------------------- //
    // MAIN PAGE
    // --------------------------------------------------------------------- //
    override val mainPage = mainPageOf(
        "$mainUrl/load/page/1/home/" to "Yeni Filmler",
        "$mainUrl/load/page/1/home-series/" to "Yeni Diziler",
        "$mainUrl/load/page/1/imdb7/" to "IMDB 7+",
        "$mainUrl/load/page/1/mostLiked/" to "En Çok Beğenilen"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data.replace("/page/1/", "/page/$page/")
        val html = app.get(url).text
        val json = mapper.readValue<HDFC>(html)
        val doc = Jsoup.parse(json.html)

        val items = doc.select("a").mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val title = attr("title").ifEmpty { selectFirst("strong.poster-title")?.text() }?.trim() ?: return null
        val href = fixUrl(attr("href")) ?: return null
        val poster = fixUrl(selectFirst("img")?.attr("data-src") ?: selectFirst("img")?.attr("src"))
        val year = selectFirst(".poster-meta span")?.text()?.toIntOrNull()
        val tvType = if (href.contains("/dizi/") || href.contains("/series")) TvType.TvSeries else TvType.Movie

        return newMovieSearchResponse(title, href, tvType) {
            this.posterUrl = poster
            this.year = year
        }
    }

    // --------------------------------------------------------------------- //
    // SEARCH
    // --------------------------------------------------------------------- //
    override suspend fun search(query: String): List<SearchResponse> {
        val resp = app.get("$mainUrl/search?q=$query").parsedSafe<Results>() ?: return emptyList()
        return resp.results.mapNotNull { html ->
            val doc = Jsoup.parse(html)
            val title = doc.selectFirst("h4.title")?.text() ?: return@mapNotNull null
            val href = fixUrl(doc.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
            val poster = fixUrl(doc.selectFirst("img")?.attr("src"))
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
        }
    }

    // --------------------------------------------------------------------- //
    // LOAD (detail)
    // --------------------------------------------------------------------- //
    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val title = doc.selectFirst("strong.poster-title")?.text()
            ?: doc.selectFirst("h1.section-title")?.text()?.substringBefore(" izle") ?: return null
        val poster = fixUrl(doc.selectFirst("img.lazyload")?.attr("data-src")
            ?: doc.selectFirst("img")?.attr("src")) ?: return null
        val year = doc.selectFirst(".poster-meta span")?.text()?.toIntOrNull()
        val plot = doc.selectFirst(".popover-description, article.post-info-content > p")?.text()?.trim()
        val tags = doc.selectFirst(".popover-meta span:containsOwn(Türler)")?.parent()
            ?.ownText()?.split(",")?.map { it.trim() } ?: emptyList()

        val isSeries = url.contains("/dizi/") || url.contains("/series")
        if (isSeries) {
            val episodes = doc.select("div.seasons-tab-content a").mapNotNull {
                val name = it.selectFirst("h4")?.text() ?: return@mapNotNull null
                val href = fixUrl(it.attr("href")) ?: return@mapNotNull null
                val season = Regex("""(\d+)\. Sezon""").find(name)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val episode = Regex("""(\d+)\. Bölüm""").find(name)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                newEpisode(href) { this.name = name; this.season = season; this.episode = episode }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster; this.year = year; this.plot = plot; this.tags = tags
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster; this.year = year; this.plot = plot; this.tags = tags
        }
    }

    // --------------------------------------------------------------------- //
    // LINK EXTRACTION – newExtractorLink DSL ONLY
    // --------------------------------------------------------------------- //
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val doc = app.get(data).document

        // === 1. CLOSE CDN – from poster filename ===
        doc.selectFirst("img.poster-lazy, img.lazyload")?.attr("data-src")?.let { posterUrl ->
            val fileName = posterUrl.substringAfterLast("/")
            val master = "https://srv10.cdnimages1241.sbs/hls/$fileName/txt/master.txt"

            callback(
                newExtractorLink {
                    source = name
                    name = "Close (1080p)"
                    url = master
                    referer = mainUrl
                    type = ExtractorLinkType.M3U8
                    quality = Qualities.Unknown.value
                }
            )
        }

        // === 2. RAPIDRAME JW-PLAYER (tokenised) ===
        doc.select("button.alternative-link[data-video]").forEach { btn ->
            val videoId = btn.attr("data-video")
            val sourceName = btn.text().trim() + " (Rapidrame)"

            val apiResp = app.get(
                "$mainUrl/video/$videoId/",
                headers = mapOf("X-Requested-With" to "fetch"),
                referer = data
            ).text

            val iframe = Regex("""data-src=["']([^"']+)""").find(apiResp)?.groupValues?.get(1)
                ?: return@forEach

            val iframeDoc = app.get(iframe, referer = data).document

            // --- Subtitles ---
            iframeDoc.select("track[kind=captions]").forEach { track ->
                val lang = when (track.attr("srclang")) {
                    "tr" -> "Türkçe"
                    "en" -> "English"
                    "fr" -> "Français"
                    "pt" -> "Português"
                    else -> track.attr("label") ?: track.attr("srclang").uppercase()
                }
                val subUrl = URI(iframe).resolve(track.attr("src")).toString()
                subtitleCallback(SubtitleFile(lang, subUrl))
            }

            // --- JW Player JS (unpacked) ---
            val script = iframeDoc.selectFirst("script:containsData(playerInstance)")?.data()
                ?: return@forEach
            val unpacked = getAndUnpack(script)

            Regex("""file\s*:\s*["']([^"']+)["']""").find(unpacked)?.groupValues?.get(1)?.let { hlsUrl ->
                callback(
                    newExtractorLink {
                        source = name
                        name = sourceName
                        url = hlsUrl
                        referer = iframe
                        type = ExtractorLinkType.M3U8
                        quality = Qualities.Unknown.value
                        headers = mapOf("Origin" to mainUrl)
                    }
                )
            }
        }

        return true
    }

    // --------------------------------------------------------------------- //
    // JSON MODELS
    // --------------------------------------------------------------------- //
    data class Results(@JsonProperty("results") val results: List<String>)
    data class HDFC(@JsonProperty("html") val html: String)
}
