package com.kreastream

import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.PluginManifest
import com.lagradost.cloudstream3.plugins.PluginContext
import com.lagradost.cloudstream3.plugins.registerMainAPI
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@PluginManifest(
    name = "TR Plugin",
    description = "TRT Diziler + Canlı Yayınlar",
    version = 1,
    author = "kreaStream"
)
@CloudstreamPlugin
class TrPlugin : Plugin() {
    override fun load(context: PluginContext) {
        // Register parsers (submodules)
        registerMainAPI(Trt1Parser())
        registerMainAPI(TrtLive())
    }
}
