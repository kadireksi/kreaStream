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
            val link =
                YoutubeStreamLinkHandlerFactory.getInstance().fromUrl(
                    url.replace(schemaStripRegex, "")
                )

            val extractor = object : YoutubeStreamExtractor(
                ServiceList.YouTube,
                link
            ) {}

            extractor.fetchPage()

            Log.d("YoutubeExtractor", "Is HLS enabled: $hls")
            
            // Try to get HLS URL first
            val hlsUrl = try {
                extractor.hlsUrl
            } catch (e: Exception) {
                Log.d("YoutubeExtractor", "Failed to get HLS URL: ${e.message}")
                null
            }
            
            Log.d("YoutubeExtractor", "HLS Url: $hlsUrl")
            
            if (!hlsUrl.isNullOrEmpty()) {
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
            } else {
                Log.w("YoutubeExtractor", "No HLS URL found for video")
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
        }
    }
}