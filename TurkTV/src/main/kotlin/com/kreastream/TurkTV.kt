package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class TurkTV : MainAPI() {
    override var mainUrl = "https://turktv.internal"
    override var name = "Türk TV"
    override var hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Live)
    override var lang = "tr"

    private val providers by lazy { listOf(Trt1(), TrtLive()) }

    override val mainPage = mainPageOf(
        "trt1_current" to "TRT 1 – Güncel Diziler",
        "trt1_archive" to "TRT 1 – Eski Diziler",
        "live_tv"      to "TRT Canlı TV",
        "live_radio"   to "TRT Canlı Radyo"
    )

    private val cache = mutableMapOf<String, List<SearchResponse>>()

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val key = "${request.data}_$page"
        cache[key]?.let {
            return newHomePageResponse(listOf(HomePageList(request.name, it, request.data.startsWith("live_"))))
        }

        val items = when (request.data) {
            "trt1_current" -> delegate(Trt1::class.java, "https://www.trt1.com.tr/diziler?archive=false&order=title_asc", page)
            "trt1_archive" -> delegate(Trt1::class.java, "https://www.trt1.com.tr/diziler?archive=true&order=title_asc", page)
            "live_tv"      -> delegate(TrtLive::class.java, "tv", 1)
            "live_radio"   -> delegate(TrtLive::class.java, "radio", 1)
            else -> emptyList()
        }

        cache[key] = items
        return newHomePageResponse(listOf(HomePageList(request.name, items, request.data.startsWith("live_"))))
    }

    private suspend fun delegate(clazz: Class<out MainAPI>, url: String, page: Int): List<SearchResponse> {
        val provider = providers.find { it::class.java == clazz } ?: return emptyList()
        return try {
            provider.getMainPage(page, MainPageRequest("", url, false))
                ?.items?.firstOrNull()?.list?.map { wrap(it) } ?: emptyList()
        } catch (e: Exception) { emptyList() }
    }

    // ------------------- URL WRAPPING -------------------
    private fun wrap(resp: SearchResponse): SearchResponse = when (resp) {
        is TvSeriesSearchResponse -> newTvSeriesSearchResponse(
            resp.name,
            "https://turktv.internal/series/${resp.url.removePrefix("https://www.trt1.com.tr")}",
            TvType.TvSeries
        ) { posterUrl = resp.posterUrl }
        is MovieSearchResponse -> newMovieSearchResponse(
            resp.name,
            "https://turktv.internal/live/${resp.url}",
            TvType.Live
        ) { posterUrl = resp.posterUrl }
        else -> resp
    }

    private fun unwrap(wrapped: String): String = when {
        wrapped.startsWith("https://turktv.internal/series/") ->
            "https://www.trt1.com.tr${wrapped.removePrefix("https://turktv.internal/series/")}"
        wrapped.startsWith("https://turktv.internal/live/") ->
            wrapped.removePrefix("https://turktv.internal/live/")
        else -> wrapped
    }

    // ------------------- LOAD -------------------
    override suspend fun load(url: String): LoadResponse {
        val real = unwrap(url)
        val provider = findProvider(real) ?: throw ErrorLoadingException("No provider for $real")
        return provider.load(real)!!
    }

    // ------------------- LOAD LINKS – UNWRAP FIRST -------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val realUrl = unwrap(data)
        val provider = findProvider(realUrl) ?: return false

        // CRITICAL: Use real URL for all network calls
        return provider.loadLinks(realUrl, isCasting, subtitleCallback, callback)
    }

    // ------------------- SEARCH -------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val all = mutableListOf<SearchResponse>()
        providers.forEach { p ->
            try { all.addAll(p.search(query).orEmpty().map { wrap(it) }) } catch (_: Exception) {}
        }
        return all.distinctBy { it.url }
    }

    // ------------------- ROUTING -------------------
    private fun findProvider(url: String): MainAPI? = providers.find {
        when (it) {
            is Trt1    -> url.contains("trt1.com.tr/diziler/")
            is TrtLive -> url.contains(".m3u8") || url.contains(".aac") || url.contains("trt.net.tr")
            else -> false
        }
    }
}