package com.kreastream

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class TrPlugin : Plugin() {
    override fun load() {
        registerMainAPI(TrtMain())
    }
}

