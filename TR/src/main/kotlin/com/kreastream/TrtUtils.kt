package com.kreastream

import com.lagradost.cloudstream3.app
import org.json.JSONObject

object TrtUtils {

    /** Static list of TRT live channels */
    fun getLiveChannels(): List<Triple<String, String, String>> {
        return listOf(
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
    }

    /** Fetch the currently airing program for a given channel */
    suspend fun getNowPlaying(channel: String): String? {
        val map = mapOf(
            "TRT 1" to "trt1",
            "TRT 2" to "trt2",
            "TRT Spor" to "trtspor",
            "TRT Spor 2" to "trtspor2",
            "TRT Haber" to "trthaber",
            "TRT Belgesel" to "trtbelgesel",
            "TRT Çocuk" to "trtcocuk",
            "TRT Müzik" to "trtmuzik",
            "TRT World" to "trtworld"
        )

        val code = map[channel] ?: return null
        val url = "https://www.trt.net.tr/_Channel/Service/GetScheduleByChannel?channel=$code"

        return try {
            val response = app.get(url).text
            val json = JSONObject(response)
            val arr = json.optJSONArray("ScheduleList") ?: return null
            (0 until arr.length())
                .mapNotNull { arr.optJSONObject(it) }
                .firstOrNull { it.optBoolean("IsNow") }
                ?.optString("ProgramName")
        } catch (_: Exception) {
            null
        }
    }
}
