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

    /* =========================
       MAIN PAGE
       ========================= */
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val url = if (page > 1) "${request.data}/$page" else request.data
        val document = app.get(url).document

        val items = when (request.name) {

            "Yerli Diziler" -> {
                document.selectFirst("ul.list_")
                    ?.select("li > a")
                    ?.mapNotNull { it.toSearchResult() }
                    ?: emptyList()
            }

            else -> {
                document.select("div.dizi-boxpost, div.dizi-boxpost-cat")
                    .mapNotNull { it.toSearchResult() }
            }
        }

        val hasNext =
            document.selectFirst(".pagination a:contains(Sonraki)") != null

        return newHomePageResponse(request.name, items, hasNext)
    }

    /* =========================
       SEARCH RESULT PARSER
       ========================= */
    private fun Element.toSearchResult(): SearchResponse? {
        val a = if (tagName() == "a") this else selectFirst("a") ?: return null

        val title = a.text().trim()
        val href = fixUrl(a.attr("href"))

        val poster = a.selectFirst("img")
            ?.let { fixUrlNull(it.attr("data-src") ?: it.attr("src")) }

        if (title.isBlank() || href.isBlank()) return null

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            posterUrl = poster
        }
    }

    /* =========================
       SEARCH
       ========================= */
    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.post(
            "$mainUrl/arama/",
            data = mapOf("arama" to query)
        ).document

        return document.select(
            "div.dizi-boxpost, div.dizi-boxpost-cat, div.dizi-listesi a"
        ).mapNotNull { it.toSearchResult() }
    }

    /* =========================
       LOAD (SERIES + PARTS)
       ========================= */
    override suspend fun load(url: String): LoadResponse {

        val document = app.get(url).document
        val pageTitle = document.selectFirst("h1, h2")?.text()?.trim() ?: name

        val poster =
            document.selectFirst("div.afis img, img.afis, img.img-back")
                ?.let { fixUrlNull(it.attr("data-src") ?: it.attr("src")) }

        val episodes = mutableListOf<Episode>()

        val isSeriesPage =
            url.contains("/dizi/") ||
            url.contains("/diziler/") ||
            url.contains("/yabanci-dizi") ||
            url.contains("/eski.diziler")

        /* ---- SERIES PAGE ---- */
        if (isSeriesPage) {

            document.select(
                "div.bolumler a, div.sezonlar a, div.dizi-arsiv a, div.dizi-boxpost-cat a"
            ).forEach { el ->

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
            /* ---- EPISODE PAGE (PARTS AS EPISODES) ---- */

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
                            name = "$pageTitle - $index.Parça"
                            true
                        }
                    )
                    index++
                }

            } else {
                episodes.add(
                    newEpisode(url) {
                        name = pageTitle
                        true
                    }
                )
            }
        }

        return newTvSeriesLoadResponse(
            pageTitle,
            url,
            TvType.TvSeries,
            episodes
        ) {
            posterUrl = poster
        }
    }

    /* =========================
       LOAD LINKS
       ========================= */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = app.get(
            data,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to data
            )
        ).document

        // YouTube iframe
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

        // og:video (JWPlayer / M3U8 / MP4)
        document.selectFirst("meta[property=og:video]")
            ?.attr("content")
            ?.let {
                loadExtractor(it, data, subtitleCallback, callback)
                return true
            }

        return false
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/134.0.0.0 Safari/537.36"
    }
}
