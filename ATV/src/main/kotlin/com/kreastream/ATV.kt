// ATV.kt
package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element
import java.net.URI

class ATV : MainAPI() {
    override var name = "ATV Türkiye"
    override var mainUrl = "https://www.atv.com.tr"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/diziler" to "Güncel Diziler",
        "$mainUrl/eski-diziler" to "Arşiv Diziler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val items = document.select("div.series-card, div.card, a[href*='/dizi/']").mapNotNull {
            it.toSearchResult()
        }

        val homeList = HomePageList(
            name = if (request.data.contains("/diziler")) "Güncel Diziler" else "Arşiv Diziler",
            list = items,
            isHorizontalImages = true
        )

        return newHomePageResponse(listOf(homeList))
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = this.selectFirst("a[href*='/dizi/']") ?: return null
        val title = linkElement.attr("title").ifBlank { 
            this.selectFirst("h3, .title, img")?.attr("alt") ?: "Bilinmeyen Dizi"
        }.trim()
        val href = fixUrl(linkElement.attr("href"))
        val poster = this.selectFirst("img")?.attr("src")?.let { fixUrl(it) }

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/arama?q=$query"
        val doc = app.get(url).document

        return doc.select("a[href*='/dizi/']").mapNotNull { element ->
            val title = element.selectFirst("img")?.attr("alt")
                ?: element.selectFirst("h3, .title")?.text()
                ?: return@mapNotNull null

            val href = fixUrl(element.attr("href"))
            val img = element.selectFirst("img")?.attr("src")?.let { fixUrl(it) }

            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = img
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1, .dizi-baslik, title")?.text()?.trim() ?: "Bilinmeyen Dizi"
        val poster = doc.selectFirst("img.ana-gorsel, .poster img, meta[property='og:image']")
            ?.attr("content") ?: doc.selectFirst("img")?.attr("src")?.let { fixUrl(it) }

        val plot = doc.selectFirst(".ozet p, .aciklama, meta[name='description']")?.text()
        val tags = doc.select(".turler a, .kategori a, .genre a").map { it.text() }

        val episodes = doc.select("a[href*='/bolum/'], a.bolum-link, .bolum-card a").mapNotNull { el ->
            val epName = el.text().trim().ifBlank { "Bölüm" }
            val epUrl = fixUrl(el.attr("href"))

            newEpisode(epUrl) {
                this.name = epName
                this.description = epName
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document

        // Try direct video source
        val sources = doc.select("video source, video[src], [data-video], iframe[src*='player']")

        if (sources.isNotEmpty()) {
            sources.forEach { source ->
                var videoUrl = source.attr("src").ifBlank { source.attr("data-video") }
                if (videoUrl.isBlank() && source.tagName() == "iframe") {
                    videoUrl = source.attr("src")
                    try {
                        val iframeDoc = app.get(fixUrl(videoUrl), referer = data).document
                        videoUrl = iframeDoc.selectFirst("source, video")?.attr("src") ?: videoUrl
                    } catch (e: Exception) { /* ignore */ }
                }

                if (videoUrl.isNotBlank()) {
                    val fixedUrl = fixUrl(videoUrl)
                    callback(
                        newExtractorLink {
                            this.url = fixedUrl
                            this.name = "$name - Bölüm"
                            this.referer = mainUrl
                            this.quality = Qualities.Unknown.value
                            this.isM3u8 = fixedUrl.contains(".m3u8")
                        }
                    )
                }
            }
        }

        return true
    }

    private fun fixUrl(url: String): String {
        return if (url.startsWith("http")) url else URI(mainUrl).resolve(url).toString()
    }
}
