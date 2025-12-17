package com.kreastream

import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.kiosk.KioskInfo
import org.schabi.newpipe.extractor.stream.Description
import org.schabi.newpipe.extractor.services.youtube.YoutubeService
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeChannelLinkHandlerFactory
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubePlaylistLinkHandlerFactory
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory

class YouTubeParser {

    private val youtubeService: YoutubeService by lazy {
        NewPipe.getService(YoutubeService::class.java)
    }

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
        val searchInfo = SearchInfo.getInfo(youtubeService, youtubeService.searchQHFactory, query)

        return searchInfo.relatedItems
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
        val kioskInfo = KioskInfo.getInfo(youtubeService, youtubeService.kioskList.defaultKioskId)

        return kioskInfo.relatedItems
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
        val linkHandler = YoutubeStreamLinkHandlerFactory.getInstance().fromUrl(url)
        val info = StreamInfo.getInfo(youtubeService, linkHandler)

        return ParsedVideo(
            name = info.name,
            url = info.url,
            thumbnailUrl = info.thumbnails.firstOrNull()?.url,
            description = info.description
        )
    }

    fun getChannel(url: String): ParsedChannel {
        val linkHandler = YoutubeChannelLinkHandlerFactory.getInstance().fromUrl(url)
        val info = ChannelInfo.getInfo(youtubeService, linkHandler)

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
        val linkHandler = YoutubePlaylistLinkHandlerFactory.getInstance().fromUrl(url)
        val info = PlaylistInfo.getInfo(youtubeService, linkHandler)

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