package com.kreastream

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class TurkTVPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(TurkTV())
    }
}