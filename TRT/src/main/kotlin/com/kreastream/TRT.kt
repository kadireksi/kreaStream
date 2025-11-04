package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class TRT : MainAPI() {
    override var name = "TRT"
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.TVSeries)
    override val hasMainPage = true
    override val hasQuickSearch = false // Set to true if you want quick search tile

    override val mainUrl = "https://www.trt1.com.tr"
    private val apiUrl = "https://www.trt1.com.tr/diziler"

    // Cache documents to avoid double-loading during search + main page
    private val currentDoc by lazy { app.get("$apiUrl?archive=false").document }
    private val archiveDoc by lazy { app.get("$apiUrl?archive=true").document }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = mutableListOf<HomePageList>()

        // Current Shows
        val currentShows = currentDoc.select("div.series-item").mapNotNull { it.toSearchResult() }
            .sortedBy { it.name }
        if (currentShows.isNotEmpty()) {
            items.add(HomePageList("Güncel Diziler", currentShows))
        }

        // Archived Shows
        val archiveShows = archiveDoc.select("div.series-item").mapNotNull { it.toSearchResult() }
            .sortedBy { it.name }
        if (archiveShows.isNotEmpty()) {
            items.add(HomePageList("Eski Diziler", archiveShows))
        }

        return HomePageResponse(items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleEl = this.selectFirst("h3 a") ?: return null
        val title = titleEl.text().trim()
        if (title.isBlank()) return null

        val href = fixUrl(titleEl.attr("href"))
        val posterImg = this.selectFirst("img")
        val posterUrl = posterImg?.attr("src")?.takeIf { it.isNotBlank() }
            ?: posterImg?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: posterImg?.attr("data-lazy")?.takeIf { it.isNotBlank() }
        val finalPoster = posterUrl?.let { fixUrl(it) }

        return newAnimeSearchResponse(title, href, TvType.TVSeries) {
            this.posterUrl = finalPoster
        }
    }

    // SEARCH FUNCTIONALITY
    override suspend fun search(query: String): List<SearchResponse> {
        val normalizedQuery = query.trim().lowercaseTurkish()

        val results = mutableListOf<SearchResponse>()

        // Search in Current Shows
        currentDoc.select("div.series-item").forEach { element ->
            val response = element.toSearchResult() ?: return@forEach
            if (response.name.lowercaseTurkish().contains(normalizedQuery)) {
                results.add(response)
            }
        }

        // Search in Archived Shows
        archiveDoc.select("div.series-item").forEach { element ->
            val response = element.toSearchResult() ?: return@forEach
            if (response.name.lowercaseTurkish().contains(normalizedQuery)) {
                results.add(response)
            }
        }

        // Sort by name and remove duplicates (in case of overlap)
        return results.distinctBy { it.url }.sortedBy { it.name }
    }

    // Helper: Turkish case-insensitive matching
    private fun String.lowercaseTurkish(): String {
        return this.replace("İ", "i")
            .replace("I", "ı")
            .lowercase()
            .replace("ı", "i") // treat ı as i for search
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1")?.text()
            ?: doc.selectFirst(".series-title")?.text()
            ?: doc.selectFirst("title")?.text()?.substringBefore(" - ") ?: return null

        val poster = doc.selectFirst("img.series-poster, img.detail-poster, meta[property='og:image']")
            ?.attr("src")?.let { fixUrl(it) }
            ?: doc.selectFirst("meta[property='og:image']")?.attr("content")?.let { fixUrl(it) }

        val description = doc.selectFirst(".series-description, .synopsis, .summary, meta[name='description']")
            ?.text()
            ?: doc.selectFirst("meta[property='og:description']")?.attr("content")

        val episodes = doc.select("div.episode-item a, .episodes-list a, .episode a").mapNotNull { ep ->
            val aTag = ep.selectFirst("a") ?: ep as? Element ?: return@mapNotNull null
            val epTitle = aTag.selectFirst("span.title, .episode-title, h4, .title")?.text()
                ?.trim() ?: "Bölüm"
            val epHref = fixUrl(aTag.attr("href"))
            val epNumText = aTag.selectFirst(".episode-number, .episode-no")?.text()
                ?: aTag.attr("href").substringAfterLast("/").substringAfterLast("-").toIntOrNull()?.toString()
            val epNum = epNumText?.replace(Regex("\\D"), "")?.toIntOrNull()

            Episode(
                data = epHref,
                name = epTitle,
                episode = epNum
            )
        }.filter { it.data.isNotBlank() }
          .sortedBy { it.episode }

        return newTvSeriesLoadResponse(title, url, TvType.TVSeries, episodes) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document

        var found = false

        // 1. Direct video tag
        doc.select("video source").forEach {
            val src = it.attr("src")
            if (src.isNotBlank()) {
                val isM3u8 = src.contains(".m3u8")
                callback.invoke(
                    ExtractorLink(
                        source = name,
                        name = "$name - Video Source",
                        url = fixUrl(src),
                        referer = data,
                        quality = Qualities.Unknown.value,
                        isM3u8 = isM3u8
                    )
                )
                found = true
            }
        }

        // 2. Iframe players
        doc.select("iframe[src*='player'], iframe[src*='video'], iframe[src*='embed']").forEach {
            val src = it.attr("src")
            if (src.isNotBlank() && !src.contains("about:")) {
                callback.invoke(
                    ExtractorLink(
                        source = name,
                        name = "$name - Iframe Player",
                        url = fixUrl(src),
                        referer = data,
                        quality = Qualities.Unknown.value,
                        isM3u8 = src.contains(".m3u8")
                    )
                )
                found = true
            }
        }

        // 3. Extract from JS (common on TRT1)
        doc.select("script").forEach { script ->
            val content = script.data()

            // Look for .mp4 or .m3u8 URLs
            Regex("""["'](https?://[^\s"']+\.(mp4|m3u8)[^\s"']*)["']""").findAll(content).forEach { match ->
                val url = match.groupValues[1]
                callback.invoke(
                    ExtractorLink(
                        source = name,
                        name = "$name - JS Source",
                        url = url,
                        referer = data,
                        quality = Qualities.Unknown.value,
                        isM3u8 = url.contains("m3u8")
                    )
                )
                found = true
            }
        }

        return found
    }
}