package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.YoutubeHelper.getYouTubeLinks
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
    //  MAIN PAGE – with pagination
    // --------------------------------------------------------------------- //
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = mutableListOf<HomePageList>()
        val isArchive = request.data.contains("archive=true")
        val baseUrl = if (isArchive) "$apiUrl?archive=true" else "$apiUrl?archive=false"

        val url = if (page > 1) "$baseUrl&page=$page" else baseUrl
        val doc = app.get(url).document

        val shows = doc.select("div.grid_grid-wrapper__elAnh > div.h-full.w-full")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
            .sortedBy { it.name }

        val title = if (isArchive) "Eski Diziler" else "Güncel Diziler"
        if (shows.isNotEmpty()) {
            items.add(HomePageList(title, shows))
        }

        val hasNext = try {
            val nextUrl = "$baseUrl&page=${page + 1}"
            val nextDoc = app.get(nextUrl, timeout = 5L).document
            nextDoc.select("div.grid_grid-wrapper__elAnh > div.h-full.w-full").isNotEmpty()
        } catch (e: Exception) {
            false
        }

        return newHomePageResponse(items, hasNext)
    }

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

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().lowercaseTurkish()
        val results = mutableSetOf<SearchResponse>()

        searchInArchive("$apiUrl?archive=false", q, results)
        searchInArchive("$apiUrl?archive=true", q, results)

        return results.sortedBy { it.name }
    }

    private suspend fun searchInArchive(baseUrl: String, query: String, results: MutableSet<SearchResponse>) {
        var page = 1
        while (true) {
            val url = if (page > 1) "$baseUrl&page=$page" else baseUrl
            val doc = app.get(url).document
            val pageResults = doc.select("div.grid_grid-wrapper__elAnh > div.h-full.w-full")
                .mapNotNull { it.toSearchResult() }
                .filter { it.name.lowercaseTurkish().contains(query) }

            if (pageResults.isEmpty()) break
            results.addAll(pageResults)

            val nextUrl = "$baseUrl&page=${page + 1}"
            try {
                val nextDoc = app.get(nextUrl, timeout = 5L).document
                if (nextDoc.select("div.grid_grid-wrapper__elAnh > div.h-full.w-full").isEmpty()) break
            } catch (e: Exception) {
                break
            }
            page++
        }
    }

    private fun String.lowercaseTurkish(): String =
        this.replace("İ", "i").replace("I", "ı").lowercase().replace("ı", "i")

    // --------------------------------------------------------------------- //
    //  LOAD – /bolum → grid + thumbnails
    // --------------------------------------------------------------------- //
    override suspend fun load(url: String): LoadResponse? {
        val bolumUrl = if (url.endsWith("/bolum")) url else "$url/bolum"
        val doc = app.get(bolumUrl).document

        val seriesDoc = app.get(url).document
        val title = seriesDoc.selectFirst("h1")?.text()
            ?: seriesDoc.selectFirst(".series-title")?.text()
            ?: seriesDoc.selectFirst("title")?.text()?.substringBefore(" - ")
            ?: return null

        val poster = seriesDoc.selectFirst("img.series-poster, img.detail-poster")
            ?.attr("src")?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
            ?: seriesDoc.selectFirst("meta[property=og:image]")?.attr("content")?.let { fixUrl(it) }

        val plot = seriesDoc.selectFirst(".series-description, .synopsis, .summary")
            ?.text()
            ?: seriesDoc.selectFirst("meta[name=description]")?.attr("content")
            ?: seriesDoc.selectFirst("meta[property=og:description]")?.attr("content")

        val episodes = doc.select("div.row_row-wrapper__v3J0q div.grid_grid-wrapper__elAnh div.h-full.w-full")
            .mapNotNull { card ->
                val a = card.selectFirst("a[target=\"_self\"]") ?: return@mapNotNull null
                val href = fixUrl(a.attr("href"))

                val titleEl = a.selectFirst("div.card_card-title__IJ9af") ?: return@mapNotNull null
                val epTitle = titleEl.text().trim().takeIf { it.isNotBlank() } ?: "Bölüm"

                val epNum = epTitle.split(".").firstOrNull()?.trim()?.toIntOrNull()
                    ?: href.substringAfterLast("/bolum/").substringBefore("-").toIntOrNull()

                val img = a.selectFirst("img.card_card-image__e0L1j") ?: return@mapNotNull null
                val thumb = img.attr("src").takeIf { it.isNotBlank() }?.let { fixUrl(it) }

                newEpisode(href) {
                    this.name = epTitle
                    this.episode = epNum
                    this.posterUrl = thumb
                }
            }
            .sortedByDescending { it.episode }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    // --------------------------------------------------------------------- //
    //  LOAD LINKS – YouTube with **ALL** qualities
    // --------------------------------------------------------------------- //
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        var found = false

        // ---- YouTube embed → extract videoId → get every quality ----
        doc.select("iframe[src*=\"youtube.com/embed/\"]").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isBlank()) return@forEach

            val videoId = src.substringAfter("/embed/").substringBefore("?")
            if (videoId.isBlank()) return@forEach

            try {
                // CloudStream helper – returns a list of ExtractorLink (all qualities)
                val ytLinks = app.getYouTubeLinks(videoId, data)
                ytLinks.forEach { link ->
                    // link.quality is an Int (e.g. 1080, 720 …)
                    val quality = link.quality
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "$name - ${quality}p",
                            url = link.url
                        ){
                            this.referer = data;
                            this.quality = quality;
                        }
                    )
                }
                found = true
            } catch (e: Exception) {
                // Fallback – just give the watch?v= link (CloudStream will still play it)
                val watchUrl = "https://www.youtube.com/watch?v=$videoId"
                callback(
                    newExtractorLink(
                        source = name,
                        name = "$name - YouTube",
                        url = watchUrl
                    ){
                        this.referer = data;
                        this.quality = Qualities.Unknown.value;
                    }
                )
                found = true
            }
        }

        return found
    }
}
