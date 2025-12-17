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
    private val api: MainAPI,
    private val apiName: String
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

        return info.items
            .filterIsInstance<StreamInfoItem>()
            .map {
                api.newMovieSearchResponse(
                    name = it.name ?: "YouTube",
                    url = it.url,
                    type = TvType.Others
                ) {
                    posterUrl = it.thumbnails.lastOrNull()?.url
                }
            }
    }

    fun channelToSearchResponseList(query: String): List<SearchResponse> {
        val qh = ServiceList.YouTube.searchQHFactory.fromQuery(query)
        val info = SearchInfo.getInfo(ServiceList.YouTube, qh)

        return info.items
            .filterIsInstance<org.schabi.newpipe.extractor.channel.ChannelInfoItem>()
            .map {
                api.newTvSeriesSearchResponse(
                    name = it.name ?: "YouTube Channel",
                    url = it.url,
                    type = TvType.Others
                ) {
                    posterUrl = it.thumbnails.lastOrNull()?.url
                }
            }
    }

    fun playlistToSearchResponseList(query: String): List<SearchResponse> {
        val qh = ServiceList.YouTube.searchQHFactory.fromQuery(query)
        val info = SearchInfo.getInfo(ServiceList.YouTube, qh)

        return info.items
            .filterIsInstance<org.schabi.newpipe.extractor.playlist.PlaylistInfoItem>()
            .map {
                api.newTvSeriesSearchResponse(
                    name = it.name ?: "YouTube Playlist",
                    url = it.url,
                    type = TvType.Others
                ) {
                    posterUrl = it.thumbnails.lastOrNull()?.url
                }
            }
    }

    /* ================= LOAD ================= */

    suspend fun videoToLoadResponse(url: String): LoadResponse {
        val info = StreamInfo.getInfo(ServiceList.YouTube, url)

        return api.newMovieLoadResponse(
            name = info.name ?: "YouTube Video",
            url = url,
            type = TvType.Others,
            dataUrl = url
        ) {
            posterUrl = info.thumbnails.lastOrNull()?.url
            plot = info.description?.content
            duration = info.duration.toInt()
        }
    }

    suspend fun channelToLoadResponse(url: String): LoadResponse {
        val info = ChannelInfo.getInfo(ServiceList.YouTube, url)

        val episodes = info.items
            .filterIsInstance<StreamInfoItem>()
            .map {
                api.newEpisode(it.url) {
                    name = it.name
                    posterUrl = it.thumbnails.lastOrNull()?.url
                }
            }

        return api.newTvSeriesLoadResponse(
            name = info.name ?: "YouTube Channel",
            url = url,
            type = TvType.Others,
            episodes = episodes
        ) {
            posterUrl = info.avatars.lastOrNull()?.url
            plot = info.description
        }
    }

    suspend fun playlistToLoadResponse(url: String): LoadResponse {
        val info = PlaylistInfo.getInfo(ServiceList.YouTube, url)

        val episodes = info.items
            .filterIsInstance<StreamInfoItem>()
            .map {
                api.newEpisode(it.url) {
                    name = it.name
                    posterUrl = it.thumbnails.lastOrNull()?.url
                }
            }

        return api.newTvSeriesLoadResponse(
            name = info.name ?: "YouTube Playlist",
            url = url,
            type = TvType.Others,
            episodes = episodes
        ) {
            posterUrl = info.thumbnails.lastOrNull()?.url
            plot = info.description
        }
    }

    /* ================= MAIN PAGE ================= */

    fun getTrendingVideoUrls(page: Int): HomePageList {
        val qh = ServiceList.YouTube.searchQHFactory.fromQuery("trending")
        val info = SearchInfo.getInfo(ServiceList.YouTube, qh)

        val items = info.items
            .filterIsInstance<StreamInfoItem>()
            .map {
                api.newMovieSearchResponse(
                    name = it.name ?: "YouTube",
                    url = it.url,
                    type = TvType.Others
                ) {
                    posterUrl = it.thumbnails.lastOrNull()?.url
                }
            }

        return HomePageList(
            listOf(HomePageList.HomePage("Trending", items))
        )
    }
}
