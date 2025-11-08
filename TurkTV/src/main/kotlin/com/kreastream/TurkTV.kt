package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class TurkTV : MainAPI() {
    override var mainUrl = "https://turktv.com"
    override var name = "Türk TV"
    override var hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Live)
    override var lang = "tr"

    override fun hasValidUrl(url: String): Boolean = true

    private val providers by lazy {
        listOf(
            Trt1(),
            TrtLive()
        )
    }

    override val mainPage = mainPageOf(
        "trt1_series" to "TRT 1 - Güncel Diziler",
        "trt1_archive" to "TRT 1 - Eski Diziler",
        "live_tv" to "TRT Canlı TV",
        "live_radio" to "TRT Canlı Radyo"
    )

    private val mainPageCache = mutableMapOf<String, List<SearchResponse>>()

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val cacheKey = "${request.data}_$page"
        val cached = mainPageCache[cacheKey]
        if (cached != null) {
            return newHomePageResponse(
                listOf(
                    HomePageList(
                        name = request.name,
                        list = cached,
                        isHorizontalImages = request.data.startsWith("live_")
                    )
                )
            )
        }

        val items = when (request.data) {
            "trt1_series" ->
                getProviderSection(
                    Trt1::class.java.name,
                    "https://www.trt1.com.tr/diziler?archive=false&order=title_asc",
                    page
                )
            "trt1_archive" ->
                getProviderSection(
                    Trt1::class.java.name,
                    "https://www.trt1.com.tr/diziler?archive=true&order=title_asc",
                    page
                )
            "live_tv" ->
                getProviderSection(TrtLive::class.java.name, "tv", 1)
            "live_radio" ->
                getProviderSection(TrtLive::class.java.name, "radio", 1)
            else -> emptyList()
        }

        mainPageCache[cacheKey] = items
        return newHomePageResponse(
            listOf(
                HomePageList(
                    name = request.name,
                    list = items,
                    isHorizontalImages = request.data.startsWith("live_")
                )
            )
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
                ?.items?.firstOrNull()?.list ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val normalized = normalizeUrl(url)
        val provider = findProviderForUrl(normalized)
            ?: throw ErrorLoadingException("No provider supports URL: $normalized")

        return provider.load(normalized)!!
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val normalized = normalizeUrl(data)
        val provider = findProviderForUrl(normalized) ?: return false
        return provider.loadLinks(normalized, isCasting, subtitleCallback, callback)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        providers.forEach { provider ->
            try {
                results.addAll(provider.search(query).orEmpty())
            } catch (_: Exception) { /* ignore */ }
        }
        return results.distinctBy { it.url }
    }

    private fun findProviderForUrl(url: String): MainAPI? {
        return providers.find { provider ->
            when (provider) {
                is Trt1 -> url.contains("trt1.com.tr/diziler/")
                is TrtLive -> url.contains(".m3u8") || url.contains(".aac") || url.contains("trt.net.tr")
                else -> false
            }
        }
    }

    private fun normalizeUrl(url: String): String {
        val base = when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "https://www.trt1.com.tr$url"
            else -> url
        }
        return fixTrt1Slug(base)
    }

    private fun fixTrt1Slug(url: String): String {
        return if (url.contains("/diziler/") && !url.contains("trt1.com.tr")) {
            "https://www.trt1.com.tr$url"
        } else {
            url
        }
    }
}