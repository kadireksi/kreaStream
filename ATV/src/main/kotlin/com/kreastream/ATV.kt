// ATV.kt
package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import java.net.URI

class ATV : MainAPI() {
    override val name = "ATV Türkiye"
    override val mainUrl = "https://www.atv.com.tr"
    override val lang = "tr"
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

        val homeList = if (request.data.contains("/diziler")) {
            HomePageList("Güncel Diziler", items, isHorizontalImages = true)
        } else {
            HomePageList("Arşiv Diziler", items, isHorizontalImages = true)
        }

        return newHomePageResponse(listOf(homeList))
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h3 a, .title a, a[href*='/dizi/']")?.text()?.trim() ?: return null
        val href = fixUrl(this.selectFirst("a[href*='/dizi/']")?.attr("href") ?: return null)
        val image = this.selectFirst("img")?.attr("src")?.let { fixUrl(it) }

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = image
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/arama?q=$query"
        val doc = app.get(url).document
        return doc.select("a[href*='/dizi/']").mapNotNull {
            val title = it.selectFirst("h3, .title")?.text()?.trim() ?: return@mapNotNull null
            val href = fixUrl(it.attr("href"))
            val img = it.selectFirst("img")?.attr("src")?.let { fixUrl(it) }

            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = img
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1, .series-title, .dizi-baslik")?.text()?.trim() ?: "Unknown"
        val poster = doc.selectFirst("img.ana-gorsel, img.poster, .series-image img")
            ?.attr("src")?.let { fixUrl(it) }

        val description = doc.selectFirst(".ozet p, .synopsis, .aciklama")?.text()
        val genres = doc.select(".turler a, .kategori a").map { it.text() }

        // Episodes – ATV usually has season tabs + episode grid
        val episodes = mutableListOf<Episode>()
        doc.select("a.bolum-link, a[href*='/bolum/'], .bolum-card a").forEach {
            val epTitle = it.text().trim().ifEmpty { "Bölüm" }
            val epUrl = fixUrl(it.attr("href"))

            episodes.add(newEpisode(epUrl) {
                this.name = epTitle
                this.description = epTitle
            })
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = description
            this.tags = genres
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document

        // ATV uses embedded players (usually VideoJS + m3u8 or direct MP4)
        // Common patterns:
        // 1. <video><source src="..." type="video/mp4"></video>
        // 2. data-src or data-video attribute
        // 3. iframe or script containing m3u8 URL

        val videoUrl = doc.selectFirst("video source")?.attr("src")
            ?: doc.selectFirst("video")?.attr("src")
            ?: doc.selectFirst("[data-video]")?.attr("data-video")
            ?: doc.selectFirst("iframe")?.attr("src")?.let { iframeUrl ->
                // If iframe, try to extract from there
                try { app.get(fixUrl(iframeUrl)).document.selectFirst("source")?.attr("src") } catch (e: Exception) { null }
            }

        if (!videoUrl.isNullOrBlank()) {
            val fixed = fixUrl(videoUrl)
            callback.invoke(
                ExtractorLink(
                    source = name,
                    name = "$name - Bölüm",
                    url = fixed,
                    referer = mainUrl,
                    quality = Qualities.Unknown.value,
                    isM3u8 = fixed.contains(".m3u8")
                )
            )
        }

        return true
    }

    private fun fixUrl(url: String): String {
        return if (url.startsWith("http")) url else URI(mainUrl).resolve(url).toString()
    }
}
