package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class DDizi : MainAPI() {

    override var mainUrl = "https://www.ddizi.im"
    override var name = "DDizi"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/yeni-eklenenler1" to "Son Eklenen Bölümler",
        "$mainUrl" to "Yerli Diziler",
        "$mainUrl/yabanci-dizi-izle" to "Yabancı Diziler",
        "$mainUrl/eski.diziler" to "Eski Diziler"
    )

    // =============================
    // MAIN PAGE
    // =============================
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val url = if (page > 1) "${request.data}/$page" else request.data
        val document = app.get(url).document

        val items = document.select(
            "div.dizi-boxpost, div.dizi-boxpost-cat"
        ).mapNotNull { it.toSearchResult() }

        val hasNext =
            document.selectFirst(".pagination a:contains(Sonraki)") != null

        return newHomePageResponse(request.name, items, hasNext)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = selectFirst("a") ?: return null
        val title = a.text().trim()
        val href = fixUrl(a.attr("href"))
        val poster = selectFirst("img")
            ?.let { fixUrlNull(it.attr("data-src") ?: it.attr("src")) }

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            posterUrl = poster
        }
    }

    // =============================
    // SEARCH
    // =============================
    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.post(
            "$mainUrl/arama/",
            data = mapOf("arama" to query)
        ).document

        return document.select(
            "div.dizi-boxpost, div.dizi-boxpost-cat, div.dizi-listesi a"
        ).mapNotNull { it.toSearchResult() }
    }

    // =============================
    // LOAD (PARTS AS EPISODES)
    // =============================
    override suspend fun load(url: String): LoadResponse {

        val document = app.get(url).document
        val rawTitle = document.selectFirst("h1, h2")?.text()?.trim() ?: name

        val poster =
            document.selectFirst("div.afis img, img.afis, img.img-back")
                ?.let { fixUrlNull(it.attr("data-src") ?: it.attr("src")) }

        val episodes = mutableListOf<Episode>()

        // ===== SERIES PAGE =====
        if (url.contains("/dizi/") || url.contains("/diziler/")) {

            document.select("div.bolumler a, div.sezonlar a").forEach { el ->
                val epTitle = el.text().trim()
                val epUrl = fixUrl(el.attr("href"))

                episodes.add(
                    newEpisode(epUrl) {
                        name = epTitle
                        true
                    }
                )
            }

        } else {
            // ===== EPISODE PAGE WITH PARTS =====
            val parts = document.select("div.parts a")

            if (parts.isNotEmpty()) {
                var index = 1

                parts.forEach { part ->
                    val partUrl =
                        part.attr("href").takeIf { it.isNotBlank() }
                            ?.let { fixUrl(it) }
                            ?: url

                    episodes.add(
                        newEpisode(partUrl) {
                            name = "$rawTitle - $index.Parça"
                            true
                        }
                    )

                    index++
                }
            } else {
                episodes.add(
                    newEpisode(url) {
                        name = rawTitle
                        true
                    }
                )
            }
        }

        return newTvSeriesLoadResponse(
            rawTitle,
            url,
            TvType.TvSeries,
            episodes
        ) {
            posterUrl = poster
        }
    }

    // =============================
    // LOAD LINKS
    // =============================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = app.get(data).document

        // YouTube
        document.selectFirst("iframe")?.attr("src")
            ?.takeIf { it.contains("youtube", true) }
            ?.let { iframe ->
                Regex("""id=(https://.*?)(?:&|$)""")
                    .find(iframe)
                    ?.groupValues
                    ?.get(1)
                    ?.let {
                        loadExtractor(it, data, subtitleCallback, callback)
                        return true
                    }
            }

        // og:video
        document.selectFirst("meta[property=og:video]")
            ?.attr("content")
            ?.let {
                loadExtractor(it, data, subtitleCallback, callback)
                return true
            }

        return false
    }
}
