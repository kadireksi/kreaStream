package com.kreastream

import com.lagradost.cloudstream3.*
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

class YouTubeParser(private val apiName: String) {

    /* =========================
     * SEARCH
     * ========================= */

    fun search(
        query: String,
        contentFilter: String = "videos"
    ): List<SearchResponse> {

        val handler = ServiceList.YouTube.searchQHFactory
            .fromQuery(query, listOf(contentFilter), null)

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
            when (it.infoType) {
                InfoType.STREAM -> movieSearch(it)
                InfoType.PLAYLIST, InfoType.CHANNEL -> seriesSearch(it)
                else -> null
            }
        }
    }

    /* =========================
     * TRENDING
     * ========================= */

    fun getTrendingVideoUrls(page: Int): HomePageList? {
        val kiosk = ServiceList.YouTube.kioskList.defaultKioskExtractor
        val info = KioskInfo.getInfo(ServiceList.YouTube, kiosk.url)

        val videos = if (page == 1) {
            info.relatedItems
        } else {
            if (!info.hasNextPage()) return null
            val more = KioskInfo.getMoreItems(
                ServiceList.YouTube,
                kiosk.url,
                info.nextPage
            )
            more.items
        }

        val list = videos
            .filterIsInstance<StreamInfoItem>()
            .filterNot { it.isShortFormContent }
            .mapNotNull { movieSearch(it) }

        return HomePageList(
            name = "Trending",
            list = list,
            isHorizontalImages = true
        )
    }

    /* =========================
     * PLAYLIST → SEARCH
     * ========================= */

    fun playlistToSearchResponseList(
        url: String,
        page: Int
    ): HomePageList? {

        val info = PlaylistInfo.getInfo(url)

        val videos = if (page == 1) {
            info.relatedItems
        } else {
            if (!info.hasNextPage()) return null
            val more = PlaylistInfo.getMoreItems(
                ServiceList.YouTube,
                url,
                info.nextPage
            )
            more.items
        }

        val list = videos.mapNotNull { movieSearch(it) }

        return HomePageList(
            name = "${info.uploaderName}: ${info.name}",
            list = list,
            isHorizontalImages = true
        )
    }

    /* =========================
     * CHANNEL → SEARCH
     * ========================= */

    fun channelToSearchResponseList(
        url: String,
        page: Int
    ): HomePageList? {

        val channel = ChannelInfo.getInfo(url)

        val tabHandler = channel.tabs.firstOrNull {
            it.url.endsWith("/videos")
        } ?: return null

        val tab = ChannelTabInfo.getInfo(ServiceList.YouTube, tabHandler)

        val videos = if (page == 1) {
            tab.relatedItems
        } else {
            if (!tab.hasNextPage()) return null
            val more = ChannelTabInfo.getMoreItems(
                ServiceList.YouTube,
                tabHandler,
                tab.nextPage
            )
            more.items
        }

        val list = videos.mapNotNull { movieSearch(it) }

        return HomePageList(
            name = channel.name,
            list = list,
            isHorizontalImages = true
        )
    }

    /* =========================
     * LOAD – VIDEO
     * ========================= */

    suspend fun videoToLoadResponse(url: String): LoadResponse {
        val info = StreamInfo.getInfo(url)

        return newMovieLoadResponse(
            name = info.name,
            url = url,
            type = TvType.Others,
            dataUrl = url
        ) {
            posterUrl = info.thumbnails.lastOrNull()?.url
            plot = info.description?.content
            duration = (info.duration / 60).toInt()
            tags = listOf(
                info.uploaderName,
                "Views: ${info.viewCount}",
                "Likes: ${info.likeCount}"
            )
        }
    }

    /* =========================
     * LOAD – CHANNEL
     * ========================= */

    suspend fun channelToLoadResponse(url: String): LoadResponse {
        val channel = ChannelInfo.getInfo(url)

        val tab = channel.tabs
            .map { ChannelTabInfo.getInfo(ServiceList.YouTube, it) }
            .first { it.name == "videos" }

        val episodes = tab.relatedItems.mapNotNull {
            newEpisode(it)
        }.reversed()

        return newTvSeriesLoadResponse(
            name = channel.name,
            url = url,
            type = TvType.Others,
            episodes = episodes
        ) {
            posterUrl = channel.avatars.lastOrNull()?.url
            backgroundPosterUrl = channel.banners.lastOrNull()?.url
            plot = channel.description
        }
    }

    /* =========================
     * LOAD – PLAYLIST
     * ========================= */

    suspend fun playlistToLoadResponse(url: String): LoadResponse {
        val info = PlaylistInfo.getInfo(url)

        val episodes = info.relatedItems.mapNotNull {
            newEpisode(it)
        }

        return newTvSeriesLoadResponse(
            name = info.name,
            url = url,
            type = TvType.Others,
            episodes = episodes
        ) {
            posterUrl = info.thumbnails.lastOrNull()?.url
            backgroundPosterUrl =
                info.banners.lastOrNull()?.url
                    ?: info.thumbnails.lastOrNull()?.url
            plot = info.description?.content
        }
    }

    /* =========================
     * HELPERS
     * ========================= */

    private fun movieSearch(it: InfoItem): SearchResponse? {
        return newMovieSearchResponse(
            name = it.name ?: return null,
            url = it.url ?: return null,
            type = TvType.Others
        ) {
            posterUrl = it.thumbnails.lastOrNull()?.url
        }
    }

    private fun seriesSearch(it: InfoItem): SearchResponse? {
        return newTvSeriesSearchResponse(
            name = it.name ?: return null,
            url = it.url ?: return null,
            type = TvType.Others
        ) {
            posterUrl = it.thumbnails.lastOrNull()?.url
        }
    }

    private fun newEpisode(item: StreamInfoItem): Episode? {
        val url = item.url ?: return null

        return newEpisode(
            url = url
        ) {
            name = item.name
            posterUrl = item.thumbnails.lastOrNull()?.url
            runTime = (item.duration / 60).toInt()
            item.uploadDate?.let {
                addDate(Date(it.date().timeInMillis))
            }
        }
    }
}
