package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class TurkTV : MainAPI() {
    override var name = "Türk TV"
    override var lang = "tr"
    override var mainUrl = "https://turktv.internal"
    override var hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Live)

    // Dynamically find all channel providers under this package
    private val providers: List<MainAPI> by lazy {
        val all = listOf(
            Trt1(),
            TrtLive(),
            // Later: Atv(), ShowTV(), StarTV(), etc.
        )
        all
    }

    // Gather metadata
    private val channelInfos = providers.mapNotNull { provider ->
        try {
            val infoField = provider::class.java.getDeclaredField("Companion")
            val companion = infoField.get(null)
            val infoProp = companion::class.java.getDeclaredField("info")
            val info = infoProp.get(companion) as? ChannelRegistry.ChannelInfo
            if (info != null) info to provider else null
        } catch (_: Exception) {
            null
        }
    }

    override val mainPage: HomePageList by lazy {
        val sections = mutableListOf<Pair<String, String>>()
        for ((info, _) in channelInfos) {
            info.sections.forEach { (slug, _) ->
                sections += "${info.id}_$slug" to "${info.title} – ${slug.capitalize()}"
            }
        }
        mainPageOf(*sections.toTypedArray())
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val (provider, sectionUrl) = parseRequest(request.data)
        provider ?: return newHomePageResponse(emptyList())
        return provider.getMainPage(page, MainPageRequest(request.name, sectionUrl, false))
            ?: newHomePageResponse(emptyList())
    }

    private fun parseRequest(data: String): Pair<MainAPI?, String> {
        val parts = data.split("_", limit = 2)
        val id = parts.getOrNull(0)
        val slug = parts.getOrNull(1)
        val entry = channelInfos.find { it.first.id == id }
        val url = entry?.first?.sections?.get(slug)
        return entry?.second to (url ?: "")
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
            val info = channelInfos.find { it.second == p }?.first
            info?.let { url.contains(it.id, ignoreCase = true) } ?: false
        }
    }
}
