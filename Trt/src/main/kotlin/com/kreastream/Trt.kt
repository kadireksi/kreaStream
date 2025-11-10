package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup

class Trt : MainAPI() {
    override var mainUrl = "https://www.tabii.com"
    override var name = "TRT"
    override val supportedTypes = setOf(TvType.Live, TvType.TvSeries)
    override var lang = "tr"
    override var hasMainPage = true

    private val tabiiUrl = "https://www.tabii.com/tr"
    private val trt1Url = "https://www.trt1.com.tr"

    override val mainPage = mainPageOf(
        "live" to "Canlı Yayınlar",
        "series" to "Güncel Diziler", 
        "archive" to "Eski Diziler"
    )

    data class TabiiChannel(
        val name: String,
        val streamUrls: List<String>,
        val logoUrl: String,
        val description: String = ""
    )

    data class TrtSeries(
        val title: String,
        val url: String,
        val posterUrl: String?,
        val description: String = ""
    )

    // Channel data with tabii slugs
    private val channelData = listOf(
        "TRT 1" to "trt1",
        "TRT 2" to "trt2", 
        "TRT 3" to "trt3",
        "TRT Haber" to "trthaber",
        "TRT Spor" to "trtspor",
        "TRT Spor Yıldız" to "trtsporyildiz",
        "TRT Belgesel" to "trtbelgesel",
        "TRT Çocuk" to "trtcocuk",
        "TRT Müzik" to "trtmuzik",
        "TRT Arabi" to "trtarabi",
        "TRT Avaz" to "trtavaz",
        "TRT Türk" to "trtturk",
        "TRT World" to "trtworld",
        "TRT Kurdi" to "trtkurdi"
    )

    private suspend fun getTabiiChannels(): List<TabiiChannel> {
        val channels = mutableListOf<TabiiChannel>()
        val logoMap = getTrtLogos()
        
        for ((channelName, channelSlug) in channelData) {
            try {
                val channelUrl = "$tabiiUrl/canli-yayin/$channelSlug"
                val streamUrl = getStreamUrlFromChannelPage(channelUrl)
                
                if (streamUrl != null) {
                    val streamUrls = generateQualityVariants(streamUrl)
                    val logoUrl = findBestLogoForChannel(channelName, logoMap)
                    
                    channels.add(
                        TabiiChannel(
                            name = channelName,
                            streamUrls = streamUrls,
                            logoUrl = logoUrl,
                            description = "$channelName canlı yayın"
                        )
                    )
                }
                
                kotlinx.coroutines.delay(100)
            } catch (e: Exception) {
                // Continue with next channel
            }
        }
        
        return channels
    }

    private suspend fun getTrtLogos(): Map<String, String> {
        val logoMap = mutableMapOf<String, String>()
        
        try {
            val document = app.get("https://www.trt.net.tr/kurumsal/logolar").document
            val logoElements = document.select("img[src*='logo'], img[alt*='TRT']")
            
            for (logoElement in logoElements) {
                val logoUrl = logoElement.attr("src")
                val altText = logoElement.attr("alt")
                
                if (logoUrl.isNotBlank()) {
                    val channelName = determineChannelFromLogo(altText, logoUrl)
                    if (channelName.isNotBlank()) {
                        logoMap[channelName] = fixLogoUrl(logoUrl)
                    }
                }
            }
        } catch (e: Exception) {
            // Use fallback logos if official page fails
        }
        
        return logoMap
    }

