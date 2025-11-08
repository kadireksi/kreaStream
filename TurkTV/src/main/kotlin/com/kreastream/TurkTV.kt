package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class TurkTV : MainAPI() {
    override var mainUrl = "https://turktv.com"
    override var name = "Türk TV"
    override var hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Live)
    override var lang = "tr"

    // Auto-discover all MainAPI subclasses in this package
    private val providers by lazy {
        listOf(
            Trt1(),
            TrtLive(),
            // Add more here later: Atv(), ShowTv(), StarTv(), etc.
        )
    }

    // Cache main pages to avoid rebuilding
    private val mainPageCache = mutableMapOf<String, List<SearchResponse>>()

    override val mainPage = mainPageOf(
        "trt1_series" to "TRT 1 - Güncel Diziler",
        "trt1_archive" to "TRT 1 - Eski Diziler",
        "live_tv" to "TRT Canlı TV",
        "live_radio" to "TRT Canlı Radyo"
        // Future: "atv_series" to "ATV Diziler", etc.
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val key = "${request.data}_$page"
        if (mainPageCache.containsKey(key)) {
            return newHomePageResponse(
                listOf(HomePageList(request.name, mainPageCache[key]!!, isHorizontalImages = request.data.startsWith("live_")))
            )
        }

        val items = when (request.data) {
            "trt1_series" -> getProviderSection(Trt1::class.java.name, "https://www.trt1.com.tr/diziler?archive=false&order=title_asc", page)
            "trt1_archive" -> getProviderSection(Trt1::class.java.name, "https://www.trt1.com.tr/diziler?archive=true&order=title_asc", page)
            "live_tv" -> getProviderSection(TrtLive::class.java.name, "tv", 1)
            "live_radio" -> getProviderSection(TrtLive::class.java.name, "radio", 1)
            else -> emptyList()
        }

        mainPageCache[key] = items
        return newHomePageResponse(
            listOf(HomePageList(request.name, items, isHorizontalImages = request.data.startsWith("live_")))
        )
    }

    private suspend fun getProviderSection(providerClassName: String, url: String, page: Int): List<SearchResponse> {
        val provider = providers.find { it::class.java.name == providerClassName } ?: return emptyList()
        return try {
            provider.getMainPage(page, MainPageRequest("", url, false))
                .items?.firstOrNull()?.list ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    // DYNAMIC ROUTING: Find provider by URL pattern
    override suspend fun load(url: String): LoadResponse {
        val normalized = normalizeUrl(url)
        val provider = findProviderForUrl(normalized)
            ?: throw ErrorLoadingException("No provider supports URL: $normalized")

        return provider.load(normalized)
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
                results.addAll(provider.search(query))
            } catch (e: Exception) { /* Ignore */ }
        }
        return results.distinctBy { it.url }
    }

    // Find provider that can handle this URL
    private fun findProviderForUrl(url: String): MainAPI? {
        return providers.find { provider ->
            when (provider) {
                is Trt1 -> url.contains("trt1.com.tr/diziler/")
                is TrtLive -> url.contains(".m3u8") || url.contains(".aac") || url.contains("trt.net.tr")
                // Future: is Atv -> url.contains("atv.com.tr")
                else -> false
            }
        }
    }

    // Normalize all URLs
    private fun normalizeUrl(url: String): String {
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "https://www.trt1.com.tr$url"
            else -> url
        }.let { fixTrt1Slug(it) }
    }

    private fun fixTrt1Slug(url: String): String {
        return if (url.matches(Regex(".*/diziler/[^/]+"))) {
            url
        } else if (url.contains("/diziler/") && !url.contains("trt1.com.tr")) {
            "https://www.trt1.com.tr$url"
        } else url
    }
}