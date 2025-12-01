// ATV.kt - Updated & Working December 2025
package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.https
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element

class ATV : MainAPI() {
    override var name = "ATV"
    override var mainUrl = "https://www.atv.com.tr"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/diziler" to "Güncel Diziler",
        "$mainUrl/eski-diziler" to "Arşiv Diziler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + if (page > 1) "?page=$page" else ""
        val doc = app.get(url).document

        val items = doc.select(".series-card").mapNotNull { element ->
            element.toSearchResult()
        }

        val hasNextPage = doc.selectFirst(".pagination a[rel=next]") != null

        return newHomePageResponse(request.name, items, hasNextPage)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = selectFirst("a") ?: return null
        val href = fixUrl(a.attr("href"))
        val title = a.attr("title").takeIf { it.isNotBlank() }
            ?: a.selectFirst("h3, .series-card__title")?.text()
            ?: return null

        val poster = selectFirst("img")?.attr("data-src")
            ?: selectFirst("img")?.attr("src")
            ?: selectFirst("img")?.attr("data-lazy")
            ?.let { fixUrl(it) }

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/arama?q=${query}"
        val doc = app.get(url).document

        return doc.select(".search-result a[href*='/dizi/'], .series-card a").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1, .seo-h1, .series-detail__title")?.text()
            ?: doc.selectFirst("meta[property='og:title']")?.attr("content")
            ?: "Bilinmeyen Dizi"

        val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
            ?: doc.selectFirst("img.series-detail__poster")?.attr("src")?.let { fixUrl(it) }

        val plot = doc.selectFirst(".series-detail__description, .info-clamp__text")?.text()

        val tags = doc.select(".genre-list a, .tags a").map { it.text() }

        val episodes = mutableListOf<Episode>()

        // New episode list structure
        doc.select(".episode-list a, .episodes a[href*='/bolum/'], .episode-card a").forEach { el ->
            val epHref = fixUrl(el.attr("href"))
            val epTitle = el.selectFirst(".episode-card__title, .episode-number")
                ?.text()?.trim() ?: "Bölüm"

            episodes.add(
                newEpisode(epHref) {
                    name = epTitle
                    description = epTitle
                }
            )
        }

        // Fallback: load seasons/tabs
        if (episodes.isEmpty()) {
            doc.select("a[data-season]").forEach { seasonTab ->
                val seasonUrl = fixUrl(seasonTab.attr("href"))
                try {
                    val seasonDoc = app.get(seasonUrl).document
                    seasonDoc.select(".episode-card a").forEach { ep ->
                        val href = fixUrl(ep.attr("href"))
                        val name = ep.text().trim().ifBlank { "Bölüm" }
                        episodes.add(newEpisode(href) { this.name = name })
                    }
                } catch (e: Exception) { /* ignore */ }
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

        val res = app.get(data)
        val doc = res.document

        // 1. Try iframe player
        val iframe = doc.selectFirst("iframe[src*='player'], iframe#player")
            ?: doc.selectFirst("div.player iframe")

        if (iframe != null) {
            val iframeUrl = fixUrl(iframe.attr("src"))
            if (iframeUrl.contains("atvplayer.com") || iframeUrl.contains("player")) {
                loadExtractor(iframeUrl, data, subtitleCallback, callback)
                return true
            }

            // Some episodes use nested iframe + JS
            if (iframeUrl.contains("http")) {
                try {
                    val response = app.get(iframeUrl, referer = data, timeout = 20)
                    loadExtractor(response.url, data, subtitleCallback, callback)
                    return true
                } catch (e: Exception) {
                    // continue
                }
            }
        }

        // 2. Try data-video attributes or script-based sources
        val script = doc.selectFirst("script:contains(file:), script:contains(sources:)")
        script?.data()?.let { js ->
            val fileMatch = """["']file["']:\s*["']([^"']+)["']""".toRegex().find(js)
            val sourcesMatch = """sources:\s*\[{[^}]*file:\s*["']([^"']+)["']""".toRegex().find(js)

            (fileMatch ?: sourcesMatch)?.groupValues?.getOrNull(1)?.let { videoUrl ->
                if (videoUrl.isNotBlank()) {
                    callback.invoke(
                        ExtractorLink(
                            source = name,
                            name = "$name - Video",
                            url = https(videoUrl),
                            referer = data,
                            quality = Qualities.Unknown.value,
                            isM3u8 = videoUrl.contains(".m3u8")
                        )
                    )
                    return true
                }
            }
        }

        // 3. Last resort: WebView resolver for protected players
        WebViewResolver(
            regex = """(https?://[^"']+\.(m3u8|mp4)[^"']*)"""
        ).resolveUsingWebView(res.url, res.text).forEach { link ->
            callback.invoke(
                ExtractorLink(
                    source = name,
                    name = "$name - WebView",
                    url = link.url,
                    referer = link.headers["referer"] ?: mainUrl,
                    quality = Qualities.Unknown.value
                )
            )
        }

        return true
    }

    private fun fixUrl(url: String?): String {
        if (url.isNullOrBlank()) return ""
        return if (url.startsWith("http")) url else "$mainUrl$url".let {
            if (it.contains("//$mainUrl")) it.replace("//$mainUrl", mainUrl) else it
        }
    }
}