    private fun determineChannelFromLogo(altText: String, logoUrl: String): String {
        val text = "$altText $logoUrl".lowercase()
        
        return when {
            text.contains("trt 1") || text.contains("trt1") -> "TRT 1"
            text.contains("trt 2") || text.contains("trt2") -> "TRT 2"
            text.contains("trt 3") || text.contains("trt3") -> "TRT 3"
            text.contains("trt haber") || text.contains("trthaber") -> "TRT Haber"
            text.contains("trt spor") && text.contains("yıldız") -> "TRT Spor Yıldız"
            text.contains("trt spor") || text.contains("trtspor") -> "TRT Spor"
            text.contains("trt belgesel") || text.contains("trtbelgesel") -> "TRT Belgesel"
            text.contains("trt çocuk") || text.contains("trtcocuk") -> "TRT Çocuk"
            text.contains("trt müzik") || text.contains("trtmuzik") -> "TRT Müzik"
            text.contains("trt arabi") || text.contains("trtarabi") -> "TRT Arabi"
            text.contains("trt avaz") || text.contains("trtavaz") -> "TRT Avaz"
            text.contains("trt türk") || text.contains("trtturk") -> "TRT Türk"
            text.contains("trt world") || text.contains("trtworld") -> "TRT World"
            text.contains("trt kurdi") || text.contains("trtkurdi") -> "TRT Kurdi"
            else -> ""
        }
    }

    private suspend fun getStreamUrlFromChannelPage(channelUrl: String): String? {
        try {
            val document = app.get(channelUrl).document
            val scripts = document.select("script")
            
            for (script in scripts) {
                val content = script.html()
                val patterns = listOf(
                    Regex("""(https://tv-trt[^"']*\.medya\.trt\.com\.tr/[^"']*\.m3u8)"""),
                    Regex("""(https://[^"']*trt[^"']*\.medya\.trt\.com\.tr/[^"']*\.m3u8)"""),
                    Regex("""["']?streamUrl["']?\s*:\s*["']([^"']+\.m3u8[^"']*)["']""")
                )
                
                for (pattern in patterns) {
                    val match = pattern.find(content)
                    if (match != null) {
                        return match.groupValues.getOrNull(1) ?: match.value
                    }
                }
            }
        } catch (e: Exception) {
            // Fallback to direct URLs
        }
        
        return getDirectTrtStreamUrl(channelUrl)
    }

    private fun getDirectTrtStreamUrl(channelUrl: String): String? {
        return when {
            channelUrl.contains("trt1") -> "https://tv-trt1.medya.trt.com.tr/master.m3u8"
            channelUrl.contains("trt2") -> "https://tv-trt2.medya.trt.com.tr/master.m3u8"
            channelUrl.contains("trt3") -> "https://tv-trt3.medya.trt.com.tr/master.m3u8"
            channelUrl.contains("trthaber") -> "https://tv-trthaber.medya.trt.com.tr/master.m3u8"
            channelUrl.contains("trtspor") && channelUrl.contains("yildiz") -> "https://trtspor2.medya.trt.com.tr/master.m3u8"
            channelUrl.contains("trtspor") -> "https://tv-trtspor1.medya.trt.com.tr/master.m3u8"
            channelUrl.contains("trtbelgesel") -> "https://tv-trtbelgesel.medya.trt.com.tr/master.m3u8"
            channelUrl.contains("trtcocuk") -> "https://tv-trtcocuk.medya.trt.com.tr/master.m3u8"
            channelUrl.contains("trtmuzik") -> "https://tv-trtmuzik.medya.trt.com.tr/master.m3u8"
            channelUrl.contains("trtarabi") -> "https://tv-trtarabi.medya.trt.com.tr/master.m3u8"
            channelUrl.contains("trtavaz") -> "https://tv-trtavaz.medya.trt.com.tr/master.m3u8"
            channelUrl.contains("trtturk") -> "https://tv-trtturk.medya.trt.com.tr/master.m3u8"
            channelUrl.contains("trtworld") -> "https://tv-trtworld.medya.trt.com.tr/master.m3u8"
            channelUrl.contains("trtkurdi") -> "https://tv-trtkurdi.medya.trt.com.tr/master.m3u8"
            else -> null
        }
    }

