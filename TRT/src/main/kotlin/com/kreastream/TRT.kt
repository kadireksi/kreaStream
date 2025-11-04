package com.kreastream  // <-- change to your real package

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.util.concurrent.TimeUnit

class TRT : MainAPI() {
    override var name = "TRT"
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.TVSeries)
    override val hasMainPage = true
    override val hasQuickSearch = true          // optional – shows in quick-search

    override var mainUrl = "https://www.trt1.com.tr"
    private val apiUrl = "$mainUrl/diziler"

    // --------------------------------------------------------------------- //
    //  MAIN PAGE (Güncel & Eski Diziler – sorted alphabetically)
    // --------------------------------------------------------------------- //
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = mutableListOf<HomePageList>()

        // ---- Current shows -------------------------------------------------
        val currentDoc = app.get("$apiUrl?archive=false").document
        val currentShows = currentDoc.select("div.series-item")
            .mapNotNull { it.toSearchResult() }
            .sortedBy { it.name }
        if (currentShows.isNotEmpty()) {
            items.add(HomePageList("Güncel Diziler", currentShows))
        }

        // ---- Archived shows ------------------------------------------------
        val archiveDoc = app.get("$apiUrl?archive=true").document
        val archiveShows = archiveDoc.select("div.series-item")
            .mapNotNull { it.toSearchResult() }
            .sortedBy { it.name }
        if (archiveShows.isNotEmpty()) {
            items.add(HomePageList("Eski Diziler", archiveShows))
        }

        return newHomePageResponse(items, hasNext = false)
    }

    // --------------------------------------------------------------------- //
    //  Helper: element → SearchResponse
    // --------------------------------------------------------------------- //
    private fun Element.toSearchResult(): SearchResponse? {
        val a = selectFirst("h3 a") ?: return null
        val title = a.text().trim().takeIf { it.isNotBlank() } ?: return null
        val href = fixUrl(a.attr("href"))

        val img = selectFirst("img")
        val poster = when {
            img?.attr("src")?.isNotBlank() == true -> img.attr("src")
            img?.attr("data-src")?.isNotBlank() == true -> img.attr("data-src")
            img?.attr("data-lazy")?.isNotBlank() == true -> img.attr("data-lazy")
            else -> null
        }?.let { fixUrl(it) }

        return newAnimeSearchResponse(title, href, TvType.TVSeries) {
            this.posterUrl = poster
        }
    }

    // --------------------------------------------------------------------- //
    //  SEARCH (both archives, Turkish-aware, sorted A-Z)
    // --------------------------------------------------------------------- //
    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().lowercaseTurkish()

        val results = mutableSetOf<SearchResponse>()   // set → no duplicates

        // current
        app.get("$apiUrl?archive=false").document
            .select("div.series-item")
            .mapNotNull { it.toSearchResult() }
            .filter { it.name.lowercaseTurkish().contains(q) }
            .forEach { results.add(it) }

        // archive
        app.get("$apiUrl?archive=true").document
            .select("div.series-item")
            .mapNotNull { it.toSearchResult() }
            .filter { it.name.lowercaseTurkish().contains(q) }
            .forEach { results.add(it) }

        return results.sortedBy { it.name }
    }

    private fun String.lowercaseTurkish(): String =
        this.replace("İ", "i")
            .replace("I", "ı")
            .lowercase()
            .replace("ı", "i")          // treat ı as i for search

    // --------------------------------------------------------------------- //
    //  LOAD (series page → episodes)
    // --------------------------------------------------------------------- //
    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1")?.text()
            ?: doc.selectFirst(".series-title")?.text()
            ?: doc.selectFirst("title")?.text()?.substringBefore(" - ")
            ?: return null

        val poster = doc.selectFirst("img.series-poster, img.detail-poster")
            ?.attr("src")
            ?.takeIf { it.isNotBlank() }
            ?.let { fixUrl(it) }
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?.let { fixUrl(it) }

        val plot = doc.selectFirst(".series-description, .synopsis, .summary")
            ?.text()
            ?: doc.selectFirst("meta[name=description]")?.attr("content")
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")

        val episodes = doc.select("div.episode-item a, .episodes-list a, .episode a")
            .mapNotNull { el ->
                val a = el.selectFirst("a") ?: el as? Element ?: return@mapNotNull null
                val name = a.selectFirst("span.title, .episode-title, h4, .title")
                    ?.text()?.trim() ?: "Bölüm"
                val href = fixUrl(a.attr("href"))
                val epNumText = a.selectFirst(".episode-number, .episode-no")?.text()
                    ?: a.attr("href").substringAfterLast("/").substringAfterLast("-")
                val epNum = epNumText.replace(Regex("\\D"), "").toIntOrNull()

                newEpisode(href) {
                    this.name = name
                    this.episode = epNum
                }
            }
            .sortedBy { it.episode }

        return newTvSeriesLoadResponse(title, url, TvType.TVSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    // --------------------------------------------------------------------- //
    //  LOAD LINKS (iframe, video tag, JS mp4/m3u8)
    // --------------------------------------------------------------------- //
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        var found = false

        // 1. <video><source src="…">
        doc.select("video source").forEach {
            val src = it.attr("src").takeIf { src -> src.isNotBlank() } ?: return@forEach
            callback(
                newExtractorLink(
                    source = name,
                    name = "$name - Video Tag",
                    url = fixUrl(src),
                    referer = data,
                    quality = Qualities.Unknown.value,
                    isM3u8 = src.contains(".m3u8")
                )
            )
            found = true
        }

        // 2. iframe players
        doc.select("iframe[src*=player], iframe[src*=video], iframe[src*=embed]").forEach {
            val src = it.attr("src").takeIf { s -> s.isNotBlank() && !s.contains("about:") }
                ?: return@forEach
            callback(
                newExtractorLink(
                    source = name,
                    name = "$name - Iframe",
                    url = fixUrl(src),
                    referer = data,
                    quality = Qualities.Unknown.value,
                    isM3u8 = src.contains(".m3u8")
                )
            )
            found = true
        }

        // 3. JS embedded URLs
        doc.select("script").forEach { script ->
            val txt = script.data()
            Regex("""["'](https?://[^"']+\.(mp4|m3u8)[^"']*)["']""")
                .findAll(txt)
                .forEach { m ->
                    val url = m.groupValues[1]
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "$name - JS",
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
