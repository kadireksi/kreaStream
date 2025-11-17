package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.extractors.*
import org.jsoup.Jsoup
import android.util.Log
import org.json.JSONObject
import org.json.JSONArray
import java.net.URLDecoder

class Yt : ExtractorApi() {
    override val name = "YouTubeExtractor"
    override val mainUrl = "https://www.youtube.com"
    override val requiresReferer = false

    private var currentCallback: ((ExtractorLink) -> Unit)? = null

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        try {
            Log.d("YouTubeExtractor", "Processing YouTube URL: $url")
            currentCallback = callback // Store the callback
            
            val videoId = extractVideoId(url)
            if (videoId.isNullOrEmpty()) {
                Log.e("YouTubeExtractor", "Could not extract video ID from URL: $url")
                return false
            }

            // Pass callback to getVideoInfo
            val videoInfo = getVideoInfo(videoId, callback) // Add callback parameter
            if (videoInfo) {
                Log.d("YouTubeExtractor", "Successfully extracted YouTube video info")
                return true
            }

            // Fallback to existing YouTube extractor
            Log.d("YouTubeExtractor", "Falling back to default YouTube extractor")
            return loadExtractor(url, referer ?: mainUrl, subtitleCallback, callback)
            
        } catch (e: Exception) {
            Log.e("YouTubeExtractor", "Error processing YouTube URL: ${e.message}")
            // Fallback to existing extractor
            return loadExtractor(url, referer ?: mainUrl, subtitleCallback, callback)
        }
    }

    private fun extractVideoId(url: String): String? {
        val patterns = listOf(
            Regex("""(?:youtube\.com/watch\?v=|youtu\.be/|youtube\.com/embed/)([^&?#/]+)"""),
            Regex("""youtube\.com/watch\?.*v=([^&?#/]+)"""),
            Regex("""youtu\.be/([^&?#/]+)"""),
            Regex("""youtube\.com/embed/([^&?#/]+)""")
        )
        
        for (pattern in patterns) {
            val match = pattern.find(url)
            if (match != null) {
                return match.groupValues[1]
            }
        }
        return null
    }

    private suspend fun getVideoInfo(videoId: String, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            // Try multiple methods to get video info
            val methods = listOf(
                { getVideoInfoMethod1(videoId, callback) },
                { getVideoInfoMethod2(videoId, callback) },
                { getVideoInfoMethod3(videoId, callback) }
            )
            
            for (method in methods) {
                try {
                    if (method()) return true
                } catch (e: Exception) {
                    Log.d("YouTubeExtractor", "Method failed: ${e.message}")
                }
            }
            false
        } catch (e: Exception) {
            Log.e("YouTubeExtractor", "Error getting video info: ${e.message}")
            false
        }
    }

    private suspend fun getVideoInfoMethod1(videoId: String, callback: (ExtractorLink) -> Unit): Boolean {
        // Method 1: Direct embed page
        val embedUrl = "https://www.youtube.com/embed/$videoId"
        val response = app.get(embedUrl)
        val document = response.document
        
        // Extract from ytInitialPlayerResponse
        val scripts = document.select("script")
        for (script in scripts) {
            val html = script.html()
            if (html.contains("ytInitialPlayerResponse")) {
                val jsonMatch = Regex("""ytInitialPlayerResponse\s*=\s*(\{.*?\});""").find(html)
                if (jsonMatch != null) {
                    val jsonStr = jsonMatch.groupValues[1]
                    return parseYouTubeJson(jsonStr, videoId)
                }
            }
        }
        return false
    }

    private suspend fun getVideoInfoMethod2(videoId: String, callback: (ExtractorLink) -> Unit): Boolean {
        // Method 2: Watch page
        val watchUrl = "https://www.youtube.com/watch?v=$videoId"
        val response = app.get(watchUrl)
        val html = response.text
        
        // Look for ytInitialPlayerResponse in the HTML
        val jsonMatch = Regex("""ytInitialPlayerResponse\s*=\s*(\{.*?\});""").find(html)
        if (jsonMatch != null) {
            val jsonStr = jsonMatch.groupValues[1]
            return parseYouTubeJson(jsonStr, videoId)
        }
        return false
    }

    private suspend fun getVideoInfoMethod3(videoId: String, callback: (ExtractorLink) -> Unit): Boolean {
        // Method 3: Using get_video_info endpoint (legacy but reliable)
        val infoUrl = "https://www.youtube.com/get_video_info?video_id=$videoId&el=detailpage&ps=default&eurl=&gl=US&hl=en"
        val response = app.get(infoUrl)
        val responseText = response.text
        
        if (responseText.contains("url_encoded_fmt_stream_map")) {
            return parseGetVideoInfo(responseText, videoId)
        }
        return false
    }

    private fun parseYouTubeJson(jsonStr: String, videoId: String): Boolean {
        return try {
            val json = JSONObject(jsonStr)
            val streamingData = json.optJSONObject("streamingData")
            
            if (streamingData != null) {
                // Parse adaptive formats (highest quality)
                val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats")
                if (adaptiveFormats != null) {
                    for (i in 0 until adaptiveFormats.length()) {
                        val format = adaptiveFormats.getJSONObject(i)
                        val url = format.optString("url")
                        val mimeType = format.optString("mimeType")
                        val bitrate = format.optLong("bitrate", 0)
                        
                        if (url.isNotEmpty() && mimeType.contains("video/mp4")) {
                            val quality = extractQualityFromBitrate(bitrate, mimeType)
                            val extractorLink = newExtractorLink(
                                source = name,
                                name = "YouTube $quality",
                                url = url,
                                referer = "https://www.youtube.com",
                                quality = quality
                            )
                            callback(extractorLink)
                        }
                    }
                }
                
                // Parse formats (standard quality)
                val formats = streamingData.optJSONArray("formats")
                if (formats != null) {
                    for (i in 0 until formats.length()) {
                        val format = formats.getJSONObject(i)
                        val url = format.optString("url")
                        val mimeType = format.optString("mimeType")
                        val bitrate = format.optLong("bitrate", 0)
                        
                        if (url.isNotEmpty() && mimeType.contains("video/mp4")) {
                            val quality = extractQualityFromBitrate(bitrate, mimeType)
                            val extractorLink = newExtractorLink(
                                source = name,
                                name = "YouTube $quality",
                                url = url,
                                referer = "https://www.youtube.com",
                                quality = quality
                            )
                            callback(extractorLink)
                        }
                    }
                }
                
                // Parse HLS streams
                val hlsUrl = streamingData.optString("hlsManifestUrl")
                if (hlsUrl.isNotEmpty()) {
                    M3u8Helper.generateM3u8(
                        name,
                        hlsUrl,
                        "https://www.youtube.com"
                    ).forEach(callback)
                }
                
                return true
            }
            false
        } catch (e: Exception) {
            Log.e("YouTubeExtractor", "Error parsing YouTube JSON: ${e.message}")
            false
        }
    }

    private fun parseGetVideoInfo(responseText: String, videoId: String): Boolean {
        return try {
            val params = responseText.split("&")
            var urlEncodedMap = ""
            
            for (param in params) {
                if (param.startsWith("url_encoded_fmt_stream_map=")) {
                    urlEncodedMap = param.substringAfter("=")
                    break
                }
            }
            
            if (urlEncodedMap.isNotEmpty()) {
                val decodedMap = java.net.URLDecoder.decode(urlEncodedMap, "UTF-8")
                val streams = decodedMap.split(",")
                
                for (stream in streams) {
                    val streamParams = stream.split("&")
                    var url = ""
                    var quality = "360p"
                    var type = ""
                    
                    for (param in streamParams) {
                        when {
                            param.startsWith("url=") -> url = java.net.URLDecoder.decode(param.substringAfter("="), "UTF-8")
                            param.startsWith("quality=") -> quality = param.substringAfter("=")
                            param.startsWith("type=") -> type = java.net.URLDecoder.decode(param.substringAfter("="), "UTF-8")
                        }
                    }
                    
                    if (url.isNotEmpty() && type.contains("video/mp4")) {
                        val extractorLink = newExtractorLink(
                            source = name,
                            name = "YouTube $quality",
                            url = url,
                            referer = "https://www.youtube.com",
                            quality = getQualityNumber(quality)
                        )
                        callback(extractorLink)
                    }
                }
                return true
            }
            false
        } catch (e: Exception) {
            Log.e("YouTubeExtractor", "Error parsing get_video_info: ${e.message}")
            false
        }
    }

    private fun extractQualityFromBitrate(bitrate: Long, mimeType: String): Int {
        return when {
            bitrate > 8000000 -> Qualities.QuadHDR.value // 4K
            bitrate > 5000000 -> Qualities.FullHDR.value // 1080p
            bitrate > 2500000 -> Qualities.1080p.value
            bitrate > 1500000 -> Qualities.720p.value
            bitrate > 800000 -> Qualities.480p.value
            bitrate > 500000 -> Qualities.360p.value
            else -> Qualities.360p.value
        }
    }

    private fun getQualityNumber(quality: String): Int {
        return when (quality.uppercase()) {
            "HD720", "720P" -> Qualities.720p.value
            "HD1080", "1080P" -> Qualities.1080p.value
            "HD1440", "1440P" -> Qualities.1440p.value
            "HD2160", "2160P", "4K" -> Qualities.QuadHDR.value
            "HD480", "480P" -> Qualities.480p.value
            else -> Qualities.360p.value
        }
    }
}