package com.kreastream

import com.lagradost.cloudstream3.Plugin
import com.lagradost.cloudstream3.main
import com.kreastream.modules.Trt1Parser
import com.kreastream.modules.TrtLive

class TrMain : Plugin() {
    override fun load() {
        // register all modules here
        registerMainAPI(Trt1Parser())
        registerMainAPI(TrtLive())
    }
}
