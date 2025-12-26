package com.kreastream

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.schemaStripRegex
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory

open class YouTubeExtractor(private val hls: Boolean) : ExtractorApi() {
    override val mainUrl = "https://www.youtube.com"
    override val requiresReferer = false
    override val name = "YouTube"

    constructor() : this(true)

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        try {
            Log.d("YoutubeExtractor", "Starting extraction for URL: $url")
            
            val link =
                YoutubeStreamLinkHandlerFactory.getInstance().fromUrl(
                    url.replace(schemaStripRegex, "")
                )

            val extractor = object : YoutubeStreamExtractor(
                ServiceList.YouTube,
                link
            ) {}

            Log.d("YoutubeExtractor", "Fetching page...")
            extractor.fetchPage()
            Log.d("YoutubeExtractor", "Page fetched successfully")

            Log.d("YoutubeExtractor", "Is HLS enabled: $hls")
            
            // Try to get HLS URL first
            val hlsUrl = try {
                val url = extractor.hlsUrl
                Log.d("YoutubeExtractor", "HLS URL retrieved: ${if (url.isNullOrEmpty()) "EMPTY" else "OK"}")
                url
            } catch (e: Exception) {
                Log.d("YoutubeExtractor", "Failed to get HLS URL: ${e.message}")
                e.printStackTrace()
                null
            }
            
            Log.d("YoutubeExtractor", "HLS Url: $hlsUrl")
            
            // Try to get video streams if HLS is not available
            val videoStreams = try {
                val streams = extractor.videoStreams
                Log.d("YoutubeExtractor", "Video streams retrieved: ${streams.size} streams")
                streams
            } catch (e: Exception) {
                Log.d("YoutubeExtractor", "Failed to get video streams: ${e.message}")
                e.printStackTrace()
                emptyList()
            }
            
            // Try to get audio streams as fallback
            val audioStreams = try {
                val streams = extractor.audioStreams
                Log.d("YoutubeExtractor", "Audio streams retrieved: ${streams.size} streams")
                streams
            } catch (e: Exception) {
                Log.d("YoutubeExtractor", "Failed to get audio streams: ${e.message}")
                emptyList()
            }
            
            Log.d("YoutubeExtractor", "Video streams count: ${videoStreams.size}, Audio streams count: ${audioStreams.size}")
            
            // Log all available video streams for debugging
            videoStreams.forEachIndexed { idx, stream ->
                Log.d("YoutubeExtractor", "Video stream $idx: resolution=${stream.resolution}, itag=${stream.formatId}, fps=${stream.fps}")
            }
            
            // Log all available audio streams for debugging
            audioStreams.forEachIndexed { idx, stream ->
                Log.d("YoutubeExtractor", "Audio stream $idx: bitrate=${stream.averageBitrate}, format=${stream.format}")
            }
            
            // If we only got 360p or lower, try alternative extraction
            val hasHighQuality = videoStreams.any { stream ->
                val resolution = stream.resolution
                if (!resolution.isNullOrEmpty()) {
                    val height = resolution.substringAfter("x").toIntOrNull() ?: 0
                    height >= 720
                } else {
                    false
                }
            }
            
            if (!hasHighQuality && videoStreams.isNotEmpty()) {
                Log.w("YoutubeExtractor", "Only low quality streams found (${videoStreams.size} video streams). NewPipe might be restricted.")
            }
            
            if (!hlsUrl.isNullOrEmpty()) {
                Log.d("YoutubeExtractor", "Processing HLS URL")
                if (hls) {
                    callback.invoke(
                        newExtractorLink(
                            this.name,
                            this.name,
                            hlsUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = referer ?: ""
                            this.quality = Qualities.Unknown.value
                        }
                    )
                } else {
                    try {
                        val stream = M3u8Helper.generateM3u8(this.name, hlsUrl, "")
                        Log.d("YoutubeExtractor", "Generated ${stream.size} streams from M3u8")
                        stream.forEach {
                            callback.invoke(it)
                        }
                    } catch (e: Exception) {
                        Log.d("YoutubeExtractor", "M3u8 parsing failed: ${e.message}, falling back to HLS")
                        // Fallback to HLS if M3u8 parsing fails
                        callback.invoke(
                            newExtractorLink(
                                this.name,
                                this.name,
                                hlsUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = referer ?: ""
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    }
                }
            } else if (videoStreams.isNotEmpty()) {
                Log.d("YoutubeExtractor", "Using video streams instead of HLS")
                videoStreams.forEach { stream ->
                    try {
                        val streamUrl = stream.content
                        val resolution = stream.resolution
                        Log.d("YoutubeExtractor", "Stream resolution: $resolution")
                        
                        val quality = if (!resolution.isNullOrEmpty()) {
                            // Extract height from resolution string like "1920x1080"
                            resolution.substringAfter("x").toIntOrNull() ?: Qualities.Unknown.value
                        } else {
                            Qualities.Unknown.value
                        }
                        
                        Log.d("YoutubeExtractor", "Adding video stream: $streamUrl with quality: $quality, resolution: $resolution")
                        
                        callback.invoke(
                            newExtractorLink(
                                this.name,
                                this.name,
                                streamUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = referer ?: ""
                                this.quality = quality
                            }
                        )
                    } catch (e: Exception) {
                        Log.d("YoutubeExtractor", "Failed to process video stream: ${e.message}")
                        e.printStackTrace()
                    }
                }
                
                // Also add audio streams so player can combine them for better quality
                if (audioStreams.isNotEmpty()) {
                    Log.d("YoutubeExtractor", "Adding ${audioStreams.size} audio streams to complement video")
                    audioStreams.forEach { stream ->
                        try {
                            val streamUrl = stream.content
                            val quality = stream.averageBitrate ?: Qualities.Unknown.value
                            
                            Log.d("YoutubeExtractor", "Adding audio stream: $streamUrl with bitrate: $quality")
                            
                            callback.invoke(
                                newExtractorLink(
                                    this.name,
                                    this.name,
                                    streamUrl,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.referer = referer ?: ""
                                    this.quality = quality
                                }
                            )
                        } catch (e: Exception) {
                            Log.d("YoutubeExtractor", "Failed to process audio stream: ${e.message}")
                            e.printStackTrace()
                        }
                    }
                }
            } else if (audioStreams.isNotEmpty()) {
                Log.d("YoutubeExtractor", "Using audio streams as fallback")
                audioStreams.forEach { stream ->
                    try {
                        val streamUrl = stream.content
                        val quality = stream.averageBitrate ?: Qualities.Unknown.value
                        
                        Log.d("YoutubeExtractor", "Adding audio stream: $streamUrl with bitrate: $quality")
                        
                        callback.invoke(
                            newExtractorLink(
                                this.name,
                                this.name,
                                streamUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = referer ?: ""
                                this.quality = quality
                            }
                        )
                    } catch (e: Exception) {
                        Log.d("YoutubeExtractor", "Failed to process audio stream: ${e.message}")
                        e.printStackTrace()
                    }
                }
            } else {
                Log.w("YoutubeExtractor", "No HLS URL, video streams, or audio streams found for video")
            }
            
            // Get subtitles
            val subtitles = try {
                extractor.subtitlesDefault.filterNotNull()
            } catch (e: Exception) {
                Log.d("YoutubeExtractor", "Failed to get subtitles: ${e.message}")
                emptyList()
            }
            subtitles.mapNotNull {
                newSubtitleFile(
                    lang = it.languageTag ?: return@mapNotNull null,
                    url = it.content ?: return@mapNotNull null
                )
            }.forEach(subtitleCallback)
        } catch (e: Exception) {
            logError(e)
            Log.e("YoutubeExtractor", "Error extracting URL: ${e.message}")
            e.printStackTrace()
        }
    }
}