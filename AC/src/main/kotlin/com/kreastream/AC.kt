package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element

class AC : MainAPI() {

    override var mainUrl = "https://m.youtube.com"
    override var name = "Abdullah Çiftçi"
    override val supportedTypes = setOf(TvType.Movie, TvType.Live, TvType.Clip)
    override val hasMainPage = true

    private val channelUrl = "$mainUrl/@abdullahciftcib/videos"

    override val mainPage = mainPageOf(
        channelUrl to "Latest Videos",
        "$mainUrl/@abdullahciftcib/streams" to "Live Streams",
        "$mainUrl/@abdullahciftcib/shorts" to "Shorts",
        "$mainUrl/@abdullahciftcib/playlists" to "Playlists"
    )

    /** Pagination fix: appends &page=X if exists **/
    private fun buildPagedUrl(url: String, page: Int): String {
        return if (page > 1) "$url&page=$page" else url
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = buildPagedUrl(request.data, page)
        val document = app.get(url).document

        val items = document.select("ytd-rich-item-renderer, ytd-video-renderer").mapNotNull { parseVideo(it) }

        val hasNextPage = document.toString().contains("continuation")
        return newHomePageResponse(request.name, items, hasNextPage)
    }

    private fun parseVideo(element: Element): SearchResponse? {
        val link = element.selectFirst("a#thumbnail")?.attr("href") ?: return null
        val title = element.selectFirst("#video-title")?.text()?.trim() ?: return null
        val thumbnail = element.selectFirst("img")?.attr("src")

        // Detect shorts based on URL format
        val type = if (link.contains("/shorts/")) TvType.Clip else TvType.Movie

        return newMovieSearchResponse(title, mainUrl + link, type) {
            this.posterUrl = thumbnail
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/@abdullahciftcib/search?query=${app.encode(query)}"
        val document = app.get(searchUrl).document
        return document.select("ytd-video-renderer, ytd-rich-item-renderer").mapNotNull { parseVideo(it) }
    }

    override suspend fun load(url: String): LoadResponse {
        val videoId = extractVideoId(url)

        val recommended = getMainPage(1, MainPageRequest(channelUrl, "Recommended"))

        return newMovieLoadResponse(
            "Abdullah Çiftçi",
            url,
            TvType.Movie,
            "$mainUrl/watch?v=$videoId"
        ) {
            posterUrl = "https://img.youtube.com/vi/$videoId/maxresdefault.jpg"
            plot = "Content from Abdullah Çiftçi official YouTube channel"

            addActors(listOf(Actor("Abdullah Çiftçi")))

            this.recommendations = recommended.items.take(10)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val id = extractVideoId(data)

        return youtubeExtractor(id, subtitleCallback, callback)
    }

    /** Best extractor with adaptive + fallback quality */
    private suspend fun youtubeExtractor(
        videoId: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val api = "https://inv.nadeko.net/api/v1/videos/$videoId"
        val res = app.get(api).parsedSafe<InvidiousResponse>()

        if (res?.formatStreams != null) {
            val list = (res.formatStreams + res.adaptiveFormats).filter { it.url != null }

            list.sortedByDescending { getQuality(it.quality) }.forEach { f ->
                newExtractorLink(
                    name,
                    f.url!!,
                    name
                ) {
                    referer = mainUrl
                    quality = getQuality(f.quality)
                }.let(callback)
            }

            res.subtitleTracks?.forEach { sub ->
                subtitleCallback(SubtitleFile(sub.label ?: "Subtitle", api.removeSuffix("/api/v1/videos/$videoId") + (sub.url ?: "")))
            }

            return true
        }

        return addFallbackVideo(videoId, callback)
    }

    /** yt-dlp style fallback */
    private fun addFallbackVideo(videoId: String, callback: (ExtractorLink) -> Unit): Boolean {
        listOf(144, 360, 480, 720, 1080).forEach { q ->
            newExtractorLink(
                name,
                "https://rr.youtube.com/videoplayback?id=$videoId&itag=${itagFor(q)}",
                name
            ) {
                referer = mainUrl
                quality = q
            }.let(callback)
        }
        return true
    }

    private fun extractVideoId(url: String) =
        url.substringAfter("v=").substringBefore("&").substringAfter("/shorts/")

    private fun getQuality(name: String?): Int {
        return when {
            name?.contains("144") == true -> Qualities.P144.value
            name?.contains("360") == true -> Qualities.P360.value
            name?.contains("480") == true -> Qualities.P480.value
            name?.contains("720") == true -> Qualities.P720.value
            name?.contains("1080") == true -> Qualities.P1080.value
            else -> Qualities.Unknown.value
        }
    }

    private fun itagFor(q: Int) = when (q) {
        144 -> 160
        360 -> 134
        480 -> 135
        720 -> 136
        1080 -> 137
        else -> 134
    }

    // ----- Invidious Data Models -----

    private data class InvidiousResponse(
        val formatStreams: List<VideoFormat> = emptyList(),
        val adaptiveFormats: List<VideoFormat> = emptyList(),
        val subtitleTracks: List<SubtitleTrack>? = null
    )

    private data class VideoFormat(val url: String?, val quality: String?)
    private data class SubtitleTrack(val label: String?, val url: String?)
}

@Suppress("unused")
fun getPlugin(): AC = AC()
