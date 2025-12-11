package com.kreastream.helpers

object UrlHelper {
    fun join(base: String, path: String): String {
        if (path.startsWith("http")) return path
        val b = base.trimEnd('/')
        val p = path.trimStart('/')
        return "$b/$p"
    }

    fun buildPagedUrl(base: String, path: String, page: Int): String {
        var p = path
        if (p.contains("{page}")) p = p.replace("{page}", page.toString())
        else if (page > 1) p = if (p.contains("?")) "$p&page=$page" else "$p?page=$page"
        return join(base, p)
    }

    fun buildEpisodePageUrl(base: String, detailUrl: String, suffix: String, page: Int): String {
        if (suffix.isNotEmpty()) {
            val clean = detailUrl.trimEnd('/') + suffix
            return if (page > 1) "$clean?page=$page" else clean
        }
        return if (page > 1) "$detailUrl?page=$page" else detailUrl
    }
}
