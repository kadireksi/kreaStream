package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.json.JSONObject
import android.util.Log
import android.util.Base64
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import java.io.ByteArrayOutputStream

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

    // ---------- Pastel palette (option C) ----------
    private val genreColors = listOf(
        "#A8DADC", "#F1FAEE", "#E9C46A", "#F2D7EE", "#FFDDD2",
        "#ECD5E3", "#DCD6F7", "#B9FBC0", "#C1FBA4", "#FFE5B4"
    )

    // ---------- Icon map (emoji used as drawable text) ----------
    private val genreIcon = mapOf(
        "News" to "\uD83D\uDCF0",     // 📰
        "Haber" to "\uD83D\uDCF0",
        "Sports" to "\uD83C\uDFC5",   // 🏅
        "Spor" to "\uD83C\uDFC5",
        "Kids" to "\ud83e\uddd2",     // 🧒
        "Çocuk" to "\uD83E\uDE78",
        "Music" to "\uD83C\uDFB5",    // 🎵
        "Müzik" to "\uD83C\uDFB5",
        "Yerli Müzik" to "\ud83c\uddf9\ud83c\uddf7 \uD83C\uDFB5",
        "Yabancı Müzik" to "\ud83c\uddfa\ud83c\uddf8 \uD83C\uDFB5",
        "Movies" to "\uD83C\uDFAC",   // 🎬
        "Film" to "\uD83C\uDFAC",
        "TV" to "\uD83D\uDCFA",       // 📺
        "Radio" to "\uD83D\uDCFB",    // 📻
        "Diğer" to "\u2B50"          // ⭐
    )

    // ---------- PNG Generator helpers ----------
    private fun createPosterPngBase64(title: String, icon: String?, colorHex: String): String {
        val width = 600
        val height = 340
        val radius = 32f

        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        // Background
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.style = Paint.Style.FILL
        paint.color = parseColorSafe(colorHex)
        val rect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, radius, radius, paint)

        // Icon (emoji) - draw near top
        if (!icon.isNullOrEmpty()) {
            val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            iconPaint.textAlign = Paint.Align.CENTER
            iconPaint.textSize = 200f
            iconPaint.typeface = Typeface.DEFAULT
            iconPaint.color = 0xFFFFFFFF.toInt()
            canvas.drawText(icon, width / 2f, height * 0.10f, iconPaint)
        }

        // Title text - bottom area
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textPaint.color = 0xFFFFFFFF.toInt()

        // Fit text size to width
        var textSize = 46f
        textPaint.textSize = textSize
        val maxWidth = width * 0.88f
        while (textPaint.measureText(title) > maxWidth && textSize > 18f) {
            textSize -= 2f
            textPaint.textSize = textSize
        }
        canvas.drawText(title, width / 2f, height * 0.70f, textPaint)

        // Convert to PNG base64
        val baos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, baos)
        val bytes = baos.toByteArray()
        baos.close()
        bmp.recycle()
        return "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    // safe parse color from hex like "#RRGGBB"
    private fun parseColorSafe(hex: String): Int {
        return try {
            val clean = hex.trim().removePrefix("#")
            val colorInt = when (clean.length) {
                6 -> (0xFF shl 24) or (clean.substring(0, 2).toInt(16) shl 16) or
                        (clean.substring(2, 4).toInt(16) shl 8) or clean.substring(4, 6).toInt(16)
                8 -> (clean.substring(0, 2).toInt(16) shl 24) or
                        (clean.substring(2, 4).toInt(16) shl 16) or
                        (clean.substring(4, 6).toInt(16) shl 8) or clean.substring(6, 8).toInt(16)
                else -> 0xFF000000.toInt()
            }
            colorInt
        } catch (e: Exception) {
            0xFF000000.toInt()
        }
    }

    // ---------- Genre detection ----------
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
                    val icon = genreIcon[genreName] ?: genreIcon["Diğer"]
                    val poster = createPosterPngBase64(genreName.uppercase(), icon, color)
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
                    val poster = s.poster
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
                    posterUrl = s.poster
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
                    posterUrl = s.poster
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
                    posterUrl = s.poster
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
                    this.posterUrl = streamItem.poster
                })
            ) {
                this.posterUrl = streamItem.poster
                this.backgroundPosterUrl = streamItem.backgroundPosterUrl
                this.plot = if (streamItem.is_audio) "Canlı Radyo Yayını" else "Canlı TV Yayını"
            }
        }

        // 4) Channel-config pages (fallback to channel listing handling)
        val channel = channels.find { url.startsWith(it.base_url) }
        if (channel == null) throw Error("Channel config not found for this URL")
        val doc = app.get(url, headers = channel.headers).document

        val selectors = channel.detail_page?.selectors ?: emptyMap()
        val title = doc.smartSelect(selectors["title"]) ?: "Bilinmeyen Başlık"
        val plot = doc.smartSelect(selectors["description"]) ?: channel.name
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
            val scripts = doc.select("script")
            methods.forEach { method ->
                when (method.type) {
                    "iframe" -> {
                        val iframes = doc.select(method.selector ?: "iframe")
                        for (iframe in iframes) {
                            var src = iframe.attr("src")
                            if (src.isNotEmpty()) {
                                src = fixUrl(src, channel.base_url)
                                try {
                                    loadExtractor(src, subtitleCallback, callback)
                                } catch (e: Exception) {
                                    try {
                                        loadExtractor(src, channel.base_url, subtitleCallback, callback)
                                    } catch (_: Exception) {}
                                }
                            }
                        }
                    }
                    "regex" -> {
                        for (script in scripts) {
                            val html = script.html()
                            val m = Regex(
                                """https?://[^"'\s]+?\.m3u8[^"'\s]*""",
                                RegexOption.IGNORE_CASE
                            ).find(html)

                            if (m != null) {
                                val found = m.value
                                M3u8Helper.generateM3u8(
                                    source = channel.name,
                                    streamUrl = found,
                                    referer = channel.base_url,
                                    headers = mapOf("Referer" to channel.base_url)
                                ).forEach(callback)

                                return true
                            }
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
                        for (script in scripts) {
                            val scriptContent = script.html()

                            if (
                                scriptContent.contains("playerConfig", ignoreCase = true) ||
                                scriptContent.contains("streamUrl", ignoreCase = true) ||
                                scriptContent.contains("contentUrl", ignoreCase = true)
                            ) {
                                val m3u8Url = extractM3u8FromJson(scriptContent)
                                if (m3u8Url != null) {
                                    M3u8Helper.generateM3u8(
                                        source = channel.name,
                                        streamUrl = m3u8Url,
                                        referer = channel.base_url
                                    ).forEach(callback)

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
                    if (obj.has("streamUrl") || obj.has("contentUrl")) {
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
}
