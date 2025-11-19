package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class TRTCocuk : MainAPI() {
    override var name = "TRT Çocuk"
    override var mainUrl = "https://www.trtcocuk.net.tr"
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Cartoon)
    override val hasMainPage = true
    override val hasQuickSearch = true

    // TÜM DİZİLER – ALFABETİK SIRALI (A → Z)
    private val popularSeries = listOf(
        "akilli-tavsan-momo" to "Akıllı Tavşan Momo",
        "arkadasim-max" to "Arkadaşım Max",
        "aslan" to "Aslan",
        "bizimkiler" to "Bizimkiler",
        "canim-kardesim" to "Canım Kardeşim",
        "cille" to "Cille",
        "doru" to "Doru",
        "ekip-siberay" to "Ekip: SİBERAY",
        "elali-ve-arkadaslari" to "Elali ve Arkadaşları",
        "elifi-ve-arkadaslari" to "Elif ve Arkadaşları",
        "emiray" to "Emiray",
        "emircan-ve-arkadaslari" to "Emircan ve Arkadaşları",
        "fısildayan-kaya" to "Fısıldayan Kaya",
        "hacivat-ve-karagoz" to "Hacivat ve Karagöz",
        "harika-kanatlar" to "Harika Kanatlar",
        "hayalimdeki-oyuncak" to "Hayalimdeki Oyuncak",
        "hopi" to "Hopi",
        "ibi" to "İbi",
        "karagoz-amca" to "Karagöz Amca",
        "kare" to "Kare",
        "kaptan-pengu" to "Kaptan Pengu",
        "kaptan-pengu-ve-arkadaslari" to "Kaptan Pengu ve Arkadaşları",
        "kaptan-pengu-ve-arkadaslari-2" to "Kaptan Pengu 2. Sezon",
        "kelile-ve-dimne" to "Kelile ve Dimne",
        "keloglan" to "Keloğlan",
        "keloğlan-masallari" to "Keloğlan Masalları",
        "kirmizi-kamy" to "Kırmızı Kamyon",
        "komsular" to "Komşular",
        "köstebekgiller" to "Köstebekgiller",
        "kral-sakir" to "Kral Şakir",
        "kucuk-akasya" to "Küçük Akasya",
        "kucuk-prens" to "Küçük Prens",
        "kukuli" to "Kukuli",
        "kuzucuklar" to "Kuzucuklar",
        "macera-adasi" to "Macera Adası",
        "masal-olsun" to "Masal Olsun",
        "maymun-coco" to "Maymun Coco",
        "minika" to "Minika (Seçkiler)",
        "nasrettin-hoca" to "Nasrettin Hoca",
        "niloya" to "Niloya",
        "pepee" to "Pepee",
        "piril" to "Pırıl",
        "rafadan-tayfa" to "Rafadan Tayfa",
        "rafadan-tayfa-arkeolojik-park" to "Rafadan Tayfa: Arkeolojik Park",
        "rafadan-tayfa-dehliz-maceralari" to "Rafadan Tayfa: Dehliz Macerası",
        "rafadan-tayfa-galaktik-tayfa" to "Rafadan Tayfa: Galaktik Tayfa",
        "rafadan-tayfa-golge" to "Rafadan Tayfa: Gölge",
        "rafadan-tayfa-kapadokya" to "Rafadan Tayfa: Kapadokya",
        "rafadan-tayfa-torso" to "Rafadan Tayfa: Torso",
        "robo-pat" to "Robo Pat",
        "sibal" to "Sibal",
        "sidali" to "Sidali",
        "sihirli-annem" to "Sihirli Annem (Çocuk Versiyonu)",
        "super-fikir" to "Süper Fikir",
        "trt-cocuk-dergisi" to "TRT Çocuk Dergisi Çizgi Filmleri",
        "visne-bahcesi" to "Vişne Bahçesi",
        "yesil-vadi" to "Yeşil Vadi",
        "yusuf-un-dunyasi" to "Yusuf'un Dünyası",
        "z-takimi" to "Z Takımı"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = popularSeries.map { (slug, title) ->
            newAnimeSearchResponse(title, "$mainUrl/video/$slug", TvType.Cartoon) {
                posterUrl = "https://cdn-i.pr.trt.com.tr/trtcocuk/w513/h288/q60/$slug.jpeg"
            }
        }
        return newHomePageResponse("Tüm Çizgi Filmler (A→Z)", items)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        val url = "$mainUrl/ara?q=${query.replace(" ", "+")}"
        val doc = app.get(url).document
        return doc.select(".search-result a[href*=\"/video/\"]").mapNotNull { el ->
            val href = fixUrl(el.attr("href"))
            val title = el.selectFirst(".title")?.text()
                ?: el.selectFirst("img")?.attr("alt") ?: return@mapNotNull null
            val poster = el.selectFirst("img")?.attr("data-src")
                ?: el.selectFirst("img")?.attr("src")

            newAnimeSearchResponse(title, href, TvType.Cartoon) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> = quickSearch(query) ?: emptyList()

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1")?.text()
            ?: doc.selectFirst("meta[property=\"og:title\"]")?.attr("content")?.removeSuffix(" | TRT Çocuk")?.trim()
            ?: "TRT Çocuk"

        val episodeLinks = doc.select("a[href*=\"-bolum\"], a[href*=\"-bölüm\"]")
        val isSeries = episodeLinks.isNotEmpty()

        val episodes = mutableListOf<Episode>()

        if (isSeries) {
            episodeLinks.forEach { a ->
                val href = fixUrl(a.attr("href"))
                val epText = a.selectFirst("p")?.text() ?: a.selectFirst("img")?.attr("alt") ?: "Bölüm"
                val epNum = Regex("""(\d+)""").find(epText)?.value?.toIntOrNull() ?: (episodes.size + 1)
                val poster = a.selectFirst("img")?.attr("data-src") ?: a.selectFirst("img")?.attr("src")

                episodes += newEpisode(href) {
                    name = epText.trim()
                    season = 1
                    episode = epNum
                    posterUrl = poster
                }
            }
            episodes.reverse() // En yeniden en eskiye → biz en eskiden başlayalım
        } else {
            episodes += newEpisode(url) {
                name = title
                season = 1
                episode = 1
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.Cartoon, episodes) {
            posterUrl = doc.selectFirst("meta[property=\"og:image\"]")?.attr("content")
            plot = doc.selectFirst("meta[name=\"description\"]")?.attr("content")
            tags = listOf("Çocuk", "Türkçe", "TRT")
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        val script = doc.select("script").firstOrNull { it.html().contains("master.mpd") } ?: return false
        val mpdMatch = Regex("""["']([^"']*master\.mpd[^"']*)["']""").find(script.html()) ?: return false
        val mpdUrl = if (mpdMatch.groupValues[1].startsWith("http")) mpdMatch.groupValues[1]
                     else "https://cdn-i.pr.trt.com.tr${mpdMatch.groupValues[1]}"

        newExtractorLink(
            url = mpdUrl,
            name = "TRT Çocuk",
            source = name
        ) {
            this.referer = "$mainUrl/"
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