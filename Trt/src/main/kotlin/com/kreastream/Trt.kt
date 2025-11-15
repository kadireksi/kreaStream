package com.kreastream

import kotlin.text.RegexOption
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import kotlinx.coroutines.delay
import android.util.Log
import java.net.URI
import java.net.URL

class Trt : MainAPI() {
    override var mainUrl = "https://www.tabii.com"
    override var name = "TRT"
    override val supportedTypes = setOf(TvType.Live, TvType.TvSeries)
    override var lang = "tr"
    override var hasMainPage = true

    private val tabiiUrl = "https://www.tabii.com/tr"
    private val trt1Url = "https://www.trt1.com.tr"
    private val trtCocukBase = "https://www.trtcocuk.net.tr"
    private val liveBase = "$tabiiUrl/watch/live"

    private val dummyTvUrl = "$tabiiUrl/tv"
    private val dummyRadioUrl = "$tabiiUrl/radio"

    override val mainPage = mainPageOf(
        "series" to "Güncel Diziler",
        "archive" to "Eski Diziler",
        "programs" to "Programlar",
        "programs_archive" to "Arşiv Programlar",
        "live" to "TRT Tv & Radyo",
        "trtcocuk_videos" to "TRT Çocuk Videolar"
    )

    // Ortak arayüz
    sealed class LiveChannel {
        abstract val name: String
        abstract val slug: String
        abstract val streamUrl: String
        abstract val logoUrl: String
        abstract val description: String
    }

    data class TvChannel(
        override val name: String,
        override val slug: String,
        override val streamUrl: String,
        override val logoUrl: String,
        override val description: String = ""
    ) : LiveChannel()

    data class RadioChannel(
        override val name: String,
        override val slug: String,
        override val streamUrl: String,
        override val logoUrl: String,
        override val description: String = ""
    ) : LiveChannel()

    // ----------------------------
    // Helper utilities
    // ----------------------------
    private fun makeAbsolute(base: String, href: String): String {
        return try {
            if (href.startsWith("http://") || href.startsWith("https://")) href
            else {
                val baseUri = URI(base)
                baseUri.resolve(href).toString()
            }
        } catch (e: Exception) {
            if (href.startsWith("/")) {
                val u = URL(base)
                "${u.protocol}://${u.host}$href"
            } else href
        }
    }

    // ----------------------------
    // Tabii live channels (TV)
    // ----------------------------
    private suspend fun getTvChannels(): List<TvChannel> {
        val result = mutableListOf<TvChannel>()
        try {
            val sample = "$liveBase/trt1?trackId=150002"
            val response = app.get(sample)
            val doc = response.document
            val nextData = doc.selectFirst("#__NEXT_DATA__")?.data() ?: return emptyList()

            val json = JSONObject(nextData)
            val liveChannels = json.getJSONObject("props")
                .getJSONObject("pageProps")
                .getJSONArray("liveChannels")

            for (i in 0 until liveChannels.length()) {
                val ch = liveChannels.getJSONObject(i)
                val name = ch.optString("title")
                val slug = ch.optString("slug")

                var logoUrl = ""
                val images = ch.optJSONArray("images")
                if (images != null) {
                    for (j in 0 until images.length()) {
                        val img = images.getJSONObject(j)
                        if (img.optString("imageType") == "logo") {
                            val imgName = img.optString("name")
                            if (imgName.isNotBlank()) {
                                logoUrl = "https://cms-tabii-public-image.tabii.com/int/$imgName"
                                break
                            }
                        }
                    }
                }
                if (logoUrl.isBlank()) continue

                var streamUrl = ""
                val media = ch.optJSONArray("media")
                if (media != null) {
                    for (j in 0 until media.length()) {
                        val m = media.getJSONObject(j)
                        if (m.optString("type") == "hls" && m.optString("drmSchema") == "clear") {
                            streamUrl = m.optString("url")
                            break
                        }
                    }
                }
                if (streamUrl.isBlank()) continue

                if (!name.contains("tabii", ignoreCase = true)) {
                    result += TvChannel(name, slug, streamUrl, logoUrl, name)
                }
            }
        } catch (e: Exception) {
            Log.e("TRT", "getTvChannels error: ${e.message}")
        }
        return result
    }

