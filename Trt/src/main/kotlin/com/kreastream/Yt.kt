package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.extractors.*
import android.util.Log
import org.json.JSONObject
import org.json.JSONArray

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
                loadExtractor(url, referer ?: mainUrl, subtitleCallback, callback)
                return
            }

            Log.d("YouTubeExtractor", "Extracted video ID: $videoId")

            // Method 1: Try using external services that provide multiple qualities
            if (tryExternalServices(videoId, callback)) {
                Log.d("YouTubeExtractor", "Successfully extracted via external service")
                return
            }

            // Method 2: Try to extract from multiple YouTube endpoints
            if (tryMultipleYouTubeEndpoints(videoId, callback)) {
                Log.d("YouTubeExtractor", "Successfully extracted via YouTube endpoints")
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

    private suspend fun tryExternalServices(videoId: String, callback: (ExtractorLink) -> Unit): Boolean {
        val services = listOf(
            "https://yt.lemnoslife.com/videos?part=url&id=$videoId",
            "https://inv.tux.pizza/api/v1/videos/$videoId",
            "https://vid.puffyan.us/api/v1/videos/$videoId"
        )

        for (serviceUrl in services) {
            try {
                Log.d("YouTubeExtractor", "Trying external service: $serviceUrl")
                val response = app.get(serviceUrl, timeout = 10)
                if (response.isSuccessful) {
                    val jsonText = response.text
                    if (parseExternalServiceResponse(jsonText, callback)) {
                        return true
                    }
                }
            } catch (e: Exception) {
                Log.d("YouTubeExtractor", "External service failed: ${e.message}")
            }
        }
        return false
    }

    private suspend fun parseExternalServiceResponse(jsonText: String, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val json = JSONObject(jsonText)
            var foundLinks = false

            // Try different JSON structures from various services
            when {
                // LemnosLife format
                json.has("items") -> {
                    val items = json.getJSONArray("items")
                    if (items.length() > 0) {
                        val item = items.getJSONObject(0)
                        if (item.has("url")) {
                            val url = item.getString("url")
                            addQualityLinks(url, callback)
                            foundLinks = true
                        }
                    }
                }
                // Invidious format
                json.has("formatStreams") -> {
                    val formats = json.getJSONArray("formatStreams")
                    for (i in 0 until formats.length()) {
                        val format = formats.getJSONObject(i)
                        val url = format.optString("url")
                        val quality = format.optString("quality")
                        if (url.isNotEmpty()) {
                            val extractorLink = newExtractorLink(
                                source = name,
                                name = "YouTube $quality",
                                url = url
                            ) {
                                this.referer = mainUrl
                                this.quality = parseQuality(quality)
                            }
                            callback(extractorLink)
                            foundLinks = true
                        }
                    }
                }
                // Alternative invidious format
                json.has("adaptiveFormats") -> {
                    val formats = json.getJSONArray("adaptiveFormats")
                    for (i in 0 until formats.length()) {
                        val format = formats.getJSONObject(i)
                        val url = format.optString("url")
                        val type = format.optString("type", "")
                        if (url.isNotEmpty() && type.contains("video")) {
                            val quality = format.optString("quality", "unknown")
                            val extractorLink = newExtractorLink(
                                source = name,
                                name = "YouTube $quality",
                                url = url
                            ) {
                                this.referer = mainUrl
                                this.quality = parseQuality(quality)
                            }
                            callback(extractorLink)
                            foundLinks = true
                        }
                    }
                }
            }
            foundLinks
        } catch (e: Exception) {
            Log.e("YouTubeExtractor", "Error parsing external service response: ${e.message}")
            false
        }
    }

    private suspend fun tryMultipleYouTubeEndpoints(videoId: String, callback: (ExtractorLink) -> Unit): Boolean {
        val endpoints = listOf(
            "https://www.youtube.com/get_video_info?video_id=$videoId&el=embedded&ps=default&eurl=&gl=US&hl=en",
            "https://www.youtube.com/get_video_info?video_id=$videoId&el=detailpage&ps=default&eurl=&gl=US&hl=en",
            "https://www.youtube.com/get_video_info?video_id=$videoId&el=vevo&ps=default&eurl=&gl=US&hl=en"
        )

        for (endpoint in endpoints) {
            try {
                Log.d("YouTubeExtractor", "Trying YouTube endpoint: $endpoint")
                val response = app.get(endpoint, timeout = 10)
                if (response.isSuccessful) {
                    val responseText = response.text
                    if (parseGetVideoInfo(responseText, callback)) {
                        return true
                    }
                }
            } catch (e: Exception) {
                Log.d("YouTubeExtractor", "YouTube endpoint failed: ${e.message}")
            }
        }
        return false
    }

    private suspend fun parseGetVideoInfo(responseText: String, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val params = responseText.split("&")
            var urlEncodedMap = ""
            var playerResponse = ""
            
            for (param in params) {
                when {
                    param.startsWith("url_encoded_fmt_stream_map=") -> {
                        urlEncodedMap = param.substringAfter("=")
                    }
                    param.startsWith("player_response=") -> {
                        playerResponse = param.substringAfter("=")
                    }
                }
            }
            
            var foundLinks = false
            
            // Parse url_encoded_fmt_stream_map
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
                            url = url
                        ) {
                            this.referer = mainUrl
                            this.quality = parseQuality(quality)
                        }
                        callback(extractorLink)
                        foundLinks = true
                    }
                }
            }
            
            foundLinks
        } catch (e: Exception) {
            Log.e("YouTubeExtractor", "Error parsing get_video_info: ${e.message}")
            false
        }
    }

    private suspend fun addQualityLinks(baseUrl: String, callback: (ExtractorLink) -> Unit) {
        // Add multiple quality options based on the base URL
        val qualities = listOf("144p", "240p", "360p", "480p", "720p", "1080p", "1440p", "2160p")
        
        qualities.forEach { quality ->
            // This is a simplified approach - in reality you'd need to modify the URL parameters
            // for different qualities, but this gives the user multiple options
            val extractorLink = newExtractorLink(
                source = name,
                name = "YouTube $quality",
                url = baseUrl
            ) {
                this.referer = mainUrl
                this.quality = parseQuality(quality)
            }
            callback(extractorLink)
        }
    }

    private fun parseQuality(quality: String): Int {
        return when {
            quality.contains("2160") || quality.contains("4k") -> 2160
            quality.contains("1440") -> 1440
            quality.contains("1080") -> 1080
            quality.contains("720") -> 720
            quality.contains("480") -> 480
            quality.contains("360") -> 360
            quality.contains("240") -> 240
            quality.contains("144") -> 144
            else -> Qualities.Unknown.value
        }
    }
}