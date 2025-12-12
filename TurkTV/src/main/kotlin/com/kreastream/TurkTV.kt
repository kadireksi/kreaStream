package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.json.JSONObject
import android.util.Log

class TurkTV : MainAPI() {

    override var name = "Türk TV"
    override var mainUrl = "https://turktv.local" // Virtual URL
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Live, TvType.Movie)

    private val dummyTvUrl = "${mainUrl}/tv"
    private val dummyRadioUrl = "${mainUrl}/radio"

    private val channelsJsonUrl = "https://raw.githubusercontent.com/kadireksi/kreaStream/builds/channels.json"
    private val streamsJsonUrl = "https://raw.githubusercontent.com/kadireksi/kreaStream/builds/streams.json"

    // ---------- Data classes ----------
    data class StreamItem(
        val id: String,
        val title: String,
        val group: String = "TV",
        val url: String,
        val poster: String? = null,
        val type: String = "Live",
        val is_audio: Boolean = false,
        val backgroundPosterUrl: String? = null,
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
        val ishorizontal: Boolean
    )

    data class DetailConfig(
        val selectors: Map<String, String>
    )

    data class EpisodePageConfig(
        val path_suffix: String = "",
        val selectors: Map<String, String>,
        val reverse_order: Boolean = false
    )

    data class VideoExtractionConfig(
        val methods: List<ExtractionMethod> = emptyList()
    )

    data class ExtractionMethod(
        val type: String, // "iframe", "regex", "direct_m3u8", "css", "youtube"
        val selector: String? = null,
        val pattern: String? = null,
        val attribute: String? = null
    )

    private var channels: List<ChannelConfig> = emptyList()
    private var streams: List<StreamItem> = emptyList()

    // ---------- Helpers: colors / posters / genre detection ----------
    private val genreColors = listOf(
        "#E63946", "#457B9D", "#2A9D8F", "#F4A261", "#E9C46A",
        "#8A4FFF", "#FF6B6B", "#4ECDC4", "#1A535C", "#B56576"
    )

    private fun genrePosterSvg(text: String, color: String): String {
        val safe = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val svg = """
            <svg xmlns="http://www.w3.org/2000/svg" width="400" height="600">
              <rect width="100%" height="100%" fill="$color"/>
              <text x="50%" y="50%" dominant-baseline="middle" text-anchor="middle"
                    font-size="56" fill="white" font-family="Arial" font-weight="700">
                $safe
              </text>
            </svg>
        """.trimIndent()

        return "data:image/svg+xml;base64," + android.util.Base64.encodeToString(
            svg.toByteArray(),
            android.util.Base64.NO_WRAP
        )
    }

    private fun detectGenre(title: String, raw: String?): String {
        if (!raw.isNullOrBlank()) return raw.trim()
        val lower = title.lowercase()
        return when {
            "spor" in lower || "sport" in lower -> "Sports"
            "haber" in lower || "news" in lower -> "News"
            "çocuk" in lower || "kids" in lower -> "Kids"
            "muzik" in lower || "music" in lower -> "Music"
            "film" in lower || "movie" in lower -> "Movies"
            else -> "Diğer"
        }
    }

    // ---------- Fetch remote config ----------
    private suspend fun fetchConfiguration() {
        if (channels.isEmpty() || streams.isEmpty()) {
            try {
                val chanText = app.get(channelsJsonUrl).text
                channels = parseJson<List<ChannelConfig>>(chanText)
            } catch (e: Exception) {
                Log.e("TurkTV", "Failed to load channels.json: ${e.message}")
                channels = emptyList()
            }

            try {
                val streamText = app.get(streamsJsonUrl).text
                streams = parseJson<List<StreamItem>>(streamText)
            } catch (e: Exception) {
                Log.e("TurkTV", "Failed to load streams.json: ${e.message}")
                streams = emptyList()
            }
        }
    }

    // ---------- Core: getMainPage ----------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page == 1) fetchConfiguration()

        val pages = mutableListOf<HomePageList>()

        // 1) GROUPS (TV / Radio / others) -> show genres as series or streams when no genres
        val grouped = streams.groupBy { it.group.ifBlank { "TV" } }
            .toSortedMap(String.CASE_INSENSITIVE_ORDER)

        for ((groupName, list) in grouped) {
            val header = when (groupName.lowercase()) {
                "radio", "radyo" -> "📻 $groupName"
                else -> "📺 $groupName"
            }

            // Build genres (auto-detect if not provided)
            val genres = list.groupBy { detectGenre(it.title, it.genres) }
                .toSortedMap(String.CASE_INSENSITIVE_ORDER)

            if (genres.isNotEmpty()) {
                val items = genres.entries.mapIndexed { idx, (genreName, streamsInGenre) ->
                    val color = genreColors[idx % genreColors.size]
                    val poster = genrePosterSvg(genreName.uppercase(), color)
                    // Use a custom scheme that Cloudstream will not rewrite
                    val genreUrl = "csgenre://${groupName}/${genreName}"
                    newTvSeriesSearchResponse(genreName, genreUrl, TvType.TvSeries) {
                        this.posterUrl = poster
                    }
                }
                pages.add(HomePageList(header, items, true))
            } else {
                // No genres -> show streams directly
                val items = list.sortedBy { it.title.lowercase() }.map { s ->
                    val poster = if (s.is_audio) audioWavePosterDataUrl(s.title) else s.poster
                    newTvSeriesSearchResponse(s.title, s.url, TvType.Live) {
                        this.posterUrl = poster
                    }
                }
                pages.add(HomePageList(header, items, true))
            }
        }

        // 2) Add channel-config sections (from channels.json) after group sections
        channels.filter { it.active }.forEach { channel ->
            channel.listings.forEach { listing ->
                try {
                    val url = buildListingUrl(listing.path, channel.base_url, page)
                    val doc = app.get(url, headers = channel.headers).document
                    val items = extractItems(doc, listing.selectors, channel.base_url)
                    if (items.isNotEmpty()) {
                        pages.add(HomePageList("${channel.name} - ${listing.title}", items, listing.ishorizontal))
                    }
                } catch (e: Exception) {
                    Log.e("TurkTV", "Error loading ${channel.name}: ${e.message}")
                }
            }
        }

        return newHomePageResponse(pages)
    }

    // Build listing URL (pagination aware)
    private fun buildListingUrl(path: String, baseUrl: String, page: Int): String {
        var p = path
        if (p.contains("{page}")) {
            p = p.replace("{page}", page.toString())
        } else if (page > 1) {
            p = if (p.contains("?")) "$p&page=$page" else "$p?page=$page"
        }
        return fixUrl(p, baseUrl)
    }

    // ---------- Search ----------
    override suspend fun search(query: String): List<SearchResponse> {
        fetchConfiguration()
        val results = mutableListOf<SearchResponse>()
        streams.filter { it.title.contains(query, true) }.forEach {
            results.add(it.toSearchResponse())
        }
        // Also include channels.json quick matches (optional)
        channels.filter { it.name.contains(query, true) }.forEach { ch ->
            results.add(newMovieSearchResponse(ch.name, ch.base_url, TvType.TvSeries).apply {
                posterUrl = null
            })
        }
        return results
    }

    // ---------- Load (handle genre scheme, dummy tv/radio, stream items, channels) ----------
    override suspend fun load(url: String): LoadResponse {
        // Ensure configuration loaded
        fetchConfiguration()

        // 1) Genre handler: support csgenre:// and legacy genre:// occurrences
        val cleanedGenrePrefix = when {
            url.startsWith("csgenre://") -> url.removePrefix("csgenre://")
            url.contains("csgenre://") -> url.substringAfter("csgenre://")
            url.contains("genre://") -> url.substringAfter("genre://")
            else -> null
        }
        if (cleanedGenrePrefix != null) {
            val parts = cleanedGenrePrefix.split("/")
            val groupName = parts.getOrNull(0) ?: return newTvSeriesLoadResponse("Hata", url, TvType.TvSeries, emptyList())
            val genreName = parts.getOrNull(1) ?: return newTvSeriesLoadResponse("Hata", url, TvType.TvSeries, emptyList())

            val filtered = streams.filter {
                it.group.equals(groupName, true) && detectGenre(it.title, it.genres).equals(genreName, true)
            }.sortedBy { it.title.lowercase() }

            val episodes = filtered.mapIndexed { idx, s ->
                newEpisode(s.url) {
                    name = s.title
                    episode = idx + 1
                    posterUrl = if (s.is_audio) audioWavePosterDataUrl(s.title) else s.poster
                }
            }

            return newTvSeriesLoadResponse("$groupName • $genreName", url, TvType.TvSeries, episodes) {
                this.plot = "$groupName kategorisi altında '$genreName' içerikleri."
            }
        }

        // 2) Dummy TV / Radio entries (show live lists)
        if (url == dummyTvUrl) {
            val tvStreams = streams.filter { it.group.equals("TV", true) || it.group.isBlank() }
            val episodes = tvStreams.mapIndexed { i, s ->
                newEpisode(s.url) {
                    name = s.title
                    posterUrl = if (s.is_audio) audioWavePosterDataUrl(s.title) else s.poster
                    episode = i + 1
                }
            }
            return newTvSeriesLoadResponse("📺 Canlı TV", dummyTvUrl, TvType.TvSeries, episodes) {
                this.plot = "Canlı TV kanalları"
            }
        }

        if (url == dummyRadioUrl) {
            val radioStreams = streams.filter { it.group.equals("Radio", true) || it.group.equals("Radyo", true) }
            val episodes = radioStreams.mapIndexed { i, s ->
                newEpisode(s.url) {
                    name = s.title
                    posterUrl = if (s.is_audio) audioWavePosterDataUrl(s.title) else s.poster
                    episode = i + 1
                }
            }
            return newTvSeriesLoadResponse("📻 Canlı Radyo", dummyRadioUrl, TvType.TvSeries, episodes) {
                this.plot = "Canlı radyo kanalları"
            }
        }

        // 3) Direct stream item (live) - match by exact URL
        val streamItem = streams.find { it.url == url }
        if (streamItem != null) {
            return newTvSeriesLoadResponse(streamItem.title, url, TvType.Live,
                listOf(newEpisode(url) {
                    this.name = streamItem.title
                    this.posterUrl = if (streamItem.is_audio) audioWavePosterDataUrl(streamItem.title) else streamItem.poster
                })
            ) {
                this.posterUrl = if (streamItem.is_audio) audioWavePosterDataUrl(streamItem.title) else streamItem.poster
                this.backgroundPosterUrl = if (streamItem.is_audio) streamItem.poster else streamItem.backgroundPosterUrl
                this.plot = if (streamItem.is_audio) "Canlı Radyo Yayını" else "Canlı TV Yayını"
            }
        }

        // 4) Channel-config pages (fallback to channel listing handling)
        val channel = channels.find { url.startsWith(it.base_url) }
        if (channel == null) throw Error("Channel config not found for this URL")
        val doc = app.get(url, headers = channel.headers).document

        val selectors = channel.detail_page?.selectors ?: emptyMap()
        val title = doc.smartSelect(selectors["title"]) ?: "Bilinmeyen Başlık"
        val plot = doc.smartSelect(selectors["description"])
        val poster = doc.smartSelect(selectors["poster"])?.let { fixUrl(it, channel.base_url) }
        val year = doc.smartSelect(selectors["year"])?.filter { it.isDigit() }?.toIntOrNull()
        val tags = doc.smartSelect(selectors["tags"], all = true)

        val episodes = mutableListOf<Episode>()
        val suffix = channel.episode_page?.path_suffix ?: ""
        val epSelectors = channel.episode_page?.selectors

        if (epSelectors != null) {
            val seenUrls = mutableSetOf<String>()
            for (pIndex in 1..5) {
                try {
                    val episodeDoc = if (suffix.isNotEmpty()) {
                        val cleanUrl = if (url.endsWith("/")) url.dropLast(1) else url
                        val pageSuffix = if (pIndex == 1) suffix else "$suffix${if (suffix.contains("?")) "&" else "?"}page=$pIndex"
                        app.get("$cleanUrl$pageSuffix", headers = channel.headers).document
                    } else {
                        val pageUrl = if (pIndex == 1) url else "$url${if (url.contains("?")) "&" else "?"}page=$pIndex"
                        app.get(pageUrl, headers = channel.headers).document
                    }

                    val container = epSelectors["container"] ?: ""
                    val rows = episodeDoc.select(container)
                    if (rows.isEmpty()) break

                    rows.forEach { row ->
                        val epName = row.smartSelect(epSelectors["name"]) ?: "Bölüm ${episodes.size + 1}"
                        val epUrlRaw = row.smartSelect(epSelectors["url"])
                        if (!epUrlRaw.isNullOrBlank()) {
                            val fullEpUrl = fixUrl(epUrlRaw, channel.base_url)
                            if (seenUrls.add(fullEpUrl)) {
                                episodes.add(newEpisode(fullEpUrl) {
                                    this.name = epName
                                    this.episode = episodes.size + 1
                                    this.posterUrl = poster
                                })
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("TurkTV", "Episode pagination fetch error: ${e.message}")
                    break
                }
            }
            if (channel.episode_page.reverse_order) episodes.reverse()
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
        }
    }

    // ---------- loadLinks (extraction) ----------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        fetchConfiguration()

        // A) Direct live stream from streams.json
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

        // B) Channel-config extraction
        val channel = channels.find { data.startsWith(it.base_url) } ?: return false
        val methods = channel.video_extraction?.methods ?: emptyList()

        try {
            val doc = app.get(data, headers = channel.headers).document
            methods.forEach { method ->
                when (method.type) {
                    "iframe" -> {
                        doc.select(method.selector ?: "iframe").forEach { iframe ->
                            var src = iframe.attr("src")
                            if (src.isNotEmpty()) {
                                src = fixUrl(src, channel.base_url)
                                try {
                                    loadExtractor(src, subtitleCallback, callback)
                                } catch (e: Exception) {
                                    try { loadExtractor(src, channel.base_url, subtitleCallback, callback) } catch (_: Exception) {}
                                }
                            }
                        }
                    }
                    "regex" -> {
                        val html = doc.html()
                        val regex = method.pattern?.toRegex()
                        regex?.find(html)?.groupValues?.get(1)?.let { link ->
                            val cleanLink = link.replace("\\/", "/")
                            callback(newExtractorLink(source = "Source", name = "Source", url = cleanLink) {
                                this.referer = channel.base_url
                                this.quality = Qualities.Unknown.value
                                this.type = if (cleanLink.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            })
                        }
                    }
                    "css" -> {
                        val link = doc.select(method.selector ?: "").attr(method.attribute ?: "src")
                        if (link.isNotEmpty()) {
                            val fixed = fixUrl(link, channel.base_url)
                            callback(newExtractorLink(source = "Source", name = "Source", url = fixed) {
                                this.referer = channel.base_url
                                this.quality = Qualities.Unknown.value
                                this.type = if (fixed.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            })
                        }
                    }
                    "direct_m3u8" -> {
                        val scripts = doc.select("script")
                        for (script in scripts) {
                            val scriptContent = script.html()
                            if (scriptContent.contains("playerConfig", ignoreCase = true) || scriptContent.contains("streamUrl", ignoreCase = true)) {
                                val m3u8Url = extractM3u8FromJson(scriptContent)
                                if (m3u8Url != null) {
                                    M3u8Helper.generateM3u8(source = name, streamUrl = m3u8Url, referer = channel.base_url).forEach { callback(it) }
                                    return true
                                }
                            }
                        }
                    }
                    "youtube" -> {
                        val yt = doc.selectFirst("iframe[src*='youtube.com/embed']")?.attr("src")
                            ?.let { "https://www.youtube.com/watch?v=${it.substringAfter("embed/").substringBefore("?")}" }
                            ?: Regex("""https://www\.youtube\.com/watch\?v=([a-zA-Z0-9_-]+)""").find(doc.html())?.groupValues?.get(1)
                                ?.let { "https://www.youtube.com/watch?v=$it" }
                        if (yt != null) {
                            try { loadExtractor(yt, subtitleCallback, callback) } catch (e: Exception) { try { loadExtractor(yt, channel.base_url, subtitleCallback, callback) } catch (_: Exception) {} }
                            return true
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("TurkTV", "Error extracting links: ${e.message}")
        }

        return false
    }

    // ---------- Utility functions ----------
    private fun Element.smartSelect(selectorStr: String?): String? {
        if (selectorStr.isNullOrEmpty()) return null
        if (selectorStr.startsWith("self@")) {
            val attr = selectorStr.substringAfter("self@")
            return if (attr == "text") this.text().trim() else this.attr(attr).trim()
        }
        if (selectorStr == "self@text") return this.text()
        if (selectorStr == "self@value") return this.attr("value")
        val parts = selectorStr.split("@")
        val css = parts[0]
        val attr = if (parts.size > 1) parts[1] else "text"
        val el = this.selectFirst(css) ?: return null
        return when (attr) {
            "text" -> el.text().trim()
            "ownText" -> el.ownText().trim()
            "html" -> el.html()
            else -> el.attr(attr).trim()
        }
    }

    private fun Element.smartSelect(selectorStr: String?, all: Boolean): List<String> {
        if (selectorStr.isNullOrEmpty()) return emptyList()
        if (selectorStr.startsWith("self@")) {
            val attr = selectorStr.substringAfter("self@")
            return listOf(if (attr == "text") this.text().trim() else this.attr(attr).trim()).filter { it.isNotEmpty() }
        }
        val parts = selectorStr.split("@")
        val css = parts[0]
        val attr = if (parts.size > 1) parts[1] else "text"
        return this.select(css).map { el ->
            when (attr) {
                "text" -> el.text().trim()
                else -> el.attr(attr).trim()
            }
        }.filter { it.isNotEmpty() }
    }

    private fun extractItems(doc: Document, selectors: Map<String, String>, baseUrl: String): List<SearchResponse> {
        val container = selectors["container"] ?: return emptyList()
        val list = mutableListOf<SearchResponse>()
        doc.select(container).forEach { el ->
            val title = el.smartSelect(selectors["title"])
            val href = el.smartSelect(selectors["url"])
            val poster = el.smartSelect(selectors["poster"])
            if (title != null && href != null) {
                val fullUrl = fixUrl(href, baseUrl)
                val fullPoster = poster?.let { fixUrl(it, baseUrl) }
                list.add(newTvSeriesSearchResponse(title, fullUrl, TvType.TvSeries) {
                    this.posterUrl = fullPoster
                })
            }
        }
        return list
    }

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
                    if (obj.has("sources")) {
                        val sources = obj.getJSONArray("sources")
                        for (i in 0 until sources.length()) {
                            val src = sources.getJSONObject(i)
                            if (src.optString("type") == "application/x-mpegURL" || src.optString("file").contains(".m3u8")) {
                                return src.optString("file", src.optString("src", src.optString("url")))
                            }
                        }
                    }
                    if (obj.has("media") || obj.has("playlist")) {
                        val arr = if (obj.has("media")) obj.getJSONArray("media") else obj.getJSONArray("playlist")
                        for (i in 0 until arr.length()) {
                            val item = arr.getJSONObject(i)
                            if (item.optString("type") == "hls" || item.optString("format") == "hls") {
                                return item.optString("url", item.optString("src", item.optString("streamUrl")))
                            }
                        }
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
                return findInJson(config)
            }
            null
        } catch (e: Exception) {
            Regex("""["']?streamUrl["']?\s*:\s*["']([^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE).find(jsonStr)?.groupValues?.get(1)
        }
    }

    private fun fixUrl(url: String, baseUrl: String): String {
        if (url.startsWith("http")) return url
        val cleanBase = baseUrl.trimEnd('/')
        val cleanUrl = url.trimStart('/')
        return "$cleanBase/$cleanUrl"
    }

    private fun StreamItem.toSearchResponse(): SearchResponse {
        return newTvSeriesSearchResponse(this.title, this.url, TvType.Live) {
            this.posterUrl = this@toSearchResponse.poster
        }
    }

    // ---------- Small audio waveform poster (data URL) for player/poster when stream is audio-only ----------
    private fun audioWavePosterDataUrl(title: String): String {
        val safe = title.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val bars = (0 until 20).joinToString("") { i ->
            val h = (20 + (i * 3) % 80) // deterministic but varied heights (no RNG)
            val x = 10 + i * 18
            """<rect x="$x" y="${140 - h/2}" width="12" height="$h" rx="2" fill="white" opacity="${0.6 + (i % 3) * 0.1}"/>"""
        }
        val svg = """
            <svg xmlns="http://www.w3.org/2000/svg" width="360" height="360">
              <rect width="100%" height="100%" fill="#111"/>
              <g transform="translate(20,90)">$bars</g>
              <text x="50%" y="320" text-anchor="middle" fill="white" font-size="22" font-family="Arial">$safe</text>
            </svg>
        """.trimIndent()
        return "data:image/svg+xml;base64," + android.util.Base64.encodeToString(svg.toByteArray(), android.util.Base64.NO_WRAP)
    }
}
