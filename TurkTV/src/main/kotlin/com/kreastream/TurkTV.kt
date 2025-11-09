package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class TurkTV : MainAPI() {
    override var mainUrl = ""
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
        } catch (e: Exception) { 
            e.printStackTrace()
            emptyList() 
        }
    }

    // ------------------- LOAD -------------------
    override suspend fun load(url: String): LoadResponse {
        val provider = findProvider(url) ?: throw ErrorLoadingException("No provider for $url")
        
        return try {
            provider.load(url) ?: throw ErrorLoadingException("Provider returned null response")
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
        val provider = findProvider(data) ?: return false
        
        return try {
            provider.loadLinks(data, isCasting, subtitleCallback, callback)
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
        return when {
            url.contains("trt1.com.tr/diziler/") -> providers.find { it is Trt1 }
            url.contains(".m3u8") || url.contains(".aac") || url.contains("medya.trt.com.tr") -> providers.find { it is TrtLive }
            else -> null
        }
    }
}