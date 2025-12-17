package com.kreastream

import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.YoutubeService
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.kiosk.KioskInfo
import org.schabi.newpipe.extractor.stream.Description

class YouTubeParser {

    data class ParsedItem(
        val name: String,
        val url: String,
        val thumbnailUrl: String?
    )

    data class ParsedChannel(
        val name: String,
        val url: String,
        val thumbnailUrl: String?,
        val videos: List<ParsedItem>
    )

    data class ParsedPlaylist(
        val name: String,
        val url: String,
        val thumbnailUrl: String?,
        val videos: List<ParsedItem>
    )

    data class ParsedVideo(
        val name: String,
        val url: String,
        val thumbnailUrl: String?,
        val description: Description?
    )

    fun searchVideos(query: String) =
        search(query, InfoItem.InfoType.STREAM)

    fun searchChannels(query: String) =
        search(query, InfoItem.InfoType.CHANNEL)

    fun searchPlaylists(query: String) =
        search(query, InfoItem.InfoType.PLAYLIST)

    private fun search(query: String, type: InfoItem.InfoType): List<ParsedItem> {
        val youtubeService = ServiceList.YouTube as YoutubeService
        val handler = youtubeService.searchQHFactory.fromQuery(query)
        val info = SearchInfo.getInfo(youtubeService, handler)

        return info.relatedItems
            .filter { it.infoType == type }
            .map {
                ParsedItem(
                    name = it.name,
                    url = it.url,
                    thumbnailUrl = it.thumbnails.firstOrNull()?.url
                )
            }
    }

    fun getTrendingVideos(): List<ParsedItem> {
        val youtubeService = ServiceList.YouTube as YoutubeService
        val kiosk = KioskInfo.getInfo(youtubeService, "Trending")

        return kiosk.relatedItems
            .filterIsInstance<StreamInfoItem>()
            .map {
                ParsedItem(
                    name = it.name,
                    url = it.url,
                    thumbnailUrl = it.thumbnails.firstOrNull()?.url
                )
            }
    }

    fun getVideo(url: String): ParsedVideo {
        val info = StreamInfo.getInfo(ServiceList.YouTube, url)

        return ParsedVideo(
            name = info.name,
            url = info.url,
            thumbnailUrl = info.thumbnails.firstOrNull()?.url,
            description = info.description
        )
    }

    fun getChannel(url: String): ParsedChannel {
        val info = ChannelInfo.getInfo(ServiceList.YouTube, url)

        val videos = info.relatedItems
            .filterIsInstance<StreamInfoItem>()
            .map {
                ParsedItem(
                    name = it.name,
                    url = it.url,
                    thumbnailUrl = it.thumbnails.firstOrNull()?.url
                )
            }

        return ParsedChannel(
            name = info.name,
            url = info.url,
            thumbnailUrl = info.avatars.firstOrNull()?.url,
            videos = videos
        )
    }

    fun getPlaylist(url: String): ParsedPlaylist {
        val info = PlaylistInfo.getInfo(ServiceList.YouTube, url)

        val videos = info.relatedItems
            .filterIsInstance<StreamInfoItem>()
            .map {
                ParsedItem(
                    name = it.name,
                    url = it.url,
                    thumbnailUrl = it.thumbnails.firstOrNull()?.url
                )
            }

        return ParsedPlaylist(
            name = info.name,
            url = info.url,
            thumbnailUrl = info.thumbnails.firstOrNull()?.url,
            videos = videos
        )
    }
}