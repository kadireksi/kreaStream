package com.kreastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import kotlinx.coroutines.delay
import android.util.Log
import java.net.URI

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

    private fun makeAbsolute(base: String, href: String): String {
        return try {
            if (href.startsWith("http")) return href
            URI(base).resolve(href).toString()
        } catch (e: Exception) {
            if (href.startsWith("/")) "${URL(base).let { "${it.protocol}://${it.host}" }}$href" else href
        }
    }

    private suspend fun getTvChannels(): List<TvChannel> {
        val result = mutableListOf<TvChannel>()
        try {
            val response = app.get("$liveBase/trt1?trackId=150002")
            val json = response.document.selectFirst("#__NEXT_DATA__")?.data()?.let { JSONObject(it) } ?: return emptyList()
            val liveChannels = json.getJSONObject("props").getJSONObject("pageProps").getJSONArray("liveChannels")

            for (i in 0 until liveChannels.length()) {
                val ch = liveChannels.getJSONObject(i)
                val name = ch.optString("title")
                val slug = ch.optString("slug")
                if (name.contains("tabii", ignoreCase = true)) continue

                val logoUrl = ch.optJSONArray("images")
                    ?.let { arr -> (0 until arr.length()).firstNotNullOfOrNull { arr.getJSONObject(it).takeIf { it.optString("imageType") == "logo" } } }
                    ?.optString("name")?.takeIf { it.isNotBlank() }
                    ?.let { "https://cms-tabii-public-image.tabii.com/int/$it" } ?: continue

                val streamUrl = ch.optJSONArray("media")
                    ?.let { arr -> (0 until arr.length()).firstNotNullOfOrNull { arr.getJSONObject(it).takeIf { it.optString("type") == "hls" && it.optString("drmSchema") == "clear" } } }
                    ?.optString("url")?.takeIf { it.isNotBlank() } ?: continue

                result += TvChannel(name, slug, streamUrl, logoUrl, name)
            }
        } catch (e: Exception) {
            Log.e("TRT", "getTvChannels error: ${e.message}")
        }
        return result
    }

    private fun getRadioChannels(): List<RadioChannel> = listOf(
        RadioChannel("TRT FM", "trt-fm", "https://trt.radyotvonline.net/trt_fm.aac", "https://cdn-i.pr.trt.com.tr/trtdinle/w480/h360/q70/12467418.jpeg", "Türkçe Pop"),
        RadioChannel("TRT Radyo 1", "trt-radyo-1", "https://trt.radyotvonline.net/trt_1.aac", "https://cdn-i.pr.trt.com.tr/trtdinle/w480/h360/q70/12467415.jpeg", "Haber ve kültür"),
        RadioChannel("TRT Nağme", "trt-nagme", "https://trt.radyotvonline.net/trt_nagme.aac", "https://cdn-i.pr.trt.com.tr/trtdinle/w480/h360/q70/12467419.jpeg", "Türk sanat müziği"),
        RadioChannel("TRT Türkü", "trt-turku", "https://trt.radyotvonline.net/trt_turku.aac", "https://cdn-i.pr.trt.com.tr/trtdinle/w480/h360/q70/12467420.jpeg", "Halk müziği"),
        RadioChannel("TRT Radyo 3", "trt-radyo-3", "https://trt.radyotvonline.net/trt_3.aac", "https://cdn-i.pr.trt.com.tr/trtdinle/w480/h360/q70/12467416.jpeg", "Yabancı müzik"),
        RadioChannel("TRT Kurdi", "trt-kurdi", "https://rd-trtkurdi.medya.trt.com.tr/master_128.m3u8", "https://cdn-i.pr.trt.com.tr/trtdinle/w480/h360/q70/12467508.jpeg", "Kürtçe yayın")
    )

    private suspend fun getTrtCocukVideos(page: Int = 1): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        val url = if (page <= 1) "$trtCocukBase/video" else "$trtCocukBase/video?page=$page"
        try {
            val doc = app.get(url, timeout = 15).document
            doc.select("a[href*='/video/']").forEach { el ->
                val href = makeAbsolute(trtCocukBase, el.attr("href"))
                if (out.any { it.url == href }) return@forEach
                val title = el.selectFirst("h3, h2, .title, .card-title")?.text()
                    ?: el.attr("title").takeIf { it.isNotBlank() }
                    ?: el.text().trim().takeIf { it.isNotBlank() } ?: return@forEach

                var poster = el.selectFirst("img")?.absUrl("src") ?: el.parent()?.selectFirst("img")?.absUrl("src")
                poster = poster?.replace(Regex("webp/w\\d+/h\\d+"), "webp/w600/h338")

                out += newTvSeriesSearchResponse(title, href) { this.posterUrl = poster }
            }
        } catch (e: Exception) {
            Log.e("TRT", "getTrtCocukVideos failed: ${e.message}")
        }
        return out
    }

    private suspend fun loadTrtCocukVideoPage(url: String): LoadResponse = newTvSeriesLoadResponse("TRT Çocuk", url, TvType.TvSeries) {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1, .page-title")?.text() ?: "TRT Çocuk Video"
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content") ?: doc.selectFirst("img")?.absUrl("src")
        val desc = doc.selectFirst("meta[name=description]")?.attr("content").orEmpty()

        plot = desc
        this.posterUrl = poster
        addEpisode(newEpisode(url) { name = title; this.posterUrl = poster; description = desc })
    }

    private suspend fun getTrtContent(archive: Boolean, isSeries: Boolean, page: Int): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        val path = if (isSeries) "diziler" else "programlar"
        val url = if (page == 1) "$trt1Url/$path?archive=$archive&order=title_asc"
        else "$trt1Url/$path/$page?archive=$archive&order=title_asc"

        try {
            app.get(url, timeout = 15).document.select("div.grid_grid-wrapper__elAnh > div.h-full.w-full > a").forEach { el ->
                val title = el.selectFirst("div.card_card-title__IJ9af")?.text()?.trim() ?: return@forEach
                val href = makeAbsolute(trt1Url, el.attr("href"))
                var poster = el.selectFirst("img")?.absUrl("src")
                poster = poster?.replace(Regex("webp/w\\d+/h\\d+"), "webp/w600/h338")?.replace("/q75/", "/q85/")
                out += newTvSeriesSearchResponse(title, href) { this.posterUrl = poster }
            }
        } catch (e: Exception) {
            Log.e("TRT", "getTrtContent failed: ${e.message}")
        }
        return out
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = when (request.data) {
            "live" -> listOf(
                newTvSeriesSearchResponse("TRT TV Kanalları", dummyTvUrl, TvType.Live) {
                    posterUrl = "https://www.trt.net.tr/logos/our-logos/corporate/trt.png"
                    year = 1964
                },
                newTvSeriesSearchResponse("TRT Radyo Kanalları", dummyRadioUrl, TvType.Live) {
                    posterUrl = "https://www.trtdinle.com/trt-dinle-fb-share.jpg"
                    year = 1927
                }
            )
            "series" -> getTrtContent(false, true, page)
            "archive" -> getTrtContent(true, true, page)
            "programs" -> getTrtContent(false, false, page)
            "programs_archive" -> getTrtContent(true, false, page)
            "trtcocuk_videos" -> getTrtCocukVideos(page)
            else -> emptyList()
        }

        val hasNext = request.data in setOf("series", "archive", "programs", "programs_archive", "trtcocuk_videos") && items.isNotEmpty()
        return newHomePageResponse(HomePageList(request.name, items, true), hasNext)
    }

    override suspend fun load(url: String): LoadResponse {
        when {
            url == dummyTvUrl -> return newTvSeriesLoadResponse("TRT TV Kanalları", url, TvType.Live) {
                posterUrl = "https://www.trt.net.tr/logos/our-logos/corporate/trt.png"
                plot = "Tüm TRT TV kanalları"
                getTvChannels().forEachIndexed { i, ch ->
                    addEpisode(newEpisode(ch.streamUrl) {
                        name = ch.name; posterUrl = ch.logoUrl; description = ch.description; episode = i + 1
                    })
                }
            }
            url == dummyRadioUrl -> return newTvSeriesLoadResponse("TRT Radyo Kanalları", url, TvType.Live) {
                posterUrl = "https://www.trtdinle.com/trt-dinle-fb-share.jpg"
                plot = "Tüm TRT radyo kanalları"
                getRadioChannels().forEachIndexed { i, ch ->
                    addEpisode(newEpisode(ch.streamUrl) {
                        name = ch.name; posterUrl = ch.logoUrl; description = ch.description; episode = i + 1
                    })
                }
            }
            url.contains(".m3u8", true) || url.contains(".aac", true) -> {
                val all = getTvChannels() + getRadioChannels()
                val ch = all.find { it.streamUrl.equals(url, true) }
                return newTvSeriesLoadResponse(ch?.name ?: "TRT Canlı", url, TvType.Live) {
                    posterUrl = ch?.logoUrl ?: "https://www.trt.net.tr/logos/our-logos/corporate/trt.png"
                    plot = ch?.description ?: "TRT canlı yayın"
                    addEpisode(newEpisode(url) { name = ch?.name ?: "Canlı Yayın"; posterUrl = ch?.logoUrl })
                }
            }
            url.contains("trtcocuk.net.tr/video", true) -> return loadTrtCocukVideoPage(url)
            else -> {
                val doc = app.get(url).document
                val title = doc.selectFirst("h1")?.text()?.trim() ?: throw ErrorLoadingException("Başlık yok")
                val plot = doc.selectFirst("meta[name=description]")?.attr("content").orEmpty()
                var poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
                poster = poster?.replace(Regex("webp/w\\d+/h\\d+"), "webp/w600/h338")?.replace("/q75/", "/q85/")

                val isSeries = url.contains("/diziler/")
                val isProgram = url.contains("/programlar/")
                if (!isSeries && !isProgram) {
                    return newTvSeriesLoadResponse(title, url, TvType.TvSeries) {
                        this.posterUrl = poster; this.plot = plot
                        addEpisode(newEpisode(url) { name = title; description = plot })
                    }
                }

                val slug = url.removePrefix(if (isSeries) "$trt1Url/diziler/" else "$trt1Url/programlar/").substringBefore("/")
                val episodes = mutableListOf<Episode>()
                var pageNum = 1
                while (pageNum <= 30) {
                    val epUrl = "$trt1Url/${if (isSeries) "diziler" else "programlar"}/$slug/bolum" + if (pageNum > 1) "/$pageNum" else ""
                    try {
                        val epDoc = app.get(epUrl, timeout = 10).document
                        val cards = epDoc.select("div.grid_grid-wrapper__elAnh > div.h-full.w-full > a")
                        if (cards.isEmpty()) break
                        cards.forEach { el ->
                            val epTitle = el.selectFirst("div.card_card-title__IJ9af")?.text()?.trim() ?: return@forEach
                            val href = makeAbsolute(trt1Url, el.attr("href"))
                            val img = el.selectFirst("img")?.absUrl("src")?.replace(Regex("webp/w\\d+/h\\d+"), "webp/w600/h338")?.replace("/q75/", "/q85/")
                            val desc = el.selectFirst("p.card_card-description__0PSTi")?.text()?.trim().orEmpty()
                            val epNum = epTitle.replace(Regex("\\D"), "").toIntOrNull() ?: pageNum
                            episodes += newEpisode(href) { name = epTitle; posterUrl = img; episode = epNum; description = desc }
                        }
                        pageNum++
                        delay(120)
                    } catch (e: Exception) { break }
                }

                return newTvSeriesLoadResponse(title, url, TvType.TvSeries) {
                    this.posterUrl = poster; this.plot = plot
                    addEpisodes(episodes)
                }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.contains(".m3u8", true) || data.contains(".aac", true)) {
            callback(newExtractorLink {
                name = "TRT"
                url = data
                referer = tabiiUrl
                quality = Qualities.Unknown.value
            })
            return true
        }

        if (data.contains("trtcocuk.net.tr/video", true)) {
            try {
                val doc = app.get(data).document

                doc.selectFirst("iframe[src*='youtube.com/embed']")?.attr("src")?.let { src ->
                    val v = src.substringAfter("embed/").substringBefore("?")
                    if (v.isNotBlank()) loadExtractor("https://www.youtube.com/watch?v=$v", trtCocukBase, subtitleCallback, callback)
                }

                doc.select("video source, video[src], source[src]").firstOrNull()?.attr("src")?.takeIf { it.isNotBlank() }?.let { src ->
                    callback(newExtractorLink { name = "TRT Çocuk"; url = makeAbsolute(trtCocukBase, src); referer = trtCocukBase })
                    return true
                }

                doc.select("script").forEach { s ->
                    Regex("""https?://[^\s"']+\.(m3u8|mp4)[^\s"']*""").find(s.html())?.value?.let { link ->
                        callback(newExtractorLink { name = "TRT Çocuk"; url = link; referer = trtCocukBase })
                        return true
                    }
                }
            } catch (e: Exception) {
                Log.e("TRT", "TRT Çocuk link hatası: ${e.message}")
            }
        }

        try {
            val doc = app.get(data).document
            doc.select("script").forEach { s ->
                Regex("""https?://[^\s"']+\.m3u8[^\s"']*""").find(s.html())?.value?.let { link ->
                    callback(newExtractorLink { name = "TRT"; url = link; referer = trt1Url })
                    return true
                }
            }

            doc.selectFirst("iframe[src*='youtube.com/embed']")?.attr("src")?.let { src ->
                val v = src.substringAfter("embed/").substringBefore("?")
                if (v.isNotBlank()) loadExtractor("https://www.youtube.com/watch?v=$v", tabiiUrl, subtitleCallback, callback)
            }
        } catch (e: Exception) {
            Log.e("TRT", "loadLinks error: ${e.message}")
        }

        return false
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        (getTvChannels() + getRadioChannels())
            .filter { it.name.contains(query, true) }
            .forEach { out += newTvSeriesSearchResponse(it.name, it.streamUrl, TvType.Live) { posterUrl = it.logoUrl } }

        getTrtCocukVideos().filter { it.name.contains(query, true) }.forEach { out += it }

        try {
            app.get("$trt1Url/arama/$query?contenttype=series").document
                .select("div.grid_grid-wrapper__elAnh > div.h-full.w-full > a")
                .forEach { el ->
                    val title = el.selectFirst("div.card_card-title__IJ9af")?.text()?.trim() ?: return@forEach
                    val href = makeAbsolute(trt1Url, el.attr("href"))
                    if (!href.contains("/diziler/") && !href.contains("/programlar/")) return@forEach
                    val poster = el.selectFirst("img")?.absUrl("src")?.replace(Regex("webp/w\\d+/h\\d+"), "webp/w600/h338")
                    out += newTvSeriesSearchResponse(title, href) { this.posterUrl = poster }
                }
        } catch (_: Exception) {}

        return out
    }
}
