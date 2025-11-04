package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class TRT : MainAPI() {
    override var name = "TRT"
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.TVSeries)
    override val hasMainPage = true
    override val hasQuickSearch = false

    override val mainUrl = "https://www.trt1.com.tr"
    private val apiUrl = "https://www.trt1.com.tr/diziler"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = mutableListOf<HomePageList>()

        // Current Shows
        val currentDoc = app.get("$apiUrl?archive=false").document
        val currentShows = currentDoc.select("div.series-item").mapNotNull { it.toSearchResult() }
            .sortedBy { it.name }
        if (currentShows.isNotEmpty()) {
            items.add(HomePageList("Güncel Diziler", currentShows))
        }

        // Archived Shows
        val archiveDoc = app.get("$apiUrl?archive=true").document
        val archiveShows = archiveDoc.select("div.series-item").mapNotNull { it.toSearchResult() }
            .sortedBy { it.name }
        if (archiveShows.isNotEmpty()) {
            items.add(HomePageList("Eski Diziler", archiveShows))
        }

        return HomePageResponse(items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h3 a")?.text() ?: return null
        val href = fixUrl(this.selectFirst("h3 a")?.attr("href") ?: return null)
        val posterImg = this.selectFirst("img")?.attr("src") ?: this.selectFirst("img")?.attr("data-src")
        val posterUrl = posterImg?.let { fixUrl(it) }

        return newAnimeSearchResponse(title, href, TvType.TVSeries) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return emptyList() // Search not implemented
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1")?.text() ?: doc.selectFirst(".series-title")?.text() ?: return null
        val poster = doc.selectFirst("img.series-poster")?.attr("src")
            ?.let { fixUrl(it) }

        val description = doc.selectFirst(".series-description, .synopsis")?.text()

        val episodes = doc.select("div.episode-item, .episodes a").mapNotNull { ep ->
            val epTitle = ep.selectFirst("span.title, h4")?.text() ?: "Bölüm"
            val epHref = fixUrl(ep.attr("href") ?: ep.selectFirst("a")?.attr("href") ?: return@mapNotNull null)
            val epNum = ep.selectFirst(".episode-number")?.text()?.replace(Regex("\\D"), "")?.toIntOrNull()

            Episode(
                data = epHref,
                name = epTitle,
                episode = epNum
            )
        }.sortedBy { it.episode }

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

        // TRT1 embeds videos via iframe or script
        val iframe = doc.selectFirst("iframe[src*='player']")
            ?: doc.selectFirst("video source")
            ?: doc.selectFirst("script:containsData(playerInstance)")

        var videoUrl: String? = null

        if (iframe != null) {
            videoUrl = iframe.attr("src")
            if (videoUrl.startsWith("//")) videoUrl = "https:$videoUrl"
        } else {
            // Try to extract from JS
            val script = doc.select("script").find { it.data().contains("video") || it.data().contains("mp4") }
            script?.data()?.let { data ->
                val urlMatch = Regex("""["'](https?://[^"']+\.mp4[^"']*)["']""").find(data)
                videoUrl = urlMatch?.groupValues?.get(1)
            }
        }

        videoUrl?.let {
            callback.invoke(
                ExtractorLink(
                    source = name,
                    name = name,
                    url = it,
                    referer = mainUrl,
                    quality = Qualities.Unknown.value,
                    isM3u8 = it.contains(".m3u8")
                )
            )
        } ?: run {
            // Fallback: look for any .mp4 or .m3u8 in page
            doc.select("script").forEach { script ->
                val urls = Regex("""["'](https?://[^\s"']+\.(mp4|m3u8)[^\s"']*)["']""").findAll(script.data())
                urls.forEach { match ->
                    val url = match.groupValues[1]
                    callback.invoke(
                        ExtractorLink(
                            source = name,
                            name = name,
                            url = url,
                            referer = mainUrl,
                            quality = Qualities.Unknown.value,
                            isM3u8 = url.contains("m3u8")
                        )
                    )
                }
            }
        }

        return true
    }
}