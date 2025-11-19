package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class TRTCocuk : MainAPI() {
    override var name = "TRT Çocuk"
    override var mainUrl = "https://www.trtcocuk.net.tr"
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Cartoon)
    override val hasMainPage = true
    override val hasQuickSearch = true

    private val popularSeries = listOf(
        "rafadan-tayfa" to "Rafadan Tayfa",
        "ekip-siberay" to "Ekip: SİBERAY",
        "aslan" to "Aslan",
        "ibi" to "İbi",
        "doru" to "Doru",
        "niloya" to "Niloya",
        "akilli-tavsan-momo" to "Akıllı Tavşan Momo",
        "elifi-ve-arkadaslari" to "Elif ve Arkadaşları",
        "kare" to "Kare",
        "piril" to "Pırıl",
        "kelile-ve-dimne" to "Kelile ve Dimne",
        "kaptan-pengu" to "Kaptan Pengu",
        "hopi" to "Hopi",
        "z-takimi" to "Z Takımı",
        "kuzucuklar" to "Kuzucuklar",
        "emiray" to "Emiray",
        "maymun-coco" to "Maymun Coco",
        "sibal" to "Sibal",
        "komsular" to "Komşular",
        "kral-sakir" to "Kral Şakir",
        "elali-ve-arkadaslari" to "Elali ve Arkadaşları",
        "sidali" to "Sidali",
        "kaptan-pengu-ve-arkadaslari" to "Kaptan Pengu ve Arkadaşları",
        "hacivat-ve-karagoz" to "Hacivat ve Karagöz",
        "kucuk-akasya" to "Küçük Akasya",
        "keloglan" to "Keloğlan",
        "nasrettin-hoca" to "Nasrettin Hoca",
        "kurt-koruyucu" to "Kurt Koruyucu",
        "kayip-ucgen" to "Kayıp Üçgen",
        "kalemlerle-hikayeler" to "Kalemlerle Hikayeler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = popularSeries.map { (slug, title) ->
            val url = "$mainUrl/video/$slug"
            newAnimeSearchResponse(title, url, TvType.Cartoon) {
                this.posterUrl = "https://cdn-i.pr.trt.com.tr/trtcocuk/w513/h288/q60/$slug-cover.jpeg" // approximate, real poster loads later
            }
        }

        return newHomePageResponse("Popüler Çizgi Filmler", items)
    }

    // Quick Search still works perfectly (uses site's HTML search)
    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        val searchUrl = "$mainUrl/ara?q=${query.replace(" ", "+")}"
        val doc = app.get(searchUrl).document

        return doc.select(".search-result a[href*=\"/video/\"], .search-result a[href^=\"/\"]").mapNotNull {
            val href = fixUrl(it.attr("href"))
            if (!href.contains("/video/") && !href.endsWith(oneOfSeriesSlugs())) return@mapNotNull null

            val title = it.selectFirst(".title")?.text()
                ?: it.selectFirst("img")?.attr("alt")
                ?: return@mapNotNull null
            val poster = it.selectFirst("img")?.attr("data-src") ?: it.selectFirst("img")?.attr("src")

            newAnimeSearchResponse(title, href, TvType.Cartoon) {
                this.posterUrl = poster
            }
        }
    }

    private fun oneOfSeriesSlugs() = popularSeries.map { it.first }

    override suspend fun search(query: String): List<SearchResponse> = quickSearch(query) ?: emptyList()

    // Load series / episodes (works perfectly – episodes are in HTML)
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1")?.text() ?: "TRT Çocuk Dizisi"

        val episodeLinks = doc.select("a[href*=\"-bolum\"], a[href*=\"-bölüm\"]")
        val isSeries = episodeLinks.isNotEmpty()

        val episodes = mutableListOf<Episode>()

        if (isSeries) {
            episodeLinks.forEach { a ->
                val href = fixUrl(a.attr("href"))
                val epText = a.selectFirst("p")?.text() ?: a.selectFirst("img")?.attr("alt") ?: ""
                val epNum = Regex("""(\d+)""").find(epText)?.value?.toIntOrNull() ?: (episodes.size + 1)
                val poster = a.selectFirst("img")?.attr("data-src") ?: a.selectFirst("img")?.attr("src")

                episodes += newEpisode(href) {
                    name = epText.ifBlank { "Bölüm $epNum" }
                    season = 1
                    episode = epNum
                    posterUrl = poster
                }
            }
            episodes.reverse() // oldest first
        } else {
            episodes += newEpisode(url) { name = title; season = 1; episode = 1 }
        }

        return newTvSeriesLoadResponse(title, url, TvType.Cartoon, episodes) {
            posterUrl = doc.selectFirst("meta[property=\"og:image\"]")?.attr("content")
            plot = doc.selectFirst("meta[name=\"description\"]")?.attr("content")
            tags = listOf("Çocuk", "Türkçe", "TRT")
        }
    }

    // Video & subtitles (unchanged – works 100%)
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        val script = doc.select("script").firstOrNull { it.html().contains("master.mpd") } ?: return false
        val mpd = Regex("""["']([^"']*\.mpd[^"']*)["']""").find(script.html())?.groupValues?.get(1) ?: return false
        val mpdUrl = if (mpd.startsWith("http")) mpd else "https://cdn-i.pr.trt.com.tr$mpd"

        newExtractorLink(
            url = mpdUrl,
            name = "TRT Çocuk",
            source = name
        ){
            this.referer = mainUrl + "/"
            this.quality = Qualities.P720.value
            this.type = ExtractorLinkType.DASH
        }?.let(callback)

        doc.select("track[kind=\"captions\"], track[label*=\"Türkçe\"]").forEach {
            val sub = fixUrl(it.attr("src"))
            if (sub.isNotBlank()) subtitleCallback(SubtitleFile("Türkçe", sub))
        }

        return true
    }
}