package com.kreastream

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.registerMainAPI

@CloudstreamPlugin
class TrPlugin : Plugin() {
    override fun load(context: Context) {
        // Register TRT1 and Live sources
        registerMainAPI(Trt1Parser())
        registerMainAPI(TrtLive())
    }
}
