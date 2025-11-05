package com.kreastream

import com.lagradost.cloudstream3.app
import org.json.JSONObject

object TrtUtils {
    suspend fun getNowPlaying(channelName: String): String? {
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
        val code = map[channelName] ?: return null
        return try {
            val response = app.get("https://www.trt.net.tr/_Channel/Service/GetScheduleByChannel?channel=$code").text
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
