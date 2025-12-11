package com.kreastream.helpers

import com.lagradost.cloudstream3.*
import com.kreastream.*

object LiveHelper {
    fun loadGenre(api: MainAPI, group: String, genre: String, streams: List<StreamItem>): LoadResponse {
        val filtered = streams.filter { it.group.equals(group, true) }
            .filter { it.genres.split(",").map { s -> s.trim() }.contains(genre) }

        val episodes = filtered.mapIndexed { i, s ->
            api.newEpisode(s.url) {
                name = s.title
                posterUrl = s.poster
                episode = i + 1
                season = 1
            }
        }

        return api.newTvSeriesLoadResponse(genre, "${group}::$genre", TvType.TvSeries, episodes) {
            this.posterUrl = episodes.firstOrNull()?.posterUrl
            this.plot = "Canlı yayınlar - $genre"
        }
    }
}