    // ----------------------------
    // Radio channels (hardcoded)
    // ----------------------------
    private suspend fun getRadioChannels(): List<RadioChannel> {
        return listOf(
            RadioChannel(
                name = "TRT FM",
                slug = "trt-fm",
                streamUrl = "https://trt.radyotvonline.net/trt_fm.aac",
                logoUrl = "https://cdn-i.pr.trt.com.tr/trtdinle/w480/h360/q70/12467418.jpeg",
                description = "Türkçe Pop ve güncel müzik"
            ),
            RadioChannel(
                name = "TRT Radyo 1",
                slug = "trt-radyo-1",
                streamUrl = "https://trt.radyotvonline.net/trt_1.aac",
                logoUrl = "https://cdn-i.pr.trt.com.tr/trtdinle/w480/h360/q70/12467415.jpeg",
                description = "Haber, kültür ve klasik müzik"
            ),
            RadioChannel(
                name = "TRT Nağme",
                slug = "trt-nagme",
                streamUrl = "https://trt.radyotvonline.net/trt_nagme.aac",
                logoUrl = "https://cdn-i.pr.trt.com.tr/trtdinle/w480/h360/q70/12467419.jpeg",
                description = "Türk sanat müziği"
            ),
            RadioChannel(
                name = "TRT Türkü",
                slug = "trt-turku",
                streamUrl = "https://trt.radyotvonline.net/trt_turku.aac",
                logoUrl = "https://cdn-i.pr.trt.com.tr/trtdinle/w480/h360/q70/12467420.jpeg",
                description = "Türk halk müziği"
            ),
            RadioChannel(
                name = "TRT Radyo 3",
                slug = "trt-radyo-3",
                streamUrl = "https://trt.radyotvonline.net/trt_3.aac",
                logoUrl = "https://cdn-i.pr.trt.com.tr/trtdinle/w480/h360/q70/12467416.jpeg",
                description = "Yabancı müzik ve kültür"
            ),
            RadioChannel(
                name = "TRT Kurdi",
                slug = "trt-kurdi",
                streamUrl = "https://rd-trtkurdi.medya.trt.com.tr/master_128.m3u8",
                logoUrl = "https://cdn-i.pr.trt.com.tr/trtdinle/w480/h360/q70/12467508.jpeg",
                description = "Kürtçe yayın"
            )
        )
    }

