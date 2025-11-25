package com.kreastream

object ChannelRegistry {
    data class ChannelInfo(
        val id: String,
        val title: String,
        val sections: Map<String, String>
    )
}
