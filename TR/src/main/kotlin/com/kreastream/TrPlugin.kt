package com.kreastream

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

class TrPlugin : Plugin() {
    override fun load(context: PluginContext) {
        registerMainAPI(Trt1Parser())
        registerMainAPI(TrtLive())
    }
}
