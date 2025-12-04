package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class AC : MainAPI() {

    override var mainUrl = "https://m.youtube.com"
    override var name = "Abdullah Çiftçi"
    override val supportedTypes = setOf(TvType.Movie, TvType.Live)
    override val hasMainPage = true

    private val channelUrl = "$mainUrl/@abdullahciftcib/videos"

    override val mainPage = mainPageOf(
        channelUrl to "Latest Videos",
        "$mainUrl/@abdullahciftcib/streams" to "Live Streams",
        "$mainUrl/@abdullahciftcib/shorts" to "Shorts",
        "$mainUrl/@abdullahciftcib/playlists" to "Playlists"
    )

    private fun pageUrl(url: String, page: Int): String {
        return if (page > 1) "$url&page=$page" else url
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(pageUrl(request.data, page)).document

        val videos = doc.select("ytd-video-renderer, ytd-rich-item-renderer").mapNotNull { parseVideo(it) }
        val hasMore = doc.toString().contains("continuation")

        return newHomePageResponse(request.name, videos, hasMore)
    }

    private fun parseVideo(el: Element): SearchResponse? {
        val href = el.selectFirst("a#thumbnail")?.attr("href") ?: return null
        var title = el.selectFirst("#video-title")?.text()?.trim() ?: "Video"
        val img = el.selectFirst("img")?.attr("src")

        // Shorts detection
        if (href.contains("/shorts/")) title = "🎬 SHORT · $title"

        return newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
            posterUrl = img
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/@abdullahciftcib/search?query=${fixUrlEncoded(query)}"
        val doc = app.get(searchUrl).document
        return doc.select("ytd-video-renderer, ytd-rich-item-renderer").mapNotNull { parseVideo(it) }
    }

    override suspend fun load(url: String): LoadResponse {
        val videoId = extractId(url)

        val rec = getMainPage(1, MainPageRequest(channelUrl, "Recommended"))

        return newMovieLoadResponse(
            "Abdullah Çiftçi",
            url,
            TvType.Movie,
            "$mainUrl/watch?v=$videoId"
        ) {
            posterUrl = "https://img.youtube.com/vi/$videoId/maxresdefault.jpg"
            plot = "Video from Abdullah Çiftçi YouTube channel"
            addActors(listOf(Actor("Abdullah Çiftçi")))
            recommendations = rec.items.take(10)
        }
    }

    /** Cloudstream 4 extractor-compatible */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val videoId = extractId(data)

        val sources = listOf(
            "https://inv.nadeko.net",
            "https://yewtu.be",
            "https://invidious.snopyta.org"
        )

        for (i in sources) {
            try {
                val api = "$i/api/v1/videos/$videoId"
                val response = app.get(api).parsedSafe<Invidious>() ?: continue

                response.formatStreams?.sortedByDescending { guessQuality(it.quality) }?.forEach { fmt ->
                    callback(
                        ExtractorLink(
                            name,
                            name,
                            fmt.url ?: return@forEach,
                            i,
                            guessQuality(fmt.quality),
                            false
                        )
                    )
                }

                return true
            } catch (_: Exception) {}
        }

        return fallbackExtractor(videoId, callback)
    }

    private fun fallbackExtractor(videoId: String, cb: (ExtractorLink) -> Unit): Boolean {
        val qualities = listOf(144, 360, 480, 720, 1080)

        qualities.forEach { q ->
            cb(
                ExtractorLink(
                    name,
                    name,
                    "https://rr.youtube.com/videoplayback?id=$videoId&itag=${itag(q)}",
                    mainUrl,
                    q,
                    false
                )
            )
        }
        return true
    }

    private fun extractId(url: String): String {
        return url.substringAfter("v=").substringBefore("&").substringAfter("/shorts/")
    }

    private fun guessQuality(q: String?): Int {
        return when {
            q?.contains("144") == true -> Qualities.P144.value
            q?.contains("360") == true -> Qualities.P360.value
            q?.contains("480") == true -> Qualities.P480.value
            q?.contains("720") == true -> Qualities.P720.value
            q?.contains("1080") == true -> Qualities.P1080.value
            else -> Qualities.Unknown.value
        }
    }

    private fun itag(q: Int) = when (q) {
        144 -> 160
        360 -> 134
        480 -> 135
        720 -> 136
        1080 -> 137
        else -> 134
    }

    // Invidious Model
    private data class Invidious(
        val formatStreams: List<Format>? = null
    )

    private data class Format(val url: String?, val quality: String?)
}

@Suppress("unused")
fun getPlugin() = AC()