    private fun generateQualityVariants(baseUrl: String): List<String> {
        val variants = mutableListOf(baseUrl)
        
        try {
            if (baseUrl.contains("trt.com.tr")) {
                val basePath = baseUrl.substringBeforeLast(".").removeSuffix("_master")
                val qualities = listOf("360", "480", "720", "1080", "1440")
                
                qualities.forEach { quality ->
                    variants.add("${basePath}_${quality}.m3u8")
                }
            }
        } catch (e: Exception) {
            // Use base URL only if variant generation fails
        }
        
        return variants.distinct()
    }

    private fun findBestLogoForChannel(channelName: String, logoMap: Map<String, String>): String {
        return logoMap[channelName] ?: getFallbackLogo(channelName)
    }

    private fun getFallbackLogo(channelName: String): String {
        return when {
            channelName.contains("1") -> "https://www.trt.net.tr/images/trt1-logo.png"
            channelName.contains("2") -> "https://www.trt.net.tr/images/trt2-logo.png"
            channelName.contains("3") -> "https://www.trt.net.tr/images/trt3-logo.png"
            channelName.contains("haber") -> "https://www.trt.net.tr/images/trthaber-logo.png"
            channelName.contains("spor") && channelName.contains("yıldız") -> "https://www.trt.net.tr/images/trtsporyildiz-logo.png"
            channelName.contains("spor") -> "https://www.trt.net.tr/images/trtspor-logo.png"
            channelName.contains("belgesel") -> "https://www.trt.net.tr/images/trtbelgesel-logo.png"
            channelName.contains("çocuk") -> "https://www.trt.net.tr/images/trtcocuk-logo.png"
            channelName.contains("müzik") -> "https://www.trt.net.tr/images/trtmuzik-logo.png"
            channelName.contains("arabi") -> "https://www.trt.net.tr/images/trtarabi-logo.png"
            channelName.contains("avaz") -> "https://www.trt.net.tr/images/trtavaz-logo.png"
            channelName.contains("türk") -> "https://www.trt.net.tr/images/trtturk-logo.png"
            channelName.contains("world") -> "https://www.trt.net.tr/images/trtworld-logo.png"
            channelName.contains("kurdi") -> "https://www.trt.net.tr/images/trtkurdi-logo.png"
            else -> "https://www.trt.net.tr/images/trt-logo.png"
        }
    }

    private fun fixLogoUrl(url: String): String {
        var fixedUrl = url
        if (fixedUrl.startsWith("//")) {
            fixedUrl = "https:$fixedUrl"
        } else if (fixedUrl.startsWith("/")) {
            fixedUrl = "https://www.trt.net.tr$fixedUrl"
        }
        return fixedUrl.substringBefore("?")
    }

    private suspend fun getTrtSeries(archive: Boolean = false, page: Int = 1): List<TrtSeries> {
        val url = if (page == 1) {
            "$trt1Url/diziler?archive=$archive&order=title_asc"
        } else {
            "$trt1Url/diziler/$page?archive=$archive&order=title_asc"
        }
        
        val document = app.get(url).document
        
        return document.select("div.grid_grid-wrapper__elAnh > div.h-full.w-full > a").mapNotNull { element ->
            val title = element.selectFirst("div.card_card-title__IJ9af")?.text()?.trim() ?: return@mapNotNull null
            val href = element.attr("href")
            var posterUrl = element.selectFirst("img")?.attr("src")
            
            posterUrl = fixPosterUrlHorizontal(posterUrl)
            
            TrtSeries(
                title = title,
                url = fixTrtUrl(href),
                posterUrl = posterUrl
            )
        }
    }

    private fun fixPosterUrlHorizontal(url: String?): String? {
        return url?.replace("webp/w800/h450", "webp/w600/h338")
            ?.replace("webp/w400/h600", "webp/w600/h338")
            ?.replace("/q75/", "/q85/")
    }

