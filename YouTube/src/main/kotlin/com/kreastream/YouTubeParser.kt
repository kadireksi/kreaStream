package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.search.SearchInfoItem
import org.schabi.newpipe.extractor.search.filter.SearchFilters
import org.schabi.newpipe.extractor.search.filter.SearchFilters.FilterType
import org.schabi.newpipe.extractor.linkhandler.LinkHandlerFactory

class YouTubeParser(private val apiName: String) {

    init {
        ServiceList.YouTube.init(NewPipeDownloader.getInstance())
    }

    /* ========================= SEARCH ========================= */

    fun search(query: String, contentFilter: String): List<SearchResponse> {
        val filters = SearchFilters.Builder().apply {
            when (contentFilter) {
                "channels" -> setType(FilterType.CHANNEL)
                "playlists" -> setType(FilterType.PLAYLIST)
                else -> setType(FilterType.VIDEO)
            }
        }.build()

        val searchInfo = SearchInfo.getInfo(
            ServiceList.YouTube,
            query,
            filters,
            null
        )

        return searchInfo.items.mapNotNull { item ->
            when (item) {
                is StreamInfoItem -> videoToSearchResponse(item)
                is ChannelInfoItem -> channelToSearchResponse(item)
                is PlaylistInfoItem -> playlistToSearchResponse(item)
                else -> null
            }
        }
    }

    /* ========================= LOAD ========================= */

    suspend fun videoToLoadResponse(url: String): LoadResponse {
        val info = StreamInfo.getInfo(ServiceList.YouTube, url)

        return newMovieLoadResponse(
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

        val episodes = info.relatedItems
            .filterIsInstance<StreamInfoItem>()
            .map {
                newEpisode(it.url) {
                    name = it.name
                    posterUrl = it.thumbnails.lastOrNull()?.url
                }
            }

        return newTvSeriesLoadResponse(
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

        val episodes = info.relatedItems
            .filterIsInstance<StreamInfoItem>()
            .map {
                newEpisode(it.url) {
                    name = it.name
                    posterUrl = it.thumbnails.lastOrNull()?.url
                }
            }

        return newTvSeriesLoadResponse(
            name = info.name ?: "YouTube Playlist",
            url = url,
            type = TvType.Others,
            episodes = episodes
        ) {
            posterUrl = info.thumbnails.lastOrNull()?.url
            plot = info.description
        }
    }

    /* ========================= SEARCH HELPERS ========================= */

    private fun videoToSearchResponse(item: StreamInfoItem): SearchResponse =
        newMovieSearchResponse(
            name = item.name ?: "YouTube Video",
            url = item.url,
            type = TvType.Others
        ) {
            posterUrl = item.thumbnails.lastOrNull()?.url
        }

    private fun channelToSearchResponse(item: ChannelInfoItem): SearchResponse =
        newTvSeriesSearchResponse(
            name = item.name ?: "YouTube Channel",
            url = item.url,
            type = TvType.Others
        ) {
            posterUrl = item.thumbnails.lastOrNull()?.url
        }

    private fun playlistToSearchResponse(item: PlaylistInfoItem): SearchResponse =
        newTvSeriesSearchResponse(
            name = item.name ?: "YouTube Playlist",
            url = item.url,
            type = TvType.Others
        ) {
            posterUrl = item.thumbnails.lastOrNull()?.url
        }
}
