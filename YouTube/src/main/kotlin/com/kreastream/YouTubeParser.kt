package com.kreastream

import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.InfoItem.InfoType
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.kiosk.KioskInfo
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

class YouTubeParser {

    /* =======================
     * DATA MODELS
     * ======================= */

    data class ParsedVideo(
        val name: String,
        val url: String,
        val poster: String?,
        val duration: Int?,
        val description: String?,
        val uploader: String?,
        val views: Long?,
        val likes: Long?
    )

    data class ParsedEpisode(
        val name: String,
        val url: String,
        val poster: String?,
        val duration: Int?,
        val date: Long?
    )

    data class ParsedSeries(
        val name: String,
        val url: String,
        val poster: String?,
        val background: String?,
        val description: String?,
        val episodes: List<ParsedEpisode>
    )

    /* =======================
     * SEARCH
     * ======================= */

    fun search(query: String): List<InfoItem> {
        val handler = ServiceList.YouTube.searchQHFactory
            .fromQuery(query, listOf("videos"), null)

        val info = SearchInfo.getInfo(
            ServiceList.YouTube,
            SearchQueryHandler(handler)
        )

        val results = mutableListOf<InfoItem>()
        results.addAll(info.relatedItems)

        var next = info.nextPage
        repeat(3) {
            if (!info.hasNextPage()) return@repeat
            val more = SearchInfo.getMoreItems(ServiceList.YouTube, handler, next)
            results.addAll(more.items)
            next = more.nextPage
        }

        return results
    }

    /* =======================
     * VIDEO
     * ======================= */

    fun parseVideo(url: String): ParsedVideo {
        val info = StreamInfo.getInfo(url)

        return ParsedVideo(
            name = info.name,
            url = url,
            poster = info.thumbnails.lastOrNull()?.url,
            duration = (info.duration / 60).toInt(),
            description = info.description?.content,
            uploader = info.uploaderName,
            views = info.viewCount,
            likes = info.likeCount
        )
    }

    /* =======================
     * CHANNEL
     * ======================= */

    fun parseChannel(url: String): ParsedSeries {
        val channel = ChannelInfo.getInfo(url)

        val videoTab = channel.tabs
            .map { ChannelTabInfo.getInfo(ServiceList.YouTube, it) }
            .first { it.name == "videos" }

        val episodes = videoTab.relatedItems.mapNotNull {
            parseEpisode(it)
        }.reversed()

        return ParsedSeries(
            name = channel.name,
            url = url,
            poster = channel.avatars.lastOrNull()?.url,
            background = channel.banners.lastOrNull()?.url,
            description = channel.description,
            episodes = episodes
        )
    }

    /* =======================
     * PLAYLIST
     * ======================= */

    fun parsePlaylist(url: String): ParsedSeries {
        val info = PlaylistInfo.getInfo(url)

        val episodes = mutableListOf<ParsedEpisode>()
        episodes.addAll(info.relatedItems.mapNotNull { parseEpisode(it) })

        var next = info.nextPage
        repeat(10) {
            if (!info.hasNextPage()) return@repeat
            val more = PlaylistInfo.getMoreItems(ServiceList.YouTube, url, next)
            episodes.addAll(more.items.mapNotNull { parseEpisode(it) })
            next = more.nextPage
        }

        return ParsedSeries(
            name = info.name,
            url = url,
            poster = info.thumbnails.lastOrNull()?.url,
            background = info.banners.lastOrNull()?.url
                ?: info.thumbnails.lastOrNull()?.url,
            description = info.description?.content,
            episodes = episodes
        )
    }

    /* =======================
     * EPISODE
     * ======================= */

    private fun parseEpisode(item: StreamInfoItem): ParsedEpisode? {
        return ParsedEpisode(
            name = item.name ?: return null,
            url = item.url ?: return null,
            poster = item.thumbnails.lastOrNull()?.url,
            duration = (item.duration / 60).toInt(),
            date = item.uploadDate?.date()?.timeInMillis
        )
    }
}
