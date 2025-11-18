package com.trtcocuk

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class TRTCocuk : MainAPI() {
    override var lang = "tr"
    override var name = "TRT Çocuk"
    override var mainUrl = "https://www.trtcocuk.net.tr"
    override val supportedTypes = setOf(TvType.Cartoon)
    override val hasMainPage = true
    override val hasQuickSearch = true

    data class TrtEpisode(
        val name: String,
        val episode: Int,
        val link: String,
        val poster: String?
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get("$mainUrl/video").document
        val listOf(
            newHomePageResponse("Tüm Çizgi Filmler", doc.select(".col-xl-2 a[href^=\"/\"]").mapNotNull { el ->
                val href = fixUrl(el.attr("href"))
                val title = el.selectFirst("img")?.attr("alt") ?: return@mapNotNull null
                val poster = el.selectFirst("img")?.attr("src") ?: el.selectFirst("img")?.attr("data-src")
                newAnimeSearchResponse(title, href, TvType.Cartoon) {
                    this.posterUrl = poster
                    addDubStatus(dubbed = false, subbed = true)
                }
            })
        ).also { return HomePageResponse(it) }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/ara?q=${query.replace(" ", "+")}"
        val doc = app.get(url).document
        return doc.select(".search-result a").mapNotNull {
            val href = fixUrl(it.attr("href"))
            val img = it.selectFirst("img")?.attr("src") ?: it.selectFirst("img")?.attr("data-src")
            val title = it.selectFirst(".title")?.text() ?: ""
            if (!href.contains("/video/")) return@mapNotNull null
            newAnimeSearchResponse(title, href, TvType.Cartoon) {
                this.posterUrl = img
                addDubStatus(dubbed = false, subbed = true)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1")?.text() ?: doc.selectFirst("title")?.text() ?: "Bilinmeyen"
        val isSeries = url.contains("/") && !url.contains("-bolum")
        val episodes = mutableListOf<Episode>()

        if (isSeries) {
            // Series page - extract all episodes from carousel + "Daha Fazla" if exists
            doc.select("a[href*=\"-bolum\"], a[href*=\"-bölüm\"]").forEach { a ->
                val href = fixUrl(a.attr("href"))
                val epTitle = a.selectFirst("p")?.text() ?: a.selectFirst("img")?.attr("alt") ?: ""
                val epNum = Regex("""(\d+)\.?(?:\s*B[BöÖ]l[üu]m|)$""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
                    ?: episodes.size + 1
                val poster = a.selectFirst("img")?.attr("data-src") ?: a.selectFirst("img")?.attr("src")
                episodes.add(Episode(
                    data = href,
                    name = epTitle,
                    season = 1,
                    episode = epNum,
                    posterUrl = poster
                ))
            }
            // Reverse because TRT lists newest first
            episodes.reverse()
        } else {
            // Direct episode page
            episodes.add(Episode(data = url, name = title, season = 1, episode = 1))
        }

        return newTvSeriesLoadResponse(title, url, TvType.Cartoon, episodes) {
            this.posterUrl = doc.selectFirst("meta[property=\"og:image\"]")?.attr("content")
            this.plot = doc.selectFirst("meta[name=\"description\"]")?.attr("content")
            this.tags = listOf("Çocuk", "Türkçe", "TRT")
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = app.get(data).document

        // Find the script that contains "master.mpd"
        val script = doc.select("script").find { it.html().contains("master.mpd") } ?: return

        val mpdUrl = Regex("""["']([^"']*master\.mpd[^"']*)["']""").find(script.html())?.groupValues?.get(1)
            ?: return

        val fullMpd = if (mpdUrl.startsWith("http")) mpdUrl else "https://cdn-i.pr.trt.com.tr$mpdUrl"

        callback.invoke(
            ExtractorLink(
                source = name,
                name = name,
                url = fullMpd,
                referer = "https://www.trtcocuk.net.tr/",
                quality = Qualities.P720.value,
                isMpd = true
            )
        )

        // Turkish subtitles (always .vtt)
        doc.select("track[kind=\"captions\"]").forEach {
            val subUrl = it.attr("src")
            if (subUrl.isNotEmpty()) {
                subtitleCallback.invoke(SubtitleFile("Türkçe", fixUrl(subUrl)))
            }
        }
    }
}