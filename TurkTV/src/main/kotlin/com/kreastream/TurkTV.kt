package com.kreastream


return newHomePageResponse(pages)
}


override suspend fun search(query: String): List<SearchResponse> {
ensureConfig()
val results = mutableListOf<SearchResponse>()
// Search streams
streams.filter { it.title.contains(query, true) }.forEach { results.add(it.toSearchResponse()) }
// Search channels listings
channels.forEach { ch ->
ch.listings.forEach { listing ->
results += ListingHelper.searchListing(app, ch, listing, query)
}
}
return results
}


override suspend fun load(url: String): LoadResponse {
ensureConfig()


// 1) Live genre: format used above -> "<header>_genre::<group>::<genre>"
if (url.contains("_genre::")) {
val parts = url.split("::")
if (parts.size >= 3) {
val group = parts[1]
val genre = parts[2]
return LiveHelper.loadGenre(group, genre, streams)
}
}


// 2) Stream direct
streams.firstOrNull { it.url == url }?.let { si ->
return StreamHelper.loadStreamSingle(si)
}


// 3) Channel detail (long form url inside channel base)
val ch = channels.firstOrNull { url.startsWith(it.base_url) }
if (ch != null) {
return EpisodeHelper.loadDetails(app, ch, url)
}


throw Error("Unknown url format")
}


override suspend fun loadLinks(
data: String,
isCasting: Boolean,
subtitleCallback: (SubtitleFile) -> Unit,
callback: (ExtractorLink) -> Unit
): Boolean {
ensureConfig()


// if data is a stream url
streams.firstOrNull { it.url == data }?.let { si ->
StreamHelper.loadStreamLinks(si, callback)
return true
}


// if data belongs to channel
val ch = channels.firstOrNull { data.startsWith(it.base_url) }
if (ch != null) return VideoExtractor.extract(app, ch, data, subtitleCallback, callback)


return false
}
}