package com.kreastream

data class StreamItem(
    val id: String,
    val title: String,
    val group: String? = "TV",
    val url: String,
    val poster: String? = null,
    val type: String? = "tv",
    val headers: Map<String, String> = emptyMap(),
    val is_audio: Boolean = false,
    val genres: String = ""
)

data class ChannelConfig(
    val id: String,
    val name: String,
    val base_url: String,
    val active: Boolean = true,
    val headers: Map<String, String> = emptyMap(),
    val listings: List<ListingConfig> = emptyList(),
    val detail_page: DetailConfig? = null,
    val episode_page: EpisodePageConfig? = null,
    val video_extraction: VideoExtractionConfig? = null
)

data class ListingConfig(
    val title: String,
    val path: String,
    val selectors: Map<String, String>,
    val ishorizontal: Boolean = false
)

data class DetailConfig(val selectors: Map<String, String>)
data class EpisodePageConfig(val path_suffix: String = "", val selectors: Map<String, String> = emptyMap(), val reverse_order: Boolean = false)
data class VideoExtractionConfig(val methods: List<ExtractionMethod> = emptyList())
data class ExtractionMethod(val type: String, val selector: String? = null, val pattern: String? = null, val attribute: String? = null)
