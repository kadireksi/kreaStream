package com.kreastream

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class TrPlugin : Plugin() {
    override val name = "TR Stream"
    override val description = "TRT Diziler + Canlı Yayınlar"
    override val version = 1
    override val author = "kreaStream"
    override fun load(context: Context) {
        // Register parsers (submodules)
        registerMainAPI(Trt1Parser())
        registerMainAPI(TrtLive())
    }
}
