package com.kreastream

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.registerMainAPI   // ✅  this exists in every build

@CloudstreamPlugin
class TrPlugin : Plugin() {
    override fun load(context: Context) {
        // use the safe, old-style registrar
        this.registerMainAPI(TrMain())
    }
}
