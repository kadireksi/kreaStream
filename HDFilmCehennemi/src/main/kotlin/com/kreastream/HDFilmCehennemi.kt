package com.kreastream

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import okhttp3.FormBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class HDFilmCehennemi : MainAPI() {

    override var mainUrl = "https://www.hdfilmcehennemi.ws"
    override var name = "HDFilmCehennemi"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override var hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 50L
    override var sequentialMainPageScrollDelay = 50L

    private val objectMapper = ObjectMapper().apply {
        registerModule(KotlinModule.Builder().build())
        disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
    }

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0",
        "Accept" to "*/*",
        "X-Requested-With" to "fetch"
    )

    private fun cleanPoster(url: String?) =
        url?.replace("/list/", "/")?.replace("/thumb/", "/")

    private data class PosterData(
        val title: String,
        val href: String,
        val posterUrl: String?,
        val year: Int?,
        val score: Float?,
        val tvType: TvType,
        val newTitle: String
    )

    private data class LoadData(
        val title: String,
        val poster: String?,
        val tags: List<String>,
        val year: Int?,
        val tvType: TvType,
        val description: String?,
        val score: Float?,
        val actors: List<Actor>,
        val trailer: String?
    )

    override val mainPage = mainPageOf(
        "$mainUrl/load/page/1/home/" to "Yeni Filmler",
        "$mainUrl/load/page/1/languages/turkce-dublajli-film-izleyin-3/" to "Türkçe Dublaj Filmler",
        "$mainUrl/load/page/1/countries/turkiye-2/" to "Türk Filmleri",
        "$mainUrl/load/page/1/recent-episodes/" to "Yeni Bölümler",
        "$mainUrl/load/page/1/home-series/" to "Yeni Diziler",
        "$mainUrl/load/page/1/categories/tavsiye-filmler-izle2/" to "Tavsiye Filmler",
        "$mainUrl/load/page/1/genres/aksiyon-filmleri-izleyin-5/" to "Aksiyon Filmleri",
        "$mainUrl/load/page/1/genres/animasyon-filmlerini-izleyin-5/" to "Animasyon Filmleri",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else request.data.replace("/page/1/", "/page/$page/")
        val response = app.get(url, headers = headers)
        val parsed = response.parsedSafe<HDFC>() ?: return newHomePageResponse(request.name, emptyList())

        val results = Jsoup.parse(parsed.html)
            .select("a.poster, a.mini-poster")
            .mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, results)
    }

    private fun Element.extractPoster(): PosterData? {
        val title = attr("title").ifBlank { selectFirst("strong, h4")?.text()?.trim() } ?: return null
        val href = fixUrlNull(attr("href")) ?: return null

        val poster = cleanPoster(selectFirst("img[data-src], img[src]")?.attr("data-src") ?: selectFirst("img")?.attr("src"))

        val year = selectFirst(".poster-meta span")?.text()?.trim()?.toIntOrNull()
        val score = selectFirst(".imdb")?.ownText()?.trim()?.toFloatOrNull()
        val isSeries = href.contains("/dizi/") || href.contains("/series")

        return PosterData(
            title = title,
            href = href,
            posterUrl = poster,
            year = year,
            score = score,
            tvType = if (isSeries) TvType.TvSeries else TvType.Movie,
            newTitle = title
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val data = extractPoster() ?: return null

        return newMovieSearchResponse(data.newTitle, data.href, data.tvType) {
            posterUrl = data.posterUrl
            score = Score.from10(data.score)
        }
    }

    override suspend fun quickSearch(query: String) = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.get("$mainUrl/search?q=$query", headers = headers).parsedSafe<Results>() ?: return emptyList()

        return response.results.mapNotNull {
            Jsoup.parse(it).selectFirst("a")?.extractPoster()?.let { poster ->
                newMovieSearchResponse(poster.newTitle, poster.href, poster.tvType) {
                    posterUrl = poster.posterUrl
                    score = Score.from10(poster.score)
                }
            }
        }
    }

    private fun Document.extractLoadData(): LoadData? {
        val title = selectFirst("h1.section-title")?.text()?.substringBefore(" izle") ?: return null
        val poster = cleanPoster(select("aside.post-info-poster img.lazyload").lastOrNull()?.attr("data-src"))
        val tags = select("div.post-info-genres a").map { it.text() }
        val year = selectFirst("div.post-info-year-country a")?.text()?.trim()?.toIntOrNull()
        val isSeries = select("div.seasons").isNotEmpty()

        val actors = select("div.post-info-cast a").map {
            Actor(it.text(), it.selectFirst("img")?.attr("data-src"))
        }

        val trailer = selectFirst("div.post-info-trailer button")
            ?.attr("data-modal")?.substringAfter("trailer/")
            ?.let { "https://www.youtube.com/embed/$it" }

        return LoadData(
            title = title,
            poster = poster,
            tags = tags,
            year = year,
            tvType = if (isSeries) TvType.TvSeries else TvType.Movie,
            description = selectFirst("article.post-info-content")?.text()?.trim(),
            score = selectFirst("div.post-info-imdb-rating span")?.text()?.toFloatOrNull(),
            actors = actors,
            trailer = trailer
        )
    }


    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        val data = doc.extractLoadData() ?: return null

        val recommendations = doc.select("div.section-slider-container div.slider-slide").mapNotNull {
            val recHref = fixUrlNull(it.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
            val recPoster = fixUrlNull(it.selectFirst("img")?.attr("data-src") ?: it.selectFirst("img")?.attr("src"))

            newMovieSearchResponse(it.attr("title"), recHref, data.tvType) {
                this.posterUrl = recPoster
            }
        }

        return if (data.tvType == TvType.TvSeries) {
            val episodes = doc.select("div.seasons-tab-content a").mapNotNull {
                newEpisode(fixUrlNull(it.attr("href")) ?: return@mapNotNull null) {
                    name = it.text()
                }
            }

            newTvSeriesLoadResponse(data.title, url, data.tvType, episodes) {
                posterUrl = data.poster
                year = data.year
                plot = data.description
                tags = data.tags
                score = Score.from10(data.score)
                this.recommendations = recommendations
                addActors(data.actors)
                addTrailer(data.trailer)
            }

        } else {
            newMovieLoadResponse(data.title, url, data.tvType, url) {
                posterUrl = data.poster
                year = data.year
                plot = data.description
                tags = data.tags
                score = Score.from10(data.score)
                this.recommendations = recommendations
                addActors(data.actors)
                addTrailer(data.trailer)
            }
        }
    }

    private object VideoJsDecrypter {
        fun decrypt(strings: List<String>): String {
            if (strings.isEmpty()) return ""

            val joined = strings.joinToString("").reversed()
            val once = runCatching { Base64.decode(joined, Base64.NO_PADDING) }.getOrNull() ?: return ""
            val twice = runCatching { Base64.decode(once, Base64.NO_PADDING) }.getOrNull() ?: return ""

            return buildString {
                twice.forEachIndexed { i, byte ->
                    val cc = byte.toInt() and 0xFF
                    val shift = 256 % (i + 5)
                    append(((cc - shift + 256) % 256).toChar())
                }
            }.trim()
        }

        fun fallback(joined: String): String = runCatching {
            val reversed = joined.reversed()
            val decoded = Base64.decode(reversed, Base64.NO_PADDING)

            buildString {
                decoded.forEachIndexed { i, byte ->
                    val cc = byte.toInt() and 0xFF
                    val shift = 256 % (i + 5)
                    append(((cc - shift + 256) % 256).toChar())
                }
            }
        }.getOrDefault("")
    }

    private suspend fun extractFromPlayer(script: String, source: String, referer: String, cb: (ExtractorLink) -> Unit) {
        val unpacked = JsUnpacker(script).unpack() ?: return

        val match = Regex("""decrypt\(\[(.*?)\]""").find(unpacked) ?: return
        val list = Regex("\"(.*?)\"").findAll(match.value).map { it.groupValues[1] }.toList()

        var url = VideoJsDecrypter.decrypt(list)

        if (!url.contains("http"))
            url = VideoJsDecrypter.fallback(list.joinToString(""))

        if (!url.startsWith("http")) return

        cb(
            newExtractorLink(
                source = source,
                name = source,
                url = url
            ){
                this.referer = referer
                this.type = if (url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            }
        )
    }


    private val seen = hashSetOf<String>()

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        cb: (ExtractorLink) -> Unit
    ): Boolean {

        seen.clear()

        val doc = app.get(data).document

        doc.select("div.alternative-links button.alternative-link").forEach { button ->

            val iframeSrc = "${mainUrl}/video/${button.attr("data-video")}/"
            val iframe = app.get(iframeSrc).document.selectFirst("iframe")?.attr("src") ?: return@forEach

            val page = app.get(fixUrl(iframe)).text
            val js = Jsoup.parse(page).select("script").find { it.data().contains("eval(") }?.data() ?: return@forEach

            extractFromPlayer(js, button.text(), iframe, cb)
        }

        return seen.isNotEmpty()
    }

    data class Results(@JsonProperty("results") val results: List<String>)
    data class HDFC(@JsonProperty("html") val html: String)
}
