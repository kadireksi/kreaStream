package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
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
    private val trt1Url = "https://www.trt1.com.tr"

    private val dummyTvUrl = "https://www.tabii.com/tr/watch/live"
    private val dummyRadioUrl = "https://www.trtdinle.com/radyolar"

    override val mainPage = mainPageOf(
        "series" to "Güncel Diziler",
        "archive" to "Eski Diziler",
        "live" to "TRT Canlı TV & Radyo"
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

    // Hardcoded reliable TRT TV channels (dynamic fallback if Tabii works)
    private suspend fun getTvChannels(): List<TvChannel> {
        // Try dynamic first
        val dynamic = tryDynamicTvChannels()
        if (dynamic.isNotEmpty()) {
            Log.i("TRT_TV", "Using dynamic TV channels: ${dynamic.size}")
            return dynamic
        }

        // Hardcoded always-works fallback
        Log.i("TRT_TV", "Using hardcoded TV channels")
        return listOf(
            TvChannel("TRT 1", "trt1", "https://tv-trt1.medya.trt.com.tr/master.m3u8",
                "https://upload.wikimedia.org/wikipedia/commons/thumb/7/7f/TRT_1_2018.svg/512px-TRT_1_2018.svg.png", "Genel eğlence ve diziler"),
            TvChannel("TRT Haber", "trthaber", "https://tv-trthaber.medya.trt.com.tr/master.m3u8",
                "https://upload.wikimedia.org/wikipedia/commons/thumb/6/6d/TRT_Haber_2018.svg/512px-TRT_Haber_2018.svg.png", "24 saat haber"),
            TvChannel("TRT 2", "trt2", "https://tv-trt2.medya.trt.com.tr/master.m3u8",
                "https://upload.wikimedia.org/wikipedia/commons/thumb/4/4a/TRT_2_2018.svg/512px-TRT_2_2018.svg.png", "Kültür-sanat"),
            TvChannel("TRT Spor", "trtspor", "https://tv-trtspor.medya.trt.com.tr/master.m3u8",
                "https://upload.wikimedia.org/wikipedia/commons/thumb/0/0d/TRT_Spor_2018.svg/512px-TRT_Spor_2018.svg.png", "Spor yayınları"),
            TvChannel("TRT Çocuk", "trtcocuk", "https://tv-trtcocuk.medya.trt.com.tr/master.m3u8",
                "https://upload.wikimedia.org/wikipedia/commons/thumb/1/1f/TRT_%C3%87ocuk_2018.svg/512px-TRT_%C3%87ocuk_2018.svg.png", "Çocuk programları"),
            TvChannel("TRT Belgesel", "trtbelgesel", "https://tv-trtbelgesel.medya.trt.com.tr/master.m3u8",
                "https://upload.wikimedia.org/wikipedia/commons/thumb/8/8e/TRT_Belgesel_2018.svg/512px-TRT_Belgesel_2018.svg.png", "Belgeseller")
        )
    }

    private suspend fun tryDynamicTvChannels(): List<TvChannel> {
        val result = mutableListOf<TvChannel>()
        try {
            val response = app.get("$dummyTvUrl/trt1?trackId=150002", headers = mapOf("User-Agent" to "Mozilla/5.0"))
            val doc = response.document
            val nextData = doc.selectFirst("#__NEXT_DATA__")?.data() ?: return emptyList()

            val json = JSONObject(nextData)
            val liveChannels = json.getJSONObject("props")
                .getJSONObject("pageProps")
                .getJSONArray("liveChannels")

            for (i in 0 until liveChannels.length()) {
                val ch = liveChannels.getJSONObject(i)
                val name = ch.getString("title")
                if (name.contains("tabii Spor", ignoreCase = true)) continue

                val slug = ch.getString("slug")
                var logoUrl = ""
                val images = ch.getJSONArray("images")
                for (j in 0 until images.length()) {
                    val img = images.getJSONObject(j)
                    if (img.getString("imageType") == "logo") {
                        logoUrl = "https://cms-tabii-public-image.tabii.com/int/${img.getString("name")}"
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

                result += TvChannel(name, slug, streamUrl, logoUrl, "$name - Canlı Yayın")
            }
        } catch (e: Exception) {
            Log.e("TRT_TV", "Dynamic TV fetch failed", e)
        }
        return result
    }

    // Hardcoded reliable TRT Radio channels
    private suspend fun getRadioChannels(): List<RadioChannel> {
        return listOf(
            RadioChannel("TRT FM", "trt-fm", "https://radio-trt-fm.medya.trt.com.tr/master.m3u8",
                "https://cdn-i.pr.trt.com.tr/trtdinle/w480/h480/q70/12467418.jpeg", "Türkçe Pop"),
            RadioChannel("TRT Radyo 1", "trt-radyo-1", "https://trt.radyotvonline.net/trt_1.aac",
                "https://cdn-i.pr.trt.com.tr/trtdinle/w480/h480/q70/12467415.jpeg", "Klasik & Haber"),
            RadioChannel("TRT Nağme", "trt-nagme", "https://rd-trtnagme.medya.trt.com.tr/master.m3u8",
                "https://cdn-i.pr.trt.com.tr/trtdinle/w480/h480/q70/12467465.jpeg", "Türk Sanat Müziği"),
            RadioChannel("TRT Türkü", "trt-turku", "https://rd-trtturku.medya.trt.com.tr/master.m3u8",
                "https://cdn-i.pr.trt.com.tr/trtdinle/w480/h480/q70/12467466.jpeg", "Türk Halk Müziği"),
            RadioChannel("TRT Radyo Haber", "trt-radyo-haber", "https://trt.radyotvonline.net/trt_haber.aac",
                "https://cdn-i.pr.trt.com.tr/trtdinle/w480/h480/q70/12530424_0-0-2048-1536.jpeg", "24 Saat Haber"),
            RadioChannel("TRT Radyo 3", "trt-radyo-3", "https://trt.radyotvonline.net/trt_3.aac",
                "https://cdn-i.pr.trt.com.tr/trtdinle/w480/h480/q70/12467470.jpeg", "Dünya Müziği"),
            RadioChannel("TRT Kurdi", "trt-kurdi", "https://rd-trtkurdi.medya.trt.com.tr/master.m3u8",
                "https://cdn-i.pr.trt.com.tr/trtdinle/w480/h480/q70/12467508.jpeg", "Kürtçe Yayın"),
            RadioChannel("TRT Arabi", "trt-arabi", "https://rd-trtarabi.medya.trt.com.tr/master.m3u8",
                "https://cdn-i.pr.trt.com.tr/trtdinle/w480/h480/q70/12467526.jpeg", "Arapça Yayın"),
            RadioChannel("Antalya Radyosu", "antalya-radyosu", "https://rd-antalya.medya.trt.com.tr/master.m3u8",
                "https://cdn-i.pr.trt.com.tr/trtdinle/w480/h480/q70/12467462.jpeg", "Bölgesel"),
            RadioChannel("Çukurova Radyosu", "cukurova-radyosu", "https://rd-cukurova.medya.trt.com.tr/master.m3u8",
                "https://cdn-i.pr.trt.com.tr/trtdinle/w480/h480/q70/12467486.jpeg", "Bölgesel"),
            RadioChannel("Erzurum Radyosu", "erzurum-radyosu", "https://rd-erzurum.medya.trt.com.tr/master.m3u8",
                "https://cdn-i.pr.trt.com.tr/trtdinle/w480/h480/q70/12467502.jpeg", "Bölgesel"),
            RadioChannel("Trabzon Radyosu", "trabzon-radyosu", "https://rd-trabzon.medya.trt.com.tr/master.m3u8",
                "https://cdn-i.pr.trt.com.tr/trtdinle/w480/h480/q70/12467523.jpeg", "Bölgesel")
        )
    }

    private suspend fun getTrtSeries(archive: Boolean = false, page: Int = 1): List<SearchResponse> {
        return try {
            val url = if (page == 1) "$trt1Url/diziler?archive=$archive&order=title_asc"
            else "$trt1Url/diziler/$page?archive=$archive&order=title_asc"

            app.get(url, timeout = 15).document
                .select("div.grid_grid-wrapper__elAnh > div.h-full.w-full > a")
                .mapNotNull { el ->
                    val title = el.selectFirst("div.card_card-title__IJ9af")?.text()?.trim() ?: return@mapNotNull null
                    val href = el.attr("href")
                    var poster = el.selectFirst("img")?.absUrl("src")
                        ?.replace(Regex("webp/w\\d+/h\\d+"), "webp/w600/h338")
                        ?.replace("/q75/", "/q85/")

                    newTvSeriesSearchResponse(title, "$trt1Url$href") { 
                        this.posterUrl = poster 
                    }
                }
        } catch (e: Exception) { 
            Log.e("TRT", "Series fetch error", e)
            emptyList() 
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = when (request.data) {
            "live" -> listOf(
                newMovieSearchResponse("TRT TV Canlı", dummyTvUrl, TvType.Live) {
                    this.posterUrl = "https://www.trt.net.tr/logos/our-logos/corporate/trt.png"
                },
                newMovieSearchResponse("TRT Radyo Canlı", dummyRadioUrl, TvType.Live) {
                    this.posterUrl = "https://port-rotf.pr.trt.com.tr/r/trtdinle/w480/h480/q70/12530507_0-0-2048-1536.jpeg"
                }
            )
            "series" -> getTrtSeries(archive = false, page = page)
            "archive" -> getTrtSeries(archive = true, page = page)
            else -> emptyList()
        }

        val hasNext = request.data in listOf("series", "archive") && items.isNotEmpty()

        return newHomePageResponse(
            listOf(HomePageList(request.name, items, true)),
            hasNext = hasNext
        )
    }

    override suspend fun load(url: String): LoadResponse {
        return when {
            url == dummyTvUrl -> buildLiveTVResponse(getTvChannels())
            url == dummyRadioUrl -> buildLiveRadioResponse(getRadioChannels())
            url.contains(".m3u8", ignoreCase = true) || url.contains(".aac", ignoreCase = true) -> 
                newMovieLoadResponse("TRT Canlı Yayın", url, TvType.Live, url) {
                    this.posterUrl = "https://kariyer.trt.net.tr/wp-content/uploads/2022/01/trt-kariyer-logo.png"
                }
            else -> {
                try {
                    val doc = app.get(url, timeout = 15).document
                    val title = doc.selectFirst("h1")?.text()?.trim() ?: throw ErrorLoadingException("Başlık bulunamadı")
                    val plot = doc.selectFirst("meta[name=description]")?.attr("content") ?: ""
                    var poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
                        ?.replace(Regex("webp/w\\d+/h\\d+"), "webp/w600/h338")
                        ?.replace("/q75/", "/q85/")

                    val seriesSlug = url.removePrefix("$trt1Url/diziler/").substringBefore("/")
                    val episodes = mutableListOf<Episode>()
                    var pageNum = 1
                    var more = true

                    while (more && pageNum <= 30) {
                        try {
                            val epUrl = if (pageNum == 1) "$trt1Url/diziler/$seriesSlug/bolum"
                            else "$trt1Url/diziler/$seriesSlug/bolum/$pageNum"
                            val epDoc = app.get(epUrl, timeout = 10).document
                            val pageEps = epDoc.select("div.grid_grid-wrapper__elAnh > div.h-full.w-full > a")
                                .mapNotNull { el ->
                                    val epTitle = el.selectFirst("div.card_card-title__IJ9af")?.text()?.trim() ?: return@mapNotNull null
                                    val href = el.attr("href")
                                    var img = el.selectFirst("img")?.absUrl("src")
                                        ?.replace(Regex("webp/w\\d+/h\\d+"), "webp/w600/h338")
                                        ?.replace("/q75/", "/q85/")
                                    val desc = el.selectFirst("p.card_card-description__0PSTi")?.text()?.trim() ?: ""
                                    val epNum = epTitle.replace(Regex("[^0-9]"), "").toIntOrNull() ?: pageNum

                                    newEpisode("$trt1Url$href") {
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
                        }
                    }

                    newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                        this.posterUrl = poster
                        this.plot = plot
                    }
                } catch (e: Exception) {
                    throw ErrorLoadingException("Dizi yüklenemedi: ${e.message}")
                }
            }
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
                this.data = ch.streamUrl  // Fixes Continue Watching posters
            }
        }

        return newTvSeriesLoadResponse("TRT Canlı TV", dummyTvUrl, TvType.Live, episodes) {
            this.posterUrl = "https://www.trt.net.tr/logos/our-logos/corporate/trt.png"
            this.plot = "TRT 1, Haber, Spor, Çocuk ve daha fazlası - Canlı yayın"
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
                this.data = ch.streamUrl  // Fixes Continue Watching posters
            }
        }

        return newTvSeriesLoadResponse("TRT Radyo Canlı", dummyRadioUrl, TvType.Live, episodes) {
            this.posterUrl = "https://port-rotf.pr.trt.com.tr/r/trtdinle/w480/h480/q70/12530507_0-0-2048-1536.jpeg"
            this.plot = "TRT FM, Radyo 1, Türkü, Nağme ve bölgesel - Canlı yayın"
        }
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

        try {
            val doc = app.get(data, timeout = 10).document
            val script = doc.select("script").find { it.html().contains("playerConfig") }?.html()
            val m3u8 = script?.let {
                Regex("""streamUrl["']?\s*:\s*["']([^"']+\.m3u8[^"']*)["']""").find(it)?.groupValues?.get(1)
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

            val yt = doc.selectFirst("iframe[src*='youtube.com/embed']")?.attr("src")
                ?.let { "https://www.youtube.com/watch?v=${it.substringAfter("embed/").substringBefore("?")}" }
                ?: Regex("""https://www\.youtube\.com/watch\?v=([a-zA-Z0-9_-]+)""").find(doc.html())?.groupValues?.get(1)
                    ?.let { "https://www.youtube.com/watch?v=$it" }

            if (yt != null) {
                loadExtractor(yt, tabiiUrl, subtitleCallback, callback)
                return true
            }
        } catch (e: Exception) {
            Log.e("TRT", "loadLinks error", e)
        }

        return false
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()

        getTvChannels().filter { it.name.contains(query, ignoreCase = true) }
            .forEach { ch ->
                out += newMovieSearchResponse(ch.name, ch.streamUrl, TvType.Live) { this.posterUrl = ch.logoUrl }
            }

        getRadioChannels().filter { it.name.contains(query, ignoreCase = true) }
            .forEach { ch ->
                out += newMovieSearchResponse(ch.name, ch.streamUrl, TvType.Live) { this.posterUrl = ch.logoUrl }
            }

        try {
            val sUrl = "$trt1Url/arama/$query?contenttype=series"
            app.get(sUrl, timeout = 10).document
                .select("div.grid_grid-wrapper__elAnh > div.h-full.w-full > a")
                .mapNotNull { el ->
                    val title = el.selectFirst("div.card_card-title__IJ9af")?.text()?.trim() ?: return@mapNotNull null
                    val href = el.attr("href")
                    if (!href.contains("/diziler/")) return@mapNotNull null
                    var poster = el.selectFirst("img")?.absUrl("src")
                        ?.replace(Regex("webp/w\\d+/h\\d+"), "webp/w600/h338")
                        ?.replace("/q75/", "/q85/")

                    out += newTvSeriesSearchResponse(title, "$trt1Url$href") { this.posterUrl = poster }
                }
        } catch (e: Exception) {
            Log.e("TRT", "Search error", e)
        }

        return out
    }
}