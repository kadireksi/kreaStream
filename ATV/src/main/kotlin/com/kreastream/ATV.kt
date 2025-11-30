package com.kreastream

import android.content.Context
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.coroutines.delay
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI

class ATV : MainAPI() {
    override var mainUrl = "https://www.atv.com.tr"
    override var name = "ATV Türkiye"
    override val lang = "tr"  // Turkish
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries)
    override val hasDownloadSupport = false  // Enable if adding download logic

    override val mainPage = mainPageOf(
        "${mainUrl}/diziler" to "Güncel Diziler",  // Current series list
        "${mainUrl}/eski-diziler" to "Arşiv Diziler"  // Archive series list
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = request.url
        val doc = app.get(url).document  // Fetch and parse HTML

        val home = mutableListOf<HomePageList>()
        if (url.contains("/diziler")) {
            home.add(
                HomePageList(
                    "Güncel Diziler",
                    doc.select("div.series-list > div.card, .dizi-item, [class*='series'], [class*='dizi']").mapNotNull { element ->
                        element.toSearchResponse()  // Convert to HomePageList item
                    }
                )
            )
        } else if (url.contains("/eski-diziler")) {
            home.add(
                HomePageList(
                    "Arşiv Diziler",
                    doc.select("div.series-list > div.card, .dizi-item, [class*='series'], [class*='dizi']").mapNotNull { element ->
                        element.toSearchResponse()
                    }
                )
            )
        }

        return HomePageResponse(home)
    }

    private fun Element.toSearchResponse(): HomePageListItem? {
        // Adjust selectors based on actual HTML:
        // - Title: Often in <h3> or <a class="title">
        // - Image: <img src="...">
        // - Link: <a href="...">
        val titleElement = this.selectFirst("h3 a, .title a, a[href*='/dizi/']") ?: return null
        val title = titleElement.text().trim().ifEmpty { this.text().trim() }
        val posterUrl = this.selectFirst("img")?.attr("src")?.let { fixUrl(it) } ?: ""
        val link = fixUrl(titleElement.attr("href"))

        return HomePageListItem(
            name = title,
            url = link,
            image = posterUrl,
            type = TvType.TvSeries
        )
    }

    // Search function (optional, for querying series)
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/arama?q=$query"  // ATV has a search endpoint; adjust if needed
        val doc = app.get(url).document
        return doc.select("div.search-result > div.card, .result-item").mapNotNull { element ->
            val title = element.selectFirst("h3 a, .title")?.text() ?: return@mapNotNull null
            val poster = element.selectFirst("img")?.attr("src")?.let { fixUrl(it) } ?: ""
            val link = fixUrl(element.selectFirst("a")?.attr("href") ?: return@mapNotNull null)

            newTvSeriesSearchResponse(title, link, TvType.TvSeries, posterUrl = poster)
        }
    }

    // Load series details (for episodes)
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document  // Series detail page

        // Extract metadata
        val title = doc.selectFirst("h1, .series-title")?.text() ?: ""
        val poster = doc.selectFirst("img.poster, .series-image")?.attr("src")?.let { fixUrl(it) } ?: ""
        val description = doc.selectFirst("p.description, .synopsis")?.text() ?: ""
        val tags = doc.select(".genres a").map { it.text() }

        // Episodes (placeholder - adjust selector for episode list, e.g., .sezon-list)
        val episodes = mutableListOf<Episode>()
        doc.select("div.episode-item, .bolum").forEachIndexed { index, el ->
            val epTitle = el.selectFirst("a")?.text() ?: "Bölüm ${index + 1}"
            val epLink = fixUrl(el.selectFirst("a")?.attr("href") ?: return@forEachIndexed null)
            episodes.add(
                Episode(
                    index + 1,
                    title = epTitle,
                    url = epLink
                )
            )
        }

        return newTvSeriesLoadResponse(
            title, url, TvType.TvSeries, episodes = episodes,
            imageUrl = poster, plot = description, tags = tags
        )
    }

    // Extract streams (videos) from episode page
    override suspend fun getStream(
        episode: Episode,
        playerCfg: PlayerConfig?
    ): List<ExtractorLink> {
        // Placeholder: Fetch episode page and find video sources (e.g., <video src=""> or embedded player)
        // You may need an extractor for ATV's player (e.g., HLS/DASH).
        // For now, returns empty; implement based on site (often .mp4 or m3u8 links).
        val doc = app.get(episode.url).document
        val videoUrl = doc.selectFirst("video source, .player video")?.attr("src")
            ?.let { fixUrl(it) } ?: ""

        if (videoUrl.isNotEmpty()) {
            return listOf(
                ExtractorLink(
                    source = name,
                    name = "ATV Video",
                    url = videoUrl,
                    referer = mainUrl,
                    quality = Qualities.Unknown.value
                )
            )
        }
        return emptyList()
    }

    private fun fixUrl(input: String): String {
        return if (input.startsWith("http")) input else URI(mainUrl).resolve(input).toString()
    }
}