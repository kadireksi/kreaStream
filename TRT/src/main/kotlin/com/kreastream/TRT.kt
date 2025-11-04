package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class TRT : MainAPI() {
    override var name = "TRT"
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.TvSeries)
    override val hasMainPage = true
    override val hasQuickSearch = true

    override var mainUrl = "https://www.trt1.com.tr"
    private val apiUrl = "$mainUrl/diziler"

    // --------------------------------------------------------------------- //
    //  MAIN PAGE – Güncel & Eski Diziler (sorted A-Z)
    // --------------------------------------------------------------------- //
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = mutableListOf<HomePageList>()

        // ---- Current shows -------------------------------------------------
        val currentDoc = app.get("$apiUrl?archive=false").document
        val currentShows = currentDoc.select("div.grid_grid-wrapper__elAnh > div.h-full.w-full")
            .mapNotNull { it.toSearchResult() }
            .sortedBy { it.name }
        if (currentShows.isNotEmpty()) items.add(HomePageList("Güncel Diziler", currentShows))

        // ---- Archived shows ------------------------------------------------
        val archiveDoc = app.get("$apiUrl?archive=true").document
        val archiveShows = archiveDoc.select("div.grid_grid-wrapper__elAnh > div.h-full.w-full")
            .mapNotNull { it.toSearchResult() }
            .sortedBy { it.name }
        if (archiveShows.isNotEmpty()) items.add(HomePageList("Eski Diziler", archiveShows))

        return newHomePageResponse(items, hasNext = false)
    }

    // --------------------------------------------------------------------- //
    //  Helper: card → SearchResponse
    // --------------------------------------------------------------------- //
    private fun Element.toSearchResult(): SearchResponse? {
        val a = selectFirst("a[target=\"_self\"]") ?: return null
        val href = fixUrl(a.attr("href"))

        val titleEl = selectFirst("div.card_card-title__IJ9af") ?: return null
        val title = titleEl.text().trim().takeIf { it.isNotBlank() } ?: return null

        val img = selectFirst("img.card_card-image__e0L1j") ?: return null
        val poster = img.attr("src").takeIf { it.isNotBlank() }?.let { fixUrl(it) }
            ?: img.attr("data-src").takeIf { it.isNotBlank() }?.let { fixUrl(it) }

        return newAnimeSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    // --------------------------------------------------------------------- //
    //  SEARCH – both archives, Turkish-aware, sorted A-Z
    // --------------------------------------------------------------------- //
    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().lowercaseTurkish()
        val results = mutableSetOf<SearchResponse>()

        // current
        app.get("$apiUrl?archive=false").document
            .select("div.grid_grid-wrapper__elAnh > div.h-full.w-full")
            .mapNotNull { it.toSearchResult() }
            .filter { it.name.lowercaseTurkish().contains(q) }
            .forEach { results.add(it) }

        // archive
        app.get("$apiUrl?archive=true").document
            .select("div.grid_grid-wrapper__elAnh > div.h-full.w-full")
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
    //  LOAD – series page → episodes (updated for new layout)
    // --------------------------------------------------------------------- //
    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1")?.text()
            ?: doc.selectFirst(".series-title")?.text()
            ?: doc.selectFirst("title")?.text()?.substringBefore(" - ")
            ?: return null

        val poster = doc.selectFirst("img.series-poster, img.detail-poster")
            ?.attr("src")?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.let { fixUrl(it) }

        val plot = doc.selectFirst(".series-description, .synopsis, .summary")
            ?.text()
            ?: doc.selectFirst("meta[name=description]")?.attr("content")
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")

        val episodes = doc.select("div.swiper-wrapper > div.swiper-slide > div.h-full.w-full")
            .mapNotNull { card ->
                val a = card.selectFirst("a[target=\"_self\"]") ?: return@mapNotNull null
                val href = fixUrl(a.attr("href"))

                val epTitleEl = a.selectFirst("div.card_card-title__IJ9af") ?: return@mapNotNull null
                val epTitle = epTitleEl.text().trim().takeIf { it.isNotBlank() } ?: "Bölüm"

                // Extract episode number from title (e.g., "191. Bölüm" → 191)
                val epNum = epTitle.split(".").firstOrNull()?.trim()?.toIntOrNull()
                    // Fallback to URL: /bolum/191-bolum → 191
                    ?: href.substringAfterLast("/bolum/").substringBefore("-").toIntOrNull()

                newEpisode(href) {
                    this.name = epTitle
                    this.episode = epNum
                }
            }
            .sortedByDescending { it.episode }  // Latest first (as on site), or use sortedBy for oldest first

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    // --------------------------------------------------------------------- //
    //  LOAD LINKS – iframe / video tag / JS mp4|m3u8 (fixed syntax)
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
            val src = it.attr("src").takeIf { s -> s.isNotBlank() } ?: return@forEach
            callback(
                newExtractorLink(
                    source = name,
                    name = "$name - Video Tag",
                    url = fixUrl(src)
                ){
                    referer = data;
                    quality = Qualities.Unknown.value;
                    isM3u8 = src.contains(".m3u8");
                }
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
                    url = fixUrl(src)
                ){
                    referer = data;
                    quality = Qualities.Unknown.value;
                    isM3u8 = src.contains(".m3u8");
                }
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
                            url = url
                        ){
                            referer = data;
                            quality = Qualities.Unknown.value;
                            isM3u8 = url.contains("m3u8");
                        }
                    )
                    found = true
                }
        }

        return found
    }
}
