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
            val doc = response.document

            val rawEpisodes = mutableListOf<RawEpisode>()
            
            // Try to extract data from Nuxt.js state first - optimized to script content
            val nuxtScript = doc.select("script").first { it.html().contains("__NUXT__") }?.html() ?: ""
            val nuxtDataRegex = Regex("""window\.__NUXT__\s*=\s*(\{.*\});?""", RegexOption.DOT_MATCHES_ALL)
            val match = nuxtDataRegex.find(nuxtScript)
            
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
                                    
                                    // Extract episode number
                                    val num = extractEpisodeNumber(title)
                                    
                                    rawEpisodes += RawEpisode(title, fullUrl, mainImageUrl.ifBlank { seriesPoster }, seriesDescription, num)
                                    Log.d("TRTÇocuk", "Added raw episode from Nuxt: $title (episode: $num) -> $fullUrl")
                                }
                            }
                            
                            // Also check clipUps for additional content
                            if (dataObj.has("clipUps")) {
                                val clipUpsArray = dataObj.getJSONArray("clipUps")
                                
                                for (i in 0 until clipUpsArray.length()) {
                                    val clip = clipUpsArray.getJSONObject(i)
                                    val title = clip.getString("title")
                                    val path = clip.getString("path")
                                    val fullUrl = "$trtCocukBase$path"
                                    var mainImage = clip.optString("mainImage", "")
                                    mainImage = mainImage.replace(Regex("w\\d+/h\\d+"), "w600/h338").replace("q70", "q90")
                                    
                                    val num = extractEpisodeNumber(title)
                                    
                                    rawEpisodes += RawEpisode(title, fullUrl, mainImage.ifBlank { seriesPoster }, "", num)
                                    Log.d("TRTÇocuk", "Added raw clip from Nuxt: $title (episode: $num) -> $fullUrl")
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("TRTÇocuk", "Error parsing Nuxt data: ${e.message}")
                }
            }
            
            // Fallback to HTML parsing if no episodes found in Nuxt data
            if (rawEpisodes.isEmpty()) {
                val episodeSelectors = "a[href*='/video/'], [data-href*='/video/'], div.vueperslides__track a, div.episode-item a, div.video-item a, a.video-link, article a[href*='/video/'], li a[href*='/video/'], div.card a[href*='/video/'], div.list-item a[href*='/video/']"
                
                val allEpisodeLinks = doc.select(episodeSelectors).distinctBy { it.attr("href").ifBlank { it.attr("data-href") } }

                Log.d("TRTÇocuk", "Fallback: Found ${allEpisodeLinks.size} potential episode links")

                val tempRawEpisodes = mutableListOf<RawEpisode>()
                for (a in allEpisodeLinks) {
                    try {
                        var href = a.attr("href").trim()
                        if (href.isBlank()) href = a.attr("data-href").trim()
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

                        val num = extractEpisodeNumber(title)

                        tempRawEpisodes += RawEpisode(title, fullHref, poster.ifBlank { null }, "", num)
                        Log.d("TRTÇocuk", "Added raw episode from HTML: $title (episode: $num) -> $fullHref")
                        
                    } catch (e: Exception) {
                        Log.e("TRTÇocuk", "Error processing episode link: ${e.message}")
                    }
                }
                rawEpisodes += tempRawEpisodes.distinctBy { it.url }
            }

            // Ultimate fallback: Generate sequential episode URLs if still empty
            if (rawEpisodes.isEmpty()) {
                val slug = seriesUrl.removePrefix(trtCocukBase).trim('/')
                if (slug.isNotBlank()) {
                    var seriesPoster = doc.selectFirst("meta[property=og:image]")?.attr("content") ?: ""
                    if (seriesPoster.isBlank()) {
                        seriesPoster = doc.selectFirst("img[alt*=$slug], img[src*=$slug]")?.absUrl("src") ?: ""
                    }
                    seriesPoster = seriesPoster.replace(Regex("w\\d+/h\\d+"), "w600/h338").replace("q70", "q90")

                    var i = 1
                    while (i <= 100) {  // Limit to prevent infinite loop
                        val episodePath = "$slug-$i"
                        val episodeUrl = "$trtCocukBase/video/$episodePath"
                        try {
                            val epResponse = app.get(episodeUrl, timeout = 10)
                            if (epResponse.code != 200) break

                            val epDoc = epResponse.document
                            val epTitle = epDoc.selectFirst("h1, title")?.text()?.trim() ?: "$slug $i. Bölüm"
                            val epPoster = epDoc.selectFirst("meta[property=og:image]")?.attr("content") ?: seriesPoster
                            val epPosterFixed = epPoster.replace(Regex("w\\d+/h\\d+"), "w600/h338").replace("q70", "q90")
                            val epNum = extractEpisodeNumber(epTitle) ?: i

                            if (epTitle.contains("yok", ignoreCase = true) || epTitle.contains("not found", ignoreCase = true)) break

                            rawEpisodes += RawEpisode(epTitle, episodeUrl, epPosterFixed.ifBlank { null }, "", epNum)
                            Log.d("TRTÇocuk", "Added generated episode: $epTitle (episode: $epNum) -> $episodeUrl")

                            i++
                            delay(200)  // Rate limit
                        } catch (e: Exception) {
                            Log.e("TRTÇocuk", "Error fetching generated episode $i: ${e.message}")
                            break
                        }
                    }
                }
            }
            
            // Process raw episodes to assign proper numbers and dedup
            val uniqueRaw = rawEpisodes.distinctBy { it.url.lowercase() }
            val numbered = uniqueRaw.filter { it.extractedNum != null && it.extractedNum!! > 0 }.sortedBy { it.extractedNum }
            val unnumbered = uniqueRaw.filter { it.extractedNum == null || it.extractedNum == 0 }.sortedBy { it.title }
            
            var nextEpNum = if (numbered.isNotEmpty()) numbered.last().extractedNum!! + 1 else 1
            
            for (raw in numbered) {
                val ep = newEpisode(raw.url) {
                    this.name = raw.title
                    raw.posterUrl?.takeIf { it.isNotBlank() }?.let { this.posterUrl = it }
                    this.episode = raw.extractedNum!!
                    this.season = 1
                    this.description = raw.description
                }
                episodes += ep
            }
            
            for (raw in unnumbered) {
                val ep = newEpisode(raw.url) {
                    this.name = raw.title
                    raw.posterUrl?.takeIf { it.isNotBlank() }?.let { this.posterUrl = it }
                    this.episode = nextEpNum++
                    this.season = 1
                    this.description = raw.description
                }
                episodes += ep
            }

            // Sort episodes by number
            episodes.sortBy { it.episode }

        } catch (e: Exception) {
            Log.e("TRTÇocuk", "getTrtCocukEpisodes failed: ${e.message}")
        }
        
        Log.d("TRTÇocuk", "Total episodes found: ${episodes.size}")
        return episodes
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

        // Handle direct YouTube URLs for episodes
        if (url.startsWith("https://www.youtube.com")) {
            return newMovieLoadResponse(
                name = "TRT (YouTube)",
                url = url,
                type = TvType.TvSeries,
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
                            generateQualityVariants(m3u8Url).forEach { u ->
                                M3u8Helper.generateM3u8(
                                    source = name,
                                    streamUrl = u,
                                    referer = trt1Url,
                                    headers = mapOf(
                                        "Referer" to trt1Url,
                                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                                    )
                                ).forEach(callback)
                            }
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
                        generateQualityVariants(found).forEach { u ->
                            M3u8Helper.generateM3u8(
                                source = name,
                                streamUrl = u,
                                referer = trt1Url,
                                headers = mapOf("Referer" to trt1Url)
                            ).forEach(callback)
                        }
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

        // TRT Çocuk search
        try {
            getTrtCocuk().filter { it.name.contains(query, ignoreCase = true) }.forEach { out += it }
        } catch (e: Exception) {
            Log.e("TRT", "TRT Çocuk search error: ${e.message}")
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
