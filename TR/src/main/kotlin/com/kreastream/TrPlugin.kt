package com.kreastream

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class TrPlugin : Plugin() {
    override fun load(context: Context) {
        // Register your unified API manually
        addMainAPI(TrMain())
    }
}
