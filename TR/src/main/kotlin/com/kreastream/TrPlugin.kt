package com.kreastream

import com.lagradost.cloudstream3.Plugin

class TrPlugin : Plugin() {
    override fun load() {
        registerMainAPI(Trt1Parser())
        registerMainAPI(TrtLive())
    }
}
