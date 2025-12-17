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
import java.util.Date

class YouTubeParser {

    /* =====================
     * DATA MODELS
     * ===================== */

    data class ParsedSearchItem(
        val name: String,
        val url: String,
        val poster: String?,
        val type: InfoType
    )

    data class ParsedEpisode(
        val name: String,
        val url: String,
        val poster: String?,
        val durationMin: Int?,
        val uploadDate: Date?
    )

    data class ParsedVideo(
        val name: String,
        val url: String,
        val poster: String?,
        val description: String?,
        val durationMin: Int,
        val uploader: String,
        val views: Long,
        val likes: Long
    )

    data class ParsedSeries(
        val name: String,
        val url: String,
        val poster: String?,
        val banner: String?,
        val description: String?,
        val episodes: List<ParsedEpisode>
    )

    /* =====================
     * SEARCH
     * ===================== */

    fun search(query: String): List<ParsedSearchItem> {
        val handler = ServiceList.YouTube.searchQHFactory
            .fromQuery(query, listOf("videos"), null)

        val info = SearchInfo.getInfo(
            ServiceList.YouTube,
            SearchQueryHandler(handler)
        )

        val items = mutableListOf<InfoItem>()
        items.addAll(info.relatedItems)

        var next = info.nextPage
        repeat(3) {
            if (!info.hasNextPage()) return@repeat
            val more = SearchInfo.getMoreItems(ServiceList.YouTube, handler, next)
            items.addAll(more.items)
            next = more.nextPage
        }

        return items.mapNotNull {
            val name = it.name ?: return@mapNotNull null
            val url = it.url ?: return@mapNotNull null

            ParsedSearchItem(
                name = name,
                url = url,
                poster = it.thumbnails.lastOrNull()?.url,
                type = it.infoType
            )
        }
    }

    /* =====================
     * VIDEO
     * ===================== */

    fun loadVideo(url: String): ParsedVideo {
        val info = StreamInfo.getInfo(url)

        return ParsedVideo(
            name = info.name,
            url = url,
            poster = info.thumbnails.lastOrNull()?.url,
            description = info.description?.content,
            durationMin = (info.duration / 60).toInt(),
            uploader = info.uploaderName,
            views = info.viewCount,
            likes = info.likeCount
        )
    }

    /* =====================
     * CHANNEL
     * ===================== */

    fun loadChannel(url: String): ParsedSeries {
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
            banner = channel.banners.lastOrNull()?.url,
            description = channel.description,
            episodes = episodes
        )
    }

    /* =====================
     * PLAYLIST
     * ===================== */

    fun loadPlaylist(url: String): ParsedSeries {
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
            banner = info.banners.lastOrNull()?.url
                ?: info.thumbnails.lastOrNull()?.url,
            description = info.description?.content,
            episodes = episodes
        )
    }

    /* =====================
     * HELPERS
     * ===================== */

    private fun parseEpisode(item: StreamInfoItem): ParsedEpisode? {
        val name = item.name ?: return null
        val url = item.url ?: return null

        val date = item.uploadDate?.let {
            Date(it.date().timeInMillis)
        }

        return ParsedEpisode(
            name = name,
            url = url,
            poster = item.thumbnails.lastOrNull()?.url,
            durationMin = (item.duration / 60).toInt(),
            uploadDate = date
        )
    }
}
