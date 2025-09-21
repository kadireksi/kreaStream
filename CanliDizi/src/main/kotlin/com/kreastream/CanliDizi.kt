package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element

class CanliDizi : MainAPI() {
    override var mainUrl = "https://www.canlidizi14.com"
    override var name = "CanliDizi"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)
    override var lang = "tr"
    override val hasMainPage = true

    private fun Element.toSearchResponse(): SearchResponse {
        val a = selectFirst("a") ?: throw ErrorLoadingException("No link found")
        val img = selectFirst("img")
        val posterAttr = if (img?.hasAttr("data-wpfc-original-src") == true) "data-wpfc-original-src" else "src"
        val poster = fixUrl(img?.attr(posterAttr) ?: "")
        val titleElem = selectFirst("div.serie-name")
        val epElem = selectFirst("div.episode-name")
        val title = if (a.attr("href").contains("-izle.html") && !a.attr("href").contains("bolum")) epElem?.text() ?: titleElem?.text() ?: "" else titleElem?.text() ?: epElem?.text() ?: ""
        val href = fixUrl(a.attr("href"))
        val isSeries = href.contains("kategori")
        val isMovie = href.contains("-izle.html") && !href.contains("bolum")
        val headers = mapOf("referer" to "$mainUrl/")

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href) {
                this.posterUrl = poster
                this.year = epElem?.text()?.toIntOrNull()
                this.quality = getQualityFromString(img?.attr("title"))
                this.posterHeaders = headers
            }
        } else if (isMovie) {
            newMovieSearchResponse(title, href) {
                this.posterUrl = poster
                this.quality = getQualityFromString(img?.attr("title"))
                this.posterHeaders = headers
            }
        } else {
            val epTitle = "$title ${epElem?.text() ?: ""}".trim()
            newMovieSearchResponse(epTitle, href, TvType.TvSeries) {
                this.posterUrl = poster
                this.quality = getQualityFromString(img?.attr("title"))
                this.posterHeaders = headers
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(mainUrl).document
        val lists = ArrayList<HomePageList>()

        val popularItems = doc.select("div.diziler div.owl-item").map { it.toSearchResponse() }
        if (popularItems.isNotEmpty()) lists.add(HomePageList("Popüler Diziler", popularItems))

        val yerliSection = doc.select("div.episodes.episode").getOrNull(0)
        val yerliItems = yerliSection?.select("div.list-episodes")?.map { it.selectFirst("div.episode-box")?.toSearchResponse()!! } ?: emptyList()
        if (yerliItems.isNotEmpty()) lists.add(HomePageList("Yerli Diziler", yerliItems))

        val digitalSection = doc.select("div.episodes.episode").getOrNull(1)
        val digitalItems = digitalSection?.select("div.list-episodes")?.map { it.selectFirst("div.episode-box")?.toSearchResponse()!! } ?: emptyList()
        if (digitalItems.isNotEmpty()) lists.add(HomePageList("Dijital Diziler", digitalItems))

        val filmsSection = doc.select("div.episodes.episode").getOrNull(2)
        val filmsItems = filmsSection?.select("div.list-episodes")?.map { it.selectFirst("div.episode-box")?.toSearchResponse()!! } ?: emptyList()
        if (filmsItems.isNotEmpty()) lists.add(HomePageList("Filmler", filmsItems))

        return HomePageResponse(lists)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=${query.replace(" ", "+")}"
        val doc = app.get(searchUrl).document
        return doc.select("div.episodes.episode div.list-episodes div.episode-box").map { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val headers = mapOf("referer" to "$mainUrl/")

        if (url.contains("kategori")) {
            val title = doc.selectFirst("div.title-border")?.text() ?: doc.selectFirst("title")?.text()?.split(" | ")?.get(0) ?: ""
            val posterElem = doc.selectFirst("div.poster img")
            val posterAttr = if (posterElem?.hasAttr("data-wpfc-original-src") == true) "data-wpfc-original-src" else "src"
            val poster = fixUrl(posterElem?.attr(posterAttr) ?: "")
            val description = doc.selectFirst("div.synopsis")?.text()
            val ratingStr = doc.selectFirst("div.episode-date")?.text()?.replace("IMDb: ", "")?.replace(",", ".")?.toFloatOrNull()
            val rating = (ratingStr?.times(10))?.toInt()
            val episodes = doc.select("div.episodes.episode div.list-episodes div.episode-box").mapIndexedNotNull { index, el ->
                val a = el.selectFirst("a") ?: return@mapIndexedNotNull null
                val epName = el.selectFirst("div.episode-name")?.text() ?: ""
                val epNum = epName.replace(".Bölüm", "").trim().toIntOrNull() ?: (index + 1)
                val epUrl = fixUrl(a.attr("href"))
                val epImg = el.selectFirst("img")
                val epPosterAttr = if (epImg?.hasAttr("data-wpfc-original-src") == true) "data-wpfc-original-src" else "src"
                val epPoster = fixUrl(epImg?.attr(epPosterAttr) ?: "")
                val epDate = el.selectFirst("div.episode-date")?.text()
                newEpisode(epUrl) {
                    this.name = epName
                    this.season = 1
                    this.episode = epNum
                    this.posterUrl = epPoster
                    this.description = epDate
                }
            }.reversed()

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.rating = rating
                this.posterHeaders = headers
            }
        } else {
            val title = doc.selectFirst("title")?.text()?.split(" | ")?.get(0) ?: ""
            val posterElem = doc.selectFirst("div.poster img")
            val posterAttr = if (posterElem?.hasAttr("data-wpfc-original-src") == true) "data-wpfc-original-src" else "src"
            val poster = fixUrl(posterElem?.attr(posterAttr) ?: "")
            val description = doc.selectFirst("div.synopsis")?.text()
            val ratingStr = doc.selectFirst("div.episode-date")?.text()?.replace("IMDb: ", "")?.replace(",", ".")?.toFloatOrNull()
            val rating = (ratingStr?.times(10))?.toInt()
            val type = if (url.contains("bolum")) TvType.TvSeries else TvType.Movie

            return newMovieLoadResponse(title, url, type, url) {
                this.posterUrl = poster
                this.plot = description
                this.rating = rating
                this.posterHeaders = headers
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document

        val partLinks = doc.select("div.diziplus_part a").map { fixUrl(it.attr("href")) }.toMutableList()
        partLinks.add(0, data)

        partLinks.apmap { partUrl ->
            val partDoc = app.get(partUrl).document
            val iframe = partDoc.selectFirst("iframe")
            val iframeAttr = if (iframe?.hasAttr("data-wpfc-original-src") == true) "data-wpfc-original-src" else "src"
            val iframeSrc = iframe?.attr(iframeAttr)
                ?: partDoc.selectFirst("div.video embed")?.attr("src")
                ?: return@apmap

            // Try custom extraction
            val playerDoc = app.get(iframeSrc, referer = partUrl).document
            val videoSources = playerDoc.select("video source")
            if (videoSources.isNotEmpty()) {
                videoSources.apmap {
                    val src = it.attr("src")
                    val quality = Qualities.values().find { q -> it.attr("title").contains(q.name) } ?: Qualities.Unknown
                    callback(newExtractorLink(
                        this.name,
                        this.name,
                        fixUrl(src),
                        iframeSrc,
                        quality.value,
                        src.endsWith(".m3u8")
                    ))
                }
            } else {
                val video = playerDoc.selectFirst("video")
                if (video != null) {
                    val src = video.attr("src")
                    if (src.isNotEmpty()) {
                        callback(newExtractorLink(
                            this.name,
                            this.name,
                            fixUrl(src),
                            iframeSrc,
                            Qualities.Unknown.value,
                            src.endsWith(".m3u8")
                        ))
                    }
                } else {
                    // Fallback to loadExtractor
                    loadExtractor(iframeSrc, partUrl, subtitleCallback, callback)
                }
            }
        }

        return true
    }
}