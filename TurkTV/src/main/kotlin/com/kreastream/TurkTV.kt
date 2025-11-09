package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class TurkTV : MainAPI() {
    override var name = "Türk TV"
    override var lang = "tr"
    override var mainUrl = "https://turktv.internal"
    override var hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Live)

    // manually list your providers here; later you can add reflection
    private val providers: List<MainAPI> by lazy {
        listOf(Trt1(), TrtLive())
    }

    // Load metadata dynamically
    private val channelInfos: List<Pair<ChannelRegistry.ChannelInfo, MainAPI>> by lazy {
        providers.mapNotNull { provider ->
            try {
                val companionField = provider::class.java.getDeclaredField("Companion")
                val companion = companionField.get(null)
                val infoField = companion::class.java.getDeclaredField("info")
                val info = infoField.get(companion) as? ChannelRegistry.ChannelInfo
                if (info != null) info to provider else null
            } catch (_: Exception) {
                null
            }
        }
    }

    // Cloudstream expects List<MainPageData>
    override val mainPage: List<MainPageData> by lazy {
        channelInfos.flatMap { (info, _) ->
            info.sections.map { (slug, _) ->
                MainPageData("${info.id}_$slug", "${info.title} – ${slug.replaceFirstChar { it.uppercase() }}")
            }
        }
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
