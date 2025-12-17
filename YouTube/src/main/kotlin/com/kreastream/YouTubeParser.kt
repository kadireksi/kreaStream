package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

class YouTubeParser(
    private val api: MainAPI
) {

    /* ================= SEARCH ================= */

    fun search(query: String, contentFilter: String): List<SearchResponse> {
        return when (contentFilter) {
            "channels" -> channelToSearchResponseList(query)
            "playlists" -> playlistToSearchResponseList(query)
            else -> videoSearch(query)
        }
    }

    private fun videoSearch(query: String): List<SearchResponse> {
        val qh = ServiceList.YouTube.searchQHFactory.fromQuery(query)
        val info = SearchInfo.getInfo(ServiceList.YouTube, qh)

        return info.getItems()
            .filterIsInstance<StreamInfoItem>()
            .map { item ->
                api.newMovieSearchResponse(
                    item.name ?: "YouTube",
                    item.url,
                    TvType.Others
                ) {
                    posterUrl = item.thumbnails.lastOrNull()?.url
                }
            }
    }

    fun channelToSearchResponseList(query: String): List<SearchResponse> {
        val qh = ServiceList.YouTube.searchQHFactory.fromQuery(query)
        val info = SearchInfo.getInfo(ServiceList.YouTube, qh)

        return info.getItems()
            .filterIsInstance<org.schabi.newpipe.extractor.channel.ChannelInfoItem>()
            .map { item ->
                api.newTvSeriesSearchResponse(
                    item.name ?: "YouTube Channel",
                    item.url,
                    TvType.Others
                ) {
                    posterUrl = item.thumbnails.lastOrNull()?.url
                }
            }
    }

    fun playlistToSearchResponseList(query: String): List<SearchResponse> {
        val qh = ServiceList.YouTube.searchQHFactory.fromQuery(query)
        val info = SearchInfo.getInfo(ServiceList.YouTube, qh)

        return info.getItems()
            .filterIsInstance<org.schabi.newpipe.extractor.playlist.PlaylistInfoItem>()
            .map { item ->
                api.newTvSeriesSearchResponse(
                    item.name ?: "YouTube Playlist",
                    item.url,
                    TvType.Others
                ) {
                    posterUrl = item.thumbnails.lastOrNull()?.url
                }
            }
    }

    /* ================= LOAD ================= */

    suspend fun videoToLoadResponse(url: String): LoadResponse {
        val info = StreamInfo.getInfo(ServiceList.YouTube, url)

        return api.newMovieLoadResponse(
            info.name ?: "YouTube",
            url,
            TvType.Others,
            url
        ) {
            posterUrl = info.thumbnails.lastOrNull()?.url
            plot = info.description?.content
            duration = info.duration.toInt()
        }
    }

    suspend fun channelToLoadResponse(url: String): LoadResponse {
        val info = ChannelInfo.getInfo(ServiceList.YouTube, url)

        val episodes = info.getItems()
            .filterIsInstance<StreamInfoItem>()
            .map { item ->
                api.newEpisode(item.url, false) {
                    name = item.name
                    posterUrl = item.thumbnails.lastOrNull()?.url
                }
            }

        return api.newTvSeriesLoadResponse(
            info.name ?: "YouTube Channel",
            url,
            TvType.Others,
            episodes
        ) {
            posterUrl = info.avatars.lastOrNull()?.url
            plot = info.description
        }
    }

    suspend fun playlistToLoadResponse(url: String): LoadResponse {
        val info = PlaylistInfo.getInfo(ServiceList.YouTube, url)

        val episodes = info.getItems()
            .filterIsInstance<StreamInfoItem>()
            .map { item ->
                api.newEpisode(item.url, false) {
                    name = item.name
                    posterUrl = item.thumbnails.lastOrNull()?.url
                }
            }

        return api.newTvSeriesLoadResponse(
            info.name ?: "YouTube Playlist",
            url,
            TvType.Others,
            episodes
        ) {
            posterUrl = info.thumbnails.lastOrNull()?.url
            plot = info.description
        }
    }

    /* ================= MAIN PAGE ================= */

    fun getTrendingVideoUrls(): HomePageList {
        val qh = ServiceList.YouTube.searchQHFactory.fromQuery("trending")
        val info = SearchInfo.getInfo(ServiceList.YouTube, qh)

        val list = info.getItems()
            .filterIsInstance<StreamInfoItem>()
            .map { item ->
                api.newMovieSearchResponse(
                    item.name ?: "YouTube",
                    item.url,
                    TvType.Others
                ) {
                    posterUrl = item.thumbnails.lastOrNull()?.url
                }
            }

        return HomePageList("Trending", list)
    }
}
