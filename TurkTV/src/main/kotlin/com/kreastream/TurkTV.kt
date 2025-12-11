package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.M3u8Helper
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.json.JSONObject
import android.util.Log

class TurkTV : MainAPI() {

    override var name = "Türk TV"
    override var mainUrl = "https://turktv.local"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Live, TvType.Movie)

    private val channelsJsonUrl = "https://raw.githubusercontent.com/kadireksi/kreaStream/builds/channels.json"
    private val streamsJsonUrl = "https://raw.githubusercontent.com/kadireksi/kreaStream/builds/streams.json"

    // in-memory configs
    private var channels: List<ChannelConfig> = emptyList()
    private var streams: List<StreamItem> = emptyList()
    private var loaded = false

    // ----------------------
    // Models (inline)
    // ----------------------
    data class StreamItem(
        val id: String,
        val title: String,
        val group: String = "TV",
        val url: String,
        val poster: String? = null,
        val type: String = "Live",
        val is_audio: Boolean = false,
        val headers: Map<String, String> = emptyMap(),
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

    // ----------------------
    // Config loader (remote only for CI safety)
    // ----------------------
    private suspend fun ensureConfig() {
        if (loaded) return
        try {
            // fetch channels
            try {
                val chText = app.get(channelsJsonUrl).text
                channels = parseJson(chText)
            } catch (e: Exception) {
                Log.e("TurkTV", "Failed to fetch channels.json: ${e.message}")
                channels = emptyList()
            }

            // fetch streams
            try {
                val stText = app.get(streamsJsonUrl).text
                streams = parseJson(stText)
            } catch (e: Exception) {
                Log.e("TurkTV", "Failed to fetch streams.json: ${e.message}")
                streams = emptyList()
            }
        } finally {
            loaded = true
        }
    }

    // ----------------------
    // HTML helpers (Element extension)
    // ----------------------
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

    // ----------------------
    // URL helpers
    // ----------------------
    private fun fixUrl(url: String, baseUrl: String): String {
        if (url.startsWith("http")) return url
        val cleanBase = baseUrl.trimEnd('/')
        val cleanUrl = url.trimStart('/')
        return "$cleanBase/$cleanUrl"
    }

    private fun buildListingUrl(path: String, baseUrl: String, page: Int): String {
        var p = path
        if (p.contains("{page}")) {
            p = p.replace("{page}", page.toString())
        } else if (page > 1) {
            p = if (p.contains("?")) "$p&page=$page" else "$p?page=$page"
        }
        return fixUrl(p, baseUrl)
    }

    private fun buildEpisodePageUrl(base: String, detailUrl: String, suffix: String, page: Int): String {
        if (suffix.isNotEmpty()) {
            val clean = if (detailUrl.endsWith("/")) detailUrl.dropLast(1) + suffix else detailUrl + suffix
            return if (page > 1) "$clean?page=$page" else clean
        }
        return if (page > 1) "$detailUrl?page=$page" else detailUrl
    }

    // ----------------------
    // Listing extraction (index-based loops to avoid ambiguous iterators)
    // ----------------------
    private suspend fun extractListing(api: MainAPI, ch: ChannelConfig, listing: ListingConfig): List<SearchResponse> {
        try {
            val url = buildListingUrl(listing.path, ch.base_url, 1)
            val res = api.app.get(url, headers = ch.headers)
            val doc = res.document
            val container = listing.selectors["container"] ?: return emptyList()
            val els = doc.select(container)
            val out = mutableListOf<SearchResponse>()
            var i = 0
            while (i < els.size) {
                val el = els[i]
                val title = el.smartSelect(listing.selectors["title"]) ?: run { i++; continue }
                val href = el.smartSelect(listing.selectors["url"]) ?: run { i++; continue }
                val poster = el.smartSelect(listing.selectors["poster"])
                val full = fixUrl(href, ch.base_url)
                out.add(api.newTvSeriesSearchResponse(title, full, TvType.TvSeries) { this.posterUrl = poster })
                i++
            }
            return out
        } catch (e: Exception) {
            Log.e("TurkTV", "extractListing error: ${e.message}")
            return emptyList()
        }
    }

    // ----------------------
    // Episode extraction (pages 1..5)
    // ----------------------
    private suspend fun extractEpisodes(api: MainAPI, ch: ChannelConfig, url: String): List<Episode> {
        val cfg = ch.episode_page ?: return emptyList()
        val eps = mutableListOf<Episode>()
        val seen = mutableSetOf<String>()
        for (p in 1..5) {
            try {
                val pageUrl = buildEpisodePageUrl(ch.base_url, url, cfg.path_suffix, p)
                val res = api.app.get(pageUrl, headers = ch.headers)
                val doc = res.document
                val container = cfg.selectors["container"] ?: break
                val rows = doc.select(container)
                if (rows.isEmpty()) break
                var idx = 0
                while (idx < rows.size) {
                    val r = rows[idx]
                    val name = r.smartSelect(cfg.selectors["name"]) ?: r.text()
                    val href = r.smartSelect(cfg.selectors["url"]) ?: ""
                    if (href.isNotBlank()) {
                        val full = fixUrl(href, ch.base_url)
                        if (seen.add(full)) {
                            eps.add(newEpisode(full) { this.name = name })
                        }
                    }
                    idx++
                }
            } catch (e: Exception) {
                Log.e("TurkTV", "extractEpisodes error: ${e.message}")
                break
            }
        }
        if (cfg.reverse_order) eps.reverse()
        return eps
    }

    // ----------------------
    // m3u8 extractor helper (scan inline scripts)
    // ----------------------
    private fun extractM3u8FromJson(jsonStr: String): String? {
        return try {
            var cleanJson = jsonStr.trim()
            if (cleanJson.startsWith("var ") || cleanJson.startsWith("let ") || cleanJson.startsWith("const ")) {
                cleanJson = cleanJson.substringAfterLast("= ").trim().trimEnd(';')
            }
            if (cleanJson.startsWith("{") && cleanJson.endsWith("}")) {
                val config = JSONObject(cleanJson)
                fun findInJson(obj: JSONObject): String? {
                    if (obj.has("streamUrl")) {
                        val url = obj.getString("streamUrl")
                        if (url.contains(".m3u8")) return url
                    }
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val value = obj.get(key)
                        if (value is JSONObject) {
                            val found = findInJson(value)
                            if (found != null) return found
                        }
                    }
                    return null
                }
                findInJson(config)
            } else null
        } catch (e: Exception) {
            Regex("""["']?streamUrl["']?\s*:\s*["']([^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE).find(jsonStr)?.groupValues?.get(1)
        }
    }

    // ----------------------
    // Extraction router (attempt loadExtractor in both signatures where necessary)
    // ----------------------
    private suspend fun runExtractionMethod(
        api: MainAPI,
        method: ExtractionMethod,
        ch: ChannelConfig,
        doc: Document,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        when (method.type) {
            "iframe" -> {
                val sel = method.selector ?: "iframe"
                val iframes = doc.select(sel)
                var found = false
                var i = 0
                while (i < iframes.size) {
                    val iframe = iframes[i]
                    var src = iframe.attr("src")
                    if (src.isNotEmpty()) {
                        src = fixUrl(src, ch.base_url)
                        // try signature without referer first (most common in your repo)
                        try {
                            api.loadExtractor(src, subtitleCallback, callback)
                        } catch (e1: Exception) {
                            // try with referer
                            try { api.loadExtractor(src, ch.base_url, subtitleCallback, callback) } catch (e2: Exception) { /* ignore */ }
                        }
                        found = true
                    }
                    i++
                }
                return found
            }

            "regex" -> {
                val html = doc.html()
                val regex = method.pattern?.toRegex()
                regex?.find(html)?.groupValues?.get(1)?.let { link ->
                    val cleanLink = link.replace("\\/", "/")
                    callback(newExtractorLink(source = "Source", name = "Source", url = cleanLink) {
                        this.referer = ch.base_url
                        this.quality = Qualities.Unknown.value
                        this.type = if (cleanLink.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    })
                    return true
                }
            }

            "css" -> {
                val link = doc.select(method.selector ?: "").attr(method.attribute ?: "src")
                if (link.isNotEmpty()) {
                    val fixed = fixUrl(link, ch.base_url)
                    callback(newExtractorLink(source = "Source", name = "Source", url = fixed) {
                        this.referer = ch.base_url
                        this.quality = Qualities.Unknown.value
                        this.type = if (fixed.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    })
                    return true
                }
            }

            "direct_m3u8" -> {
                val scripts = doc.select("script")
                var found = false
                var i = 0
                while (i < scripts.size) {
                    val s = scripts[i].html()
                    Regex("(https?://[^\"']+\\.m3u8[^\"']*)").find(s)?.groupValues?.get(1)?.let { u ->
                        // produce extractor links via M3u8Helper so Cloudstream handles qualities
                        try {
                            M3u8Helper.generateM3u8(source = name, streamUrl = u, referer = ch.base_url, headers = mapOf("Referer" to ch.base_url)).forEach { callback(it) }
                            found = true
                        } catch (_: Exception) {
                            // fallback: emit raw link
                            callback(newExtractorLink(source = "HLS", name = "HLS", url = u) {
                                this.type = ExtractorLinkType.M3U8
                                this.quality = Qualities.Unknown.value
                            })
                            found = true
                        }
                    }
                    i++
                }
                return found
            }

            "youtube" -> {
                Regex("youtube.*(?:v=|embed/)([a-zA-Z0-9_-]+)").find(doc.html())?.groupValues?.get(1)?.let { id ->
                    val yt = "https://www.youtube.com/watch?v=$id"
                    try {
                        api.loadExtractor(yt, subtitleCallback, callback)
                        return true
                    } catch (e: Exception) {
                        try { api.loadExtractor(yt, ch.base_url, subtitleCallback, callback); return true } catch (_: Exception) { }
                    }
                }
            }
        }
        return false
    }

    // ----------------------
    // MainAPI overrides
    // ----------------------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        ensureConfig()

        val pages = mutableListOf<HomePageList>()

        // Live: group streams by group and list unique genres as items
        val grouped: Map<String, List<StreamItem>> = streams.groupBy { it.group ?: "TV" }
        for ((groupName, list) in grouped) {
            val header = when (groupName.lowercase()) {
                "radio", "radyo" -> "📻 Canlı Radyo"
                else -> "📺 Canlı TV"
            }

            val genres = mutableListOf<String>()
            var idx = 0
            while (idx < list.size) {
                val sp = list[idx]
                sp.genres.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { g ->
                    if (!genres.contains(g)) genres.add(g)
                }
                idx++
            }

            val items = mutableListOf<SearchResponse>()
            for (g in genres) {
                val fakeUrl = "${header}_genre::${groupName}::${g}"
                items.add(newTvSeriesSearchResponse(g, fakeUrl, TvType.TvSeries) {
                    this.posterUrl = ""
                    this.plot = "Canlı: $g"
                })
            }
            if (items.isNotEmpty()) pages.add(HomePageList(header, items, true))
        }

        // Channel listings
        var ci = 0
        while (ci < channels.size) {
            val ch = channels[ci]
            if (ch.active) {
                var li = 0
                while (li < ch.listings.size) {
                    val listing = ch.listings[li]
                    val items = extractListing(this, ch, listing)
                    if (items.isNotEmpty()) pages.add(HomePageList("${ch.name} • ${listing.title}", items, listing.ishorizontal))
                    li++
                }
            }
            ci++
        }

        return newHomePageResponse(pages)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        ensureConfig()
        val results = mutableListOf<SearchResponse>()
        var i = 0
        while (i < streams.size) {
            val s = streams[i]
            if (s.title.contains(query, true)) results.add(newTvSeriesSearchResponse(s.title, s.url, TvType.Live) { this.posterUrl = s.poster })
            i++
        }

        var ci = 0
        while (ci < channels.size) {
            val ch = channels[ci]
            var li = 0
            while (li < ch.listings.size) {
                val listing = ch.listings[li]
                results += extractListing(this, ch, listing).filter { it.title.contains(query, true) }
                li++
            }
            ci++
        }

        return results
    }

    override suspend fun load(url: String): LoadResponse {
        ensureConfig()

        // Genre fake URL: "<header>_genre::<group>::<genre>"
        if (url.contains("_genre::")) {
            val parts = url.split("::")
            if (parts.size >= 3) {
                val group = parts[1]
                val genre = parts[2]
                val filtered = streams.filter { it.group.equals(group, true) }.filter { it.genres.split(",").map { s -> s.trim() }.contains(genre) }
                val episodes = mutableListOf<Episode>()
                var i = 0
                while (i < filtered.size) {
                    val s = filtered[i]
                    episodes.add(newEpisode(s.url) { this.name = s.title; this.posterUrl = s.poster })
                    i++
                }
                return newTvSeriesLoadResponse(genre, "${group}::$genre", TvType.TvSeries, episodes) {
                    this.posterUrl = episodes.firstOrNull()?.posterUrl
                    this.plot = "Canlı yayınlar - $genre"
                }
            }
        }

        // direct stream item
        streams.firstOrNull { it.url == url }?.let { si ->
            return newTvSeriesLoadResponse(si.title, url, TvType.Live,
                listOf(newEpisode(url) { this.name = si.title; this.posterUrl = si.poster })
            ) {
                this.posterUrl = si.poster
                this.plot = if (si.is_audio) "Canlı Radyo" else "Canlı TV"
            }
        }

        // channel detail
        val ch = channels.firstOrNull { url.startsWith(it.base_url) }
        if (ch != null) {
            val doc = try { app.get(url, headers = ch.headers).document } catch (e: Exception) { null }
            val title = doc?.let { d ->
                val tSel = ch.detail_page?.selectors?.get("title") ?: ""
                if (tSel.isNotEmpty()) d.selectFirst(tSel)?.text()?.trim() ?: ch.name else ch.name
            } ?: ch.name

            val episodes = extractEpisodes(this, ch, url)
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = doc?.selectFirst(ch.detail_page?.selectors?.get("poster") ?: "")?.attr("src")
                this.plot = doc?.selectFirst(ch.detail_page?.selectors?.get("description") ?: "")?.text()
            }
        }

        throw ErrorLoadingException("Unknown url format: $url")
    }

    // exact signature your CI expects for loadLinks
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        ensureConfig()

        // A) direct live stream
        val live = streams.find { it.url == data }
        if (live != null) {
            val type = if (live.url.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            callback(newExtractorLink(source = this.name, name = live.title, url = live.url) {
                this.referer = live.headers["Referer"] ?: ""
                this.quality = Qualities.Unknown.value
                this.type = type
                this.headers = live.headers
            })
            return true
        }

        // B) VOD extraction from a channel page
        val channel = channels.firstOrNull { data.startsWith(it.base_url) } ?: return false
        val methods = channel.video_extraction?.methods ?: emptyList()
        val doc = try { app.get(data, headers = channel.headers).document } catch (e: Exception) { null }
        if (doc == null) return false

        var found = false
        var mi = 0
        while (mi < methods.size) {
            val method = methods[mi]
            if (runExtractionMethod(this, method, channel, doc, subtitleCallback, callback)) found = true
            mi++
        }

        // fallback: direct m3u8 string passed in data
        if (!found && data.contains(".m3u8", ignoreCase = true)) {
            try {
                M3u8Helper.generateM3u8(source = name, streamUrl = data, referer = mainUrl).forEach { callback(it) }
                found = true
            } catch (_: Exception) { /* ignore */ }
        }

        return found
    }
}
