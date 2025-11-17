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

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        try {
            Log.d("YouTubeExtractor", "Processing YouTube URL: $url")
            
            val videoId = extractVideoId(url)
            if (videoId.isNullOrEmpty()) {
                Log.e("YouTubeExtractor", "Could not extract video ID from URL: $url")
                return
            }

            // Try to extract from embed page first
            if (extractFromEmbedPage(videoId, callback)) {
                Log.d("YouTubeExtractor", "Successfully extracted from embed page")
                return
            }

            // Fallback to watch page
            if (extractFromWatchPage(videoId, callback)) {
                Log.d("YouTubeExtractor", "Successfully extracted from watch page")
                return
            }

            // Final fallback to default extractor
            Log.d("YouTubeExtractor", "Falling back to default extractor")
            loadExtractor(url, referer ?: mainUrl, subtitleCallback, callback)
            
        } catch (e: Exception) {
            Log.e("YouTubeExtractor", "Error processing YouTube URL: ${e.message}")
            loadExtractor(url, referer ?: mainUrl, subtitleCallback, callback)
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

    private suspend fun extractFromEmbedPage(videoId: String, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val embedUrl = "https://www.youtube.com/embed/$videoId"
            val response = app.get(embedUrl)
            val html = response.text
            
            // Look for ytInitialPlayerResponse
            val jsonMatch = Regex("""ytInitialPlayerResponse\s*=\s*(\{.*?\});""").find(html)
            if (jsonMatch != null) {
                val jsonStr = jsonMatch.groupValues[1]
                return parseYouTubeJson(jsonStr, callback)
            }
            false
        } catch (e: Exception) {
            Log.e("YouTubeExtractor", "Error extracting from embed page: ${e.message}")
            false
        }
    }

    private suspend fun extractFromWatchPage(videoId: String, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val watchUrl = "https://www.youtube.com/watch?v=$videoId"
            val response = app.get(watchUrl)
            val html = response.text
            
            // Look for ytInitialPlayerResponse
            val jsonMatch = Regex("""ytInitialPlayerResponse\s*=\s*(\{.*?\});""").find(html)
            if (jsonMatch != null) {
                val jsonStr = jsonMatch.groupValues[1]
                return parseYouTubeJson(jsonStr, callback)
            }
            
            // Alternative: look for player response in ytInitialData
            val dataMatch = Regex("""ytInitialData\s*=\s*(\{.*?\});""").find(html)
            if (dataMatch != null) {
                val jsonStr = dataMatch.groupValues[1]
                return parseYouTubeInitialData(jsonStr, callback)
            }
            
            false
        } catch (e: Exception) {
            Log.e("YouTubeExtractor", "Error extracting from watch page: ${e.message}")
            false
        }
    }

    private fun parseYouTubeJson(jsonStr: String, callback: (ExtractorLink) -> Unit): Boolean {
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
                        
                        if (url.isNotEmpty() && (mimeType.contains("video/mp4") || mimeType.contains("video/webm"))) {
                            val quality = extractQualityFromBitrate(bitrate)
                            val extractorLink = newExtractorLink(
                                source = name,
                                name = "YouTube $quality",
                                url = url
                            ) {
                                this.referer = mainUrl
                                this.quality = getQualityValue(quality)
                            }
                            callback(extractorLink)
                        }
                    }
                }
                
                // Parse HLS streams
                val hlsUrl = streamingData.optString("hlsManifestUrl")
                if (hlsUrl.isNotEmpty()) {
                    // Use coroutine scope for M3u8Helper
                    kotlinx.coroutines.GlobalScope.launch {
                        M3u8Helper.generateM3u8(
                            name,
                            hlsUrl,
                            mainUrl
                        ).forEach(callback)
                    }
                }
                
                return true
            }
            false
        } catch (e: Exception) {
            Log.e("YouTubeExtractor", "Error parsing YouTube JSON: ${e.message}")
            false
        }
    }

    private fun parseYouTubeInitialData(jsonStr: String, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val json = JSONObject(jsonStr)
            // Navigate through the complex JSON structure to find video data
            val contents = json.optJSONObject("contents")
                ?.optJSONObject("twoColumnWatchNextResults")
                ?.optJSONObject("results")
                ?.optJSONObject("results")
                ?.optJSONObject("contents")
            
            if (contents != null) {
                // Try to find player response in various locations
                val playerResponse = contents.optJSONObject("playerResponse") 
                    ?: contents.optJSONArray("contents")?.optJSONObject(0)?.optJSONObject("playerResponse")
                
                if (playerResponse != null) {
                    return parseYouTubeJson(playerResponse.toString(), callback)
                }
            }
            false
        } catch (e: Exception) {
            Log.e("YouTubeExtractor", "Error parsing YouTube initial data: ${e.message}")
            false
        }
    }

    private fun extractQualityFromBitrate(bitrate: Long): String {
        return when {
            bitrate > 8000000 -> "4K"
            bitrate > 5000000 -> "1080p"
            bitrate > 2500000 -> "720p" 
            bitrate > 1000000 -> "480p"
            bitrate > 500000 -> "360p"
            else -> "240p"
        }
    }

    private fun getQualityValue(quality: String): Int {
        return when (quality.lowercase()) {
            "4k", "2160p" -> 2160
            "1080p" -> 1080
            "720p" -> 720
            "480p" -> 480
            "360p" -> 360
            "240p" -> 240
            else -> Qualities.Unknown.value
        }
    }
}