package com.kreastream

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

class TrPlugin : Plugin() {
    override fun load() {
        registerMainAPI(Trt1Parser())
        registerMainAPI(TrtLive())
    }
}
