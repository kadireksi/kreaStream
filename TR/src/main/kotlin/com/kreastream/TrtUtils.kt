package com.kreastream

object TrtUtils {
    val liveChannels = listOf(
        Triple("TRT 1", "https://tv-trt1.medya.trt.com.tr/master_720.m3u8", "https://upload.wikimedia.org/wikipedia/commons/7/70/Logo_of_TRT1.png"),
        Triple("TRT 2", "https://tv-trt2.medya.trt.com.tr/master_720.m3u8", "https://upload.wikimedia.org/wikipedia/commons/7/70/Logo_of_TRT2.png"),
        Triple("TRT Spor", "https://tv-trtspor1.medya.trt.com.tr/master_720.m3u8", "https://upload.wikimedia.org/wikipedia/commons/a/a4/TRT_Spor_logo.png"),
        Triple("TRT Belgesel", "https://tv-trtbelgesel.medya.trt.com.tr/master_720.m3u8", "https://upload.wikimedia.org/wikipedia/commons/3/37/TRT_Belgesel_logo.png"),
        Triple("TRT Çocuk", "https://tv-trtcocuk.medya.trt.com.tr/master_720.m3u8", "https://upload.wikimedia.org/wikipedia/commons/8/8f/TRT_%C3%87ocuk_logo.png"),
        Triple("TRT Arabi", "https://tv-trtarabi.medya.trt.com.tr/master_720.m3u8", "https://upload.wikimedia.org/wikipedia/commons/8/8c/TRT_Arabi_logo.png"),
        Triple("TRT Avaz", "https://tv-trtavaz.medya.trt.com.tr/master_720.m3u8", "https://upload.wikimedia.org/wikipedia/commons/0/0c/TRT_Avaz_logo.png"),
        Triple("TRT Türk", "https://tv-trtturk.medya.trt.com.tr/master_720.m3u8", "https://upload.wikimedia.org/wikipedia/commons/3/3c/TRT_T%C3%BCrk_logo.png"),
        Triple("TRT World", "https://tv-trtworld.medya.trt.com.tr/master_720.m3u8", "https://upload.wikimedia.org/wikipedia/commons/6/6a/TRT_World_logo.png")
    )

    fun getNowPlaying(channelName: String): String? {
        return null // optional future EPG integration
    }
}