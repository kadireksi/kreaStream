package com.kreastream.helpers

import org.jsoup.nodes.Element
import org.jsoup.nodes.Document

// extension on Element to mimic smartSelect used in Trt.kt style code
fun Element.smartSelect(selectorStr: String?): String? {
    if (selectorStr.isNullOrEmpty()) return null
    if (selectorStr.startsWith("self@")) {
        val attr = selectorStr.substringAfter("self@")
        return if (attr == "text") this.text().trim() else this.attr(attr).trim()
    }
    val parts = selectorStr.split("@")
    val css = parts[0]
    val attr = if (parts.size > 1) parts[1] else "text"
    val el = this.selectFirst(css) ?: return null
    return if (attr == "text") el.text().trim() else el.attr(attr).trim()
}

fun Document.selectTextAll(css: String, attr: String = "text"): List<String> {
    return this.select(css).map { e -> if (attr == "text") e.text().trim() else e.attr(attr).trim() }.filter { it.isNotEmpty() }
}
