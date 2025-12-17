package com.kreastream

import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.search.SearchResult
import org.schabi.newpipe.extractor.search.SearchExtractor
import org.schabi.newpipe.extractor.search.SearchInfoItem
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandlerFactory
import org.schabi.newpipe.extractor.kiosk.KioskInfo

class YouTubeParser {

    /* ---------------- DATA MODELS ---------------- */

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
        val description: StreamInfo.Description?
    )

    /* ---------------- SEARCH ---------------- */

    fun searchVideos(query: String): List<ParsedItem> =
        search(query, SearchExtractor.ITEM_STREAM)

    fun searchChannels(query: String): List<ParsedItem> =
        search(query, SearchExtractor.ITEM_CHANNEL)

    fun searchPlaylists(query: String): List<ParsedItem> =
        search(query, SearchExtractor.ITEM_PLAYLIST)

    private fun search(query: String, type: Int): List<ParsedItem> {
        val handler = SearchQueryHandlerFactory
            .getInstance()
            .fromQuery(query)

        val info = SearchInfo.getInfo(ServiceList.YouTube, handler)

        return info.relatedItems
            .filterIsInstance<SearchInfoItem>()
            .filter { it.type == type }
            .map {
                ParsedItem(
                    name = it.name,
                    url = it.url,
                    thumbnailUrl = it.thumbnails.firstOrNull()?.url
                )
            }
    }

    /* ---------------- TRENDING ---------------- */

    fun getTrendingVideos(): List<ParsedItem> {
        val kiosk = KioskInfo.getInfo(
            ServiceList.YouTube,
            "Trending"
        )

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

    /* ---------------- LOAD VIDEO ---------------- */

    fun getVideo(url: String): ParsedVideo {
        val info = StreamInfo.getInfo(ServiceList.YouTube, url)

        return ParsedVideo(
            name = info.name,
            url = info.url,
            thumbnailUrl = info.thumbnails.firstOrNull()?.url,
            description = info.description
        )
    }

    /* ---------------- LOAD CHANNEL ---------------- */

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

    /* ---------------- LOAD PLAYLIST ---------------- */

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
