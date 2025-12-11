package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.M3u8Helper
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.InputStreamReader

/**
 * Single-file modular Cloudstream plugin for dynamic channels + streams
 * All helpers/extractors are in this file to avoid cross-file import issues.
 *
 * Behavior:
 * - Remote-first, local-fallback JSON for channels & streams
 * - Live streams grouped by `group` and `genres`
 * - Clicking a genre opens a LoadResponse where each stream in that genre is an episode
 * - Episodes/Listing extraction and video extraction are JSON-driven
 */

class TurkTV : MainAPI() {

    override var name = "Türk TV"
    override var mainUrl = "https://turktv.local"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Live)

    // Remote JSON endpoints (change to your repo raw URLs)
    private val channelsUrl = "https://raw.githubusercontent.com/kadireksi/kreaStream/builds/channels.json"
    private val streamsUrl = "https://raw.githubusercontent.com/kadireksi/kreaStream/builds/streams.json"

    // In-memory configs
    private var channels: List<ChannelConfig> = emptyList()
    private var streams: List<StreamItem> = emptyList()
    private var loaded = false

    // -------------------------
    // Models (inline)
    // -------------------------
    data class StreamItem(
        val id: String,
        val title: String,
        val group: String? = "TV",
        val url: String,
        val poster: String? = null,
        val type: String? = "tv",
        val headers: Map<String, String> = emptyMap(),
        val is_audio: Boolean = false,
        val genres: String = ""
    )

    data class ChannelConfig(
        val id: String,
        val name: String,
        val base_url: String,
        val active: Boolean = true,
        val headers: Map<String, String> = emptyMap(),
        val listings: List<ListingConfig> = emptyList(),
        val detail_page: DetailConfig? = null,
        val episode_page: EpisodePageConfig? = null,
        val video_extraction: VideoExtractionConfig? = null
    )

    data class ListingConfig(
        val title: String,
        val path: String,
        val selectors: Map<String, String>,
        val ishorizontal: Boolean = false
    )

    data class DetailConfig(val selectors: Map<String, String>)
    data class EpisodePageConfig(val path_suffix: String = "", val selectors: Map<String, String> = emptyMap(), val reverse_order: Boolean = false)
    data class VideoExtractionConfig(val methods: List<ExtractionMethod> = emptyList())
    data class ExtractionMethod(val type: String, val selector: String? = null, val pattern: String? = null, val attribute: String? = null)

    // -------------------------
    // Config loader (remote-first, classpath fallback)
    // -------------------------
    private suspend fun ensureConfig() {
        if (loaded) return

        // helper to try remote then classpath
        suspend fun loadJson(remote: String, classpathNames: List<String>): String? {
            // try remote
            try {
                val res = app.get(remote)
                if (res.status.value in 200..299) return res.text
            } catch (_: Exception) { /* ignore remote failure */ }

            // try classpath resources (bundled in plugin assets)
            for (name in classpathNames) {
                try {
                    val stream = javaClass.classLoader.getResourceAsStream(name)
                    if (stream != null) {
                        InputStreamReader(stream).use { r -> return r.readText() }
                    }
                } catch (_: Exception) { /* ignore */ }
            }
            return null
        }

        val chText = loadJson(channelsUrl, listOf("channels.json", "assets/channels.json", "config/channels.json"))
        channels = if (!chText.isNullOrBlank()) {
            try { parseJson(chText) } catch (_: Exception) { emptyList() }
        } else emptyList()

        val stText = loadJson(streamsUrl, listOf("streams.json", "assets/streams.json", "config/streams.json"))
        streams = if (!stText.isNullOrBlank()) {
            try { parseJson(stText) } catch (_: Exception) { emptyList() }
        } else emptyList()

        loaded = true
    }

    // -------------------------
    // HTML helpers (Element extension and Document helper)
    // -------------------------
    private fun Element.smartSelect(selectorStr: String?): String? {
        if (selectorStr.isNullOrEmpty()) return null
        if (selectorStr.startsWith("self@")) {
            val attr = selectorStr.substringAfter("self@")
            return if (attr == "text") this.text().trim() else this.attr(attr).trim()
        }
        val parts = selectorStr.split("@")
        val css = parts[0]
        val attr = if (parts.size > 1) parts[1] else "text"
        val el = this.selectFirst(css) ?: return null
        return if (attr == "text") el.text().trim() else el.attr(attr).trim()
    }

    private fun Document.selectTextAll(css: String, attr: String = "text"): List<String> {
        return this.select(css).map { e -> if (attr == "text") e.text().trim() else e.attr(attr).trim() }.filter { it.isNotEmpty() }
    }

    // -------------------------
    // URL helpers
    // -------------------------
    private object UrlHelper {
        fun join(base: String, path: String): String {
            if (path.startsWith("http")) return path
            val b = base.trimEnd('/')
            val p = path.trimStart('/')
            return "$b/$p"
        }

        fun buildPagedUrl(base: String, path: String, page: Int): String {
            var p = path
            if (p.contains("{page}")) p = p.replace("{page}", page.toString())
            else if (page > 1) p = if (p.contains("?")) "$p&page=$page" else "$p?page=$page"
            return join(base, p)
        }

        fun buildEpisodePageUrl(base: String, detailUrl: String, suffix: String, page: Int): String {
            if (suffix.isNotEmpty()) {
                val clean = detailUrl.trimEnd('/') + suffix
                return if (page > 1) "$clean?page=$page" else clean
            }
            return if (page > 1) "$detailUrl?page=$page" else detailUrl
        }
    }

    // -------------------------
    // Listing loader (JSON-driven)
    // -------------------------
    private suspend fun loadListing(api: MainAPI, ch: ChannelConfig, listing: ListingConfig): List<SearchResponse> {
        try {
            val url = UrlHelper.buildPagedUrl(ch.base_url, listing.path, 1)
            val res = api.app.get(url, headers = ch.headers)
            val doc = res.document
            val container = listing.selectors["container"] ?: return emptyList()
            val list = mutableListOf<SearchResponse>()
            val elements = doc.select(container)
            for (el in elements) {
                val title = el.smartSelect(listing.selectors["title"]) ?: continue
                val href = el.smartSelect(listing.selectors["url"]) ?: continue
                val poster = el.smartSelect(listing.selectors["poster"])
                val full = UrlHelper.join(ch.base_url, href)
                list.add(api.newTvSeriesSearchResponse(title, full, TvType.TvSeries) {
                    this.posterUrl = poster
                })
            }
            return list
        } catch (e: Exception) {
            return emptyList()
        }
    }

    // -------------------------
    // Episode loader (pagination 1..5)
    // -------------------------
    private suspend fun loadEpisodes(api: MainAPI, ch: ChannelConfig, url: String): List<Episode> {
        val cfg = ch.episode_page ?: return emptyList()
        val eps = mutableListOf<Episode>()
        val seen = mutableSetOf<String>()
        for (p in 1..5) {
            val pageUrl = UrlHelper.buildEpisodePageUrl(ch.base_url, url, cfg.path_suffix, p)
            val res = api.app.get(pageUrl, headers = ch.headers)
            val doc = res.document
            val container = cfg.selectors["container"] ?: ""
            if (container.isEmpty()) break
            val rows = doc.select(container)
            if (rows.isEmpty()) break
            for (r in rows) {
                val name = r.smartSelect(cfg.selectors["name"]) ?: r.text()
                val href = r.smartSelect(cfg.selectors["url"]) ?: ""
                if (href.isBlank()) continue
                val full = UrlHelper.join(ch.base_url, href)
                if (seen.add(full)) {
                    eps.add(api.newEpisode(full) { this.name = name })
                }
            }
        }
        if (cfg.reverse_order) eps.reverse()
        return eps
    }

    // -------------------------
    // Video extractor router (basic methods supported)
    // -------------------------
    private suspend fun runExtractionMethod(
        api: MainAPI,
        method: ExtractionMethod,
        ch: ChannelConfig,
        doc: Document,
        subtitleCallback: (SubtitleFile) -> Unit,
        cb: (ExtractorLink) -> Unit
    ): Boolean {
        when (method.type) {
            "iframe" -> {
                val sel = method.selector ?: "iframe"
                val iframes = doc.select(sel)
                iframes.forEach { iframe ->
                    val src = iframe.attr(method.attribute ?: "src")
                    if (src.isNotBlank()) {
                        try {
                            api.loadExtractor(src, ch.base_url, subtitleCallback, cb)
                        } catch (_: Exception) {
                            try { api.loadExtractor(src, subtitleCallback, cb) } catch (_: Exception) { }
                        }
                    }
                }
                return iframes.isNotEmpty()
            }
            "css" -> {
                val attr = method.attribute ?: "src"
                val valStr = doc.select(method.selector ?: "").attr(attr)
                if (valStr.isNotBlank()) {
                    cb(api.newExtractorLink("css", "source", valStr) { this.quality = Qualities.Unknown.value })
                    return true
                }
            }
            "regex" -> {
                val html = doc.html()
                val pat = method.pattern ?: return false
                Regex(pat).find(html)?.groupValues?.getOrNull(1)?.let { l ->
                    cb(api.newExtractorLink("regex", "src", l) { this.quality = Qualities.Unknown.value })
                    return true
                }
            }
            "direct_m3u8" -> {
                val scripts = doc.select("script").map { it.html() }
                var found = false
                scripts.forEach { s ->
                    Regex("(https?://[^\"']+\\.m3u8[^\"']*)").find(s)?.groupValues?.get(1)?.let { u ->
                        cb(api.newExtractorLink("m3u8", "HLS", u) { this.type = ExtractorLinkType.M3U8; this.quality = Qualities.Unknown.value })
                        found = true
                    }
                }
                return found
            }
            "youtube" -> {
                Regex("youtube.*(?:v=|embed/)([a-zA-Z0-9_-]+)").find(doc.html())?.groupValues?.get(1)?.let { id ->
                    val url = "https://www.youtube.com/watch?v=$id"
                    try { api.loadExtractor(url, api.mainUrl, subtitleCallback, cb); return true } catch (_: Exception) {}
                }
            }
        }
        return false
    }

    // -------------------------
    // Live helper: create a LoadResponse listing stream items as episodes
    // -------------------------
    private fun loadGenreAsSeries(api: MainAPI, group: String, genre: String, streamsList: List<StreamItem>): LoadResponse {
        val filtered = streamsList.filter { it.group.equals(group, true) }
            .filter { it.genres.split(",").map { s -> s.trim() }.contains(genre) }

        val episodes = filtered.mapIndexed { i, s ->
            api.newEpisode(s.url) {
                this.name = s.title
                this.posterUrl = s.poster
                this.episode = i + 1
                this.season = 1
            }
        }

        return api.newTvSeriesLoadResponse(genre, "${group}::$genre", TvType.TvSeries, episodes) {
            this.posterUrl = episodes.firstOrNull()?.posterUrl
            this.plot = "Canlı yayınlar - $genre"
        }
    }

    // -------------------------
    // Stream helper: play live stream link directly
    // -------------------------
    private fun StreamItem.emitLink(callback: (ExtractorLink) -> Unit) {
        val type = if (this.url.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        callback(newExtractorLink("Live", this.title, this.url) {
            this.headers = this@emitLink.headers
            this.quality = Qualities.Unknown.value
            this.type = type
            this.referer = this@emitLink.headers["Referer"] ?: ""
        })
    }

    // -------------------------
    // MainAPI overrides
    // -------------------------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        ensureConfig()

        val pages = mutableListOf<HomePageList>()

        // Live groups -> generate HomePageList of genres for each group
        val grouped = streams.groupBy { it.group ?: "TV" }
        for ((groupName, list) in grouped) {
            val header = when (groupName.lowercase()) {
                "radio", "radyo" -> "📻 Canlı Radyo"
                else -> "📺 Canlı TV"
            }

            val genres = list.flatMap { it.genres.split(",") }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()

            val items = mutableListOf<SearchResponse>()
            for (genre in genres) {
                val fakeUrl = "${header}_genre::${groupName}::${genre}"
                items.add(newTvSeriesSearchResponse(genre, fakeUrl, TvType.TvSeries) {
                    this.posterUrl = ""
                    this.description = "Canlı: $genre"
                })
            }
            if (items.isNotEmpty()) pages.add(HomePageList(header, items, true))
        }

        // Channel listings from channels.json
        for (ch in channels.filter { it.active }) {
            for (listing in ch.listings) {
                val items = loadListing(this, ch, listing)
                if (items.isNotEmpty()) pages.add(HomePageList("${ch.name} • ${listing.title}", items, listing.ishorizontal))
            }
        }

        return newHomePageResponse(pages)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        ensureConfig()
        val results = mutableListOf<SearchResponse>()

        // search streams
        for (s in streams.filter { it.title.contains(query, true) }) {
            results.add(newTvSeriesSearchResponse(s.title, s.url, TvType.Live) {
                this.posterUrl = s.poster
            })
        }

        // search listings
        for (ch in channels) {
            for (listing in ch.listings) {
                results += loadListing(this, ch, listing).filter { it.title.contains(query, true) }
            }
        }

        return results
    }

    override suspend fun load(url: String): LoadResponse {
        ensureConfig()

        // genre fake URL handling
        if (url.contains("_genre::")) {
            val parts = url.split("::")
            if (parts.size >= 3) {
                val group = parts[1]
                val genre = parts[2]
                return loadGenreAsSeries(this, group, genre, streams)
            }
        }

        // direct stream
        streams.firstOrNull { it.url == url }?.let { si ->
            val ep = newEpisode(si.url) { this.name = si.title; this.posterUrl = si.poster }
            return newTvSeriesLoadResponse(si.title, si.url, TvType.Live, listOf(ep)) {
                this.posterUrl = si.poster
                this.plot = if (si.is_audio) "Canlı radyo" else "Canlı TV"
            }
        }

        // channel detail load
        val ch = channels.firstOrNull { url.startsWith(it.base_url) }
        if (ch != null) {
            // load details page & episodes
            val doc = app.get(url, headers = ch.headers).document
            val titleSel = ch.detail_page?.selectors?.get("title") ?: ""
            val title = if (titleSel.isNotEmpty()) doc.selectFirst(titleSel)?.text()?.trim() ?: ch.name else ch.name

            val episodes = loadEpisodes(this, ch, url)
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                val posterSel = ch.detail_page?.selectors?.get("poster") ?: ""
                this.posterUrl = if (posterSel.isNotEmpty()) doc.selectFirst(posterSel)?.attr("src") else null
                val descSel = ch.detail_page?.selectors?.get("description") ?: ""
                this.plot = if (descSel.isNotEmpty()) doc.selectFirst(descSel)?.text() else null
            }
        }

        throw ErrorLoadingException("Unknown url format: $url")
    }

    // Use the exact signature your build expects
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        ensureConfig()

        // If the requested data is a live stream URL present in streams.json, emit it directly
        streams.firstOrNull { it.url == data }?.let { si ->
            si.emitLink(callback)
            return true
        }

        // If belongs to a channel, run extractor flow
        val ch = channels.firstOrNull { data.startsWith(it.base_url) }
        if (ch != null) {
            try {
                val doc = app.get(data, headers = ch.headers).document
                var found = false
                ch.video_extraction?.methods?.forEach { m ->
                    if (runExtractionMethod(this, m, ch, doc, subtitleCallback, callback)) found = true
                }
                return found
            } catch (e: Exception) {
                // extraction failed
            }
        }

        // fallback: direct m3u8 string
        if (data.contains(".m3u8", ignoreCase = true)) {
            try {
                M3u8Helper.generateM3u8(source = name, streamUrl = data, referer = mainUrl).forEach(callback)
                return true
            } catch (_: Exception) { }
        }

        return false
    }
}
