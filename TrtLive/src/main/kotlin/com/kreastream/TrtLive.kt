package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class TrtLive : MainAPI() {
    override var mainUrl = "https://www.trt.net.tr"
    override var name = "TRT Canlı"
    override var hasMainPage = true
    override val supportedTypes = setOf(TvType.Live, TvType.Series)
    override var lang = "tr"

    private val mainLogo = "https://raw.githubusercontent.com/kadireksi/kreaStream/refs/heads/logos/TV/trt.png"

    // TV Channels with multiple quality options
    private val tvChannels = listOf(
        TrtChannel(
            "TRT 1",
            listOf(
                "https://tv-trt1.medya.trt.com.tr/master_360.m3u8",
                "https://tv-trt1.medya.trt.com.tr/master_480.m3u8",
                "https://tv-trt1.medya.trt.com.tr/master_720.m3u8",
                "https://tv-trt1.medya.trt.com.tr/master_1080.m3u8",
                "https://tv-trt1.medya.trt.com.tr/master_1440.m3u8"
            ),
            "https://raw.githubusercontent.com/kadireksi/kreaStream/refs/heads/logos/TV/TRT%201.png"
        ),
        TrtChannel(
            "TRT 2",
            listOf(
                "https://tv-trt2.medya.trt.com.tr/master_360.m3u8",
                "https://tv-trt2.medya.trt.com.tr/master_480.m3u8",
                "https://tv-trt2.medya.trt.com.tr/master_720.m3u8",
                "https://tv-trt2.medya.trt.com.tr/master_1080.m3u8",
                "https://tv-trt2.medya.trt.com.tr/master_1440.m3u8"
            ),
            "https://raw.githubusercontent.com/kadireksi/kreaStream/refs/heads/logos/TV/TRT%202.png"
        ),
        TrtChannel(
            "TRT Spor",
            listOf(
                "https://tv-trtspor1.medya.trt.com.tr/master_360.m3u8",
                "https://tv-trtspor1.medya.trt.com.tr/master_480.m3u8",
                "https://tv-trtspor1.medya.trt.com.tr/master_720.m3u8",
                "https://tv-trtspor1.medya.trt.com.tr/master_1080.m3u8",
                "https://tv-trtspor1.medya.trt.com.tr/master_1440.m3u8"
            ),
            "https://raw.githubusercontent.com/kadireksi/kreaStream/refs/heads/logos/TV/TRT%20Spor.png"
        ),
        TrtChannel(
            "TRT Belgesel",
            listOf(
                "https://tv-trtbelgesel.medya.trt.com.tr/master_360.m3u8",
                "https://tv-trtbelgesel.medya.trt.com.tr/master_480.m3u8",
                "https://tv-trtbelgesel.medya.trt.com.tr/master_720.m3u8",
                "https://tv-trtbelgesel.medya.trt.com.tr/master_1080.m3u8",
                "https://tv-trtbelgesel.medya.trt.com.tr/master_1440.m3u8"
            ),
            "https://raw.githubusercontent.com/kadireksi/kreaStream/refs/heads/logos/TV/TRT%20Belgesel.png"
        ),
        TrtChannel(
            "TRT Çocuk",
            listOf(
                "https://tv-trtcocuk.medya.trt.com.tr/master_360.m3u8",
                "https://tv-trtcocuk.medya.trt.com.tr/master_480.m3u8",
                "https://tv-trtcocuk.medya.trt.com.tr/master_720.m3u8",
                "https://tv-trtcocuk.medya.trt.com.tr/master_1080.m3u8",
                "https://tv-trtcocuk.medya.trt.com.tr/master_1440.m3u8"
            ),
            "https://raw.githubusercontent.com/kadireksi/kreaStream/refs/heads/logos/TV/TRT%20%C3%87ocuk.png"
        ),
        TrtChannel(
            "TRT Arabi",
            listOf(
                "https://tv-trtarabi.medya.trt.com.tr/master_360.m3u8",
                "https://tv-trtarabi.medya.trt.com.tr/master_480.m3u8",
                "https://tv-trtarabi.medya.trt.com.tr/master_720.m3u8",
                "https://tv-trtarabi.medya.trt.com.tr/master_1080.m3u8"
            ),
            "https://raw.githubusercontent.com/kadireksi/kreaStream/refs/heads/logos/TV/TRT%20Arabi.png"
        ),
        TrtChannel(
            "TRT Avaz",
            listOf(
                "https://tv-trtavaz.medya.trt.com.tr/master_360.m3u8",
                "https://tv-trtavaz.medya.trt.com.tr/master_480.m3u8",
                "https://tv-trtavaz.medya.trt.com.tr/master_720.m3u8",
                "https://tv-trtavaz.medya.trt.com.tr/master_1080.m3u8",
                "https://tv-trtavaz.medya.trt.com.tr/master_1440.m3u8"
            ),
            "https://raw.githubusercontent.com/kadireksi/kreaStream/refs/heads/logos/TV/TRT%20Avaz.png"
        ),
        TrtChannel(
            "TRT Türk",
            listOf(
                "https://tv-trtturk.medya.trt.com.tr/master_360.m3u8",
                "https://tv-trtturk.medya.trt.com.tr/master_480.m3u8",
                "https://tv-trtturk.medya.trt.com.tr/master_720.m3u8",
                "https://tv-trtturk.medya.trt.com.tr/master_1080.m3u8",
                "https://tv-trtturk.medya.trt.com.tr/master_1440.m3u8"
            ),
            "https://raw.githubusercontent.com/kadireksi/kreaStream/refs/heads/logos/TV/TRT%20T%C3%BCrk.png"
        ),
        TrtChannel(
            "TRT World",
            listOf(
                "https://tv-trtworld.medya.trt.com.tr/master_360.m3u8",
                "https://tv-trtworld.medya.trt.com.tr/master_480.m3u8",
                "https://tv-trtworld.medya.trt.com.tr/master_720.m3u8",
                "https://tv-trtworld.medya.trt.com.tr/master_1080.m3u8",
                "https://tv-trtworld.medya.trt.com.tr/master_1440.m3u8"
            ),
            "https://raw.githubusercontent.com/kadireksi/kreaStream/refs/heads/logos/TV/TRT%20World.png"
        ),
        TrtChannel(
            "TRT Haber",
            listOf(
                "https://tv-trthaber.medya.trt.com.tr/master_360.m3u8",
                "https://tv-trthaber.medya.trt.com.tr/master_480.m3u8",
                "https://tv-trthaber.medya.trt.com.tr/master_720.m3u8",
                "https://tv-trthaber.medya.trt.com.tr/master_1080.m3u8",
                "https://tv-trthaber.medya.trt.com.tr/master_1440.m3u8"
            ),
            "https://raw.githubusercontent.com/kadireksi/kreaStream/refs/heads/logos/TV/TRT%20Haber.png"
        ),
        TrtChannel(
            "TRT Kurdi",
            listOf(
                "https://tv-trtkurdi.medya.trt.com.tr/master_360.m3u8",
                "https://tv-trtkurdi.medya.trt.com.tr/master_480.m3u8",
                "https://tv-trtkurdi.medya.trt.com.tr/master_720.m3u8",
                "https://tv-trtkurdi.medya.trt.com.tr/master_1080.m3u8",
                "https://tv-trtkurdi.medya.trt.com.tr/master_1440.m3u8"
            ),
            "https://raw.githubusercontent.com/kadireksi/kreaStream/refs/heads/logos/TV/TRT%20Kurdi.png"
        ),
        TrtChannel(
            "TRT Müzik",
            listOf(
                "https://tv-trtmuzik.medya.trt.com.tr/master_360.m3u8",
                "https://tv-trtmuzik.medya.trt.com.tr/master_480.m3u8",
                "https://tv-trtmuzik.medya.trt.com.tr/master_720.m3u8",
                "https://tv-trtmuzik.medya.trt.com.tr/master_1080.m3u8",
                "https://tv-trtmuzik.medya.trt.com.tr/master_1440.m3u8"
            ),
            "https://raw.githubusercontent.com/kadireksi/kreaStream/refs/heads/logos/TV/TRT%20M%C3%BCzik.png"
        ),
        TrtChannel(
            "TRT Spor Yıldız",
            listOf(
                "https://trt.daioncdn.net/trtspor-yildiz/master_360p.m3u8",
                "https://trt.daioncdn.net/trtspor-yildiz/master_480p.m3u8",
                "https://trt.daioncdn.net/trtspor-yildiz/master_720p.m3u8",
                "https://trt.daioncdn.net/trtspor-yildiz/master_1080p.m3u8"
            ),
            "https://raw.githubusercontent.com/kadireksi/kreaStream/refs/heads/logos/TV/TRT%20Spor%20Y%C4%B1ld%C4%B1z.png"
        )
    )

    // Radio Channels
    private val radioChannels = listOf(
        TrtChannel(
            "TRT Radyo 1",
            listOf("https://trt.radyotvonline.net/trt_1.aac"),
            "https://raw.githubusercontent.com/kadireksi/kreaStream/refs/heads/logos/Radyo/trt-radyo-1.png"
        ),
        TrtChannel(
            "TRT FM",
            listOf("https://trt.radyotvonline.net/trt_fm.aac"),
            "https://raw.githubusercontent.com/kadireksi/kreaStream/refs/heads/logos/Radyo/trt-fm.png"
        ),
        TrtChannel(
            "TRT Radyo 3",
            listOf("https://rd-trtradyo3.medya.trt.com.tr/master_128.m3u8"),
            "https://raw.githubusercontent.com/kadireksi/kreaStream/refs/heads/logos/Radyo/trt-radyo-3.png"
        ),
        TrtChannel(
            "TRT Radyo Haber",
            listOf("https://radio-trtvotworld.medya.trt.com.tr/master_128.m3u8"),
            "https://raw.githubusercontent.com/kadireksi/kreaStream/refs/heads/logos/Radyo/trt-radyo-haber.png"
        ),
        TrtChannel(
            "TRT Nagme",
            listOf("https://rd-trtnagme.medya.trt.com.tr/master_128.m3u8"),
            "https://raw.githubusercontent.com/kadireksi/kreaStream/refs/heads/logos/Radyo/trt-nagme.png"
        ),
        TrtChannel(
            "TRT Radyo Kurdi",
            listOf("https://radio-trtarabi.medya.trt.com.tr/master_128.m3u8"),
            "https://raw.githubusercontent.com/kadireksi/kreaStream/refs/heads/logos/Radyo/trt-radyo-kurdi.png"
        ),
        TrtChannel(
            "TRT World Radyo",
            listOf("https://radio-trtworld.medya.trt.com.tr/master_128.m3u8"),
            "https://raw.githubusercontent.com/kadireksi/kreaStream/refs/heads/logos/Radyo/trt-world.png"
        ),
        TrtChannel(
            "TRT Antalya Radyosu",
            listOf("https://radio-trtantalya.medya.trt.com.tr/master_128.m3u8"),
            "https://raw.githubusercontent.com/kadireksi/kreaStream/refs/heads/logos/Radyo/trt-antalya.png"
        ),
        TrtChannel(
            "TRT Trabzon Radyosu",
            listOf("https://radio-trttrabzon.medya.trt.com.tr/master_128.m3u8"),
            "https://raw.githubusercontent.com/kadireksi/kreaStream/refs/heads/logos/Radyo/trt-trabzon.png"
        )
    )

    override val mainPage = mainPageOf(
        "live" to "Canlı Yayınlar"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val items = when (request.data) {
            "live" -> {
                listOf(
                    newMovieSearchResponse("TV Kanalları", "tv_section", TvType.Live) {
                        this.posterUrl = mainLogo
                    },
                    newMovieSearchResponse("Radyo Kanalları", "radio_section", TvType.Live) {
                        this.posterUrl = mainLogo
                    }
                )
            }
            else -> emptyList()
        }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun load(url: String): LoadResponse {
        return when (url) {
            "tv_section" -> loadTvSection()
            "radio_section" -> loadRadioSection()
            else -> loadChannel(url)
        }
    }

    private suspend fun loadTvSection(): LoadResponse {
        val episodes = tvChannels.map { channel ->
            // Use the highest quality stream as the main URL
            val mainStream = channel.streamUrls.firstOrNull() ?: ""
            newEpisode(mainStream) {
                this.name = channel.name
                this.posterUrl = channel.logoUrl
                this.description = "TRT TV Kanalı - ${channel.streamUrls.size} kalite seçeneği"
            }
        }

        return newTvSeriesLoadResponse("TV Kanalları", "tv_section", TvType.Live, episodes) {
            this.posterUrl = mainLogo
            this.plot = "TRT TV kanallarını canlı izleyin"
        }
    }

    private suspend fun loadRadioSection(): LoadResponse {
        val episodes = radioChannels.map { channel ->
            val mainStream = channel.streamUrls.firstOrNull() ?: ""
            newEpisode(mainStream) {
                this.name = channel.name
                this.posterUrl = channel.logoUrl
                this.description = "TRT Radyo Kanalı"
            }
        }

        return newTvSeriesLoadResponse("Radyo Kanalları", "radio_section", TvType.Live, episodes) {
            this.posterUrl = mainLogo
            this.plot = "TRT Radyo kanallarını canlı dinleyin"
        }
    }

    private suspend fun loadChannel(url: String): LoadResponse {
        // Find channel by any of its stream URLs
        val channel = (tvChannels + radioChannels).find { channel ->
            channel.streamUrls.contains(url)
        }

        return if (channel != null) {
            newMovieLoadResponse(channel.name, url, TvType.Live, url) {
                this.posterUrl = channel.logoUrl
                this.plot = "TRT ${channel.name} canlı yayın"
            }
        } else {
            // Fallback for direct channel loading
            newMovieLoadResponse("TRT Canlı", url, TvType.Live, url)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Find the channel to get all quality options
        val channel = (tvChannels + radioChannels).find { channel ->
            channel.streamUrls.contains(data)
        }

        val streams = channel?.streamUrls ?: listOf(data)

        streams.forEach { streamUrl ->
            val quality = when {
                streamUrl.contains("_1440") -> Qualities.P1440.value
                streamUrl.contains("_1080") -> Qualities.P1080.value
                streamUrl.contains("_720") -> Qualities.P720.value
                streamUrl.contains("_480") -> Qualities.P480.value
                streamUrl.contains("_360") -> Qualities.P360.value
                else -> Qualities.Unknown.value
            }

            val isM3u8 = streamUrl.contains(".m3u8")
            val isAudio = streamUrl.contains(".aac") || radioChannels.any { it.streamUrls.contains(streamUrl) }

            callback(
                newExtractorLink(
                    name = if (isAudio) "TRT Radyo" else "TRT Live",
                    source = name,
                    url = streamUrl
                ) {
                    this.referer = mainUrl
                    this.quality = quality
                    //this.isM3u8 = isM3u8
                    this.audioOnly = isAudio
                    this.headers = mapOf("User-Agent" to "Mozilla/5.0")
                }
            )
        }

        return true
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val allChannels = tvChannels + radioChannels
        return allChannels
            .filter { it.name.contains(query, ignoreCase = true) }
            .map { channel ->
                val mainStream = channel.streamUrls.firstOrNull() ?: ""
                newMovieSearchResponse(channel.name, mainStream, TvType.Live) {
                    this.posterUrl = channel.logoUrl
                }
            }
    }

    private data class TrtChannel(
        val name: String,
        val streamUrls: List<String>,
        val logoUrl: String
    )
}