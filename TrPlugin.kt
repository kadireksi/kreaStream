package com.kreastream

import com.lagradost.cloudstream3.Plugin

class TrPlugin : Plugin() {
    override fun load(context: PluginContext) {
        registerMainAPI(Trt1Parser())
        registerMainAPI(TrtLive())
    }
}
