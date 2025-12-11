package com.kreastream.helpers

import com.lagradost.cloudstream3.*
import com.kreastream.*

object EpisodeHelper {
    suspend fun loadDetails(api: MainAPI, ch: ChannelConfig, url: String): LoadResponse {
        val doc = api.app.get(url, headers = ch.headers).document
        val titleSelector = ch.detail_page?.selectors?.get("title") ?: ""
        val title = if (titleSelector.isNotEmpty()) doc.selectFirst(titleSelector)?.text() ?: ch.name else ch.name

        val episodes = loadEpisodes(api, ch, url)
        return api.newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            val posterSel = ch.detail_page?.selectors?.get("poster") ?: ""
            this.posterUrl = if (posterSel.isNotEmpty()) doc.selectFirst(posterSel)?.attr("src") else null
            val descSel = ch.detail_page?.selectors?.get("description") ?: ""
            this.plot = if (descSel.isNotEmpty()) doc.selectFirst(descSel)?.text() else null
        }
    }

    private suspend fun loadEpisodes(api: MainAPI, ch: ChannelConfig, url: String): List<Episode> {
        val cfg = ch.episode_page ?: return emptyList()
        val eps = mutableListOf<Episode>()
        val seen = mutableSetOf<String>()
        for (p in 1..5) {
            val pageUrl = UrlHelper.buildEpisodePageUrl(ch.base_url, url, cfg.path_suffix, p)
            val doc = api.app.get(pageUrl, headers = ch.headers).document
            val container = cfg.selectors["container"] ?: ""
            if (container.isEmpty()) break
            val rows = doc.select(container)
            if (rows.isEmpty()) break
            for (r in rows) {
                val name = r.smartSelect(cfg.selectors["name"]) ?: r.text()
                val href = r.smartSelect(cfg.selectors["url"]) ?: ""
                if (href.isBlank()) continue
                val full = UrlHelper.join(ch.base_url, href)
                if (seen.add(full)) {
                    eps.add(Episode(full, name))
                }
            }
        }
        if (cfg.reverse_order) eps.reverse()
        return eps
    }
}
