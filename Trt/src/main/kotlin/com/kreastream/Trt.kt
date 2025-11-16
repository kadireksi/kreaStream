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
    private val trtCocukBase = "https://www.trtcocuk.net.tr"
    private val liveBase  = "$tabiiUrl/watch/live"

    private val tvFolderUrl = "trt_tv_folder"
    private val radioFolderUrl = "trt_radio_folder"

    override val mainPage = mainPageOf(
        "series"  to "Güncel Diziler",
        "archive" to "Eski Diziler",
        "programs" to "Programlar",
        "archivePrograms" to "Arşiv Programlar",
        "trtcocuk" to "TRT Çocuk",
        "live" to "TRT Tv & Radyo"
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

    private fun generateQualityVariants(base: String): List<String> {
        val list = mutableListOf(base)
        try {
            if (base.contains("medya.trt.com.tr")) {
                val prefix = base.substringBeforeLast("/").removeSuffix("_master")
                listOf("360", "480", "720", "1080").forEach { q ->
                    list += "$prefix" + "_$q.m3u8"
                }
            }
        } catch (e: Exception) {
            Log.e("TRT", "generateQualityVariants error: ${e.message}")
        }
        return list.distinct()
    }

    private suspend fun getTrtCocuk(page: Int = 1): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        
        try {
            val url = "$trtCocukBase/video" + if (page > 1) "?page=$page" else ""
            val response = app.get(url, timeout = 15)
            val html = response.text
            
            // Try to extract data from the Nuxt.js state
            val nuxtDataRegex = Regex("""window\.__NUXT__\s*=\s*(\{.*?\})(?=;|</script>)""", RegexOption.DOT_MATCHES_ALL)
            val match = nuxtDataRegex.find(html)
            
            if (match != null) {
                val nuxtJsonString = match.groupValues[1]
                try {
                    val nuxtData = JSONObject(nuxtJsonString)
                    val dataArray = nuxtData.getJSONArray("data")
                    
                    if (dataArray.length() > 0) {
                        val firstData = dataArray.getJSONObject(0)
                        if (firstData.has("data")) {
                            val dataObj = firstData.getJSONObject("data")
                            if (dataObj.has("list")) {
                                val listArray = dataObj.getJSONArray("list")
                                
                                for (i in 0 until listArray.length()) {
                                    val item = listArray.getJSONObject(i)
                                    val title = item.getString("title")
                                    val path = item.getString("path")
                                    val fullUrl = "$trtCocukBase$path"
                                    
                                    // Get poster image - try different image fields
                                    var poster = ""
                                    val imageFields = listOf("artWork", "mobileCover", "logo")
                                    for (field in imageFields) {
                                        if (item.has(field) && !item.isNull(field)) {
                                            poster = item.getString(field)
                                            if (poster.isNotBlank()) break
                                        }
                                    }
                                    
                                    out += newTvSeriesSearchResponse(title, fullUrl) {
                                        this.posterUrl = poster
                                    }
                                    
                                    Log.d("TRTÇocuk", "Added from Nuxt data: $title -> $fullUrl")
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("TRTÇocuk", "Error parsing Nuxt data: ${e.message}")
                }
            }
            
            // Fallback: Also try to parse the HTML structure as before
            if (out.isEmpty()) {
                val doc = response.document
                val anchors = doc.select("div.row > div > a[href^='/']")
                Log.d("TRTÇocuk", "Fallback: Found ${anchors.size} anchors")
                
                for (a in anchors) {
                    val href = a.attr("href").trim()
                    if (href.isBlank() || href.startsWith("/video") || href == "/") continue

                    val fullUrl = if (href.startsWith("http")) href else trtCocukBase + href
                    if (out.any { it.url == fullUrl }) continue

                    val img = a.selectFirst("img")
                    val title = img?.attr("alt")?.trim() ?: a.text().trim()
                    if (title.isBlank()) continue

                    val poster = img?.attr("data-src")?.ifBlank { img.attr("src") }?.trim() ?: ""

                    out += newTvSeriesSearchResponse(title, fullUrl) {
                        this.posterUrl = poster
                    }
                }
            }

        } catch (e: Exception) {
            Log.e("TRTÇocuk", "getTrtCocuk failed: ${e.message}")
        }

        Log.d("TRTÇocuk", "Total series found: ${out.size}")
        return out
    }

    private suspend fun getTrtCocukEpisodes(seriesUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        try {
            val doc = app.get(seriesUrl, timeout = 15).document

            // Look for episodes in multiple possible locations
            val selectors = listOf(
                "div.vueperslides__track a[href^='/video/']",
                "a[href^='/video/']",
                "div.vImglighter a[href^='/video/']"
            )
            
            val allEpisodes = mutableListOf<org.jsoup.nodes.Element>()
            selectors.forEach { selector ->
                allEpisodes.addAll(doc.select(selector))
            }

            // Remove duplicates by href
            val uniqueEpisodes = allEpisodes.distinctBy { it.attr("href") }

            Log.d("TRTÇocuk", "Found ${uniqueEpisodes.size} episodes for $seriesUrl")

            for (a in uniqueEpisodes) {
                val href = a.attr("href").trim()
                if (href.isBlank()) continue

                val fullHref = if (href.startsWith("http")) href else trtCocukBase + href

                // Try multiple title sources
                val title = a.selectFirst("p.oneline")?.text()?.trim()
                    ?: a.selectFirst("img")?.attr("title")?.trim()
                    ?: a.selectFirst("img")?.attr("alt")?.trim()
                    ?: a.text().trim()
                
                if (title.isBlank()) continue

                val imgEl = a.selectFirst("img")
                val poster = imgEl?.attr("data-src")?.ifBlank { imgEl.attr("src") }?.trim()

                // Extract episode number with better patterns
                val num = try {
                    Regex("""(\d{1,4})\s*\.?\s*[Bb]ölüm""").find(title)?.groupValues?.get(1)?.toIntOrNull()
                        ?: Regex("""[Bb]ölüm\s*(\d{1,4})""").find(title)?.groupValues?.get(1)?.toIntOrNull()
                        ?: Regex("""\b(\d{1,4})\b""").find(title)?.groupValues?.get(1)?.toIntOrNull()
                        ?: 0
                } catch (e: Exception) {
                    0
                }

                val ep = newEpisode(fullHref) {
                    name = title
                    if (!poster.isNullOrBlank()) posterUrl = poster
                    episode = num
                    season = 1
                    description = ""
                }

                episodes += ep
                Log.d("TRTÇocuk", "Added episode: $title (episode: $num)")
            }

        } catch (e: Exception) {
            Log.e("TRTÇocuk", "getTrtCocukEpisodes failed: ${e.message}")
        }
        return episodes
    }

    private suspend fun getTrtSeries(archive: Boolean = false, page: Int = 1): List<SearchResponse> {
        return try {
            val url = if (page == 1) {
                "$trt1Url/diziler?archive=$archive&order=title_asc"
            } else {
                "$trt1Url/diziler/$page?archive=$archive&order=title_asc"
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
            Log.e("TRT", "getTrtSeries error: ${e.message}")
            emptyList()
        }
    }

    private suspend fun getTrtPrograms(archive: Boolean = false, page: Int = 1): List<SearchResponse> {
        return try {
            val url = if (page == 1) {
                "$trt1Url/programlar?archive=$archive&order=title_asc"
            } else {
                "$trt1Url/programlar/$page?archive=$archive&order=title_asc"
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
            Log.e("TRT", "getTrtPrograms error: ${e.message}")
            emptyList()
        }
    }

    private fun fixTrtUrl(url: String): String = if (url.startsWith("http")) url else "$trt1Url$url"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = when (request.data) {
            "live" -> listOf(
                newTvSeriesSearchResponse(
                    name = "📺 TRT TV Kanalları",
                    url = tvFolderUrl,
                    type = TvType.Live
                ) {
                    this.posterUrl = "https://www.trt.net.tr/logos/our-logos/corporate/trt.png"
                },
                newTvSeriesSearchResponse(
                    name = "📻 TRT Radyo Kanalları",
                    url = radioFolderUrl,
                    type = TvType.Live
                ) {
                    this.posterUrl = "https://trtdinle.com/trt-dinle-fb-share.jpg"
                }
            )
            "trt_tv_folder" -> {
                getTvChannels().map { channel ->
                    newMovieSearchResponse(channel.name, channel.streamUrl, TvType.Live) {
                        this.posterUrl = channel.logoUrl
                    }
                }
            }
            "trt_radio_folder" -> {
                getRadioChannels().map { channel ->
                    newMovieSearchResponse(channel.name, channel.streamUrl, TvType.Live) {
                        this.posterUrl = channel.logoUrl
                    }
                }
            }
            "series"  -> getTrtSeries(archive = false, page = page)
            "archive" -> getTrtSeries(archive = true,  page = page)
            "programs" -> getTrtPrograms(archive = false, page = page)
            "archivePrograms" -> getTrtPrograms(archive = true,  page = page)
            "trtcocuk" -> getTrtCocuk(page = page)
            else -> emptyList()
        }

        val hasNext = request.data in listOf("series", "archive", "trtcocuk", "programs", "archivePrograms") && items.isNotEmpty()

        return newHomePageResponse(
            listOf(HomePageList(request.name, items, true)),
            hasNext = hasNext
        )
    }

    override suspend fun load(url: String): LoadResponse {
        // TV Folder
        if (url == tvFolderUrl) {
            return newTvSeriesLoadResponse("TRT TV Kanalları", url, TvType.Live, emptyList()) {
                this.posterUrl = "https://www.trt.net.tr/logos/our-logos/corporate/trt.png"
                this.plot = "TRT TV canlı yayın kanalları"
            }
        }

        // Radio Folder
        if (url == radioFolderUrl) {
            return newTvSeriesLoadResponse("TRT Radyo Kanalları", url, TvType.Live, emptyList()) {
                this.posterUrl = "https://trtdinle.com/trt-dinle-fb-share.jpg"
                this.plot = "TRT Radyo canlı yayın"
            }
        }

        // Individual TV channel stream
        val tvChannels = getTvChannels()
        val tvChannel = tvChannels.find { it.streamUrl == url }
        if (tvChannel != null) {
            return newMovieLoadResponse(tvChannel.name, url, TvType.Live, url) {
                this.posterUrl = tvChannel.logoUrl
                this.plot = tvChannel.description
            }
        }

        // Individual Radio channel stream
        val radioChannels = getRadioChannels()
        val radioChannel = radioChannels.find { it.streamUrl == url }
        if (radioChannel != null) {
            return newMovieLoadResponse(radioChannel.name, url, TvType.Live, url) {
                this.posterUrl = radioChannel.logoUrl
                this.plot = radioChannel.description
            }
        }

        // Direct m3u8 stream
        if (url.contains(".m3u8", ignoreCase = true) || url.contains(".aac", ignoreCase = true)) {
            return newMovieLoadResponse("TRT Canlı", url, TvType.Live, url)
        }

        // TRT Çocuk Series
        if (url.contains("trtcocuk.net.tr") && !url.contains("/video")) {
            try {
                val doc = app.get(url, timeout = 15).document
                val title = doc.selectFirst("h1, .page-title, .title")?.text()?.trim()
                    ?: doc.selectFirst("meta[property=og:title]")?.attr("content") ?: "TRT Çocuk"
                val plot = doc.selectFirst("meta[name=description]")?.attr("content") ?: ""
                var poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
                    ?: doc.selectFirst("img")?.absUrl("src")
                poster = poster?.replace(Regex("w\\d+/h\\d+"), "w600/h338")

                val episodes = getTrtCocukEpisodes(url)

                return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.plot = plot
                }
            } catch (e: Exception) {
                throw ErrorLoadingException("TRT Çocuk dizi yüklenemedi: ${e.message}")
            }
        }

        // TRT Çocuk Video page
        if (url.contains("trtcocuk.net.tr")) {
            try {
                val doc = app.get(url, timeout = 15).document
                val title = doc.selectFirst("h1, .page-title, .title")?.text()?.trim() ?: "TRT Çocuk Video"
                val plot = doc.selectFirst("meta[name=description]")?.attr("content") ?: ""
                var poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
                    ?: doc.selectFirst("img")?.absUrl("src")
                poster = poster?.replace(Regex("w\\d+/h\\d+"), "w600/h338")

                val episode = newEpisode(url) {
                    name = title
                    posterUrl = poster
                    episode = 1
                    season = 1
                    description = plot
                }

                return newTvSeriesLoadResponse(title, url, TvType.TvSeries, listOf(episode)) {
                    this.posterUrl = poster
                    this.plot = plot
                }
            } catch (e: Exception) {
                throw ErrorLoadingException("TRT Çocuk video yüklenemedi: ${e.message}")
            }
        }

        // TRT1 Series
        try {
            val doc = app.get(url, timeout = 15).document
            val title = doc.selectFirst("h1")?.text()?.trim()
                ?: throw ErrorLoadingException("Başlık bulunamadı")
            val plot = doc.selectFirst("meta[name=description]")?.attr("content") ?: ""
            var poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            poster = poster?.replace(Regex("webp/w\\d+/h\\d+"), "webp/w600/h338")
                ?.replace("/q75/", "/q85/")

            val seriesSlug = url.removePrefix("$trt1Url/diziler/").substringBefore("/")
            val episodes = mutableListOf<Episode>()
            var pageNum = 1
            var more = true

            while (more && pageNum <= 30) {
                try {
                    val epUrl = if (pageNum == 1) {
                        "$trt1Url/diziler/$seriesSlug/bolum"
                    } else {
                        "$trt1Url/diziler/$seriesSlug/bolum/$pageNum"
                    }
                    val epDoc = app.get(epUrl, timeout = 10).document
                    val pageEps = epDoc.select("div.grid_grid-wrapper__elAnh > div.h-full.w-full > a")
                        .mapNotNull { el ->
                            val epTitle = el.selectFirst("div.card_card-title__IJ9af")?.text()?.trim()
                                ?: return@mapNotNull null
                            val href = el.attr("href")
                            var img = el.selectFirst("img")?.absUrl("src")
                            img = img?.replace(Regex("webp/w\\d+/h\\d+"), "webp/w600/h338")
                                ?.replace("/q75/", "/q85/")
                            val desc = el.selectFirst("p.card_card-description__0PSTi")?.text()?.trim() ?: ""
                            val epNum = epTitle.replace(Regex("[^0-9]"), "").toIntOrNull() ?: pageNum

                            newEpisode(fixTrtUrl(href)) {
                                name = epTitle
                                posterUrl = img
                                episode = epNum
                                description = desc
                            }
                        }

                    if (pageEps.isNotEmpty()) {
                        episodes += pageEps
                        pageNum++
                        delay(100)
                    } else more = false
                } catch (e: Exception) { 
                    more = false 
                    Log.e("TRT", "Error loading episodes page $pageNum: ${e.message}")
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Block downloads for live TV and radio streams
        if ((data.contains(".m3u8", ignoreCase = true) && data.contains("medya.trt.com.tr")) ||
            (data.contains(".aac", ignoreCase = true) && data.contains("radyotvonline.net"))) {
            return false
        }

        // Handle regular m3u8 streams (non-live)
        if (data.contains(".m3u8", ignoreCase = true)) {
            generateQualityVariants(data).forEach { u ->
                M3u8Helper.generateM3u8(
                    source = name,
                    streamUrl = u,
                    referer = tabiiUrl,
                    headers = mapOf("User-Agent" to "Mozilla/5.0", "Referer" to tabiiUrl)
                ).forEach(callback)
            }
            return true
        }

        // TRT Çocuk links
        if (data.contains("trtcocuk.net.tr")) {
            try {
                val doc = app.get(data, timeout = 10).document

                // YouTube embed
                val iframe = doc.selectFirst("iframe[src*='youtube.com/embed']")
                if (iframe != null) {
                    val src = iframe.attr("src")
                    val v = src.substringAfter("embed/").substringBefore("?")
                    if (v.isNotBlank()) {
                        loadExtractor("https://www.youtube.com/watch?v=$v", trtCocukBase, subtitleCallback, callback)
                        return true
                    }
                }

                // Direct video source
                val videoSrc = doc.selectFirst("video source[src], video[src]")?.attr("src")
                if (!videoSrc.isNullOrBlank()) {
                    callback(newExtractorLink(
                        source = name,
                        name = "TRT Çocuk",
                        url = videoSrc
                    ) {
                        this.referer = trtCocukBase
                        this.quality = Qualities.Unknown.value
                    })
                    return true
                }

                // m3u8 in scripts
                val scripts = doc.select("script")
                for (s in scripts) {
                    val html = s.html()
                    val m = Regex("""https?://[^"'\s]+?\.m3u8[^"'\s]*""").find(html)
                    if (m != null) {
                        val found = m.value
                        callback(newExtractorLink(
                            source = name,
                            name = "TRT Çocuk",
                            url = found
                        ) {
                            this.referer = trtCocukBase
                            this.quality = Qualities.Unknown.value
                        })
                        return true
                    }
                }
            } catch (e: Exception) {
                Log.e("TRT", "TRT Çocuk loadLinks error: ${e.message}")
            }
        }

        // TRT1 series/programs
        try {
            val doc = app.get(data, timeout = 10).document
            val script = doc.select("script")
                .find { it.html().contains("playerConfig") }
                ?.html()
            val m3u8 = script?.let {
                Regex("""streamUrl["']?\s*:\s*["']([^"']+\.m3u8[^"']*)["']""")
                    .find(it)?.groupValues?.get(1)
            }

            if (m3u8 != null) {
                generateQualityVariants(m3u8).forEach { u ->
                    M3u8Helper.generateM3u8(
                        source = name,
                        streamUrl = u,
                        referer = trt1Url,
                        headers = mapOf("Referer" to trt1Url)
                    ).forEach(callback)
                }
                return true
            }

            val yt = doc.selectFirst("iframe[src*='youtube.com/embed']")
                ?.attr("src")
                ?.let { "https://www.youtube.com/watch?v=${it.substringAfter("embed/").substringBefore("?")}" }
                ?: Regex("""https://www\.youtube\.com/watch\?v=([a-zA-Z0-9_-]+)""")
                    .find(doc.html())?.groupValues?.get(1)
                    ?.let { "https://www.youtube.com/watch?v=$it" }

            if (yt != null) {
                loadExtractor(yt, tabiiUrl, subtitleCallback, callback)
                return true
            }
        } catch (e: Exception) {
            Log.e("TRT", "loadLinks error for $data: ${e.message}")
        }

        return false
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()

        // Search TV channels
        getTvChannels()
            .filter { it.name.contains(query, ignoreCase = true) }
            .forEach { ch ->
                out += newMovieSearchResponse(ch.name, ch.streamUrl, TvType.Live) {
                    this.posterUrl = ch.logoUrl
                }
            }

        // Search Radio channels
        getRadioChannels()
            .filter { it.name.contains(query, ignoreCase = true) }
            .forEach { ch ->
                out += newMovieSearchResponse(ch.name, ch.streamUrl, TvType.Live) {
                    this.posterUrl = ch.logoUrl
                }
            }

        // TRT Çocuk search
        try {
            getTrtCocuk().filter { it.name.contains(query, ignoreCase = true) }.forEach { out += it }
        } catch (e: Exception) {
            Log.e("TRT", "TRT Çocuk search error: ${e.message}")
        }

        // TRT1 series search
        try {
            val sUrl = "$trt1Url/arama/$query?contenttype=series"
            app.get(sUrl, timeout = 10).document
                .select("div.grid_grid-wrapper__elAnh > div.h-full.w-full > a")
                .mapNotNull { el ->
                    val title = el.selectFirst("div.card_card-title__IJ9af")?.text()?.trim()
                        ?: return@mapNotNull null
                    val href = el.attr("href")
                    if (!href.contains("/diziler/")) return@mapNotNull null
                    var poster = el.selectFirst("img")?.absUrl("src")
                    poster = poster?.replace(Regex("webp/w\\d+/h\\d+"), "webp/w600/h338")
                        ?.replace("/q75/", "/q85/")

                    newTvSeriesSearchResponse(title, fixTrtUrl(href)) {
                        this.posterUrl = poster
                    }
                }.forEach { out += it }
        } catch (e: Exception) {
            Log.e("TRT", "TRT1 series search error: ${e.message}")
        }

        return out
    }
}
