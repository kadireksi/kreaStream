package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.*
import org.jsoup.nodes.Element
import java.util.Base64

class CanliDizi : MainAPI() {
    override var mainUrl = "https://www.canlidizi14.com"
    override var name = "Canlı Dizi"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)
    override var lang = "tr"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = true

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = listOf(
            Triple("$mainUrl/yerli-bolumler", "Yerli Yeni Bölümler", true),
            Triple("$mainUrl/digi-bolumler", "Dijital Yeni Bölümler", true),
            Triple("$mainUrl/dijital-diziler-izle", "Dijital Diziler", true),
            Triple("$mainUrl/film-izle", "Filmler", false)
        ).mapNotNull { (url, title, isSeries) ->
            try {
                val list = if (isSeries) parseSeriesCategory(url) else parseMovieCategory(url)
                if (list.isNotEmpty()) HomePageList(title, list, true) else null
            } catch (e: Exception) {
                null
            }
        }
        return newHomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val url = "$mainUrl/search/${query.encodeToUrl()}/"
        return app.get(url).document.select("div.single-item")
            .mapNotNull { parseSearchItem(it) }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        return when {
            doc.selectFirst("div.incontentx") != null -> loadSeriesNew(doc, url)
            url.contains("/film") || doc.selectFirst("div.bolumust") == null -> loadMovie(doc, url)
            else -> loadSeriesOld(doc, url)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = app.get(data).text

        return extractBetaPlayer(html, data, callback) ||
                extractCanliPlayer(html, data, callback) ||
                extractDirectVideo(html, data, callback) ||
                extractYouTube(html, data, callback) ||
                extractFromIframes(app.get(data).document, data, callback)
    }

    private suspend fun extractBetaPlayer(
        html: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val playerUrl = Regex("""https?://[^\s"']*betaplayer\.site[^\s"']*""")
            .find(html)?.value ?: return false

        println("BetaPlayer → $playerUrl")
        return app.get(playerUrl, referer = referer).text.let { content ->
            extractBetaListBase64(content, playerUrl, callback) ||
                    extractBetaM3U8(content, playerUrl, callback) ||
                    extractDirectVideo(content, playerUrl, callback)
        }
    }

    private suspend fun extractCanliPlayer(
        html: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val playerUrl = Regex("""https?://[^\s"']*canliplayer\.com[^\s"']*""")
            .find(html)?.value ?: return false

        println("CanliPlayer → $playerUrl")
        return app.get(playerUrl, referer = referer).text.let { content ->
            Regex("""["']12["']\s*:\s*["']([A-Za-z0-9+/=]+)""").find(content)
                ?.groupValues?.get(1)
                ?.let { decodeBase64Video(it, playerUrl, callback, "CanliPlayer") }
                ?: extractDirectVideo(content, playerUrl, callback)
        }
    }

    private suspend fun extractBetaListBase64(
        html: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return Regex("""betaplayer\.site/list/([A-Za-z0-9+/=]+)""")
            .findAll(html)
            .any { decodeBase64Video(it.groupValues[1], referer, callback, "BetaPlayer") }
    }

    private suspend fun extractBetaM3U8(
        html: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val match = Regex("""betaplayer\.site/m3u/([A-Za-z0-9+/=]+)""").find(html) ?: return false
        return try {
            val path = String(Base64.getDecoder().decode(match.groupValues[1]))
            val url = "https://betaplayer.site/m3u/$path"
            if (url.contains(".m3u8")) {
                addLink(url, referer, callback, "BetaPlayer M3U8")
                true
            } else false
        } catch (e: Exception) { false }
    }

    private suspend fun extractDirectVideo(
        html: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val url = Regex("""["'](https?://[^\s"']+\.(m3u8|mp4)[^\s"']*)["']""")
            .find(html)?.groupValues?.get(1) ?: return false
        addLink(url, referer, callback, "Direct")
        return true
    }

    private suspend fun extractYouTube(
        html: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val id = Regex("""youtube\.com/embed/([A-Za-z0-9_-]{11})""")
            .find(html)?.groupValues?.get(1)
            ?: Regex("""v=([A-Za-z0-9_-]{11})""").find(html)?.groupValues?.get(1)
            ?: return false
        addLink("https://youtube.com/watch?v=$id", referer, callback, "YouTube")
        return true
    }

    private suspend fun extractFromIframes(
        doc: Element,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        doc.select("iframe").forEach { iframe ->
            val src = fixUrl(iframe.attr("src"))
            if (src.contains("betaplayer") || src.contains("canliplayer") || src.contains("youtube")) {
                try {
                    val subHtml = app.get(src, referer = referer).text
                    if (extractBetaPlayer(subHtml, src, callback) ||
                        extractCanliPlayer(subHtml, src, callback) ||
                        extractYouTube(subHtml, src, callback)) {
                        return true
                    }
                } catch (_: Exception) {}
            }
        }
        return false
    }

    // ====================== DECODING & LINK CREATION ======================

    private suspend fun decodeBase64Video(
        base64: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
        source: String
    ): Boolean {
        try {
            var decoded = String(Base64.getDecoder().decode(base64.trim()))
            if (decoded.matches(Regex("[A-Za-z0-9+/=]+"))) {
                decoded = String(Base64.getDecoder().decode(decoded))
            }
            if (decoded.contains("http") && (decoded.contains(".m3u8") || decoded.contains(".mp4"))) {
                addLink(decoded, referer, callback, source)
                return true
            }
            if (decoded.startsWith("/")) {
                val full = "https://betaplayer.site$decoded"
                if (full.contains(".m3u8")) {
                    addLink(full, referer, callback, source)
                    return true
                }
            }
        } catch (e: Exception) {
            println("Decode failed: ${e.message}")
        }
        return false
    }

    private suspend fun addLink(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
        source: String
    ) {
        val quality = when {
            url.contains("1080", true) -> Qualities.P1080.value
            url.contains("720", true) -> Qualities.P720.value
            url.contains("480", true) -> Qualities.P480.value
            else -> Qualities.Unknown.value
        }
        val type = if (url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

        callback(
            newExtractorLink(
                source = "$name - $source",
                name = name,
                url = url
            ) {
                this.referer = referer
                this.quality = quality
                this.type = type
            }
        )
        println("Link added: $source → $url")
    }

    // ====================== PARSERS ======================

    private fun parseSearchItem(e: Element): SearchResponse? {
        val a = e.selectFirst("div.cat-img a") ?: return null
        val href = fixUrl(a.attr("href"))
        val title = e.selectFirst("div.categorytitle a")?.text()
            ?: e.selectFirst("img")?.attr("alt")
                ?.replace(Regex("(izle|son bölüm).*", RegexOption.IGNORE_CASE), "")
                ?.trim()
            ?: return null
        val poster = e.selectFirst("img")
            ?.attr("data-wpfc-original-src")
            ?.takeIf { it.isNotBlank() }
            ?: e.selectFirst("img")?.attr("src")?.let { fixUrl(it) }

        val isMovie = href.contains("/film") || title.contains("film", true)
        return if (isMovie) {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
        }
    }

    private suspend fun parseSeriesCategory(base: String): List<SearchResponse> {
        val list = mutableListOf<SearchResponse>()
        for (p in 1..8) {
            val url = if (p == 1) base else "$base/page/$p/"
            try {
                val doc = app.get(url).document
                val items = doc.select("div.single-item").mapNotNull { parseSearchItem(it) }
                if (items.isEmpty()) break
                list.addAll(items)
            } catch (e: Exception) { break }
        }
        return list
    }

    private suspend fun parseMovieCategory(base: String): List<SearchResponse> {
        val list = mutableListOf<MovieSearchResponse>()
        for (p in 1..8) {
            val url = if (p == 1) base else "$base/page/$p/"
            try {
                val doc = app.get(url).document
                val items = doc.select("div.single-item, div.film-item").mapNotNull {
                    val a = it.selectFirst("a") ?: return@mapNotNull null
                    val href = fixUrl(a.attr("href"))
                    val title = a.selectFirst("img")?.attr("alt") ?: a.text()
                    val poster = a.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
                    newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
                }
                if (items.isEmpty()) break
                list.addAll(items)
            } catch (e: Exception) { break }
        }
        return list
    }

    private suspend fun loadSeriesNew(doc: Element, url: String): LoadResponse {
        val title = doc.selectFirst("h1.title-border")?.text()?.trim()
            ?.replace(Regex("son bölüm izle.*", RegexOption.IGNORE_CASE), "")?.trim()
            ?: throw ErrorLoadingException("Title not found")

        val poster = doc.selectFirst("div.cat-img img")?.attr("src")?.let { fixUrl(it) }
        val episodes = doc.select("div.bolumust a").mapNotNull {
            val a = it.selectFirst("div.baslik")?.text() ?: it.attr("title")
            val epNum = Regex("""(\d+)""").find(a)?.value?.toIntOrNull() ?: 1
            newEpisode(fixUrl(it.attr("href"))) {
                this.name = a
                this.episode = epNum
                this.season = 1
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
        }
    }

    private suspend fun loadSeriesOld(doc: Element, url: String): LoadResponse {
        val title = doc.selectFirst("h1")?.text()?.trim() ?: "Unknown"
        val poster = doc.selectFirst("img.poster, img.wp-post-image")?.attr("src")?.let { fixUrl(it) }
        val episodes = doc.select("div.bolumust a, div.episode-box a").mapNotNull {
            val href = fixUrl(it.attr("href"))
            val name = it.text().trim()
            val ep = Regex("""(\d+)""").find(name)?.value?.toIntOrNull() ?: 1
            newEpisode(href) { this.name = name; this.episode = ep }
        }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
        }
    }

    private suspend fun loadMovie(doc: Element, url: String): LoadResponse {
        val title = doc.selectFirst("h1")?.text()?.trim()
            ?.replace(Regex("(izle|film).*", RegexOption.IGNORE_CASE), "")?.trim()
            ?: "Movie"
        val poster = doc.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
        }
    }

    private fun String.encodeToUrl(): String = java.net.URLEncoder.encode(this, "UTF-8")
}
