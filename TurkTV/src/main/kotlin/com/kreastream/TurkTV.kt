package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class TurkTV : MainAPI() {
    override var mainUrl = ""
    override var name = "Türk TV"
    override var hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Live)
    override var lang = "tr"

    private val providers by lazy {
        APIHolder.apis.filterIsInstance<MainAPI>().filter {
            it.name.equals("TRT1", ignoreCase = true) ||
            it.name.equals("TRT Canlı", ignoreCase = true)
        }
    }

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
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    // ------------------- URL WRAPPING (FIXED) -------------------
    private fun wrap(resp: SearchResponse): SearchResponse = when (resp) {
        is TvSeriesSearchResponse -> {
            // Preserve full relative path
            val fullPath = resp.url.removePrefix("https://www.trt1.com.tr/")
            newTvSeriesSearchResponse(
                resp.name,
                "https://turktv.internal/trt1/$fullPath",
                TvType.TvSeries
            ) {
                posterUrl = resp.posterUrl
            }
        }
        is MovieSearchResponse -> {
            // For live streams, encode the URL
            val encodedUrl = java.net.URLEncoder.encode(resp.url, "UTF-8")
            newMovieSearchResponse(
                resp.name,
                "https://turktv.internal/live/$encodedUrl",
                TvType.Live
            ) {
                posterUrl = resp.posterUrl
            }
        }
        else -> resp
    }

    // ------------------- URL UNWRAPPING (FIXED) -------------------
    private fun unwrap(wrapped: String): String = when {
        wrapped.startsWith("https://turktv.internal/trt1/") -> {
            val fullPath = wrapped.removePrefix("https://turktv.internal/trt1/")
            "https://www.trt1.com.tr/$fullPath"
        }
        wrapped.startsWith("https://turktv.internal/live/") -> {
            val encodedUrl = wrapped.removePrefix("https://turktv.internal/live/")
            java.net.URLDecoder.decode(encodedUrl, "UTF-8")
        }
        else -> wrapped
    }

    // ------------------- LOAD -------------------
    override suspend fun load(url: String): LoadResponse {
        val realUrl = unwrap(url)
        val provider = findProvider(realUrl) ?: throw ErrorLoadingException("No provider for $realUrl")

        return try {
            provider.load(realUrl) ?: throw ErrorLoadingException("Provider returned null response")
        } catch (e: Exception) {
            e.printStackTrace()
            throw ErrorLoadingException("Failed to load: ${e.message}")
        }
    }

    // ------------------- LOAD LINKS -------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val realUrl = unwrap(data)
        val provider = findProvider(realUrl) ?: return false

        return try {
            provider.loadLinks(realUrl, isCasting, subtitleCallback, callback)
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // ------------------- SEARCH -------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val all = mutableListOf<SearchResponse>()
        providers.forEach { p ->
            try {
                val results = p.search(query).orEmpty()
                all.addAll(results.map { wrap(it) })
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return all.distinctBy { it.url }
    }

    // ------------------- ROUTING (IMPROVED) -------------------
    private fun findProvider(url: String): MainAPI? {
        val p = when {
            url.contains("trt1.com.tr") -> providers.find { it.name.contains("TRT1", true) }
            url.contains("medya.trt.com.tr") || url.endsWith(".m3u8") || url.endsWith(".aac") ->
                providers.find { it.name.contains("Canlı", true) }
            else -> null
        }
        println("🔹 TurkTV routed $url → provider=${p?.name}")
        return p
    }

}
