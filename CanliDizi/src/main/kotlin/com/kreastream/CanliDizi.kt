package com.example.cloudstream.plugins

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.*

class CanliDiziPlugin : MainAPI() {
    override var mainUrl = "https://www.canlidizi12.com"
    override var name = "CanliDizi"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Others)
    override var lang = "tr" // Turkish content
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = false // Set to true if search is implemented

    // Load main page content (e.g., popular series, yerli diziler)
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val homeSections = mutableListOf<HomePageList>()

        // Example: Scrape "Popüler Diziler" section
        val popularSeries = document.select("div.populer-diziler > div.item").mapNotNull { element ->
            val title = element.selectFirst("h3.title")?.text() ?: return@mapNotNull null
            val url = element.selectFirst("a")?.attr("href")?.let { fixUrl(it) } ?: return@mapNotNull null
            val poster = element.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = poster
            }
        }

        // Example: Scrape "Yerli Diziler" section
        val yerliDiziler = document.select("div.yerli-diziler > div.item").mapNotNull { element ->
            val title = element.selectFirst("h3.title")?.text() ?: return@mapNotNull null
            val url = element.selectFirst("a")?.attr("href")?.let { fixUrl(it) } ?: return@mapNotNull null
            val poster = element.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = poster
            }
        }

        homeSections.add(HomePageList("Popüler Diziler", popularSeries))
        homeSections.add(HomePageList("Yerli Diziler", yerliDiziler))
        return HomePageResponse(homeSections)
    }

    // Load series details (seasons, episodes, etc.)
    override suspend fun getLoadResponse(url: String, data: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.series-title")?.text() ?: "Unknown"
        val poster = document.selectFirst("img.poster")?.attr("src")?.let { fixUrl(it) }
        val description = document.selectFirst("div.description")?.text()
        val episodes = document.select("div.episode-list > div.episode").mapNotNull { element ->
            val epTitle = element.selectFirst("span.episode-title")?.text() ?: "Episode"
            val epUrl = element.selectFirst("a")?.attr("href")?.let { fixUrl(it) } ?: return@mapNotNull null
            val epNum = element.selectFirst("span.episode-number")?.text()?.toIntOrNull() ?: 1
            newEpisode(epUrl) {
                this.name = epTitle
                this.episode = epNum
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    // Load video links for an episode
    override suspend fun getLoadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val videoUrl = document.selectFirst("video > source")?.attr("src")?.let { fixUrl(it) }
            ?: return false

        callback.invoke(
            ExtractorLink(
                this.name,
                this.name,
                videoUrl,
                mainUrl,
                quality = Qualities.P1080.value, // Adjust based on actual quality (720p or 1080p)
                isM3u8 = videoUrl.endsWith(".m3u8")
            )
        )

        // Add subtitles if available
        document.select("track[kind=subtitles]").forEach { track ->
            val subUrl = track.attr("src").let { fixUrl(it) }
            val subLang = track.attr("label") ?: "Turkish"
            subtitleCallback.invoke(SubtitleFile(subLang, subUrl))
        }

        return true
    }

    // Optional: Implement search functionality
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search?q=$query"
        val document = app.get(searchUrl).document
        return document.select("div.search-results > div.result").mapNotNull { element ->
            val title = element.selectFirst("h3.title")?.text() ?: return@mapNotNull null
            val url = element.selectFirst("a")?.attr("href")?.let { fixUrl(it) } ?: return@mapNotNull null
            val poster = element.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = poster
            }
        }
    }
}
