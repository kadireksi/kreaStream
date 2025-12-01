package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
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
        val url = if (page > 1) "${request.data}?page=$page" else request.data
        val doc = app.get(url).document
        val items = doc.select(".series-card, .card-item, a[href*='/dizi/']").mapNotNull { it.toSearchResult() }
        val hasNextPage = doc.select(".pagination .next").isNotEmpty()
        return newHomePageResponse(request.name, items, hasNextPage)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = selectFirst("a") ?: return null
        val href = fixUrl(a.attr("href"))
        if (!href.contains("/dizi/")) return null

        val title = a.attr("title").takeIf { it.isNotBlank() }
            ?: a.selectFirst("h3, .title, img")?.attr("alt")
            ?: a.text().takeIf { it.isNotBlank() } ?: return null

        val poster = a.selectFirst("img")
            ?.attr("data-src") ?: a.selectFirst("img")?.attr("src")
            ?.let { if (it.startsWith("http")) it else fixUrl(it) }

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/arama?q=${query.encodeUrl()}"
        val doc = app.get(url).document
        return doc.select("a[href*='/dizi/']").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1, .seo-h1, .series-title")?.text()
            ?: doc.selectFirst("meta[property='og:title']")?.attr("content")
            ?: "Bilinmeyen Dizi"

        val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
            ?: doc.selectFirst("img.series-poster")?.attr("src")?.let { fixUrl(it) }

        val plot = doc.selectFirst(".description, .synopsis, .info-clamp__text")?.text()

        val tags = doc.select(".genre a, .tags a").map { it.text() }

        val episodes = mutableListOf<Episode>()

        doc.select("a[href*='/bolum/'], a.episode-card, .episode-item a").forEach { el ->
            val href = fixUrl(el.attr("href"))
            val name = el.text().trim().ifBlank { "Bölüm" }
            if (href.contains("/bolum/")) {
                episodes += newEpisode(href) {
                    this.name = name
                    this.description = name
                }
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

        // 1. Try iframe player (most common)
        val iframe = doc.selectFirst("iframe[src*='player'], iframe#player, div.player iframe")
        if (iframe != null) {
            val iframeSrc = fixUrl(iframe.attr("src"))
            if (iframeSrc.isNotBlank()) {
                loadExtractor(iframeSrc, data, subtitleCallback, callback)
            }
        }

        // 2. Try JS-embedded video URL
        doc.select("script").forEach { script ->
            val content = script.data()
            if (content.contains("file") || content.contains("sources")) {
                Regex("""["']file["']:\s*["']([^"']+)["']""").find(content)?.groupValues?.get(1)
                    ?.takeIf { it.isNotBlank() }?.let { videoUrl ->
                        callback.invoke(
                            newExtractorLink {
                                this.name = "$name - Direkt"
                                this.url = videoUrl.trim()
                                this.referer = data
                                this.quality = Qualities.Unknown.value
                                this.isM3u8 = videoUrl.contains(".m3u8")
                            }
                        )
                    }
            }
        }

        // 3. WebView Resolver (for protected players like atvplayer.com)
        val resolver = WebViewResolver(
            interceptUrl = { url -> url.contains(".m3u8") || url.contains(".mp4") || url.contains("master") },
            onLinkFound = { link ->
                callback.invoke(
                    newExtractorLink {
                        this.name = "$name - WebView"
                        this.url = link.url
                        this.referer = link.headers["Referer"] ?: mainUrl
                        this.quality = Qualities.Unknown.value
                        this.isM3u8 = link.url.contains(".m3u8")
                        this.headers = link.headers
                    }
                )
            }
        )

        resolver.resolveUsingWebView(res.url, res.text)

        return true
    }

    private fun fixUrl(url: String): String {
        if (url.startsWith("http")) return url
        if (url.startsWith("//")) return "https:$url"
        return mainUrl + url.trimStart('/')
    }
}
