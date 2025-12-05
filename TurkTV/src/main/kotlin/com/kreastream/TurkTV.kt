package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import kotlin.text.RegexOption
import org.json.JSONObject
import org.json.JSONArray
import kotlinx.coroutines.delay
import android.util.Log


class TurkTV : MainAPI() {
    override var name = "Türk TV"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Live)
    
    private val tabiiUrl = "https://www.tabii.com/tr"
    private val trt1Url = "https://www.trt1.com.tr"
    private val showTvUrl = "https://www.showtv.com.tr"
    private val atvUrl = "https://www.atv.com.tr"
    private val kanaldUrl = "https://www.kanald.com.tr"
    private val starTvUrl = "https://www.startv.com.tr"
    private val nowTvUrl = "https://www.nowtv.com.tr"
    private val liveBase = "$tabiiUrl/watch/live"

    data class Channel(
        val key: String,
        val displayName: String,
        val baseUrl: String,
        val currentPath: String,
        val archivePath: String? = null,
        val liveStream: String? = null,
        val isTrt: Boolean = false
    )

    private val channels = listOf(
        Channel("atv", "ATV", atvUrl, "/diziler", "/eski-diziler",
            null),
        Channel("kanald", "Kanal D", kanaldUrl, "/diziler/tum-diziler", "/diziler/arsiv-diziler",
            "https://demiroren.daioncdn.net/kanald/kanald.m3u8?app=kanald_web&ce=3&ppid=c1bfdca3b54034e7be9660071f17d132"),
        Channel("show", "Show TV", showTvUrl, "/diziler", null,
            null),
        Channel("star", "Star TV", starTvUrl, "/diziler/yayinda-olanlar", "/diziler/arsiv-diziler",
            "https://startv-live.ercdn.net/startv/startv.m3u8"),
        Channel("now", "NOW", nowTvUrl, "/dizi-izle", "/dizi-arsivi",
            "https://nowtv-live.ercdn.net/nowtv/nowtv.m3u8"),
        Channel("trt1", "TRT 1", trt1Url, "/diziler?archive=false", "/diziler?archive=true",
            null, isTrt = true)
    )

    override val mainPage = mainPageOf(
        "trt_series" to "TRT - Güncel Diziler",   
        "atv_current" to "ATV - Güncel Diziler",
        "show_current" to "Show TV - Diziler",
        "kanald_current" to "Kanal D - Güncel Diziler",
        "star_current" to "Star TV - Güncel Diziler",
        "now_current" to "NOW - Güncel Diziler",

        "trt_archive_series" to "TRT - Arşiv Diziler",
        "atv_archive" to "ATV - Arşiv Diziler",
        "kanald_archive" to "Kanal D - Arşiv Diziler",
        "star_archive" to "Star TV - Arşiv Diziler",
        "now_archive" to "NOW - Arşiv Diziler",

        "trt_programs" to "TRT - Programlar",
        "trt_archive_programs" to "TRT - Arşiv Programlar",

        "live_tv" to "TRT Canlı TV",
        "live_radio" to "TRT Canlı Radyo",
        "other_live" to "Diğer TV Kanalları"
    )

    // === TRT Data Structures ===
    data class TvChannel(
        val name: String,
        val slug: String,
        val streamUrl: String,
        val logoUrl: String,
        val description: String = ""
    )

    data class RadioChannel(
        val name: String,
        val slug: String,
        val streamUrl: String,
        val logoUrl: String,
        val description: String = ""
    )

    data class RawEpisode(
        val title: String,
        val url: String,
        val posterUrl: String?,
        val description: String,
        val extractedNum: Int?
    )

    // === Utility Functions ===
    private fun extractEpisodeNumber(title: String): Int? {
        return try {
            val patterns = listOf(
                Regex("""(\d{1,4})\s*\.?\s*[Bb]ölüm"""),
                Regex("""[Bb]ölüm\s*(\d{1,4})"""),
                Regex("""[Ee]pisode\s*(\d{1,4})"""),
                Regex("""\b(\d{1,4})\b""")
            )
            
            for (pattern in patterns) {
                val match = pattern.find(title)
                if (match != null) {
                    return match.groupValues[1].toIntOrNull()
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun fixTrtUrl(url: String): String = if (url.startsWith("http")) url else "$trt1Url$url"
    
    // === Live Stream Functions ===
    private suspend fun getAtvLiveStream(): String? {
        return try {
            val url = "$atvUrl/canli-yayin"
            val response = app.get(url, timeout = 10)
            val html = response.text
            
            val m3u8Regex = Regex("""https?://[^"'\s]+\.m3u8[^"'\s]*""")
            val matches = m3u8Regex.findAll(html).toList()
            
            val atvMatch = matches.find { 
                it.value.contains("atv", ignoreCase = true) && 
                it.value.contains("daioncdn", ignoreCase = true)
            }
            
            atvMatch?.value
        } catch (e: Exception) {
            Log.e("TurkTV", "Error fetching ATV live stream: ${e.message}")
            "https://trkvz.daioncdn.net/atv/atv.m3u8"
        }
    }

    private suspend fun getShowTvLiveStreams(): Map<String, String> {
        return try {
            val url = "$showTvUrl/canli-yayin"
            val response = app.get(url, timeout = 10)
            val html = response.text
            
            val streams = mutableMapOf<String, String>()
            
            val videoUrlRegex = Regex("""var\s+videoUrl\s*=\s*["']([^"']+)["']""")
            val videoUrlMatch = videoUrlRegex.find(html)
            videoUrlMatch?.let {
                val streamUrl = it.groupValues[1]
                if (streamUrl.isNotBlank() && streamUrl.contains(".m3u8")) {
                    streams["Show TV"] = streamUrl
                }
            }
            
            val showturkUrlRegex = Regex("""var\s+showturkVideoUrl\s*=\s*["']([^"']+)["']""")
            val showturkMatch = showturkUrlRegex.find(html)
            showturkMatch?.let {
                val streamUrl = it.groupValues[1]
                if (streamUrl.isNotBlank() && streamUrl.contains(".m3u8")) {
                    streams["Show Türk"] = streamUrl
                }
            }
            
            if (streams.isEmpty()) {
                val allM3u8Regex = Regex("""https?://[^"'\s]+\.m3u8[^"'\s]*""")
                val allMatches = allM3u8Regex.findAll(html).toList()
                
                val showTvMatch = allMatches.find { it.value.contains("showtv", ignoreCase = true) }
                val showturkMatch = allMatches.find { it.value.contains("showturk", ignoreCase = true) }
                
                showTvMatch?.let { streams["Show TV"] = it.value }
                showturkMatch?.let { streams["Show Türk"] = it.value }
            }
            
            streams
        } catch (e: Exception) {
            Log.e("TurkTV", "Error fetching Show TV live streams: ${e.message}")
            mapOf(
                "Show TV" to "https://showtv-live.ercdn.net/showtv/showtv.m3u8",
                "Show Türk" to "https://ciner-live.ercdn.net/showturk/playlist.m3u8"
            )
        }
    }

    // === NOW TV Specific Functions ===

    private suspend fun getNowTvSeries(isArchive: Boolean, page: Int = 1): List<SearchResponse> {
        return try {
            if (isArchive) {
                // Archive Logic
                if (page == 1) {
                    val url = "$nowTvUrl/dizi-arsivi?filter=date"
                    val doc = app.get(url, timeout = 15).document
                    
                    // Parse .list-item elements
                    return doc.select("div.list-item").mapNotNull { item ->
                        val a = item.selectFirst("a[href]") ?: return@mapNotNull null
                        val href = a.attr("href")
                        
                        // Title extraction
                        val title = item.selectFirst(".program-name strong")?.text()?.trim() 
                            ?: a.attr("title").takeIf { it.isNotBlank() }
                            ?: return@mapNotNull null
                        
                        // Image extraction
                        val img = item.selectFirst("img")?.attr("src")?.let {
                            if (it.startsWith("http")) it else nowTvUrl + it
                        }
                        
                        newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                            this.posterUrl = img
                        }
                    }
                } else {
                    // AJAX Logic for paging
                    val url = "$nowTvUrl/ajax/archive"
                    
                    val formData = mapOf(
                        "filter" to "archive",
                        "page" to page.toString(),
                        "count" to "10",
                        "type" to "series",
                        "orderby" to "id",
                        "sorting" to "desc"
                    )
                    
                                        val response = app.post(url, data = formData, headers = mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "Referer" to "$nowTvUrl/dizi-arsivi",
                        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
                    ))
                    
                    if (response.code == 200) {
                        val json = JSONObject(response.text)
                        if (json.getInt("code") == 200) {
                            val html = json.optString("data", "")
                            
                            if (html.isNotBlank()) {
                                val doc = org.jsoup.Jsoup.parse(html)
                                return doc.select("div.list-item").mapNotNull { item ->
                                    val a = item.selectFirst("a[href]") ?: return@mapNotNull null
                                    val href = a.attr("href")
                                    val title = item.selectFirst(".program-name strong")?.text()?.trim() 
                                        ?: return@mapNotNull null
                                    
                                    val img = item.selectFirst("img")?.attr("src")?.let {
                                        if (it.startsWith("http")) it else nowTvUrl + it
                                    }
                                    
                                    newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                                        this.posterUrl = img
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // Current Series Logic 
                val url = "$nowTvUrl/dizi-izle"
                val doc = app.get(url, timeout = 15).document
                
                // Parse owl-item posters
                return doc.select(".owl-item .poster").mapNotNull { item ->
                    val a = item.selectFirst("a[href]") ?: return@mapNotNull null
                    val href = a.attr("href")
                    
                    val imgEl = item.selectFirst("img")
                    val title = imgEl?.attr("alt")?.trim() ?: return@mapNotNull null
                    val poster = (imgEl.attr("data-src").takeIf { it.isNotBlank() } ?: imgEl.attr("src")).let {
                         if (it.startsWith("http")) it else nowTvUrl + it
                    }

                    newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                        this.posterUrl = poster
                    }
                }
            }
            emptyList()
        } catch (e: Exception) {
            Log.e("TurkTV", "Error loading NOW series: ${e.message}")
            emptyList()
        }
    }

    private suspend fun getNowTvEpisodes(seriesUrl: String): List<Episode> {
        return try {
            // Construct episodes URL
            val baseUrl = seriesUrl.removeSuffix("/")
            val episodesUrl = if (baseUrl.endsWith("/bolumler")) baseUrl else "$baseUrl/bolumler"
            
            val doc = app.get(episodesUrl, timeout = 15).document

            // Get season selector
            val seasonSelect = doc.selectFirst("select#video-filter-changer")
            val episodesSelect = doc.selectFirst("select#video-finder-changer")
            
            val episodes = mutableListOf<Episode>()
            
            if (episodesSelect != null) {
                // Extract episodes from the select options
                episodesSelect.select("option[data-target][value]").forEach { option ->
                    val href = option.attr("data-target")
                    val epValue = option.attr("value")
                    
                    if (href.isNotBlank() && epValue.isNotBlank()) {
                        val title = option.text().trim()
                        val epNum = epValue.toIntOrNull() ?: extractEpisodeNumber(title) ?: 0
                        
                        // Try to extract season number from season selector
                        var seasonNum = 1
                        seasonSelect?.select("option[selected]")?.firstOrNull()?.let { seasonOption ->
                            val seasonText = seasonOption.text()
                            val seasonMatch = Regex("""(\d+)""").find(seasonText)
                            seasonMatch?.let {
                                seasonNum = it.groupValues[1].toIntOrNull() ?: 1
                            }
                        }
                        
                        episodes.add(newEpisode(href) {
                            this.name = title
                            this.episode = epNum
                            this.season = seasonNum
                        })
                    }
                }
            } else {
                // Fallback: Look for episode links directly
                doc.select("a[href*='/bolum/']").forEach { a ->
                    val href = a.attr("href")
                    if (href.isNotBlank() && href.contains("/bolum/")) {
                        val title = a.text().trim().ifBlank { "Bölüm" }
                        val epNum = extractEpisodeNumber(title) ?: 0
                        
                        episodes.add(newEpisode(href) {
                            this.name = title
                            this.episode = epNum
                            this.season = 1
                        })
                    }
                }
            }
            
            // Return episodes in reverse order (newest first)
            episodes.reversed()
        } catch (e: Exception) {
            Log.e("TurkTV", "Error loading NOW episodes: ${e.message}")
            emptyList()
        }
    }
    
    private suspend fun extractNowTvLinks(data: String, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val response = app.get(data, timeout = 15)
            val html = response.text
            
            // Try to extract m3u8 URL from JavaScript
            val sourceRegex = Regex("""source\s*:\s*['"]([^'"]+\.m3u8[^'"]*)['"]""")
            val sourceMatch = sourceRegex.find(html)
            
            if (sourceMatch != null) {
                val streamUrl = sourceMatch.groupValues[1]
                M3u8Helper.generateM3u8(
                    source = "NOW",
                    streamUrl = streamUrl,
                    referer = nowTvUrl,
                    headers = mapOf("Referer" to nowTvUrl, "Origin" to nowTvUrl)
                ).forEach(callback)
                return true
            }
            
            // Try alternative regex patterns
            val patterns = listOf(
                Regex("""["']src["']\s*:\s*['"]([^'"]+\.m3u8[^'"]*)['"]"""),
                Regex("""["']file["']\s*:\s*['"]([^'"]+\.m3u8[^'"]*)['"]"""),
                Regex("""playlist\.m3u8[^"']*""")
            )
            
            for (pattern in patterns) {
                val match = pattern.find(html)
                if (match != null) {
                    var streamUrl = match.value
                    if (!streamUrl.startsWith("http")) {
                        streamUrl = "https:$streamUrl"
                    }
                    
                    M3u8Helper.generateM3u8(
                        source = "NOW",
                        streamUrl = streamUrl,
                        referer = nowTvUrl,
                        headers = mapOf("Referer" to nowTvUrl, "Origin" to nowTvUrl)
                    ).forEach(callback)
                    return true
                }
            }
            
            // Try to find any m3u8 URL in the page
            val m3u8Regex = Regex("""https?://[^"'\s]+\.m3u8[^"'\s]*""")
            val m3u8Matches = m3u8Regex.findAll(html).toList()
            
            val ercdnMatch = m3u8Matches.find { it.value.contains("nowtv-vod.ercdn.net", ignoreCase = true) }
            ercdnMatch?.let {
                M3u8Helper.generateM3u8(
                    source = "NOW",
                    streamUrl = it.value,
                    referer = nowTvUrl,
                    headers = mapOf("Referer" to nowTvUrl, "Origin" to nowTvUrl)
                ).forEach(callback)
                return true
            }
            
            false
        } catch (e: Exception) {
            Log.e("TurkTV", "Error extracting NOW links: ${e.message}")
            false
        }
    }

    // === ATV & ShowTV Series Functions (Existing) ===
    private suspend fun getAtvSeries(isArchive: Boolean, page: Int = 1): List<SearchResponse> {
        return try {
            val path = if (isArchive) "/eski-diziler" else "/diziler"
            val url = if (page == 1) atvUrl + path else "$atvUrl$path?page=$page"
            
            val doc = app.get(url, timeout = 15).document
            
            val items = if (isArchive) {
                doc.select("div.classic-series-list .category-classic-item").mapNotNull { item ->
                    val a = item.selectFirst("a.blankpage")
                    val title = a?.selectFirst("h2.title")?.text()?.trim() ?: return@mapNotNull null
                    val href = a.attr("href")
                    if (href.isBlank()) return@mapNotNull null
                    
                    val poster = item.selectFirst("img")?.attr("src")?.let {
                        if (it.startsWith("http")) it else atvUrl + it
                    }
                    
                    newTvSeriesSearchResponse(title, atvUrl + href, TvType.TvSeries) {
                        this.posterUrl = poster
                    }
                }
            } else {
                doc.select("div.category-classic-item.type-02").mapNotNull { item ->
                    val a = item.selectFirst("a.blankpage")
                    val title = a?.selectFirst("h3.title")?.text()?.trim() ?: return@mapNotNull null
                    val href = a.attr("href")
                    if (href.isBlank()) return@mapNotNull null
                    
                    val poster = item.selectFirst("img")?.attr("src")?.let {
                        if (it.startsWith("http")) it else atvUrl + it
                    }
                    
                    newTvSeriesSearchResponse(title, atvUrl + href, TvType.TvSeries) {
                        this.posterUrl = poster
                    }
                }
            }
            items
        } catch (e: Exception) {
            Log.e("TurkTV", "Error loading ATV series: ${e.message}")
            emptyList()
        }
    }

    private suspend fun getAtvEpisodes(seriesUrl: String): List<Episode> {
        return try {
            val episodesUrl = if (seriesUrl.endsWith("/")) "${seriesUrl}bolumler" else "$seriesUrl/bolumler"
            
            val doc = app.get(episodesUrl, timeout = 15).document
            val select = doc.selectFirst("select#episode-combobox")
            val episodes = if (select != null) {
                select.select("option[value]").filterNot { 
                    it.attr("disabled") == "disabled" || it.attr("value") == "bolum" 
                }.mapNotNull { option ->
                    val href = option.attr("value")
                    if (href.isBlank()) return@mapNotNull null
                    
                    val title = option.text().trim()
                    val episodeNum = extractEpisodeNumber(title) ?: 0
                    val episodeUrl = atvUrl + href
                    
                    newEpisode(episodeUrl) {
                        name = title
                        episode = episodeNum
                        season = 1
                    }
                }
            } else {
                doc.select("a[href*='/bolum/']").mapNotNull { a ->
                    val href = a.attr("href")
                    if (href.isBlank()) return@mapNotNull null
                    
                    val title = a.text().trim().ifBlank { "Bölüm" }
                    val episodeNum = extractEpisodeNumber(title) ?: 0
                    val episodeUrl = atvUrl + href
                    
                    newEpisode(episodeUrl) {
                        name = title
                        episode = episodeNum
                        season = 1
                    }
                }
            }
            episodes.reversed()
        } catch (e: Exception) {
            Log.e("TurkTV", "Error loading ATV episodes: ${e.message}")
            emptyList()
        }
    }

    private suspend fun getShowTvSeries(page: Int = 1): List<SearchResponse> {
        return try {
            val url = if (page == 1) "$showTvUrl/diziler" else "$showTvUrl/diziler?page=$page"
            
            val doc = app.get(url, timeout = 15).document
            
            val items = doc.select("ul.grid li div[data-name='box-type6']").mapNotNull { item ->
                val a = item.selectFirst("a.group")
                val title = a?.attr("title") ?: return@mapNotNull null
                val href = a.attr("href")
                if (href.isBlank() || !href.contains("/dizi/tanitim/")) return@mapNotNull null
                
                val poster = item.selectFirst("img")?.attr("src")?.let {
                    if (it.startsWith("http")) it else showTvUrl + it
                }
                
                newTvSeriesSearchResponse(title.trim(), showTvUrl + href, TvType.TvSeries) {
                    this.posterUrl = poster
                }
            }
            items
        } catch (e: Exception) {
            Log.e("TurkTV", "Error loading Show TV series: ${e.message}")
            emptyList()
        }
    }

    private suspend fun getShowTvEpisodes(seriesUrl: String): List<Episode> {
        return try {
            val doc = app.get(seriesUrl, timeout = 15).document
            
            val select = doc.selectFirst("select#seasonWithJs")
            if (select != null) {
                return select.select("option[data-href]").mapNotNull { option ->
                    val href = option.attr("data-href")
                    if (href.isBlank()) return@mapNotNull null
                    
                    val title = option.text().trim()
                    val seasonId = option.attr("data-season-id").toIntOrNull() ?: 1
                    val episodeNum = extractEpisodeNumber(title) ?: 0
                    
                    newEpisode(showTvUrl + href) {
                        name = title
                        season = seasonId
                        episode = episodeNum
                    }
                }.reversed()
            }
            
            val fallbackEpisodes = doc.select("a[href*='/dizi/tum_bolumler/']").mapNotNull { a ->
                val href = a.attr("href")
                if (href.isBlank()) return@mapNotNull null
                
                val title = a.text().trim().ifBlank { "Bölüm" }
                val episodeNum = extractEpisodeNumber(title) ?: 0
                
                newEpisode(showTvUrl + href) {
                    name = title
                    episode = episodeNum
                    season = 1
                }
            }
            fallbackEpisodes
        } catch (e: Exception) {
            Log.e("TurkTV", "Error loading Show TV episodes: ${e.message}")
            emptyList()
        }
    }
    
    // === TRT Functions ===
    private suspend fun getTvChannels(): List<TvChannel> {
        val result = mutableListOf<TvChannel>()
        try {
            val sample = "$liveBase/trt1?trackId=150002"
            val response = app.get(sample)
            val doc = response.document
            val nextData = doc.selectFirst("#__NEXT_DATA__")?.data() ?: return emptyList()

            val json = JSONObject(nextData)
            val liveChannels = json.getJSONObject("props").getJSONObject("pageProps").getJSONArray("liveChannels")

            for (i in 0 until liveChannels.length()) {
                val ch = liveChannels.getJSONObject(i)
                val name = ch.getString("title")
                val slug = ch.getString("slug")

                var logoUrl = ""
                val images = ch.getJSONArray("images")
                for (j in 0 until images.length()) {
                    val img = images.getJSONObject(j)
                    if (img.getString("imageType") == "logo") {
                        val imgName = img.getString("name")
                        logoUrl = "https://cms-tabii-public-image.tabii.com/int/$imgName"
                        break
                    }
                }
                if (logoUrl.isBlank()) continue

                var streamUrl = ""
                val media = ch.getJSONArray("media")
                for (j in 0 until media.length()) {
                    val m = media.getJSONObject(j)
                    if (m.getString("type") == "hls" && m.getString("drmSchema") == "clear") {
                        streamUrl = m.getString("url")
                        break
                    }
                }
                if (streamUrl.isBlank()) continue
                if(!name.contains("tabii")) {
                    result += TvChannel(name, slug, streamUrl, logoUrl, "$name")
                }
            }
        } catch (e: Exception) {
            Log.e("TurkTV", "getTvChannels error: ${e.message}")
        }
        return result
    }

    private suspend fun getRadioChannels(): List<RadioChannel> {
        return listOf(
            RadioChannel("TRT FM", "trt-fm", "https://trt.radyotvonline.net/trt_fm.aac", "https://cdn-i.pr.trt.com.tr/trtdinle/w480/h360/q70/12467418.jpeg", "Türkçe Pop ve güncel müzik"),
            RadioChannel("TRT Radyo 1", "trt-radyo-1", "https://trt.radyotvonline.net/trt_1.aac", "https://cdn-i.pr.trt.com.tr/trtdinle/w480/h360/q70/12467415.jpeg", "Haber, kültür ve klasik müzik"),
            RadioChannel("TRT Nağme", "trt-nagme", "https://rd-trtnagme.medya.trt.com.tr/master_128.m3u8", "https://cdn-i.pr.trt.com.tr/trtdinle/w480/h360/q70/12467465.jpeg", "Türk Sanat Müziği"),
            RadioChannel("TRT Türkü", "trt-turku", "https://rd-trtturku.medya.trt.com.tr/master_128.m3u8", "https://cdn-i.pr.trt.com.tr/trtdinle/w480/h360/q70/12467466.jpeg", "Türk Halk Müziği"),
            RadioChannel("Memleketim FM", "memleketim-fm", "https://radio-trtmemleketimfm.medya.trt.com.tr/master_128.m3u8", "https://cdn-i.pr.trt.com.tr/trtdinle/w480/h360/q70/12467512.jpeg", "24 Saat Kesintisiz Müzik"),
            RadioChannel("TRT Radyo Haber", "trt-radyo-haber", "https://trt.radyotvonline.net/trt_haber.aac", "https://cdn-i.pr.trt.com.tr/trtdinle/w480/h360/q70/12530424_0-0-2048-1536.jpeg", "Sürekli haber akışı"),
            RadioChannel("TRT Radyo 3", "trt-radyo-3", "https://rd-trtradyo3.medya.trt.com.tr/master_128.m3u8", "https://cdn-i.pr.trt.com.tr/trtdinle/w480/h360/q70/12467462.jpeg", "Klasik, caz, rock ve dünya müziği"),
            RadioChannel("TRT Kurdi", "trt-kurdi", "https://radio-trtradyo6.medya.trt.com.tr/master_128.m3u8", "https://cdn-i.pr.trt.com.tr/trtdinle/w480/h360/q70/12467484.jpeg", "Kürtçe Müzik Yayını")
        )
    }

    private suspend fun getTrtContent(contentType: String, archive: Boolean = false, page: Int = 1): List<SearchResponse> {
        return try {
            val url = if (page == 1) {
                "$trt1Url/$contentType?archive=$archive&order=title_asc"
            } else {
                "$trt1Url/$contentType/$page?archive=$archive&order=title_asc"
            }
            
            val response = app.get(url, timeout = 15)
            val document = response.document
            
            val items = document.select("div.grid_grid-wrapper__elAnh > div.h-full.w-full > a")

            items.mapNotNull { el ->
                val title = el.selectFirst("div.card_card-title__IJ9af")?.text()?.trim()
                if (title == null) return@mapNotNull null
                
                val href = el.attr("href")
                if (href.isBlank()) return@mapNotNull null
                
                var poster = el.selectFirst("img")?.absUrl("src")
                poster = poster?.replace(Regex("webp/w\\d+/h\\d+"), "webp/w600/h338")
                    ?.replace("/q75/", "/q85/")

                newTvSeriesSearchResponse(title, fixTrtUrl(href)) {
                    this.posterUrl = poster
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    // === Extraction Helpers ===
    private fun extractShowTvM3u8(jsonStr: String): String? {
        return try {
            var cleanJson = jsonStr.trim()
            if (cleanJson.startsWith("data-hope-video='")) {
                cleanJson = cleanJson.removePrefix("data-hope-video='").removeSuffix("'")
            }

            val json = JSONObject(cleanJson)
            val media = json.getJSONObject("media")

            if (media.has("m3u8")) {
                val m3u8Array = media.getJSONArray("m3u8")
                if (m3u8Array.length() > 0) {
                    val m3u8 = m3u8Array.getJSONObject(0)
                    return m3u8.getString("src")
                }
            }

            if (media.has("mp4")) {
                val mp4Array = media.getJSONArray("mp4")
                if (mp4Array.length() > 0) {
                    val item = mp4Array.getJSONObject(0)
                    return item.getString("src")
                }
            }
            null
        } catch (e: Exception) {
            Log.e("Show TV", "Error parsing Show TV video JSON: ${e.message}")
            Regex("""["']src["']\s*:\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE)
                .find(jsonStr)?.groupValues?.get(1)
        }
    }
    
    private fun extractAtvVideoUrl(html: String): String? {
        try {
            val jsonLdRegex = Regex("""<script type="application/ld\+json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            val jsonLdMatch = jsonLdRegex.find(html)

            if (jsonLdMatch != null) {
                val jsonStr = jsonLdMatch.groupValues[1].trim()
                val json = JSONObject(jsonStr)

                if (json.optString("@type") == "VideoObject") {
                    val contentUrl = json.optString("contentUrl")
                    if (contentUrl.isNotBlank()) return contentUrl
                    val embedUrl = json.optString("embedUrl")
                    if (embedUrl.isNotBlank()) return "$atvUrl$embedUrl"
                }
            }
            val videoRegex = Regex("""["']contentUrl["']\s*:\s*["']([^"']+\.(?:mp4|m3u8)[^"']*)["']""")
            val match = videoRegex.find(html)
            if (match != null) return match.groupValues[1]

        } catch (_: Exception) {}
        return null
    }

    private fun extractAtvM3u8FromPage(html: String): String? {
        return try {
            val m3u8Regex = Regex("""https?://[^"'\s]+\.m3u8[^"'\s]*""")
            val matches = m3u8Regex.findAll(html).toList()
            val ercdnMatch = matches.find { it.value.contains("atv-vod.ercdn.net") }
            if (ercdnMatch != null) return ercdnMatch.value
            val atvMatch = matches.find { it.value.contains("atv", ignoreCase = true) }
            atvMatch?.value
        } catch (e: Exception) {
            null
        }
    }

    private fun extractM3u8FromJson(jsonStr: String): String? {
        return try {
            var cleanJson = jsonStr.trim()
            if (cleanJson.startsWith("var ") || cleanJson.startsWith("let ") || cleanJson.startsWith("const ")) {
                cleanJson = cleanJson.substringAfterLast("= ").trim().trimEnd(';')
            }
            if (cleanJson.startsWith("{") && cleanJson.endsWith("}")) {
                val config = JSONObject(cleanJson)
                var streamUrl = config.optString("streamUrl")
                if (streamUrl.contains(".m3u8")) return streamUrl
                return null
            }
            null
        } catch (e: Exception) {
            Regex("""["']?streamUrl["']?\s*:\s*["']([^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE)
                .find(jsonStr)?.groupValues?.get(1)
        }
    }

    private suspend fun extractVideoPlayerScripts(data: String, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val doc = app.get(data, timeout = 10).document
            val scripts = doc.select("script")
            val baseUrl = data.substringBefore("/", data)
            
            for (script in scripts) {
                val scriptContent = script.html()
                val m3u8Url = extractM3u8FromJson(scriptContent)
                if (m3u8Url != null) {
                    M3u8Helper.generateM3u8(
                        source = "Player JSON",
                        streamUrl = m3u8Url,
                        referer = baseUrl,
                        headers = mapOf("Referer" to baseUrl)
                    ).forEach(callback)
                    return true
                }

                val jwPlayerRegex = Regex("""(jwplayer\(|videoPlayer\.setup)\(.*?({.*?})\);""", RegexOption.DOT_MATCHES_ALL)
                val match = jwPlayerRegex.find(scriptContent)
                
                if (match != null) {
                    val configStr = match.groupValues[2]
                    val config = JSONObject(configStr)
                    val file = config.optString("file")
                    val sources = config.optJSONArray("sources")
                    val streamUrl = if (file.isNotBlank() && (file.contains(".mp4") || file.contains(".m3u8"))) {
                        file
                    } else if (sources != null && sources.length() > 0) {
                        sources.getJSONObject(0).optString("file")
                    } else {
                        null
                    }
                    if (streamUrl != null) {
                        if (streamUrl.contains(".m3u8")) {
                            M3u8Helper.generateM3u8(source = "Player Config", streamUrl = streamUrl, referer = baseUrl).forEach(callback)
                        } else {
                            callback(newExtractorLink("Player Config", "MP4", streamUrl) { this.referer = baseUrl })
                        }
                        return true
                    }
                }
            }
            return false
        } catch (e: Exception) {
            return false
        }
    }

    private suspend fun extractGeneralIframe(data: String, baseUrl: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val doc = app.get(data, timeout = 10).document
            val iframe = doc.selectFirst("iframe[src*='player']")
            if (iframe != null) {
                val src = iframe.attr("src")
                if (src.isNotBlank()) {
                    val fullSrc = if (src.startsWith("http")) src else "https:$src"
                    return loadExtractor(fullSrc, data, subtitleCallback, callback)
                }
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun extractAtvLinks(data: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val response = app.get(data, timeout = 10)
            val html = response.text
            
            val jsonLdUrl = extractAtvVideoUrl(html)
            if (jsonLdUrl != null) {
                if (jsonLdUrl.contains(".m3u8")) {
                    M3u8Helper.generateM3u8(source = "ATV", streamUrl = jsonLdUrl, referer = atvUrl, headers = mapOf("Referer" to atvUrl)).forEach(callback)
                } else {
                    callback(newExtractorLink("ATV", "ATV - MP4", jsonLdUrl) { this.referer = atvUrl })
                }
                return true
            }
            
            val pageM3u8 = extractAtvM3u8FromPage(html)
            if (pageM3u8 != null) {
                M3u8Helper.generateM3u8(source = "ATV", streamUrl = pageM3u8, referer = atvUrl).forEach(callback)
                return true
            }
            if (extractVideoPlayerScripts(data, callback)) return true
            return extractGeneralIframe(data, atvUrl, subtitleCallback, callback)
        } catch (e: Exception) {
            false
        }
    }
    
    private suspend fun extractShowTvLinks(data: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val doc = app.get(data, timeout = 10).document
            val hopeVideoDiv = doc.selectFirst("div.hope-video[data-hope-video]")
            if (hopeVideoDiv != null) {
                val videoJson = hopeVideoDiv.attr("data-hope-video")
                val streamUrl = extractShowTvM3u8(videoJson)
                
                if (streamUrl != null) {
                    try {
                        val subtitleMatch = Regex("""["']subtitles["']\s*:\s*\[([^\]]+)\]""").find(videoJson)
                        subtitleMatch?.let {
                            val subtitlesStr = it.groupValues[1]
                            val subtitleRegex = Regex("""["']src["']\s*:\s*["']([^"']+\.vtt[^"']*)["']""")
                            subtitleRegex.findAll(subtitlesStr).forEach { match ->
                                subtitleCallback.invoke(SubtitleFile("Türkçe", match.groupValues[1]))
                            }
                        }
                    } catch (_: Exception) {}
                    
                    if (streamUrl.contains(".m3u8")) {
                        M3u8Helper.generateM3u8(source = "Show TV", streamUrl = streamUrl, referer = showTvUrl).forEach(callback)
                    } else {
                        callback(newExtractorLink("Show TV", "Show TV - MP4", streamUrl) { this.referer = showTvUrl })
                    }
                    return true
                }
            }
            return extractGeneralIframe(data, showTvUrl, subtitleCallback, callback)
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun extractOtherChannelLinks(data: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val channel = channels.find { data.contains(it.baseUrl, ignoreCase = true) }
        val channelBaseUrl = channel?.baseUrl ?: data.substringBefore("/", data)
        val channelName = channel?.displayName ?: "Diğer Kanal"

        return try {
            if (extractVideoPlayerScripts(data, callback)) return true
            if (extractGeneralIframe(data, channelBaseUrl, subtitleCallback, callback)) return true
            val html = app.get(data, timeout = 10).text
            val m = Regex("""https?://[^"'\s]+?\.m3u8[^"'\s]*""", RegexOption.IGNORE_CASE).find(html)
            if (m != null) {
                M3u8Helper.generateM3u8(source = channelName, streamUrl = m.value, referer = channelBaseUrl).forEach(callback)
                return true
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun getChannelSeries(channel: Channel, isArchive: Boolean, page: Int): List<SearchResponse> {
        return try {
            val path = if (isArchive) channel.archivePath ?: channel.currentPath else channel.currentPath
            val url = if (page == 1) channel.baseUrl + path else "${channel.baseUrl}$path?page=$page"
            
            val doc = app.get(url, timeout = 15).document
            
            val items = doc.select("a[href*='/dizi/'], .series-card a, .card a, article a").mapNotNull { el ->
                var href = el.attr("href")
                if (href.startsWith("/")) href = channel.baseUrl + href
                if (!href.contains("/dizi/") && !href.contains("/program/")) return@mapNotNull null
                
                val title = el.attr("title").takeIf { it.isNotBlank() }
                    ?: el.selectFirst("h3, h2, .title, .name, img")?.attr("alt")
                    ?: el.text().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                
                val poster = el.selectFirst("img")?.attr("data-src") 
                    ?: el.selectFirst("img")?.attr("src")
                    ?.let { if (it.startsWith("http")) it else channel.baseUrl + it }
                
                newTvSeriesSearchResponse(title.trim(), href, TvType.TvSeries) {
                    this.posterUrl = poster
                }
            }
            items
        } catch (e: Exception) {
            Log.e("TurkTV", "Error loading ${channel.displayName} series: ${e.message}")
            emptyList()
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = when (request.data) {
            "trt_series" -> getTrtContent("diziler", archive = false, page = page)
            "trt_archive_series" -> getTrtContent("diziler", archive = true, page = page)
            "trt_programs" -> getTrtContent("programlar", archive = false, page = page)
            "trt_archive_programs" -> getTrtContent("programlar", archive = true, page = page)
            
            "atv_current" -> getAtvSeries(isArchive = false, page = page)
            "atv_archive" -> getAtvSeries(isArchive = true, page = page)
            "kanald_current" -> getChannelSeries(channels[1], isArchive = false, page = page)
            "kanald_archive" -> getChannelSeries(channels[1], isArchive = true, page = page)
            "show_current" -> getShowTvSeries(page)
            "star_current" -> getChannelSeries(channels[3], isArchive = false, page = page)
            "star_archive" -> getChannelSeries(channels[3], isArchive = true, page = page)
            
            // Updated NOW TV Handlers
            "now_current" -> getNowTvSeries(isArchive = false, page = page)
            "now_archive" -> getNowTvSeries(isArchive = true, page = page)
            
            "live_tv" -> {
                val tvChannels = getTvChannels()
                tvChannels.map { ch ->
                    newLiveSearchResponse(ch.name, ch.streamUrl, TvType.Live) {
                        this.posterUrl = ch.logoUrl
                    }
                }
            }
            "live_radio" -> {
                val radioChannels = getRadioChannels()
                radioChannels.map { ch ->
                    newLiveSearchResponse(ch.name, ch.streamUrl, TvType.Live) {
                        this.posterUrl = ch.logoUrl
                    }
                }
            }
            "other_live" -> {
                val liveItems = mutableListOf<SearchResponse>()
                val atvStream = getAtvLiveStream()
                atvStream?.let {
                    liveItems.add(newLiveSearchResponse("ATV Canlı", it, TvType.Live) {
                        this.posterUrl = "https://i.tmgrup.com.tr/atv/c/i/player_imaj_v3.png"
                    })
                }
                
                val showStreams = getShowTvLiveStreams()
                showStreams.forEach { (name, streamUrl) ->
                    liveItems.add(newLiveSearchResponse(name, streamUrl, TvType.Live) {
                        this.posterUrl = when (name) {
                            "Show TV" -> "https://www.showtv.com.tr/images/showtv-logo.png"
                            "Show Türk" -> "https://www.showtv.com.tr/images/showturk-logo.png"
                            else -> "https://www.showtv.com.tr/favicon.ico"
                        }
                    })
                }
                
                channels.filterNot { it.isTrt || it.key == "atv" || it.key == "show" }.forEach { ch ->
                    ch.liveStream?.let {
                        liveItems.add(newLiveSearchResponse("${ch.displayName} Canlı", it, TvType.Live) {
                            this.posterUrl = "${ch.baseUrl}/favicon.ico"
                        })
                    }
                }
                liveItems
            }
            else -> emptyList()
        }

        val hasNext = when {
            request.data.startsWith("trt_") -> items.isNotEmpty() && page <= 30
            request.data.startsWith("now_") -> items.isNotEmpty()
            request.data == "show_current" -> items.isNotEmpty()
            request.data.startsWith("atv_") || request.data.endsWith("_current") || request.data.endsWith("_archive") -> items.isNotEmpty()
            else -> false
        }

        return newHomePageResponse(
            listOf(HomePageList(request.name, items, true)),
            hasNext = hasNext
        )
    }

    override suspend fun load(url: String): LoadResponse {
        // Handle live stream URLs
        if (url.contains(".m3u8", ignoreCase = true) || url.contains(".aac", ignoreCase = true)) {
            val name = when {
                url.contains("atv", ignoreCase = true) -> "ATV"
                url.contains("showtv", ignoreCase = true) -> "Show TV"
                url.contains("showturk", ignoreCase = true) -> "Show Türk"
                url.contains("nowtv", ignoreCase = true) -> "NOW"
                else -> "Canlı Yayın"
            }
            return newMovieLoadResponse(name, url, TvType.Live, url) {
                this.posterUrl = "https://www.trt.net.tr/logos/our-logos/corporate/trt.png"
            }
        }

        // Handle ATV content
        if (url.contains(atvUrl) && (url.contains("/diziler/") || url.endsWith("/") || url.matches(Regex("$atvUrl/[^/]+$")))) {
            try {
                val doc = app.get(url, timeout = 15).document
                val title = doc.selectFirst("h1")?.text()?.trim()
                    ?: doc.selectFirst("meta[property='og:title']")?.attr("content")
                    ?: doc.selectFirst("h2.title, h3.title")?.text()?.trim()
                    ?: throw ErrorLoadingException("Başlık bulunamadı")
                
                val plot = doc.selectFirst("meta[name=description]")?.attr("content") ?: ""
                val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
                    ?: doc.selectFirst("img[src*='.jpg']")?.attr("src")
                    ?.let { if (it.startsWith("http")) it else atvUrl + it }
                
                val episodes = getAtvEpisodes(url)
                return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.plot = plot
                }
            } catch (e: Exception) {
                throw ErrorLoadingException("ATV dizisi yüklenemedi: ${e.message}")
            }
        }

        // Handle Show TV content
        if (url.contains(showTvUrl) && url.contains("/dizi/tanitim/")) {
            try {
                val doc = app.get(url, timeout = 15).document
                val title = doc.selectFirst("h1")?.text()?.trim()
                    ?: doc.selectFirst("meta[property='og:title']")?.attr("content")
                    ?: throw ErrorLoadingException("Başlık bulunamadı")
                
                val plot = doc.selectFirst("meta[name=description]")?.attr("content") ?: ""
                val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
                    ?: doc.selectFirst("img[src*='.jpg']")?.attr("src")
                    ?.let { if (it.startsWith("http")) it else showTvUrl + it }
                
                val episodes = getShowTvEpisodes(url)
                return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.plot = plot
                }
            } catch (e: Exception) {
                throw ErrorLoadingException("Show TV dizisi yüklenemedi: ${e.message}")
            }
        }

        // Handle NOW TV Content
        if (url.contains(nowTvUrl)) {
            try {
                val doc = app.get(url, timeout = 15).document
                
                val title = doc.selectFirst("h1")?.text()?.trim() 
                    ?: doc.selectFirst("meta[property='og:title']")?.attr("content")
                    ?: "NOW Dizi"
                    
                val plot = doc.selectFirst("meta[name='description']")?.attr("content") 
                    ?: doc.selectFirst(".program-desc")?.text()?.trim()
                    ?: ""
                    
                val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
                    ?: doc.selectFirst("img[src*='poster']")?.attr("src")
                    ?: doc.selectFirst("img[src*='thumbnail']")?.attr("src")?.let {
                        if (it.startsWith("http")) it else nowTvUrl + it
                    }
                
                val episodes = getNowTvEpisodes(url)
                
                return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.plot = plot
                }
            } catch (e: Exception) {
                throw ErrorLoadingException("NOW dizisi yüklenemedi: ${e.message}")
            }
        }

        // Handle TRT content
        if (url.contains(trt1Url)) {
            try {
                val doc = app.get(url, timeout = 15).document
                val title = doc.selectFirst("h1")?.text()?.trim() ?: throw ErrorLoadingException("Başlık bulunamadı")
                val plot = doc.selectFirst("meta[name=description]")?.attr("content") ?: ""
                var poster = doc.selectFirst("picture.card_card-image__T64bP img")?.attr("src")?.let { fixTrtUrl(it) }
                poster = poster?.replace(Regex("webp/w\\d+/h\\d+"), "webp/w600/h338")?.replace("/q75/", "/q85/")

                val basePath = if (url.contains("/diziler/")) "diziler" else "programlar"
                val slug = url.removePrefix("$trt1Url/$basePath/").substringBefore("/")
                val episodesPath = "bolum"
                val rawEpisodes = mutableListOf<RawEpisode>()
                var pageNum = 1
                var more = true

                while (more && pageNum <= 30) {
                    try {
                        val epUrl = if (pageNum == 1) "$trt1Url/$basePath/$slug/$episodesPath" else "$trt1Url/$basePath/$slug/$episodesPath/$pageNum"
                        val epDoc = app.get(epUrl, timeout = 10).document
                        val pageRaws = epDoc.select("div.grid_grid-wrapper__elAnh > div.h-full.w-full > a")
                            .mapNotNull { el ->
                                val epTitle = el.selectFirst("div.card_card-title__IJ9af")?.text()?.trim() ?: return@mapNotNull null
                                val href = el.attr("href")
                                var img = el.selectFirst("img")?.absUrl("src")
                                img = img?.replace(Regex("webp/w\\d+/h\\d+"), "webp/w600/h338")?.replace("/q75/", "/q85/")
                                val desc = el.selectFirst("p.card_card-description__0PSTi")?.text()?.trim() ?: ""
                                val extracted = extractEpisodeNumber(epTitle)
                                RawEpisode(epTitle, fixTrtUrl(href), img, desc, extracted)
                            }

                        if (pageRaws.isNotEmpty()) {
                            rawEpisodes += pageRaws
                            pageNum++
                            delay(100)
                        } else more = false
                    } catch (e: Exception) { 
                        more = false 
                    }
                }
                val numbered = rawEpisodes.filter { it.extractedNum != null && it.extractedNum!! > 0 }.sortedBy { it.extractedNum }
                val unnumbered = rawEpisodes.filter { it.extractedNum == null || it.extractedNum == 0 }
                var nextEpNum = if (numbered.isNotEmpty()) numbered.last().extractedNum!! + 1 else 1
                val episodes = numbered.map { 
                    newEpisode(it.url) { name = it.title; posterUrl = it.posterUrl; episode = it.extractedNum!!; description = it.description }
                } + unnumbered.map { 
                    newEpisode(it.url) { name = it.title; posterUrl = it.posterUrl; episode = nextEpNum++; description = it.description }
                }

                return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.plot = plot
                }
            } catch (e: Exception) {
                throw ErrorLoadingException("TRT içeriği yüklenemedi: ${e.message}")
            }
        }

        // Handle other channels
        val channel = channels.find { url.contains(it.baseUrl, ignoreCase = true) } ?: throw ErrorLoadingException("Kanal bulunamadı")
        try {
            val doc = app.get(url, timeout = 15).document
            val title = doc.selectFirst("h1, .seo-h1, meta[property='og:title']")
                ?.let { if (it.tagName() == "meta") it.attr("content") else it.text() } 
                ?: doc.title().substringBefore(" | ")
            val plot = doc.selectFirst(".description, meta[name='description']")?.attr("content") ?: ""
            val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
            
            val episodes = doc.select("a[href*='/bolum/'], a[href*='/video/'], .episode-list a, .episode-item a")
                .mapNotNull { el ->
                    var href = el.attr("href")
                    if (href.startsWith("/")) href = channel.baseUrl + href
                    if (!href.contains("/bolum/") && !href.contains("/video/")) return@mapNotNull null
                    val epTitle = el.attr("title").takeIf { it.isNotBlank() } ?: el.text()
                    newEpisode(href) { name = epTitle.trim() }
                }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        } catch (e: Exception) {
            throw ErrorLoadingException("Dizi yüklenemedi: ${e.message}")
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // 1. Handle direct stream URLs
        val name = when {
            data.contains("atv", ignoreCase = true) -> "ATV"
            data.contains("showtv", ignoreCase = true) -> "Show TV"
            data.contains("nowtv", ignoreCase = true) -> "NOW"
            else -> "Canlı Yayın"
        }

        if (data.contains(".m3u8", ignoreCase = true)) {
            M3u8Helper.generateM3u8(source = name, streamUrl = data, referer = data.substringBeforeLast("/")).forEach(callback)
            return true
        } else if (data.endsWith(".aac", ignoreCase = true)) {
            callback(newExtractorLink(source = name, name = "Audio AAC", url = data) { this.quality = Qualities.Unknown.value })
            return true
        }

        // 2. Channel Specific Extractors
        if (data.contains(atvUrl)) return extractAtvLinks(data, subtitleCallback, callback)
        if (data.contains(showTvUrl)) return extractShowTvLinks(data, subtitleCallback, callback)
        if (data.contains(nowTvUrl)) return extractNowTvLinks(data, callback)
        
        if (data.contains(kanaldUrl) || data.contains(starTvUrl)) {
            return extractOtherChannelLinks(data, subtitleCallback, callback)
        }
        
        // 3. YouTube & Generic
        if (data.contains("youtube.com") || data.contains("youtu.be")) {
            return loadExtractor(data, "", subtitleCallback, callback)
        }

        val channel = channels.find { data.contains(it.baseUrl, ignoreCase = true) }
        val baseUrl = channel?.baseUrl ?: data.substringBefore("/", data)

        try {
            if (extractVideoPlayerScripts(data, callback)) return true
            if (extractGeneralIframe(data, baseUrl, subtitleCallback, callback)) return true
        } catch (e: Exception) {
            Log.e("TurkTV", "Final fallback extraction failed for $data: ${e.message}")
        }
        return false
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()

        // Live Channels
        getTvChannels().filter { it.name.contains(query, ignoreCase = true) }.forEach { ch ->
            out += newLiveSearchResponse(ch.name, ch.streamUrl, TvType.Live) { posterUrl = ch.logoUrl }
        }
        getRadioChannels().filter { it.name.contains(query, ignoreCase = true) }.forEach { ch ->
            out += newLiveSearchResponse(ch.name, ch.streamUrl, TvType.Live) { posterUrl = ch.logoUrl }
        }

        // Live Streams (ATV/Show/NOW etc)
        try {
            val atvStream = getAtvLiveStream()
            if (atvStream != null && "ATV".contains(query, ignoreCase = true)) {
                out += newLiveSearchResponse("ATV Canlı", atvStream, TvType.Live) { posterUrl = "https://i.tmgrup.com.tr/atv/c/i/player_imaj_v3.png" }
            }
        } catch (_: Exception) {}
        
        try {
            val showStreams = getShowTvLiveStreams()
            showStreams.forEach { (name, streamUrl) ->
                if (name.contains(query, ignoreCase = true)) {
                    out += newLiveSearchResponse(name, streamUrl, TvType.Live) { posterUrl = "https://www.showtv.com.tr/favicon.ico" }
                }
            }
        } catch (_: Exception) {}

        // Other channels (including NOW Live if configured in channels list)
        channels.filterNot { it.isTrt || it.key == "atv" || it.key == "show" }.forEach { ch ->
            ch.liveStream?.let {
                if (ch.displayName.contains(query, ignoreCase = true)) {
                    out += newLiveSearchResponse("${ch.displayName} Canlı", it, TvType.Live) { posterUrl = "${ch.baseUrl}/favicon.ico" }
                }
            }
        }

        // TRT Content
        try {
            val sUrl = "$trt1Url/arama/$query?contenttype=series"
            app.get(sUrl, timeout = 10).document
                .select("div.grid_grid-wrapper__elAnh > div.h-full.w-full > a").mapNotNull { el ->
                    val title = el.selectFirst("div.card_card-title__IJ9af")?.text()?.trim() ?: return@mapNotNull null
                    val href = el.attr("href")
                    if (!href.contains("/diziler/")) return@mapNotNull null
                    var poster = el.selectFirst("img")?.absUrl("src")
                    poster = poster?.replace(Regex("webp/w\\d+/h\\d+"), "webp/w600/h338")?.replace("/q75/", "/q85/")
                    out += newTvSeriesSearchResponse(title, fixTrtUrl(href)) { this.posterUrl = poster }
                }
        } catch (_: Exception) {}

        // ATV Content
        try {
            val currentUrl = "$atvUrl/arama?q=$query"
            app.get(currentUrl, timeout = 10).document.select("a[href^='/'], a.blankpage").forEach { el ->
                val href = el.attr("href")
                if (!href.contains("/") || href.contains("http")) return@forEach
                val title = el.attr("title").takeIf { it.isNotBlank() } ?: el.selectFirst("h3.title, h2.title")?.text() ?: return@forEach
                val poster = el.selectFirst("img")?.attr("src")?.let { if (it.startsWith("http")) it else atvUrl + it }
                out += newTvSeriesSearchResponse(title.trim(), atvUrl + href, TvType.TvSeries) { this.posterUrl = poster }
            }
        } catch (_: Exception) {}

        // Show TV Content
        try {
            val searchUrl = "$showTvUrl/arama?q=$query"
            app.get(searchUrl, timeout = 10).document.select("a[href*='/dizi/tanitim/']").forEach { el ->
                val href = el.attr("href")
                val title = el.attr("title").takeIf { it.isNotBlank() } ?: el.selectFirst("h3, .title")?.text() ?: return@forEach
                val poster = el.selectFirst("img")?.attr("src")?.let { if (it.startsWith("http")) it else showTvUrl + it }
                out += newTvSeriesSearchResponse(title.trim(), showTvUrl + href, TvType.TvSeries) { this.posterUrl = poster }
            }
        } catch (_: Exception) {}

        // NOW TV Content Search
        try {
            // Search in current series
            val currentUrl = "$nowTvUrl/dizi-izle"
            val currentDoc = app.get(currentUrl, timeout = 10).document
            currentDoc.select(".owl-item .poster a[href]").forEach { el ->
                val href = el.attr("href")
                val title = el.selectFirst("img")?.attr("alt")?.trim() ?: return@forEach
                if (title.contains(query, ignoreCase = true)) {
                    val poster = el.selectFirst("img")?.attr("data-src")?.let {
                        if (it.startsWith("http")) it else nowTvUrl + it
                    }
                    out += newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
                }
            }
            
            // Search in archive series (first page only for performance)
            val archiveUrl = "$nowTvUrl/dizi-arsivi?filter=date"
            val archiveDoc = app.get(archiveUrl, timeout = 10).document
            archiveDoc.select("div.list-item a[href]").forEach { el ->
                val href = el.attr("href")
                val title = el.selectFirst(".program-name strong")?.text()?.trim() ?: return@forEach
                if (title.contains(query, ignoreCase = true)) {
                    val poster = el.selectFirst("img")?.attr("src")?.let {
                        if (it.startsWith("http")) it else nowTvUrl + it
                    }
                    out += newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
                }
            }
        } catch (_: Exception) {}

        // Generic Channel Search
        for (channel in channels.filterNot { it.isTrt || it.key == "atv" || it.key == "show" || it.key == "now" }) {
            try {
                val searchUrl = "${channel.baseUrl}/search?q=${query}"
                val doc = app.get(searchUrl, timeout = 10).document
                doc.select("a[href*='/dizi/']").forEach { el ->
                    var href = el.attr("href")
                    if (href.startsWith("/")) href = channel.baseUrl + href
                    val title = el.attr("title").takeIf { it.isNotBlank() } ?: el.selectFirst("h3, .title")?.text() ?: return@forEach
                    val poster = el.selectFirst("img")?.attr("src")?.let { if (it.startsWith("http")) it else channel.baseUrl + it }
                    out += newTvSeriesSearchResponse(title.trim(), href, TvType.TvSeries) { this.posterUrl = poster }
                }
            } catch (_: Exception) {}
        }

        return out
    }
}
