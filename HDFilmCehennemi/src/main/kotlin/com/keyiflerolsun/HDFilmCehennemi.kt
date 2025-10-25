package com.keyiflerolsun

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class HDFilmCehennemi : MainAPI() {
    override var mainUrl              = "https://www.hdfilmcehennemi.la"
    override var name                 = "HDFilmCehennemi"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries)

    // ! CloudFlare bypass
    override var sequentialMainPage = true        // * https://recloudstream.github.io/dokka/-cloudstream/com.lagradost.cloudstream3/-main-a-p-i/index.html#-2049735995%2FProperties%2F101969414
    override var sequentialMainPageDelay       = 50L  // ? 0.05 saniye
    override var sequentialMainPageScrollDelay = 50L  // ? 0.05 saniye

    // ! CloudFlare v2
    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor      by lazy { CloudflareInterceptor(cloudflareKiller) }

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller): Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request  = chain.request()
            val response = chain.proceed(request)
            val doc      = Jsoup.parse(response.peekBody(1024 * 1024).string())

            if (doc.text().contains("Just a moment")) {
                return cloudflareKiller.intercept(chain)
            }

            return response
        }
    }

    // ObjectMapper for JSON parsing
    private val objectMapper = ObjectMapper().apply {
        registerModule(KotlinModule.Builder().build())
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    // Standard headers for requests
    private val standardHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:137.0) Gecko/20100101 Firefox/137.0",
        "Accept" to "*/*",
        "X-Requested-With" to "fetch"
    )

    // Ana sayfa kategorilerini tanımlıyoruz
    override val mainPage = mainPageOf(
        "${mainUrl}/load/page/1/home/"                        to "Yeni Eklenen Filmler",
        "${mainUrl}/load/page/1/categories/nette-ilk-filmler/"            to "Nette İlk Filmler",
        "${mainUrl}/load/page/1/home-series/"                             to "Yeni Eklenen Diziler",
        "${mainUrl}/load/page/1/categories/tavsiye-filmler-izle2/"        to "Tavsiye Filmler",
        "${mainUrl}/load/page/1/imdb7/"                                   to "IMDB 7+ Filmler",
        "${mainUrl}/load/page/1/mostLiked/"                               to "En Çok Beğenilenler",
        "${mainUrl}/load/page/1/genres/aile-filmleri-izleyin-6/"          to "Aile Filmleri",
        "${mainUrl}/load/page/1/genres/aksiyon-filmleri-izleyin-5/"       to "Aksiyon Filmleri",
        "${mainUrl}/load/page/1/genres/animasyon-filmlerini-izleyin-5/"   to "Animasyon Filmleri",
        "${mainUrl}/load/page/1/genres/belgesel-filmlerini-izle-1/"       to "Belgesel Filmleri",
        "${mainUrl}/load/page/1/genres/bilim-kurgu-filmlerini-izleyin-3/" to "Bilim Kurgu Filmleri",
        "${mainUrl}/load/page/1/genres/komedi-filmlerini-izleyin-1/"      to "Komedi Filmleri",
        "${mainUrl}/load/page/1/genres/korku-filmlerini-izle-4/"          to "Korku Filmleri",
        "${mainUrl}/load/page/1/genres/romantik-filmleri-izle-2/"         to "Romantik Filmleri"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // URL'deki sayfa numarasını güncelle
        val url = if (page == 1) {
            request.data
                .replace("/load/page/1/genres/","/tur/")
                .replace("/load/page/1/categories/","/category/")
                .replace("/load/page/1/imdb7/","/imdb-7-puan-uzeri-filmler/")
        } else {
            request.data
                .replace("/page/1/", "/page/${page}/")
        }

        // API isteği gönder
        val response = app.get(url, headers = standardHeaders, referer = mainUrl)

        // Yanıt başarılı değilse boş liste döndür
        if (response.text.contains("Sayfa Bulunamadı")) {
            Log.d("HDCH", "Sayfa bulunamadı: $url")
            return newHomePageResponse(request.name, emptyList())
        }

        try {
            // JSON yanıtını parse et
            val hdfc: HDFC = objectMapper.readValue(response.text)
            val document = Jsoup.parse(hdfc.html)

            Log.d("HDCH", "Kategori ${request.name} için ${document.select("a").size} sonuç bulundu")

            // Film/dizi kartlarını SearchResponse listesine dönüştür
            val results = document.select("a").mapNotNull { it.toSearchResult() }

            return newHomePageResponse(request.name, results)
        } catch (e: Exception) {
            Log.e("HDCH", "JSON parse hatası (${request.name}): ${e.message}")
            return newHomePageResponse(request.name, emptyList())
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.attr("title")
            .takeIf { it.isNotEmpty() }
            .takeUnless {
                it?.contains("Seri Filmler", ignoreCase = true) == true
                || it?.contains("Japonya Filmleri", ignoreCase = true) == true
                || it?.contains("Kore Filmleri", ignoreCase = true) == true
                || it?.contains("Hint Filmleri", ignoreCase = true) == true
                || it?.contains("Türk Filmleri", ignoreCase = true) == true
                || it?.contains("DC Yapımları", ignoreCase = true) == true
                || it?.contains("Marvel Yapımları", ignoreCase = true) == true
                || it?.contains("Amazon Yapımları", ignoreCase = true) == true
                || it?.contains("1080p Film izle", ignoreCase = true) == true
            } ?: return null

        val href      = fixUrlNull(this.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src"))

        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.get(
            "${mainUrl}/search?q=${query}",
            headers = mapOf("X-Requested-With" to "fetch")
        ).parsedSafe<Results>() ?: return emptyList()

        val searchResults = mutableListOf<SearchResponse>()

        response.results.forEach { resultHtml ->
            val document = Jsoup.parse(resultHtml)

            val title     = document.selectFirst("h4.title")?.text() ?: return@forEach
            val href      = fixUrlNull(document.selectFirst("a")?.attr("href")) ?: return@forEach
            val posterUrl = fixUrlNull(document.selectFirst("img")?.attr("src")) ?:
            fixUrlNull(document.selectFirst("img")?.attr("data-src"))

            searchResults.add(
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = posterUrl?.replace("/thumb/", "/list/")
                }
            )
        }

        return searchResults
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document



        val title       = document.selectFirst("h1.section-title")?.text()?.substringBefore(" izle") ?: return null
        val poster      = fixUrlNull(document.select("aside.post-info-poster img.lazyload").lastOrNull()?.attr("data-src"))
        val tags        = document.select("div.post-info-genres a").map { it.text() }
        val year        = document.selectFirst("div.post-info-year-country a")?.text()?.trim()?.toIntOrNull()
        val tvType      = if (document.select("div.seasons").isEmpty()) TvType.Movie else TvType.TvSeries
        val description = document.selectFirst("article.post-info-content > p")?.text()?.trim()
        val score      = document.selectFirst("div.post-info-imdb-rating span")?.text()?.substringBefore("(")?.trim()?.toFloatOrNull()
        val actors      = document.select("div.post-info-cast a").map {
            Actor(it.selectFirst("strong")!!.text(), it.select("img").attr("data-src"))
        }

        val recommendations = document.select("div.section-slider-container div.slider-slide").mapNotNull {
            val recName      = it.selectFirst("a")?.attr("title") ?: return@mapNotNull null
            val recHref      = fixUrlNull(it.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
            val recPosterUrl = fixUrlNull(it.selectFirst("img")?.attr("data-src")) ?:
            fixUrlNull(it.selectFirst("img")?.attr("src"))

            newTvSeriesSearchResponse(recName, recHref, TvType.TvSeries) {
                this.posterUrl = recPosterUrl
            }
        }

        return if (tvType == TvType.TvSeries) {
            val trailer  = document.selectFirst("div.post-info-trailer button")?.attr("data-modal")
                ?.substringAfter("trailer/")?.let { "https://www.youtube.com/embed/$it" }
            val episodes = document.select("div.seasons-tab-content a").mapNotNull {
                val epName    = it.selectFirst("h4")?.text()?.trim() ?: return@mapNotNull null
                val epHref    = fixUrlNull(it.attr("href")) ?: return@mapNotNull null
                val epEpisode = Regex("""(\d+)\. ?Bölüm""").find(epName)?.groupValues?.get(1)?.toIntOrNull()
                val epSeason  = Regex("""(\d+)\. ?Sezon""").find(epName)?.groupValues?.get(1)?.toIntOrNull() ?: 1

                newEpisode(epHref) {
                    this.name = epName
                    this.season = epSeason
                    this.episode = epEpisode
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl       = poster
                this.year            = year
                this.plot            = description
                this.tags            = tags
                this.score = Score.from10(score)
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
            }
        } else {
            val trailer = document.selectFirst("div.post-info-trailer button")?.attr("data-modal")
                ?.substringAfter("trailer/")?.let { "https://www.youtube.com/embed/$it" }

            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl       = poster
                this.year            = year
                this.plot            = description
                this.tags            = tags
                this.score           = Score.from10(score)
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
            }
        }
    }

    private suspend fun invokeLocalSource(
        source: String,
        url: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val response = app.get(url, referer = "$mainUrl/")
        val script = response.document.select("script")
            .find { it.data().contains("file_link=") }?.data()

        if (script == null) {
            Log.e("HDCH", "Script not found at $url")
            return
        }

        val unpacked = runCatching { getAndUnpack(script) }.getOrNull()
        if (unpacked == null) {
            Log.e("HDCH", "Unpacking failed at $url")
            return
        }

        val videoData = unpacked.substringAfter("file_link=\"").substringBefore("\";")
        if (videoData.isBlank()) {
            Log.e("HDCH", "file_link not found in unpacked script at $url")
            return
        }

        val decodedUrl = runCatching { base64Decode(videoData) }.getOrNull()
        if (decodedUrl.isNullOrBlank()) {
            Log.e("HDCH", "Decoded video URL is invalid at $url")
            return
        }

        Log.d("HDCH", "Decoded video URL: $decodedUrl")

        callback.invoke(
            newExtractorLink(source, source, decodedUrl, INFER_TYPE) {
                this.referer = "$mainUrl/"
                this.headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                this.quality = Qualities.Unknown.value
            }
        )
    }


    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        val iframealak = fixUrlNull(
            document.selectFirst(".close")?.attr("data-src")
                ?: document.selectFirst(".rapidrame")?.attr("data-src")
        )?.takeIf { it.isNotBlank() } ?: run {
            Log.e("HDCH", "iframe not found on $data")
            return false
        }

        Log.d("HDCH", "iframe found: $iframealak")

        // Subtitle handling (unchanged)
        if (iframealak.contains("hdfilmcehennemi.mobi")) {
            val iframedoc = app.get(iframealak, referer = mainUrl).document
            val baseUri = iframedoc.location().substringBefore("/", "https://www.hdfilmcehennemi.mobi")

            iframedoc.select("track[kind=captions]")
                .filter { it.attr("srclang") != "forced" }
                .forEach { track ->
                    val lang = when (val code = track.attr("srclang")) {
                        "tr" -> "Türkçe"
                        "en" -> "İngilizce"
                        else -> code
                    }
                    val subUrl = track.attr("src").let { src ->
                        if (src.startsWith("http")) src else "$baseUri/$src".replace("//", "/")
                    }
                    subtitleCallback(SubtitleFile(lang, subUrl))
                }
        } else if (iframealak.contains("rplayer")) {
            val iframeDoc = app.get(iframealak, referer = "$data/").document
            Regex("\"file\":\"((?:[^\"]|\"\")*)\"", RegexOption.IGNORE_CASE)
                .findAll(iframeDoc.toString())
                .forEach { match ->
                    val fileUrl = match.groupValues[1].replace("\\/", "/")
                    val finalUrl = fixUrlNull(fileUrl)?.plus("/") ?: return@forEach
                    val langCode = when {
                        fileUrl.contains("Turkish", true) -> "Türkçe"
                        fileUrl.contains("English", true) -> "İngilizce"
                        else -> "Unknown"
                    }
                    subtitleCallback(SubtitleFile(langCode, finalUrl))
                }
        }

        document.select("div.alternative-links").forEach { element ->
            val langCode = element.attr("data-lang").uppercase()
            element.select("button.alternative-link").forEach { button ->
                val source = button.text().replace("(HDrip Xbet)", "").trim() + " $langCode"
                val videoID = button.attr("data-video").takeIf { it.isNotBlank() } ?: run {
                    Log.e("HDCH", "Missing videoID for $source")
                    return@forEach
                }

                val apiResponse = app.get(
                    "$mainUrl/video/$videoID/",
                    headers = mapOf("Content-Type" to "application/json", "X-Requested-With" to "fetch"),
                    referer = data
                ).parsedSafe<VideoIframe>() ?: run {
                    Log.e("HDCH", "Failed to parse iframe JSON for videoID $videoID")
                    return@forEach
                }

                val iframe = Jsoup.parse(apiResponse.data.html).selectFirst("iframe")?.attr("data-src")
                    ?.takeIf { it.isNotBlank() } ?: run {
                    Log.e("HDCH", "iframe not found in JSON for videoID $videoID")
                    return@forEach
                }


                val finalIframe = if (iframe.contains("?rapidrame_id=")) {
                    "$mainUrl/playerr/" + iframe.substringAfter("?rapidrame_id=")
                } else iframe

                Log.d("HDCH", "$source » $videoID » $finalIframe")
                invokeLocalSource(source, finalIframe, callback)
            }
        }

        return true
    }



    data class Results(
        @JsonProperty("results") val results: List<String> = arrayListOf()
    )

    data class HDFC(
        @JsonProperty("html") val html: String,
        @JsonProperty("meta") val meta: Meta
    )

    data class Meta(
        @JsonProperty("title") val title: String,
        @JsonProperty("canonical") val canonical: Boolean,
        @JsonProperty("keywords") val keywords: Boolean
    )

    data class VideoIframe(
    @JsonProperty("success") val success: Boolean,
    @JsonProperty("data") val data: IframeData
    )

    data class IframeData(
        @JsonProperty("html") val html: String
    )

}
