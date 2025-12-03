package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.getAndUnpack
import org.jsoup.nodes.Element

class AC : MainAPI() {
    override var mainUrl = "https://www.youtube.com"
    override var name = "Abdullah Çiftçi"
    override val supportedTypes = setOf(TvType.Movie, TvType.LiveStream)
    override val hasMainPage = true

    private val channelUrl = "https://www.youtube.com/@abdullahciftcib/videos"

    override val mainPage = mainPageOf(
        channelUrl to "Latest Videos"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data).document
        val items = document.select("ytd-rich-grid-row ytd-rich-item-renderer").mapNotNull { element ->
            val link = element.selectFirst("a#thumbnail")?.attr("href") ?: return@mapNotNull null
            val title = element.selectFirst("#video-title")?.text()?.trim() ?: return@mapNotNull null
            val thumbnail = element.selectFirst("img")?.attr("src")
            val duration = element.selectFirst("span#text")?.text()?.trim()

            newMovieLoadResponse(title, mainUrl + link, TvType.Movie, mainUrl + link) {
                this.posterUrl = thumbnail
                this.duration = duration?.let { parseDuration(it) }
            }
        }

        return HomePageResponse(listOf(HomePageList(request.name, items)))
    }

    private fun parseDuration(duration: String): Int {
        return try {
            val parts = duration.split(":").map { it.toInt() }
            when (parts.size) {
                2 -> parts[0] * 60 + parts[1]
                3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
                else -> 0
            }
        } catch (e: Exception) {
            0
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val videoId = url.substringAfter("v=").substringBefore("&")
        return newMovieLoadResponse(
            "Abdullah Çiftçi Video",
            url,
            TvType.Movie,
            "https://www.youtube.com/watch?v=$videoId"
        ) {
            posterUrl = "https://img.youtube.com/vi/$videoId/maxresdefault.jpg"
            plot = "Watch content from Abdullah Çiftçi's YouTube channel"
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // This will use CloudStream's built-in YouTube extractor
        return false // Return false to let CloudStream handle it with default extractor
    }
}

@Suppress("unused")
fun getPlugin(): ACPlugin {
    return ACPlugin()
}