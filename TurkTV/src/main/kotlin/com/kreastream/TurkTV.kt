package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class TurkTV : MainAPI() {
    override var name = "Türk TV"
    override var lang = "tr"
    override var mainUrl = "https://turktv.internal"
    override var hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Live)

    private val providers: List<MainAPI> by lazy {
        listOf(
            Trt1(),
            TrtLive()
        )
    }

    override val mainPage = mainPageOf(
        "trt1_current" to "TRT 1 – Güncel Diziler",
        "trt1_archive" to "TRT 1 – Eski Diziler",
        "trt_live"     to "TRT Canlı Yayınlar"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val (provider, section) = parseRequest(request.data)
        provider ?: return newHomePageResponse(emptyList())

        return provider.getMainPage(page, MainPageRequest(request.name, section, false))
            ?: newHomePageResponse(emptyList())
    }

    private fun parseRequest(data: String): Pair<MainAPI?, String> {
        return when (data) {
            "trt1_current" -> providers.find { it is Trt1 } to "https://www.trt1.com.tr/diziler?archive=false"
            "trt1_archive" -> providers.find { it is Trt1 } to "https://www.trt1.com.tr/diziler?archive=true"
            "trt_live"     -> providers.find { it is TrtLive } to "tv"
            else -> null to ""
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val provider = findProvider(url) ?: throw ErrorLoadingException("Provider not found for $url")
        return provider.load(url)
            ?: throw ErrorLoadingException("Provider returned null for $url")
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val provider = findProvider(data) ?: return false
        return provider.loadLinks(data, isCasting, subtitleCallback, callback)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        for (p in providers) {
            try {
                val res = p.search(query)
                if (res != null) results.addAll(res)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return results.distinctBy { it.url }
    }

    private fun findProvider(url: String): MainAPI? {
        return providers.find { p ->
            when (p) {
                is Trt1 -> url.contains("trt1.com.tr", ignoreCase = true)
                is TrtLive -> url.contains("medya.trt.com.tr", ignoreCase = true)
                else -> false
            }
        }
    }
}
