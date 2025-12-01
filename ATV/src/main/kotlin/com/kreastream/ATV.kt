// ATV.kt - Fully Modular & Reusable Design (2025+ CloudStream Compatible)
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

    // ==================== CONFIGURATION START ====================
    private data class Selectors(
        // Main Page
        val mainPageItems: String = ".series-card, .card-item, a[href*='/dizi/']",
        val mainPageNext: String = ".pagination .next, a[rel='next']",

        // Search & Series Card
        val cardLink: String = "a",
        val cardTitle: String = "h3, .title, img[alt], [title]",
        val cardPoster: String = "img",
        val cardPosterAttr: String = "data-src, src, data-lazy",

        // Series Detail Page
        val title: String = "h1, .seo-h1, .series-title, meta[property='og:title']",
        val titleAttr: String = "content, text",
        val poster: String = "meta[property='og:image'], img.series-poster, .poster img",
        val posterAttr: String = "content, src, data-src",
        val description: String = ".description, .synopsis, .info-clamp__text, meta[name='description']",
        val genres: String = ".genre a, .tags a, .kategori a",
        val episodeItem: String = "a[href*='/bolum/'], .episode-card a, .episode-item a",

        // Player
        val playerIframe: String = "iframe[src*='player'], iframe#player, div.player iframe",
        val jsVideoFile: Regex = """["']file["']\s*:\s*["']([^"']+)["']""".toRegex()
    )

    private val sel = Selectors()
    // ==================== CONFIGURATION END ====================

    override val mainPage = mainPageOf(
        "$mainUrl/diziler" to "Güncel Diziler",
        "$mainUrl/eski-diziler" to "Arşiv Diziler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "${request.data}?page=$page" else request.data
        val doc = app.get(url).document
        val items = doc.select(sel.mainPageItems).mapNotNull { it.toSearchResult() }
        val hasNext = doc.select(sel.mainPageNext).isNotEmpty()
        return newHomePageResponse(request.name, items, hasNext)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = selectFirst(sel.cardLink) ?: return null
        val href = fixUrl(a.attr("href"))
        if (!href.contains("/dizi/") && !href.contains("/webtv/")) return null

        val title = a.selectFirst(sel.cardTitle)?.let {
            it.attr(sel.titleAttr.split(",").first()) ?: it.text()
        }?.takeIf { it.isNotBlank() } ?: return null

        val poster = a.selectFirst(sel.cardPoster)?.let { img ->
            sel.cardPosterAttr.split(",").mapNotNull { img.attr(it.trim()).takeIf { it.isNotBlank() } }.firstOrNull()
        }?.let { fixUrl(it) }

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/arama?q=${query.encodeUrl()}"
        val doc = app.get(url).document
        return doc.select("a[href*='/dizi/'], a[href*='/webtv/']").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst(sel.title)?.let { el ->
            val attr = sel.titleAttr.split(",").first()
            if (attr == "text") el.text() else el.attr(attr)
        }?.trim() ?: "Bilinmeyen Dizi"

        val poster = doc.selectFirst(sel.poster)?.let { el ->
            sel.posterAttr.split(",").mapNotNull { el.attr(it.trim()).takeIf { it.isNotBlank() } }.firstOrNull()
        }?.let { fixUrl(it) }

        val plot = doc.selectFirst(sel.description)?.let {
            if (it.tagName() == "meta") it.attr("content") else it.text()
        }

        val tags = doc.select(sel.genres).map { it.text() }

        val episodes = doc.select(sel.episodeItem).mapNotNull { el ->
            val href = fixUrl(el.attr("href"))
            if (!href.contains("/bolum/")) return@mapNotNull null
            val name = el.text().trim().ifBlank { "Bölüm" }
            newEpisode(href) { this.name = name; this.description = name }
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

        // 1. Iframe Player
        doc.selectFirst(sel.playerIframe)?.attr("src")?.takeIf { it.isNotBlank() }?.let { src ->
            loadExtractor(fixUrl(src), data, subtitleCallback, callback)
        }

        // 2. JS file: "file": "https://..."
        doc.select("script").forEach { script ->
            sel.jsVideoFile.find(script.data())?.groupValues?.get(1)?.let { videoUrl ->
                callback(newExtractorLink {
                    name = "$name - JS"
                    url = videoUrl.trim()
                    referer = data
                    quality = Qualities.Unknown.value
                    //isM3u8 = videoUrl.contains(".m3u8")
                })
            }
        }

        // 3. WebView Resolver for protected players (atvplayer.com, etc.)
        WebViewResolver(
            interceptUrl = { it.contains(".m3u8") || it.contains(".mp4") || it.contains("master") || it.contains("chunk") },
            onLinkFound = { link ->
                callback(newExtractorLink {
                    name = "$name - Protected"
                    url = link.url
                    referer = link.headers["Referer"] ?: data
                    headers = link.headers
                    quality = Qualities.Unknown.value
                    //isM3u8 = link.url.contains(".m3u8")
                })
            }
        ).resolveUsingWebView(res.url, res.text)

        return true
    }

    private fun fixUrl(url: String): String {
        if (url.isBlank()) return ""
        if (url.startsWith("http")) return url
        if (url.startsWith("//")) return "https:$url"
        return mainUrl + url.removePrefix("/")
    }
}
