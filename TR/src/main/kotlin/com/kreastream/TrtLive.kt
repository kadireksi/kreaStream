package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class TrtLive : MainAPI() {
    override var mainUrl = "https://www.trt.net.tr"
    override var name = "TRT Canlı"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Live)

    private val trtLiveChannels = listOf(
        Triple("TRT 1", "https://tv-trt1.medya.trt.com.tr/master_720.m3u8", "https://upload.wikimedia.org/wikipedia/commons/7/70/Logo_of_TRT1.png"),
        Triple("TRT 2", "https://tv-trt2.medya.trt.com.tr/master_720.m3u8", "https://upload.wikimedia.org/wikipedia/commons/a/a8/Trt2_logo.png"),
        Triple("TRT Spor", "https://tv-trtspor1.medya.trt.com.tr/master_720.m3u8", "https://upload.wikimedia.org/wikipedia/commons/f/fb/TRT_Spor_logo.png"),
        Triple("TRT Spor 2", "https://tv-trtspor2.medya.trt.com.tr/master_720p.m3u8", "https://upload.wikimedia.org/wikipedia/commons/3/3a/TRT_Spor_Yildiz_logo.png"),
        Triple("TRT Haber", "https://tv-trthaber.medya.trt.com.tr/master_720.m3u8", "https://upload.wikimedia.org/wikipedia/commons/8/8e/TRT_Haber_logo.png"),
        Triple("TRT Belgesel", "https://tv-trtbelgesel.medya.trt.com.tr/master_720.m3u8", "https://upload.wikimedia.org/wikipedia/commons/b/b4/TRT_Belgesel_logo.png"),
        Triple("TRT Çocuk", "https://tv-trtcocuk.medya.trt.com.tr/master_720.m3u8", "https://upload.wikimedia.org/wikipedia/commons/d/d2/TRT_%C3%87ocuk_logo.png"),
        Triple("TRT Müzik", "https://tv-trtmuzik.medya.trt.com.tr/master_720.m3u8", "https://upload.wikimedia.org/wikipedia/commons/3/38/TRT_Muzik_logo.png"),
        Triple("TRT World", "https://tv-trtworld.medya.trt.com.tr/master_720.m3u8", "https://upload.wikimedia.org/wikipedia/commons/5/5b/TRT_World_logo.png")
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val liveItem = newTvSeriesSearchResponse("Canlı Yayınlar", "$mainUrl/canli") {
            this.posterUrl = "https://upload.wikimedia.org/wikipedia/commons/7/70/Logo_of_TRT1.png"
        }
        return newHomePageResponse(listOf(HomePageList("Canlı Yayınlar", listOf(liveItem))))
    }

    override suspend fun load(url: String): LoadResponse {
        if (url.endsWith("/canli")) {
            val episodes = trtLiveChannels.map { (name, streamUrl, logo) ->
                val now = TrtUtils.getNowPlaying(name)
                newEpisode(streamUrl) {
                    this.name = name
                    this.posterUrl = logo
                    this.description = now ?: "Canlı yayın akışı"
                }
            }

            val response = newTvSeriesLoadResponse("Canlı Yayınlar", "$mainUrl/canli") {
                this.posterUrl = "https://upload.wikimedia.org/wikipedia/commons/7/70/Logo_of_TRT1.png"
                this.plot = "TRT kanallarının canlı yayın listesi"
                this.episodes = episodes
            }
            return response
        }

        return newTvSeriesLoadResponse("TRT Canlı", "$mainUrl") {
            this.posterUrl = "https://upload.wikimedia.org/wikipedia/commons/7/70/Logo_of_TRT1.png"
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.endsWith(".m3u8")) {
            M3u8Helper.generateM3u8(
                source = "TRT",
                streamUrl = data,
                referer = mainUrl,
                headers = mapOf("Referer" to mainUrl)
            ).forEach(callback)
            return true
        }
        return false
    }
}
