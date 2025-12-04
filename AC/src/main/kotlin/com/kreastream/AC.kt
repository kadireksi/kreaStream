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

    private fun paged(url: String, page: Int): String {
        return if (page > 1) "$url&page=$page" else url
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(paged(request.data, page)).document

        val items = doc.select("ytd-video-renderer, ytd-rich-item-renderer").mapNotNull { toSearch(it) }

        val hasNext = doc.toString().contains("continuation")

        return newHomePageResponse(request.name, items, hasNext)
    }

    private fun toSearch(el: Element): SearchResponse? {
        val href = el.selectFirst("a#thumbnail")?.attr("href") ?: return null
        var title = el.selectFirst("#video-title")?.text()?.trim() ?: "Video"
        val img = el.selectFirst("img")?.attr("src")

        // Shorts label
        if (href.contains("/shorts/")) title = "🎬 SHORT · $title"

        return newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
            posterUrl = img
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = query.replace(" ", "+")
        val url = "$mainUrl/@abdullahciftcib/search?query=$encoded"

        val doc = app.get(url).document
        return doc.select("ytd-video-renderer, ytd-rich-item-renderer").mapNotNull { toSearch(it) }
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
            plot = "Content from Abdullah Çiftçi Official YouTube channel."

            // Fix recommendations type
            recommendations = rec.items.flatMap { it.items }.take(10)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val id = extractId(data)

        val apiList = listOf(
            "https://inv.nadeko.net",
            "https://yewtu.be",
            "https://invidious.snopyta.org"
        )

        for (base in apiList) {
            try {
                val json = app.get("$base/api/v1/videos/$id").parsedSafe<Invidious>() ?: continue

                json.formatStreams?.sortedByDescending { q(it.quality) }?.forEach { fmt ->
                    newExtractorLink(
                        name,
                        fmt.url ?: return@forEach,
                        name,
                        referer = mainUrl,
                        quality = q(fmt.quality)
                    ).let(callback)
                }

                return true
            } catch (_: Exception) {}
        }

        return fallback(id, callback)
    }

    private fun fallback(videoId: String, callback: (ExtractorLink) -> Unit): Boolean {
        val qualities = listOf(144, 360, 480, 720, 1080)

        qualities.forEach { q ->
            newExtractorLink(
                name,
                "https://rr.youtube.com/videoplayback?id=$videoId&itag=${itag(q)}",
                name,
                referer = mainUrl,
                quality = q
            ).let(callback)
        }
        return true
    }

    private fun extractId(url: String): String =
        url.substringAfter("v=").substringBefore("&").substringAfter("/shorts/")

    private fun itag(q: Int) = when (q) {
        144 -> 160
        360 -> 134
        480 -> 135
        720 -> 136
        1080 -> 137
        else -> 134
    }

    private fun q(q: String?): Int = when {
        q?.contains("144") == true -> Qualities.P144.value
        q?.contains("360") == true -> Qualities.P360.value
        q?.contains("480") == true -> Qualities.P480.value
        q?.contains("720") == true -> Qualities.P720.value
        q?.contains("1080") == true -> Qualities.P1080.value
        else -> Qualities.Unknown.value
    }

    private data class Invidious(
        val formatStreams: List<Format>? = null
    )

    private data class Format(val url: String?, val quality: String?)
}

@Suppress("unused")
fun getPlugin() = AC()
