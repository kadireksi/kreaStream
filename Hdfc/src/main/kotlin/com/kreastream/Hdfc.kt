private suspend fun extractVideoFromClosePlayer(
    iframeDoc: org.jsoup.nodes.Document,
    iframeUrl: String,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    Log.d("HDFC", "Extracting video from Close player")
    
    // Method 1: Extract from obfuscated JavaScript (MAIN FIX)
    val scripts = iframeDoc.select("script")
    var foundVideo = false
    
    scripts.forEach { script ->
        val scriptContent = script.data()
        if (scriptContent.isNotEmpty() && scriptContent.contains("eval(function(p,a,c,k,e,d)")) {
            Log.d("HDFC", "Found obfuscated script, attempting to extract real video URL")
            
            // Extract the packed JavaScript code
            val packedScript = extractPackedScript(scriptContent)
            if (packedScript != null) {
                // Try to decode the packed script to find video URLs
                val decodedUrls = decodePackedScript(packedScript)
                decodedUrls.forEach { videoUrl ->
                    if (videoUrl.contains(".m3u8") || videoUrl.contains(".mp4") || videoUrl.contains("master.")) {
                        Log.d("HDFC", "Found real video URL from obfuscated script: $videoUrl")
                        callback.invoke(
                            newExtractorLink(
                                name = "Close Player (Real)",
                                url = videoUrl,
                                source = "Close"
                            )
                        )
                        foundVideo = true
                    }
                }
            }
        }
    }

    // Method 2: Look for base64 encoded URLs in the obfuscated script
    if (!foundVideo) {
        scripts.forEach { script ->
            val scriptContent = script.data()
            if (scriptContent.isNotEmpty()) {
                // Look for base64 encoded video URLs in the obfuscated script
                val base64Patterns = listOf(
                    Regex("""file_link\s*=\s*["']([A-Za-z0-9+/=]+)["']"""),
                    Regex("""["']([A-Za-z0-9+/=]{20,})["']""")
                )
                
                base64Patterns.forEach { pattern ->
                    val matches = pattern.findAll(scriptContent)
                    matches.forEach { match ->
                        val base64Data = match.groupValues[1]
                        try {
                            val decodedUrl = base64Decode(base64Data)
                            if (decodedUrl.isNotEmpty() && (decodedUrl.contains(".m3u8") || decodedUrl.contains(".mp4"))) {
                                Log.d("HDFC", "Found base64 decoded URL: $decodedUrl")
                                callback.invoke(
                                    newExtractorLink(
                                        name = "Close Player (Base64)",
                                        url = decodedUrl,
                                        source = "Close"
                                    )
                                )
                                foundVideo = true
                            }
                        } catch (e: Exception) {
                            Log.e("HDFC", "Error decoding base64: ${e.message}")
                        }
                    }
                }
            }
        }
    }

    // Method 3: Look for direct URLs in script content
    if (!foundVideo) {
        scripts.forEach { script ->
            val scriptContent = script.data()
            if (scriptContent.isNotEmpty()) {
                // Look for direct m3u8/master.txt URLs
                val urlPatterns = listOf(
                    Regex("""https?://[^\s"']+\.m3u8[^\s"']*"""),
                    Regex("""https?://[^\s"']+/master\.(m3u8|txt)[^\s"']*"""),
                    Regex("""https?://[^\s"']+\.mp4[^\s"']*""")
                )
                
                urlPatterns.forEach { pattern ->
                    val matches = pattern.findAll(scriptContent)
                    matches.forEach { match ->
                        val videoUrl = match.value
                        if (!videoUrl.contains("hls8.playmix.uno")) { // Skip the fake URL
                            Log.d("HDFC", "Found direct video URL in script: $videoUrl")
                            callback.invoke(
                                newExtractorLink(
                                    name = "Close Player (Direct)",
                                    url = videoUrl,
                                    source = "Close"
                                )
                            )
                            foundVideo = true
                        }
                    }
                }
            }
        }
    }

    // Method 4: Try to extract from the iframe URL pattern
    if (!foundVideo) {
        try {
            // Extract the video ID from iframe URL
            val videoId = iframeUrl.substringAfter("/embed/").substringBefore("/")
            if (videoId.isNotEmpty() && videoId != iframeUrl) {
                // Try common CDN patterns with the actual video ID
                val cdnPatterns = listOf(
                    "https://srv10.cdnimages1241.sbs/hls/",
                    "https://srv11.cdnimages1241.sbs/hls/", 
                    "https://srv12.cdnimages1241.sbs/hls/",
                    "https://cdn.hdfilmcehennemi.mobi/hls/"
                )
                
                cdnPatterns.forEach { cdnBase ->
                    val potentialUrl = "$cdnBase$videoId/master.m3u8"
                    Log.d("HDFC", "Trying CDN pattern: $potentialUrl")
                    // We'll let the extractor try this URL
                    callback.invoke(
                        newExtractorLink(
                            name = "Close Player (CDN Pattern)",
                            url = potentialUrl,
                            source = "Close"
                        )
                    )
                }
                foundVideo = true
            }
        } catch (e: Exception) {
            Log.e("HDFC", "Error extracting from iframe pattern: ${e.message}")
        }
    }

    // Method 5: Use loadExtractor as last resort
    if (!foundVideo) {
        Log.d("HDFC", "No direct video found, trying loadExtractor")
        loadExtractor(iframeUrl, "$mainUrl/", subtitleCallback) { link ->
            callback.invoke(link)
        }
    }
}

