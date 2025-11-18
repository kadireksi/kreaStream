package com.trtcocuk

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class TRTCocuk : MainAPI() {
    override val name = "TRT Çocuk"
    override val mainUrl = "https://www.trtcocuk.net.tr"
    override val lang = "tr"
    override val supportedTypes = setOf(TvType.Cartoon)
    override val hasMainPage = true
    override val hasQuickSearch = true

    // TRT Çocuk now loads the grid via AJAX → we use their own API endpoint (undocumented but stable)
    private val apiUrl = "https://www.trtcocuk.net.tr/api/icerik?tip=video&sayfa=1&limit=300"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val response = app.get(apiUrl).parsedSafe<TrtApiResponse>() ?: return newHomePageResponse("Boş", emptyList())

        val shows = response.data.mapNotNull { item ->
            val slug = item.seoUrl ?: return@mapNotNull null
            val href = "$mainUrl/video/$slug"
            val title = item.baslik ?: return@mapNotNull null
            val poster = "https://cdn-i.pr.trt.com.tr${item.kapakResimYolu}"

            newAnimeSearchResponse(title, href, TvType.Cartoon) {
                this.posterUrl = poster
            }
        }

        return newHomePageResponse("Tüm Çizgi Filmler", shows)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        val url = "$mainUrl/ara?q=${query.replace(" ", "+")}"
        val doc = app.get(url).document

        return doc.select(".search-result a[href*=\"/video/\"]").mapNotNull { el ->
            val href = fixUrl(el.attr("href"))
            val title = el.selectFirst(".title")?.text()
                ?: el.selectFirst("img")?.attr("alt") ?: return@mapNotNull null
            val poster = el.selectFirst("img")?.attr("data-src") ?: el.selectFirst("img")?.attr("src")

            newAnimeSearchResponse(title, href, TvType.Cartoon) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> = quickSearch(query) ?: emptyList()

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1")?.text()
            ?: doc.selectFirst("meta[property=\"og:title\"]")?.attr("content")?.removeSuffix(" | TRT Çocuk")?.trim()
            ?: "TRT Çocuk"

        // Check if it's a series page (has carousel or episode list)
        val episodeElements = doc.select("a[href*=\"-bolum\"], a[href*=\"-bölüm\"]")
        val isSeries = episodeElements.isNotEmpty()

        val episodes = mutableListOf<Episode>()

        if (isSeries) {
            episodeElements.forEach { a ->
                val href = fixUrl(a.attr("href"))
                val epTitle = a.selectFirst("p")?.text()
                    ?: a.selectFirst("img")?.attr("alt") ?: "Bölüm"
                val epNum = Regex("""(\d+)""").find(epTitle)?.value?.toIntOrNull() ?: (episodes.size + 1)
                val poster = a.selectFirst("img")?.attr("data-src") ?: a.selectFirst("img")?.attr("src")

                episodes += newEpisode(href) {
                    name = epTitle.trim()
                    season = 1
                    episode = epNum
                    posterUrl = poster
                }
            }
            episodes.reverse() // newest first → we want oldest first
        } else {
            // Single episode
            episodes += newEpisode(url) {
                name = title
                season = 1
                episode = 1
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.Cartoon, episodes) {
            posterUrl = doc.selectFirst("meta[property=\"og:image\"]")?.attr("content")
            plot = doc.selectFirst("meta[name=\"description\"]")?.attr("content")
            tags = listOf("Çocuk", "Türkçe", "TRT")
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
        val mpd = Regex("""["']([^"']*master\.mpd[^"']*)["']""")
            .find(script.html())?.groupValues?.get(1) ?: return false

        val mpdUrl = if (mpd.startsWith("http")) mpd else "https://cdn-i.pr.trt.com.tr$mpd"

        newExtractorLink(
            url = mpdUrl,
            name = "TRT Çocuk",
            referer = mainUrl + "/",
            quality = Qualities.P720.value,
            type = ExtractorLinkType.DASH
        )?.let(callback)

        doc.select("track[kind=\"captions\"], track[label*=\"Türkçe\"]").forEach {
            val sub = fixUrl(it.attr("src"))
            if (sub.isNotBlank()) subtitleCallback(SubtitleFile("Türkçe", sub))
        }

        return true
    }
}

// API response model (only what we need)
data class TrtApiResponse(val data: List<TrtItem>)
data class TrtItem(
    val baslik: String?,
    val seoUrl: String?,
    val kapakResimYolu: String
)
