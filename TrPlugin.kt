package com.kreastream

import com.lagradost.cloudstream3.Plugin
import com.lagradost.cloudstream3.main

class TrPlugin : Plugin() {
    override fun load() {
        // Register all available Turkish sources here
        registerMainAPI(Trt1Parser())
        registerMainAPI(TrtLive())

        // Later you’ll simply add:
        // registerMainAPI(AtvParser())
        // registerMainAPI(ShowTVParser())
        // registerMainAPI(NowTVParser())
    }
}
