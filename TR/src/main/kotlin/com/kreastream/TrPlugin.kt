package com.kreastream

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class TrPlugin : Plugin() {
    override fun load(context: Context) {
        // use the safe, old-style registrar
        this.registerMainAPI(TrMain())
    }
}
