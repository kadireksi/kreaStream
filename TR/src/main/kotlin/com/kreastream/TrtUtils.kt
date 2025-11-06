package com.kreastream

import com.lagradost.cloudstream3.app
import org.json.JSONObject

object TrtUtils {

    /** Mapping of live channels to their stream URLs and logos */
    val liveChannels = listOf(
        Triple("TRT 1", "https://tv-trt1.medya.trt.com.tr/master_480.m3u8", "https://upload.wikimedia.org/wikipedia/commons/7/70/Logo_of_TRT1.png"),
        Triple("TRT 2", "https://tv-trt2.medya.trt.com.tr/master_480.m3u8", "https://upload.wikimedia.org/wikipedia/commons/a/a8/Trt2_logo.png"),
        Triple("TRT Spor", "https://tv-trtspor1.medya.trt.com.tr/master_480.m3u8", "https://upload.wikimedia.org/wikipedia/commons/f/fb/TRT_Spor_logo.png"),
        Triple("TRT Belgesel", "https://tv-trtbelgesel.medya.trt.com.tr/master_480.m3u8", "https://upload.wikimedia.org/wikipedia/commons/b/b4/TRT_Belgesel_logo.png"),
        Triple("TRT Çocuk", "https://tv-trtcocuk.medya.trt.com.tr/master_480.m3u8", "https://upload.wikimedia.org/wikipedia/commons/d/d2/TRT_%C3%87ocuk_logo.png"),
        Triple("TRT Haber", "https://tv-trthaber.medya.trt.com.tr/master_480.m3u8", "https://upload.wikimedia.org/wikipedia/commons/8/8e/TRT_Haber_logo.png"),
        Triple("TRT Müzik", "https://tv-trtmuzik.medya.trt.com.tr/master_480.m3u8", "https://upload.wikimedia.org/wikipedia/commons/3/38/TRT_Muzik_logo.png"),
        Triple("TRT World", "https://tv-trtworld.medya.trt.com.tr/master_480.m3u8", "https://upload.wikimedia.org/wikipedia/commons/5/5b/TRT_World_logo.png")
    )
}
