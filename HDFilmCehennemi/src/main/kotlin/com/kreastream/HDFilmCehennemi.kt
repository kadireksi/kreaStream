package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element
import java.net.URI

class Hdfilmcehennemi : MainApi() {
    override var mainUrl = "https://www.hdfilmcehennemi.la"  // Add mirrors: val mirrors = listOf(".la", ".date", ".nl")
    override var name = "HDFilmCehennemi"
    override val lang = "tr"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/film-izle/",
        "$mainUrl/dizi-izle/",
        "$mainUrl/tumunu-gor/"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.url).document
        val home = mutableListOf<HomePageList>()

        // Parse latest movies
        val latestMovies = document.select("div.movie-item").mapNotNull { elem ->
            val title = elem.selectFirst("h3 a")?.text()?.trim() ?: return@mapNotNull null
            val poster = elem.selectFirst("img")?.attr("src")
            val link = fixUrl(elem.selectFirst("a")?.attr("href") ?: return@mapNotNull null)
            newMovieSearchResponse(title, link) { this.posterUrl = poster }
        }
        home.add(HomePageList("Latest Movies", latestMovies))

        // Similar for series...
        return HomePageResponse(home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"  // Or POST to /search
        val doc = app.get(url).document
        return doc.select("div.search-result a").mapNotNull { elem ->
            val title = elem.attr("title").trim()
            val href = fixUrl(elem.attr("href"))
            val yearRegex = Regex("""(\d{4})""")
            val year = yearRegex.find(title)?.value?.toIntOrNull()
            val poster = elem.selectFirst("img")?.attr("src")
            if (href.contains("/film/")) newMovieSearchResponse(title, href, TvType.Movie, year = year) { this.posterUrl = poster }
            else if (href.contains("/dizi/")) newTvSeriesSearchResponse(title, href, TvType.TvSeries, year = year) { this.posterUrl = poster }
            else null
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1")?.text()?.trim() ?: throw ErrorLoadingException("Title not found")
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val year = doc.selectFirst(".year")?.text()?.toIntOrNull()
        val tags = doc.select(".genres a").map { it.text() }
        val rating = doc.selectFirst(".rating")?.text()?.toFloatOrNull()

        val recs = doc.select(".related-movies a").mapNotNull {
            val recTitle = it.text().trim()
            val recHref = fixUrl(it.attr("href"))
            newMovieSearchResponse(recTitle, recHref) { this.posterUrl = it.selectFirst("img")?.attr("src") }
        }

        return if (url.contains("/dizi/")) {
            val episodes = mutableListOf<Episode>()
            doc.select(".sezon-list .episode").forEach { ep ->
                val season = ep.selectFirst(".season")?.text()?.toIntOrNull() ?: 1
                ep.select(".episode-item a").forEachIndexed { idx, link ->
                    val epTitle = "S$season E${idx+1}"
                    val epUrl = fixUrl(link.attr("href"))
                    episodes.add(Episode(epUrl, epTitle, season, idx + 1))
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.tags = tags
                this.rating = rating
                addRecommendations(recs)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.tags = tags
                this.rating = rating
                addRecommendations(recs)
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
        // Extract iframe srcs (common for embeds)
        doc.select("iframe[src]").apmap { iframe ->
            val embedUrl = fixUrl(iframe.attr("src"))
            if (embedUrl.contains(".mobi") || embedUrl.contains("rapidrame") || embedUrl.contains("voe")) {
                callback(ExtractorLink(name, "${name} Embed", embedUrl, mainUrl, Qualities.Unknown.value, headers = mapOf("Referer" to mainUrl)))
            }
        }

        // Subtitles (VTT common)
        doc.select("track[src], .subtitle a").apmap { sub ->
            val subUrl = fixUrl(sub.attr("src") ?: sub.attr("href"))
            if (subUrl.endsWith(".vtt") || subUrl.endsWith(".srt")) {
                subtitleCallback(SubtitleFile("Turkish", subUrl))
            }
        }

        // Fallback: JS embeds (if site uses script loading)
        val jsEmbeds = doc.select("script:containsData('player')").text().let { script ->
            Regex("""src['"]\s*:\s*['"]([^'"]+)""").findAll(script).map { it.groupValues[1] }
        }
        jsEmbeds.toList().apmap { embed ->
            callback(ExtractorLink(name, "JS Embed", embed, referer = mainUrl, quality = Qualities.Unknown.value))
        }

        return true
    }

    private fun fixUrl(input: String): String {
        return if (input.startsWith("http")) input else mainUrl + input.removePrefix("/")
    }

    companion object {
        fun getMirror(): String {
            val mirrors = listOf("https://www.hdfilmcehennemi.la", "https://www.hdfilmcehennemi.date", "https://www.hdfilmcehennemi.nl")
            // Rotate if main fails – implement logic here
            return mirrors.first()
        }
    }
}