    private fun fixTrtUrl(url: String): String {
        return if (url.startsWith("http")) url else "$trt1Url$url"
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val items = when (request.data) {
            "live" -> {
                getTabiiChannels().map { channel ->
                    newMovieSearchResponse(channel.name, channel.streamUrls.firstOrNull() ?: "", TvType.Live) {
                        this.posterUrl = channel.logoUrl
                    }
                }
            }
            "series" -> {
                getTrtSeries(archive = false, page = page).map { series ->
                    newTvSeriesSearchResponse(series.title, series.url) {
                        this.posterUrl = series.posterUrl
                    }
                }
            }
            "archive" -> {
                getTrtSeries(archive = true, page = page).map { series ->
                    newTvSeriesSearchResponse(series.title, series.url) {
                        this.posterUrl = series.posterUrl
                    }
                }
            }
            else -> emptyList()
        }

        val hasNext = when (request.data) {
            "series", "archive" -> items.isNotEmpty()
            else -> false
        }

        return newHomePageResponse(
            listOf(HomePageList(request.name, items, true)),
            hasNext = hasNext
        )
    }

    override suspend fun load(url: String): LoadResponse {
        if (url.contains("m3u8") || channelData.any { url.contains(it.second, ignoreCase = true) }) {
            val channels = getTabiiChannels()
            val liveChannel = channels.find { channel ->
                channel.streamUrls.contains(url) || url.contains(channel.name, ignoreCase = true)
            }

            if (liveChannel != null) {
                return newMovieLoadResponse(liveChannel.name, url, TvType.Live, url) {
                    this.posterUrl = liveChannel.logoUrl
                }
            }
        }

        val document = app.get(url).document
        val title = document.selectFirst("h1")?.text()?.trim() ?: throw ErrorLoadingException("Title not found")
        val description = document.selectFirst("meta[name=description]")?.attr("content") ?: ""
        var poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        poster = fixPosterUrlHorizontal(poster)
        
        val episodes = mutableListOf<Episode>()
        val seriesSlug = url.removePrefix("$trt1Url/diziler/").substringBefore("?")
        var currentPage = 1
        var hasMoreEpisodes = true
        
        while (hasMoreEpisodes && currentPage <= 20) {
            try {
                val episodesUrl = if (currentPage == 1) {
                    "$trt1Url/diziler/$seriesSlug/bolum"
                } else {
                    "$trt1Url/diziler/$seriesSlug/bolum/$currentPage"
                }
                
                val episodeDoc = app.get(episodesUrl).document
                val pageEpisodes = episodeDoc.select("div.grid_grid-wrapper__elAnh > div.h-full.w-full > a").mapNotNull { element ->
                    val epTitle = element.selectFirst("div.card_card-title__IJ9af")?.text()?.trim() ?: return@mapNotNull null
                    val epHref = element.attr("href")
                    var epPoster = element.selectFirst("img")?.attr("src")
                    val epDescription = element.selectFirst("p.card_card-description__0PSTi")?.text()?.trim() ?: ""
                    
                    epPoster = fixPosterUrlHorizontal(epPoster)
                    val episodeNumber = epTitle.replace(Regex("[^0-9]"), "").toIntOrNull() ?: currentPage
                    
                    newEpisode(fixTrtUrl(epHref)) {
                        this.name = epTitle
                        this.posterUrl = epPoster
                        this.episode = episodeNumber
                        this.description = epDescription
                    }
                }
                
                if (pageEpisodes.isNotEmpty()) {
                    episodes.addAll(pageEpisodes)
                    currentPage++
                    kotlinx.coroutines.delay(100)
                } else {
                    hasMoreEpisodes = false
                }
            } catch (e: Exception) {
                hasMoreEpisodes = false
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.contains("m3u8")) {
            M3u8Helper.generateM3u8(
                name,
                data,
                "$tabiiUrl/",
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0",
                    "Referer" to tabiiUrl
                )
            ).forEach(callback)
            return true
        }

        val document = app.get(data).document
        val m3u8Url = findM3u8UrlInTabii(document)
        
        if (m3u8Url != null) {
            M3u8Helper.generateM3u8(
                name,
                m3u8Url,
                "$trt1Url/",
                headers = mapOf("Referer" to "$trt1Url/")
            ).forEach(callback)
            return true
        }
        
        val youtubeUrl = findYouTubeUrl(document)
        if (youtubeUrl != null) {
            return handleYouTubeVideo(youtubeUrl, subtitleCallback, callback)
        }

        return false
    }

