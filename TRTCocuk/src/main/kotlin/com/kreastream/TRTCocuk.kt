package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class TRTCocuk : MainAPI() {
    override var name = "TRT Çocuk"
    override var mainUrl = "https://www.trtcocuk.net.tr"
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Cartoon)
    override val hasMainPage = true
    override val hasQuickSearch = true

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get("$mainUrl/video").document
        val items = doc.select(".col-xl-2 a[href^=\"/video/\"], .col-xl-2 a[href^=\"/dizi/\"]")
            .mapNotNull { el ->
                val href = fixUrl(el.attr("href"))
                val title = el.selectFirst("img")?.attr("alt") ?: return@mapNotNull null
                val poster = el.selectFirst("img")?.attr("src") ?: el.selectFirst("img")?.attr("data-src")

                newAnimeSearchResponse(title, href, TvType.Cartoon) {
                    this.posterUrl = poster
                }
            }

        return newHomePageResponse("Tüm Çizgi Filmler", items)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        val url = "$mainUrl/ara?q=${query.replace(" ", "+")}"
        val doc = app.get(url).document

        return doc.select(".search-result a").mapNotNull { el ->
            val href = fixUrl(el.attr("href"))
            if (!href.contains("/video/")) return@mapNotNull null

            val title = el.selectFirst(".title")?.text()
                ?: el.selectFirst("img")?.attr("alt")
                ?: return@mapNotNull null

            val poster = el.selectFirst("img")?.attr("src") ?: el.selectFirst("img")?.attr("data-src")

            newAnimeSearchResponse(title, href, TvType.Cartoon) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> = quickSearch(query) ?: emptyList()

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1")?.text()
            ?: doc.selectFirst("meta[property=\"og:title\"]")?.attr("content")
            ?: "TRT Çocuk"

        val isSeries = !url.substringAfterLast("/").contains("-bolum") &&
                       !url.substringAfterLast("/").contains("-bölüm")

        val episodes = mutableListOf<Episode>()

        if (isSeries) {
            doc.select("a[href*=\"-bolum\"], a[href*=\"-bölüm\"]").forEach { a ->
                val href = fixUrl(a.attr("href"))
                val epTitle = a.selectFirst("p")?.text()
                    ?: a.selectFirst("img")?.attr("alt")
                    ?: "Bölüm"

                val epNum = Regex("""(\d+)""").find(epTitle)?.value?.toIntOrNull() ?: (episodes.size + 1)

                val poster = a.selectFirst("img")?.attr("data-src") ?: a.selectFirst("img")?.attr("src")

                episodes += newEpisode(href) {
                    name = epTitle.trim()
                    season = 1
                    episode = epNum
                    this.posterUrl = poster
                }
            }
            episodes.reverse() // Yeni bölümler başta gelir, ters çevir
        } else {
            episodes += newEpisode(url) {
                name = title
                season = 1
                episode = 1
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.Cartoon, episodes) {
            posterUrl = doc.selectFirst("meta[property=\"og:image\"]")?.attr("content")
            plot = doc.selectFirst("meta[name=\"description\"]")?.attr("content")
            tags = listOf("Çocuk", "Eğitici", "Türkçe", "TRT")
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document

        val script = doc.select("script").firstOrNull { it.html().contains("master.mpd") } ?: return false

        val mpdRelative = Regex("""["']([^"']*master\.mpd[^"']*)["']""")
            .find(script.html())?.groupValues?.get(1) ?: return false

        val mpdUrl = if (mpdRelative.startsWith("http")) mpdRelative else "https://cdn-i.pr.trt.com.tr$mpdRelative"

        // YENİ YÖNTEM: newExtractorLink
        newExtractorLink(
            url = mpdUrl,
            source = name,
            name = "TRT Çocuk"
        ){
            this.referer = "https://www.trtcocuk.net.tr/"
            this.quality = Qualities.P720.value
            this.type = ExtractorLinkType.DASH
        }?.let(callback)

        // Altyazılar
        doc.select("track[kind=\"captions\"], track[label*=\"Türkçe\"]").forEach {
            val subUrl = fixUrl(it.attr("src"))
            if (subUrl.isNotBlank()) {
                subtitleCallback(SubtitleFile("Türkçe", subUrl))
            }
        }

        return true
    }
}