    // ----------------------------
    // TRT Çocuk videos
    // ----------------------------
    private suspend fun getTrtCocukVideos(page: Int = 1): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        val url = if (page <= 1) "$trtCocukBase/video" else "$trtCocukBase/video?page=$page"
        try {
            val doc = app.get(url, timeout = 15).document
            val anchors = doc.select("a[href*='/video/']")
            for (el in anchors) {
                val hrefRaw = el.attr("href")
                if (hrefRaw.isNullOrBlank()) continue
                val href = makeAbsolute(trtCocukBase, hrefRaw)
                if (out.any { it.url == href }) continue

                val title = el.selectFirst("h3, h2, .title, .card-title")?.text()?.trim()
                    ?: el.attr("title")?.takeIf { it.isNotBlank() }
                    ?: el.text()?.trim() ?: continue

                var poster = el.selectFirst("img")?.absUrl("src")
                    ?: el.parent()?.selectFirst("img")?.absUrl("src")
                if (!poster.isNullOrBlank()) {
                    poster = poster.replace(Regex("webp/w\\d+/h\\d+"), "webp/w600/h338")
                }

                out += newTvSeriesSearchResponse(title, href) {
                    this.posterUrl = poster
                }
            }
        } catch (e: Exception) {
            Log.e("TRT", "getTrtCocukVideos failed: ${e.message}")
        }
        return out
    }

    private suspend fun loadTrtCocukVideoPage(url: String): LoadResponse {
        try {
            val doc = app.get(url, timeout = 15).document
            val title = doc.selectFirst("h1, .page-title, .title")?.text()?.trim() ?: "TRT Çocuk Video"
            val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: doc.selectFirst("img")?.absUrl("src")
            val desc = doc.selectFirst("meta[name=description]")?.attr("content")
                ?: doc.selectFirst(".description")?.text()?.trim() ?: ""

            val episode = newEpisode(url) {
                name = title
                posterUrl = poster
                episode = 1
                season = 1
                description = desc
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, listOf(episode)) {
                this.posterUrl = poster
                this.plot = desc
            }
        } catch (e: Exception) {
            throw ErrorLoadingException("TRT Çocuk video yüklenemedi: ${e.message}")
        }
    }

    // ----------------------------
    // TRT1 Series & Programs
    // ----------------------------
    private suspend fun getTrtSeries(archive: Boolean = false, page: Int = 1): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        try {
            val url = if (page == 1) "$trt1Url/diziler?archive=$archive&order=title_asc"
            else "$trt1Url/diziler/$page?archive=$archive&order=title_asc"
            val doc = app.get(url, timeout = 15).document
            val cards = doc.select("div.grid_grid-wrapper__elAnh > div.h-full.w-full > a")
            for (el in cards) {
                val title = el.selectFirst("div.card_card-title__IJ9af")?.text()?.trim() ?: continue
                val href = makeAbsolute(trt1Url, el.attr("href"))
                var poster = el.selectFirst("img")?.absUrl("src")
                if (!poster.isNullOrBlank()) {
                    poster = poster.replace(Regex("webp/w\\d+/h\\d+"), "webp/w600/h338").replace("/q75/", "/q85/")
                }
                out += newTvSeriesSearchResponse(title, href) { this.posterUrl = poster }
            }
        } catch (e: Exception) {
            Log.e("TRT", "getTrtSeries failed: ${e.message}")
        }
        return out
    }

    private suspend fun getTrtPrograms(archive: Boolean = false, page: Int = 1): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        try {
            val url = if (page == 1) "$trt1Url/programlar?archive=$archive&order=title_asc"
            else "$trt1Url/programlar/$page?archive=$archive&order=title_asc"
            val doc = app.get(url, timeout = 15).document
            val cards = doc.select("div.grid_grid-wrapper__elAnh > div.h-full.w-full > a")
            for (el in cards) {
                val title = el.selectFirst("div.card_card-title__IJ9af")?.text()?.trim() ?: continue
                val href = makeAbsolute(trt1Url, el.attr("href"))
                var poster = el.selectFirst("img")?.absUrl("src")
                if (!poster.isNullOrBlank()) {
                    poster = poster.replace(Regex("webp/w\\d+/h\\d+"), "webp/w600/h338").replace("/q75/", "/q85/")
                }
                out += newTvSeriesSearchResponse(title, href) { this.posterUrl = poster }
            }
        } catch (e: Exception) {
            Log.e("TRT", "getTrtPrograms failed: ${e.message}")
        }
        return out
    }

    // ----------------------------
    // Main page
    // ----------------------------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = when (request.data) {
            "live" -> listOf(
                newTvSeriesSearchResponse("TRT TV Kanalları", dummyTvUrl, TvType.Live) {
                    this.posterUrl = "https://www.trt.net.tr/logos/our-logos/corporate/trt.png"
                    this.year = 1964
                },
                newTvSeriesSearchResponse("TRT Radyo Kanalları", dummyRadioUrl, TvType.Live) {
                    this.posterUrl = "https://www.trtdinle.com/trt-dinle-fb-share.jpg"
                    this.year = 1927
                }
            )
            "series" -> getTrtSeries(archive = false, page = page)
            "archive" -> getTrtSeries(archive = true, page = page)
            "programs" -> getTrtPrograms(archive = false, page = page)
            "programs_archive" -> getTrtPrograms(archive = true, page = page)
            "trtcocuk_videos" -> getTrtCocukVideos(page)
            else -> emptyList()
        }

        val hasNext = request.data in listOf("series", "archive", "programs", "programs_archive", "trtcocuk_videos") && items.isNotEmpty()
        return newHomePageResponse(HomePageList(request.name, items, true), hasNext)
    }

    // ----------------------------
    // Load
    // ----------------------------
    override suspend fun load(url: String): LoadResponse {
        if (url == dummyTvUrl) {
            val channels = getTvChannels()
            return buildLiveTVResponse(channels)
        }

        if (url == dummyRadioUrl) {
            val channels = getRadioChannels()
            return buildLiveRadioResponse(channels)
        }

        if (url.contains(".m3u8", ignoreCase = true) || url.contains(".aac", ignoreCase = true)) {
            return loadIndividualChannel(url)
        }

        if (url.contains("trtcocuk.net.tr/video", ignoreCase = true)) {
            return loadTrtCocukVideoPage(url)
        }

        try {
            val doc = app.get(url, timeout = 15).document
            val title = doc.selectFirst("h1")?.text()?.trim() ?: throw ErrorLoadingException("Başlık bulunamadı")
            val plot = doc.selectFirst("meta[name=description]")?.attr("content") ?: ""
            var poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            if (!poster.isNullOrBlank()) {
                poster = poster.replace(Regex("webp/w\\d+/h\\d+"), "webp/w600/h338").replace("/q75/", "/q85/")
            }

            val isSeries = url.contains("/diziler/")
            val isProgram = url.contains("/programlar/")
            if (!isSeries && !isProgram) {
                val episode = newEpisode(url) {
                    name = title
                    posterUrl = poster
                    episode = 1
                    season = 1
                    description = plot
                }
                return newTvSeriesLoadResponse(title, url, TvType.TvSeries, listOf(episode)) {
                    this.posterUrl = poster
                    this.plot = plot
                }
            }

            val prefix = if (isSeries) "$trt1Url/diziler/" else "$trt1Url/programlar/"
            val seriesSlug = url.removePrefix(prefix).substringBefore("/")

            val episodes = mutableListOf<Episode>()
            var pageNum = 1
            var more = true
            while (more && pageNum <= 30) {
                try {
                    val epUrl = if (pageNum == 1) {
                        "$trt1Url/${if (isSeries) "diziler" else "programlar"}/$seriesSlug/bolum"
                    } else {
                        "$trt1Url/${if (isSeries) "diziler" else "programlar"}/$seriesSlug/bolum/$pageNum"
                    }
                    val epDoc = app.get(epUrl, timeout = 10).document
                    val pageEps = epDoc.select("div.grid_grid-wrapper__elAnh > div.h-full.w-full > a")
                    if (pageEps.isEmpty()) {
                        more = false
                        break
                    }
                    for (el in pageEps) {
                        val epTitle = el.selectFirst("div.card_card-title__IJ9af")?.text()?.trim() ?: continue
                        val href = makeAbsolute(trt1Url, el.attr("href"))
                        var img = el.selectFirst("img")?.absUrl("src")
                        if (!img.isNullOrBlank()) {
                            img = img.replace(Regex("webp/w\\d+/h\\d+"), "webp/w600/h338").replace("/q75/", "/q85/")
                        }
                        val desc = el.selectFirst("p.card_card-description__0PSTi")?.text()?.trim() ?: ""
                        val epNum = epTitle.replace(Regex("[^0-9]"), "").toIntOrNull() ?: pageNum

                        episodes += newEpisode(href) {
                            name = epTitle
                            posterUrl = img
                            episode = epNum
                            description = desc
                        }
                    }
                    pageNum++
                    delay(120)
                } catch (e: Exception) {
                    more = false
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        } catch (e: Exception) {
            throw ErrorLoadingException("Dizi/program yüklenemedi: ${e.message}")
        }
    }

    // ----------------------------
    // Live Channel Responses
    // ----------------------------
    private suspend fun loadIndividualChannel(url: String): LoadResponse {
        val tvChannels = getTvChannels()
        val radioChannels = getRadioChannels()
        val allChannels: List<LiveChannel> = tvChannels + radioChannels

        val channel = allChannels.find { it.streamUrl.equals(url, ignoreCase = true) }

        val episode = newEpisode(url) {
            name = channel?.name ?: "TRT Canlı Yayın"
            posterUrl = channel?.logoUrl
            description = channel?.description
            episode = 1
            season = 1
        }

        return newTvSeriesLoadResponse(
            name = channel?.name ?: "TRT Canlı Yayın",
            url = url,
            type = TvType.Live,
            episodes = listOf(episode)
        ) {
            this.posterUrl = channel?.logoUrl ?: "https://www.trt.net.tr/logos/our-logos/corporate/trt.png"
            this.plot = channel?.description ?: "TRT canlı TV veya radyo yayını"
        }
    }

    private fun buildLiveTVResponse(channels: List<TvChannel>): LoadResponse {
        val episodes = channels.mapIndexed { i, ch ->
            newEpisode(ch.streamUrl) {
                name = ch.name
                posterUrl = ch.logoUrl
                description = ch.description
                episode = i + 1
                season = 1
            }
        }
        return newTvSeriesLoadResponse("TRT TV Kanalları", dummyTvUrl, TvType.Live, episodes) {
            this.posterUrl = "https://www.trt.net.tr/logos/our-logos/corporate/trt.png"
            this.plot = "${channels.size} TRT TV kanalı"
            this.year = 1964
        }
    }

    private fun buildLiveRadioResponse(channels: List<RadioChannel>): LoadResponse {
        val episodes = channels.mapIndexed { i, ch ->
            newEpisode(ch.streamUrl) {
                name = ch.name
                posterUrl = ch.logoUrl
                description = ch.description
                episode = i + 1
                season = 1
            }
        }
        return newTvSeriesLoadResponse("TRT Radyo Kanalları", dummyRadioUrl, TvType.Live, episodes) {
            this.posterUrl = "https://www.trtdinle.com/trt-dinle-fb-share.jpg"
            this.plot = "${channels.size} TRT radyo kanalı"
            this.year = 1927
        }
    }

    // ----------------------------
    // Link extraction
    // ----------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.contains(".m3u8", ignoreCase = true) || data.contains(".aac", ignoreCase = true)) {
            callback.invoke(ExtractorLink(name, name, data, referer = tabiiUrl, quality = Qualities.Unknown.value))
            return true
        }

        if (data.contains("trtcocuk.net.tr/video", ignoreCase = true)) {
            try {
                val doc = app.get(data, timeout = 12).document

                // YouTube embed
                doc.selectFirst("iframe[src*='youtube.com/embed'], iframe[src*='youtube-nocookie.com/embed']")?.let { iframe ->
                    val src = iframe.attr("src")
                    val v = src.substringAfter("embed/").substringBefore("?")
                    if (v.isNotBlank()) {
                        loadExtractor("https://www.youtube.com/watch?v=$v", trtCocukBase, subtitleCallback, callback)
                        return true
                    }
                }

                // Direct source
                doc.selectFirst("video source[src], video[src], source[src]")?.attr("src")?.takeIf { it.isNotBlank() }?.let { src ->
                    val resolved = if (src.startsWith("http")) src else makeAbsolute(trtCocukBase, src)
                    callback.invoke(ExtractorLink(name, name, resolved, trtCocukBase, Qualities.Unknown.value))
                    return true
                }

                // m3u8/mp4 in scripts
                doc.select("script").forEach { script ->
                    val html = script.html()
                    Regex("""https?://[^\s"']+\.(m3u8|mp4)[^\s"']*""").find(html)?.value?.let { link ->
                        callback.invoke(ExtractorLink(name, name, link, trtCocukBase, Qualities.Unknown.value))
                        return true
                    }
                }
            } catch (e: Exception) {
                Log.e("TRT", "trtcocuk loadLinks error: ${e.message}")
            }
        }

        // TRT1 player
        try {
            val doc = app.get(data, timeout = 12).document
            doc.select("script").forEach { script ->
                val html = script.html()
                Regex("""https?://[^\s"']+\.m3u8[^\s"']*""").find(html)?.value?.let { link ->
                    callback.invoke(ExtractorLink(name, name, link, trt1Url, Qualities.Unknown.value))
                    return true
                }
            }

            // YouTube fallback
            doc.selectFirst("iframe[src*='youtube.com/embed']")?.attr("src")?.let { src ->
                val v = src.substringAfter("embed/").substringBefore("?")
                if (v.isNotBlank()) {
                    loadExtractor("https://www.youtube.com/watch?v=$v", tabiiUrl, subtitleCallback, callback)
                    return true
                }
            }
        } catch (e: Exception) {
            Log.e("TRT", "loadLinks error: ${e.message}")
        }

        return false
    }

    // ----------------------------
    // Search
    // ----------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()

        val allLive: List<LiveChannel> = getTvChannels() + getRadioChannels()
        allLive.filter { it.name.contains(query, ignoreCase = true) }.forEach { ch ->
            out += newTvSeriesSearchResponse(ch.name, ch.streamUrl, TvType.Live) {
                this.posterUrl = ch.logoUrl
            }
        }

        try {
            getTrtCocukVideos().filter { it.name.contains(query, ignoreCase = true) }.forEach { out += it }
        } catch (_: Exception) {}

        try {
            val sUrl = "$trt1Url/arama/$query?contenttype=series"
            val doc = app.get(sUrl, timeout = 12).document
            doc.select("div.grid_grid-wrapper__elAnh > div.h-full.w-full > a").forEach { el ->
                val title = el.selectFirst("div.card_card-title__IJ9af")?.text()?.trim() ?: return@forEach
                val hrefRaw = el.attr("href")
                if (!hrefRaw.contains("/diziler/") && !hrefRaw.contains("/programlar/")) return@forEach
                val href = makeAbsolute(trt1Url, hrefRaw)
                var poster = el.selectFirst("img")?.absUrl("src")
                if (!poster.isNullOrBlank()) {
                    poster = poster.replace(Regex("webp/w\\d+/h\\d+"), "webp/w600/h338").replace("/q75/", "/q85/")
                }
                out += newTvSeriesSearchResponse(title, href) { this.posterUrl = poster }
            }
        } catch (_: Exception) {}

        return out
    }
}
