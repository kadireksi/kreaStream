package com.kreastream

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class TurkTVPlugin : Plugin() {
    override fun load(context: Context) {
        // Register the main hub
        registerMainAPI(TurkTV())
        
        // You can still register individual parsers if needed,
        // but TurkTV will handle most functionality
        registerMainAPI(Trt1())
        registerMainAPI(TrtLive())
        
        // Register more channels as you create them:
        registerMainAPI(StarTv())
        registerMainAPI(Atv())
        registerMainAPI(ShowTv())
    }
}