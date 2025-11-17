package com.kreastream

import kotlin.text.RegexOption
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.json.JSONArray
import kotlinx.coroutines.delay
import android.util.Log
import java.util.Locale

class Trt : MainAPI() {
    override var mainUrl = "https://www.tabii.com"
    override var name = "TRT"
    override val supportedTypes = setOf(TvType.Live, TvType.TvSeries)
    override var lang = "tr"
    override var hasMainPage = true

    private val tabiiUrl = "https://www.tabii.com/tr"
    private val trt1Url   = "https://www.trt1.com.tr"
    private val liveBase  = "$tabiiUrl/watch/live"

    override val mainPage = mainPageOf(
        "tv" to "TRT TV Kanalları",
        "radio" to "TRT Radyo Kanalları",
        "series"  to "Güncel Diziler",
        "archiveSeries" to "Arşiv Diziler",
        "programs" to "Programlar",
        "archivePrograms" to "Arşiv Programlar"
    )

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
            Log.e("TRT", "getTvChannels error: ${e.message}")
        }
        return result
    }

    private suspend fun getRadioChannels(): List<RadioChannel> {
        return listOf(
            RadioChannel(
                name = "TRT FM",
                slug = "trt-fm",
                streamUrl = "https://trt.radyotvonline.net/trt_fm.aac",
                logoUrl = "https://cdn-i.pr.trt.com.tr/trtdinle/w480/h360/q70/12467418.jpeg",
                description = "Türkçe Pop ve güncel müzik"
            ),
            RadioChannel(
                name = "TRT Radyo 1",
                slug = "trt-radyo-1",
                streamUrl = "https://trt.radyotvonline.net/trt_1.aac",
                logoUrl = "https://cdn-i.pr.trt.com.tr/trtdinle/w480/h360/q70/12467415.jpeg",
                description = "Haber, kültür ve klasik müzik"
            ),
            RadioChannel(
                name = "TRT Nağme",
                slug = "trt-nagme",
                streamUrl = "https://rd-trtnagme.medya.trt.com.tr/master_128.m3u8",
                logoUrl = "https://cdn-i.pr.trt.com.tr/trtdinle/w480/h360/q70/12467465.jpeg",
                description = "Türk Sanat Müziği"
            ),
            RadioChannel(
                name = "TRT Türkü",
                slug = "trt-turku",
                streamUrl = "https://rd-trtturku.medya.trt.com.tr/master_128.m3u8",
                logoUrl = "https://cdn-i.pr.trt.com.tr/trtdinle/w480/h360/q70/12467466.jpeg",
                description = "Türk Halk Müziği"
            ),
            RadioChannel(
                name = "TRT Radyo Haber",
                slug = "trt-radyo-haber",
                streamUrl = "https://trt.radyotvonline.net/trt_haber.aac",
                logoUrl = "https://cdn-i.pr.trt.com.tr/trtdinle/w480/h360/q70/12530424_0-0-2048-1536.jpeg",
                description = "Sürekli haber akışı"
            ),
            RadioChannel(
                name = "TRT Radyo 3",
                slug = "trt-radyo-3",
                streamUrl = "https://rd-trtradyo3.medya.trt.com.tr/master_128.m3u8",
                logoUrl = "https://cdn-i.pr.trt.com.tr/trtdinle/w480/h360/q70/12467470.jpeg",
                description = "Klasik, caz, rock ve dünya müziği"
            ),
            RadioChannel(
                name = "TRT Kurdi",
                slug = "trt-kurdi",
                streamUrl = "https://radio-trtradyo6.medya.trt.com.tr/master_128.m3u8",
                logoUrl = "https://cdn-i.pr.trt.com.tr/trtdinle/w480/h360/q70/12467508.jpeg",
                description = "Kürtçe yayın"
            ),
            RadioChannel(
                name = "TRT Arabi",
                slug = "trt-arabi",
                streamUrl = "https://radio-trtarabi.medya.trt.com.tr/master_128.m3u8",
                logoUrl = "https://cdn-i.pr.trt.com.tr/trtdinle/w480/h360/q70/12467526.jpeg",
                description = "Arapça yayın"
            ),
            RadioChannel(
                name = "Antalya Radyosu",
                slug = "antalya-radyosu",
                streamUrl = "https://radio-trtantalya.medya.trt.com.tr/master_128.m3u8",
                logoUrl = "https://cdn-i.pr.trt.com.tr/trtdinle/w480/h360/q70/12467462.jpeg",
                description = "Bölgesel yayın"
            ),
            RadioChannel(
                name = "Çukurova Radyosu",
                slug = "cukurova-radyosu",
                streamUrl = "https://radio-trtcukurova.medya.trt.com.tr/master_128.m3u8",
                logoUrl = "https://cdn-i.pr.trt.com.tr/trtdinle/w480/h360/q70/12467486.jpeg",
                description = "Bölgesel yayın"
            ),
            RadioChannel(
                name = "Erzurum Radyosu",
                slug = "erzurum-radyosu",
                streamUrl = "https://radio-trterzurum.medya.trt.com.tr/master_128.m3u8",
                logoUrl = "https://cdn-i.pr.trt.com.tr/trtdinle/w480/h360/q70/12467502.jpeg",
                description = "Bölgesel yayın"
            ),
            RadioChannel(
                name = "Trabzon Radyosu",
                slug = "trabzon-radyosu",
                streamUrl = "https://radio-trttrabzon.medya.trt.com.tr/master_128.m3u8",
                logoUrl = "https://cdn-i.pr.trt.com.tr/trtdinle/w480/h360/q70/12467523.jpeg",
                description = "Bölgesel yayın"
            ),
            RadioChannel(
                name = "Gap Radyosu",
                slug = "gap-radyosu",
                streamUrl = "https://radio-trtgap.medya.trt.com.tr/master_128.m3u8",
                logoUrl = "https://cdn-i.pr.trt.com.tr/trtdinle/w480/h360/q70/12467503.jpeg",
                description = "Bölgesel yayın"
            )
        )
    }

    data class RawEpisode(
        val title: String,
        val url: String,
        val posterUrl: String?,
        val description: String,
        val extractedNum: Int?
    )

    private fun extractEpisodeNumber(title: String): Int? {
        return try {
            // Try common episode patterns
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

    private suspend fun getTrtContent(contentType: String, archive: Boolean = false, page: Int = 1): List<SearchResponse> {
        return try {
            val url = if (page == 1) {
                "$trt1Url/$contentType?archive=$archive&order=title_asc"
            } else {
                "$trt1Url/$contentType/$page?archive=$archive&order=title_asc"
            }

            app.get(url, timeout = 15).document
                .select("div.grid_grid-wrapper__elAnh > div.h-full.w-full > a")
                .mapNotNull { el ->
                    val title = el.selectFirst("div.card_card-title__IJ9af")?.text()?.trim()
                        ?: return@mapNotNull null
                    val href = el.attr("href")
                    var poster = el.selectFirst("img")?.absUrl("src")
                    poster = poster?.replace(Regex("webp/w\\d+/h\\d+"), "webp/w600/h338")
                        ?.replace("/q75/", "/q85/")

                    newTvSeriesSearchResponse(title, fixTrtUrl(href)) {
                        this.posterUrl = poster
                    }
                }
        } catch (e: Exception) {
            Log.e("TRT", "getTrtContent error for $contentType: ${e.message}")
            emptyList()
        }
    }
    
    private fun fixTrtUrl(url: String): String = if (url.startsWith("http")) url else "$trt1Url$url"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val homePageLists = mutableListOf<HomePageList>()
        when (request.data) {
            "tv" -> {
                val tvItem = newTvSeriesSearchResponse("TRT TV Kanalları", "trt/tv")
                homePageLists += HomePageList(request.name, listOf(tvItem), hasNext = false)
            }
            "radio" -> {
                val radioItem = newTvSeriesSearchResponse("TRT Radyo Kanalları", "trt/radio")
                homePageLists += HomePageList(request.name, listOf(radioItem), hasNext = false)
            }
            "series" -> {
                val items = getTrtContent("diziler", archive = false, page = page)
                if (items.isNotEmpty()) {
                    homePageLists += HomePageList(request.name, items, true)
                }
            }
            "archiveSeries" -> {
                val items = getTrtContent("diziler", archive = true, page = page)
                if (items.isNotEmpty()) {
                    homePageLists += HomePageList(request.name, items, true)
                }
            }
            "programs" -> {
                val items = getTrtContent("programlar", archive = false, page = page)
                if (items.isNotEmpty()) {
                    homePageLists += HomePageList(request.name, items, true)
                }
            }
            "archivePrograms" -> {
                val items = getTrtContent("programlar", archive = true, page = page)
                if (items.isNotEmpty()) {
                    homePageLists += HomePageList(request.name, items, true)
                }
            }
        }

        val hasNext = request.data in listOf("series", "archiveSeries", "programs", "archivePrograms") && homePageLists.isNotEmpty()

        return newHomePageResponse(homePageLists)
    }

    override suspend fun load(url: String): LoadResponse {

        // Direct m3u8 stream
        if (url.contains(".m3u8", ignoreCase = true) || url.contains(".aac", ignoreCase = true)) {
            return newMovieLoadResponse(
                name = "TRT Canlı",
                url = url,
                type = TvType.Live,
                data = url
            )
        }

        // Handle direct YouTube URLs for episodes
        if (url.startsWith("https://www.youtube.com")) {
            return newMovieLoadResponse(
                name = "TRT (YouTube)",
                url = url,
                type = TvType.TvSeries,
                data = url
            )
        }

        if (url == "trt/tv") {
            val tvChannels = getTvChannels()
            val episodes = tvChannels.mapIndexed { index, ch ->
                newEpisode(ch.streamUrl) {
                    name = ch.name
                    this.posterUrl = ch.logoUrl
                    this.description = ch.description
                    this.episode = index + 1
                    this.season = 1
                }
            }
            val posterUrl = tvChannels.firstOrNull()?.logoUrl ?: ""
            return newTvSeriesLoadResponse("TRT TV Kanalları", url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
            }
        }

        if (url == "trt/radio") {
            val radioChannels = getRadioChannels()
            val episodes = radioChannels.mapIndexed { index, ch ->
                newEpisode(ch.streamUrl) {
                    name = ch.name
                    this.posterUrl = ch.logoUrl
                    this.description = ch.description
                    this.episode = index + 1
                    this.season = 1
                }
            }
            val posterUrl = radioChannels.firstOrNull()?.logoUrl ?: ""
            return newTvSeriesLoadResponse("TRT Radyo Kanalları", url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
            }
        }

        // TRT1 Series/Programs
        try {
            val doc = app.get(url, timeout = 15).document
            val title = doc.selectFirst("h1")?.text()?.trim()
                ?: throw ErrorLoadingException("Başlık bulunamadı")
            val plot = doc.selectFirst("meta[name=description]")?.attr("content") ?: ""
            var poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            poster = poster?.replace(Regex("webp/w\\d+/h\\d+"), "webp/w600/h338")
                ?.replace("/q75/", "/q85/")

            val basePath = if (url.contains("/diziler/")) "diziler" else "programlar"
            val slug = url.removePrefix("$trt1Url/$basePath/").substringBefore("/")
            val episodesPath = "bolum"
            val rawEpisodes = mutableListOf<RawEpisode>()
            var pageNum = 1
            var more = true

            while (more && pageNum <= 30) {
                try {
                    val epUrl = if (pageNum == 1) {
                        "$trt1Url/$basePath/$slug/$episodesPath"
                    } else {
                        "$trt1Url/$basePath/$slug/$episodesPath/$pageNum"
                    }
                    val epDoc = app.get(epUrl, timeout = 10).document
                    val pageRaws = epDoc.select("div.grid_grid-wrapper__elAnh > div.h-full.w-full > a")
                        .mapNotNull { el ->
                            val epTitle = el.selectFirst("div.card_card-title__IJ9af")?.text()?.trim()
                                ?: return@mapNotNull null
                            val href = el.attr("href")
                            var img = el.selectFirst("img")?.absUrl("src")
                            img = img?.replace(Regex("webp/w\\d+/h\\d+"), "webp/w600/h338")
                                ?.replace("/q75/", "/q85/")
                            val desc = el.selectFirst("p.card_card-description__0PSTi")?.text()?.trim() ?: ""
                            val extracted = extractEpisodeNumber(epTitle)

                            var episodeUrl = fixTrtUrl(href)
                            if (slug == "baba-candir" && epTitle.trim().lowercase() == "final") {
                                episodeUrl = "https://www.youtube.com/watch?v=baW3qcmcXxU"
                            }

                            RawEpisode(epTitle, episodeUrl, img, desc, extracted)
                        }

                    if (pageRaws.isNotEmpty()) {
                        rawEpisodes += pageRaws
                        pageNum++
                        delay(100)
                    } else more = false
                } catch (e: Exception) { 
                    more = false 
                    Log.e("TRT", "Error loading episodes page $pageNum: ${e.message}")
                }
            }

            // Process raw episodes to assign proper numbers
            val numbered = rawEpisodes.filter { it.extractedNum != null && it.extractedNum!! > 0 }.sortedBy { it.extractedNum }
            val unnumbered = rawEpisodes.filter { it.extractedNum == null || it.extractedNum == 0 }
            
            var nextEpNum = if (numbered.isNotEmpty()) numbered.last().extractedNum!! + 1 else 1
            
            val episodes = mutableListOf<Episode>()
            for (raw in numbered) {
                episodes += newEpisode(raw.url) {
                    name = raw.title
                    this.posterUrl = raw.posterUrl
                    episode = raw.extractedNum!!
                    description = raw.description
                }
            }
            
            for (raw in unnumbered) {
                episodes += newEpisode(raw.url) {
                    name = raw.title
                    this.posterUrl = raw.posterUrl
                    episode = nextEpNum++
                    description = raw.description
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        } catch (e: Exception) {
            throw ErrorLoadingException("Dizi yüklenemedi: ${e.message}")
        }
    }

    private fun extractM3u8FromJson(jsonStr: String): String? {
        return try {
            // Clean up the JSON string if necessary (remove var/let/const assignments)
            var cleanJson = jsonStr.trim()
            if (cleanJson.startsWith("var ") || cleanJson.startsWith("let ") || cleanJson.startsWith("const ")) {
                cleanJson = cleanJson.substringAfterLast("= ").trim().trimEnd(';')
            }
            if (cleanJson.startsWith("{") && cleanJson.endsWith("}")) {
                val config = JSONObject(cleanJson)
                // Direct streamUrl
                var streamUrl = config.optString("streamUrl")
                if (streamUrl.contains(".m3u8")) return streamUrl

                // Look in nested objects like sources, media, hls, etc.
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
                    // Recursive search in nested objects
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
            Log.e("TRT", "JSON parsing error: ${e.message}")
            // Fallback to regex
            Regex("""["']?streamUrl["']?\s*:\s*["']([^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE)
                .find(jsonStr)?.groupValues?.get(1)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Handle direct YouTube URLs
        if (data.startsWith("https://www.youtube.com/watch?v=")) {
            loadExtractor(data, mainUrl, subtitleCallback, callback)
            return true
        }

        // Handle regular m3u8 streams (non-live)
        if (data.contains(".m3u8", ignoreCase = true)) {
            M3u8Helper.generateM3u8(
                source = name,
                streamUrl = data,
                referer = tabiiUrl,
                headers = mapOf("User-Agent" to "Mozilla/5.0", "Referer" to tabiiUrl)
            ).forEach(callback)
            return true
        } else if (data.endsWith(".aac", ignoreCase = true)) {
            callback(newExtractorLink(
                source = name,
                name = "Audio AAC",
                url = data
            ) {
                this.referer = mainUrl
                this.quality = Qualities.Unknown.value
            })
            return true
        }

        // TRT1 series/programs - Improved native source extraction
        if (data.contains(trt1Url)) {
            try {
                val doc = app.get(data, timeout = 10).document
                val scripts = doc.select("script")

                // Look for playerConfig or similar in scripts
                for (script in scripts) {
                    val scriptContent = script.html()
                    if (scriptContent.contains("playerConfig", ignoreCase = true) || scriptContent.contains("streamUrl", ignoreCase = true)) {
                        Log.d("TRT", "Found potential player script: ${scriptContent.length} chars")
                        val m3u8Url = extractM3u8FromJson(scriptContent)
                        if (m3u8Url != null) {
                            Log.d("TRT", "Extracted native m3u8: $m3u8Url")
                            M3u8Helper.generateM3u8(
                                source = name,
                                streamUrl = m3u8Url,
                                referer = trt1Url,
                                headers = mapOf(
                                    "Referer" to trt1Url,
                                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                                )
                            ).forEach(callback)
                            return true
                        }
                    }
                }

                // Fallback: Broad regex search for m3u8 in any script
                for (script in scripts) {
                    val html = script.html()
                    val m = Regex("""https?://[^"'\s]+?\.m3u8[^"'\s]*""", RegexOption.IGNORE_CASE).find(html)
                    if (m != null) {
                        val found = m.value
                        Log.d("TRT", "Found m3u8 via regex: $found")
                        M3u8Helper.generateM3u8(
                            source = name,
                            streamUrl = found,
                            referer = trt1Url,
                            headers = mapOf("Referer" to trt1Url)
                        ).forEach(callback)
                        return true
                    }
                }

                // YouTube fallback (as before)
                val yt = doc.selectFirst("iframe[src*='youtube.com/embed']")
                    ?.attr("src")
                    ?.let { "https://www.youtube.com/watch?v=${it.substringAfter("embed/").substringBefore("?")}" }
                    ?: Regex("""https://www\.youtube\.com/watch\?v=([a-zA-Z0-9_-]+)""")
                        .find(doc.html())?.groupValues?.get(1)
                        ?.let { "https://www.youtube.com/watch?v=$it" }

                if (yt != null) {
                    Log.d("TRT", "Falling back to YouTube: $yt")
                    loadExtractor(yt, tabiiUrl, subtitleCallback, callback)
                    return true
                }
            } catch (e: Exception) {
                Log.e("TRT", "loadLinks error for $data: ${e.message}")
            }
        }

        return false
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()

        // Search TV channels
        getTvChannels()
            .filter { it.name.contains(query, ignoreCase = true) }
            .forEach { ch ->
                val sr = newMovieSearchResponse(ch.name, ch.streamUrl, TvType.Live)
                sr.posterUrl = ch.logoUrl
                out += sr
            }

        // Search Radio channels
        getRadioChannels()
            .filter { it.name.contains(query, ignoreCase = true) }
            .forEach { ch ->
                val sr = newMovieSearchResponse(ch.name, ch.streamUrl, TvType.Live)
                sr.posterUrl = ch.logoUrl
                out += sr
            }

        // TRT1 series and programs search
        try {
            getTrtContent("diziler").filter { it.name.contains(query, ignoreCase = true) }.forEach { out += it }
            getTrtContent("programlar").filter { it.name.contains(query, ignoreCase = true) }.forEach { out += it }
        } catch (e: Exception) {
            Log.e("TRT", "TRT1 search error: ${e.message}")
        }

        return out
    }
}