    private fun findM3u8UrlInTabii(document: org.jsoup.nodes.Document): String? {
        val scripts = document.select("script")
        for (script in scripts) {
            val content = script.html()
            val patterns = listOf(
                Regex("""(https://[^"']*trt[^"']*\.medya\.trt\.com\.tr[^"']*\.m3u8[^"']*)"""),
                Regex("""["']?url["']?\s*:\s*["']([^"']+\.m3u8[^"']*)["']""")
            )
            
            for (pattern in patterns) {
                val match = pattern.find(content)
                if (match != null) {
                    return match.groupValues.getOrNull(1) ?: match.value
                }
            }
        }
        return null
    }

    private fun findYouTubeUrl(document: org.jsoup.nodes.Document): String? {
        val iframe = document.selectFirst("iframe[src*='youtube.com/embed']")
        if (iframe != null) {
            val embedUrl = iframe.attr("src")
            val videoId = embedUrl.substringAfter("embed/").substringBefore("?")
            return "https://www.youtube.com/watch?v=$videoId"
        }
        
        val scripts = document.select("script")
        for (script in scripts) {
            val content = script.html()
            val patterns = listOf(
                Regex("""https://www\.youtube\.com/watch\?v=([^"']+)"""),
                Regex("""https://www\.youtube\.com/embed/([a-zA-Z0-9_-]+)""")
            )
            
            for (pattern in patterns) {
                val match = pattern.find(content)
                if (match != null) {
                    return if (match.value.contains("embed")) {
                        "https://www.youtube.com/watch?v=${match.groupValues[1]}"
                    } else {
                        match.value
                    }
                }
            }
        }
        
        return null
    }

    private suspend fun handleYouTubeVideo(
        youtubeUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val videoId = youtubeUrl.substringAfter("v=").substringBefore("&")
        val watchUrl = "https://www.youtube.com/watch?v=$videoId"
        val extractedLinks = mutableListOf<ExtractorLink>()

        loadExtractor(watchUrl, "https://www.youtube.com/", subtitleCallback) { link ->
            extractedLinks.add(link)
        }

        if (extractedLinks.isEmpty()) return false

        for (link in extractedLinks) {
            callback(
                newExtractorLink("YouTube", "YouTube", link.url) {
                    this.quality = link.quality
                    this.referer = "https://www.youtube.com/"
                    this.headers = link.headers ?: mapOf("User-Agent" to "Mozilla/5.0")
                }
            )
        }

        return true
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        
        getTabiiChannels().filter { it.name.contains(query, ignoreCase = true) }
            .forEach { channel ->
                results.add(
                    newMovieSearchResponse(channel.name, channel.streamUrls.firstOrNull() ?: "", TvType.Live) {
                        this.posterUrl = channel.logoUrl
                    }
                )
            }
        
        try {
            val searchUrl = "$trt1Url/arama/${query}?contenttype=series"
            val document = app.get(searchUrl).document
            document.select("div.grid_grid-wrapper__elAnh > div.h-full.w-full > a").mapNotNull { element ->
                val title = element.selectFirst("div.card_card-title__IJ9af")?.text()?.trim() ?: return@mapNotNull null
                val href = element.attr("href")
                var posterUrl = element.selectFirst("img")?.attr("src")
                posterUrl = fixPosterUrlHorizontal(posterUrl)
                
                if (href.contains("/diziler/")) {
                    results.add(
                        newTvSeriesSearchResponse(title, fixTrtUrl(href)) {
                            this.posterUrl = posterUrl
                        }
                    )
                }
            }
        } catch (e: Exception) {
            // Ignore search errors
        }
        
        return results
    }
}