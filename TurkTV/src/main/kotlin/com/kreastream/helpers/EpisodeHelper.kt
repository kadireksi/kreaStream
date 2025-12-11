package com.kreastream.helpers


import com.lagradost.cloudstream3.*
import com.kreastream.*


object EpisodeHelper {
suspend fun loadDetails(app: com.lagradost.cloudstream3.App, ch: ChannelConfig, url: String): LoadResponse {
val doc = app.get(url, headers = ch.headers).document
val title = doc.selectFirst(ch.detail_page?.selectors?.get("title") ?: "")?.text() ?: ch.name


val episodes = loadEpisodes(app, ch, url)
return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
this.posterUrl = doc.selectFirst(ch.detail_page?.selectors?.get("poster") ?: "")?.attr("src")
this.plot = doc.selectFirst(ch.detail_page?.selectors?.get("description") ?: "")?.text()
}
}