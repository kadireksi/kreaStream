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

    private val dummyTvUrl = tabiiUrl
    private val dummyRadioUrl = "https://www.trtdinle.com/radyolar"

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
        } catch (e: Exception) {}
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
        } catch (_: Exception) {}
        return list.distinct()
    }

    private suspend fun getTrtCocuk(page: Int = 1): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        val url = "$trtCocukBase" + if (page > 1) "?page=$page" else ""

        try {
            val doc = app.get(url, timeout = 15).document

            // Target the program list container. This should match the structure you pasted.
            val anchors = doc.select("div.container-fluid.mpage div.col-xl-2.col-lg-3.col-md-4.col-sm-6.col-6.py-3 a[href^='/']")
            Log.d("TRTÇocuk", "anchors: ${anchors.size}")
            for (a in anchors) {
                val href = a.attr("href").trim()
                if (href.isBlank()) continue

                // Skip video links here; we only want series links (e.g. /ekip-siberay)
                if (href.startsWith("/video")) continue

                val fullUrl = if (href.startsWith("http")) href else trtCocukBase + href
                if (out.any { it.url == fullUrl }) continue

                val img = a.selectFirst("img")
                val title = img?.attr("alt")?.trim() ?: a.text().trim()
                if (title.isBlank()) continue

                val poster = img?.attr("data-src")?.ifBlank { img.attr("src") }?.trim() ?: ""

                out += newTvSeriesSearchResponse(title, fullUrl) {
                    this.posterUrl = poster
                    //this.referer = trtCocukBase
                }
            }

        } catch (e: Exception) {
            Log.e("TRTÇocuk", "getTrtCocuk failed: ${e.message}")
        }

        return out
    }

    private suspend fun getTrtCocukEpisodes(seriesUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        try {
            val doc = app.get(seriesUrl, timeout = 15).document

            // Select episode anchors that point to /video/slug-...-bolum
            val anchors = doc.select("a[href^='/video/']")

            for (a in anchors) {
                val href = a.attr("href").trim()
                if (href.isBlank()) continue

                val fullHref = if (href.startsWith("http")) href else trtCocukBase + href

                // Title: prefer p.oneline (sample had <p class="oneline">), fall back to img alt or link text
                val title = a.selectFirst("p.oneline")?.text()?.trim()
                    ?: a.selectFirst("img")?.attr("alt")?.trim()
                    ?: a.text().trim()
                if (title.isBlank()) continue

                // Poster: data-src or src on img inside anchor
                val imgEl = a.selectFirst("img")
                val poster = imgEl?.attr("data-src")?.ifBlank { imgEl.attr("src") }?.trim()

                // Episode number from title if possible
                val num = Regex("""(\d{1,4})\s*[.\-]?\s*Bölüm""", RegexOption.IGNORE_CASE)
                    .find(title)?.groupValues?.get(1)?.toIntOrNull()

                // Build Episode using newEpisode builder so it integrates with your load system
                val ep = newEpisode(fullHref) {
                    name = title
                    if (!poster.isNullOrBlank()) posterUrl = poster
                    episode = num ?: 0
                    season = 1
                    description = ""
                }

                episodes += ep
            }

            // Some series pages may include a carousel or other lists elsewhere; try to also pick videos from slide sections
            // (already covered by a[href^='/video/']), so nothing extra required.

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
            emptyList()
        }
    }

    private fun fixTrtUrl(url: String): String = if (url.startsWith("http")) url else "$trt1Url$url"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = when (request.data) {
            "live" -> listOf(
                newTvSeriesSearchResponse(
                    name = "TRT TV",
                    url = dummyTvUrl,
                    type = TvType.Live
                ) {
                    this.posterUrl = "https://www.trt.net.tr/logos/our-logos/corporate/trt.png"
                    this.year = 1964
                },
                 newTvSeriesSearchResponse(
                    name = "TRT Radyo",
                    url = dummyRadioUrl,
                    type = TvType.Live
                ) {
                    this.posterUrl = "https://trtdinle.com/trt-dinle-fb-share.jpg"
                    this.year = 1927
                }
            )
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
        // TV
        if (url == dummyTvUrl) {
            val channels = getTvChannels()
            return buildLiveTVResponse(channels)
        }

        if (url == dummyRadioUrl) {
            val channels = getRadioChannels()
            return buildLiveRadioResponse(channels)  // No fallback needed
        }

        if (url.contains(".m3u8", ignoreCase = true)) {
            return newMovieLoadResponse("TRT Canlı", url, TvType.Live, url) 
        }

        // TRT Çocuk Series (series page like https://www.trtcocuk.net.tr/ekip-siberay)
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

        // TRT Çocuk Video page (episode page)
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

        // Series (TRT1)
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
                } catch (e: Exception) { more = false }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        } catch (e: Exception) {
            throw ErrorLoadingException("Dizi yüklenemedi")
        }
    }

    private suspend fun buildLiveTVResponse(channels: List<TvChannel>): LoadResponse {
        val episodes = channels.mapIndexed { i, ch ->
            newEpisode(ch.streamUrl) {
                name = ch.name
                posterUrl = ch.logoUrl
                episode = i + 1
                season = 1
                description = ch.description
                this.data = ch.streamUrl
            }
        }

        return newTvSeriesLoadResponse("TRT Tv", dummyTvUrl, TvType.TvSeries, episodes) {
            this.posterUrl = "https://kariyer.trt.net.tr/wp-content/uploads/2022/01/trt-kariyer-logo.png"
            this.plot = "TRT TV canlı yayın"
            this.year = 1964
        }
    }

    private suspend fun buildLiveRadioResponse(channels: List<RadioChannel>): LoadResponse {
        val episodes = channels.mapIndexed { i, ch ->
            newEpisode(ch.streamUrl) {
                name = ch.name
                posterUrl = ch.logoUrl
                episode = i + 1
                season = 1
                description = ch.description
                this.data = ch.streamUrl
            }
        }

        return newTvSeriesLoadResponse("TRT Radyo", dummyRadioUrl, TvType.TvSeries, episodes) {
            this.posterUrl = "https://kariyer.trt.net.tr/wp-content/uploads/2022/01/trt-kariyer-logo.png"
            this.plot = "TRT Radyo canlı yayın"
            this.year = 1927
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        
        if (data.contains(".m3u8", ignoreCase = true) || data.contains(".aac", ignoreCase = true)) {
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

        // TRT Çocuk links (episode pages)
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
        } catch (e: Exception) {}

        return false
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()

        getTvChannels()
            .filter { it.name.contains(query, ignoreCase = true) }
            .forEach { ch ->
                out += newMovieSearchResponse(ch.name, ch.streamUrl, TvType.Live) {
                    this.posterUrl = ch.logoUrl
                }
            }

        getRadioChannels()
            .filter { it.name.contains(query, ignoreCase = true) }
            .forEach { ch ->
                out += newMovieSearchResponse(ch.name, ch.streamUrl, TvType.Live) {
                    this.posterUrl = ch.logoUrl
                }
            }

        // TRT Çocuk search (simple filter from main page)
        try {
            getTrtCocuk().filter { it.name.contains(query, ignoreCase = true) }.forEach { out += it }
        } catch (_: Exception) {}

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
        } catch (_: Exception) {}

        return out
    }
}
