package com.kreastream.helpers

import com.lagradost.cloudstream3.*
import com.kreastream.*

object ListingHelper {
    suspend fun loadListing(api: MainAPI, ch: ChannelConfig, listing: ListingConfig): List<SearchResponse> {
        try {
            val url = UrlHelper.buildPagedUrl(ch.base_url, listing.path, 1)
            val res = api.app.get(url, headers = ch.headers)
            val doc = res.document
            val container = listing.selectors["container"] ?: return emptyList()
            val list = mutableListOf<SearchResponse>()
            val elements = doc.select(container)
            for (el in elements) {
                val title = el.smartSelect(listing.selectors["title"]) ?: continue
                val href = el.smartSelect(listing.selectors["url"]) ?: continue
                val poster = el.smartSelect(listing.selectors["poster"])
                val full = UrlHelper.join(ch.base_url, href)
                list.add(api.newTvSeriesSearchResponse(title, full, TvType.TvSeries) {
                    this.posterUrl = poster
                })
            }
            return list
        } catch (e: Exception) {
            return emptyList()
        }
    }

    suspend fun searchListing(api: MainAPI, ch: ChannelConfig, listing: ListingConfig, query: String): List<SearchResponse> {
        return loadListing(api, ch, listing).filter { it.title.contains(query, true) }
    }
}
