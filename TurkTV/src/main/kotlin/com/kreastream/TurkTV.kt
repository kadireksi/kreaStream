package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class TurkTV : MainAPI() {
    // Use a dummy domain that ALL internal URLs will be rewritten to
    override var mainUrl = "https://turktv.internal"
    override var name = "Türk TV"
    override var hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Live)
    override var lang = "tr"

    // --------------------------------------------------------------------- //
    //  All providers
    // --------------------------------------------------------------------- //
    private val providers by lazy {
        listOf(
            Trt1(),
            TrtLive()
            // Add: Atv(), ShowTv(), etc.
        )
    }

    // --------------------------------------------------------------------- //
    //  Main page
    // --------------------------------------------------------------------- //
    override val mainPage = mainPageOf(
        "trt1_series" to "TRT 1 - Güncel Diziler",
        "trt1_archive" to "TRT 1 - Eski Diziler",
        "live_tv" to "TRT Canlı TV",
        "live_radio" to "TRT Canlı Radyo"
    )

    private val mainPageCache = mutableMapOf<String, List<SearchResponse>>()

    // --------------------------------------------------------------------- //
    //  getMainPage
    // --------------------------------------------------------------------- //
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val cacheKey = "${request.data}_$page"
        val cached = mainPageCache[cacheKey]
        if (cached != null) {
            return newHomePageResponse(
                listOf(HomePageList(request.name, cached, isHorizontalImages = request.data.startsWith("live_")))
            )
        }

        val items = when (request.data) {
            "trt1_series" ->
                getProviderSection(Trt1::class.java.name, "https://www.trt1.com.tr/diziler?archive=false&order=title_asc", page)
            "trt1_archive" ->
                getProviderSection(Trt1::class.java.name, "https://www.trt1.com.tr/diziler?archive=true&order=title_asc", page)
            "live_tv" ->
                getProviderSection(TrtLive::class.java.name, "tv", 1)
            "live_radio" ->
                getProviderSection(TrtLive::class.java.name, "radio", 1)
            else -> emptyList()
        }

        mainPageCache[cacheKey] = items
        return newHomePageResponse(
            listOf(HomePageList(request.name, items, isHorizontalImages = request.data.startsWith("live_")))
        )
    }

    private suspend fun getProviderSection(
        providerClassName: String,
        url: String,
        page: Int
    ): List<SearchResponse> {
        val provider = providers.find { it::class.java.name == providerClassName } ?: return emptyList()
        return try {
            provider.getMainPage(page, MainPageRequest("", url, false))
                ?.items?.firstOrNull()?.list?.map { wrapUrl(it) } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    // --------------------------------------------------------------------- //
    //  Wrap ALL URLs with our internal domain
    // --------------------------------------------------------------------- //
    private fun wrapUrl(response: SearchResponse): SearchResponse {
        return when (response) {
            is TvSeriesSearchResponse -> {
                newTvSeriesSearchResponse(response.name, "https://turktv.internal/trt1${response.url}", TvType.TvSeries) {
                    this.posterUrl = response.posterUrl
                }
            }
            is MovieSearchResponse -> {
                newMovieSearchResponse(response.name, "https://turktv.internal/live${response.url}", TvType.Live) {
                    this.posterUrl = response.posterUrl
                }
            }
            else -> response
        }
    }

    private fun unwrapUrl(wrapped: String): String {
        return when {
            wrapped.startsWith("https://turktv.internal/trt1") -> wrapped.removePrefix("https://turktv.internal/trt1")
            wrapped.startsWith("https://turktv.internal/live") -> wrapped.removePrefix("https://turktv.internal/live")
            else -> wrapped
        }
    }

    // --------------------------------------------------------------------- //
    //  load() – unwrap and delegate
    // --------------------------------------------------------------------- //
    override suspend fun load(url: String): LoadResponse {
        val realUrl = unwrapUrl(url)
        val provider = findProviderForUrl(realUrl)
            ?: throw ErrorLoadingException("No provider for: $realUrl")

        return provider.load(realUrl)!!
    }

    // --------------------------------------------------------------------- //
    //  loadLinks()
    // --------------------------------------------------------------------- //
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val realUrl = unwrapUrl(data)
        val provider = findProviderForUrl(realUrl) ?: return false
        return provider.loadLinks(realUrl, isCasting, subtitleCallback, callback)
    }

    // --------------------------------------------------------------------- //
    //  search()
    // --------------------------------------------------------------------- //
    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        providers.forEach { provider ->
            try {
                results.addAll(provider.search(query).orEmpty().map { wrapUrl(it) })
            } catch (_: Exception) {}
        }
        return results.distinctBy { it.url }
    }

    // --------------------------------------------------------------------- //
    //  Routing
    // --------------------------------------------------------------------- //
    private fun findProviderForUrl(url: String): MainAPI? {
        return providers.find { provider ->
            when (provider) {
                is Trt1 -> url.contains("trt1.com.tr/diziler/")
                is TrtLive -> url.contains(".m3u8") || url.contains(".aac") || url.contains("trt.net.tr")
                else -> false
            }
        }
    }
}