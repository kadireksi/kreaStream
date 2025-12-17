package it.dogior.hadEnough

import com.lagradost.api.Log
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addDuration
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
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

    fun getTrendingVideoUrls(page: Int): HomePageList? {
        val service = ServiceList.YouTube
        val kiosks = service.kioskList
        val trendingsUrl = kiosks.defaultKioskExtractor.url
        val infoItem = KioskInfo.getInfo(ServiceList.YouTube, trendingsUrl)

        val videos = if (page == 1) {
            infoItem.relatedItems.toMutableList()
        } else {
            mutableListOf<StreamInfoItem>()
        }
        if (page > 1) {
            var hasNext = infoItem.hasNextPage()
            if (!hasNext) {
                return null
            }
            var count = 1
            var nextPage = infoItem.nextPage
            while (count < page && hasNext) {
                val more = KioskInfo.getMoreItems(ServiceList.YouTube, trendingsUrl, nextPage)
                if (count == page - 1) {
                    videos.addAll(more.items)
                }
                hasNext = more.hasNextPage()
                nextPage = more.nextPage
                count++
            }
        }
        val searchResponses = videos.filter { !it.isShortFormContent }.map {
            MovieSearchResponse(
                name = it.name,
                url = it.url,
                posterUrl = it.thumbnails.last().url,
                type = TvType.Others,
                apiName = apiName
            )
        }
        return HomePageList(
            name = "Trending",
            list = searchResponses,
            isHorizontalImages = true
        )
    }

    fun playlistToSearchResponseList(url: String, page: Int): HomePageList? {
        val playlistInfo = PlaylistInfo.getInfo(url)
        val videos = if (page == 1) {
            playlistInfo.relatedItems.toMutableList()
        } else {
            mutableListOf<StreamInfoItem>()
        }
        if (page > 1) {
            var hasNext = playlistInfo.hasNextPage()
            if (!hasNext) {
                return null
            }
            var count = 1
            var nextPage = playlistInfo.nextPage
            while (count < page && hasNext) {
                val more = PlaylistInfo.getMoreItems(ServiceList.YouTube, url, nextPage)
                if (count == page - 1) {
                    videos.addAll(more.items)
                }
                hasNext = more.hasNextPage()
                nextPage = more.nextPage
                count++
            }
        }
        val searchResponses = videos.map {
            MovieSearchResponse(
                name = it.name,
                url = it.url,
                posterUrl = it.thumbnails.last().url,
                type = TvType.Others,
                apiName = apiName
            )
        }
        return HomePageList(
            name = "${playlistInfo.uploaderName}: ${playlistInfo.name}",
            list = searchResponses,
            isHorizontalImages = true
        )
    }

    fun channelToSearchResponseList(url: String, page: Int): HomePageList? {
        val channelInfo = ChannelInfo.getInfo(url)
        val tabsLinkHandlers = channelInfo.tabs
        val tabs = tabsLinkHandlers.map { ChannelTabInfo.getInfo(ServiceList.YouTube, it) }
        val videoTab = tabs.first { it.name == "videos" }

        val videos = if (page == 1) {
            videoTab.relatedItems.toMutableList()
        } else {
            mutableListOf<InfoItem>()
        }

        if (page > 1) {
            var hasNext = videoTab.hasNextPage()
            if (!hasNext) {
                return null
            }
            var count = 1
            var nextPage = videoTab.nextPage
            while (count < page && hasNext) {

                val videoTabHandler = tabsLinkHandlers.first{it.url.endsWith("/videos")}
                val more = ChannelTabInfo.getMoreItems(ServiceList.YouTube, videoTabHandler, nextPage)
                if (count == page - 1) {
                    videos.addAll(more.items)
                }
                hasNext = more.hasNextPage()
                nextPage = more.nextPage
                count++
            }
        }
        val searchResponses = videos.map {
            MovieSearchResponse(
                name = it.name,
                url = it.url,
                posterUrl = it.thumbnails.last().url,
                type = TvType.Others,
                apiName = apiName
            )
        }
        return HomePageList(
            name = channelInfo.name,
            list = searchResponses,
            isHorizontalImages = true
        )
    }

    fun search(
        query: String,
        contentFilter: String = "videos",
    ): List<SearchResponse> {
        val handlerFactory = ServiceList.YouTube.searchQHFactory
        val searchHandler = handlerFactory.fromQuery(
            query,
            listOf(contentFilter),
            null
        )

        val searchInfo = SearchInfo.getInfo(ServiceList.YouTube, SearchQueryHandler(searchHandler))

        val resultSize = searchInfo.relatedItems.size
        if (resultSize <= 0) {
            return emptyList()
        }

        val pageResults = searchInfo.relatedItems.toMutableList()
        var nextPage = searchInfo.nextPage
        for (i in 1..3) {
            val more = SearchInfo.getMoreItems(ServiceList.YouTube, searchHandler, nextPage)
            pageResults.addAll(more.items)
            if (!more.hasNextPage()) break
            nextPage = more.nextPage
        }

        val finalResults = pageResults.mapNotNull {
//            Log.d("YouTubeParser", "Related: ${it.name}, type: ${it.infoType}")
            when (it.infoType) {
                InfoType.PLAYLIST, InfoType.CHANNEL -> {
                    TvSeriesSearchResponse(
                        name = it.name,
                        url = it.url,
                        posterUrl = it.thumbnails.last().url,
                        apiName = apiName
                    )
                }

                InfoType.STREAM -> {
                    MovieSearchResponse(
                        name = it.name,
                        url = it.url,
                        posterUrl = it.thumbnails.last().url,
                        apiName = apiName
                    )
                }

                else -> {
//                    Log.d("YouTubeParser", "Other type: ${it.name} \t|\t type: ${it.infoType}")
                    null
                }
            }
        }
//        Log.d("YouTubeParser", "Results size: ${finalResults.size}")
        return finalResults
    }

    fun videoToLoadResponse(videoUrl: String): LoadResponse {
        val videoInfo = StreamInfo.getInfo(videoUrl)
        val views = "Views: ${videoInfo.viewCount}"
        val likes = "Likes: ${videoInfo.likeCount}"
        val length = videoInfo.duration / 60
        return MovieLoadResponse(
            name = videoInfo.name,
            url = videoUrl,
            dataUrl = videoUrl,
            posterUrl = videoInfo.thumbnails.last().url,
            plot = videoInfo.description.content,
            type = TvType.Others,
            tags = listOf(videoInfo.uploaderName, views, likes),
            apiName = apiName
        ).apply {
            this.duration = length.toInt()
        }
    }

    fun channelToLoadResponse(url: String): LoadResponse {
        val channelInfo = ChannelInfo.getInfo(url)
        val avatars = try {
            channelInfo.avatars.last().url
        } catch (e: Exception){
            null
        }
        val banners = try {
            channelInfo.banners.last().url
        } catch (e: Exception){
            null
        }
        val tags = mutableListOf("Subscribers: ${channelInfo.subscriberCount}")
        return TvSeriesLoadResponse(
            name = channelInfo.name,
            url = url,
            posterUrl = avatars,
            backgroundPosterUrl = banners,
            plot = channelInfo.description,
            type = TvType.Others,
            tags = tags,
            episodes = getChannelVideos(channelInfo),
            apiName = apiName
        )
    }

    private fun getChannelVideos(channel: ChannelInfo): List<Episode> {
        val tabsLinkHandlers = channel.tabs
        val tabs = tabsLinkHandlers.map { ChannelTabInfo.getInfo(ServiceList.YouTube, it) }
        val videoTab = tabs.first { it.name == "videos" }
        val videos = videoTab.relatedItems.mapNotNull {
            Episode(
                data = it.url,
                name = it.name,
                posterUrl = it.thumbnails.last().url
            )
        }
        return videos.reversed()
    }

    fun playlistToLoadResponse(url: String): LoadResponse {
        val playlistInfo = PlaylistInfo.getInfo(url)
        val tags = mutableListOf("Channel: ${playlistInfo.uploaderName}")
        val banner =
            if (playlistInfo.banners.isNotEmpty()) playlistInfo.banners.last().url else playlistInfo.thumbnails.last().url
        val eps = playlistInfo.relatedItems.toMutableList()
        var hasNext = playlistInfo.hasNextPage()
        var count = 1
        var nextPage = playlistInfo.nextPage
        while (hasNext) {
            val more = PlaylistInfo.getMoreItems(ServiceList.YouTube, url, nextPage)
            eps.addAll(more.items)
            hasNext = more.hasNextPage()
            nextPage = more.nextPage
            count++
            if (count >= 10) break
//            Log.d("YouTubeParser", "Page ${count + 1}: ${more.items.size}")
        }
        return TvSeriesLoadResponse(
            name = playlistInfo.name,
            url = url,
            posterUrl = playlistInfo.thumbnails.last().url,
            backgroundPosterUrl = banner,
            plot = playlistInfo.description.content,
            type = TvType.Others,
            tags = tags,
            episodes = getPlaylistVideos(eps),
            apiName = apiName
        )
    }

    private fun getPlaylistVideos(videos: List<StreamInfoItem>): List<Episode> {
        val episodes = videos.map { video ->
//            Log.d("YouTubeParser", video.name)
            Episode(
                data = video.url,
                name = video.name,
                posterUrl = video.thumbnails.last().url,
                runTime = (video.duration / 60).toInt()
            ).apply {
                video.uploadDate?.let { addDate(Date(it.date().timeInMillis)) }
            }
        }
        return episodes
    }
}