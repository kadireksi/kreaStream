package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject

class TrtLive : MainAPI() {
    override var mainUrl = "https://www.trt.net.tr"
    override var name = "TRT Canlı"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Live)

    // --- Static live channels definition ---
    private val trtLiveChannels = listOf(
        Triple("TRT 1", "https://tv-trt1.medya.trt.com.tr/master_720.m3u8", "https://upload.wikimedia.org/wikipedia/commons/7/70/Logo_of_TRT1.png"),
        Triple("TRT 2", "https://tv-trt2.medya.trt.com.tr/master_720.m3u8", "https://upload.wikimedia.org/wikipedia/commons/a/a8/Trt2_logo.png"),
        Triple("TRT Spor", "https://tv-trtspor1.medya.trt.com.tr/master_720.m3u8", "https://upload.wikimedia.org/wikipedia/commons/f/fb/TRT_Spor_logo.png"),
        Triple("TRT Spor 2", "https://tv-trtspor2.medya.trt.com.tr/master_720p.m3u8", "https://upload.wikimedia.org/wikipedia/commons/3/3a/TRT_Spor_Yildiz_logo.png"),
        Triple("TRT Haber", "https://tv-trthaber.medya.trt.com.tr/master_720.m3u8", "https://upload.wikimedia.org/wikipedia/commons/8/8e/TRT_Haber_logo.png"),
        Triple("TRT Belgesel", "https://tv-trtbelgesel.medya.trt.com.tr/master_720.m3u8", "https://upload.wikimedia.org/wikipedia/commons/b/b4/TRT_Belgesel_logo.png"),
        Triple("TRT Çocuk", "https://tv-trtcocuk.medya.trt.com.tr/master_720.m3u8", "https://upload.wikimedia.org/wikipedia/commons/d/d2/TRT_%C3%87ocuk_logo.png"),
        Triple("TRT World", "https://tv-trtworld.medya.trt.com.tr/master_720.m3u8", "https://upload.wikimedia.org/wikipedia/commons/5/5b/TRT_World_logo.png")
    )

    // region Homepage
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val home = mutableListOf<HomePageList>()

        val liveSeries = listOf(
            newTvSeriesSearchResponse("Canlı Yayınlar", "$mainUrl/canli") {
                this.posterUrl = "https://upload.wikimedia.org/wikipedia/commons/7/70/Logo_of_TRT1.png"
            }
        )
        home.add(HomePageList("Canlı Yayınlar", liveSeries))
        return HomePageResponse(home)
    }
    // endregion

    // region Load page for live channels
    override suspend fun load(url: String): LoadResponse {
        if (url.endsWith("/canli")) {
            val episodes = mutableListOf<Episode>()

            for ((name, streamUrl, logo) in trtLiveChannels) {
                val nowPlaying = getNowPlaying(name)
                episodes.add(
                    newEpisode(streamUrl) {
                        this.name = name
                        this.posterUrl = logo
                        this.description = nowPlaying ?: "Canlı yayın akışı"
                    }
                )
            }

            return newTvSeriesLoadResponse("Canlı Yayınlar", url, TvType.Live, episodes) {
                this.posterUrl = "https://upload.wikimedia.org/wikipedia/commons/7/70/Logo_of_TRT1.png"
                this.plot = "TRT kanallarının canlı yayın listesi"
            }
        }

        return newTvSeriesLoadResponse("TRT Canlı", url, TvType.Live, listOf()) {}
    }
    // endregion

    // region Links (HLS multi-quality)
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.endsWith(".m3u8")) {
            M3u8Helper.generateM3u8(
                name = "TRT",
                url = data,
                referer = mainUrl,
                headers = mapOf("Referer" to mainUrl)
            ).forEach(callback)
            return true
        }
        return false
    }
    // endregion

    // region EPG Now Playing Fetcher
    private suspend fun getNowPlaying(channelName: String): String? {
        // The EPG JSON endpoint is available via: https://www.trt.net.tr/_Channel/Service/GetScheduleByChannel?channel=trt1
        val map = mapOf(
            "TRT 1" to "trt1",
            "TRT 2" to "trt2",
            "TRT Spor" to "trtspor",
            "TRT Spor 2" to "trtspor2",
            "TRT Haber" to "trthaber",
            "TRT Belgesel" to "trtbelgesel",
            "TRT Çocuk" to "trtcocuk",
            "TRT World" to "trtworld"
        )

        val code = map[channelName] ?: return null
        return try {
            val response = app.get("https://www.trt.net.tr/_Channel/Service/GetScheduleByChannel?channel=$code").text
            val json = JSONObject(response)
            val now = json.optJSONArray("ScheduleList")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i)
                    if (obj?.optBoolean("IsNow") == true) {
                        return obj.optString("ProgramName", null)
                    }
                }
                null
            }
            now
        } catch (e: Exception) {
            null
        }
    }
    // endregion
}
