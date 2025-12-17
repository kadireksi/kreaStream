package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addDuration
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

    /* ==============================
     * TRENDING
     * ============================== */

    fun getTrendingVideoUrls(page: Int): HomePageList? {
        val kiosk = ServiceList.YouTube.kioskList.defaultKioskExtractor
        val info = KioskInfo.getInfo(ServiceList.YouTube, kiosk.url)

        val videos = loadPaged(
            page = page,
            first = info.relatedItems,
            hasNext = { info.hasNextPage() },
            next = { info.nextPage },
            loader = { next ->
                KioskInfo.getMoreItems(ServiceList.YouTube, kiosk.url, next)
            }
        )

        val results = videos
            .filterIsInstance<StreamInfoItem>()
            .filterNot { it.isShortFormContent }
            .mapNotNull { it.toMovieSearch() }

        return HomePageList(
            name = "Trending",
            list = results,
            isHorizontalImages = true
        )
    }

    /* ==============================
     * PLAYLIST → SEARCH
     * ============================== */

    fun playlistToSearchResponseList(url: String, page: Int): HomePageList? {
        val info = PlaylistInfo.getInfo(url)

        val videos = loadPaged(
            page = page,
            first = info.relatedItems,
            hasNext = { info.hasNextPage() },
            next = { info.nextPage },
            loader = { next ->
                PlaylistInfo.getMoreItems(ServiceList.YouTube, url, next)
            }
        )

        val results = videos
            .filterIsInstance<StreamInfoItem>()
            .mapNotNull { it.toMovieSearch() }

        return HomePageList(
            name = "${info.uploaderName}: ${info.name}",
            list = results,
            isHorizontalImages = true
        )
    }

    /* ==============================
     * CHANNEL → SEARCH
     * ============================== */

    fun channelToSearchResponseList(url: String, page: Int): HomePageList? {
        val channel = ChannelInfo.getInfo(url)

        val videoTabHandler = channel.tabs.firstOrNull {
            it.url.endsWith("/videos")
        } ?: return null

        val videoTab = ChannelTabInfo.getInfo(ServiceList.YouTube, videoTabHandler)

        val videos = loadPaged(
            page = page,
            first = videoTab.relatedItems,
            hasNext = { videoTab.hasNextPage() },
            next = { videoTab.nextPage },
            loader = { next ->
                ChannelTabInfo.getMoreItems(
                    ServiceList.YouTube,
                    videoTabHandler,
                    next
                )
            }
        )

        val results = videos.mapNotNull { it.toMovieSearch() }

        return HomePageList(
            name = channel.name,
            list = results,
            isHorizontalImages = true
        )
    }

    /* ==============================
     * SEARCH
     * ============================== */

    fun search(
        query: String,
        contentFilter: String = "videos"
    ): List<SearchResponse> {

        val handler = ServiceList.YouTube.searchQHFactory.fromQuery(
            query,
            listOf(contentFilter),
            null
        )

        val info = SearchInfo.getInfo(
            ServiceList.YouTube,
            SearchQueryHandler(handler)
        )

        val items = mutableListOf<InfoItem>()
        items.addAll(info.relatedItems)

        var next = info.nextPage
        repeat(3) {
            val more = SearchInfo.getMoreItems(ServiceList.YouTube, handler, next)
            items.addAll(more.items)
            if (!more.hasNextPage()) return@repeat
            next = more.nextPage
        }

        return items.mapNotNull {
            when (it.infoType) {
                InfoType.STREAM -> it.toMovieSearch()
                InfoType.PLAYLIST, InfoType.CHANNEL -> it.toSeriesSearch()
                else -> null
            }
        }
    }

    /* ==============================
     * VIDEO → LOAD
     * ============================== */

    fun videoToLoadResponse(url: String): LoadResponse {
        val info = StreamInfo.getInfo(url)

        return newMovieLoadResponse(
            name = info.name,
            url = url,
            type = TvType.Others,
            dataUrl = url
        ) {
            posterUrl = info.thumbnails.lastOrNull()?.url
            plot = info.description?.content
            tags = listOfNotNull(
                info.uploaderName,
                "Views: ${info.viewCount}",
                "Likes: ${info.likeCount}"
            )
            duration = (info.duration / 60).toInt()
        }
    }

    /* ==============================
     * CHANNEL → LOAD
     * ============================== */

    fun channelToLoadResponse(url: String): LoadResponse {
        val channel = ChannelInfo.getInfo(url)

        return newTvSeriesLoadResponse(
            name = channel.name,
            url = url,
            type = TvType.Others,
            episodes = getChannelVideos(channel)
        ) {
            posterUrl = channel.avatars.lastOrNull()?.url
            backgroundPosterUrl = channel.banners.lastOrNull()?.url
            plot = channel.description
            tags = listOf("Subscribers: ${channel.subscriberCount}")
        }
    }

    /* ==============================
     * PLAYLIST → LOAD
     * ============================== */

    fun playlistToLoadResponse(url: String): LoadResponse {
        val info = PlaylistInfo.getInfo(url)

        val videos = mutableListOf<StreamInfoItem>()
        videos.addAll(info.relatedItems)

        var next = info.nextPage
        repeat(10) {
            if (!info.hasNextPage()) return@repeat
            val more = PlaylistInfo.getMoreItems(ServiceList.YouTube, url, next)
            videos.addAll(more.items)
            next = more.nextPage
        }

        val banner =
            info.banners.lastOrNull()?.url
                ?: info.thumbnails.lastOrNull()?.url

        return newTvSeriesLoadResponse(
            name = info.name,
            url = url,
            type = TvType.Others,
            episodes = getPlaylistVideos(videos)
        ) {
            posterUrl = info.thumbnails.lastOrNull()?.url
            backgroundPosterUrl = banner
            plot = info.description?.content
            tags = listOf("Channel: ${info.uploaderName}")
        }
    }

    /* ==============================
     * EPISODES
     * ============================== */

    private fun getChannelVideos(channel: ChannelInfo): List<Episode> {
        val videoTab = channel.tabs
            .map { ChannelTabInfo.getInfo(ServiceList.YouTube, it) }
            .first { it.name == "videos" }

        return videoTab.relatedItems.mapNotNull { it.toEpisode() }.reversed()
    }

    private fun getPlaylistVideos(videos: List<StreamInfoItem>): List<Episode> {
        return videos.mapNotNull { video ->
            Episode(
                data = video.url ?: return@mapNotNull null,
                name = video.name ?: return@mapNotNull null,
                posterUrl = video.thumbnails.lastOrNull()?.url,
                runTime = (video.duration / 60).toInt()
            ).apply {
                video.uploadDate?.let {
                    addDate(Date(it.date().timeInMillis))
                }
            }
        }
    }

    /* ==============================
     * HELPERS
     * ============================== */

    private fun InfoItem.toMovieSearch(): SearchResponse? {
        return newMovieSearchResponse(
            name = name ?: return null,
            url = url ?: return null,
            type = TvType.Others
        ) {
            posterUrl = thumbnails.lastOrNull()?.url
        }
    }

    private fun InfoItem.toSeriesSearch(): SearchResponse? {
        return newTvSeriesSearchResponse(
            name = name ?: return null,
            url = url ?: return null,
            type = TvType.Others
        ) {
            posterUrl = thumbnails.lastOrNull()?.url
        }
    }

    private fun InfoItem.toEpisode(): Episode? {
        return Episode(
            data = url ?: return null,
            name = name ?: return null,
            posterUrl = thumbnails.lastOrNull()?.url
        )
    }

    private fun <T> loadPaged(
        page: Int,
        first: List<T>,
        hasNext: () -> Boolean,
        next: () -> Any?,
        loader: (Any?) -> org.schabi.newpipe.extractor.Page<T>
    ): List<T> {
        if (page == 1) return first

        if (!hasNext()) return emptyList()

        var current = next()
        repeat(page - 1) {
            val more = loader(current)
            if (it == page - 2) return more.items
            if (!more.hasNextPage()) return emptyList()
            current = more.nextPage
        }
        return emptyList()
    }
}