/**
 * Extract the packed JavaScript code from the eval function
 */
private fun extractPackedScript(scriptContent: String): String? {
    return try {
        // Look for the packed code inside eval(function(p,a,c,k,e,d){...})
        val packedRegex = Regex("""eval\(function\(p,a,c,k,e,d\)\{[^}]+\}\((.*?)\)\)""", RegexOption.DOT_MATCHES_ALL)
        val match = packedRegex.find(scriptContent)
        match?.groupValues?.get(1)
    } catch (e: Exception) {
        Log.e("HDFC", "Error extracting packed script: ${e.message}")
        null
    }
}

/**
 * Simple decoding of common packed JavaScript patterns to find URLs
 */
private fun decodePackedScript(packedScript: String): List<String> {
    val urls = mutableListOf<String>()
    
    try {
        // Method 1: Look for URL patterns in the packed script
        val urlPatterns = listOf(
            Regex("""https?://[^,"']+\.(m3u8|mp4|txt)"""),
            Regex("""(srv\d+\.cdnimages\d+\.sbs[^,"']*)"""),
            Regex("""(hls\d+\.playmix\.uno[^,"']*)"""),
            Regex("""(rapidrame\.com[^,"']*)""")
        )
        
        urlPatterns.forEach { pattern ->
            val matches = pattern.findAll(packedScript)
            matches.forEach { match ->
                var url = match.value
                // Ensure it's a full URL
                if (!url.startsWith("http")) {
                    url = "https://$url"
                }
                if (url.contains(".m3u8") || url.contains(".mp4") || url.contains("master.")) {
                    urls.add(url)
                }
            }
        }
        
        // Method 2: Look for base64 patterns in packed script
        val base64Regex = Regex("""["']([A-Za-z0-9+/=]{20,})["']""")
        val base64Matches = base64Regex.findAll(packedScript)
        
        base64Matches.forEach { match ->
            val base64Data = match.groupValues[1]
            try {
                val decoded = base64Decode(base64Data)
                if (decoded.contains("http") && (decoded.contains(".m3u8") || decoded.contains(".mp4"))) {
                    // Extract URL from decoded string
                    val urlMatch = Regex("""https?://[^\s"']+""").find(decoded)
                    urlMatch?.value?.let { url ->
                        urls.add(url)
                    }
                }
            } catch (e: Exception) {
                // Ignore base64 decode errors
            }
        }
        
    } catch (e: Exception) {
        Log.e("HDFC", "Error decoding packed script: ${e.message}")
    }
    
    return urls.distinct()
}

private fun base64Decode(encoded: String): String {
    return try {
        String(android.util.Base64.decode(encoded, android.util.Base64.DEFAULT))
    } catch (e: Exception) {
        Log.e("HDFC", "Base64 decode error: ${e.message}")
        ""
    }
}
