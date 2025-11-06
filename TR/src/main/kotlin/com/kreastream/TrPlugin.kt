package com.kreastream

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.registerMainAPI

@CloudstreamPlugin
class TrPlugin : Plugin() {
    override fun load(context: Context) {
        // Register a single unified main API
        registerMainAPI(TrMain())
    }
}
