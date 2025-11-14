package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
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
    private val liveBase = "$tabiiUrl/watch/live"

    private val dummyTvUrl = tabiiUrl
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

    // Dynamic TV channels from Tabii (TRT 1, Haber, Çocuk, etc.)
    private suspend fun getTvChannels(): List<TvChannel> {
        val result = mutableListOf<TvChannel>()
        try {
            val response = app.get("$liveBase/trt1?trackId=150002")
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
            Log.i("TRT_TV", "Loaded ${result.size} live TV channels")
        } catch (e: Exception) {
            Log.e("TRT_TV", "Failed to load TV channels", e)
        }
        return result
    }

    // Hardcoded & always working TRT Radio channels
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

                    newTvSeriesSearchResponse(title, "$trt1Url$href") { this.posterUrl = poster }
                }
        } catch (e: Exception) { emptyList() }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = when (request.data) {
            "live" -> listOf(
                newMovieSearchResponse("TRT TV Canlı", dummyTvUrl, TvType.Live) {
                    posterUrl = "https://www.trt.net.tr/logos/our-logos/corporate/trt.png"
                    year = 1964
                    plot = "TRT 1, TRT Haber, TRT Çocuk ve daha fazlası"
                },
                newMovieSearchResponse("TRT Radyo Canlı", dummyRadioUrl, TvType.Live) {
                    posterUrl = "https://port-rotf.pr.trt.com.tr/r/trtdinle/w480/h480/q70/12530507_0-0-2048-1536.jpeg"
                    year = 1927
                    plot = "TRT FM, Radyo 1, Türkü, Nağme ve bölgesel radyolar"
                }
            )
            "series" -> getTrtSeries(archive = false, page = page)
            "archive" -> getTrtSeries(archive = true, page = page)
            else -> emptyList()
        }

        return newHomePageResponse(HomePageList(request.name, items, true), items.isNotEmpty())
    }

    override suspend fun load(url: String): LoadResponse {
        return when {
            url == dummyTvUrl -> {
                val channels = getTvChannels()
                if (channels.isEmpty()) {
                    buildLiveTVResponse(listOf(TvChannel("TRT 1", "trt1", "https://tv-trt1.medya.trt.com.tr/master.m3u8",
                        "https://upload.wikimedia.org/wikipedia/tr/6/67/TRT_1_logo.png", "TRT 1")))
                } else buildLiveTVResponse(channels)
            }
            url == dummyRadioUrl -> buildLiveRadioResponse(getRadioChannels())
            url.contains(".m3u8", ignoreCase = true) || url.contains(".aac", ignoreCase = true) ->
                newMovieLoadResponse("Canlı Yayın", url, TvType.Live, url) {
                    posterUrl = "https://kariyer.trt.net.tr/wp-content/uploads/2022/01/trt-kariyer-logo.png"
                }
            else -> {
                val doc = app.get(url, timeout = 15).document
                val title = doc.selectFirst("h1")?.text()?.trim() ?: "TRT Dizi"
                val plot = doc.selectFirst("meta[name=description]")?.attr("content") ?: ""
                val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
                    ?.replace(Regex("webp/w\\d+/h\\d+"), "webp/w600/h338")
                    ?.replace("/q75/", "/q85/")

                val episodes = mutableListOf<Episode>()
                var pageNum = 1
                while (pageNum <= 30) {
                    val epDoc = app.get("$url/bolum${if (pageNum > 1) "/$pageNum" else ""}", timeout = 10).document
                    val eps = epDoc.select("div.grid_grid-wrapper__elAnh > div.h-full.w-full > a")
                        .mapNotNull { el ->
                            val epTitle = el.selectFirst("div.card_card-title__IJ9af")?.text()?.trim() ?: return@mapNotNull null
                            val href = el.attr("href")
                            val img = el.selectFirst("img")?.absUrl("src")
                                ?.replace(Regex("webp/w\\d+/h\\d+"), "webp/w600/h338")
                                ?.replace("/q75/", "/q85/")
                            newEpisode("$trt1Url$href") {
                                name = epTitle
                                posterUrl = img
                                episode = epTitle.replace(Regex("[^0-9]"), "").toIntOrNull() ?: pageNum
                            }
                        }
                    if (eps.isEmpty()) break
                    episodes += eps
                    pageNum++
                }
                newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.plot = plot
                }
            }
        }
    }

    private fun buildLiveTVResponse(channels: List<TvChannel>): LoadResponse {
        val episodes = channels.mapIndexed { i, ch ->
            newEpisode(ch.streamUrl) {
                name = "TRT ${ch.name}"
                posterUrl = ch.logoUrl
                episode = i + 1
                season = 1
                description = ch.description
                this.data = ch.streamUrl
            }
        }
        return newTvSeriesLoadResponse("TRT Canlı TV", dummyTvUrl, TvType.Live, episodes) {
            posterUrl = "https://www.trt.net.tr/logos/our-logos/corporate/trt.png"
            plot = "Tüm TRT kanalları canlı yayın"
            year = 1964
            tags = listOf("Canlı", "TV", "TRT")
        }
    }

    private fun buildLiveRadioResponse(channels: List<RadioChannel>): LoadResponse {
        val episodes = channels.mapIndexed { i, ch ->
            newEpisode(ch.streamUrl) {
                name = "TRT ${ch.name}"
                posterUrl = ch.logoUrl
                episode = i + 1
                season = 1
                description = ch.description
                this.data = ch.streamUrl
            }
        }
        return newTvSeriesLoadResponse("TRT Radyo Canlı", dummyRadioUrl, TvType.Live, episodes) {
            posterUrl = "https://port-rotf.pr.trt.com.tr/r/trtdinle/w480/h480/q70/12530507_0-0-2048-1536.jpeg"
            plot = "TRT'nin tüm radyoları 7/24 canlı"
            year = 1927
            tags = listOf("Radyo", "Müzik", "TRT")
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.contains(".m3u8") || data.contains(".aac")) {
            M3u8Helper.generateM3u8(name, data, tabiiUrl).forEach(callback)
            return true
        }
        return false
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()

        getTvChannels().filter { it.name.contains(query, true) }
            .forEach { results += newMovieSearchResponse(it.name, it.streamUrl, TvType.Live) { posterUrl = it.logoUrl } }

        getRadioChannels().filter { it.name.contains(query, true) }
            .forEach { results += newMovieSearchResponse(it.name, it.streamUrl, TvType.Live) { posterUrl = it.logoUrl } }

        return results
    }
}