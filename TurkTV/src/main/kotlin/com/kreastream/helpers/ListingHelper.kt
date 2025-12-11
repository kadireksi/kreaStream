package com.kreastream.helpers


import com.lagradost.cloudstream3.*
import com.kreastream.*


object ListingHelper {
suspend fun loadListing(app: com.lagradost.cloudstream3.App, ch: ChannelConfig, listing: ListingConfig): List<SearchResponse> {
try {
val url = UrlHelper.buildPagedUrl(ch.base_url, listing.path, 1)
val res = app.get(url, headers = ch.headers)
val doc = res.document
val container = listing.selectors["container"] ?: return emptyList()
val list = mutableListOf<SearchResponse>()
doc.select(container).forEach { el ->
val title = el.smartSelect(listing.selectors["title"]) ?: return@forEach
val href = el.smartSelect(listing.selectors["url"]) ?: return@forEach
val poster = el.smartSelect(listing.selectors["poster"])
val full = UrlHelper.join(ch.base_url, href)
list.add(newTvSeriesSearchResponse(title, full, TvType.TvSeries) { this.posterUrl = poster })
}
return list
} catch (e: Exception) {
return emptyList()
}
}


suspend fun searchListing(app: com.lagradost.cloudstream3.App, ch: ChannelConfig, listing: ListingConfig, query: String): List<SearchResponse> {
return loadListing(app, ch, listing).filter { it.title.contains(query, true) }
}
}