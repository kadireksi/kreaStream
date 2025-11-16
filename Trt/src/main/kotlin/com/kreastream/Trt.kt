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

    override val mainPage = mainPageOf(
        "series"  to "Güncel Diziler",
        "archiveSeries" to "Arşiv Diziler",
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
            Log.d("TRTÇocuk", "Fetching URL: $url")
            
            val doc = app.get(url, timeout = 15).document

            // Look for show containers - more specific selector
            val showContainers = doc.select("div.col-xl-2, div.col-lg-3, div.col-md-4, div.col-sm-6, div.col-6")
            Log.d("TRTÇocuk", "Found ${showContainers.size} show containers")
            
            for (container in showContainers) {
                try {
                    val a = container.selectFirst("a[href]") ?: continue
                    val href = a.attr("href").trim()
                    
                    // Skip invalid hrefs
                    if (href.isBlank() || href == "/" || href.startsWith("#") || href.contains("/video/")) continue
                    
                    val fullUrl = if (href.startsWith("http")) href else "$trtCocukBase$href"
                    
                    // Skip if already added
                    if (out.any { it.url == fullUrl }) continue

                    // Get title from img alt attribute
                    val img = a.selectFirst("img")
                    val title = img?.attr("alt")?.trim() ?: continue
                    
                    if (title.isBlank()) continue

                    // Get poster URL - try multiple attributes
                    var poster = img.attr("src")?.trim() ?: ""
                    if (poster.isBlank()) {
                        poster = img.attr("data-src")?.trim() ?: ""
                    }
                    
                    // Make poster URL absolute if it's relative
                    if (poster.isNotBlank()) {
                        if (!poster.startsWith("http")) {
                            poster = "$trtCocukBase$poster"
                        }
                        // Ensure we have a proper image URL - upscale resolution
                        poster = poster.replace(Regex("w\\d+/h\\d+"), "w600/h338").replace("q70", "q90")
                    }

                    Log.d("TRTÇocuk", "Found show: $title -> $fullUrl (poster: $poster)")
                    
                    out += newTvSeriesSearchResponse(title, fullUrl) {
                        this.posterUrl = poster
                    }
                    
                } catch (e: Exception) {
                    Log.e("TRTÇocuk", "Error processing container: ${e.message}")
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
            Log.d("TRTÇocuk", "Fetching episodes from: $seriesUrl")
            val response = app.get(seriesUrl, timeout = 15)
            val html = response.text

            // Try to extract data from Nuxt.js state first
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
                            
                            // Extract series info
                            val seriesTitle = dataObj.optString("title", "")
                            val seriesDescription = dataObj.optString("description", "")
                            var seriesPoster = dataObj.optString("logo", "")
                            if (seriesPoster.isBlank()) {
                                seriesPoster = dataObj.optString("artWork", "")
                            }
                            if (seriesPoster.isBlank()) {
                                seriesPoster = dataObj.optString("mobileCover", "")
                            }
                            
                            // Extract videos/episodes
                            if (dataObj.has("videos")) {
                                val videosArray = dataObj.getJSONArray("videos")
                                
                                for (i in 0 until videosArray.length()) {
                                    val video = videosArray.getJSONObject(i)
                                    val title = video.getString("title")
                                    val path = video.getString("path")
                                    val fullUrl = "$trtCocukBase$path"
                                    var mainImageUrl = video.optString("mainImageUrl", "")
                                    mainImageUrl = mainImageUrl.replace(Regex("w\\d+/h\\d+"), "w600/h338").replace("q70", "q90")
                                    val publishedDate = video.optString("publishedDate", "")
                                    
                                    // Extract episode number
                                    val num = extractEpisodeNumber(title) ?: 0
                                    
                                    val ep = newEpisode(fullUrl) {
                                        this.name = title
                                        if (mainImageUrl.isNotBlank()) this.posterUrl = mainImageUrl
                                        this.episode = num
                                        this.season = 1
                                        this.description = seriesDescription
                                    }
                                    
                                    episodes += ep
                                    Log.d("TRTÇocuk", "Added episode from Nuxt: $title (episode: $num) -> $fullUrl")
                                }
                            }
                            
                            // Also check clipUps for additional content
                            if (dataObj.has("clipUps") && episodes.isEmpty()) {
                                val clipUpsArray = dataObj.getJSONArray("clipUps")
                                
                                for (i in 0 until clipUpsArray.length()) {
                                    val clip = clipUpsArray.getJSONObject(i)
                                    val title = clip.getString("title")
                                    val path = clip.getString("path")
                                    val fullUrl = "$trtCocukBase$path"
                                    var mainImage = clip.optString("mainImage", "")
                                    mainImage = mainImage.replace(Regex("w\\d+/h\\d+"), "w600/h338").replace("q70", "q90")
                                    
                                    val ep = newEpisode(fullUrl) {
                                        this.name = title
                                        if (mainImage.isNotBlank()) this.posterUrl = mainImage
                                        this.episode = i + 1
                                        this.season = 1
                                    }
                                    
                                    episodes += ep
                                    Log.d("TRTÇocuk", "Added clip from Nuxt: $title -> $fullUrl")
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("TRTÇocuk", "Error parsing Nuxt data: ${e.message}")
                }
            }
            
            // Fallback to HTML parsing if no episodes found in Nuxt data
            if (episodes.isEmpty()) {
                val doc = response.document
                val episodeSelectors = listOf(
                    "a[href*='/video/']",
                    "div.vueperslides__track a",
                    "div.episode-item a", 
                    "div.video-item a",
                    "a.video-link",
                    "article a[href*='/video/']",
                    "li a[href*='/video/']",
                    "div.card a[href*='/video/']",
                    "div.list-item a[href*='/video/']"
                )
                
                val allEpisodeLinks = mutableListOf<org.jsoup.nodes.Element>()
                episodeSelectors.forEach { selector ->
                    allEpisodeLinks.addAll(doc.select(selector))
                }

                Log.d("TRTÇocuk", "Fallback: Found ${allEpisodeLinks.size} potential episode links")

                val tempEpisodes = mutableListOf<Episode>()
                for (a in allEpisodeLinks) {
                    try {
                        val href = a.attr("href").trim()
                        if (href.isBlank() || !href.contains("/video/")) continue

                        val fullHref = if (href.startsWith("http")) href else "$trtCocukBase$href"

                        var title = a.attr("title").trim()
                        if (title.isBlank()) {
                            title = a.selectFirst("img")?.attr("alt")?.trim() ?: ""
                        }
                        if (title.isBlank()) {
                            title = a.text().trim()
                        }
                        if (title.isBlank()) continue

                        val imgEl = a.selectFirst("img")
                        var poster = imgEl?.attr("src")?.trim() ?: ""
                        if (poster.isBlank()) {
                            poster = imgEl?.attr("data-src")?.trim() ?: ""
                        }
                        if (poster.isNotBlank() && !poster.startsWith("http")) {
                            poster = "$trtCocukBase$poster"
                        }
                        poster = poster.replace(Regex("w\\d+/h\\d+"), "w600/h338").replace("q70", "q90")

                        val num = extractEpisodeNumber(title) ?: 0

                        val ep = newEpisode(fullHref) {
                            this.name = title
                            if (poster.isNotBlank()) this.posterUrl = poster
                            this.episode = num
                            this.season = 1
                        }

                        tempEpisodes += ep
                        Log.d("TRTÇocuk", "Added episode from HTML: $title (episode: $num) -> $fullHref")
                        
                    } catch (e: Exception) {
                        Log.e("TRTÇocuk", "Error processing episode link: ${e.message}")
                    }
                }
                // Dedup by data
                episodes += tempEpisodes.distinctBy { it.data }
            }

        } catch (e: Exception) {
            Log.e("TRTÇocuk", "getTrtCocukEpisodes failed: ${e.message}")
        }
        
        Log.d("TRTÇocuk", "Total episodes found: ${episodes.size}")
        return episodes
    }

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
        val homePageLists = mutableListOf<HomePageList>()
        when (request.data) {
            "live" -> {
                val tvChannels = getTvChannels()
                val tvItems = tvChannels.map { ch ->
                    val sr = newMovieSearchResponse(ch.name, ch.streamUrl, TvType.Live)
                    sr.posterUrl = ch.logoUrl
                    sr
                }
                val radioChannels = getRadioChannels()
                val radioItems = radioChannels.map { ch ->
                    val sr = newMovieSearchResponse(ch.name, ch.streamUrl, TvType.Live)
                    sr.posterUrl = ch.logoUrl
                    sr
                }
                homePageLists += HomePageList("📺 TRT TV Kanalları", tvItems, true)
                homePageLists += HomePageList("📻 TRT Radyo Kanalları", radioItems, true)
            }
            "series" -> {
                val items = getTrtSeries(archive = false, page = page)
                if (items.isNotEmpty()) {
                    homePageLists += HomePageList(request.name, items, true)
                }
            }
            "archiveSeries" -> {
                val items = getTrtSeries(archive = true, page = page)
                if (items.isNotEmpty()) {
                    homePageLists += HomePageList(request.name, items, true)
                }
            }
            "programs" -> {
                val items = getTrtPrograms(archive = false, page = page)
                if (items.isNotEmpty()) {
                    homePageLists += HomePageList(request.name, items, true)
                }
            }
            "archivePrograms" -> {
                val items = getTrtPrograms(archive = true, page = page)
                if (items.isNotEmpty()) {
                    homePageLists += HomePageList(request.name, items, true)
                }
            }
            "trtcocuk" -> {
                val items = getTrtCocuk(page = page)
                if (items.isNotEmpty()) {
                    homePageLists += HomePageList(request.name, items, true)
                }
            }
        }

        val hasNext = request.data in listOf("series", "archiveSeries", "trtcocuk", "programs", "archivePrograms") && homePageLists.isNotEmpty()

        return newHomePageResponse(homePageLists, hasNext = hasNext)
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

        // In the load function, update the TRT Çocuk series section:
        if (url.contains("trtcocuk.net.tr") && !url.contains("/video")) {
            try {
                val response = app.get(url, timeout = 15)
                val html = response.text
                val doc = response.document
                
                // Try to get title from Nuxt data first
                var title = "TRT Çocuk"
                var plot = ""
                var poster = ""
                
                val nuxtDataRegex = Regex("""window\.__NUXT__\s*=\s*(\{.*?\})(?=;|</script>)""", RegexOption.DOT_MATCHES_ALL)
                val match = nuxtDataRegex.find(html)
                
                if (match != null) {
                    try {
                        val nuxtData = JSONObject(match.groupValues[1])
                        val dataArray = nuxtData.getJSONArray("data")
                        if (dataArray.length() > 0) {
                            val firstData = dataArray.getJSONObject(0)
                            if (firstData.has("data")) {
                                val dataObj = firstData.getJSONObject("data")
                                title = dataObj.optString("title", title)
                                plot = dataObj.optString("description", plot)
                                
                                // Try multiple image sources
                                poster = dataObj.optString("logo", "")
                                if (poster.isBlank()) {
                                    poster = dataObj.optString("artWork", "")
                                }
                                if (poster.isBlank()) {
                                    poster = dataObj.optString("mobileCover", "")
                                }
                                if (poster.isBlank()) {
                                    poster = dataObj.optString("backgroundImage", "")
                                }
                                poster = poster.replace(Regex("w\\d+/h\\d+"), "w600/h338").replace("q70", "q90")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("TRTÇocuk", "Error parsing Nuxt data in load: ${e.message}")
                    }
                }
                
                // Fallback to HTML parsing
                if (title == "TRT Çocuk") {
                    title = doc.selectFirst("h1, .page-title, .title")?.text()?.trim() ?: title
                }
                if (plot.isBlank()) {
                    plot = doc.selectFirst("meta[name=description]")?.attr("content") ?: plot
                }
                if (poster.isBlank()) {
                    poster = doc.selectFirst("meta[property=og:image]")?.attr("content") ?: ""
                    if (poster.isBlank()) {
                        poster = doc.selectFirst("img")?.absUrl("src") ?: ""
                    }
                    poster = poster.replace(Regex("w\\d+/h\\d+"), "w600/h338").replace("q70", "q90")
                }

                val episodes = getTrtCocukEpisodes(url)

                return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.plot = plot
                }
            } catch (e: Exception) {
                throw ErrorLoadingException("TRT Çocuk dizi yüklenemedi: ${e.message}")
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
            val episodes = mutableListOf<Episode>()
            var pageNum = 1
            var more = true
            var episodeCounter = 1

            while (more && pageNum <= 30) {
                try {
                    val epUrl = if (pageNum == 1) {
                        "$trt1Url/$basePath/$slug/$episodesPath"
                    } else {
                        "$trt1Url/$basePath/$slug/$episodesPath/$pageNum"
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
                            val epNum = extractEpisodeNumber(epTitle) ?: episodeCounter++

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
